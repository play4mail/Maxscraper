package com.example.maxscraper.net

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebSettings
import kotlinx.coroutines.*
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import com.example.maxscraper.Http
import java.nio.channels.FileChannel

object ParallelDownloader {

    data class Options(
        val maxParts: Int = 6,
        val minPartBytes: Long = 2L * 1024 * 1024,
        val maxPartBytes: Long = 32L * 1024 * 1024,
        val connectTimeoutSec: Long = 20,
        val readTimeoutSec: Long = 120
    )

    interface Listener {
        fun onStart(totalBytes: Long) {}
        fun onProgress(bytesDownloaded: Long, totalBytes: Long) {}
        fun onComplete(file: File) {}
        fun onError(t: Throwable) {}
    }

    private data class ProbeResult(
        val supportsRanges: Boolean,
        val totalBytes: Long,
        val contentType: String?
    )

    suspend fun download(
        ctx: Context,
        url: String,
        outFile: File,
        referer: String? = null,
        opts: Options = Options(),
        listener: Listener? = null
    ) = withContext(Dispatchers.IO) {
        val probe = probeRange(ctx, url, referer, opts)

        val total = probe.totalBytes
        if (total > 0) listener?.onStart(total)

        if (!probe.supportsRanges || total <= 0) {
            // Single-connection fallback (handles IG and finicky CDNs)
            try {
                singleConnectionDownload(ctx, url, outFile, referer, listener)
                listener?.onComplete(outFile)
                return@withContext
            } catch (t: Throwable) {
                if (outFile.exists()) outFile.delete()
                listener?.onError(t)
                throw t
            }
        }

        // Parallel path
        val partSize = clampPartSize(total, opts.minPartBytes, opts.maxPartBytes, opts.maxParts)
        val ranges = buildRanges(total, partSize)

        RandomAccessFile(outFile, "rw").use { raf -> raf.setLength(total) }

        val aggregate = AtomicLong(0L)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        try {
            coroutineScope {
                ranges.map { r ->
                    async(scope.coroutineContext) {
                        downloadRangeToFile(ctx, url, referer, outFile, r) { inc ->
                            val done = aggregate.addAndGet(inc)
                            listener?.onProgress(done, total)
                        }
                    }
                }.awaitAll()
            }
            if (aggregate.get() != total) throw IllegalStateException("Downloaded ${aggregate.get()} of $total bytes")
            listener?.onComplete(outFile)
        } catch (ce: CancellationException) {
            if (outFile.exists()) outFile.delete()
            throw ce
        } catch (t: Throwable) {
            // Any failure during parallel? fall back seamlessly to single stream.
            try {
                singleConnectionDownload(ctx, url, outFile, referer, listener)
                listener?.onComplete(outFile)
            } catch (t2: Throwable) {
                if (outFile.exists()) outFile.delete()
                listener?.onError(t2)
                throw t2
            }
        } finally {
            scope.cancel()
        }
    }

