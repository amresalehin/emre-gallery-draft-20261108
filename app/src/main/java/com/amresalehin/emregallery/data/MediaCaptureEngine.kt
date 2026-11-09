package com.amresalehin.emregallery.data

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/**
 * Unified Media Capture Engine.
 * Single source of truth for:
 * 1. Network request interception & stream/media classification.
 * 2. Deep JavaScript DOM, srcset, CSS background, and JSON-LD schema inspection.
 * 3. MutationObserver injection for dynamic DOM change detection.
 * 4. Adaptive scrolling logic.
 * 5. Media normalization, deduplication, and ScrapedMediaItem construction with headers.
 */
object MediaCaptureEngine {

    /**
     * Shared JavaScript DOM scan script that inspects:
     * - <img>, <picture source>, data-src, data-original, srcset, data-lazy-src
     * - <video>, <video source>, poster
     * - <audio>, <audio source>
     * - Schema.org JSON-LD (ImageObject, VideoObject, contentUrl)
     * - Content container CSS background-image
     * - IFrames pointing directly to media
     */
    fun getJsDomScanScript(): String {
        return """
            (function() {
                var list = [];
                function resolveAbs(u) {
                    if (!u) return null;
                    try {
                        return new URL(u, document.baseURI || window.location.href).href;
                    } catch(e) {
                        if (u.indexOf('//') === 0) return 'https:' + u;
                        return u;
                    }
                }

                function isChromeElement(el) {
                    if (!el) return false;
                    var chromeParent = el.closest('header, nav, footer, aside, menu, form, [role="banner"], [role="navigation"], [role="contentinfo"], .header, .site-header, .nav, .navbar, .site-nav, .menu, #header, #nav, #navbar, #footer, #mw-navigation, #p-logo, .footer, .site-footer, .breadcrumb, .breadcrumbs, .pagination, .toolbar, .ad, .ads, .advertisement, .cookie, .cookie-banner, .consent');
                    if (chromeParent) {
                        var contentParent = el.closest('article, main, [role="main"], #content, #main-content, .mw-parser-output, figure, .gallery, .shots-grid, .shots, .dribbble-shot, .entry-content, .post-content');
                        if (!contentParent || chromeParent.contains(contentParent) === false) {
                            return true;
                        }
                    }
                    var idAndClass = ((el.id || '') + ' ' + (el.className || '') + ' ' + (el.getAttribute('alt') || '')).toLowerCase();
                    if (/logo|favicon|avatar_default|badge|donate|donation|fundraising|paypal|magnifying-glass|nav-icon|menu-icon|close-icon|arrow-|chevron|bullet|spinner|1x1/.test(idAndClass)) {
                        return true;
                    }
                    return false;
                }

                // 1. Deep Image Inspection (img, picture, data attributes, srcset)
                var images = document.querySelectorAll('img, picture source');
                for (var i = 0; i < images.length; i++) {
                    var img = images[i];
                    if (isChromeElement(img)) continue;

                    var candidateUrls = [];

                    // Extract srcset highest resolution candidates
                    var srcset = img.getAttribute('srcset') || img.getAttribute('data-srcset');
                    if (srcset) {
                        var parts = srcset.split(',');
                        for (var p = 0; p < parts.length; p++) {
                            var trimmed = parts[p].trim();
                            if (trimmed) {
                                var splitBits = trimmed.split(/\s+/);
                                var u = splitBits[0];
                                var descriptor = splitBits[1] || '';
                                if (u && u.indexOf('data:') !== 0) {
                                    var absSrcset = resolveAbs(u);
                                    if (absSrcset) {
                                        list.push({u: absSrcset, s: 'SRCSET', r: descriptor});
                                    }
                                }
                            }
                        }
                    }

                    // Direct src & data-lazy attributes
                    var rawSrc = img.src || img.getAttribute('src');
                    var dataSrc = img.getAttribute('data-src') || img.getAttribute('data-original') ||
                                  img.getAttribute('data-lazy-src') || img.getAttribute('data-large-file') ||
                                  img.getAttribute('data-hi-res-src') || img.getAttribute('data-zoom-image');

                    if (dataSrc && dataSrc.indexOf('data:') !== 0) {
                        var absData = resolveAbs(dataSrc);
                        if (absData) candidateUrls.push({u: absData, s: 'DATA_SRC'});
                    }
                    if (rawSrc && rawSrc.indexOf('data:') !== 0) {
                        var absRaw = resolveAbs(rawSrc);
                        if (absRaw) candidateUrls.push({u: absRaw, s: 'DOM'});
                    }

                    for (var c = 0; c < candidateUrls.length; c++) {
                        list.push(candidateUrls[c]);
                    }
                }

                // 2. Video Elements & Sources
                var videos = document.querySelectorAll('video');
                for (var v = 0; v < videos.length; v++) {
                    var vid = videos[v];
                    var poster = resolveAbs(vid.getAttribute('poster') || vid.poster);
                    var chosenVideo = null;
                    if (vid.src && !vid.src.startsWith('blob:') && !vid.src.startsWith('data:')) {
                        chosenVideo = resolveAbs(vid.src);
                    } else {
                        var vsources = vid.querySelectorAll('source');
                        for (var j = 0; j < vsources.length; j++) {
                            var vsRaw = vsources[j].src;
                            if (vsRaw && !vsRaw.startsWith('blob:') && !vsRaw.startsWith('data:')) {
                                chosenVideo = resolveAbs(vsRaw);
                                break;
                            }
                        }
                    }
                    if (chosenVideo) {
                        list.push({u: chosenVideo, s: 'DOM', p: poster, r: 'video'});
                    } else if (poster) {
                        list.push({u: poster, s: 'POSTER', r: 'poster'});
                    }
                }

                // 3. Audio Elements
                var audios = document.querySelectorAll('audio');
                for (var a = 0; a < audios.length; a++) {
                    var au = audios[a];
                    var chosenAudio = null;
                    if (au.src && !au.src.startsWith('blob:') && !au.src.startsWith('data:')) {
                        chosenAudio = resolveAbs(au.src);
                    } else {
                        var asources = au.querySelectorAll('source');
                        for (var k = 0; k < asources.length; k++) {
                            var asRaw = asources[k].src;
                            if (asRaw && !asRaw.startsWith('blob:') && !asRaw.startsWith('data:')) {
                                chosenAudio = resolveAbs(asRaw);
                                break;
                            }
                        }
                    }
                    if (chosenAudio) {
                        list.push({u: chosenAudio, s: 'DOM', r: 'audio'});
                    }
                }

                // 4. Schema.org JSON-LD (ImageObject, VideoObject)
                var jsonLdScripts = document.querySelectorAll('script[type="application/ld+json"]');
                for (var jl = 0; jl < jsonLdScripts.length; jl++) {
                    try {
                        var parsed = JSON.parse(jsonLdScripts[jl].innerText);
                        var arr = Array.isArray(parsed) ? parsed : [parsed];
                        for (var idx = 0; idx < arr.length; idx++) {
                            var item = arr[idx];
                            if (item.contentUrl) {
                                var cu = resolveAbs(item.contentUrl);
                                if (cu) list.push({u: cu, s: 'DOM', r: 'json-ld'});
                            }
                            if (item.thumbnailUrl) {
                                var tu = resolveAbs(item.thumbnailUrl);
                                if (tu) list.push({u: tu, s: 'DOM', r: 'json-ld-thumb'});
                            }
                            if (item.image && typeof item.image === 'string') {
                                var iu = resolveAbs(item.image);
                                if (iu) list.push({u: iu, s: 'DOM', r: 'json-ld-image'});
                            }
                        }
                    } catch(e) {}
                }

                // 5. CSS background-image in content containers
                var bgElements = document.querySelectorAll('main, article, figure, [role="main"], #content, .gallery, .shots-grid, .shots, .photo, .hero');
                var count = Math.min(bgElements.length, 60);
                for (var b = 0; b < count; b++) {
                    var bEl = bgElements[b];
                    if (bEl.clientWidth >= 120 && bEl.clientHeight >= 120 && !isChromeElement(bEl)) {
                        var style = window.getComputedStyle(bEl);
                        var bg = style.backgroundImage;
                        if (bg && bg.indexOf('url(') !== -1) {
                            var match = bg.match(/url\(['"]?(.*?)['"]?\)/);
                            if (match && match[1] && !match[1].startsWith('data:')) {
                                var bgu = resolveAbs(match[1]);
                                if (bgu) list.push({u: bgu, s: 'CSS', r: 'background'});
                            }
                        }
                    }
                }

                // 6. Direct media IFrames
                var iframes = document.querySelectorAll('iframe');
                for (var f = 0; f < iframes.length; f++) {
                    var ifr = resolveAbs(iframes[f].src);
                    if (ifr && /\.(?:mp4|webm|mkv|mov|mp3|m4a|wav|jpe?g|png|webp|gif|avif)(?:[?#]|$)/i.test(ifr)) {
                        list.push({u: ifr, s: 'IFRAME', r: 'iframe'});
                    }
                }

                return JSON.stringify(list);
            })();
        """.trimIndent()
    }

