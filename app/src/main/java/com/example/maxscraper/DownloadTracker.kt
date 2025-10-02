package com.example.maxscraper

import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager

object DownloadTracker {
    private const val KEY = "dm_ids"
    const val ACTION_UPDATED = "com.example.maxscraper.DOWNLOADS_UPDATED"

    private fun sp(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    fun add(ctx: Context, id: Long) {
        val set = sp(ctx).getStringSet(KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        set += id.toString()
        sp(ctx).edit().putStringSet(KEY, set).apply()
        notifyUpdated(ctx)
    }

    fun remove(ctx: Context, id: Long) {
        val set = sp(ctx).getStringSet(KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        set.remove(id.toString())
        sp(ctx).edit().putStringSet(KEY, set).apply()
        notifyUpdated(ctx)
    }

    fun allIds(ctx: Context): LongArray {
        val set = sp(ctx).getStringSet(KEY, emptySet()) ?: emptySet()
        return set.mapNotNull { it.toLongOrNull() }.toLongArray()
    }

    fun notifyUpdated(ctx: Context) {
        ctx.sendBroadcast(Intent(ACTION_UPDATED))
    }
}
