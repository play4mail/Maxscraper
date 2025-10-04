package com.example.mediadetect

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.roundToLong

class MainVideoDetectorWebView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : WebView(ctx, attrs) {

    interface Listener {
        fun onDetectionResult(result: DetectionResult)
        fun onDrmDetected() {}
    }

    private var listener: Listener? = null
    fun setListener(l: Listener) { listener = l }

    private val mediaHits = ConcurrentLinkedQueue<Hit>()
    @Volatile private var drmHit = false
    @Volatile private var pageLoadedAt = 0L

    private data class Hit(
        val url: String,
        val ts: Long,
        val isSegment: Boolean,
        val isAd: Boolean,
        val headers: Map<String, String>,
        val approxBytes: Long,
    )

    private val AD_HINT = Regex("(vast|ima|doubleclick|googlesyndication|adservice|prebid)", RegexOption.IGNORE_CASE)
    private val MEDIA_ANY = Regex("\\.(m3u8|mpd|mp4|m4s|ts|fmp4)(\\?|$)", RegexOption.IGNORE_CASE)
    private val SEGMENT = Regex("\\.(m4s|ts|fmp4)(\\?|$)", RegexOption.IGNORE_CASE)

    @SuppressLint("SetJavaScriptEnabled")
    fun initDetector() {
        with(settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = userAgentString + " MediaDetect/1.0"
        }

        addJavascriptInterface(object {
            @JavascriptInterface fun markDrm() { drmHit = true }
        }, "MediaDetect")

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                mediaHits.clear()
                drmHit = false
                pageLoadedAt = System.currentTimeMillis()
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                injectProbes()
                super.onPageFinished(view, url)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url.toString()
                if (MEDIA_ANY.containsMatchIn(u)) {
                    val isSeg = SEGMENT.containsMatchIn(u)
                    val isAd = AD_HINT.containsMatchIn(u)
                    val headers = request.requestHeaders ?: emptyMap()
                    val approxBytes = headers["Content-Length"]?.toLongOrNull()
                        ?: if (isSeg) 1_000_000L else 100_000L
                    mediaHits.add(
                        Hit(
                            url = u,
                            ts = System.currentTimeMillis(),
                            isSegment = isSeg,
                            isAd = isAd,
                            headers = headers,
                            approxBytes = approxBytes,
                        )
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
    }

    private fun injectProbes() {
        // Hook EME + expose probe
        val js = """
            (function(){
              try {
                if (navigator.requestMediaKeySystemAccess) {
                  const orig = navigator.requestMediaKeySystemAccess.bind(navigator);
                  navigator.requestMediaKeySystemAccess = async function(...args) {
                    try { MediaDetect && MediaDetect.markDrm(); } catch(e){}
                    return orig(...args);
                  };
                }
              } catch(e){}
              window.__probeVideo = function(){
                function inViewport(el){
                  if(!el) return false;
                  const r = el.getBoundingClientRect();
                  return r.width>0 && r.height>0 && r.top<innerHeight && r.left<innerWidth && r.bottom>0 && r.right>0;
                }
                const v = document.querySelector('video');
                const p = {
                  currentSrc: v?.currentSrc || v?.src || '',
                  paused: v ? v.paused : true,
                  readyState: v ? v.readyState : 0,
                  inViewport: v ? inViewport(v) : false,
                  duration: (v && isFinite(v.duration)) ? v.duration : 0,
                  width: v?.videoWidth || 0,
                  height: v?.videoHeight || 0
                };
                let vjs='', jw='', hls='';
                try { if (window.videojs) { const ps = window.videojs.getPlayers(); const ks = Object.keys(ps); if (ks.length) vjs = ps[ks[0]].currentSrc(); } } catch(e){}
                try { if (window.jwplayer) { jw = window.jwplayer().getPlaylistItem()?.file || '' } } catch(e){}
                try { const H = window.hls || window.Hls; if (H && H.url) hls = H.url } catch(e){}
                return {p, vjs, jw, hls};
              };
            })();
        """.trimIndent()
        evaluateJavascript(js, null)
    }

    /**
     * Call this ~15–25s after the main video starts (or whenever you want a reading).
     */
    fun detectNow() {
        if (drmHit) {
            val res = DetectionResult(
                mediaUrl = null, streamType = "unknown", drm = true,
                reason = "DRM/EME detected",
                evidence = DetectionResult.Evidence(
                    score = 0, scoreItems = listOf("EME call observed"),
                    segmentsSeen = 0, cadenceMsAvg = 0, player = null, headers = emptyMap()
                )
            )
            listener?.onDrmDetected()
            listener?.onDetectionResult(res)
            return
        }

        // Group requests by parent (manifest/MP4 path vs segment folder)
        val groups = mutableMapOf<String, MutableList<Hit>>()
        for (h in mediaHits) {
            val key = if (SEGMENT.containsMatchIn(h.url)) h.url.substringBeforeLast("/") else h.url
            groups.getOrPut(key) { mutableListOf() }.add(h)
        }

        // Pull player probe (async → callback chain to keep it simple)
        evaluateJavascript("window.__probeVideo && JSON.stringify(window.__probeVideo())") { json ->
            var playerSources: List<String> = emptyList()
            val player = runCatching {
                if (json == null || json == "null") null else {
                    val o = JSONObject(json)
                    val p = o.getJSONObject("p")
                    val sources = mutableListOf<String>()
                    o.optString("vjs").takeIf { it.isNotBlank() }?.let { sources += it }
                    o.optString("jw").takeIf { it.isNotBlank() }?.let { sources += it }
                    o.optString("hls").takeIf { it.isNotBlank() }?.let { sources += it }
                    val detPlayer = DetectionResult.Player(
                        currentSrc = p.optString("currentSrc"),
                        paused = p.optBoolean("paused", true),
                        readyState = p.optInt("readyState", 0),
                        inViewport = p.optBoolean("inViewport", false),
                        duration = p.optDouble("duration", 0.0),
                        width = p.optInt("width", 0),
                        height = p.optInt("height", 0)
                    )
                    detPlayer.currentSrc?.takeIf { it.isNotBlank() }?.let { sources += it }
                    playerSources = sources.distinct()
                    detPlayer
                }
            }.getOrNull()

            val groupTraffic = groups.mapValues { (_, hits) ->
                val bytes = hits.sumOf { it.approxBytes }
                if (bytes > 0) bytes else hits.size.toLong()
            }
            val maxTraffic = groupTraffic.values.maxOrNull() ?: 0L

            fun matchesSource(url: String, sources: List<String>): Boolean {
                if (sources.isEmpty()) return false
                return sources.any { src ->
                    if (src.isBlank()) return@any false
                    if (url.contains(src, ignoreCase = true) || src.contains(url, ignoreCase = true)) return@any true
                    runCatching {
                        val srcHost = java.net.URI(src).host
                        val urlHost = java.net.URI(url).host
                        if (srcHost.isNullOrBlank() || urlHost.isNullOrBlank()) {
                            false
                        } else {
                            val srcKey = srcHost.split('.').takeLast(2).joinToString(".")
                            val urlKey = urlHost.split('.').takeLast(2).joinToString(".")
                            srcHost.equals(urlHost, ignoreCase = true) || srcKey.equals(urlKey, ignoreCase = true)
                        }
                    }.getOrDefault(false)
                }
            }

            // Score candidates
            var bestUrl: String? = null
            var bestScore = Int.MIN_VALUE
            var bestItems = mutableListOf<String>()
            var bestSegs = 0
            var bestCadence = 0L
            var bestHeaders: Map<String, String> = emptyMap()
            var bestStreamType = "unknown"

            groups.forEach { (key, list) ->
                var score = 0
                val items = mutableListOf<String>()

                val representativeUrl = list.firstOrNull { !it.isSegment }?.url ?: key
                val sampleUrl = representativeUrl.ifBlank { list.firstOrNull()?.url ?: key }
                val hasTs = list.any { it.url.contains(Regex("\\.ts(\\?|$)", RegexOption.IGNORE_CASE)) }
                val hasM4s = list.any { it.url.contains(Regex("\\.m4s(\\?|$)", RegexOption.IGNORE_CASE)) }
                val hasFmp4 = list.any { it.url.contains(Regex("\\.fmp4(\\?|$)", RegexOption.IGNORE_CASE)) }
                val isHls = sampleUrl.contains(Regex("\\.m3u8(\\?|$)", RegexOption.IGNORE_CASE))
                val isDash = sampleUrl.contains(Regex("\\.mpd(\\?|$)", RegexOption.IGNORE_CASE))
                val isMp4 = sampleUrl.contains(Regex("\\.mp4(\\?|$)", RegexOption.IGNORE_CASE))
                if (isHls) { score += 2; items += "type=hls +2" }
                if (isDash) { score += 2; items += "type=dash +2" }
                if (isMp4) { score += 2; items += "type=mp4 +2" }
                if (!isHls && !isDash && !isMp4 && list.any { it.isSegment }) {
                    score += 2; items += "segment-group +2"
                }

                val segTs = list.filter { it.isSegment }.map { it.ts }.sorted()
                val segs = segTs.size
                if (segs >= 10) { score += 5; items += "steady segments >=10 +5" }
                var avg = 0L
                if (segTs.size > 2) {
                    val diffs = segTs.zip(segTs.drop(1)).map { (a, b) -> b - a }
                    val mean = diffs.sum().toDouble() / diffs.size
                    avg = mean.roundToLong()
                    if (avg in 2000..8000) { score += 4; items += "cadence ${avg}ms +4" }
                }

                if (list.any { it.isAd || AD_HINT.containsMatchIn(it.url) }) {
                    score -= 4; items += "ad-like host -4"
                }

                val playingVisible = player?.let { !it.paused && it.readyState >= 2 && it.inViewport } ?: false
                if (playingVisible && matchesSource(sampleUrl, playerSources)) {
                    score += 6; items += "matches playing video +6"
                }

                val traffic = groupTraffic[key] ?: 0L
                if (traffic > 0 && traffic == maxTraffic) {
                    score += 3; items += "top traffic +3"
                }

                if (score > bestScore) {
                    bestScore = score
                    bestUrl = representativeUrl
                    bestItems = items
                    bestSegs = segs
                    bestCadence = avg
                    bestHeaders = list.firstOrNull()?.headers ?: emptyMap()
                    bestStreamType = when {
                        isHls -> "hls"
                        isDash -> "dash"
                        isMp4 -> "mp4"
                        hasTs -> "hls"
                        hasM4s || hasFmp4 -> "dash"
                        else -> "unknown"
                    }
                }
            }

            val result = DetectionResult(
                mediaUrl = bestUrl,
                streamType = if (bestUrl == null) "unknown" else bestStreamType,
                drm = false,
                reason = if (bestUrl == null) "No media detected" else null,
                evidence = DetectionResult.Evidence(
                    score = if (bestScore == Int.MIN_VALUE) 0 else bestScore,
                    scoreItems = bestItems,
                    segmentsSeen = bestSegs,
                    cadenceMsAvg = bestCadence,
                    player = player,
                    headers = bestHeaders
                )
            )
            listener?.onDetectionResult(result)
        }
    }
}