    /**
     * Shared JavaScript MutationObserver injection for dynamic DOM additions.
     */
    fun getMutationObserverScript(): String {
        return """
            (function() {
                if (window.__emreObserverInstalled) return;
                window.__emreObserverInstalled = true;

                function report(u) {
                    if (!u || u.indexOf('data:') === 0 || u.indexOf('blob:') === 0) return;
                    try {
                        var abs = new URL(u, document.baseURI || window.location.href).href;
                        if (window.EmreBridge && window.EmreBridge.reportMedia) {
                            window.EmreBridge.reportMedia(abs);
                        }
                    } catch(e) {}
                }

                function checkNode(node) {
                    if (!node || node.nodeType !== 1) return;
                    var tag = node.tagName ? node.tagName.toUpperCase() : '';
                    if (tag === 'IMG') {
                        report(node.src);
                        report(node.getAttribute('data-src'));
                        report(node.getAttribute('data-original'));
                        report(node.getAttribute('data-lazy-src'));
                        report(node.getAttribute('data-large-file'));
                        report(node.getAttribute('data-hi-res-src'));
                        var srcset = node.getAttribute('srcset') || node.getAttribute('data-srcset');
                        if (srcset) {
                            var parts = srcset.split(',');
                            for (var i = 0; i < parts.length; i++) {
                                var bit = parts[i].trim().split(/\s+/)[0];
                                if (bit) report(bit);
                            }
                        }
                    } else if (tag === 'VIDEO') {
                        if (node.src && node.src.indexOf('blob:') !== 0 && node.src.indexOf('data:') !== 0) {
                            report(node.src);
                        } else if (node.querySelectorAll) {
                            var vSources = node.querySelectorAll('source');
                            for (var vs = 0; vs < vSources.length; vs++) {
                                var vsSrc = vSources[vs].src;
                                if (vsSrc && vsSrc.indexOf('blob:') !== 0 && vsSrc.indexOf('data:') !== 0) {
                                    report(vsSrc);
                                    break;
                                }
                            }
                        }
                        report(node.getAttribute('poster'));
                    } else if (tag === 'AUDIO') {
                        if (node.src && node.src.indexOf('blob:') !== 0 && node.src.indexOf('data:') !== 0) {
                            report(node.src);
                        } else if (node.querySelectorAll) {
                            var aSources = node.querySelectorAll('source');
                            for (var as = 0; as < aSources.length; as++) {
                                var asSrc = aSources[as].src;
                                if (asSrc && asSrc.indexOf('blob:') !== 0 && asSrc.indexOf('data:') !== 0) {
                                    report(asSrc);
                                    break;
                                }
                            }
                        }
                    } else if (tag === 'SOURCE') {
                        var parentTag = node.parentNode ? (node.parentNode.tagName || '').toUpperCase() : '';
                        if (parentTag !== 'VIDEO' && parentTag !== 'AUDIO') {
                            report(node.src);
                            report(node.getAttribute('srcset'));
                        }
                    }

                    if (node.querySelectorAll) {
                        var children = node.querySelectorAll('img, video, audio, source');
                        for (var c = 0; c < children.length; c++) {
                            checkNode(children[c]);
                        }
                    }
                }

                var observer = new MutationObserver(function(mutations) {
                    for (var m = 0; m < mutations.length; m++) {
                        var mut = mutations[m];
                        if (mut.type === 'childList') {
                            for (var i = 0; i < mut.addedNodes.length; i++) {
                                checkNode(mut.addedNodes[i]);
                            }
                        } else if (mut.type === 'attributes') {
                            checkNode(mut.target);
                        }
                    }
                });

                observer.observe(document.body || document.documentElement, {
                    childList: true,
                    subtree: true,
                    attributes: true,
                    attributeFilter: ['src', 'data-src', 'data-original', 'data-lazy-src', 'srcset', 'data-srcset', 'poster']
                });
            })();
        """.trimIndent()
    }

