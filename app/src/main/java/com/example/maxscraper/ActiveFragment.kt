package com.example.maxscraper

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Active downloads: DownloadManager + HLS (via HlsStatusStore) + direct downloads (DirectStatusStore).
 * Polls each once per second.
 */
class ActiveFragment : Fragment() {

    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
    private val adapter = Rows()
    private val rows = LinkedHashMap<String, Row>()
    private val ui = Handler(Looper.getMainLooper())

    // DM polling + speed estimation
    private val dmPrev = HashMap<Long, Pair<Long, Long>>() // id -> (bytesSoFar, tMs)
    private val dmPoll = object : Runnable {
        override fun run() {
            try { pollDownloadManager() } finally { ui.postDelayed(this, 1000) }
        }
    }

    // HLS polling + speed estimation
    private var lastHlsBytes: Long = -1L
    private var lastHlsTime: Long = 0L
    private val hlsPoll = object : Runnable {
        override fun run() {
            try { pollHlsStatusStore() } finally { ui.postDelayed(this, 1000) }
        }
    }

    // Direct (non-DM) polling + speed estimation
    private var lastDirectBytes: Long = -1L
    private var lastDirectTime: Long = 0L
    private val directPoll = object : Runnable {
        override fun run() {
            try { pollDirectStatus() } finally { ui.postDelayed(this, 1000) }
        }
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = FrameLayout(requireContext())

        recycler = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ActiveFragment.adapter
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
        }
        empty = TextView(requireContext()).apply {
            text = "No active downloads"
            setTextColor(Color.GRAY)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        root.addView(recycler, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(empty)
        return root
    }

    override fun onStart() {
        super.onStart()

        // Seed HLS if present
        HlsStatusStore.snapshot()?.let { s ->
            val key = "hls-${s.jobId}"
            rows[key] = Row(
                key = key,
                title = s.title.ifBlank { "Converting" },
                isHls = true,
                percent = s.percent.coerceIn(1, 100),
                bytesSoFar = s.bytes.coerceAtLeast(0),
                durationMs = s.totalMs.coerceAtLeast(0),
                etaMs = if (s.totalMs > 0 && s.elapsedMs in 1..(s.totalMs - 1))
                    (s.totalMs - s.elapsedMs) else -1L
            )
        }
        refreshList()

        ui.post(dmPoll)
        ui.post(hlsPoll)
        ui.post(directPoll)
    }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacks(dmPoll)
        ui.removeCallbacks(hlsPoll)
        ui.removeCallbacks(directPoll)
    }

    // ---- Pollers ----

    private fun pollDirectStatus() {
        val snap = DirectStatusStore.snapshot()
        if (snap == null) {
            rows.keys.filter { it.startsWith("direct-") }.forEach { rows.remove(it) }
            lastDirectBytes = -1L
            refreshList()
            return
        }
        val key = "direct-1"
        val r = rows[key] ?: Row(key, snap.title, isHls = false)
        r.title = snap.title
        r.bytesSoFar = snap.bytes
        r.totalBytes = snap.total
        r.percent = if (snap.total > 0) ((snap.bytes * 100.0 / snap.total).roundToLong().toInt()).coerceIn(1, 100) else 1
        val now = System.currentTimeMillis()
        if (lastDirectBytes >= 0L) {
            val dt = max(1L, now - lastDirectTime)
            val db = (snap.bytes - lastDirectBytes).coerceAtLeast(0L)
            val bps = (db * 1000L / dt).coerceAtLeast(0L)
            r.speedBps = bps
            r.etaMs = if (bps > 0 && snap.total > 0 && snap.bytes < snap.total)
                ((snap.total - snap.bytes) * 1000L / bps).coerceAtLeast(0L) else -1L
        }
        lastDirectBytes = snap.bytes
        lastDirectTime = now
        rows[key] = r
        refreshList()
    }

    private fun pollHlsStatusStore() {
        val snap = HlsStatusStore.snapshot()
        if (snap == null) {
            val toRemove = rows.keys.filter { it.startsWith("hls-") }
            toRemove.forEach { rows.remove(it) }
            lastHlsBytes = -1L
            refreshList()
            return
        }

        val key = "hls-${snap.jobId}"
        val r = rows[key] ?: Row(key, snap.title.ifBlank { "Converting" }, isHls = true)
        r.title = snap.title.ifBlank { "Converting" }
        r.percent = snap.percent.coerceIn(1, 100)
        r.bytesSoFar = snap.bytes.coerceAtLeast(0)
        r.totalBytes = 0L
        r.durationMs = snap.totalMs.coerceAtLeast(0)
        r.etaMs = if (snap.totalMs > 0 && snap.elapsedMs in 1..(snap.totalMs - 1))
            (snap.totalMs - snap.elapsedMs) else -1L

        val now = System.currentTimeMillis()
        if (lastHlsBytes >= 0L) {
            val dt = max(1L, now - lastHlsTime)
            val db = (snap.bytes - lastHlsBytes).coerceAtLeast(0L)
            r.speedBps = (db * 1000L / dt).coerceAtLeast(0L)
        }
        lastHlsBytes = snap.bytes
        lastHlsTime = now

        rows[key] = r
        refreshList()
    }

