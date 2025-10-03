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
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    fun clear() {
        map.clear()
        notifyListeners()
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun isIgnoredHost(h: String?): Boolean = h != null && ignoredHosts.any { h.endsWith(it) }

    fun report(url: String) {
        val L = url.lowercase()
        if (L.endsWith(".ts")) return
        val isHls = MediaFilter.isProbableHls(url)
        val isMp4 = MediaFilter.isProbableMp4(url)
        if (!isHls && !isMp4) return
        try {
            val host = URI(url).host?.lowercase()
            if (isIgnoredHost(host)) return
        } catch (_: Throwable) {}
        val added = map.putIfAbsent(url, MediaHit(url)) == null
        if (added) notifyListeners()
    }

    fun reportPlaying(url: String) {
        report(url)
        val hit = map[url]
        if (hit != null && !hit.playing) {
            hit.playing = true
            notifyListeners()
        }
    }

    fun getCandidates(): List<String> {
        return map.values.sortedWith(
            compareByDescending<MediaHit> { it.playing }
                .thenByDescending { it.ts }
        ).map { it.url }
    }

    private fun notifyListeners() {
        listeners.forEach { it.invoke() }
    }
}
