package com.example.maxscraper

import android.content.Context
import android.os.Build
import android.webkit.WebSettings

/**
 * Utility for generating a reliable WebView-compatible User-Agent string.
 * Some hosts are sensitive to unexpected UA formats, so prefer the system
 * default and sanitize any control characters that may leak through.
 */
object UserAgent {

    fun defaultForWeb(context: Context): String {
        val raw = runCatching { WebSettings.getDefaultUserAgent(context) }
            .getOrElse {
                System.getProperty("http.agent")
                    ?: "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
            }
        return buildString(raw.length) {
            raw.forEach { ch ->
                when {
                    ch <= '\u001f' || ch == '\u007f' -> append(' ')
                    else -> append(ch)
                }
            }
        }.trim()
    }
}
