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
              function post(u, playing){
                try {
                  if (!u) return;
                  var low = u ? u.toLowerCase() : '';
                  if (low.indexOf('.m3u8')<0 && low.indexOf('.mp4')<0) return;
                  if (playing) window.DetectorBridge.playing(u); else window.DetectorBridge.hit(u);
                } catch(e){}
              }
              function scanDoc(doc){
                try {
                  var vids = doc.getElementsByTagName('video');
                  for (var i=0;i<vids.length;i++){
                    var v = vids[i];
                    var src = v.currentSrc || v.src;
                    if (!src && v.querySelector('source')) src = v.querySelector('source').src;
                    if (src) post(src, false);
                    v.addEventListener('playing', function(e){
                      var s = e.target.currentSrc || e.target.src;
                      if (!s && e.target.querySelector('source')) s = e.target.querySelector('source').src;
                      post(s, true);
                    });
                    v.addEventListener('loadedmetadata', function(e){
                      var s = e.target.currentSrc || e.target.src;
                      if (!s && e.target.querySelector('source')) s = e.target.querySelector('source').src;
                      post(s, false);
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
