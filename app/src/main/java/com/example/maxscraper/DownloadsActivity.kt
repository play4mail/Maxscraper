package com.example.maxscraper

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit

class DownloadsActivity : AppCompatActivity() {

    private lateinit var btnActive: Button
    private lateinit var btnCompleted: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)

        btnActive = findViewById(R.id.btnActive)
        btnCompleted = findViewById(R.id.btnCompleted)

        btnActive.setOnClickListener {
            supportFragmentManager.commit { replace(R.id.content, ActiveFragment(), TAG_ACTIVE) }
            updateButtons(true)
        }
        btnCompleted.setOnClickListener {
            supportFragmentManager.commit { replace(R.id.content, CompletedFragment(), TAG_COMPLETED) }
            updateButtons(false)
        }

        val showActive = intent?.getStringExtra(EXTRA_SHOW_TAB)?.equals("active", ignoreCase = true) == true
        if (showActive) btnActive.performClick() else btnCompleted.performClick()
    }

    private fun updateButtons(activeSelected: Boolean) {
        btnActive.isEnabled = !activeSelected
        btnCompleted.isEnabled = activeSelected
    }

    companion object {
        const val EXTRA_SHOW_TAB = "EXTRA_SHOW_TAB"
        private const val TAG_ACTIVE = "active"
        private const val TAG_COMPLETED = "completed"
    }
}
