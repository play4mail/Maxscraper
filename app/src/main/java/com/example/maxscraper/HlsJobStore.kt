package com.example.maxscraper

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Tracks in-progress HLS -> MP4 conversions so ActiveFragment can show them.
 * Lightweight SharedPreferences JSON store; broadcasts ACTION_UPDATED on changes.
 */
object HlsJobStore {
    const val ACTION_UPDATED = "com.example.maxscraper.HLS_JOBS_UPDATED"
    private const val PREF = "hls_jobs"
    private const val KEY = "items"

    data class Job(
        val id: String,
        val url: String,
        val title: String,
        var progress: Int,
        var status: String, // RUNNING | DONE | ERROR
        var outUri: String? = null,
        var error: String? = null,
        val startedAt: Long = System.currentTimeMillis()
    )

    fun start(context: Context, url: String, title: String): String {
        val id = UUID.randomUUID().toString()
        val arr = loadArray(context)
        val o = JSONObject()
            .put("id", id)
            .put("url", url)
            .put("title", title)
            .put("progress", 0)
            .put("status", "RUNNING")
            .put("startedAt", System.currentTimeMillis())
        arr.put(o)
        saveArray(context, arr)
        notifyUpdated(context)
        return id
    }

    fun update(context: Context, id: String, progress: Int) {
        val arr = loadArray(context)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("id") == id) {
                o.put("progress", progress.coerceIn(0, 100))
                saveArray(context, arr); notifyUpdated(context); return
            }
        }
    }

    fun done(context: Context, id: String, success: Boolean, out: Uri?, error: String?) {
        val arr = loadArray(context)
        var changed = false
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("id") == id) {
                o.put("status", if (success) "DONE" else "ERROR")
                if (out != null) o.put("outUri", out.toString())
                if (error != null) o.put("error", error)
                changed = true
                break
            }
        }
        if (changed) { saveArray(context, arr); notifyUpdated(context) }
    }

    fun remove(context: Context, id: String) {
        val arr = loadArray(context)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("id") != id) out.put(o)
        }
        saveArray(context, out)
        notifyUpdated(context)
    }

    /** Only running jobs. */
    fun active(context: Context): List<Job> {
        val arr = loadArray(context)
        val list = mutableListOf<Job>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("status") == "RUNNING") {
                list += Job(
                    id = o.optString("id"),
                    url = o.optString("url"),
                    title = o.optString("title"),
                    progress = o.optInt("progress", 0),
                    status = "RUNNING",
                    outUri = null,
                    error = null,
                    startedAt = o.optLong("startedAt", System.currentTimeMillis())
                )
            }
        }
        return list
    }

    private fun sp(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)
    private fun loadArray(ctx: Context): JSONArray {
        val raw = sp(ctx).getString(KEY, "[]") ?: "[]"
        return try { JSONArray(raw) } catch (_: Throwable) { JSONArray() }
    }
    private fun saveArray(ctx: Context, arr: JSONArray) {
        sp(ctx).edit().putString(KEY, arr.toString()).apply()
    }
    private fun notifyUpdated(ctx: Context) {
        ctx.sendBroadcast(Intent(ACTION_UPDATED))
    }
}
