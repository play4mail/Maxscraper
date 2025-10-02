package com.example.maxscraper

import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URI
import java.util.Locale
import com.example.maxscraper.VideoMetadata
import com.example.maxscraper.VideoVariant


data class VideoVariant(
    val qualityLabel: String,
    val width: Int?,
    val height: Int?,
    val durationSec: Double?,
    val bandwidthbps: Long?,
    val sizeBytes: Long?,
    val url: String
)

object VideoMetadata {

    suspend fun fetchFor(url: String, headers: Map<String, String> = emptyMap()): List<VideoVariant> {
        return if (url.lowercase(Locale.US).contains(".m3u8")) {
            fetchHls(url, headers)
        } else {
            listOfNotNull(fetchMp4(url, headers))
        }
    }

    private suspend fun fetchMp4(url: String, headers: Map<String,String>): VideoVariant? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).head().apply {
                headers.forEach { (k, v) -> addHeader(k, v) }
            }.build()
            val resp = Http.client.newCall(req).execute()
            val size = resp.headers["Content-Length"]?.toLongOrNull()
            resp.close()

            val retriever = MediaMetadataRetriever()
            try { retriever.setDataSource(url, headers) } catch (_: Throwable) { retriever.setDataSource(url) }

            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            retriever.release()

            val quality = height?.let { "${it}p" } ?: "MP4"
            val durationSec = durMs?.div(1000.0)

            VideoVariant(
                qualityLabel = quality,
                width = width,
                height = height,
                durationSec = durationSec,
                bandwidthbps = null,
                sizeBytes = size,
                url = url
            )
        } catch (_: Throwable) { null }
    }

    private suspend fun fetchHls(url: String, headers: Map<String,String>): List<VideoVariant> = withContext(Dispatchers.IO) {
        try {
            val masterText = httpGetText(url, headers) ?: return@withContext emptyList()
            val isMaster = masterText.lineSequence().any { it.startsWith("#EXT-X-STREAM-INF") }
            return@withContext if (isMaster) {
                parseMasterAndVariants(url, masterText, headers)
            } else {
                val duration = sumExtInf(masterText)
                val bandwidth = extractAverageBandwidth(masterText)
                val estSize = if (duration != null && bandwidth != null) ((bandwidth * duration) / 8.0).toLong() else null
                listOf(
                    VideoVariant(
                        qualityLabel = "HLS",
                        width = null, height = null,
                        durationSec = duration,
                        bandwidthbps = bandwidth,
                        sizeBytes = estSize,
                        url = url
                    )
                )
            }
        } catch (_: Throwable) { emptyList() }
    }

    private suspend fun parseMasterAndVariants(masterUrl: String, masterText: String, headers: Map<String,String>): List<VideoVariant> {
        val base = masterUrlBase(masterUrl)
        val out = mutableListOf<VideoVariant>()
        val lines = masterText.lines()

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val attrs = parseAttrList(line.removePrefix("#EXT-X-STREAM-INF:"))
                val resolution = attrs["RESOLUTION"]
                val bandwidth = attrs["AVERAGE-BANDWIDTH"]?.toLongOrNull() ?: attrs["BANDWIDTH"]?.toLongOrNull()
                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                if (j < lines.size) {
                    val variantUri = resolveUrl(base, lines[j].trim())
                    val mediaText = httpGetText(variantUri, headers)
                    val duration = mediaText?.let { sumExtInf(it) }
                    val (w, h) = resolution?.split("x")?.let { it.getOrNull(0)?.toIntOrNull() to it.getOrNull(1)?.toIntOrNull() } ?: (null to null)
                    val qualityLabel = (h?.let { "${it}p" }) ?: "HLS"
                    val estSize = if (duration != null && bandwidth != null) ((bandwidth * duration) / 8.0).toLong() else null
                    out += VideoVariant(
                        qualityLabel = qualityLabel,
                        width = w, height = h,
                        durationSec = duration,
                        bandwidthbps = bandwidth,
                        sizeBytes = estSize,
                        url = variantUri
                    )
                }
                i = j
            } else i++
        }
        return out.sortedWith(compareByDescending<VideoVariant> { it.height ?: -1 }.thenByDescending { it.bandwidthbps ?: -1 })
    }

    private fun parseAttrList(s: String): Map<String,String> {
        val map = mutableMapOf<String,String>()
        var i = 0
        while (i < s.length) {
            val eq = s.indexOf('=', i); if (eq == -1) break
            val key = s.substring(i, eq).trim()
            var j = eq + 1
            val value: String
            if (j < s.length && s[j] == '\"') {
                j++
                val end = s.indexOf('\"', j).coerceAtLeast(s.length)
                value = s.substring(j, end)
                i = s.indexOf(',', end).let { if (it == -1) s.length else it + 1 }
            } else {
                val end = s.indexOf(',', j).let { if (it == -1) s.length else it }
                value = s.substring(j, end).trim()
                i = if (end == s.length) end else end + 1
            }
            map[key] = value
        }
        return map
    }

    private suspend fun httpGetText(url: String, headers: Map<String,String>): String? {
        val req = Request.Builder().url(url).get().apply {
            headers.forEach { (k, v) -> addHeader(k, v) }
        }.build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        }
    }

    private fun sumExtInf(playlist: String): Double? {
        var total = 0.0
        var found = false
        playlist.lineSequence().forEach { line ->
            if (line.startsWith("#EXTINF:")) {
                val secs = line.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull()
                if (secs != null) { total += secs; found = true }
            }
        }
        return if (found) total else null
    }

    private fun extractAverageBandwidth(@Suppress("UNUSED_PARAMETER") playlist: String): Long? {
        return null
    }

    private fun masterUrlBase(url: String): String {
        val uri = URI(url)
        val path = uri.path
        val basePath = path.substringBeforeLast('/', "")
        val prefix = uri.scheme + "://" + uri.host + (if (uri.port != -1) ":${uri.port}" else "")
        return if (basePath.isNotEmpty()) "$prefix/$basePath/" else "$prefix/"
    }

    private fun resolveUrl(base: String, relative: String): String {
        return if (relative.startsWith("http://") || relative.startsWith("https://")) relative
        else base + relative.removePrefix("./")
    }

    fun formatDuration(seconds: Double?): String {
        if (seconds == null) return "—"
        val total = seconds.toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = (total % 60)
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    fun formatSize(bytes: Long?): String {
        if (bytes == null) return "—"
        val kb = 1024.0; val mb = kb * 1024; val gb = mb * 1024
        return when {
            bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.2f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.2f KB", bytes / kb)
            else -> "$bytes B"
        }
    }
}
