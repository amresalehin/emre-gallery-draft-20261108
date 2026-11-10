package com.amresalehin.emregallery.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.*
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import android.app.Activity
import android.os.Build
import com.amresalehin.emregallery.data.BotDetectionMitigator
import com.amresalehin.emregallery.data.BrowserHistoryManager
import com.amresalehin.emregallery.data.BrowserSession
import com.amresalehin.emregallery.data.BrowserShortcut
import com.amresalehin.emregallery.data.BrowserTabItem
import com.amresalehin.emregallery.data.DownloaderSession
import com.amresalehin.emregallery.data.ExtractionSource
import com.amresalehin.emregallery.data.MediaCaptureEngine
import com.amresalehin.emregallery.data.MediaClassifier
import com.amresalehin.emregallery.data.PreferencesRepository
import com.amresalehin.emregallery.data.ScrapedMediaItem
import kotlinx.coroutines.launch
import java.net.URL

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DirectBrowserView(
    initialUrl: String = "",
    onClose: (() -> Unit)? = null,
    onMenuClick: (() -> Unit)? = null,
    onUrlChange: ((String) -> Unit)? = null,
    onPageTitleChanged: ((String) -> Unit)? = null,
    onMediaExtracted: (ScrapedMediaItem) -> Unit,
    onBatchExtracted: (List<ScrapedMediaItem>, pageTitle: String?, domain: String?) -> Unit,
    onSwitchToMediaTab: () -> Unit = {},
    onOpenDownloads: (() -> Unit)? = null,
    onOpenDiscoveredMedia: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val historyManager = remember { BrowserHistoryManager(context) }
    val tabSession = remember { BrowserSession.getTabSession("tab_main") }
    val tabDetectedCount by tabSession.detectedCount.collectAsState()
    val sessionDiscoveredItems by DownloaderSession.discoveredItems.collectAsState()

    val prefsRepo = remember { PreferencesRepository(context) }
    val themePref by prefsRepo.theme.collectAsState(initial = "system")
    val systemInDark = isSystemInDarkTheme()
    val isDark = when (themePref) {
        "dark" -> true
        "light" -> false
        else -> systemInDark
    }

    val shortcutsList by historyManager.shortcuts.collectAsState(initial = historyManager.defaultShortcuts)
    val allHistorySites by historyManager.allSites.collectAsState(initial = historyManager.defaultRecentSites)
    val tabsList by historyManager.openTabs.collectAsState(initial = historyManager.defaultTabs)

    val startUrl = remember {
        val trimmed = initialUrl.trim()
        if (trimmed.isNotBlank()) {
            MediaClassifier.formatQueryOrUrl(trimmed)
        } else {
            "" // Clean monochrome home page by default
        }
    }

    var currentUrl by remember { mutableStateOf(startUrl) }
    val totalDetectedCount = if (currentUrl.isBlank() || currentUrl == "about:blank") {
        0
    } else {
        maxOf(tabDetectedCount, sessionDiscoveredItems.size)
    }
    var addressInput by remember { mutableStateOf(startUrl) }
    var pageTitle by remember { mutableStateOf("") }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingProgress by remember { mutableFloatStateOf(0f) }
    var isDesktopMode by remember { mutableStateOf(false) }

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    // Dialog & Sheet States
    var showAddShortcutDialog by remember { mutableStateOf(false) }
    var selectedShortcutForEdit by remember { mutableStateOf<BrowserShortcut?>(null) }
    var showHistorySheet by remember { mutableStateOf(false) }
    var showTabsSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var activeTabId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tabsList) {
        if (tabsList.isEmpty()) {
            val newId = "tab_${System.currentTimeMillis()}"
            val initialTab = BrowserTabItem(
                id = newId,
                title = if (pageTitle.isNotBlank()) pageTitle else if (currentUrl.isNotBlank()) MediaClassifier.cleanDomainFromUrl(currentUrl) else "Home",
                url = currentUrl
            )
            activeTabId = newId
            historyManager.saveTabs(listOf(initialTab))
        } else if (activeTabId == null || tabsList.none { it.id == activeTabId }) {
            val matching = tabsList.firstOrNull { it.url.isNotBlank() && it.url.equals(currentUrl, ignoreCase = true) }
                ?: tabsList.first()
            activeTabId = matching.id
        }
    }

    fun syncCurrentTabWithPage(targetUrl: String, targetTitle: String) {
        val tabId = activeTabId ?: return
        val currentTabs = tabsList
        val existing = currentTabs.firstOrNull { it.id == tabId }
        val displayTitle = if (targetTitle.isNotBlank() && targetTitle != "about:blank") {
            targetTitle
        } else if (targetUrl.isNotBlank() && targetUrl != "about:blank") {
            MediaClassifier.cleanDomainFromUrl(targetUrl)
        } else {
            "Home"
        }
        val cleanUrl = if (targetUrl == "about:blank") "" else targetUrl
        if (existing == null || existing.url != cleanUrl || existing.title != displayTitle) {
            val updatedTabs = if (existing == null) {
                currentTabs + BrowserTabItem(id = tabId, title = displayTitle, url = cleanUrl)
            } else {
                currentTabs.map {
                    if (it.id == tabId) it.copy(title = displayTitle, url = cleanUrl, timestamp = System.currentTimeMillis())
                    else it
                }
            }
            coroutineScope.launch {
                historyManager.saveTabs(updatedTabs)
            }
        }
    }

    val currentView = LocalView.current
    DisposableEffect(isDark) {
        val window = (currentView.context as? Activity)?.window
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, currentView)
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
        }
        onDispose { }
    }

    val scrollHandler = remember { BrowserToolbarScrollHandler() }

    LaunchedEffect(currentUrl) {
        if (currentUrl.isBlank()) {
            scrollHandler.show()
        }
    }

    LaunchedEffect(Unit) {
        if (startUrl.isBlank()) {
            tabSession.clearMedia()
            tabSession.updateNavigation("")
            DownloaderSession.clear()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewInstance?.let { wv ->
                wv.stopLoading()
                wv.webViewClient = WebViewClient()
                wv.webChromeClient = WebChromeClient()
                wv.destroy()
                webViewInstance = null
            }
        }
    }

    // Intercept back button when browser can navigate back, or return to home
    BackHandler(enabled = canGoBack || currentUrl.isNotBlank() || onClose != null) {
        when {
            showHistorySheet -> showHistorySheet = false
            showTabsSheet -> showTabsSheet = false
            showSettingsSheet -> showSettingsSheet = false
            showAddShortcutDialog -> showAddShortcutDialog = false
            selectedShortcutForEdit != null -> selectedShortcutForEdit = null
            canGoBack && webViewInstance?.canGoBack() == true -> webViewInstance?.goBack()
            currentUrl.isNotBlank() -> {
                currentUrl = ""
                addressInput = ""
                pageTitle = ""
                onUrlChange?.invoke("")
                tabSession.clearMedia()
                tabSession.updateNavigation("")
                DownloaderSession.clear()
                webViewInstance?.loadUrl("about:blank")
            }
            onClose != null -> onClose()
        }
    }

    fun navigateToUrl(target: String) {
        val trimmed = target.trim()
        if (trimmed.isBlank()) return
        scrollHandler.show()
        val formatted = MediaClassifier.formatQueryOrUrl(trimmed)
        tabSession.clearMedia()
        DownloaderSession.clear()
        addressInput = formatted
        currentUrl = formatted
        onUrlChange?.invoke(formatted)
        tabSession.updateNavigation(formatted, pageTitle)
        DownloaderSession.updateBrowserPage(formatted)
        syncCurrentTabWithPage(formatted, pageTitle)
        coroutineScope.launch {
            historyManager.recordVisit(formatted)
        }
        webViewInstance?.loadUrl(formatted)
        focusManager.clearFocus()
    }

    fun scanCurrentPageDOM(wv: WebView?) {
        if (wv == null) return
        val current = wv.url ?: return
        if (current.isBlank() || current == "about:blank" || currentUrl.isBlank()) return

        wv.evaluateJavascript(MediaCaptureEngine.getJsDomScanScript()) { jsonRaw ->
            val discovered = MediaCaptureEngine.parseJsDomScanResult(jsonRaw, current)
            if (discovered.isNotEmpty()) {
                val domain = runCatching { URL(current).host }.getOrDefault("")
                tabSession.registerMediaBatch(discovered, pageTitle)
                onBatchExtracted(discovered, pageTitle, domain)
                DownloaderSession.addDiscoveredItems(discovered, pageTitle)
            }
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("direct_browser_view"),
        color = BrowserThemeColors.background(isDark)
    ) {
        // The toolbar is a fixed layout section; webpage content begins below it.
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(1f)
                    .background(BrowserThemeColors.background(isDark))
                    .testTag("browser_pinned_top_controls")
            ) {
            // 1. Minimalist Monochrome Top Bar (always visible)
            BrowserTopBar(
                addressInput = addressInput,
                onAddressChange = { addressInput = it },
                onNavigate = { navigateToUrl(it) },
                onHamburgerClick = {
                    if (onMenuClick != null) {
                        onMenuClick.invoke()
                    } else {
                        showTabsSheet = true
                    }
                },
                onDiscoveredMediaClick = {
                    onOpenDiscoveredMedia?.invoke() ?: onSwitchToMediaTab()
                },
                discoveredCount = totalDetectedCount,
                onNewTabClick = {
                    val newId = "tab_${System.currentTimeMillis()}"
                    val newTab = BrowserTabItem(id = newId, title = "New Tab", url = "")
                    activeTabId = newId
                    currentUrl = ""
                    addressInput = ""
                    pageTitle = ""
                    onUrlChange?.invoke("")
                    tabSession.clearMedia()
                    tabSession.updateNavigation("")
                    DownloaderSession.clear()
                    scrollHandler.show()
                    webViewInstance?.loadUrl("about:blank")
                    coroutineScope.launch {
                        historyManager.saveTabs(tabsList + newTab)
                    }
                },
                onReloadClick = {
                    if (isLoading) webViewInstance?.stopLoading() else webViewInstance?.reload()
                },
                onPinCurrentSite = {
                    if (currentUrl.isNotBlank()) {
                        coroutineScope.launch {
                            historyManager.addShortcut(pageTitle.ifBlank { currentUrl }, currentUrl)
                        }
                    }
                },
                onSaveToPocket = {
                    if (currentUrl.isNotBlank()) {
                        val pocketRepo = com.amresalehin.emregallery.data.PocketRepository.getInstance(context)
                        val effectiveTitle = pageTitle.ifBlank { webViewInstance?.title ?: currentUrl }
                        
                        webViewInstance?.evaluateJavascript(
                            """
                            (function() {
                                try {
                                    var ogImg = document.querySelector('meta[property="og:image"]')?.content ||
                                                document.querySelector('meta[name="og:image"]')?.content ||
                                                document.querySelector('meta[name="twitter:image"]')?.content ||
                                                document.querySelector('link[rel="apple-touch-icon"]')?.href || '';
                                    var ogDesc = document.querySelector('meta[property="og:description"]')?.content ||
                                                 document.querySelector('meta[name="description"]')?.content || '';
                                    return JSON.stringify({ image: ogImg, desc: ogDesc });
                                } catch(e) {
                                    return JSON.stringify({ image: '', desc: '' });
                                }
                            })();
                            """.trimIndent()
                        ) { resultJson ->
                            var previewImg = ""
                            var pageDesc = ""
                            if (!resultJson.isNullOrBlank() && resultJson != "null") {
                                try {
                                    val unquoted = if (resultJson.startsWith("\"") && resultJson.endsWith("\"")) {
                                        org.json.JSONTokener(resultJson).nextValue().toString()
                                    } else {
                                        resultJson
                                    }
                                    val json = org.json.JSONObject(unquoted)
                                    previewImg = json.optString("image", "")
                                    pageDesc = json.optString("desc", "")
                                } catch (_: Exception) {}
                            }

                            pocketRepo.addBookmark(
                                title = effectiveTitle,
                                url = currentUrl,
                                description = pageDesc,
                                previewImageUrl = previewImg,
                                autoFetchMetadata = true
                            )
                        } ?: run {
                            pocketRepo.addBookmark(
                                title = effectiveTitle,
                                url = currentUrl,
                                autoFetchMetadata = true
                            )
                        }
                        Toast.makeText(context, "Saved to Pocket", Toast.LENGTH_SHORT).show()
                    }
                },
                onShareClick = {
                    if (currentUrl.isNotBlank()) {
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, currentUrl)
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "Share link"))
                    }
                },
                onDesktopToggle = { desktop ->
                    isDesktopMode = desktop
                    webViewInstance?.settings?.apply {
                        userAgentString = if (desktop) {
                            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        } else {
                            BotDetectionMitigator.STEALTH_USER_AGENT
                        }
                        useWideViewPort = desktop
                        loadWithOverviewMode = desktop
                    }
                    webViewInstance?.reload()
                },
                isDesktopMode = isDesktopMode,
                onClearHistoryClick = {
                    coroutineScope.launch {
                        historyManager.clearHistory()
                    }
                },
                onSettingsClick = {
                    showSettingsSheet = true
                },
                isWebPage = currentUrl.isNotBlank(),
                onHomeClick = {
                    currentUrl = ""
                    addressInput = ""
                    pageTitle = ""
                    onUrlChange?.invoke("")
                    tabSession.clearMedia()
                    tabSession.updateNavigation("")
                    DownloaderSession.clear()
                    scrollHandler.show()
                    webViewInstance?.loadUrl("about:blank")
                },
                onCloseBrowser = onClose,
                isDark = isDark,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )

            // Loading Progress Bar
            if (isLoading) {
                LinearProgressIndicator(
                    progress = { loadingProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp),
                    color = BrowserThemeColors.textPrimary(isDark),
                    trackColor = BrowserThemeColors.divider(isDark)
                )
            }

            }

            val isChallengeActive = remember(pageTitle, currentUrl) {
                BotDetectionMitigator.isBotChallenge(pageTitle, currentUrl)
            }
            AnimatedVisibility(
                visible = isChallengeActive,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, start = 16.dp, end = 16.dp, bottom = 8.dp)
            ) {
                Surface(
                    color = Color(0xFFFEF3C7),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("browser_bot_challenge_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = Color(0xFF92400E),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Security Verification: Tap captcha checkbox if prompted to continue.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF92400E),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }


            // The browser viewport uses the remaining height beneath the toolbar.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // Interactive WebView (always instantiated for high-speed responsiveness)
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewInstance = this
                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )

                            setBackgroundColor(if (isDark) android.graphics.Color.parseColor("#111111") else android.graphics.Color.WHITE)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                settings.forceDark = if (isDark) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
                            }

                            var startTouchY = 0f
                            var startTouchX = 0f
                            var accumulatedTouchDy = 0f

                            setOnTouchListener { _, event ->
                                when (event.actionMasked) {
                                    MotionEvent.ACTION_DOWN -> {
                                        startTouchY = event.rawY
                                        startTouchX = event.rawX
                                        accumulatedTouchDy = 0f
                                    }
                                    MotionEvent.ACTION_MOVE -> {
                                        val dy = startTouchY - event.rawY
                                        val dx = startTouchX - event.rawX
                                        startTouchY = event.rawY
                                        startTouchX = event.rawX

                                        if (kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                                            if (dy > 0f) {
                                                if (accumulatedTouchDy < 0f) accumulatedTouchDy = 0f
                                                accumulatedTouchDy += dy
                                                if (accumulatedTouchDy >= scrollHandler.thresholdPx) {
                                                    scrollHandler.hide()
                                                }
                                            } else if (dy < 0f) {
                                                if (accumulatedTouchDy > 0f) accumulatedTouchDy = 0f
                                                accumulatedTouchDy += dy
                                                if (accumulatedTouchDy <= -scrollHandler.thresholdPx) {
                                                    scrollHandler.show()
                                                }
                                            }
                                        }
                                    }
                                    MotionEvent.ACTION_UP,
                                    MotionEvent.ACTION_CANCEL -> {
                                        accumulatedTouchDy = 0f
                                        scrollHandler.onTouchEnd()
                                    }
                                }
                                false
                            }

                            setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                                val dy = scrollY - oldScrollY
                                scrollHandler.onScroll(scrollY, dy)
                            }

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                javaScriptCanOpenWindowsAutomatically = true
                                setSupportMultipleWindows(false)
                                cacheMode = WebSettings.LOAD_DEFAULT
                                builtInZoomControls = true
                                displayZoomControls = false
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                mediaPlaybackRequiresUserGesture = false
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                userAgentString = BotDetectionMitigator.STEALTH_USER_AGENT
                            }

                            addJavascriptInterface(
                                BrowserSession.EmreMediaBridge { rawUrl ->
                                    val item = MediaCaptureEngine.buildMediaItem(
                                        rawUrl = rawUrl,
                                        source = ExtractionSource.DOM_SCRAPE,
                                        baseUrl = currentUrl
                                    )
                                    if (item != null) {
                                        tabSession.registerMedia(item)
                                        onMediaExtracted(item)
                                        DownloaderSession.addDiscoveredItem(item)
                                    }
                                },
                                "EmreBridge"
                            )

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    loadingProgress = newProgress / 100f
                                    isLoading = newProgress < 100
                                }

                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    pageTitle = title.orEmpty()
                                    onPageTitleChanged?.invoke(pageTitle)
                                    tabSession.updateNavigation(currentUrl, pageTitle)
                                    if (currentUrl.isNotBlank() && currentUrl != "about:blank") {
                                        syncCurrentTabWithPage(currentUrl, pageTitle)
                                    }
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                private fun handleExternalNavigation(view: WebView?, urlString: String): Boolean {
                                    val lower = urlString.trim().lowercase()
                                    if (lower.startsWith("http://") || lower.startsWith("https://") ||
                                        lower.startsWith("about:") || lower.startsWith("data:") ||
                                        lower.startsWith("javascript:") || lower.startsWith("blob:") ||
                                        lower.startsWith("file:///android_asset/")
                                    ) {
                                        return false
                                    }

                                    val context = view?.context ?: return false

                                    if (lower.startsWith("intent://") || lower.startsWith("intent:")) {
                                        try {
                                            val intent = Intent.parseUri(urlString, Intent.URI_INTENT_SCHEME)
                                            if (intent != null) {
                                                val pm = context.packageManager
                                                val resolveInfo = intent.resolveActivity(pm)
                                                if (resolveInfo != null) {
                                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    context.startActivity(intent)
                                                    return true
                                                }

                                                val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                                                if (!fallbackUrl.isNullOrBlank() &&
                                                    (fallbackUrl.startsWith("http://", ignoreCase = true) || fallbackUrl.startsWith("https://", ignoreCase = true))
                                                ) {
                                                    view?.loadUrl(fallbackUrl)
                                                    return true
                                                }

                                                val packageName = intent.`package`
                                                if (!packageName.isNullOrBlank()) {
                                                    val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                    if (marketIntent.resolveActivity(pm) != null) {
                                                        context.startActivity(marketIntent)
                                                        return true
                                                    } else {
                                                        view?.loadUrl("https://play.google.com/store/apps/details?id=$packageName")
                                                        return true
                                                    }
                                                }
                                            }
                                        } catch (e: Exception) {
                                            Log.w("DirectBrowserView", "Failed to resolve intent scheme: $urlString", e)
                                        }
                                        return true
                                    }

                                    try {
                                        val uri = Uri.parse(urlString)
                                        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        val pm = context.packageManager
                                        if (intent.resolveActivity(pm) != null) {
                                            context.startActivity(intent)
                                            return true
                                        } else if (lower.startsWith("market://details?id=")) {
                                            val pkg = uri.getQueryParameter("id")
                                            if (!pkg.isNullOrBlank()) {
                                                view?.loadUrl("https://play.google.com/store/apps/details?id=$pkg")
                                                return true
                                            }
                                        }
                                    } catch (e: ActivityNotFoundException) {
                                        Log.w("DirectBrowserView", "No app available to handle scheme: $urlString", e)
                                        return true
                                    } catch (e: Exception) {
                                        Log.w("DirectBrowserView", "Failed to handle external URI: $urlString", e)
                                        return true
                                    }

                                    return true
                                }

                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val reqUrl = request?.url?.toString() ?: return false
                                    return handleExternalNavigation(view, reqUrl)
                                }

                                @Suppress("DEPRECATION")
                                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                    if (url.isNullOrBlank()) return false
                                    return handleExternalNavigation(view, url)
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    scrollHandler.show()
                                    if (url.isNullOrBlank() || url == "about:blank") {
                                        tabSession.clearMedia()
                                        tabSession.updateNavigation("")
                                        DownloaderSession.clear()
                                        isLoading = false
                                        canGoBack = false
                                        canGoForward = false
                                        syncCurrentTabWithPage("", "Home")
                                        return
                                    }
                                    tabSession.clearMedia()
                                    DownloaderSession.clear()
                                    isLoading = true
                                    BotDetectionMitigator.injectStealth(view)
                                    currentUrl = url
                                    addressInput = url
                                    onUrlChange?.invoke(url)
                                    tabSession.updateNavigation(url, pageTitle)
                                    DownloaderSession.updateBrowserPage(url)
                                    syncCurrentTabWithPage(url, pageTitle)
                                    canGoBack = view?.canGoBack() == true
                                    canGoForward = view?.canGoForward() == true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    isLoading = false
                                    if (url.isNullOrBlank() || url == "about:blank") {
                                        tabSession.updateNavigation("")
                                        canGoBack = false
                                        canGoForward = false
                                        syncCurrentTabWithPage("", "Home")
                                        return
                                    }
                                    BotDetectionMitigator.injectStealth(view)
                                    BotDetectionMitigator.syncCookiesFromWebView(url)
                                    currentUrl = url
                                    addressInput = url
                                    onUrlChange?.invoke(url)
                                    val finalTitle = view?.title.orEmpty().ifBlank { pageTitle }
                                    pageTitle = finalTitle
                                    tabSession.updateNavigation(url, finalTitle)
                                    syncCurrentTabWithPage(url, finalTitle)
                                    coroutineScope.launch {
                                        historyManager.recordVisit(url, view?.title)
                                    }
                                    view?.evaluateJavascript(MediaCaptureEngine.getMutationObserverScript(), null)
                                    scanCurrentPageDOM(view)
                                    canGoBack = view?.canGoBack() == true
                                    canGoForward = view?.canGoForward() == true
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    val reqUrl = request?.url?.toString() ?: return null
                                    if (reqUrl.startsWith("about:")) return null

                                    return MediaCaptureEngine.shouldInterceptRequest(
                                        request = request,
                                        baseUrl = currentUrl
                                    ) { item ->
                                        tabSession.registerMedia(item)
                                        DownloaderSession.addDiscoveredItem(item)
                                        onMediaExtracted(item)
                                    }
                                }
                            }

                            if (startUrl.isNotBlank()) {
                                loadUrl(startUrl)
                            }
                        }
                    },
                    update = { wv ->
                        webViewInstance = wv
                        wv.setBackgroundColor(if (isDark) android.graphics.Color.parseColor("#111111") else android.graphics.Color.WHITE)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            wv.settings.forceDark = if (isDark) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
                        }
                    },
                    onRelease = { wv ->
                        wv.stopLoading()
                        wv.webViewClient = WebViewClient()
                        wv.webChromeClient = WebChromeClient()
                        wv.destroy()
                        if (webViewInstance === wv) {
                            webViewInstance = null
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("browser_webview_canvas")
                )

                // 2. Minimalist Monochrome Mobile Browser Home Layout (blank when empty)
                if (currentUrl.isBlank()) {
                    BrowserHomeContent(
                        shortcuts = shortcutsList,
                        recentSites = allHistorySites,
                        onNavigate = { navigateToUrl(it) },
                        onAddShortcutClick = { showAddShortcutDialog = true },
                        onEditShortcutClick = { selectedShortcutForEdit = it },
                        onSeeAllHistoryClick = { showHistorySheet = true },
                        isDark = isDark,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("browser_empty_home_page")
                    )
                }

                // 3. Floating Bottom Action Bar with auto-hide slide-and-fade animation
                androidx.compose.animation.AnimatedVisibility(
                    visible = scrollHandler.isVisible,
                    enter = slideInVertically(
                        initialOffsetY = { fullHeight -> fullHeight },
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ) + fadeIn(animationSpec = tween(durationMillis = 200)),
                    exit = slideOutVertically(
                        targetOffsetY = { fullHeight -> fullHeight },
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ) + fadeOut(animationSpec = tween(durationMillis = 200)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .wrapContentHeight()
                ) {
                    BrowserBottomNavBar(
                        tabCount = tabsList.size,
                        onOpenDownloads = {
                            onOpenDownloads?.invoke() ?: onSwitchToMediaTab()
                        },
                        onOpenHistory = {
                            showHistorySheet = true
                        },
                        onOpenTabs = {
                            showTabsSheet = true
                        },
                        onOpenBrowserSettings = {
                            showSettingsSheet = true
                        },
                        isDark = isDark,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    // Modal Dialogs & Sheets
    if (showAddShortcutDialog) {
        AddShortcutDialog(
            onDismiss = { showAddShortcutDialog = false },
            onAdd = { title, url ->
                coroutineScope.launch {
                    historyManager.addShortcut(title, url)
                }
            },
            isDark = isDark
        )
    }

    selectedShortcutForEdit?.let { sc ->
        EditShortcutDialog(
            shortcut = sc,
            onDismiss = { selectedShortcutForEdit = null },
            onUpdate = { title, url ->
                coroutineScope.launch {
                    historyManager.updateShortcut(sc.id, title, url)
                }
            },
            onDelete = {
                coroutineScope.launch {
                    historyManager.removeShortcut(sc.id)
                }
            },
            isDark = isDark
        )
    }

    if (showHistorySheet) {
        BrowserHistoryBottomSheet(
            history = allHistorySites,
            onNavigate = { navigateToUrl(it) },
            onDeleteSite = { url ->
                coroutineScope.launch {
                    historyManager.deleteSite(url)
                }
            },
            onClearAll = {
                coroutineScope.launch {
                    historyManager.clearHistory()
                }
            },
            onDismiss = { showHistorySheet = false },
            isDark = isDark
        )
    }

    if (showTabsSheet) {
        BrowserTabsBottomSheet(
            tabs = tabsList,
            currentUrl = currentUrl,
            activeTabId = activeTabId,
            onSelectTab = { tab ->
                activeTabId = tab.id
                currentUrl = tab.url
                addressInput = tab.url
                pageTitle = tab.title
                onUrlChange?.invoke(tab.url)
                tabSession.clearMedia()
                tabSession.updateNavigation(tab.url, tab.title)
                DownloaderSession.clear()
                scrollHandler.show()
                if (tab.url.isNotBlank()) {
                    webViewInstance?.loadUrl(tab.url)
                } else {
                    webViewInstance?.loadUrl("about:blank")
                }
            },
            onCloseTab = { tabId ->
                val remaining = tabsList.filter { it.id != tabId }
                if (remaining.isEmpty()) {
                    val freshId = "tab_${System.currentTimeMillis()}"
                    val freshTab = BrowserTabItem(id = freshId, title = "New Tab", url = "")
                    activeTabId = freshId
                    currentUrl = ""
                    addressInput = ""
                    pageTitle = ""
                    onUrlChange?.invoke("")
                    tabSession.clearMedia()
                    tabSession.updateNavigation("")
                    DownloaderSession.clear()
                    scrollHandler.show()
                    webViewInstance?.loadUrl("about:blank")
                    coroutineScope.launch {
                        historyManager.saveTabs(listOf(freshTab))
                    }
                } else {
                    if (activeTabId == tabId) {
                        val nextTab = remaining.last()
                        activeTabId = nextTab.id
                        currentUrl = nextTab.url
                        addressInput = nextTab.url
                        pageTitle = nextTab.title
                        onUrlChange?.invoke(nextTab.url)
                        tabSession.clearMedia()
                        tabSession.updateNavigation(nextTab.url, nextTab.title)
                        DownloaderSession.clear()
                        scrollHandler.show()
                        if (nextTab.url.isNotBlank()) {
                            webViewInstance?.loadUrl(nextTab.url)
                        } else {
                            webViewInstance?.loadUrl("about:blank")
                        }
                    }
                    coroutineScope.launch {
                        historyManager.saveTabs(remaining)
                    }
                }
            },
            onNewTab = {
                val newId = "tab_${System.currentTimeMillis()}"
                val newTab = BrowserTabItem(id = newId, title = "New Tab", url = "")
                activeTabId = newId
                currentUrl = ""
                addressInput = ""
                pageTitle = ""
                onUrlChange?.invoke("")
                tabSession.clearMedia()
                tabSession.updateNavigation("")
                DownloaderSession.clear()
                scrollHandler.show()
                webViewInstance?.loadUrl("about:blank")
                coroutineScope.launch {
                    historyManager.saveTabs(tabsList + newTab)
                }
            },
            onDismiss = { showTabsSheet = false },
            isDark = isDark
        )
    }

    if (showSettingsSheet) {
        BrowserSettingsBottomSheet(
            isDesktopMode = isDesktopMode,
            onDesktopModeChange = { desktop ->
                isDesktopMode = desktop
                webViewInstance?.settings?.apply {
                    userAgentString = if (desktop) {
                        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    } else {
                        BotDetectionMitigator.STEALTH_USER_AGENT
                    }
                    useWideViewPort = desktop
                    loadWithOverviewMode = desktop
                }
                webViewInstance?.reload()
            },
            onClearCacheClick = {
                webViewInstance?.clearCache(true)
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            },
            onDismiss = { showSettingsSheet = false },
            isDark = isDark
        )
    }
}
