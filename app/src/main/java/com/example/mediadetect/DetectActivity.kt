package com.example.mediadetect

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.example.maxscraper.R

class DetectActivity : AppCompatActivity(), MainVideoDetectorWebView.Listener {

    private lateinit var web: MainVideoDetectorWebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detect)

        web = findViewById(R.id.web)
        web.initDetector()
        web.setListener(this)

        // Load your test page:
        web.loadUrl("https://example.com/page-with-video")

        // Give the page time to start playing, then ask for detection.
        web.postDelayed({ web.detectNow() }, 20000L) // ~20s window
    }

    override fun onDetectionResult(result: DetectionResult) {
        Log.d("MediaDetect", "Result: $result")
        // TODO: use result.mediaUrl / result.streamType / result.drm / result.evidence
    }

    override fun onDrmDetected() {
        Log.w("MediaDetect", "DRM/EME detected — cannot identify a usable media URL.")
    }
}
