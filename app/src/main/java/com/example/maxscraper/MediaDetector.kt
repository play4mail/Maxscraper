package com.example.maxscraper

import java.net.URI
import java.util.concurrent.ConcurrentHashMap

data class MediaHit(
    val url: String,
    val ts: Long = System.currentTimeMillis(),
    var playing: Boolean = false
)

object MediaDetector {
    private val ignoredHosts = setOf(
        "redirector.gvt1.com","v12-a.sdn.cz","stream.highwebmedia.com",
        "doppiocdn.com","doppiocdn.org","hesads.akamaized.net","dmxleo.dailymotion.com"
    )
    private val map = ConcurrentHashMap<String, MediaHit>() // url -> hit

    fun clear() = map.clear()

    fun isIgnoredHost(h: String?): Boolean = h != null && ignoredHosts.any { h.endsWith(it) }

    fun report(url: String) {
        val L = url.lowercase()
        if (L.endsWith(".ts")) return
        if (!(L.contains(".m3u8") || L.endsWith(".mp4"))) return
        try {
            val host = URI(url).host?.lowercase()
            if (isIgnoredHost(host)) return
        } catch (_: Throwable) {}
        map.putIfAbsent(url, MediaHit(url))
    }

    fun reportPlaying(url: String) {
        report(url)
        map[url]?.let { it.playing = true }
    }

    fun getCandidates(): List<String> {
        return map.values.sortedWith(
            compareByDescending<MediaHit> { it.playing }
                .thenByDescending { it.ts }
        ).map { it.url }
    }
}
