package com.amresalehin.emregallery.data

import org.jsoup.nodes.Element
import java.net.URI
import java.util.Locale

/**
 * Content-Aware Media Extraction Filter.
 * Distinguishes genuine page media (photos, videos, audio, art, illustrations, diagrams)
 * from website chrome (logos, favicons, navigation icons, donation banners, button images,
 * footer graphics, and decorative CSS backgrounds).
 */
object ContentAwareMediaFilter {

    // Keywords in URLs, file names, or classes that represent UI chrome
    private val CHROME_KEYWORDS = setOf(
        "favicon", "site-logo", "header-logo", "brand-logo", "nav-logo", "logo",
        "wordmark", "tagline", "watermark",
        "avatar_default", "default-avatar", "placeholder-avatar",
        "donate", "donation", "fundraising", "paypal", "patreon", "kofi",
        "poweredby_mediawiki", "wikimedia-button", "license-badge", "badge",
        "magnifying-glass", "search-icon", "nav-icon", "menu-icon", "close-icon",
        "arrow-left", "arrow-right", "arrow-up", "arrow-down", "chevron",
        "bullet", "divider", "spacer", "blank.gif", "transparent.gif", "pixel.gif",
        "1x1.png", "1x1.gif", "1x1.jpg", "spinner.gif", "loading.gif",
        "app-store-badge", "google-play-badge", "social-share", "share-button"
    )

    // Selectors representing website interface chrome
    private val CHROME_SELECTORS = listOf(
        "header", "nav", "footer", "aside", "menu", "form",
        "[role=banner]", "[role=navigation]", "[role=contentinfo]", "[role=menubar]",
        ".header", ".site-header", ".top-header", ".global-header", ".vector-header",
        ".nav", ".navbar", ".navigation", ".site-nav", ".main-nav", ".menu", ".sidebar",
        "#header", "#nav", "#navigation", "#navbar", "#sidebar", "#footer", "#mw-navigation", "#p-logo",
        ".footer", ".site-footer", ".page-footer", ".global-footer",
        ".breadcrumb", ".breadcrumbs", ".pagination", ".toolbar", ".action-bar",
        ".ad", ".ads", ".advertisement", ".banner-ad", ".sponsor", ".sponsored",
        ".cookie", ".cookie-banner", ".consent", ".privacy-banner",
        ".social-share", ".share-buttons", ".comments-nav"
    )

    // Selectors representing primary content containers
    private val CONTENT_CONTAINER_SELECTORS = listOf(
        "article", "main", "[role=main]", "#content", "#main-content",
        ".mw-parser-output", ".gallery", ".content", ".entry-content",
        ".post-content", "figure", ".media-container", ".photo-gallery"
    )

    /**
     * Evaluates whether a media URL or its metadata indicates website chrome rather than content.
     */
    fun isUiChromeUrl(
        url: String,
        title: String? = null,
        width: Int? = null,
        height: Int? = null,
        isVideoOrAudioOrStream: Boolean = false
    ): Boolean {
        // Videos, audio tracks, and streams are almost never UI chrome
        if (isVideoOrAudioOrStream) return false

        val lowerUrl = url.lowercase(Locale.ROOT)
        val lowerTitle = title?.lowercase(Locale.ROOT) ?: ""
        val cleanPath = lowerUrl.substringBefore("?").substringBefore("#")
        val fileName = cleanPath.substringAfterLast('/')

        // 1. Direct keywords in filename or URL
        for (kw in CHROME_KEYWORDS) {
            if (fileName.contains(kw) || lowerUrl.contains("/$kw") || lowerUrl.contains("$kw.") || lowerUrl.contains("-$kw-") || lowerUrl.contains("_${kw}_")) {
                return true
            }
            if (lowerTitle.contains(kw)) {
                return true
            }
        }

        // 2. Wikipedia / Wikimedia specific chrome assets
        if (lowerUrl.contains("wikimedia.org") || lowerUrl.contains("wikipedia.org")) {
            if (lowerUrl.contains("/static/images/") || fileName.contains("wikipedia-logo") ||
                fileName.contains("wikimedia-button") || fileName.contains("wordmark") ||
                fileName.contains("poweredby_mediawiki") || fileName.contains("magnifying_glass") ||
                fileName.contains("wikisource") || fileName.contains("commons-logo") ||
                (fileName.contains("globe") && fileName.contains("logo"))
            ) {
                return true
            }
        }

        // 3. Tiny icon or tracking pixel spatial dimensions
        if (width != null && height != null && width > 0 && height > 0) {
            // Tiny square icons, favicons, or button graphics (<= 48x48)
            if (width <= 48 && height <= 48) return true
            // Extremely narrow tracking pixels or horizontal divider lines
            if (width <= 32 || height <= 32) return true
            // Extreme aspect ratio (dividers, borders, shadows)
            val ratio = width.toFloat() / height.toFloat()
            if (ratio > 12f || ratio < (1f / 12f)) return true
        }

        return false
    }

