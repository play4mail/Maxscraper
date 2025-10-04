package com.example.maxscraper

import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class MediaHit(
    val url: String,
    val ts: Long = System.currentTimeMillis(),
    var playing: Boolean = false,
    var playingSince: Long = 0L
)

object MediaDetector {
    private const val MAX_TRACKED = 200
    private val ignoredHostSuffixes = setOf(
        "redirector.gvt1.com",
        "v12-a.sdn.cz",
        "stream.highwebmedia.com",
        "doppiocdn.com",
        "doppiocdn.org",
        "hesads.akamaized.net",
        "dmxleo.dailymotion.com",
        "dailymotion.com",
        "googleads.g.doubleclick.net",
        "doubleclick.net",
        "pagead2.googlesyndication.com",
        "googlesyndication.com",
        "hotstar.com",
        "nsfw.xxx"
    )
    private val ignoredHostKeywords = setOf(
        "googleads",
        "doubleclick",
        "nsfw",
        "hotstar"
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

    fun isIgnoredHost(h: String?): Boolean {
        if (h.isNullOrBlank()) return false
        val low = h.lowercase(Locale.US)
        if (ignoredHostSuffixes.any { low == it || low.endsWith(".$it") }) return true
        if (ignoredHostKeywords.any { low.contains(it) }) return true
        return false
    }

    fun report(url: String) {
        val L = url.lowercase()
        if (L.endsWith(".ts")) return
        val isHls = MediaFilter.isProbableHls(url)
        val isMp4 = MediaFilter.isProbableMp4(url)
        if (!isHls && !isMp4) return
        try {
            val host = URI(url).host?.lowercase(Locale.US)
            if (isIgnoredHost(host)) return
        } catch (_: Throwable) {}
        val added = map.putIfAbsent(url, MediaHit(url)) == null
        if (added) {
            trimIfNeeded()
            notifyListeners()
        }
    }

    fun reportPlaying(url: String) {
        report(url)
        val hit = map[url] ?: return
        var changed = false
        val now = System.currentTimeMillis()
        val wasPlaying = hit.playing
        hit.playing = true
        hit.playingSince = now
        if (!wasPlaying) changed = true
        map.values.forEach { other ->
            if (other !== hit && other.playing) {
                other.playing = false
                other.playingSince = 0L
                changed = true
            }
        }
        if (changed) notifyListeners()
    }

    fun getCandidates(): List<String> {
        val hits = map.values.toList()
        val playing = hits.filter { it.playing }
            .sortedByDescending { it.playingSince }
        val rest = hits.filterNot { it.playing }
            .sortedByDescending { it.ts }
        return (playing + rest).map { it.url }
    }

    fun getPlayingUrls(): List<String> {
        return map.values.filter { it.playing }
            .sortedByDescending { it.playingSince }
            .map { it.url }
    }

    private fun notifyListeners() {
        listeners.forEach { it.invoke() }
    }

    private fun trimIfNeeded() {
        val overflow = map.size - MAX_TRACKED
        if (overflow <= 0) return

        var remaining = overflow

        fun removeTargets(targets: List<MediaHit>) {
            for (hit in targets) {
                if (remaining <= 0) return
                if (map.remove(hit.url, hit)) {
                    remaining--
                }
            }
        }

        // Prefer removing the oldest non-playing hits first.
        val nonPlaying = map.values
            .filter { !it.playing }
            .sortedBy { it.ts }
        removeTargets(nonPlaying)

        if (remaining > 0) {
            // Fall back to trimming the oldest entries overall.
            val oldest = map.values
                .sortedBy { it.ts }
            removeTargets(oldest)
        }
    }
}
