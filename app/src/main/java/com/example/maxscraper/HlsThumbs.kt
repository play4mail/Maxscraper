package com.example.maxscraper

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.widget.ImageView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Best-effort thumbnail for HLS by probing first media segment.
 * Pure java.net so you don't need OkHttp.
 */
object HlsThumbs {
    fun loadInto(view: ImageView, m3u8Url: String) {
        Thread {
            val bmp = tryMakeThumb(view, m3u8Url)
            (view.context as? android.app.Activity)?.runOnUiThread {
                if (bmp != null) view.setImageBitmap(bmp)
            }
        }.start()
    }

    private fun tryMakeThumb(view: ImageView, m3u8Url: String): Bitmap? {
        if (m3u8Url.endsWith(".mp4", true)) return frameFromUrl(m3u8Url)
        val playlist = fetchText(m3u8Url) ?: return null

        // First non-comment line
        val first = playlist.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
        val firstAbs = absolutize(m3u8Url, first)

        val mediaText = if (firstAbs.endsWith(".m3u8", true)) fetchText(firstAbs) else null
        val segUrl = mediaText?.let {
            it.lineSequence().map { s -> s.trim() }.firstOrNull { s -> s.isNotEmpty() && !s.startsWith("#") }?.let { s -> absolutize(firstAbs, s) }
        } ?: firstAbs

        return frameFromUrl(segUrl)
    }

    private fun fetchText(url: String): String? {
        return try {
            val u = URL(url)
            val c = u.openConnection() as HttpURLConnection
            c.requestMethod = "GET"
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.inputStream.use { i ->
                BufferedReader(InputStreamReader(i)).use { br ->
                    br.readText()
                }
            }
        } catch (_: Throwable) { null }
    }

    private fun frameFromUrl(url: String): Bitmap? {
        return try {
            val mmr = MediaMetadataRetriever()
            try { mmr.setDataSource(url, mapOf("User-Agent" to "Mozilla/5.0 (Android)")) }
            catch (_: Throwable) { mmr.setDataSource(url) }
            val bmp = mmr.getFrameAtTime(0)
            mmr.release()
            bmp
        } catch (_: Throwable) { null }
    }

    private fun absolutize(baseUrl: String, ref: String): String {
        return try { URI(baseUrl).resolve(ref).toString() } catch (_: Throwable) { ref }
    }
}
