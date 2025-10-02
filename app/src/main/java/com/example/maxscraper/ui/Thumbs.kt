package com.example.maxscraper.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.ImageView
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import coil.size.Scale

/**
 * Load a reliable video thumbnail into any ImageView.
 * Works for content:// URIs and file paths. On failure, falls back to MMDR.
 */
fun ImageView.loadVideoThumb(source: Any, frameMs: Long = 1000L) {
    // Try Coil's VideoFrameDecoder first
    val loader = ImageLoader.Builder(context)
        .components { add(VideoFrameDecoder.Factory()) }
        .crossfade(true)
        .build()

    val req = ImageRequest.Builder(context)
        .data(source)
        .allowHardware(false)
        .videoFrameMillis(frameMs)
        .scale(Scale.FILL)
        .target(
            onStart = { /* optional placeholder */ },
            onSuccess = { drawable -> this.setImageDrawable(drawable) },
            onError = {
                // Fallback to MediaMetadataRetriever for local URIs/paths
                try {
                    val mmr = MediaMetadataRetriever()
                    when (source) {
                        is Uri -> mmr.setDataSource(context, source)
                        is String -> mmr.setDataSource(source)
                        else -> throw IllegalArgumentException("Unsupported source")
                    }
                    val bmp: Bitmap? = mmr.getFrameAtTime(frameMs * 1000)
                    if (bmp != null) this.setImageBitmap(bmp)
                    mmr.release()
                } catch (_: Throwable) { /* give up silently */ }
            }
        )
        .build()

    loader.enqueue(req)
}
