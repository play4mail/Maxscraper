package com.example.maxscraper

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.ms_title_settings)
    }
    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}

class SettingsFragment : PreferenceFragmentCompat() {

    private val pickFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            requireContext().contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            Prefs.setCustomFolderUri(requireContext(), uri)
            Prefs.setSaveDirMode(requireContext(), Prefs.SAVE_DIR_MODE_CUSTOM)
            updateFolderSummaries()
        }
    }

    private val requestPostNotif =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings, rootKey)

        findPreference<Preference>(Prefs.KEY_SAVE_TO_MOVIES)?.setOnPreferenceClickListener {
            Prefs.clearCustomFolder(requireContext())
            Prefs.setSaveDirMode(requireContext(), Prefs.SAVE_DIR_MODE_MOVIES)
            updateFolderSummaries(); true
        }
        findPreference<Preference>(Prefs.KEY_PICK_FOLDER)?.setOnPreferenceClickListener {
            try { pickFolderLauncher.launch(Prefs.getCustomFolderUri(requireContext())) }
            catch (_: Exception) { pickFolderLauncher.launch(null) }
            true
        }

        findPreference<SeekBarPreference>(Prefs.KEY_MAX_CONCURRENT)?.apply {
            min = 1; max = 25; showSeekBarValue = true
            value = Prefs.getMaxConcurrent(requireContext())
            setOnPreferenceChangeListener { _, newValue ->
                Prefs.setMaxConcurrent(requireContext(), (newValue as Int)); true
            }
        }

        findPreference<SwitchPreferenceCompat>(Prefs.KEY_SOUND_ENABLED)
            ?.setOnPreferenceChangeListener { _, newValue ->
                maybeAskForNotifPermission(newValue == true); true
            }

        findPreference<EditTextPreference>(Prefs.KEY_IGNORE_LINKS)?.apply {
            text = Prefs.getIgnoreLinksRaw(requireContext())
            setOnPreferenceChangeListener { _, newValue ->
                Prefs.setIgnoreLinksRaw(requireContext(), newValue as String); true
            }
        }

        updateFolderSummaries()
    }

    private fun updateFolderSummaries() {
        val mode = Prefs.getSaveDirMode(requireContext())
        val moviesPref = findPreference<Preference>(Prefs.KEY_SAVE_TO_MOVIES)
        val pickPref = findPreference<Preference>(Prefs.KEY_PICK_FOLDER)

        if (mode == Prefs.SAVE_DIR_MODE_MOVIES) {
            moviesPref?.summary = getString(R.string.ms_pref_movies_selected)
            pickPref?.summary = getString(R.string.ms_pref_pick_folder_sum_none)
        } else {
            moviesPref?.summary = getString(R.string.ms_pref_movies_recommended)
            val u = Prefs.getCustomFolderUri(requireContext())
            if (u != null) {
                val name = DocumentFile.fromTreeUri(requireContext(), u)?.name ?: u.lastPathSegment
                pickPref?.summary = getString(R.string.ms_pref_pick_folder_sum, name ?: u.toString())
            } else {
                pickPref?.summary = getString(R.string.ms_pref_pick_folder_sum_none)
            }
        }
    }

    private fun maybeAskForNotifPermission(enabling: Boolean) {
        if (!enabling) return
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                requireContext(), android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) requestPostNotif.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
