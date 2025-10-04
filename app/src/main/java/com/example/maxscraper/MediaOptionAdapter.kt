package com.example.maxscraper.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.maxscraper.HlsThumbs
import com.example.maxscraper.R
import com.example.maxscraper.VideoVariant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Row model for the media chooser dialog. */
data class MediaOption(
    val url: String,
    val label: String,
    val thumbUrl: String? = null,
    val meta: String? = null,
    val isHls: Boolean = false,
    val variants: List<VideoVariant> = emptyList()
)

/** RecyclerView adapter that renders your existing item_media_option.xml. */
class MediaOptionAdapter(
    private val items: List<MediaOption>,
    private val onClick: (MediaOption) -> Unit,
    @LayoutRes private val rowLayout: Int = R.layout.item_media_option,
    @IdRes private val thumbId: Int = R.id.thumb,
    @IdRes private val titleId: Int = R.id.title,
    @IdRes private val metaId: Int = R.id.meta
) : RecyclerView.Adapter<MediaOptionAdapter.VH>() {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val thumbCache = ConcurrentHashMap<String, Bitmap>()
    private val missingThumbs = ConcurrentHashMap.newKeySet<String>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(rowLayout, parent, false)
        return VH(v, thumbId, titleId, metaId)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val option = items[position]

        holder.title?.text = option.label

        holder.meta?.let { tv ->
            if (option.meta.isNullOrBlank()) {
                tv.visibility = View.GONE
                tv.text = ""
            } else {
                tv.visibility = View.VISIBLE
                tv.text = option.meta
            }
        }

        holder.thumb?.let { iv ->
            iv.setImageDrawable(null)
            scope.launch {
                val bmp = loadThumbnail(option)
                withContext(Dispatchers.Main) {
                    val currentPos = holder.adapterPosition
                    if (currentPos != RecyclerView.NO_POSITION && currentPos == position && bmp != null) {
                        iv.setImageBitmap(bmp)
                    }
                }
            }
        }

        // IMPORTANT: pass the MediaOption, not the View
        holder.itemView.setOnClickListener { onClick(option) }
    }

    /** Present this adapter inside an AlertDialog. */
    fun asDialog(builder: AlertDialog.Builder): AlertDialog {
        val rv = RecyclerView(builder.context).apply {
            layoutManager = LinearLayoutManager(builder.context)
            adapter = this@MediaOptionAdapter
        }
        builder.setView(rv).setNegativeButton(android.R.string.cancel, null)
        return builder.create()
    }

    /** Call when dialog dismisses to stop background work. */
    fun cleanup() {
        thumbCache.clear()
        missingThumbs.clear()
        scope.cancel()
    }

    class VH(
        v: View,
        @IdRes thumbId: Int,
        @IdRes titleId: Int,
        @IdRes metaId: Int
    ) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView? = v.findViewById(thumbId)
        val title: TextView?  = v.findViewById(titleId)
        val meta: TextView?   = v.findViewById(metaId)
    }

    private fun loadThumbnail(option: MediaOption): Bitmap? {
        thumbCache[option.url]?.let { return it }
        if (missingThumbs.contains(option.url)) return null

        val bmp = option.thumbUrl?.let { fetchImageBitmap(it) }
            ?: fetchVideoBitmap(option)

        if (bmp != null) {
            thumbCache[option.url] = bmp
        } else {
            missingThumbs += option.url
        }
        return bmp
    }

    private fun fetchImageBitmap(urlStr: String): Bitmap? {
        return try {
            val url = URL(urlStr)
            val c = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "*/*")
            }
            c.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun fetchVideoBitmap(option: MediaOption): Bitmap? {
        val candidate = when {
            option.isHls -> option.variants.firstOrNull()?.url ?: option.url
            else -> option.url
        }
        return HlsThumbs.fetchBitmap(candidate)
    }
}
