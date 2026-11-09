package com.amresalehin.emregallery.data

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Tab-specific media registry for interactive 1DM-style browser sessions.
 */
class BrowserTabSession(
    val tabId: String = "tab_main",
    initialUrl: String = ""
) {
    private val _url = MutableStateFlow(initialUrl)
    val url: StateFlow<String> = _url.asStateFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val itemsLock = Any()
    private val candidatesMap = LinkedHashMap<String, ScrapedMediaItem>()

    private val _detectedItems = MutableStateFlow<List<ScrapedMediaItem>>(emptyList())
    val detectedItems: StateFlow<List<ScrapedMediaItem>> = _detectedItems.asStateFlow()

    private val _detectedCount = MutableStateFlow(0)
    val detectedCount: StateFlow<Int> = _detectedCount.asStateFlow()

    fun updateNavigation(newUrl: String, newTitle: String? = null) {
        val cleanUrl = newUrl.trim()
        if (cleanUrl != _url.value) {
            // Discovered candidates belong to the current page, not the previous URL.
            // Capture the old URL before replacing it, then reset the per-tab registry.
            clearMedia()
            _url.value = cleanUrl
            _title.value = ""
            DownloaderSession.updateBrowserPage(cleanUrl)
        }
        if (!newTitle.isNullOrBlank()) {
            _title.value = newTitle
        }
    }

    fun registerMedia(item: ScrapedMediaItem) {
        registerMediaBatch(listOf(item))
    }

    fun registerMediaBatch(items: List<ScrapedMediaItem>, pageTitleHint: String? = null) {
        if (items.isEmpty()) return
        synchronized(itemsLock) {
            val modified = MediaNormalizer.mergeCandidates(candidatesMap, items)
            if (modified) {
                val list = candidatesMap.values.toList()
                _detectedItems.value = list
                _detectedCount.value = list.size
                DownloaderSession.addDiscoveredItems(list, pageTitleHint ?: _title.value)
            }
        }
        if (!pageTitleHint.isNullOrBlank()) {
            _title.value = pageTitleHint
        }
    }

    fun getDetectedMedia(): List<ScrapedMediaItem> {
        synchronized(itemsLock) {
            return candidatesMap.values.toList()
        }
    }

    fun clearMedia() {
        synchronized(itemsLock) {
            candidatesMap.clear()
            _detectedItems.value = emptyList()
            _detectedCount.value = 0
        }
    }
}

/**
 * BrowserSession:
 * 1. Hosts the per-tab media registry (1DM-style continuous detection without rescanning).
 * 2. Provides consolidated headless browser extraction for URL extraction routing fallback.
 */
object BrowserSession {

    private val tabRegistry = ConcurrentHashMap<String, BrowserTabSession>()

    fun getTabSession(tabId: String = "tab_main"): BrowserTabSession {
        return tabRegistry.getOrPut(tabId) { BrowserTabSession(tabId = tabId) }
    }

    fun registerMedia(tabId: String, item: ScrapedMediaItem) {
        getTabSession(tabId).registerMedia(item)
    }

    fun registerMediaBatch(tabId: String, items: List<ScrapedMediaItem>, pageTitle: String? = null) {
        getTabSession(tabId).registerMediaBatch(items, pageTitle)
    }

    fun getDetectedMedia(tabId: String): List<ScrapedMediaItem> {
        return getTabSession(tabId).getDetectedMedia()
    }

    fun clearTabMedia(tabId: String) {
        getTabSession(tabId).clearMedia()
    }

    // =========================================================================
    // Headless Browser Extraction Engine (used for URL Routing Fallback)
    // =========================================================================

    private val mainHandler = Handler(Looper.getMainLooper())

    class EmreMediaBridge(private val onMediaFound: (String) -> Unit) {
        @android.webkit.JavascriptInterface
        fun reportMedia(url: String?) {
            if (!url.isNullOrBlank()) {
                onMediaFound(url)
            }
        }
    }