    /**
     * Shared JavaScript adaptive scrolling script.
     */
    fun getAdaptiveScrollScript(): String {
        return """
            (function() {
                var doc = document.documentElement || document.body;
                var scrollY = Math.round(window.pageYOffset || doc.scrollTop || 0);
                var innerHeight = window.innerHeight || 0;
                var scrollHeight = doc.scrollHeight || 0;
                var remaining = scrollHeight - (scrollY + innerHeight);

                var scrollDelta = Math.max(innerHeight * 0.85, 400);
                window.scrollBy({ top: scrollDelta, behavior: 'smooth' });
                window.dispatchEvent(new Event('scroll'));

                return JSON.stringify({
                    scrollY: scrollY,
                    scrollHeight: scrollHeight,
                    remaining: remaining
                });
            })();
        """.trimIndent()
    }

    /**
     * Builds and validates a ScrapedMediaItem with provenance, clean titles,
     * download headers (User-Agent, Referer, Cookie), and thumbnail classification.
     */
    fun buildMediaItem(
        rawUrl: String,
        source: ExtractionSource,
        baseUrl: String,
        poster: String? = null,
        resolutionHint: String? = null,
        mimeHint: String? = null,
        indexHint: Int = 0,
        titleHint: String? = null,
        domSelector: String? = null,
        declaredWidth: Int? = null,
        declaredHeight: Int? = null,
        customId: String? = null
    ): ScrapedMediaItem? {
        val trimmed = rawUrl.trim()
        if (trimmed.startsWith("blob:", ignoreCase = true)) return null
        if (trimmed.startsWith("data:", ignoreCase = true) && !MediaClassifier.isSubstantialDataUri(trimmed)) return null
        if (MediaClassifier.isMediaFragment(trimmed)) return null
        if (AdBlockEngine.isAdOrTracker(trimmed)) return null

        val resolved = MediaClassifier.resolveUrl(trimmed, baseUrl) ?: return null
        if (!MediaClassifier.isValidMedia(resolved)) return null
        if (MediaClassifier.isMediaFragment(resolved)) return null

        val resolvedInfo = MediaClassifier.resolveOriginalMedia(resolved)
        val cleanedUrl = MediaClassifier.cleanMediaUrl(resolvedInfo.mediaUrl)
        if (MediaClassifier.isMediaFragment(cleanedUrl)) return null

        val classification = MediaClassifier.classify(cleanedUrl, mimeHint)
        val isVidAudStream = classification.isVideo || classification.isAudio || classification.isStream
        val title = resolvedInfo.cleanTitle
            ?: titleHint?.takeIf { it.isNotBlank() }
            ?: MediaClassifier.deriveFileName(cleanedUrl, classification.format, indexHint)

        if (ContentAwareMediaFilter.isUiChromeUrl(
                url = cleanedUrl,
                title = title,
                width = declaredWidth,
                height = declaredHeight,
                isVideoOrAudioOrStream = isVidAudStream
            )
        ) {
            return null
        }

        val thumb = resolvedInfo.thumbnailUrl ?: poster ?: if (classification.category == MediaCategory.IMAGES) resolved else poster

        val headers = mutableMapOf<String, String>()
        headers["Referer"] = baseUrl
        headers["User-Agent"] = OkHttpDownloader.DEFAULT_USER_AGENT
        val cookieStr = OkHttpDownloader.sharedCookieJar.getCookieString(baseUrl)
        if (cookieStr.isNotBlank()) {
            headers["Cookie"] = cookieStr
        }

        val metadataMap = mutableMapOf<String, String>()
        resolutionHint?.let { metadataMap["resolution"] = it }
        mimeHint?.let { metadataMap["mime_hint"] = it }
        metadataMap["source_url"] = baseUrl

        val engineLabel = when (source) {
            ExtractionSource.STATIC_HTML -> "Static HTML"
            ExtractionSource.NETWORK_INTERCEPT -> "Browser Chromium (Network Intercept)"
            ExtractionSource.DOM_SCRAPE -> "Static HTML (DOM Scrape)"
            ExtractionSource.SRCSET -> "Static HTML (Srcset)"
            ExtractionSource.CSS_BACKGROUND -> "Static HTML (CSS Background)"
            ExtractionSource.IFRAME -> "Static HTML (iFrame)"
            ExtractionSource.GALLERY_DL -> "gallery-dl"
        }

        val itemId = customId ?: "captured_${indexHint}_${System.currentTimeMillis()}_${cleanedUrl.hashCode()}"

        return ScrapedMediaItem(
            id = itemId,
            url = cleanedUrl,
            title = title,
            isVideo = classification.isVideo,
            isAudio = classification.isAudio,
            isStream = classification.isStream,
            category = classification.category,
            format = classification.format,
            thumbnailUrl = thumb,
            source = source.label,
            mimeType = classification.mimeType,
            resolutionHint = resolutionHint,
            provenance = MediaProvenance(
                extractorEngine = engineLabel,
                pageUrl = baseUrl,
                domSelector = domSelector ?: if (source == ExtractionSource.NETWORK_INTERCEPT) "network_request" else null
            ),
            metadata = metadataMap,
            downloadHeaders = headers
        )
    }