    /**
     * Checks whether an HTML DOM element in Jsoup is part of website chrome.
     */
    fun isUiChromeElement(element: Element): Boolean {
        val tagName = element.tagName().lowercase(Locale.ROOT)
        // Video and audio tags are preserved
        if (tagName == "video" || tagName == "audio") return false

        // Check if inside a chrome container without a closer content container
        var curr: Element? = element.parent()
        while (curr != null && curr.tagName() != "body" && curr.tagName() != "html") {
            val tag = curr.tagName().lowercase(Locale.ROOT)
            val id = curr.id().lowercase(Locale.ROOT)
            val className = curr.className().lowercase(Locale.ROOT)

            // If we hit a content container first, accept it as content
            if (tag == "article" || tag == "main" || tag == "figure" ||
                className.contains("mw-parser-output") || className.contains("gallery") ||
                className.contains("post-content") || className.contains("entry-content") ||
                id == "content" || id == "main-content"
            ) {
                // If the element itself is an obvious logo/icon inside the article, still reject
                break
            }

            // Check if ancestor matches chrome containers
            if (tag == "header" || tag == "nav" || tag == "footer" || tag == "aside" || tag == "menu" || tag == "form" ||
                curr.attr("role").equals("banner", ignoreCase = true) ||
                curr.attr("role").equals("navigation", ignoreCase = true) ||
                curr.attr("role").equals("contentinfo", ignoreCase = true) ||
                className.contains("navbar") || className.contains("nav") || className.contains("menu") ||
                className.contains("header") || className.contains("footer") || className.contains("sidebar") ||
                className.contains("breadcrumb") || className.contains("pagination") || className.contains("toolbar") ||
                id.contains("header") || id.contains("nav") || id.contains("footer") || id.contains("sidebar") ||
                id.contains("mw-navigation") || id.contains("p-logo")
            ) {
                return true
            }

            curr = curr.parent()
        }

        // Check element's own class, id, alt, or title
        val selfId = element.id().lowercase(Locale.ROOT)
        val selfClass = element.className().lowercase(Locale.ROOT)
        val selfAlt = element.attr("alt").lowercase(Locale.ROOT)
        val selfTitle = element.attr("title").lowercase(Locale.ROOT)

        for (kw in CHROME_KEYWORDS) {
            if (selfId.contains(kw) || selfClass.contains(kw) || selfAlt.contains(kw) || selfTitle.contains(kw)) {
                return true
            }
        }

        // Check declared width/height attributes
        val w = element.attr("width").toIntOrNull() ?: element.attr("data-width").toIntOrNull()
        val h = element.attr("height").toIntOrNull() ?: element.attr("data-height").toIntOrNull()
        if (w != null && h != null && w > 0 && h > 0) {
            if (w <= 48 && h <= 48) return true
            if (w <= 32 || h <= 32) return true
            val ratio = w.toFloat() / h.toFloat()
            if (ratio > 12f || ratio < (1f / 12f)) return true
        }

        return false
    }

    /**
     * Evaluates CSS background-image candidate element to ensure it's not a button, pattern, or gradient icon.
     */
    fun isAcceptableCssBackground(element: Element): Boolean {
        if (isUiChromeElement(element)) return false
        val tag = element.tagName().lowercase(Locale.ROOT)
        if (tag == "button" || tag == "input" || tag == "a" || tag == "span" || tag == "i") {
            // Small inline elements using background-image are almost always UI icons or sprites
            val w = element.attr("width").toIntOrNull() ?: 0
            val h = element.attr("height").toIntOrNull() ?: 0
            if (w < 120 || h < 120) return false
        }
        return true
    }
}
