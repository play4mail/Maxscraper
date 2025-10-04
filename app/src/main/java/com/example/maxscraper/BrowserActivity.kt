package com.example.maxscraper

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.lifecycle.lifecycleScope
import com.example.maxscraper.ui.MediaOption
import com.example.maxscraper.ui.MediaPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URLDecoder
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import kotlin.jvm.Volatile

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
    @Volatile private var lastPageTitle: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val detectorListener: () -> Unit = {
        mainHandler.post { updateListCount() }
    }

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
        MediaDetector.addListener(detectorListener)

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

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                lastPageTitle = title?.trim()?.takeIf { it.isNotEmpty() }
            }
        }

        webView.webViewClient = object : NetSnifferWebViewClient(this@BrowserActivity, webView) {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    lastGoodUrl = url
                }
                lastPageTitle = view.title?.trim()?.takeIf { it.isNotEmpty() }
                // Capture og:image for thumbnails
                refreshOgImage()
                // Update the "List (count)" button
                updateListCount()
            }
        }

        val initial = intent?.dataString ?: "https://www.instagram.com"
        webView.loadUrl(initial)
    }

    override fun onDestroy() {
        MediaDetector.removeListener(detectorListener)
        super.onDestroy()
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
        val preferred = filterPlayableMedia(foundUrls)
        if (preferred.isEmpty()) { toast("No playable media links"); return }

        val labels = preferred.associateWith { url ->
            ensureMp4Suffix(
                base = suggestFriendlyName(url),
                isMp4 = url.contains(".mp4", true)
            )
        }

        lifecycleScope.launch {
            val options = withContext(Dispatchers.IO) { buildMediaOptions(preferred, labels) }

            if (options.isEmpty()) {
                toast("No playable media links")
                return@launch
            }

            MediaPicker.show(
                ctx = this@BrowserActivity,
                title = "Select a video",
                options = options,
                onChosen = { chosen ->
                    if (chosen.isHls) {
                        openHlsPicker(chosen)
                    } else {
                        val suggested = labels[chosen.url]
                            ?: ensureMp4Suffix(
                                base = suggestFriendlyName(chosen.url),
                                isMp4 = chosen.url.contains(".mp4", true)
                            )
                        showSaveAsAndStart(url = chosen.url, suggestedName = suggested)
                    }
                }
            )
        }
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
            val playable = filterPlayableMedia(urls)
            btnList.text = "${getString(R.string.menu_list)} (${playable.size})"
        }
    }

    private fun fetchPageMedia(onResult: (List<String>) -> Unit) {
        webView.evaluateJavascript(listExtractionJs) { raw ->
            val fromDom = parseJsonStringArray(raw)
            val merged = mergeWithDetector(fromDom)
            onResult(merged)
        }
    }

    private fun filterPlayableMedia(foundUrls: List<String>): List<String> {
        val trimmed = foundUrls.map { it.trim() }
        val mp4s = trimmed.filter { it.contains(".mp4", true) }
        val hls = trimmed.filter { it.contains(".m3u8", true) }
        val prioritized = when {
            mp4s.isNotEmpty() && hls.isNotEmpty() -> mp4s + hls
            mp4s.isNotEmpty() -> mp4s
            else -> hls
        }
        return prioritized
            .mapNotNull { normaliseMediaUrl(it) }
            .distinctBy { runCatching { Uri.parse(it).path ?: it }.getOrElse { it } }
    }

    private suspend fun buildMediaOptions(
        urls: List<String>,
        labels: Map<String, String>
    ): List<MediaOption> {
        val built = mutableListOf<Pair<MediaOption, Long>>()
        val seen = LinkedHashSet<String>()

        urls.forEach { url ->
            val label = labels[url]
                ?: ensureMp4Suffix(suggestFriendlyName(url), isMp4 = url.contains(".mp4", true))
            val variants = runCatching {
                VideoMetadata.fetchFor(url, buildRequestHeaders(url))
            }.getOrElse { emptyList() }
            val best = chooseBestVariant(variants)
            val thumb = if (isInstagramUrl(url)) lastOgImage else null

            val primaryOption = MediaOption(
                url = url,
                label = label,
                thumbUrl = thumb,
                meta = buildMetaString(url, best),
                isHls = url.contains(".m3u8", true) || variants.any { it.url.contains(".m3u8", true) },
                variants = variants
            )
            if (seen.add(url)) {
                val sizeHint = best?.sizeBytes ?: variants.maxOfOrNull { it.sizeBytes ?: -1L } ?: -1L
                built += primaryOption to sizeHint
            }

            if (url.contains(".m3u8", true)) {
                variants
                    .filter { it.url.contains(".mp4", true) || MediaFilter.isProbableMp4(it.url) }
                    .forEach { variant ->
                        val normalised = normaliseMediaUrl(variant.url) ?: return@forEach
                        if (seen.add(normalised)) {
                            val qualityLabel = variant.qualityLabel
                                .takeIf { it.isNotBlank() && !it.equals("hls", true) }
                            val mp4LabelBase = suggestFriendlyName(variant.url)
                            val mp4Label = ensureMp4Suffix(
                                base = listOf(mp4LabelBase, qualityLabel)
                                    .filterNotNull()
                                    .joinToString(" ")
                                    .trim(),
                                isMp4 = true
                            )
                            val mp4Option = MediaOption(
                                url = normalised,
                                label = mp4Label,
                                thumbUrl = thumb,
                                meta = buildMetaString(normalised, variant),
                                isHls = false,
                                variants = listOf(variant)
                            )
                            val mp4Size = variant.sizeBytes ?: best?.sizeBytes ?: -1L
                            built += mp4Option to mp4Size
                        }
                    }
            }
        }

        return built
            .distinctBy { it.first.url }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun openHlsPicker(option: MediaOption) {
        val urls = arrayListOf<String>()
        if (option.variants.isNotEmpty()) {
            option.variants.mapTo(urls) { it.url }
        }
        if (urls.isEmpty()) {
            urls += option.url
        }
        val distinct = urls.distinct()
        val extras = arrayListOf<String>()
        extras.addAll(distinct)
        startActivity(
            Intent(this, M3u8Activity::class.java)
                .putStringArrayListExtra("EXTRA_M3U8_LIST", extras)
        )
    }

    private fun buildRequestHeaders(targetUrl: String): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = UserAgent.defaultForWeb(this)
        headers["Accept"] = "*/*"
        headers["Accept-Encoding"] = "identity"
        headers["Accept-Language"] = "en-US,en;q=0.9"

        val referer = lastGoodUrl
        if (!referer.isNullOrBlank()) {
            headers["Referer"] = referer
            runCatching { Uri.parse(referer) }.getOrNull()?.let { refUri ->
                val scheme = refUri.scheme
                val host = refUri.host
                if (!scheme.isNullOrBlank() && !host.isNullOrBlank()) {
                    headers["Origin"] = "$scheme://$host"
                }
            }
        }

        gatherCookies(targetUrl, referer)?.let { headers["Cookie"] = it }
        return headers
    }

    private fun chooseBestVariant(variants: List<VideoVariant>): VideoVariant? {
        if (variants.isEmpty()) return null
        return variants.maxWithOrNull(
            compareByDescending<VideoVariant> { it.sizeBytes ?: -1L }
                .thenByDescending { it.height ?: -1 }
                .thenByDescending { it.bandwidthbps ?: -1L }
        )
    }

    private fun buildMetaString(url: String, variant: VideoVariant?): String {
        val ext = guessExtension(url)
        val size = formatSizeShort(variant?.sizeBytes)
        val dims = if (variant?.width != null && variant.height != null) "${variant.width}x${variant.height}" else "—"
        val duration = VideoMetadata.formatDuration(variant?.durationSec)
        return listOf(ext, size, dims, duration).joinToString(" ")
    }

    private fun guessExtension(url: String): String {
        val primary = runCatching { Uri.parse(url).lastPathSegment }.getOrNull()
        val ext = primary?.substringAfterLast('.', "").orEmpty()
            .ifBlank {
                url.substringAfterLast('.', "").substringBefore('?').substringBefore('#')
            }
        return if (ext.isBlank()) "—" else ".${ext.lowercase(Locale.US)}"
    }

    private fun formatSizeShort(bytes: Long?): String {
        if (bytes == null || bytes <= 0) return "—"
        val units = arrayOf("b", "kb", "mb", "gb", "tb")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024
            unitIndex++
        }
        if (unitIndex == 0) {
            return String.format(Locale.US, "%.0f%s", value, units[unitIndex])
        }
        val digits = if (value >= 100) 0 else 1
        val format = if (digits == 0) "%.0f" else "%.1f"
        var str = String.format(Locale.US, format, value)
        if (digits > 0) {
            str = str.trimEnd('0').trimEnd('.')
        }
        return str + units[unitIndex]
    }

    private fun mergeWithDetector(domUrls: List<String>): List<String> {
        val ordered = LinkedHashSet<String>()
        val playing = MediaDetector.getPlayingUrls()
        if (playing.isNotEmpty()) {
            playing.forEach { addIfValid(it, ordered) }
        }
        MediaDetector.getCandidates().forEach { addIfValid(it, ordered) }
        domUrls.forEach { addIfValid(it, ordered) }
        return ordered.toList()
    }

    private fun addIfValid(url: String, bag: MutableSet<String>) {
        val trimmed = url.trim()
        normaliseMediaUrl(trimmed)?.let { bag += it }
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
        val title = lastPageTitle?.takeUnless { it.equals("Post", true) }
        if (title != null) return sanitizeFileName(title)
        return fallbackNameFromUrl(url)
    }

    private fun fallbackNameFromUrl(url: String): String {
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

    private fun normaliseMediaUrl(raw: String): String? {
        if (raw.isBlank()) return null
        val trimmed = raw.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) return null
        return normaliseInstagramRanges(trimmed)
    }

    private fun normaliseInstagramRanges(url: String): String {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
        val host = uri.host?.lowercase(Locale.US) ?: return url
        val isInstagramHost = host.contains("instagram.com") || host.contains("cdninstagram") || host.contains("fbcdn")
        if (!isInstagramHost) return url

        val query = uri.query ?: return url
        val params = query.split('&')
        var removedAny = false
        val kept = mutableListOf<String>()
        for (part in params) {
            if (part.isBlank()) continue
            val key = part.substringBefore('=', part).lowercase(Locale.US)
            if (key == "bytestart" || key == "byteend" || key == "range") {
                removedAny = true
            } else {
                kept += part
            }
        }
        if (!removedAny) return url

        val base = url.substringBefore('?')
        val fragment = uri.fragment
        val rebuilt = buildString {
            append(base)
            if (kept.isNotEmpty()) {
                append('?')
                append(kept.joinToString("&"))
            }
            if (!fragment.isNullOrEmpty()) {
                append('#')
                append(fragment)
            }
        }
        return rebuilt
    }

    private fun isInstagramUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return runCatching { Uri.parse(url).host?.lowercase(Locale.US) }.getOrNull()
            ?.let { host ->
                host.contains("instagram.com") || host.contains("cdninstagram") || host.contains("fbcdn")
            } ?: false
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
                    var seen = {};
                    function add(u){
                        if (!u) return;
                        var str = ('' + u).trim();
                        if (!str) return;
                        var low = str.toLowerCase();
                        if (low.indexOf('.mp4') < 0 && low.indexOf('.m3u8') < 0) return;
                        if (seen[str]) return;
                        seen[str] = true;
                        urls.push(str);
                    }
                    function collectFromValue(val){
                        if (!val) return;
                        var str = ('' + val).trim();
                        if (!str) return;
                        var matches = str.match(/https?:[^"'\s)]+/gi);
                        if (matches){
                            for (var i=0;i<matches.length;i++){
                                add(matches[i]);
                            }
                        } else {
                            add(str);
                        }
                    }
                    function collectFromElement(el){
                        if (!el) return;
                        try { collectFromValue(el.currentSrc || el.src); } catch(e){}
                        if (el.getAttribute) collectFromValue(el.getAttribute('src'));
                        if (el.dataset){
                            for (var key in el.dataset){
                                if (Object.prototype.hasOwnProperty.call(el.dataset, key)){
                                    collectFromValue(el.dataset[key]);
                                }
                            }
                        }
                        if (el.attributes){
                            for (var i=0;i<el.attributes.length;i++){
                                var attr = el.attributes[i];
                                collectFromValue(attr && attr.value);
                            }
                        }
                        if (el.querySelectorAll){
                            var sources = el.querySelectorAll('source');
                            for (var j=0;j<sources.length;j++){
                                collectFromElement(sources[j]);
                            }
                        }
                    }
                    var vids = document.querySelectorAll('video,source');
                    for (var i=0;i<vids.length;i++){
                        collectFromElement(vids[i]);
                    }
                    var as = document.querySelectorAll('a[href]');
                    for (var j=0;j<as.length;j++){
                        collectFromValue(as[j].href || '');
                    }
                    return JSON.stringify(urls);
                }catch(e){ return '[]'; }
            })();
        """.trimIndent()
    }
}