    private fun pollDownloadManager() {
        try {
            val dm = requireContext().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val q = DownloadManager.Query()
                .setFilterByStatus(
                    DownloadManager.STATUS_PENDING or
                            DownloadManager.STATUS_RUNNING or
                            DownloadManager.STATUS_PAUSED
                )
            val c: Cursor = dm.query(q) ?: return
            val now = System.currentTimeMillis()

            val seen = HashSet<String>()
            c.use {
                val idCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val titleCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
                val bytesCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val statusCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)

                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val title = c.getString(titleCol) ?: "Downloading"
                    val bytes = c.getLong(bytesCol).coerceAtLeast(0L)
                    val total = c.getLong(totalCol).coerceAtLeast(0L)
                    val status = c.getInt(statusCol)

                    val key = "dm-$id"
                    seen.add(key)

                    val r = rows[key] ?: Row(key, title, isHls = false, dmId = id)
                    r.title = title
                    r.bytesSoFar = bytes
                    r.totalBytes = total

                    val prev = dmPrev[id]
                    if (prev != null) {
                        val dt = max(1L, now - prev.second)
                        val db = (bytes - prev.first).coerceAtLeast(0L)
                        val bps = (db * 1000L / dt).coerceAtLeast(0L)
                        r.speedBps = bps
                        r.etaMs = if (bps > 0 && total > 0 && bytes < total)
                            ((total - bytes) * 1000L / bps).coerceAtLeast(0L) else -1L
                    }
                    dmPrev[id] = bytes to now

                    r.percent = if (total > 0) ((bytes * 100.0 / total).roundToLong().toInt()).coerceIn(1, 100) else 1
                    r.paused = (status == DownloadManager.STATUS_PAUSED)

                    rows[key] = r
                }
            }

            val toRemove = rows.keys.filter { it.startsWith("dm-") && it !in seen }
            toRemove.forEach { rows.remove(it) }

            refreshList()
        } catch (_: Throwable) { /* swallow */ }
    }

    private fun refreshList() {
        adapter.submit(rows.values.toList())
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---- UI rows/adapters ----

    data class Row(
        val key: String,
        var title: String,
        val isHls: Boolean,
        val hlsJobId: Long = -1L,
        val dmId: Long = -1L,
        var percent: Int = 1,
        var bytesSoFar: Long = 0L,
        var totalBytes: Long = 0L,
        var durationMs: Long = 0L,
        var etaMs: Long = -1L,
        var speedBps: Long = 0L,
        var paused: Boolean = false
    )

    inner class Rows : RecyclerView.Adapter<VH>() {
        private var data: List<Row> = emptyList()

        fun submit(items: List<Row>) {
            data = items
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                background = ContextCompat.getDrawable(ctx, android.R.drawable.list_selector_background)
            }
            val title = TextView(ctx).apply {
                textSize = 16f
                setTextColor(Color.WHITE)
            }
            val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
            }
            val meta = TextView(ctx).apply {
                textSize = 12f
                setTextColor(Color.LTGRAY)
            }
            row.addView(title)
            row.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(meta)
            return VH(row, title, bar, meta)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = data[position]
            holder.title.text = "Downloading ${r.title}: ${r.percent}%"
            holder.bar.progress = r.percent.coerceIn(1, 100)

            val parts = ArrayList<String>(4)
            if (r.isHls) {
                if (r.bytesSoFar > 0) parts.add(humanSize(r.bytesSoFar))
                if (r.speedBps > 0) parts.add("${humanSize(r.speedBps)}/s")
                if (r.etaMs >= 0) parts.add("${humanTime(r.etaMs)} left")
                if (r.durationMs > 0) parts.add("${humanTime(r.durationMs)} total")
            } else {
                if (r.totalBytes > 0) parts.add("${humanSize(r.bytesSoFar)} / ${humanSize(r.totalBytes)}")
                else parts.add(humanSize(r.bytesSoFar))
                if (r.speedBps > 0) parts.add("${humanSize(r.speedBps)}/s")
                if (r.etaMs >= 0) parts.add("${humanTime(r.etaMs)} left")
                if (r.paused) parts.add("(paused)")
            }
            holder.meta.text = parts.joinToString("   ")
        }

        override fun getItemCount(): Int = data.size
    }

    class VH(
        view: View,
        val title: TextView,
        val bar: ProgressBar,
        val meta: TextView
    ) : RecyclerView.ViewHolder(view)

    private fun humanSize(b: Long): String {
        val kb = 1024.0
        val mb = kb * kb
        val gb = mb * kb
        return when {
            b >= gb -> String.format("%.2f GB", b / gb)
            b >= mb -> String.format("%.2f MB", b / mb)
            b >= kb -> String.format("%.1f KB", b / kb)
            else -> "$b B"
        }
    }

    private fun humanTime(ms: Long): String {
        val total = (ms / 1000).toInt()
        val s = total % 60; val m = (total / 60) % 60; val h = total / 3600
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
