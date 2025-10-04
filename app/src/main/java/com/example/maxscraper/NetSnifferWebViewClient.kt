package com.example.maxscraper

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.util.Locale

open class NetSnifferWebViewClient(
    private val ctx: Context,
    private val webView: WebView
) : WebViewClient() {

    init {
        webView.addJavascriptInterface(Bridge(), "DetectorBridge")
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        super.onPageStarted(view, url, favicon)
        MediaDetector.clear()
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        injectDetectorJs()
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && handleExternalSchemes(request.url.toString())) {
            return true
        }
        return super.shouldOverrideUrlLoading(view, request)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        if (handleExternalSchemes(url)) return true
        return super.shouldOverrideUrlLoading(view, url)
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
        val url = request.url.toString()
        if (looksLikeMedia(url)) MediaDetector.report(url)
        return super.shouldInterceptRequest(view, request)
    }

    private fun looksLikeMedia(u: String): Boolean {
        val L = u.lowercase()
        if (L.endsWith(".ts")) return false
        if (!(MediaFilter.isProbableMp4(u) || MediaFilter.isProbableHls(u))) return false
        val host = runCatching { Uri.parse(u).host?.lowercase() ?: "" }.getOrElse { "" }
        if (MediaDetector.isIgnoredHost(host)) return false
        return true
    }

    private fun injectDetectorJs() {
        // monitors <video> in main doc; some sites swap src after ads
        val js = """
            (function(){
              function addCandidate(u, bucket, seen){
                if (!u) return;
                var str = ('' + u).trim();
                if (!str) return;
                var low = str.toLowerCase();
                if (low.indexOf('.m3u8') < 0 && low.indexOf('.mp4') < 0) return;
                if (seen[str]) return;
                seen[str] = true;
                bucket.push(str);
              }
              function collectFromValue(val, bucket, seen){
                if (!val) return;
                var str = ('' + val).trim();
                if (!str) return;
                var matches = str.match(/https?:[^"'\s)]+/gi);
                if (matches){
                  for (var i=0;i<matches.length;i++){
                    addCandidate(matches[i], bucket, seen);
                  }
                } else {
                  addCandidate(str, bucket, seen);
                }
              }
              function scanElement(el, bucket, seen){
                if (!el) return;
                try { collectFromValue(el.currentSrc || el.src, bucket, seen); } catch(e){}
                if (el.getAttribute) collectFromValue(el.getAttribute('src'), bucket, seen);
                if (el.dataset){
                  for (var key in el.dataset){
                    if (Object.prototype.hasOwnProperty.call(el.dataset, key)){
                      collectFromValue(el.dataset[key], bucket, seen);
                    }
                  }
                }
                if (el.attributes){
                  for (var i=0;i<el.attributes.length;i++){
                    var attr = el.attributes[i];
                    collectFromValue(attr && attr.value, bucket, seen);
                  }
                }
                if (el.querySelectorAll){
                  var sources = el.querySelectorAll('source');
                  for (var j=0;j<sources.length;j++){
                    scanElement(sources[j], bucket, seen);
                  }
                }
              }
              function collectFromElement(el){
                var bucket = [];
                var seen = {};
                scanElement(el, bucket, seen);
                return bucket;
              }
              function postAll(urls, playing){
                if (!urls || !urls.length) return;
                for (var i=0;i<urls.length;i++){
                  var u = urls[i];
                  var low = u ? u.toLowerCase() : '';
                  if (low.indexOf('.m3u8') < 0 && low.indexOf('.mp4') < 0) continue;
                  try {
                    if (playing) window.DetectorBridge.playing(u); else window.DetectorBridge.hit(u);
                  } catch(e){}
                }
              }
              function scanDoc(doc){
                try {
                  var vids = doc.getElementsByTagName('video');
                  for (var i=0;i<vids.length;i++){
                    var v = vids[i];
                    postAll(collectFromElement(v), false);
                    v.addEventListener('playing', function(e){
                      postAll(collectFromElement(e.target), true);
                    });
                    v.addEventListener('loadedmetadata', function(e){
                      postAll(collectFromElement(e.target), false);
                    });
                  }
                } catch(e){}
              }
              scanDoc(document);
              // re-scan periodically; many players swap <source> mid-play
              setInterval(function(){ scanDoc(document); }, 1500);
            })();
        """.trimIndent()
        if (Build.VERSION.SDK_INT >= 19) webView.evaluateJavascript(js, null) else webView.loadUrl("javascript:$js")
    }

    private fun handleExternalSchemes(rawUrl: String?): Boolean {
        val url = rawUrl?.trim().orEmpty()
        if (url.isEmpty()) return false

        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.US)

        if (scheme.isNullOrEmpty()) return false
        if (scheme in allowedInWebView) return false

        val intent = if (url.startsWith("intent:", ignoreCase = true)) {
            runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull()
                ?.apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                }
        } else {
            Intent(Intent.ACTION_VIEW, uri)
        }

        if (intent == null) return false

        return try {
            ctx.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, "No app can handle this link", Toast.LENGTH_SHORT).show()
            true
        } catch (_: Throwable) {
            false
        }
    }

    inner class Bridge {
        @JavascriptInterface fun hit(u: String) { MediaDetector.report(u) }
        @JavascriptInterface fun playing(u: String) { MediaDetector.reportPlaying(u) }
    }

    private companion object {
        private val allowedInWebView = setOf(
            "http",
            "https",
            "file",
            "about",
            "javascript",
            "data",
            "blob"
        )
    }
}
