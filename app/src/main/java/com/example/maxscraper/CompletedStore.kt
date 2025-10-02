package com.example.maxscraper

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

object CompletedStore {
    private const val PREF = "completed_store"
    private const val KEY = "items"

    data class Item(
        val title: String,
        val uri: Uri,
        val size: Long,
        val time: Long
    )

    fun add(context: Context, title: String, uri: Uri, size: Long, time: Long) {
        val arr = loadArray(context)
        val o = JSONObject()
            .put("title", title)
            .put("uri", uri.toString())
            .put("size", size)
            .put("time", time)
        arr.put(o)
        saveArray(context, arr)
    }

    fun all(context: Context): List<Item> {
        val arr = loadArray(context)
        val out = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += Item(
                title = o.optString("title"),
                uri = Uri.parse(o.optString("uri")),
                size = o.optLong("size", -1L),
                time = o.optLong("time", 0L)
            )
        }
        out.sortByDescending { it.time }
        return out
    }

    fun removeByUri(context: Context, uri: Uri) {
        val arr = loadArray(context)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("uri") != uri.toString()) out.put(o)
        }
        saveArray(context, out)
    }

    fun clear(context: Context) {
        saveArray(context, JSONArray())
    }

    private fun loadArray(context: Context): JSONArray {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val s = sp.getString(KEY, null) ?: return JSONArray()
        return try { JSONArray(s) } catch (_: Throwable) { JSONArray() }
    }

    private fun saveArray(context: Context, arr: JSONArray) {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        sp.edit().putString(KEY, arr.toString()).apply()
    }
}
