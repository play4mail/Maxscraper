package com.example.maxscraper

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL

object Mp4Meta {
    fun headSize(url: String): Long {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "HEAD"
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.getHeaderFieldLong("Content-Length", -1L)
        } catch (_: Throwable) { -1L }
    }

    fun durationMs(context: Context, url: String): Long {
        return try {
            val r = MediaMetadataRetriever()
            if (Build.VERSION.SDK_INT >= 14) r.setDataSource(url, HashMap())
            else r.setDataSource(url)
            (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: "0").toLong()
        } catch (_: Throwable) { 0L }
    }
}
