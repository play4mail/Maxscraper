package com.example.maxscraper

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.CustomViewCallback
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.example.maxscraper.ui.MediaOption
import com.example.maxscraper.ui.MediaPicker
import org.json.JSONArray
import java.net.URLDecoder
import java.util.LinkedHashSet
import java.util.Locale

/**
 * In-app browser wired to:
 *  - Toolbar: id=toolbar (back enabled)
 *  - Buttons: id=btnHome, id=btnList (shows "List (count)")
 *  - WebView: id=webView
 *  - Fullscreen container: id=fullscreen_container
 *
 * Behavior:
 *  - onPageFinished -> captures referer + og:image
 *  - "List" shows a thumbnail picker (prefers MP4, de-dupes)
 *  - Save-as prompt before starting download
 *  - Uses DownloadRepository.smartDownload(...) and opens Active tab
 */
class BrowserActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var btnHome: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnList: Button
    private lateinit var webView: WebView
    private lateinit var fullscreenContainer: FrameLayout

    private var lastGoodUrl: String? = null
    private var lastOgImage: String? = null

    // WebChrome fullscreen support
    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Your layout with toolbar/btnHome/btnList/webView/fullscreen_container
        setContentView(R.layout.activity_browser)

        toolbar = findViewById(R.id.toolbar)
        btnHome = findViewById(R.id.btnHome)
        btnList = findViewById(R.id.btnList)
        btnRefresh = findViewById(R.id.btnRefresh)
        webView = findViewById(R.id.webView)
        fullscreenContainer = findViewById(R.id.fullscreen_container)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            webView.stopLoading()
            navigateHome()
        }

        btnHome.setOnClickListener {
            webView.stopLoading()
            navigateHome()
        }
        btnList.setOnClickListener {
            extractAndShowMediaFromPage()
        }
        btnRefresh.setOnClickListener { webView.reload() }
        btnList.text = "${getString(R.string.menu_list)} (0)"

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = UserAgent.defaultForWeb(this@BrowserActivity)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                fullscreenContainer.visibility = View.VISIBLE
                fullscreenContainer.addView(
                    view,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }

            override fun onHideCustomView() {
                fullscreenContainer.removeAllViews()
                fullscreenContainer.visibility = View.GONE
                customView = null
                customViewCallback?.onCustomViewHidden()
            }
        }

        webView.webViewClient = object : NetSnifferWebViewClient(this, webView) {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    lastGoodUrl = url
                }
                // Capture og:image for thumbnails
                refreshOgImage()
                // Update the "List (count)" button
                updateListCount()
            }
        }

        val initial = intent?.dataString ?: "https://www.instagram.com"
        webView.loadUrl(initial)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Optional overflow "List" action (works even if no btnList in layout)
        menu.add(0, MENU_REFRESH, 0, getString(R.string.menu_refresh))
        menu.add(0, MENU_LIST, 1, getString(R.string.menu_list))
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_REFRESH -> { webView.reload(); true }
            MENU_LIST -> { extractAndShowMediaFromPage(); true }
            android.R.id.home -> {
                webView.stopLoading()
                navigateHome()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // -------------------- Extraction & picker --------------------

    private fun extractAndShowMediaFromPage() {
        fetchPageMedia { urls ->
            if (urls.isEmpty()) {
                toast("No videos found on this page")
            } else {
                showMediaPicker(urls)
            }
        }
    }

    fun onShowMediaList(urls: List<String>) {
        if (urls.isEmpty()) { toast("No videos found"); return }
        showMediaPicker(urls)
    }

    private fun showMediaPicker(foundUrls: List<String>) {
        // Prefer MP4; if none, use HLS; dedupe by path
        val mp4s = foundUrls.filter { it.contains(".mp4", true) }
        val hls  = foundUrls.filter { it.contains(".m3u8", true) }
        val preferred = (if (mp4s.isNotEmpty()) mp4s else hls)
            .map { it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinctBy { runCatching { Uri.parse(it).path ?: it }.getOrElse { it } }

        if (preferred.isEmpty()) { toast("No playable media links"); return }

        val options = preferred.map { url ->
            val label = ensureMp4Suffix(suggestFriendlyName(url), isMp4 = url.contains(".mp4", true))
            MediaOption(
                url = url,
                label = label,
                thumbUrl = lastOgImage, // null is okay; adapter will show no image
                meta = null
            )
        }

        MediaPicker.show(
            ctx = this,
            title = "Select a video",
            options = options,
            onChosen = { chosen ->
                val suggested = ensureMp4Suffix(
                    base = suggestFriendlyName(chosen.url),
                    isMp4 = chosen.url.contains(".mp4", true)
                )
                showSaveAsAndStart(url = chosen.url, suggestedName = suggested)
            }
        )
    }

    // -------------------- Download flow --------------------

    private fun showSaveAsAndStart(url: String, suggestedName: String) {
        val input = EditText(this).apply {
            setText(suggestedName)
            setSelection(suggestedName.length.coerceAtMost(suggestedName.length))
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Save video as:")
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val raw = input.text?.toString()?.trim().orEmpty()
                val finalName = ensureMp4Suffix(
                    base = if (raw.isBlank()) suggestFriendlyName(url) else raw,
                    isMp4 = url.contains(".mp4", true)
                )
                startDownload(url, finalName)
            }
            .show()
    }

    private fun startDownload(url: String, name: String) {
        DownloadRepository.smartDownload(
            ctx = this,
            url = url,
            fileName = name,
            referer = lastGoodUrl,
            listener = object : com.example.maxscraper.net.ParallelDownloader.Listener {
                override fun onStart(totalBytes: Long) {}
                override fun onProgress(bytesDownloaded: Long, totalBytes: Long) {}
                override fun onComplete(file: java.io.File) {
                    toast("Saved: ${file.name}")
                }
                override fun onError(t: Throwable) {
                    toast("Download error: ${t.message ?: "unknown"}")
                }
            }
        )
        // Jump straight to Active downloads
        startActivity(Intent(this, DownloadsActivity::class.java)
            .putExtra(DownloadsActivity.EXTRA_SHOW_TAB, "active"))
        toast("Downloading: $name")
    }

    // -------------------- Helpers --------------------

    private fun refreshOgImage() {
        webView.evaluateJavascript(
            "(function(){var m=document.querySelector('meta[property=\"og:image\"]');return m?m.content:''})()"
        ) { value ->
            val v = value?.trim('"')?.replace("\\/", "/")?.trim()
            lastOgImage = if (v.isNullOrEmpty()) null else v
        }
    }

    private fun updateListCount() {
        fetchPageMedia { urls ->
            btnList.text = "${getString(R.string.menu_list)} (${urls.size})"
        }
    }

    private fun fetchPageMedia(onResult: (List<String>) -> Unit) {
        webView.evaluateJavascript(listExtractionJs) { raw ->
            val fromDom = parseJsonStringArray(raw)
            val merged = mergeWithDetector(fromDom)
            onResult(merged)
        }
    }

    private fun mergeWithDetector(domUrls: List<String>): List<String> {
        val ordered = LinkedHashSet<String>()
        MediaDetector.getCandidates().forEach { addIfValid(it, ordered) }
        domUrls.forEach { addIfValid(it, ordered) }
        return ordered.toList()
    }

    private fun addIfValid(url: String, bag: MutableSet<String>) {
        val trimmed = url.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            bag += trimmed
        }
    }

    private fun parseJsonStringArray(jsResult: String?): List<String> {
        if (jsResult.isNullOrBlank()) return emptyList()
        val cleaned = jsResult.trim()
        return try {
            val arr = JSONArray(if (cleaned.startsWith("\"")) cleaned.substring(1, cleaned.length - 1) else cleaned)
            MutableList(arr.length()) { idx -> arr.optString(idx, "") }.filter { it.isNotBlank() }
        } catch (_: Throwable) {
            try {
                val arr = JSONArray(cleaned)
                MutableList(arr.length()) { idx -> arr.optString(idx, "") }.filter { it.isNotBlank() }
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    private fun suggestFriendlyName(url: String): String {
        val title = (webView.title ?: "").trim()
        if (title.isNotEmpty() && !title.equals("Post", true)) return sanitizeFileName(title)
        return try {
            val u = Uri.parse(url)
            val seg = u.lastPathSegment ?: "video"
            val decoded = URLDecoder.decode(seg, "UTF-8")
            sanitizeFileName(decoded.ifBlank { "video" })
        } catch (_: Throwable) {
            "video"
        }
    }

    private fun ensureMp4Suffix(base: String, isMp4: Boolean): String {
        val clean = sanitizeFileName(base)
        return when {
            clean.lowercase(Locale.US).endsWith(".mp4") -> clean
            isMp4 -> "$clean.mp4"
            else -> clean
        }
    }

    private fun sanitizeFileName(s: String): String {
        var out = s.replace("[\\\\/:*?\"<>|]".toRegex(), " ").trim()
        if (out.isEmpty()) out = "video"
        return out.replace("\\s+".toRegex(), " ")
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val MENU_LIST = 1001
        private const val MENU_REFRESH = 1002
        private val listExtractionJs = """
            (function(){
                try{
                    var urls = [];
                    var vids = document.querySelectorAll('video,source');
                    for (var i=0;i<vids.length;i++){
                        var s = vids[i].src || vids[i].getAttribute('src') || '';
                        if (s) urls.push(s);
                    }
                    var as = document.querySelectorAll('a[href]');
                    for (var j=0;j<as.length;j++){
                        var h = as[j].href || '';
                        if (/\.(mp4|m3u8)(\?|$)/i.test(h)) urls.push(h);
                    }
                    urls = Array.from(new Set(urls));
                    return JSON.stringify(urls);
                }catch(e){ return '[]'; }
            })();
        """.trimIndent()
    }
}
