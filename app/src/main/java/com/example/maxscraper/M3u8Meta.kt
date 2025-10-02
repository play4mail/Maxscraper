package com.example.maxscraper

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import kotlin.math.roundToLong

data class VariantMeta(
    val quality: String,    // e.g., 1080p
    val url: String,
    val durationSec: Int,
    val sizeBytes: Long
)

/**
 * Parses HLS playlists and returns only *video* variants (one entry per quality).
 * - Expands masters into variants
 * - Keeps entries ONLY if they have a real video quality (e.g., 1080p/720p…)
 * - Skips audio-only, subtitle, and I-FRAME-only playlists
 * - Drops playlists that look like ads (EXT-X-DATERANGE with ad class / “stitched-ad”)
 */
object M3u8Meta {

    fun inspect(ctx: Context, m3u8Url: String): List<VariantMeta> {
        val master = fetchText(m3u8Url) ?: return emptyList()
        if (!master.contains("#EXTM3U")) return emptyList()

        val lower = master.lowercase()
        if (lower.contains("stitched-ad") ||
            lower.contains("class=\"ad\"") ||
            lower.contains("#ext-x-daterange:class=\"ad\"")
        ) return emptyList()

        val base = baseUrl(m3u8Url)
        val out = mutableListOf<VariantMeta>()

        if (master.contains("#EXT-X-STREAM-INF")) {
            // Master playlist: walk STREAM-INF blocks and keep only video variants.
            val lines = master.lines()
            var i = 0
            while (i < lines.size) {
                val line = lines[i].trim()
                if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                    val attrs = parseAttrs(line.substringAfter(':'))
                    val codecs = attrs["CODECS"]?.lowercase() ?: ""
                    val resolution = attrs["RESOLUTION"]?.uppercase() ?: ""
                    val isVideoCodec =
                        codecs.contains("avc") || codecs.contains("hvc") ||
                                codecs.contains("hev1") || codecs.contains("hvc1") ||
                                codecs.contains("vp9") || codecs.contains("av01") ||
                                codecs.contains("h264") || codecs.contains("h265")
                    val hasResolution = resolution.isNotBlank()

                    // Skip audio-only/non-video variants
                    if (!isVideoCodec && !hasResolution) {
                        i++
                        if (i < lines.size && !lines[i].trim().startsWith("#")) i++
                        continue
                    }

                    // Next non-comment line is the media playlist URL
                    var j = i + 1
                    var nextUrl: String? = null
                    while (j < lines.size) {
                        val n = lines[j].trim()
                        if (n.isNotBlank()) {
                            if (!n.startsWith("#")) {
                                nextUrl = resolve(base, n)
                                break
                            }
                        }
                        j++
                    }

                    if (nextUrl != null) {
                        val quality = if (resolution.contains('x')) {
                            // 1920x1080 -> 1080p
                            resolution.substringAfter('x').filter { it.isDigit() } + "p"
                        } else {
                            val bw = attrs["BANDWIDTH"]?.toLongOrNull()
                            if (bw != null) estimateQualityFromBandwidth(bw) else ""
                        }
                        val dur = playlistDuration(nextUrl)
                        out += VariantMeta(quality = quality, url = nextUrl, durationSec = dur, sizeBytes = 0L)
                    }
                    i = j
                } else {
                    i++
                }
            }
        } else if (master.contains("#EXTINF")) {
            // Media playlist: only keep if we can infer a real quality from URL.
            val inferred = inferQualityFromUrl(m3u8Url)
            if (inferred.isNotBlank()) {
                val dur = playlistDuration(m3u8Url)
                out += VariantMeta(quality = inferred, url = m3u8Url, durationSec = dur, sizeBytes = 0L)
            }
        }

