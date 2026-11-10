package com.amresalehin.emregallery.data

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Locale

/**
 * High-performance Ad & Tracker Blocking Engine.
 * Evaluates hostnames, URLs, and network requests against curated blocklists of
 * tracking scripts, ad exchanges, telemetry endpoints, and banner networks.
 * Provides instant zero-payload WebResourceResponse to cancel ad requests in WebViews.
 */
object AdBlockEngine {

    // Curated high-impact ad and tracking hostnames (EasyList, AdGuard, Peter Lowe's)
    private val AD_HOST_EXACT = setOf(
        // Google Ads & Analytics
        "googleadservices.com",
        "googlesyndication.com",
        "adservice.google.com",
        "pagead2.googlesyndication.com",
        "partner.googleadservices.com",
        "doubleclick.net",
        "ad.doubleclick.net",
        "stats.g.doubleclick.net",
        "google-analytics.com",
        "analytics.google.com",
        "www.google-analytics.com",
        "ssl.google-analytics.com",

        // Major Ad Exchanges & Networks
        "criteo.com",
        "static.criteo.net",
        "bidder.criteo.com",
        "taboola.com",
        "cdn.taboola.com",
        "outbrain.com",
        "widgets.outbrain.com",
        "adnxs.com",
        "ib.adnxs.com",
        "appnexus.com",
        "rubiconproject.com",
        "optimized-by.rubiconproject.com",
        "fastlane.rubiconproject.com",
        "pubmatic.com",
        "ads.pubmatic.com",
        "openx.net",
        "casalemedia.com",
        "smartadserver.com",
        "amazon-adsystem.com",
        "aax.amazon-adsystem.com",
        "media.net",
        "contextual.media.net",
        "moatads.com",
        "adroll.com",
        "d.adroll.com",
        "sovrn.com",
        "revcontent.com",
        "mgid.com",
        "bidswitch.net",

        // Popups, Popunders, Aggressive mobile ad networks
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "exoclick.com",
        "main.exoclick.com",
        "trafficjunky.net",
        "juicyads.com",
        "zergnet.com",
        "infolinks.com",
        "chitika.com",
        "adcolony.com",
        "unityads.unity3d.com",
        "vungle.com",
        "chartboost.com",
        "ironsrc.com",
        "inmobi.com",

        // Telemetry, User tracking, Beacons
        "hotjar.com",
        "static.hotjar.com",
        "script.hotjar.com",
        "clarity.ms",
        "www.clarity.ms",
        "b.clarity.ms",
        "scorecardresearch.com",
        "b.scorecardresearch.com",
        "sb.scorecardresearch.com",
        "statcounter.com",
        "quantserve.com",
        "pixel.quantserve.com",
        "mixpanel.com",
        "api.mixpanel.com",
        "segment.io",
        "api.segment.io",
        "amplitude.com",
        "api.amplitude.com",
        "branch.io",
        "app.link",
        "appsflyer.com",
        "adjust.com",
        "stats.wp.com",
        "pixel.wp.com"
    )

    // Domain suffix patterns for recursive host matching
    private val AD_DOMAIN_SUFFIXES = listOf(
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
        "google-analytics.com",
        "criteo.com",
        "taboola.com",
        "outbrain.com",
        "adnxs.com",
        "rubiconproject.com",
        "pubmatic.com",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "exoclick.com",
        "trafficjunky.net",
        "moatads.com",
        "clarity.ms",
        "hotjar.com",
        "scorecardresearch.com"
    )

    // Ad path and query patterns that identify tracking and ad banner endpoints
    private val AD_PATH_PATTERNS = listOf(
        "/pagead/",
        "/adserver/",
        "/ad_banner",
        "/banner-ad",
        "/popunder",
        "/popup-ad",
        "/track/pixel",
        "/telemetry/pixel",
        "/beacon/event",
        "facebook.com/tr",
        "connect.facebook.net/en_us/fbevents.js",
        "analytics.twitter.com",
        "ads-twitter.com",
        "ads.tiktok.com",
        "tr.snapchat.com"
    )

    private val adCheckCache = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, Boolean>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                return size > 1000
            }
        }
    )

    /**
     * Checks if a target hostname belongs to an ad network or tracker.
     */
    fun isAdHost(host: String): Boolean {
        if (host.isBlank()) return false
        val lowerHost = host.lowercase(Locale.ROOT)
        if (AD_HOST_EXACT.contains(lowerHost)) return true
        for (suffix in AD_DOMAIN_SUFFIXES) {
            if (lowerHost == suffix || lowerHost.endsWith(".$suffix")) {
                return true
            }
        }
        return false
    }

    /**
     * Evaluates whether a complete URL or network request is an ad, tracker, or telemetry endpoint.
     */
    fun isAdOrTracker(url: String): Boolean {
        if (url.isBlank()) return false
        val lowerUrl = url.lowercase(Locale.ROOT)
        adCheckCache[lowerUrl]?.let { return it }

        // 1. Path pattern check
        for (pattern in AD_PATH_PATTERNS) {
            if (lowerUrl.contains(pattern)) {
                adCheckCache[lowerUrl] = true
                return true
            }
        }

        // 2. Extract and check hostname
        val host = runCatching {
            val candidate = if (!lowerUrl.startsWith("http://") && !lowerUrl.startsWith("https://")) {
                "https://$lowerUrl"
            } else {
                lowerUrl
            }
            java.net.URL(candidate).host
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: runCatching {
            val withoutScheme = lowerUrl.substringAfter("://")
            withoutScheme.substringBefore("/").substringBefore("?").substringBefore(":")
        }.getOrNull()

        val isBlocked = !host.isNullOrBlank() && isAdHost(host)
        adCheckCache[lowerUrl] = isBlocked
        return isBlocked
    }

    /**
     * Returns a zero-length empty WebResourceResponse to cleanly short-circuit
     * ad and tracker requests in WebViews without raising network error dialogs.
     */
    fun createEmptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            ByteArrayInputStream(ByteArray(0))
        )
    }
}
