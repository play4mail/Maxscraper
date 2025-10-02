package com.example.maxscraper

import android.content.Context
import android.net.Uri
import android.os.Build
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class NetSnifferWebViewClient(
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

    inner class Bridge {
        @JavascriptInterface fun hit(u: String) { MediaDetector.report(u) }
        @JavascriptInterface fun playing(u: String) { MediaDetector.reportPlaying(u) }
    }
}
