package com.amresalehin.emregallery.data

import android.webkit.CookieManager
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Anti-bot detection mitigations for WebViews, headless scrapers, and network requests.
 * Addresses:
 * - Cloudflare Turnstile & WAF challenges
 * - Headless Chrome / WebView fingerprinting (`navigator.webdriver`, missing plugins, incomplete `window.chrome`)
 * - Mobile User-Agent and Client Hints (`Sec-Ch-Ua*`)
 * - Session cookie preservation and synchronization
 */
object BotDetectionMitigator {

    const val STEALTH_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 Mobile Safari/537.36"

    val STEALTH_HEADERS: Map<String, String> = mapOf(
        "User-Agent" to STEALTH_USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "en-US,en;q=0.9",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1"
    )

    const val STEALTH_JS = """
        (function() {
            try {
                Object.defineProperty(navigator, 'webdriver', {
                    get: () => undefined,
                    configurable: true
                });
            } catch(e) {}

            try {
                if (!window.chrome) {
                    window.chrome = {};
                }
                window.chrome.runtime = window.chrome.runtime || {};
                window.chrome.loadTimes = window.chrome.loadTimes || function() {};
                window.chrome.csi = window.chrome.csi || function() {};
                window.chrome.app = window.chrome.app || {};
            } catch(e) {}

            try {
                if (!navigator.plugins || navigator.plugins.length === 0) {
                    const fakePlugins = [
                        { name: 'Chrome PDF Plugin', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
                        { name: 'Chrome PDF Viewer', filename: 'mhjfbmdgcfjbbpaeojofohoefgiehjai', description: '' },
                        { name: 'Native Client', filename: 'internal-nacl-plugin', description: '' }
                    ];
                    Object.defineProperty(navigator, 'plugins', {
                        get: () => fakePlugins,
                        configurable: true
                    });
                }
            } catch(e) {}

            try {
                Object.defineProperty(navigator, 'languages', {
                    get: () => ['en-US', 'en'],
                    configurable: true
                });
                if (!navigator.hardwareConcurrency || navigator.hardwareConcurrency < 4) {
                    Object.defineProperty(navigator, 'hardwareConcurrency', { get: () => 8, configurable: true });
                }
                if (navigator.maxTouchPoints === 0) {
                    Object.defineProperty(navigator, 'maxTouchPoints', { get: () => 5, configurable: true });
                }
            } catch(e) {}

            try {
                const originalQuery = window.navigator.permissions && window.navigator.permissions.query;
                if (originalQuery) {
                    window.navigator.permissions.query = (parameters) => (
                        parameters.name === 'notifications' ?
                            Promise.resolve({ state: Notification.permission }) :
                            originalQuery(parameters)
                    );
                }
            } catch(e) {}
        })();
    """

    fun isBotChallenge(title: String?, url: String?): Boolean {
        val t = title.orEmpty().lowercase()
        val u = url.orEmpty().lowercase()
        return t.contains("just a moment") ||
                t.contains("attention required") ||
                t.contains("cloudflare") ||
                t.contains("security check") ||
                t.contains("checking your browser") ||
                t.contains("please wait") ||
                u.contains("challenge-platform") ||
                u.contains("cf-chl-widget")
    }

    fun injectStealth(webView: WebView?) {
        val current = webView?.url
        if (current.isNullOrBlank() || current == "about:blank") return
        webView.evaluateJavascript(STEALTH_JS, null)
    }

    fun syncCookiesFromWebView(url: String?) {
        if (url.isNullOrBlank() || url == "about:blank") return
        try {
            val cm = CookieManager.getInstance()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    cm.flush()
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    fun indicatesCookiesOrBotVerificationNeeded(error: String?): Boolean {
        if (error.isNullOrBlank()) return false
        val err = error.lowercase()
        return err.contains("sign in to confirm you're not a bot") ||
                err.contains("bot verification") ||
                err.contains("too many requests") ||
                err.contains("http error 429") ||
                err.contains("http error 403") ||
                err.contains("cf_clearance") ||
                err.contains("cloudflare") ||
                err.contains("cookies are required") ||
                err.contains("use --cookies") ||
                err.contains("checkpoint_required") ||
                err.contains("login required") ||
                err.contains("sign in to confirm your age") ||
                err.contains("only available to registered users") ||
                err.contains("authentication required") ||
                err.contains("rate limit")
    }

    fun indicatesSignInRequired(error: String?): Boolean {
        if (error.isNullOrBlank()) return false
        val err = error.lowercase()
        return err.contains("sign in to confirm your age") ||
                err.contains("only available to registered users") ||
                err.contains("login required") ||
                err.contains("authentication required") ||
                err.contains("private video") ||
                err.contains("private account") ||
                err.contains("redirected to login") ||
                err.contains("restricted video")
    }

    fun isSignInPage(url: String?, title: String?): Boolean {
        val u = url.orEmpty().lowercase()
        val t = title.orEmpty().lowercase()
        return u.contains("/login") ||
                u.contains("/signin") ||
                u.contains("accounts.google.com") ||
                u.contains("accounts/login") ||
                u.contains("/checkpoint/") ||
                u.contains("/auth/") ||
                t.startsWith("sign in") ||
                t.startsWith("log in") ||
                t.startsWith("login") ||
                t.contains("sign in - google") ||
                t.contains("welcome back")
    }
}
