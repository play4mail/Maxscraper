package com.example.maxscraper

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager

object Prefs {
    // storage mode
    const val SAVE_DIR_MODE_MOVIES = "movies"
    const val SAVE_DIR_MODE_CUSTOM = "custom"
    const val KEY_DIR_MODE = "pref_dir_mode"
    const val KEY_DIR_URI = "pref_dir_uri"

    // preference keys (match XML)
    const val KEY_SAVE_TO_MOVIES = "pref_save_to_movies"
    const val KEY_PICK_FOLDER = "pref_pick_folder"
    const val KEY_MAX_CONCURRENT = "pref_max_concurrent"
    const val KEY_SOUND_ENABLED = "pref_sound"
    const val KEY_VIBRATE_ENABLED = "pref_vibrate"
    const val KEY_IGNORE_LINKS = "pref_ignore_links"

    // browser defaults
    private const val KEY_BROWSER_HOME = "browser_home"
    private const val KEY_BROWSER_LAST = "browser_last"
    private const val DEFAULT_HOME = "https://google.com"

    private fun sp(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    fun setSaveDirMode(ctx: Context, mode: String) = sp(ctx).edit().putString(KEY_DIR_MODE, mode).apply()
    fun getSaveDirMode(ctx: Context): String = sp(ctx).getString(KEY_DIR_MODE, SAVE_DIR_MODE_MOVIES) ?: SAVE_DIR_MODE_MOVIES

    fun setCustomFolderUri(ctx: Context, uri: Uri) = sp(ctx).edit().putString(KEY_DIR_URI, uri.toString()).apply()
    fun getCustomFolderUri(ctx: Context): Uri? = sp(ctx).getString(KEY_DIR_URI, null)?.let { Uri.parse(it) }
    fun clearCustomFolder(ctx: Context) = sp(ctx).edit().remove(KEY_DIR_URI).apply()

    fun getMaxConcurrent(ctx: Context): Int = sp(ctx).getInt(KEY_MAX_CONCURRENT, 3)
    fun setMaxConcurrent(ctx: Context, v: Int) = sp(ctx).edit().putInt(KEY_MAX_CONCURRENT, v.coerceIn(1, 25)).apply()

    fun isSoundEnabled(ctx: Context) = sp(ctx).getBoolean(KEY_SOUND_ENABLED, true)
    fun isVibrateEnabled(ctx: Context) = sp(ctx).getBoolean(KEY_VIBRATE_ENABLED, false)

    fun setIgnoreLinksRaw(ctx: Context, text: String) = sp(ctx).edit().putString(KEY_IGNORE_LINKS, text).apply()
    fun getIgnoreLinksRaw(ctx: Context): String = sp(ctx).getString(KEY_IGNORE_LINKS, "") ?: ""

    /** built-in + user list, already lowercased */
    fun getIgnoreLinksList(ctx: Context): List<String> =
        (BUILTIN_IGNORE + "\n" + getIgnoreLinksRaw(ctx))
            .split('\n', ',', ';')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .distinct()

    // Browser helpers
    fun getBrowserHome(ctx: Context): String = sp(ctx).getString(KEY_BROWSER_HOME, DEFAULT_HOME) ?: DEFAULT_HOME
    fun setBrowserHome(ctx: Context, url: String) = sp(ctx).edit().putString(KEY_BROWSER_HOME, url).apply()
    fun getLastBrowserUrl(ctx: Context): String? = sp(ctx).getString(KEY_BROWSER_LAST, null)
    fun setLastBrowserUrl(ctx: Context, url: String?) {
        if (url.isNullOrBlank()) return
        sp(ctx).edit().putString(KEY_BROWSER_LAST, url).apply()
    }

    // Basic ad/noise patterns; editable by user in Settings
    private const val BUILTIN_IGNORE = """
doubleclick
googlesyndication
/ads/
adserver
preroll
midroll
postroll
vmap
vast
pixel
beacon
analytics
metrics
thumbnail
.vtt
.gif
.jpg
.png
.webm
"""
}
