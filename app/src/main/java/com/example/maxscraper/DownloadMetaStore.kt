package com.example.maxscraper

import android.content.Context
import org.json.JSONObject

/**
 * Persists expected total sizes for DownloadManager jobs so we can show 0–100%
 * even when COLUMN_TOTAL_SIZE_BYTES is -1.
 */
object DownloadMetaStore {
    private const val PREF = "download_meta"
    private const val KEY_SIZES_BY_ID = "sizes_by_id"
    private const val KEY_SIZES_BY_URL = "sizes_by_url"

    fun putSizeForId(ctx: Context, id: Long, total: Long) {
        if (total <= 0) return
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val json = JSONObject(sp.getString(KEY_SIZES_BY_ID, "{}") ?: "{}")
        json.put(id.toString(), total)
        sp.edit().putString(KEY_SIZES_BY_ID, json.toString()).apply()
    }

    fun getSizeForId(ctx: Context, id: Long): Long {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val json = JSONObject(sp.getString(KEY_SIZES_BY_ID, "{}") ?: "{}")
        return json.optLong(id.toString(), -1L)
    }

    fun putSizeForUrl(ctx: Context, url: String, total: Long) {
        if (total <= 0) return
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val json = JSONObject(sp.getString(KEY_SIZES_BY_URL, "{}") ?: "{}")
        json.put(url, total)
        sp.edit().putString(KEY_SIZES_BY_URL, json.toString()).apply()
    }

    fun getSizeForUrl(ctx: Context, url: String?): Long {
        if (url.isNullOrBlank()) return -1
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val json = JSONObject(sp.getString(KEY_SIZES_BY_URL, "{}") ?: "{}")
        return json.optLong(url, -1L)
    }
}
