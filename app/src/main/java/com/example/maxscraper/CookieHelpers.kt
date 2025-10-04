package com.example.maxscraper

import android.webkit.CookieManager

/**
 * Collect cookies for the target [url] and (if necessary) fall back to cookies from [referer].
 * Some CDNs (e.g. Instagram's) require first-party cookies from the page host in order to
 * authorise the media request even though the media itself is served from a different domain.
 */
fun gatherCookies(url: String?, referer: String?): String? {
    val mgr = CookieManager.getInstance()
    val collected = linkedSetOf<String>()

    fun addFrom(target: String?) {
        if (target.isNullOrBlank()) return
        val raw = runCatching { mgr.getCookie(target) }.getOrNull().orEmpty()
        if (raw.isBlank()) return
        raw.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { collected += it }
    }

    addFrom(url)
    addFrom(referer)

    return if (collected.isEmpty()) null else collected.joinToString(separator = "; ")
}
