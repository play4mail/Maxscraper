package com.example.maxscraper.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import com.example.maxscraper.R

/**
 * Utility to present a thumbnail list. Call this from BrowserActivity when you’ve built your
 * list of URLs. You can override IDs if your item_media_option.xml uses different view ids.
 */
object MediaPicker {

    fun show(
        ctx: Context,
        title: String,
        options: List<MediaOption>,
        onChosen: (MediaOption) -> Unit,
        // If your XML IDs differ, pass them here:
        rowLayoutRes: Int = R.layout.item_media_option,
        thumbId: Int = R.id.thumb,
        titleId: Int = R.id.title,
        metaId: Int = R.id.meta
    ) {
        val adapter = MediaOptionAdapter(
            items = options,
            onClick = { opt -> onChosen(opt) },
            rowLayout = rowLayoutRes,
            thumbId = thumbId,
            titleId = titleId,
            metaId = metaId
        )

        val dialog = adapter.asDialog(AlertDialog.Builder(ctx).setTitle(title))
        dialog.setOnDismissListener { adapter.cleanup() }

        val activity = ctx as? AppCompatActivity
        if (activity != null) {
            if (activity.isFinishing || activity.isDestroyed ||
                !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            ) {
                adapter.cleanup()
                return
            }

            dialog.setOnShowListener {
                if (activity.isFinishing || activity.isDestroyed ||
                    !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                ) {
                    dialog.dismiss()
                }
            }
        }

        dialog.show()
    }
}
