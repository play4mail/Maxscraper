package com.example.maxscraper

import android.content.Context
import android.content.Intent
import java.text.DecimalFormat
import kotlin.math.roundToLong

/**
 * Simple helpers for starting/cancelling HLS conversions and a couple of formatters.
 * NOTE: Do NOT declare any class/object named HlsToMp4Service here to avoid
 * colliding with the Android Service class of the same name.
 */
object HlsHelpers {

    /**
     * Start HLS (.m3u8) -> MP4 conversion.
     * Works with the compatible Service I provided (accepts both action+keys or no action).
     */
    fun startHlsConversion(context: Context, m3u8Url: String, title: String) {
        // Preferred: use the explicit starter
        HlsToMp4Service.start(context, m3u8Url, title)

        // If you still want the old "no action" pattern somewhere else in your app,
        // it’s also supported by the service I shipped:
        // context.startService(Intent(context, HlsToMp4Service::class.java)
        //     .putExtra(HlsToMp4Service.EXTRA_URL, m3u8Url)
        //     .putExtra(HlsToMp4Service.EXTRA_TITLE, title))
    }

    /**
     * Cancel the current HLS conversion job (single-job service).
     */
    fun cancelHlsConversion(context: Context) {
        val i = Intent(context, HlsToMp4Service::class.java).setAction("cancel")
        context.startService(i)
    }

    /** Human-readable bytes. */
    fun formatBytes(bytes: Long): String {
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        val df = DecimalFormat("#,##0.00")
        return when {
            bytes >= gb -> df.format(bytes / gb) + " GB"
            bytes >= mb -> df.format(bytes / mb) + " MB"
            bytes >= kb -> df.format(bytes / kb) + " KB"
            else -> "$bytes B"
        }
    }

    /** Human-readable duration from seconds. */
    fun formatDuration(seconds: Long?): String {
        if (seconds == null || seconds <= 0) return "--:--"
        val s = seconds % 60
        val m = (seconds / 60) % 60
        val h = seconds / 3600
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Sum a list of TS durations (in seconds) and round. */
    fun sumDurations(parts: List<Double>): Long {
        var total = 0.0
        for (p in parts) total += p
        return total.roundToLong()
    }
}
