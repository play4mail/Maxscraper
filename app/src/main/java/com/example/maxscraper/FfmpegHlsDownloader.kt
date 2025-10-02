package com.example.maxscraper

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import com.arthenica.ffmpegkit.StatisticsCallback
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

object FfmpegHlsDownloader {

    interface Listener {
        /**
         * progress: 0–100. timeMs and totalMs are best-effort (totalMs may be 0 when unknown).
         * bytesSoFar reflects the current output file size (useful when totalMs==0).
         */
        fun onProgress(percent: Int, timeMs: Long, totalMs: Long, bytesSoFar: Long)
        fun onLog(line: String)
        fun onDone(success: Boolean, output: File?, error: String?)
    }

    fun downloadM3u8ToMp4(
        context: Context,
        m3u8Url: String,
        outTitle: String,
        listener: Listener
    ) {
        Thread {
            try {
                val dir = File(context.getExternalFilesDir(null), "Downloads")
                if (!dir.exists()) dir.mkdirs()
                val outFile = File(dir, safeName("$outTitle.mp4"))
                if (outFile.exists() && outFile.length() == 0L) outFile.delete()

                // Don’t block on remote metadata; compute progress from time/bytes instead.
                val durationMs: Long = probeDurationMs(m3u8Url) // returns 0L by design
                val pct = AtomicInteger(0)

                // Add UA to help some CDNs
                val cmd = listOf(
                    "-y",
                    "-user_agent", "Mozilla/5.0 (Android)",
                    "-i", m3u8Url,
                    "-c", "copy",
                    "-bsf:a", "aac_adtstoasc",
                    outFile.absolutePath
                ).joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it }

                val statsCb = StatisticsCallback { s: Statistics ->
                    // Robustly handle SDKs where time is Long or Double.
                    val elapsedMs: Long = try {
                        s.time.toLong()
                    } catch (_: Throwable) {
                        // Extremely defensive fallback if the type is unexpected
                        when (val anyTime = try { s.time } catch (_: Throwable) { 0 }) {
                            is Number -> anyTime.toLong()
                            else -> 0L
                        }
                    }

                    val total = durationMs
                    val fileBytes = outFile.length()

                    val newP = if (total > 0) {
                        val ratio = (elapsedMs.coerceAtLeast(0L)).toDouble() / total.toDouble()
                        ratio.times(100.0).toInt().coerceIn(1, 99)
                    } else {
                        (pct.get() + 1).coerceAtMost(99)
                    }

                    if (newP > pct.get()) pct.set(newP)
                    listener.onProgress(pct.get(), elapsedMs, total, fileBytes)
                }

                FFmpegKit.executeAsync(
                    cmd,
                    { ses ->
                        val rc = ses.returnCode
                        if (ReturnCode.isSuccess(rc)) {
                            if (outFile.exists() && outFile.length() > 0L) {
                                listener.onDone(true, outFile, null)
                            } else {
                                listener.onDone(false, null, "Output file missing or zero bytes")
                            }
                        } else {
                            val err = ses.failStackTrace ?: ses.allLogsAsString
                            if (outFile.exists() && outFile.length() == 0L) outFile.delete()
                            listener.onDone(false, null, err)
                        }
                    },
                    { logLine -> listener.onLog(logLine.message) },
                    statsCb
                )

            } catch (t: Throwable) {
                listener.onDone(false, null, t.message ?: "Unknown error")
            }
        }.start()
    }

    private fun safeName(n: String): String =
        n.trim().ifBlank { "video" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")

    // Keep this non-blocking; we purposely return 0L so UI progress derives from bytes/time
    private fun probeDurationMs(@Suppress("UNUSED_PARAMETER") url: String): Long = 0L
}
