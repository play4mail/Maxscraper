package com.example.maxscraper

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object MediaStoreSaver {

    /**
     * Copies a finished file into the public Downloads collection so the user
     * sees it in Files/Downloads. Scoped-storage safe on Android 10+.
     */
    fun saveToDownloads(ctx: Context, from: File, displayName: String, mime: String): File {
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Cannot create Downloads entry")
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                from.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IllegalStateException("Cannot open output stream")
            // Mark as complete
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            ctx.contentResolver.update(uri, values, null, null)
            // Best-effort file path for convenience (not strictly required)
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(dir, displayName)
        } else {
            val out = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), displayName)
            from.copyTo(out, overwrite = true)
            out
        }
    }
}
