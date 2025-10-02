package com.example.maxscraper

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.concurrent.thread

class M3u8Activity : AppCompatActivity() {

    data class Row(
        val url: String,
        val quality: String,
        val durationSec: Int,
        val sizeBytes: Long,
        var thumb: Bitmap? = null,
        var checked: Boolean = false
    )

    private lateinit var recycler: RecyclerView
    private lateinit var downloadBtn: Button
    private val rows = mutableListOf<Row>()
    private lateinit var adapter: RowsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_m3u8)

        recycler = findViewById(R.id.m3u8Recycler)
        downloadBtn = findViewById(R.id.downloadSelectedButton)

        adapter = RowsAdapter(rows)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        recycler.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        val urls = intent.getStringArrayListExtra("EXTRA_M3U8_LIST") ?: arrayListOf()
        if (urls.isEmpty()) {
            Toast.makeText(this, "No media detected", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Load meta for each provided URL
        thread {
            val out = mutableListOf<Row>()
            urls.distinct().forEach { u ->
                try {
                    val variants = M3u8Meta.inspect(this, u)
                    if (variants.isEmpty()) {
                        out += Row(url = u, quality = "Auto", durationSec = 0, sizeBytes = -1)
                    } else {
                        variants.forEach { v ->
                            out += Row(
                                url = v.url,
                                quality = v.quality,
                                durationSec = v.durationSec,
                                sizeBytes = v.sizeBytes
                            )
                        }
                    }
                } catch (_: Throwable) {
                    out += Row(url = u, quality = "Auto", durationSec = 0, sizeBytes = -1)
                }
            }
            runOnUiThread {
                rows.clear(); rows.addAll(out)
                adapter.notifyDataSetChanged()
            }
        }

        downloadBtn.setOnClickListener {
            val selected = rows.filter { it.checked }
            if (selected.isEmpty()) {
                Toast.makeText(this, "Select at least one quality", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            selected.forEach { r ->
                val name = ensureMp4Name(guessName(r.url))
                HlsToMp4Service.start(this, r.url, name)
            }
            Toast.makeText(this, "Starting downloads…", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun guessName(u: String): String {
        val last = u.substringAfterLast('/').substringBefore('?').substringBefore('#')
        val base = last.ifBlank { "video" }.substringBeforeLast('.', last)
        return base.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }
    private fun ensureMp4Name(n: String) = if (n.lowercase().endsWith(".mp4")) n else "$n.mp4"

    inner class RowsAdapter(private val data: MutableList<Row>) : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_media_option, parent, false)
            return VH(v)
        }
        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(data[position])
        override fun getItemCount(): Int = data.size
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val thumb: ImageView = view.findViewById(R.id.thumb)
        private val title: TextView = view.findViewById(R.id.title)
        private val meta: TextView = view.findViewById(R.id.meta)
        private val check: CheckBox = view.findViewById(R.id.check)

        fun bind(r: Row) {
            title.text = r.quality.ifBlank { "Auto" }
            val dur = if (r.durationSec > 0) " • ${formatDuration(r.durationSec.toLong())}" else ""
            val size = if (r.sizeBytes > 0) " • ~${formatSize(r.sizeBytes)}" else ""
            meta.text = "${r.url.substringBefore('?')}$dur$size"

            check.setOnCheckedChangeListener(null)
            check.isChecked = r.checked
            check.setOnCheckedChangeListener { _, isChecked -> r.checked = isChecked }

            if (r.thumb != null) {
                thumb.setImageBitmap(r.thumb)
            } else {
                // Best-effort HLS thumbnail
                HlsThumbs.loadInto(thumb, r.url)
            }
        }

        private fun formatSize(bytes: Long): String {
            val kb = 1024.0; val mb = kb * 1024; val gb = mb * 1024
            return when {
                bytes >= gb -> String.format("%.2f GB", bytes / gb)
                bytes >= mb -> String.format("%.1f MB", bytes / mb)
                bytes >= kb -> String.format("%.1f KB", bytes / kb)
                else -> "$bytes B"
            }
        }
        private fun formatDuration(sec: Long): String {
            var s = sec
            val h = (s / 3600).toInt(); s %= 3600
            val m = (s / 60).toInt(); s %= 60
            return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s.toInt())
        }
    }
}
