package com.example.maxscraper

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Lightweight internal media player built on VideoView.
 * - Play/Pause, Seek, 10s ± skip, scrubber with time
 * - Mute, Volume and Brightness sliders
 * - Playback speed (0.5x–2.0x on API 23+)
 * - True fullscreen toggle (immersive) with orientation lock
 *
 * Intent:
 *   data     -> Uri of the media
 *   EXTRA_TITLE -> optional title to display
 *
 * This file is self-contained and does not rely on activity_player.xml.
 */
class PlayerActivity : AppCompatActivity() {

    companion object { const val EXTRA_TITLE = "extra_title" }

    private lateinit var video: VideoView
    private lateinit var controls: ViewGroup
    private lateinit var titleBar: ViewGroup
    private lateinit var btnPlay: ImageButton
    private lateinit var btnBack10: ImageButton
    private lateinit var btnFwd10: ImageButton
    private lateinit var btnMute: ImageButton
    private lateinit var btnFullscreen: ImageButton
    private lateinit var speedSpinner: Spinner
    private lateinit var seek: SeekBar
    private lateinit var curTime: TextView
    private lateinit var totalTime: TextView
    private var mediaVol = 1f
    private var muted = false
    private var brightness: Float = -1f
    private var isFullscreen = false

    private val ui = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null
    private lateinit var gestures: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleControlsVisibility()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val width = video.width.takeIf { it > 0 } ?: root.width
                if (width <= 0) return false
                if (e.x < width / 2f) {
                    seekBy(-10_000)
                } else {
                    seekBy(10_000)
                }
                return true
            }
        })

        // VideoView
        video = VideoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setOnPreparedListener { mp ->
                mp.isLooping = false
                applyVolume()
                start()
                // init seek info
                totalTime.text = formatTime(duration / 1000)
                seek.max = duration
                startTicker()
            }
            setOnCompletionListener {
                stopTicker()
                seek.progress = duration
                curTime.text = formatTime((duration / 1000))
                btnPlay.setImageResource(android.R.drawable.ic_media_play)
            }
            setOnErrorListener { _, _, _ ->
                Toast.makeText(this@PlayerActivity, "Playback error", Toast.LENGTH_SHORT).show()
                true
            }
        }
        root.addView(video)

        video.setOnTouchListener { v, event ->
            val handled = gestures.onTouchEvent(event)
            if (!handled && event.action == MotionEvent.ACTION_UP) {
                v.performClick()
            }
            true
        }
        video.setOnClickListener { toggleControlsVisibility() }

        // Title bar
        titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(8))
            background = gradientScrim(top = true)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }
        val homeBtn = Button(this).apply {
            text = getString(R.string.menu_home)
            setOnClickListener { navigateHome() }
        }
        val backBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_previous)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        val titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            text = intent.getStringExtra(EXTRA_TITLE) ?: "Playing"
            setPadding(dp(8), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val deleteBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_delete)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "Delete"
            setOnClickListener {
                Toast.makeText(this@PlayerActivity, "Use the Completed tab to delete with options.", Toast.LENGTH_SHORT).show()
            }
        }
        titleBar.addView(homeBtn)
        titleBar.addView(backBtn)
        titleBar.addView(titleView)
        titleBar.addView(deleteBtn)

        // Controls overlay (bottom)
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(16))
            background = gradientScrim(top = false)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        // Row 1: Seekbar + time
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, dp(4), 0, dp(8))
        }
        curTime = TextView(this).apply { setTextColor(Color.WHITE); text = "0:00" }
        totalTime = TextView(this).apply { setTextColor(Color.WHITE); text = "--:--" }
        seek = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                var userSeeking = false
                override fun onStartTrackingTouch(seekBar: SeekBar?) { userSeeking = true }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    userSeeking = false
                    runCatching { video.seekTo(progress) }
                }
                override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) curTime.text = formatTime((p / 1000))
                }
            })
        }
        row1.addView(curTime)
        row1.addView(seek)
        row1.addView(totalTime)

        // Row 2: Transport + fullscreen + speed
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        btnBack10 = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_rew)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { seekBy(-10_000) }
        }
        btnPlay = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_pause)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                if (video.isPlaying) {
                    video.pause()
                    setImageResource(android.R.drawable.ic_media_play)
                } else {
                    video.start()
                    setImageResource(android.R.drawable.ic_media_pause)
                }
            }
        }
        btnFwd10 = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_ff)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { seekBy(10_000) }
        }
        btnFullscreen = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_crop)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { toggleFullscreen() }
        }
        btnMute = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_lock_silent_mode_off)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                muted = !muted
                applyVolume()
                setImageResource(if (muted) android.R.drawable.ic_lock_silent_mode
                else android.R.drawable.ic_lock_silent_mode_off)
            }
        }
        speedSpinner = Spinner(this).apply {
            val speeds = arrayOf("0.5x","0.75x","1.0x","1.25x","1.5x","2.0x")
            adapter = ArrayAdapter(this@PlayerActivity, android.R.layout.simple_spinner_dropdown_item, speeds)
            setSelection(2)
            onItemSelectedListener = object: AdapterView.OnItemSelectedListener{
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val v = when (position) {
                        0 -> 0.5f; 1 -> 0.75f; 2 -> 1.0f; 3 -> 1.25f; 4 -> 1.5f; else -> 2.0f
                    }
                    setPlaybackSpeed(v)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        row2.addView(btnBack10)
        row2.addView(btnPlay)
        row2.addView(btnFwd10)
        row2.addView(Space(this).apply { minimumWidth = dp(8) })
        row2.addView(btnFullscreen)
        row2.addView(Space(this).apply { minimumWidth = dp(8) })
        row2.addView(btnMute)
        row2.addView(Space(this).apply { minimumWidth = dp(8) })
        row2.addView(TextView(this).apply { setTextColor(Color.WHITE); text = "Speed" })
        row2.addView(Space(this).apply { minimumWidth = dp(4) })
        row2.addView(speedSpinner)

        // Row 3: Volume & Brightness sliders
        val row3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val volSeek = SeekBar(this).apply {
            max = 100; progress = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                    mediaVol = (p / 100f).coerceIn(0f, 1f)
                    applyVolume()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        val brightSeek = SeekBar(this).apply {
            max = 100; progress = 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                    brightness = (p / 100f).coerceIn(0.01f, 1f)
                    val lp = window.attributes
                    lp.screenBrightness = brightness
                    window.attributes = lp
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        val weight = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row3.addView(TextView(this).apply { setTextColor(Color.WHITE); text = "Vol" })
        row3.addView(volSeek, weight)
        row3.addView(Space(this).apply { minimumWidth = dp(8) })
        row3.addView(TextView(this).apply { setTextColor(Color.WHITE); text = "Bright" })
        row3.addView(brightSeek, weight)

        controls.addView(row1)
        controls.addView(row2)
        controls.addView(row3)

        root.addView(controls)
        root.addView(titleBar)
        setContentView(root)

        // Load Uri
        val uri: Uri? = intent?.data
        if (uri == null) {
            Toast.makeText(this, "No media to play", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        video.setVideoURI(uri)
    }

    override fun onBackPressed() {
        if (isFullscreen) {
            toggleFullscreen()
            return
        }
        super.onBackPressed()
    }

    private fun toggleControlsVisibility() {
        val showing = controls.visibility == View.VISIBLE
        controls.visibility = if (showing) View.GONE else View.VISIBLE
        if (isFullscreen) {
            titleBar.visibility = if (showing) View.GONE else View.VISIBLE
        }
    }

    private fun startTicker() {
        stopTicker()
        ticker = object : Runnable {
            override fun run() {
                runCatching {
                    val pos = video.currentPosition
                    seek.progress = pos
                    curTime.text = formatTime(pos / 1000)
                }
                ui.postDelayed(this, 500)
            }
        }
        ui.post(ticker!!)
    }

    private fun stopTicker() {
        ticker?.let { ui.removeCallbacks(it) }
        ticker = null
    }

    private fun seekBy(deltaMs: Int) {
        val np = (video.currentPosition + deltaMs).coerceIn(0, video.duration)
        video.seekTo(np)
        seek.progress = np
    }

    private fun applyVolume() {
        val mp = internalMediaPlayer() ?: return
        val v = if (muted) 0f else mediaVol
        try { mp.setVolume(v, v) } catch (_: Throwable) {}
    }

    private fun setPlaybackSpeed(sp: Float) {
        if (Build.VERSION.SDK_INT >= 23) {
            val mp = internalMediaPlayer() ?: return
            try {
                val params = (mp.playbackParams ?: PlaybackParams()).setSpeed(sp)
                mp.playbackParams = params
            } catch (_: Throwable) { /* ignore */ }
        }
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (isFullscreen) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            titleBar.visibility = View.GONE
            controls.visibility = View.GONE
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller.show(WindowInsetsCompat.Type.systemBars())
            titleBar.visibility = View.VISIBLE
            controls.visibility = View.VISIBLE
        }
    }

    @Suppress("DiscouragedPrivateApi")
    private fun internalMediaPlayer(): android.media.MediaPlayer? =
        try {
            val f = VideoView::class.java.getDeclaredField("mMediaPlayer")
            f.isAccessible = true
            f.get(video) as? android.media.MediaPlayer
        } catch (_: Throwable) { null }

    override fun onDestroy() {
        stopTicker()
        runCatching { video.stopPlayback() }
        super.onDestroy()
    }

    // ---- utilities ----
    private fun formatTime(sec: Int): String {
        val s = sec % 60
        val m = (sec / 60) % 60
        val h = sec / 3600
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    private fun dp(px: Int) = (resources.displayMetrics.density * px).toInt()

    private fun gradientScrim(top: Boolean): android.graphics.drawable.Drawable {
        val colors = intArrayOf(
            if (top) 0x99000000.toInt() else 0x00000000,
            0x00000000,
            if (!top) 0x99000000.toInt() else 0x00000000
        )
        return android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            colors
        )
    }
}