    private fun findActivity(ctx: Context): Activity? {
        var current: Context? = ctx
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun extractHeadless(
        context: Context,
        targetUrl: String,
        onStatusUpdate: (String) -> Unit = {},
        onMediaDiscovered: (ScrapedMediaItem) -> Unit = {},
        publishToDownloader: Boolean = true
    ): MediaExtractionResult = suspendCancellableCoroutine { continuation ->
        mainHandler.post {
            val normalizedUrl = if (!targetUrl.startsWith("http://", ignoreCase = true) && !targetUrl.startsWith("https://", ignoreCase = true)) {
                "https://$targetUrl"
            } else targetUrl

            val domain = runCatching { URL(normalizedUrl).host }.getOrDefault("")
            var webView: WebView? = null
            var isCompleted = false
            var hydrationScanScheduled = false

            fun cleanup() {
                try {
                    mainHandler.removeCallbacksAndMessages(null)
                    (webView?.parent as? ViewGroup)?.removeView(webView)
                    webView?.stopLoading()
                    webView?.removeJavascriptInterface("EmreBridge")
                    webView?.webViewClient = WebViewClient()
                    webView?.webChromeClient = WebChromeClient()
                    webView?.destroy()
                } catch (_: Throwable) {
                } finally {
                    webView = null
                }
            }

            continuation.invokeOnCancellation {
                mainHandler.post { cleanup() }
            }

            try {
                val act = findActivity(context)
                val wv = if (act != null) WebView(act) else WebView(context)
                webView = wv

                val displayMetrics = context.resources.displayMetrics
                val viewWidth = if (displayMetrics.widthPixels > 0) displayMetrics.widthPixels else 1080
                val viewHeight = if (displayMetrics.heightPixels > 0) displayMetrics.heightPixels else 1920

                if (act != null && !act.isFinishing && !act.isDestroyed) {
                    val root = act.findViewById<ViewGroup>(android.R.id.content)
                    if (root != null) {
                        wv.alpha = 0.001f
                        wv.isClickable = false
                        wv.isFocusable = false
                        val lp = ViewGroup.LayoutParams(viewWidth, viewHeight)
                        try { root.addView(wv, 0, lp) } catch (_: Throwable) { root.addView(wv, lp) }
                    }
                }

                wv.measure(
                    View.MeasureSpec.makeMeasureSpec(viewWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(viewHeight, View.MeasureSpec.EXACTLY)
                )
                wv.layout(0, 0, viewWidth, viewHeight)

                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    mediaPlaybackRequiresUserGesture = false
                    loadsImagesAutomatically = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString = OkHttpDownloader.DEFAULT_USER_AGENT
                }

                val seenMap = LinkedHashMap<String, ScrapedMediaItem>()

                fun ingest(items: List<ScrapedMediaItem>) {
                    if (items.isEmpty() || isCompleted) return
                    val modified = MediaNormalizer.mergeCandidates(seenMap, items)
                    if (modified) {
                        for (it in items) {
                            onMediaDiscovered(it)
                            if (publishToDownloader) {
                                DownloaderSession.addDiscoveredItem(it)
                            }
                        }
                    }
                }

                fun finish(error: String? = null) {
                    if (isCompleted) return
                    isCompleted = true
                    val discovered = seenMap.values.toList()
                    cleanup()
                    if (continuation.isActive) {
                        continuation.resume(
                            MediaExtractionResult(
                                items = discovered,
                                pageTitle = if (discovered.isNotEmpty()) discovered.first().title else domain,
                                errorMessage = if (discovered.isEmpty()) error else null
                            ),
                            onCancellation = { cleanup() }
                        )
                    }
                }

                wv.addJavascriptInterface(
                    EmreMediaBridge { rawUrl ->
                        mainHandler.post {
                            val item = MediaNormalizer.normalize(
                                rawUrl = rawUrl,
                                source = ExtractionSource.DOM_SCRAPE,
                                baseUrl = normalizedUrl
                            )
                            if (item != null) ingest(listOf(item))
                        }
                    },
                    "EmreBridge"
                )

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        BotDetectionMitigator.injectStealth(view)
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        if (request == null || isCompleted) return null
                        return MediaCaptureEngine.shouldInterceptRequest(request, normalizedUrl) { item ->
                            mainHandler.post { ingest(listOf(item)) }
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (isCompleted || hydrationScanScheduled) return
                        hydrationScanScheduled = true
                        BotDetectionMitigator.injectStealth(view)
                        BotDetectionMitigator.syncCookiesFromWebView(url)
                        if (BotDetectionMitigator.isBotChallenge(view?.title, url)) {
                            onStatusUpdate("Bypassing anti-bot verification...")
                        } else {
                            onStatusUpdate("Waiting for dynamic page media...")
                        }

                        // Keep observing mutations while client-side frameworks hydrate the page.
                        wv.evaluateJavascript(MediaCaptureEngine.getMutationObserverScript(), null)
                        val scanScript = MediaCaptureEngine.getJsDomScanScript()
                        wv.evaluateJavascript(scanScript) { initialJson ->
                            ingest(MediaCaptureEngine.parseJsDomScanResult(initialJson, normalizedUrl))

                            // Do not stop at the first image: it is often just a logo or a placeholder.
                            mainHandler.postDelayed({
                                if (!isCompleted) {
                                    onStatusUpdate("Scanning hydrated page content...")
                                    // Trigger lazy-loaded images and infinite-scroll media.
                                    wv.evaluateJavascript(
                                        "(function(){var h=Math.max(document.body?document.body.scrollHeight:0,document.documentElement?document.documentElement.scrollHeight:0);window.scrollTo(0,h);return true;})()",
                                        null
                                    )
                                    mainHandler.postDelayed({
                                        if (!isCompleted) {
                                            wv.evaluateJavascript(scanScript) { postScrollJson ->
                                                ingest(MediaCaptureEngine.parseJsDomScanResult(postScrollJson, normalizedUrl))
                                                wv.evaluateJavascript("window.scrollTo(0,0);true;", null)

                                                // One final pass catches media attached after the scroll-triggered load.
                                                mainHandler.postDelayed({
                                                    if (!isCompleted) {
                                                        wv.evaluateJavascript(scanScript) { settledJson ->
                                                            ingest(MediaCaptureEngine.parseJsDomScanResult(settledJson, normalizedUrl))
                                                            finish(if (seenMap.isEmpty()) "No media discovered" else null)
                                                        }
                                                    }
                                                }, 800L)
                                            }
                                        }
                                    }, 1800L)
                                }
                            }, 2400L)
                        }
                    }
                }

                // Allow time for SPA hydration, lazy loading, and the final DOM scan.
                mainHandler.postDelayed({
                    if (!isCompleted) {
                        finish(if (seenMap.isEmpty()) "Page scan timed out" else null)
                    }
                }, 16000L)

                wv.loadUrl(normalizedUrl)
            } catch (e: Exception) {
                Log.e("BrowserSession", "Failed headless browser initialization", e)
                cleanup()
                if (continuation.isActive) {
                    continuation.resume(
                        MediaExtractionResult(emptyList(), errorMessage = e.localizedMessage),
                        onCancellation = {}
                    )
                }
            }
        }
    }

