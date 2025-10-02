package com.example.maxscraper

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.*
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import android.view.*
import android.widget.*
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri // KTX .toUri()
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView


/**
 * Completed downloads list with delete options:
 *  - Remove from app only (untrack; keep file/Gallery)
 *  - Delete from device (and Gallery), with robust path:
 *      DownloadManager -> remove()
 *      MediaStore item -> createDeleteRequest()
 *      file:// or non-MediaStore content -> resolve to MediaStore then delete
 *      SAF content -> DocumentFile.delete()
 */
class CompletedFragment : Fragment() {

    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
    private val adapter = Rows()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refresh() }
    }

    // Launcher for MediaStore delete confirmations (Android 11+)
    private val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            refresh()
        }
    }

    @SuppressLint("SetTextI18n") // keep literals; avoids forcing strings.xml edits right now
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = FrameLayout(requireContext())

        recycler = RecyclerView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@CompletedFragment.adapter
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
        }
        empty = TextView(requireContext()).apply {
            text = "No completed downloads"
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        (context ?: requireContext()).also {
            ContextCompat.registerReceiver(
                it, receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        root.addView(recycler)
        root.addView(empty)
        refresh()
        return root
    }

    override fun onDestroyView() {
        try { requireContext().unregisterReceiver(receiver) } catch (_: Throwable) {}
        super.onDestroyView()
    }

    private fun refresh() {
        val ctx = requireContext()
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        val rows = LinkedHashMap<String, Row>() // key by Uri string

        // DownloadManager successes (keep existing logic)
        val ids = DownloadTracker.allIds(ctx)
        if (ids.isNotEmpty()) {
            var c: Cursor? = null
            try {
                c = dm.query(DownloadManager.Query().setFilterById(*ids))
                if (c != null) while (c.moveToNext()) {
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (status != DownloadManager.STATUS_SUCCESSFUL) continue
                    val title = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: "Download"
                    val local = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)) ?: continue
                    val uri = local.toUri() // KTX
                    val size = realSize(ctx, uri)
                    if (size > 0L) rows[uri.toString()] = Row(
                        title, uri, size,
                        dmId = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                    )
                }
            } finally { try { c?.close() } catch (_: Throwable) {} }
        }

        // HLS conversions tracked by your app (CompletedStore)
        CompletedStore.all(ctx).forEach { item ->
            val size = if (item.size > 0) item.size else realSize(ctx, item.uri)
            if (size > 0L) rows[item.uri.toString()] = Row(item.title, item.uri, size, dmId = null)
        }

        val list = rows.values.toMutableList()
        adapter.submit(list)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    data class Row(val title: String, val uri: Uri, val sizeBytes: Long, val dmId: Long?)

    inner class Rows : RecyclerView.Adapter<RowVH>() {
        private var data: List<Row> = emptyList()

        fun submit(newData: List<Row>) {
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = data.size
                override fun getNewListSize() = newData.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    // Stable key: the uri string
                    return data[oldItemPosition].uri == newData[newItemPosition].uri
                }
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return data[oldItemPosition] == newData[newItemPosition]
                }
            })
            data = newData
            diff.dispatchUpdatesTo(this)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowVH {
            val v = layoutInflater.inflate(R.layout.item_completed_download, parent, false)
            return RowVH(v)
        }
        override fun getItemCount(): Int = data.size
        override fun onBindViewHolder(holder: RowVH, position: Int) = holder.bind(data[position])

        fun removeAt(pos: Int) {
            if (pos !in data.indices) return
            val newList = data.toMutableList()
            newList.removeAt(pos)
            submit(newList)
        }
    }

    inner class RowVH(v: View) : RecyclerView.ViewHolder(v) {
        private val thumb: ImageView = v.findViewById(R.id.thumb)
        private val title: TextView = v.findViewById(R.id.title)
        private val subtitle: TextView = v.findViewById(R.id.subtitle)
        private val btnDelete: Button = v.findViewById(R.id.btnDelete)

        fun bind(r: Row) {
            title.text = r.title
            subtitle.text = formatSize(r.sizeBytes)
            loadThumbInto(thumb, r.uri)

            itemView.setOnClickListener {
                val ctx = itemView.context
                val i = Intent(ctx, PlayerActivity::class.java)
                    .setData(r.uri)
                    .putExtra(PlayerActivity.EXTRA_TITLE, r.title)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ctx.startActivity(i)
            }

            btnDelete.setOnClickListener { showDeleteOptions(r) }
        }

        private fun showDeleteOptions(r: Row) {
            val ctx = itemView.context
            android.app.AlertDialog.Builder(ctx)
                .setTitle("Remove or delete?")
                .setMessage("Choose what to do with this video.")
                .setNegativeButton("Remove from app only") { d, _ ->
                    // Untrack (keeps the file)
                    try { r.dmId?.let { DownloadTracker.remove(ctx, it) } } catch (_: Throwable) {}
                    try { CompletedStore.removeByUri(ctx, r.uri) } catch (_: Throwable) {}
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) adapter.removeAt(pos)
                    d.dismiss()
                }
                .setPositiveButton("Delete from device") { d, _ ->
                    deleteFromDevice(r)
                    d.dismiss()
                }
                .setNeutralButton("Cancel", null)
                .show()
        }

        private fun deleteFromDevice(r: Row) {
            val ctx = itemView.context
            var handled = false

            // 1) DownloadManager-managed file -> remove() deletes the file
            val id = r.dmId
            if (id != null) {
                try {
                    val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    dm.remove(id)
                    DownloadTracker.remove(ctx, id)
                    handled = true
                } catch (_: Throwable) {}
            }

            // 2) Try MediaStore delete (show dialog on Android 11+)
            if (!handled) {
                val mediaUri = when {
                    r.uri.scheme == "content" && (r.uri.authority ?: "").contains("com.android.providers.media") -> r.uri
                    else -> resolveMediaStoreUri(ctx, r.uri) // resolve file:// or generic content://
                }

                if (mediaUri != null) {
                    try {
                        if (Build.VERSION.SDK_INT >= 30) {
                            val req = MediaStore.createDeleteRequest(ctx.contentResolver, listOf(mediaUri))
                            deleteLauncher.launch(IntentSenderRequest.Builder(req.intentSender).build())
                            handled = true // user will confirm; refresh happens in result
                        } else {
                            val rows = ctx.contentResolver.delete(mediaUri, null, null)
                            handled = rows > 0
                        }
                    } catch (_: Throwable) {}
                }
            }

            // 3) SAF or unknown content provider
            if (!handled && r.uri.scheme == "content") {
                try {
                    DocumentFile.fromSingleUri(ctx, r.uri)?.let { doc ->
                        if (doc.delete()) handled = true
                    }
                } catch (_: Throwable) {}
            }

            // 4) Plain file:// path
            if (!handled && r.uri.scheme == "file") {
                try {
                    val f = java.io.File(r.uri.path ?: "")
                    if (f.exists() && f.delete()) handled = true
                } catch (_: Throwable) {}
            }

            // Post-delete bookkeeping + rescan
            if (handled) {
                try { CompletedStore.removeByUri(ctx, r.uri) } catch (_: Throwable) {}
                kickMediaScan(ctx, r.uri)
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) adapter.removeAt(pos)
            } else {
                Toast.makeText(ctx, "Could not delete from device. If Gallery still has it, open this video from Files and delete there.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- Helpers ----------

    /**
     * Resolve a file:// or generic content:// (e.g., Downloads) into a concrete
     * MediaStore content URI so we can delete via createDeleteRequest().
     */
    private fun resolveMediaStoreUri(ctx: Context, original: Uri): Uri? {
        return try {
            when (original.scheme) {
                "content" -> {
                    // If it's Downloads/other, try to find matching item by DISPLAY_NAME + SIZE
                    val (name, size) = probeNameAndSize(ctx, original)
                    queryMediaStoreByNameAndSize(ctx, name, size)
                }
                "file" -> {
                    val path = original.path ?: return null
                    // First, try Videos; then Images; then Audio
                    findMediaByAbsolutePath(ctx, path, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                        ?: findMediaByAbsolutePath(ctx, path, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                        ?: findMediaByAbsolutePath(ctx, path, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
                }
                else -> null
            }
        } catch (_: Throwable) { null }
    }

    /** Query MediaStore by absolute path (legacy devices where DATA is populated). */
    private fun findMediaByAbsolutePath(ctx: Context, absolutePath: String, table: Uri): Uri? {
        val cr = ctx.contentResolver
        val cols = arrayOf(MediaColumns._ID, MediaColumns.DATA)
        val sel = "${MediaColumns.DATA}=?"
        val args = arrayOf(absolutePath)
        cr.query(table, cols, sel, args, null)?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                return Uri.withAppendedPath(table, id.toString())
            }
        }
        return null
    }

    /** For scoped storage devices: match by DISPLAY_NAME + SIZE. */
    private fun queryMediaStoreByNameAndSize(ctx: Context, name: String?, size: Long?): Uri? {
        if (name.isNullOrBlank() || size == null || size <= 0) return null
        val tables = arrayOf(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        )
        val cr = ctx.contentResolver
        val cols = arrayOf(MediaColumns._ID, MediaColumns.DISPLAY_NAME, MediaColumns.SIZE)
        val sel = "${MediaColumns.DISPLAY_NAME}=? AND ${MediaColumns.SIZE}=?"
        val args = arrayOf(name, size.toString())
        for (table in tables) {
            cr.query(table, cols, sel, args, null)?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    return Uri.withAppendedPath(table, id.toString())
                }
            }
        }
        return null
    }

    /** Extract DISPLAY_NAME and SIZE for a generic content:// or file:// */
    private fun probeNameAndSize(ctx: Context, uri: Uri): Pair<String?, Long?> {
        return try {
            when (uri.scheme) {
                "content" -> {
                    ctx.contentResolver.query(uri, arrayOf(MediaColumns.DISPLAY_NAME, MediaColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val name = c.getString(0)
                            val size = if (!c.isNull(1)) c.getLong(1) else null
                            return name to size
                        }
                    }
                    null to null
                }
                "file" -> {
                    val f = java.io.File(uri.path ?: return null to null)
                    f.name to f.length()
                }
                else -> null to null
            }
        } catch (_: Throwable) { null to null }
    }

    /** Kick a media scan for the uri / its file path so Gallery updates. */
    private fun kickMediaScan(ctx: Context, uri: Uri) {
        try {
            if (uri.scheme == "file") {
                val path = uri.path ?: return
                MediaScannerConnection.scanFile(ctx, arrayOf(path), null, null)
            } else if (uri.scheme == "content") {
                val path = try {
                    ctx.contentResolver.query(uri, arrayOf(MediaColumns.DATA), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                } catch (_: Throwable) { null }
                if (!path.isNullOrBlank()) {
                    MediaScannerConnection.scanFile(ctx, arrayOf(path), null, null)
                }
            }
        } catch (_: Throwable) {}
    }

    private fun loadThumbInto(iv: ImageView, uri: Uri) {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(requireContext(), uri)
            val bmp = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            retriever.release()
            if (bmp != null) iv.setImageBitmap(bmp)
        } catch (_: Throwable) { /* leave placeholder */ }
    }

    private fun formatSize(bytes: Long): String {
        val kb = 1024.0; val mb = kb * 1024; val gb = mb * 1024
        val df = java.text.DecimalFormat("#,##0.00")
        return when {
            bytes >= gb -> df.format(bytes / gb) + " GB"
            bytes >= mb -> df.format(bytes / mb) + " MB"
            bytes >= kb -> df.format(bytes / kb) + " KB"
            else -> "$bytes B"
        }
    }

    private fun realSize(ctx: Context, uri: Uri): Long {
        return try {
            when (uri.scheme) {
                "content" -> {
                    val c = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
                    c?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
                }
                "file" -> java.io.File(uri.path ?: "").length()
                else -> 0L
            }
        } catch (_: Throwable) { 0L }
    }
}
