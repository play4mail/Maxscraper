package com.example.maxscraper.ui

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle

// Safely show a dialog only while the Activity is alive and RESUMED.
fun AlertDialog.Builder.showIfActive(activity: AppCompatActivity) {
    if (activity.isFinishing || activity.isDestroyed) return
    if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return

    val dialog = create()
    dialog.setOnShowListener {
        if (activity.isFinishing || activity.isDestroyed ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            dialog.dismiss()
        }
    }
    dialog.show()
}