    /**
     * Unified network request interception for both BrowserMediaExtractor and DirectBrowserView.
     */
    fun shouldInterceptRequest(
        request: WebResourceRequest?,
        baseUrl: String,
        onMediaDiscovered: (ScrapedMediaItem) -> Unit
    ): WebResourceResponse? {
        if (request == null) return null
        val reqUrl = request.url.toString()

        // 1. Instant zero-payload response for ad/tracker networks
        if (AdBlockEngine.isAdOrTracker(reqUrl)) {
            return AdBlockEngine.createEmptyResponse()
        }

        // 2. Reject streaming slice fragments (.ts slices, dash segments)
        if (MediaClassifier.isMediaFragment(reqUrl)) {
            return null
        }

        val reqLower = reqUrl.lowercase()
        val cleanReqPath = reqLower.substringBefore("?").substringBefore("#")
        val headers = request.requestHeaders ?: emptyMap()
        val acceptHeader = headers["Accept"]?.lowercase() ?: ""

        // Reject non-media API, logging, telemetry endpoints
        val isNonMediaEndpoint = cleanReqPath.contains("/api/") || cleanReqPath.contains("/apis/") ||
                cleanReqPath.contains("/youtubei/") || cleanReqPath.contains("/stats/") ||
                cleanReqPath.contains("/log") || cleanReqPath.contains("/logging") ||
                cleanReqPath.contains("/ptracking") || cleanReqPath.contains("/beacon") ||
                cleanReqPath.contains("/telemetry") || cleanReqPath.contains("/metrics") ||
                cleanReqPath.contains("generate_204") || cleanReqPath.contains("/recaptcha") ||
                cleanReqPath.contains("/embed/") || cleanReqPath.contains("play.google.com/log") ||
                cleanReqPath.contains("youtube.com/api/") || cleanReqPath.contains("/wiki/") ||
                cleanReqPath.contains("/geohack") || cleanReqPath.contains("centralautologin")

        if (isNonMediaEndpoint) return null

        val isNonMediaPage = cleanReqPath.endsWith(".html") || cleanReqPath.endsWith(".htm") ||
                cleanReqPath.endsWith(".php") || cleanReqPath.endsWith(".js") || cleanReqPath.endsWith(".css") ||
                cleanReqPath.endsWith(".json") || cleanReqPath.endsWith(".xml") || cleanReqPath.endsWith(".txt") ||
                cleanReqPath.endsWith(".do") || cleanReqPath.endsWith(".asp") || cleanReqPath.endsWith(".aspx")

        if (isNonMediaPage) return null

        val isStream = cleanReqPath.endsWith(".m3u8") || cleanReqPath.endsWith(".mpd") ||
                cleanReqPath.contains("chunklist") || acceptHeader.contains("mpegurl") ||
                acceptHeader.contains("dash+xml")

        val isVideo = isStream || cleanReqPath.endsWith(".mp4") || cleanReqPath.endsWith(".webm") ||
                cleanReqPath.endsWith(".mov") || cleanReqPath.endsWith(".mkv") ||
                cleanReqPath.contains("/videoplayback") || cleanReqPath.contains("googlevideo.com") ||
                acceptHeader.contains("video/")

        val isAudio = cleanReqPath.endsWith(".mp3") || cleanReqPath.endsWith(".m4a") ||
                cleanReqPath.endsWith(".aac") || cleanReqPath.endsWith(".wav") ||
                cleanReqPath.endsWith(".ogg") || cleanReqPath.endsWith(".flac") ||
                cleanReqPath.endsWith(".opus") || acceptHeader.contains("audio/")

        val hasImageExt = cleanReqPath.endsWith(".jpg") || cleanReqPath.endsWith(".jpeg") ||
                cleanReqPath.endsWith(".png") || cleanReqPath.endsWith(".webp") ||
                cleanReqPath.endsWith(".gif") || cleanReqPath.endsWith(".avif") ||
                cleanReqPath.endsWith(".svg") || cleanReqPath.endsWith(".bmp") ||
                cleanReqPath.contains("/vi/") || cleanReqPath.contains("/vi_webp/") ||
                Regex("""[?&](?:format|fmt|ext)=(?:jpg|jpeg|png|webp|avif)""").containsMatchIn(reqLower)

        val isImage = hasImageExt && !acceptHeader.contains("text/html")

        if (isVideo || isAudio || isImage) {
            val cleanReqUrl = if (isVideo) MediaClassifier.cleanMediaUrl(reqUrl) else reqUrl
            val ytPoster = if (isVideo) {
                MediaClassifier.extractYouTubeVideoId(baseUrl)?.let { ytId ->
                    "https://i.ytimg.com/vi/$ytId/maxresdefault.jpg"
                }
            } else null
            val item = buildMediaItem(
                rawUrl = cleanReqUrl,
                source = ExtractionSource.NETWORK_INTERCEPT,
                baseUrl = baseUrl,
                poster = ytPoster,
                mimeHint = if (cleanReqPath.contains("/videoplayback")) "video/mp4" else null
            )
            if (item != null) {
                onMediaDiscovered(item)
            }
        }

        return null
    }

