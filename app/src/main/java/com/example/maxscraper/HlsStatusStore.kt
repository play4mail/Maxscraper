package com.example.maxscraper

/**
 * In-process snapshot of the currently running HLS conversion so the Active
 * screen can show a row immediately even before the first FFmpeg stat ticks in.
 */
object HlsStatusStore {
    data class State(
        val jobId: Long,
        var title: String,
        var percent: Int = 1,
        var bytes: Long = 0L,
        var elapsedMs: Long = 0L,
        var totalMs: Long = 0L
    )

    @Volatile private var state: State? = null

    fun setStart(jobId: Long, title: String) {
        state = State(jobId, title, percent = 1)
    }

    fun update(jobId: Long, title: String? = null, percent: Int? = null,
               bytes: Long? = null, elapsedMs: Long? = null, totalMs: Long? = null) {
        val s = state ?: return
        if (s.jobId != jobId) return
        title?.let { s.title = it }
        percent?.let { s.percent = it.coerceIn(1, 100) }
        bytes?.let { s.bytes = it.coerceAtLeast(0) }
        elapsedMs?.let { s.elapsedMs = it.coerceAtLeast(0) }
        totalMs?.let { s.totalMs = it.coerceAtLeast(0) }
    }

    fun clear(jobId: Long) {
        val s = state ?: return
        if (s.jobId == jobId) state = null
    }

    fun snapshot(): State? = state?.copy()
}
