package com.example.maxscraper

/**
 * Lightweight status mirror for direct (non-DownloadManager) downloads so the Active tab
 * can display progress consistently (percent / bytes / speed / ETA).
 * Tracks a single direct job at a time (sufficient for typical IG flows).
 */
object DirectStatusStore {
    data class Snapshot(
        val title: String,
        val bytes: Long,
        val total: Long,
        val updatedAt: Long
    )

    @Volatile private var name: String = ""
    @Volatile private var bytesSoFar: Long = 0L
    @Volatile private var totalSize: Long = -1L
    @Volatile private var touched: Long = 0L

    fun start(title: String) {
        name = title
        bytesSoFar = 0L
        totalSize = -1L
        touched = System.currentTimeMillis()
    }

    fun update(bytes: Long, total: Long) {
        bytesSoFar = bytes
        totalSize = total
        touched = System.currentTimeMillis()
    }

    fun clear() {
        name = ""
        bytesSoFar = 0L
        totalSize = -1L
        touched = 0L
    }

    fun snapshot(): Snapshot? {
        if (name.isBlank()) return null
        return Snapshot(name, bytesSoFar, totalSize, System.currentTimeMillis())
    }
}