    /**
     * Parses the JSON array returned by getJsDomScanScript().
     */
    fun parseJsDomScanResult(
        resultJson: String?,
        baseUrl: String
    ): List<ScrapedMediaItem> {
        if (resultJson.isNullOrBlank() || resultJson == "null") return emptyList()

        return try {
            val unescaped = if (resultJson.startsWith("\"") && resultJson.endsWith("\"")) {
                org.json.JSONTokener(resultJson).nextValue().toString()
            } else {
                resultJson
            }

            val jsonArray = JSONArray(unescaped)
            val results = mutableListOf<ScrapedMediaItem>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val rawUrl = obj.optString("u", "")
                if (rawUrl.isNotBlank()) {
                    val sTag = obj.optString("s", "DOM")
                    val source = when (sTag) {
                        "CSS" -> ExtractionSource.CSS_BACKGROUND
                        "SRCSET" -> ExtractionSource.SRCSET
                        "IFRAME" -> ExtractionSource.IFRAME
                        else -> ExtractionSource.DOM_SCRAPE
                    }
                    val poster = obj.optString("p", "").ifBlank { null }
                    val res = obj.optString("r", "").ifBlank { null }
                    val item = buildMediaItem(
                        rawUrl = rawUrl,
                        source = source,
                        baseUrl = baseUrl,
                        poster = poster,
                        resolutionHint = res,
                        indexHint = i
                    )
                    if (item != null) {
                        results.add(item)
                    }
                }
            }
            results
        } catch (_: Exception) {
            emptyList()
        }
    }
}