    // --- Probe with GET Range: 0-0 (works where HEAD fails / is blocked) ---
    private suspend fun probeRange(
        ctx: Context,
        url: String,
        referer: String?,
        opts: Options
    ): ProbeResult = withContext(Dispatchers.IO) {
        val headers = defaultHeaders(ctx, url, referer)
        val req = Request.Builder()
            .url(url)
            .get()
            .header("Range", "bytes=0-0")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()

        val client = Http.client.newBuilder()
            .connectTimeout(opts.connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(opts.readTimeoutSec, TimeUnit.SECONDS)
            .build()

        client.newCall(req).execute().use { resp ->
            val code = resp.code
            val ct = resp.header("Content-Type")
            val acceptRanges = resp.header("Accept-Ranges")?.contains("bytes", true) == true
            val (supports, total) = when (code) {
                206 -> {
                    val cr = resp.header("Content-Range")
                    val totalBytes = parseTotalFromContentRange(cr)
                    true to totalBytes
                }
                200 -> {
                    val len = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                    // 200 OK to range means server ignored Range -> treat as no ranges
                    false to len
                }
                else -> false to -1L
            }
            ProbeResult(supports || acceptRanges, total, ct)
        }
    }

    private fun parseTotalFromContentRange(cr: String?): Long {
        // Content-Range: bytes 0-0/12345
        return cr?.substringAfter('/')?.toLongOrNull() ?: -1L
    }

    private fun defaultHeaders(ctx: Context, url: String, referer: String?): Map<String, String> {
        val ua = WebSettings.getDefaultUserAgent(ctx)
        val cookie = CookieManager.getInstance().getCookie(url)
        val h = mutableMapOf(
            "User-Agent" to ua,
            "Accept" to "*/*",
            "Accept-Encoding" to "identity",
            "Accept-Language" to "en-US,en;q=0.9"
        )
        if (!referer.isNullOrBlank()) {
            h["Referer"] = referer
            try {
                val u = Uri.parse(referer)
                val origin = (u.scheme ?: "https") + "://" + (u.host ?: "")
                h["Origin"] = origin
            } catch (_: Throwable) {}
        }
        if (!cookie.isNullOrBlank()) h["Cookie"] = cookie
        return h
    }

    private data class ByteRange(val start: Long, val endInclusive: Long) {
        val header: String get() = "bytes=$start-$endInclusive"
        val length: Long get() = endInclusive - start + 1
    }

    private fun clampPartSize(total: Long, minPart: Long, maxPart: Long, maxParts: Int): Long {
        val guess = max(total / maxParts, minPart)
        return guess.coerceAtMost(maxPart)
    }

    private fun buildRanges(total: Long, partBytes: Long): List<ByteRange> {
        if (total <= partBytes) return listOf(ByteRange(0, total - 1))
        val out = ArrayList<ByteRange>()
        var pos = 0L
        while (pos < total) {
            val end = (pos + partBytes - 1).coerceAtMost(total - 1)
            out += ByteRange(pos, end)
            pos = end + 1
        }
        return out
    }

    private fun downloadRangeToFile(
        ctx: Context,
        url: String,
        referer: String?,
        outFile: File,
        range: ByteRange,
        onInc: (Long) -> Unit
    ) {
        val headers = defaultHeaders(ctx, url, referer)
        val req = Request.Builder()
            .url(url)
            .get()
            .header("Range", range.header)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()

        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} for ${range.header}")
            RandomAccessFile(outFile, "rw").channel.use { ch ->
                val map = ch.map(FileChannel.MapMode.READ_WRITE, range.start, range.length)
                val source = resp.body!!.source()
                val buffer = okio.Buffer()
                var totalRead = 0L
                while (true) {
                    val read = source.read(buffer, 256 * 1024)
                    if (read == -1L) break
                    val bytes = buffer.readByteArray(read)
                    map.put(bytes)
                    totalRead += read
                    onInc(read)
                }
                if (totalRead != range.length) {
                    throw IllegalStateException("Range ${range.header} got $totalRead/${range.length} bytes")
                }
                map.force()
            }
        }
    }

    private fun singleConnectionDownload(
        ctx: Context,
        url: String,
        outFile: File,
        referer: String?,
        listener: Listener?
    ) {
        val headers = defaultHeaders(ctx, url, referer)
        val req = Request.Builder()
            .url(url)
            .get()
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()

        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val total = resp.body?.contentLength() ?: -1L
            if (total > 0) listener?.onStart(total)
            val sink = outFile.sink().buffer()
            val src = resp.body!!.source()
            var written = 0L
            val buf = okio.Buffer()
            while (true) {
                val r = src.read(buf, 256 * 1024)
                if (r == -1L) break
                sink.write(buf, r)
                written += r
                if (total > 0) listener?.onProgress(written, total)
            }
            sink.flush()
            sink.close()
        }
    }
}
