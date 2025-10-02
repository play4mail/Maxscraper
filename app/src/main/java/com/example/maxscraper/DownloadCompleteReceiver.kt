package com.example.maxscraper

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

class DownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id <= 0L) return

        // ✅ Use your existing tracker list instead of a non-existent DownloadTracker.contains()
        val tracked = try { DownloadTracker.allIds(context).contains(id) } catch (_: Throwable) { false }
        if (!tracked) return

        // Trigger gallery on legacy devices (scans the single file)
        try {
            if (Build.VERSION.SDK_INT < 29) {
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                    if (c.moveToFirst()) {
                        val local = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                        if (!local.isNullOrBlank()) {
                            context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.parse(local)))
                        }
                    }
                }
            }
        } catch (_: Throwable) { /* ignore */ }

        // Respect Settings toggles
        val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val soundOn = sp.getBoolean("pref_sound_on", true)
        val vibrateOn = sp.getBoolean("pref_vibrate_on", true)

        if (soundOn) {
            runCatching {
                val tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                RingtoneManager.getRingtone(context, tone)?.play()
            }
        }
        if (vibrateOn) {
            runCatching {
                val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
                else @Suppress("DEPRECATION") vib.vibrate(150)
            }
        }
    }
}
