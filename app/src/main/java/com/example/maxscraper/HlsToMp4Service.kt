package com.example.maxscraper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File
import java.io.FileInputStream

/**
 * Emits progress + metadata, writes to MediaStore, and mirrors updates both
 * via local & global broadcasts. Also mirrors state into HlsStatusStore so
 * Active can show the row immediately.
 */
class HlsToMp4Service : Service() {

    companion object {
        const val ACTION_PROGRESS = "com.example.maxscraper.HLS_PROGRESS"
        const val ACTION_DONE = "com.example.maxscraper.HLS_DONE"

        const val EXTRA_JOB_ID = "job_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_PERCENT = "percent"
        const val EXTRA_ELAPSED_MS = "elapsed_ms"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_BYTES = "bytes"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_URI = "uri"
        const val EXTRA_ERROR = "error"

        // Accept both "m3u8" and EXTRA_URL for URL
        const val EXTRA_URL = "m3u8"

        fun start(ctx: android.content.Context, m3u8: String, outTitle: String) {
            val i = Intent(ctx, HlsToMp4Service::class.java)
                .setAction("start")
                .putExtra("m3u8", m3u8)
                .putExtra("title", outTitle)
            ContextCompat.startForegroundService(ctx, i)
        }
    }

    private val binder = object : Binder() { fun service(): HlsToMp4Service = this@HlsToMp4Service }
    override fun onBind(intent: Intent?): IBinder = binder

    @Volatile private var cancelled = false
    @Volatile private var currentJobId: Long = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action.orEmpty()
        if (action == "start" || action.isEmpty()) {
            val title = intent?.getStringExtra("title")?.ifBlank { "video.mp4" } ?: "video.mp4"
            val m3u8 = intent?.getStringExtra(EXTRA_URL) ?: intent?.getStringExtra("m3u8")
            if (m3u8.isNullOrBlank()) return START_NOT_STICKY

            startForeground(1001, buildNotif("Converting", title, ongoing = true))
            cancelled = false
            currentJobId = System.currentTimeMillis()

            val lbm = LocalBroadcastManager.getInstance(this)

            // Seed immediate visibility in Active
            HlsStatusStore.setStart(currentJobId, title)
            emitProgress(lbm, title, 1, 0, 0, 0)

            FfmpegHlsDownloader.downloadM3u8ToMp4(
                this, m3u8, title,
                object : FfmpegHlsDownloader.Listener {
                    override fun onProgress(percent: Int, timeMs: Long, totalMs: Long, bytesSoFar: Long) {
                        if (cancelled) return
                        HlsStatusStore.update(currentJobId, title = title, percent = percent, bytes = bytesSoFar, elapsedMs = timeMs, totalMs = totalMs)
                        emitProgress(lbm, title, percent.coerceIn(1, 99), timeMs, totalMs, bytesSoFar)
                    }

                    override fun onLog(line: String) { /* no-op */ }

                    override fun onDone(success: Boolean, output: File?, error: String?) {
                        var uri: Uri? = null
                        if (success && output != null && !cancelled) {
                            uri = publishToMediaStore(output, title)
                            if (uri != null) {
                                runCatching {
                                    CompletedStore.add(
                                        this@HlsToMp4Service,
                                        title,
                                        uri,
                                        output.length(),
                                        System.currentTimeMillis()
                                    )
                                }
                                notifyCompletion()
                            }
                        }

                        // Force a final 100% and clear store
                        HlsStatusStore.update(currentJobId, percent = 100)
                        emitProgress(lbm, title, 100, 0, 0, 0)
                        HlsStatusStore.clear(currentJobId)

                        emitDone(
                            lbm = lbm,
                            title = title,
                            success = (success && !cancelled && uri != null),
                            uri = uri,
                            error = if (uri == null && !success) (error ?: "cancelled") else null
                        )

                        runCatching { output?.delete() }
                        stopForeground(true)
                        stopSelf()
                    }
                }
            )
            return START_STICKY
        }

        if (action == "cancel") {
            cancelled = true
            return START_STICKY
        }
        return START_NOT_STICKY
    }

    private fun emitProgress(
        lbm: LocalBroadcastManager,
        title: String,
        percent: Int,
        elapsedMs: Long,
        durationMs: Long,
        bytes: Long
    ) {
        val i = Intent(ACTION_PROGRESS).apply {
            putExtra(EXTRA_JOB_ID, currentJobId)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_PERCENT, percent.coerceIn(0, 100))
            putExtra(EXTRA_ELAPSED_MS, elapsedMs.coerceAtLeast(0))
            putExtra(EXTRA_DURATION_MS, durationMs.coerceAtLeast(0))
            putExtra(EXTRA_BYTES, bytes.coerceAtLeast(0))
        }
        lbm.sendBroadcast(Intent(i))
        sendBroadcast(i)
    }

    private fun emitDone(
        lbm: LocalBroadcastManager,
        title: String,
        success: Boolean,
        uri: Uri?,
        error: String?
    ) {
        val i = Intent(ACTION_DONE).apply {
            putExtra(EXTRA_JOB_ID, currentJobId)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_SUCCESS, success)
            putExtra(EXTRA_URI, uri?.toString())
            putExtra(EXTRA_ERROR, error)
        }
        lbm.sendBroadcast(Intent(i))
        sendBroadcast(i)
    }

    private fun publishToMediaStore(src: File, title: String): Uri? {
        try {
            val name = if (title.endsWith(".mp4", true)) title else "$title.mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Maxscraper")
                }
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(src).use { it.copyTo(out) }
            } ?: return null
            if (Build.VERSION.SDK_INT < 29) {
                sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))
            }
            return uri
        } catch (_: Throwable) { return null }
    }

    private fun notifyCompletion() {
        val sp = getSharedPreferences("settings", MODE_PRIVATE)
        val sound = sp.getBoolean("pref_sound_on", true)
        val vibrate = sp.getBoolean("pref_vibrate_on", true)

        if (sound) runCatching {
            val tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(this, tone)?.play()
        }
        if (vibrate) runCatching {
            val vib = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26)
                vib.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") vib.vibrate(150)
        }
    }

    private fun buildNotif(title: String, text: String, ongoing: Boolean): Notification {
        val chId = "hls"
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(chId) == null) {
                mgr.createNotificationChannel(NotificationChannel(chId, "HLS", NotificationManager.IMPORTANCE_LOW))
            }
        }
        return NotificationCompat.Builder(this, chId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(ongoing)
            .build()
    }
}
