package com.example.maxscraper

import android.app.Activity
import android.content.Intent

/**
 * Simple helper for returning to the main entry activity from anywhere in the app.
 */
fun Activity.navigateHome() {
    if (this is MainActivity) {
        // Already on the landing page; nothing to do.
        return
    }

    val intent = Intent(this, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    startActivity(intent)
    finish()
}
