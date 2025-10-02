package com.example.maxscraper

import android.net.Uri
import java.util.Locale

object MediaFilter {

    private val BIN_EXTS = setOf("bin", "dat", "octet-stream")
    private val AUDIO_HINTS = listOf("mime=audio", "audio/mp4", "itag=140")
    private val DASH_HINTS = listOf("/dash/", ".m4s", ".mpd")
    private val MP4_HINTS = listOf(
        ".mp4", "mime=video%2fmp4", "mime=video/mp4",
        "type=video%2fmp4", "format=mp4", "videoplayback"
    )

    fun isProbableHls(url: String): Boolean =
        url.contains(".m3u8", ignoreCase = true)

    fun isProbableMp4(url: String): Boolean {
        val low = url.lowercase(Locale.US)
        if (low.startsWith("blob:")) return false
        if (AUDIO_HINTS.any { low.contains(it) }) return false
        if (DASH_HINTS.any { low.contains(it) }) return false
        val ext = fileExt(low)
        if (ext in BIN_EXTS) return false
        if (ext == "mp4") return true
        if (MP4_HINTS.any { low.contains(it) }) return true
        return false
    }

    private fun fileExt(u: String): String {
        return try {
            val seg = Uri.parse(u).lastPathSegment
            if (seg.isNullOrEmpty()) "" else {
                val i = seg.lastIndexOf('.')
                if (i >= 0 && i < seg.length - 1) seg.substring(i + 1).lowercase(Locale.US) else ""
            }
        } catch (_: Throwable) { "" }
    }
}