    data class BackgroundCookieResult(
        val success: Boolean,
        val cookies: String?,
        val requiresSignIn: Boolean,
        val signInUrl: String?
    )

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchCookiesInBackground(
        context: Context,
        targetUrl: String,
        onStatusUpdate: (String) -> Unit = {}
    ): BackgroundCookieResult = suspendCancellableCoroutine { continuation ->
        mainHandler.post {
            val normalizedUrl = if (!targetUrl.startsWith("http://", ignoreCase = true) && !targetUrl.startsWith("https://", ignoreCase = true)) {
                "https://$targetUrl"
            } else targetUrl

            val domain = runCatching { URL(normalizedUrl).host }.getOrDefault("")
            var webView: WebView? = null
            var isCompleted = false

            fun cleanup() {
                try {
                    mainHandler.removeCallbacksAndMessages(null)
                    (webView?.parent as? ViewGroup)?.removeView(webView)
                    webView?.stopLoading()
                    webView?.webViewClient = WebViewClient()
                    webView?.webChromeClient = WebChromeClient()
                    webView?.destroy()
                } catch (_: Throwable) {
                } finally {
                    webView = null
                }
            }

            continuation.invokeOnCancellation {
                mainHandler.post { cleanup() }
            }

            fun finish(requiresSignIn: Boolean, signInUrl: String?) {
                if (isCompleted) return
                isCompleted = true
                try {
                    val cm = CookieManager.getInstance()
                    cm.flush()
                    val rawCookies = cm.getCookie(normalizedUrl) ?: cm.getCookie("https://$domain")
                    cleanup()
                    if (continuation.isActive) {
                        continuation.resume(
                            BackgroundCookieResult(
                                success = !rawCookies.isNullOrBlank() || !requiresSignIn,
                                cookies = rawCookies,
                                requiresSignIn = requiresSignIn,
                                signInUrl = signInUrl
                            ),
                            onCancellation = { cleanup() }
                        )
                    }
                } catch (e: Exception) {
                    cleanup()
                    if (continuation.isActive) {
                        continuation.resume(
                            BackgroundCookieResult(
                                success = false,
                                cookies = null,
                                requiresSignIn = requiresSignIn,
                                signInUrl = signInUrl
                            ),
                            onCancellation = { cleanup() }
                        )
                    }
                }
            }

            try {
                val act = findActivity(context)
                val wv = if (act != null) WebView(act) else WebView(context)
                webView = wv

                if (act != null && !act.isFinishing && !act.isDestroyed) {
                    val root = act.findViewById<ViewGroup>(android.R.id.content)
                    if (root != null) {
                        wv.alpha = 0.001f
                        wv.isClickable = false
                        wv.isFocusable = false
                        val lp = ViewGroup.LayoutParams(1080, 1920)
                        try { root.addView(wv, 0, lp) } catch (_: Throwable) { root.addView(wv, lp) }
                    }
                }

                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    mediaPlaybackRequiresUserGesture = false
                    loadsImagesAutomatically = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString = BotDetectionMitigator.STEALTH_USER_AGENT
                }

                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        BotDetectionMitigator.injectStealth(view)
                        if (BotDetectionMitigator.isSignInPage(url, view?.title)) {
                            onStatusUpdate("Sign-in required for $domain")
                            finish(requiresSignIn = true, signInUrl = url)
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        BotDetectionMitigator.injectStealth(view)
                        val title = view?.title
                        if (BotDetectionMitigator.isSignInPage(url, title)) {
                            finish(requiresSignIn = true, signInUrl = url)
                            return
                        }

                        // Check for password inputs in DOM indicating login requirement
                        view?.evaluateJavascript(
                            "Boolean(document.querySelector('input[type=\"password\"]') || document.body.innerText.includes('Sign in to confirm your age') || document.body.innerText.includes('Sign in to confirm you\\'re not a bot') || document.body.innerText.includes('Log in to continue'))"
                        ) { result ->
                            val isPwdForm = result == "true"
                            if (isPwdForm) {
                                finish(requiresSignIn = true, signInUrl = url)
                            } else {
                                mainHandler.postDelayed({
                                    finish(requiresSignIn = false, signInUrl = null)
                                }, 1200L)
                            }
                        }
                    }
                }

                // 10-second timeout
                mainHandler.postDelayed({
                    if (!isCompleted) {
                        finish(requiresSignIn = false, signInUrl = null)
                    }
                }, 10000L)

                onStatusUpdate("Obtaining cookies in background for $domain...")
                wv.loadUrl(normalizedUrl)
            } catch (e: Exception) {
                Log.e("BrowserSession", "Failed background cookie fetch", e)
                cleanup()
                if (continuation.isActive) {
                    continuation.resume(
                        BackgroundCookieResult(success = false, cookies = null, requiresSignIn = false, signInUrl = null),
                        onCancellation = {}
                    )
                }
            }
        }
    }
}
