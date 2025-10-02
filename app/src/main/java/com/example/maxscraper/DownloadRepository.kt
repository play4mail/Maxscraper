package com.example.maxscraper

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import com.example.maxscraper.net.ParallelDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Downloads:
 * - Instagram hosts -> always use direct downloader (keeps cookies/headers).
 * - Other hosts -> try DownloadManager; if it fails within ~3.5s, fall back to direct.
 * Direct path saves to app cache then publishes the file to public Downloads via MediaStore.
 * Active tab shows direct progress via DirectStatusStore.
 */
object DownloadRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun smartDownload(
        ctx: Context,
        url: String,
        fileName: String,
        referer: String?,
        listener: ParallelDownloader.Listener
    ) {
        scope.launch {
            val isIg = isInstagramLike(url) || isInstagramLike(referer ?: "")

            if (isIg) {
                runDirectAndPublish(ctx, url, fileName, referer, listener)
                return@launch
            }

            val dmOk = tryDownloadManagerResolvedIO(ctx, url, fileName, referer)
            if (!dmOk) {
                runDirectAndPublish(ctx, url, fileName, referer, listener)
            }
        }
    }

    // -------- Direct (ParallelDownloader) into app cache, then publish to MediaStore Downloads
    private suspend fun runDirectAndPublish(
        ctx: Context,
        url: String,
        fileName: String,
        referer: String?,
        listener: ParallelDownloader.Listener
    ) = withContext(Dispatchers.IO) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())

        // temp file in app cache
        val tmp = File(ctx.cacheDir, fileName)
        if (tmp.exists()) tmp.delete()

        DirectStatusStore.start(fileName)
        val wrap = object : ParallelDownloader.Listener {
            override fun onStart(totalBytes: Long) {
                DirectStatusStore.update(0, totalBytes)
                main.post { listener.onStart(totalBytes) }
            }
            override fun onProgress(bytesDownloaded: Long, totalBytes: Long) {
                DirectStatusStore.update(bytesDownloaded, totalBytes)
                main.post { listener.onProgress(bytesDownloaded, totalBytes) }
            }
            override fun onComplete(file: File) {
                // Publish to public Downloads
                try {
                    val published = publishToDownloads(ctx, file, fileName, "video/mp4")
                    // Clean temp
                    runCatching { file.delete() }
                    DirectStatusStore.clear()
                    // Notify completion with the display file name
                    main.post { listener.onComplete(File("/storage/emulated/0/Download/$fileName")) }
                } catch (t: Throwable) {
                    DirectStatusStore.clear()
                    main.post { listener.onError(t) }
                }
            }
            override fun onError(t: Throwable) {
                DirectStatusStore.clear()
                main.post { listener.onError(t) }
            }
        }

        try {
            ParallelDownloader.download(
                ctx = ctx,
                url = url,
                outFile = tmp,          // write into app cache first
                referer = referer,
                listener = wrap
            )
        } catch (t: Throwable) {
            DirectStatusStore.clear()
            main.post { listener.onError(t) }
        }
    }

    /**
     * Insert into MediaStore Downloads and copy bytes from [src].
     */
    private fun publishToDownloads(context: Context, src: File, displayName: String, mime: String): Uri {
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val itemUri = resolver.insert(collection, values)
            ?: throw IllegalStateException("Failed to create download entry")

        resolver.openOutputStream(itemUri).use { out ->
            requireNotNull(out) { "OutputStream null" }
            FileInputStream(src).use { input ->
                input.copyTo(out)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(itemUri, done, null, null)
        }
        return itemUri
    }

    // -------- DownloadManager path with fast failure detection
    private suspend fun tryDownloadManagerResolvedIO(
        ctx: Context,
        originalUrl: String,
        fileName: String,
        referer: String?
    ): Boolean = withContext(Dispatchers.IO) {
        val resolved = resolveFinalUrl(originalUrl, referer) ?: return@withContext false
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        val req = DownloadManager.Request(Uri.parse(resolved))
            .setMimeType("video/mp4")
            .setTitle(fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverRoaming(true)
            .setAllowedOverMetered(true)

        req.addRequestHeader("User-Agent", defaultUA())
        req.addRequestHeader("Accept", "*/*")
        req.addRequestHeader("Accept-Encoding", "identity")
        req.addRequestHeader("Accept-Language", "en-US,en;q=0.9")
        referer?.let {
            req.addRequestHeader("Referer", it)
            val u = Uri.parse(it)
            if (u.scheme != null && u.host != null) {
                req.addRequestHeader("Origin", "${u.scheme}://${u.host}")
            }
        }

        val id = try {
            dm.enqueue(req)
        } catch (_: Throwable) {
            return@withContext false
        }

        // Remember the DownloadManager task so the receiver/settings/UI can react.
        runCatching { DownloadTracker.add(ctx, id) }

        // Watch briefly for instant FAIL; if so, cancel & return false
        val start = System.currentTimeMillis()
        val maxMs = 3500L
        while (System.currentTimeMillis() - start < maxMs) {
            when (dm.singleStatus(id)) {
                DownloadManager.STATUS_FAILED -> {
                    runCatching {
                        dm.remove(id)
                        DownloadTracker.remove(ctx, id)
                    }
                    return@withContext false
                }
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PAUSED,
                DownloadManager.STATUS_PENDING -> return@withContext true
                else -> {} // keep polling
            }
            try { Thread.sleep(250) } catch (_: InterruptedException) { break }
        }
        true
    }

    private fun DownloadManager.singleStatus(id: Long): Int {
        var status = -1
        val q = DownloadManager.Query().setFilterById(id)
        var c: Cursor? = null
        try {
            c = query(q)
            if (c != null && c.moveToFirst()) {
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            }
        } catch (_: Throwable) {
        } finally {
            try { c?.close() } catch (_: Throwable) {}
        }
        return status
    }

    // -------- Resolve final CDN URL (IG often redirects)
    private fun resolveFinalUrl(startUrl: String, referer: String?): String? {
        var url = startUrl
        var hops = 0
        val cookieMgr = CookieManager.getInstance()
        val seen = HashSet<String>()

        while (hops < 5) {
            if (!seen.add(url)) break
            val u = URL(url)
            val conn = (u.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("User-Agent", defaultUA())
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Range", "bytes=0-0")
                cookieMgr.getCookie(url)?.let { setRequestProperty("Cookie", it) }
                referer?.let {
                    setRequestProperty("Referer", it)
                    val ru = Uri.parse(it)
                    if (ru.scheme != null && ru.host != null) {
                        setRequestProperty("Origin", "${ru.scheme}://${ru.host}")
                    }
                }
                connectTimeout = 15000
                readTimeout = 15000
            }

            val code = conn.responseCode
            when (code) {
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER,
                307, 308 -> {
                    val loc = conn.getHeaderField("Location") ?: return null
                    url = if (loc.startsWith("http", true)) loc else URL(URL(url), loc).toString()
                    conn.disconnect(); hops++; continue
                }
                HttpURLConnection.HTTP_OK,
                HttpURLConnection.HTTP_PARTIAL -> {
                    try { conn.inputStream.use { BufferedInputStream(it).read() } } catch (_: Throwable) {}
                    val finalUrl = conn.url.toString()
                    conn.disconnect(); return finalUrl
                }
                else -> { conn.disconnect(); return null }
            }
        }
        return url
    }

    private fun isInstagramLike(s: String): Boolean {
        if (s.isEmpty()) return false
        val host = try { Uri.parse(s).host?.lowercase(Locale.US) ?: "" } catch (_: Throwable) { "" }
        return host.contains("instagram.com") || host.contains("cdninstagram") || host.contains("fbcdn")
    }

    private fun defaultUA(): String =
        "Mozilla/5.0 (Linux; Android 13; App) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0 Mobile Safari/537.36"
}