        // FINAL FILTER: only items with an explicit quality like 1080p/720p/etc.
        return out
            .filter { it.quality.matches(Regex("""\d{3,4}p""", RegexOption.IGNORE_CASE)) }
            .distinctBy { it.quality.lowercase() to it.url }
            .sortedByDescending { qualityHeight(it.quality) }
    }

    // ---- Helpers ----

    private fun baseUrl(u: String): String {
        return try {
            val uri = URI(u)
            val path = uri.path ?: ""
            val basePath = if (path.contains('/')) path.substring(0, path.lastIndexOf('/') + 1) else "/"
            URI(uri.scheme, uri.authority, basePath, null, null).toString()
        } catch (_: Throwable) {
            u.substringBeforeLast('/') + "/"
        }
    }

    private fun resolve(base: String, rel: String): String =
        try { URL(URL(base), rel).toString() } catch (_: Throwable) { rel }

    private fun parseAttrs(s: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        var i = 0
        var key = StringBuilder()
        var valBuf = StringBuilder()
        var inVal = false
        var inQuote = false
        fun flush() {
            if (key.isNotEmpty()) out[key.toString().trim()] = valBuf.toString().trim().trim('"')
            key = StringBuilder(); valBuf = StringBuilder(); inVal = false; inQuote = false
        }
        while (i < s.length) {
            val c = s[i]
            when {
                c == '=' && !inVal -> inVal = true
                c == '"' && inVal -> inQuote = !inQuote
                c == ',' && !inQuote -> flush()
                else -> if (!inVal) key.append(c) else valBuf.append(c)
            }
            i++
        }
        flush()
        return out
    }

    private fun fetchText(u: String, limitBytes: Int = 262_144): String? {
        return try {
            val conn = (URL(u).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Maxscraper/1.0")
                setRequestProperty("Accept", "application/vnd.apple.mpegurl,application/x-mpegURL,text/plain,*/*")
            }
            conn.connect()
            if (conn.responseCode !in 200..299) { conn.disconnect(); return null }
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charset.forName("UTF-8")))
            val sb = StringBuilder()
            var total = 0
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line!!
                total += l.length + 1
                if (total > limitBytes) break
                sb.append(l).append('\n')
            }
            reader.close()
            conn.disconnect()
            sb.toString()
        } catch (_: Throwable) { null }
    }

    private fun playlistDuration(url: String): Int {
        val text = fetchText(url, limitBytes = 1_000_000) ?: return 0
        if (text.lowercase().contains("stitched-ad")) return 0
        var total = 0.0
        for (line in text.lines()) {
            val L = line.trim()
            if (L.startsWith("#EXTINF", ignoreCase = true)) {
                val sec = L.substringAfter(":", "")
                    .substringBefore(",", "")
                    .toDoubleOrNull()
                if (sec != null) total += sec
            }
        }
        return total.roundToLong().toInt()
    }

    private fun estimateQualityFromBandwidth(bps: Long): String = when {
        bps >= 5_000_000L -> "1080p"
        bps >= 2_500_000L -> "720p"
        bps >= 1_000_000L -> "480p"
        bps >= 600_000L   -> "360p"
        else              -> "240p"
    }

    private fun qualityHeight(q: String): Int =
        q.lowercase().removeSuffix("p").toIntOrNull() ?: 0

    private fun inferQualityFromUrl(u: String): String {
        val m = Regex("""([1-9]\d{2,3})p""", RegexOption.IGNORE_CASE).find(u)
        return if (m != null) (m.groupValues[1] + "p") else ""
    }

    /** (KEEP) Rough mp4 duration probe (if ever needed elsewhere) */
    private fun mp4Duration(ctx: Context, url: String): Long {
        return try {
            val r = MediaMetadataRetriever()
            if (Build.VERSION.SDK_INT >= 14) r.setDataSource(url, HashMap())
            else r.setDataSource(url)
            (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: "0").toLong()
        } catch (_: Throwable) { 0L }
    }

    /** Optional HEAD content length probe (unused by default) */
    private fun headSize(url: String): Long {
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection)
            c.requestMethod = "HEAD"; c.connectTimeout = 6000; c.readTimeout = 6000
            c.getHeaderFieldLong("Content-Length", -1L)
        } catch (_: Throwable) { -1L }
    }
}
