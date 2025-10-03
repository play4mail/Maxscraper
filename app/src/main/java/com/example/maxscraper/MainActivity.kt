package com.example.maxscraper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.preference.PreferenceManager

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_POST_NOTIFICATIONS = 1001
        private const val REQ_READ_MEDIA_VIDEO = 1002
        private const val REQ_READ_EXTERNAL_STORAGE = 1003
    }

    private lateinit var inputUrl: EditText
    private lateinit var btnOpenBrowser: Button
    private lateinit var btnClear: Button
    private lateinit var btnDownloads: Button
    private lateinit var btnSettings: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        inputUrl = findViewById(R.id.inputUrl)
        btnOpenBrowser = findViewById(R.id.btnOpenBrowser)
        btnClear = findViewById(R.id.btnClear)
        btnDownloads = findViewById(R.id.btnDownloads)
        btnSettings = findViewById(R.id.btnSettings)

        requestPostNotificationsIfNeeded()
        requestMediaReadIfNeeded()

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        inputUrl.setText(prefs.getString("last_url", "") ?: "")

        btnOpenBrowser.isEnabled = inputUrl.text.isNotBlank()
        inputUrl.addTextChangedListener { text ->
            btnOpenBrowser.isEnabled = !text.isNullOrBlank()
        }

        inputUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                openBrowserWithInput(); true
            } else false
        }

        btnOpenBrowser.setOnClickListener { openBrowserWithInput() }
        btnClear.setOnClickListener { inputUrl.setText("") }

        btnDownloads.setOnClickListener {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_downloads -> {
                startActivity(Intent(this, DownloadsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            val perm = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(perm), REQ_POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestMediaReadIfNeeded() {
        when {
            Build.VERSION.SDK_INT >= 33 -> {
                val perm = Manifest.permission.READ_MEDIA_VIDEO
                if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(perm), REQ_READ_MEDIA_VIDEO)
                }
            }
            Build.VERSION.SDK_INT >= 23 -> {
                val perm = Manifest.permission.READ_EXTERNAL_STORAGE
                if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(perm), REQ_READ_EXTERNAL_STORAGE)
                }
            }
        }
    }

    private fun openBrowserWithInput() {
        val raw = inputUrl.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) {
            Toast.makeText(this, "Enter a URL first", Toast.LENGTH_SHORT).show()
            return
        }
        val url = normalizeUrl(raw)
        if (!looksLikeHttpUrl(url)) {
            Toast.makeText(this, "That doesn’t look like a valid web URL", Toast.LENGTH_SHORT).show()
            return
        }

        // Remember last URL
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit().putString("last_url", url).apply()

        // Send the URL in all the ways BrowserActivity understands.
        startActivity(
            Intent(this, BrowserActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(url)
                putExtra(EXTRA_URL, url)               // <-- use shared key
                putExtra(Intent.EXTRA_TEXT, url)
            }
        )
    }

    private fun normalizeUrl(input: String): String {
        val trimmed = input.replace("\\s".toRegex(), "")
        val hasScheme = trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)
        return if (hasScheme) trimmed else "https://$trimmed"
    }

    private fun looksLikeHttpUrl(s: String): Boolean {
        return try {
            val u = Uri.parse(s)
            val scheme = u.scheme?.lowercase()
            !u.host.isNullOrBlank() && (scheme == "http" || scheme == "https")
        } catch (_: Exception) { false }
    }
}
