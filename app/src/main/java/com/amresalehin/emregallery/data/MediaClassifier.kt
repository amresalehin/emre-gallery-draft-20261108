package com.amresalehin.emregallery.data

import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs

/**
 * High-level category for discovered media.
 */
enum class MediaCategory(val label: String) {
    ALL("All"),
    IMAGES("Images"),
    VIDEOS("Videos"),
    AUDIO("Audio"),
    STREAMS("Streams")
}

/**
 * Extraction source indicator for transparency.
 */
enum class ExtractionSource(val label: String) {
    GALLERY_DL("gallery-dl"),
    STATIC_HTML("HTML"),
    DOM_SCRAPE("DOM"),
    CSS_BACKGROUND("CSS"),
    SRCSET("Srcset"),
    NETWORK_INTERCEPT("Network"),
    IFRAME("iFrame")
}

/**
 * Normalizes, classifies, and deduplicates media URLs discovered via static HTML scraping
 * or live browser network interception.
 */
object MediaClassifier {

    // Common tracking and analytics domains or path segments to exclude
    private val TRACKING_PATTERNS = listOf(
        "google-analytics", "analytics", "doubleclick", "pixel", "1x1", "tracking",
        "beacon", "telemetry", "hotjar", "clarity.ms", "stats.wp.com", "facebook.com/tr"
    )

    // Query parameters that should be stripped to produce canonical deduplication keys
    private val TRACKING_QUERY_PARAMS = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "fbclid", "gclid", "msclkid", "ref", "source", "_ga", "_gl", "_t", "cachebust",
        "range", "bytestart", "byteend", "bytes", "rn", "sq", "sver", "live", "cpn",
        "chunk", "seg", "segment", "frag", "part", "subpart"
    )

    private val STREAMING_SLICE_PARAMS = setOf(
        "range", "bytestart", "byteend", "bytes", "rn", "sq", "live", "cpn", "sver", "chunk", "seg", "part"
    )

    /**
     * Checks if a string is a substantial, legitimate Base64 inline data URI (e.g. artwork, canvas exports),
     * while rejecting tiny spacer GIFs, 1x1 transparent tracking pixels, and empty vectors.
     */
    fun isSubstantialDataUri(url: String): Boolean {
        val trimmed = url.trim()
        if (!trimmed.startsWith("data:image/", ignoreCase = true)) return false
        // Exclude SVG/XML data URIs which are placeholders/spacers
        if (trimmed.contains("svg+xml", ignoreCase = true) || trimmed.contains("<svg", ignoreCase = true) || trimmed.contains("PHN2Zy", ignoreCase = true)) return false
        // Exclude tiny spacers, 1x1 pixel tracking GIFs (< 2048 characters base64)
        if (trimmed.length < 2048) return false
        // Known 1x1 transparent GIF signature
        if (trimmed.contains("R0lGODlhAQABA", ignoreCase = true)) return false
        return trimmed.contains(";base64,", ignoreCase = true)
    }

    data class DataUriInfo(
        val mimeType: String,
        val format: String,
        val estimatedSizeBytes: Long,
        val cleanTitle: String
    )

    fun parseDataUriInfo(dataUri: String): DataUriInfo? {
        if (!isSubstantialDataUri(dataUri)) return null
        val header = dataUri.substringBefore(";base64,").removePrefix("data:").trim().lowercase(Locale.ROOT)
        val mimeType = if (header.isBlank()) "image/png" else header
        val format = when {
            mimeType.contains("png") -> "PNG"
            mimeType.contains("webp") -> "WEBP"
            mimeType.contains("gif") -> "GIF"
            mimeType.contains("svg") -> "SVG"
            else -> "JPEG"
        }
        val ext = format.lowercase(Locale.ROOT)
        val base64Payload = dataUri.substringAfter(";base64,", "")
        val estimatedBytes = (base64Payload.length * 3L) / 4L
        val cleanTitle = "embedded_artwork_${abs(dataUri.hashCode()) % 100000}.$ext"
        return DataUriInfo(
            mimeType = mimeType,
            format = format,
            estimatedSizeBytes = estimatedBytes,
            cleanTitle = cleanTitle
        )
    }

    /**
     * Estimates the size in bytes of a data URI without loading full bitmap into memory.
     */
    fun estimateDataUriSizeBytes(url: String): Long? {
        if (!url.startsWith("data:", ignoreCase = true)) return null
        val base64Payload = url.substringAfter(";base64,", "")
        if (base64Payload.isNotEmpty()) {
            return (base64Payload.length * 3L) / 4L
        }
        val commaIdx = url.indexOf(',')
        if (commaIdx != -1) {
            return (url.length - commaIdx - 1).toLong()
        }
        return null
    }

    /**
     * Cleans streaming slice and sequence query parameters from a media URL.
     * Prevents downloading only a 1MB partial fragment or single byte-range slice of a video.
     */
    fun cleanMediaUrl(url: String): String {
        return try {
            val noHash = url.substringBefore("#")
            val qIdx = noHash.indexOf('?')
            if (qIdx == -1) return noHash
            val base = noHash.substring(0, qIdx)
            val query = noHash.substring(qIdx + 1)
            val remainingParams = query.split("&").filter { param ->
                val key = param.substringBefore("=").lowercase(Locale.ROOT)
                !STREAMING_SLICE_PARAMS.contains(key)
            }
            if (remainingParams.isEmpty()) base else "$base?${remainingParams.joinToString("&")}"
        } catch (_: Exception) {
            url
        }
    }

    /**
     * Detects if a URL is an unmerged streaming chunk, segment, or initialization fragment.
     * Standalone downloading of individual video chunks causes broken, partial, and multiplied downloads.
     */
    fun isMediaFragment(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        if (lower.startsWith("blob:")) return true
        if (lower.startsWith("data:")) {
            return !isSubstantialDataUri(url)
        }
        // YouTube video playback endpoints are the master playable stream, not fragment discards
        if (lower.contains("googlevideo.com/videoplayback") || lower.contains("/videoplayback")) {
            return false
        }

        val clean = lower.substringBefore("?").substringBefore("#")

        // 1. Fragment-only streaming container extensions
        if (clean.endsWith(".m4s") || clean.endsWith(".m4f") || clean.endsWith(".f4f") || clean.endsWith(".f4m") ||
            clean.endsWith(".dash") || clean.endsWith(".cmfv") || clean.endsWith(".cmfa")
        ) {
            return true
        }

        // 2. Transport stream chunks (.ts)
        // Standalone videos on websites are MP4/WebM/MKV/MOV/M3U8. A .ts with numeric/segment numbering is an HLS chunk
        if (clean.endsWith(".ts")) {
            val fileName = clean.substringAfterLast('/')
            val isSegmentTs = fileName.matches(Regex("""(?i)(?:seg|segment|chunk|frag|fragment|track|stream|hls)?[_\-.]?\d+\.ts""")) ||
                    fileName.matches(Regex("""(?i)\d+\.ts""")) ||
                    fileName.contains("slice") || fileName.contains("stream") ||
                    clean.contains("/hls/") || clean.contains("/dash/") || clean.contains("/segments/") || clean.contains("/chunks/") ||
                    clean.contains("/live/")
            if (isSegmentTs) return true
        }

        // 3. Initialization segments (contain metadata only, no playable frames)
        if (clean.contains("/init.") || clean.contains("/init-") || clean.contains("_init.") ||
            clean.endsWith("init.mp4") || clean.endsWith("init.m4s") || clean.contains("init-stream") ||
            clean.contains("/header.mp4") || clean.contains("/init_") || clean.contains("init_video") ||
            clean.contains("init_audio")
        ) {
            return true
        }

        // 4. Fragment / segment naming in URL path
        val segmentPattern = Regex("""[/_.-](?:seg|segment|frag|fragment|chunk|part)[-_.]?\d+""", RegexOption.IGNORE_CASE)
        if (segmentPattern.containsMatchIn(clean)) {
            return true
        }
        val numberedFragment = Regex("""\d+-(?:frag|seg|segment|chunk)\.(?:mp4|ts|m4s|m4v)""", RegexOption.IGNORE_CASE)
        if (numberedFragment.containsMatchIn(clean)) {
            return true
        }
        val chunkStreamPattern = Regex("""chunk-stream\d+-\d+\.m4s""", RegexOption.IGNORE_CASE)
        if (chunkStreamPattern.containsMatchIn(clean)) {
            return true
        }

        // 5. Query parameters indicating chunk indexing
        val query = lower.substringAfter("?", "")
        if (query.isNotBlank()) {
            val params = query.split("&")
            for (p in params) {
                val key = p.substringBefore("=").lowercase(Locale.ROOT)
                val value = p.substringAfter("=", "")
                if ((key == "sq" || key == "seg" || key == "segment" || key == "frag" || key == "chunk" || key == "part") &&
                    value.toIntOrNull() != null
                ) {
                    return true
                }
                if (key == "bytestart" || key == "byteend") {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Resolves a potentially relative URL against the base URL and standardizes protocol.
     */
    fun resolveUrl(rawUrl: String, baseUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank() || trimmed.startsWith("javascript:") || trimmed.startsWith("mailto:")) {
            return null
        }
        if (trimmed.startsWith("data:", ignoreCase = true)) {
            return if (isSubstantialDataUri(trimmed)) trimmed else null
        }

        return try {
            val base = URI(if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) "https://$baseUrl" else baseUrl)
            val resolved = if (trimmed.startsWith("//")) {
                (base.scheme ?: "https") + ":" + trimmed
            } else {
                base.resolve(trimmed).toString()
            }
            if (resolved.startsWith("http://") || resolved.startsWith("https://")) {
                resolved
            } else {
                null
            }
        } catch (_: Exception) {
            // Fallback with java.net.URL
            try {
                val base = URL(if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) "https://$baseUrl" else baseUrl)
                val resolved = URL(base, trimmed).toString()
                if (resolved.startsWith("http://") || resolved.startsWith("https://")) resolved else null
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Strictly validates whether an input string is a valid web URL (e.g. https://example.com, example.com/page).
     * Rejects arbitrary text, invalid hosts, spaces, local file/javascript URIs, or non-domain inputs.
     */
    fun isValidWebUrl(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isBlank() || trimmed.contains(" ") || trimmed.length < 4) {
            return false
        }
        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower.startsWith("javascript:") || lower.startsWith("data:") ||
            lower.startsWith("mailto:") || lower.startsWith("file:") || lower.startsWith("content:")) {
            return false
        }
        val candidate = if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            "https://$trimmed"
        } else {
            trimmed
        }
        return try {
            val uri = URI(candidate)
            val host = uri.host ?: return false
            if (host.equals("localhost", ignoreCase = true)) return true
            if (!host.contains(".") || host.startsWith(".") || host.endsWith(".")) return false
            val parts = host.split(".")
            if (parts.any { it.isBlank() }) return false
            val tld = parts.last()
            val isAlphabeticTld = tld.length >= 2 && tld.all { it.isLetter() }
            val isIp = parts.size == 4 && parts.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
            isAlphabeticTld || isIp
        } catch (_: Exception) {
            false
        }
    }

    private val HTTP_URL_REGEX = "(https?://[^\\s<>\"'\\[\\]{}|\\\\^`]+)".toRegex(RegexOption.IGNORE_CASE)

    private val TRACKING_PARAMS_TO_REMOVE = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id", "utm_reader", "utm_name",
        "fbclid", "gclid", "gbraid", "wbraid", "msclkid", "twclid", "dclid",
        "igshid", "si", "feature", "ref", "ref_src", "ref_url", "source", "share_id",
        "_ga", "_gl", "_hsenc", "_hsmi", "mc_cid", "mc_eid",
        "is_from_webapp", "sender_device", "sender_web_id",
        "ved", "usg", "context"
    )

    /**
     * Extracts a clean display domain from a web URL.
     */
    fun cleanDomainFromUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return try {
            val normalized = if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                "https://$url"
            } else {
                url
            }
            val uri = URI(normalized)
            uri.host?.removePrefix("www.") ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Thoroughly sanitizes a web URL before fetching:
     * 1. Strips wrapping quotes, whitespace, and punctuation.
     * 2. Unwraps known redirect wrappers (Google / Facebook / Reddit outbound links).
     * 3. Strips tracking, analytics, and social share parameters (utm_*, igshid, si, fbclid, etc.).
     * 4. Ensures a valid https:// scheme.
     */
    fun sanitizeUrl(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var cleaned = raw.trim().trim('"', '\'', '<', '>', '(', ')', '[', ']', ';')
        if (cleaned.isBlank()) return ""

        if (!cleaned.startsWith("http://", ignoreCase = true) && !cleaned.startsWith("https://", ignoreCase = true)) {
            cleaned = "https://$cleaned"
        }

        return try {
            val uri = URI(cleaned)
            val host = uri.host?.lowercase(Locale.ROOT) ?: return cleaned

            // 1. Unwrap Google / Facebook redirect links if present
            if ((host.contains("google.") && uri.path == "/url") ||
                (host.contains("facebook.com") && uri.path?.endsWith("/l.php") == true)
            ) {
                val queryParams = uri.rawQuery?.split("&")?.associate {
                    val key = it.substringBefore("=").lowercase(Locale.ROOT)
                    val value = it.substringAfter("=", "")
                    key to java.net.URLDecoder.decode(value, "UTF-8")
                } ?: emptyMap()

                val target = queryParams["q"] ?: queryParams["url"] ?: queryParams["u"]
                if (!target.isNullOrBlank() && isValidWebUrl(target)) {
                    return sanitizeUrl(target)
                }
            }

            // 2. Clean query parameters (strip tracking parameters, retain functional ones like v=, id=, p=, etc.)
            val rawQuery = uri.rawQuery
            val filteredQuery = if (!rawQuery.isNullOrBlank()) {
                val remainingParams = rawQuery.split("&").filter { param ->
                    val key = param.substringBefore("=").lowercase(Locale.ROOT)
                    !TRACKING_PARAMS_TO_REMOVE.contains(key) && !key.startsWith("utm_")
                }
                if (remainingParams.isNotEmpty()) remainingParams.joinToString("&") else null
            } else null

            // 3. Reconstruct clean URI without tracking parameters or tracking fragments
            val cleanUri = URI(
                uri.scheme ?: "https",
                uri.authority,
                uri.path,
                filteredQuery,
                null
            )
            cleanUri.toString()
        } catch (_: Exception) {
            cleaned
        }
    }

    /**
     * Extracts the first valid web URL from arbitrary shared text, captions, or messages,
     * and sanitizes it by removing tracking parameters.
     */
    fun extractFirstUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val match = HTTP_URL_REGEX.find(text)
        if (match != null) {
            val candidate = match.value.trimEnd('.', ',', ')', ']', ';', '>', '"', '\'', ':', '!')
            val sanitized = sanitizeUrl(candidate)
            if (isValidWebUrl(sanitized)) {
                return sanitized
            }
        }
        val trimmed = text.trim()
        val sanitizedTrimmed = sanitizeUrl(trimmed)
        if (isValidWebUrl(sanitizedTrimmed)) {
            return sanitizedTrimmed
        }
        return null
    }

    val KNOWN_MEDIA_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "svg", "avif", "bmp", "heic", "heif", "ico",
        "mp4", "webm", "mov", "mkv", "avi", "flv", "3gp", "m4v", "ts", "m3u8", "mpd",
        "mp3", "m4a", "aac", "wav", "ogg", "opus", "flac", "wma"
    )

    /**
     * Checks whether the URL is valid media and not tracking/pixel/non-media page.
     */
    fun isValidMedia(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)

        // 1. Check AdBlockEngine for ad networks and tracking scripts
        if (AdBlockEngine.isAdOrTracker(url)) {
            return false
        }

        // 2. Data URI validation: allow substantial artwork, reject tiny tracking pixels
        if (lower.startsWith("data:")) {
            return isSubstantialDataUri(url)
        }

        for (pattern in TRACKING_PATTERNS) {
            if (lower.contains(pattern)) return false
        }
        if (lower.contains("favicon.ico") || lower.contains("/avatar_default") ||
            lower.contains("/blank.gif") || lower.contains("transparent.gif") ||
            lower.contains("spacer.gif") || lower.contains("spinner.gif")
        ) {
            return false
        }
        val cleanPath = lower.substringBefore("?").substringBefore("#")
        // Reject web document, script, and non-media endpoint paths
        if (cleanPath.endsWith(".html") || cleanPath.endsWith(".htm") || cleanPath.endsWith(".php") ||
            cleanPath.endsWith(".jsp") || cleanPath.endsWith(".asp") || cleanPath.endsWith(".aspx") ||
            cleanPath.endsWith(".js") || cleanPath.endsWith(".css") || cleanPath.endsWith(".json") ||
            cleanPath.endsWith(".xml") || cleanPath.endsWith(".txt") || cleanPath.endsWith(".do")
        ) {
            return false
        }

        // Reject non-media API, stats, logging, telemetry, and embed document endpoints
        if (cleanPath.contains("/api/") || cleanPath.contains("/apis/") || cleanPath.contains("/youtubei/") ||
            cleanPath.contains("/stats/") || cleanPath.contains("/log") || cleanPath.contains("/logging") ||
            cleanPath.contains("/ptracking") || cleanPath.contains("/beacon") || cleanPath.contains("/telemetry") ||
            cleanPath.contains("/track") || cleanPath.contains("/metrics") || cleanPath.contains("/collect") ||
            cleanPath.contains("generate_204") || cleanPath.contains("/recaptcha") ||
            cleanPath.contains("play.google.com/log") ||
            ((cleanPath.contains("/embed/") || cleanPath.contains("/iframe")) && !KNOWN_MEDIA_EXTENSIONS.any { cleanPath.endsWith(".$it") })
        ) {
            return false
        }

        val candidate = when {
            lower.startsWith("http://") || lower.startsWith("https://") -> url
            lower.startsWith("//") -> "https:$url"
            lower.startsWith("/") -> "https://placeholder.invalid$url"
            else -> "https://placeholder.invalid/$url"
        }
        val uri = runCatching { URI(candidate) }.getOrNull()
        val host = uri?.host?.lowercase(Locale.ROOT)?.takeIf { it != "placeholder.invalid" } ?: ""
        val path = uri?.path?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: cleanPath

        if (host == "accounts.google.com" || host == "play.google.com" || host == "myactivity.google.com" ||
            host == "consent.youtube.com" || host == "consent.google.com"
        ) {
            return false
        }

        // YouTube HTML pages (shorts, watch, channel, embed, feed) are web documents, not direct media items
        if ((host.contains("youtube.com") || host.contains("youtu.be")) &&
            (path.contains("/watch") || path.contains("/shorts/") || path.contains("/channel/") ||
             path.contains("/feed/") || path.contains("/embed/") || path.contains("/results")) &&
            !KNOWN_MEDIA_EXTENSIONS.any { cleanPath.endsWith(".$it") }
        ) {
            return false
        }

        // Host/path-aware non-media webpage rejections:
        val isWikiHost = host.contains("wikipedia.org") || host.contains("wikimedia.org") ||
                host.contains("wiktionary.org") || host.contains("wikidata.org") ||
                host.contains("wikibooks.org") || host.contains("wikiquote.org") ||
                host.contains("wikisource.org") || host.contains("wikinews.org") ||
                host.contains("wikivoyage.org") || host.contains("mediawiki.org")

        if (isWikiHost && (path.contains("/geohack") || path.contains("centralautologin") || path.contains("/api.php"))) {
            return false
        }
        // Reddit thread / comments pages (not direct media CDNs)
        if ((host.contains("reddit.com") || host.contains("redd.it")) &&
            !host.startsWith("i.") && !host.startsWith("v.") && !host.startsWith("preview.") &&
            path.contains("/comments/")
        ) {
            return false
        }
        // Pinterest pin discussion pages (not direct image CDN)
        if ((host.contains("pinterest.com") || host.contains("pin.it")) &&
            !host.startsWith("i.") &&
            path.contains("/pin/")
        ) {
            return false
        }
        // Imgur gallery web albums (not direct CDN images)
        if (host.contains("imgur.com") && !host.startsWith("i.") &&
            (path.contains("/gallery/") || path.startsWith("/gallery") || path.startsWith("/a/"))
        ) {
            return false
        }
        // Twitter/X status pages (not direct media CDNs)
        if ((host.contains("twitter.com") || host.contains("x.com")) &&
            !host.startsWith("pbs.") && !host.startsWith("video.") &&
            path.contains("/status/")
        ) {
            return false
        }
        // MediaWiki / Wikipedia article and discussion pages (HTML pages)
        if (path.contains("/wiki/file:") || path.contains("/wiki/image:") || path.contains("/wiki/special:")) {
            return false
        }
        if (isWikiHost && !host.contains("upload.wikimedia.org") && !host.contains("thumb.wikimedia.org")) {
            if (path.contains("/wiki/") || path.contains("/w/index.php") || path.contains("/w/api.php") ||
                path.contains("/file:") || path.contains("/image:") ||
                cleanPath.contains("file:") || cleanPath.contains("image:")
            ) {
                return false
            }
        }
        if (path.startsWith("/w/index.php") || path.contains("/w/index.php")) {
            return false
        }
        if (isMediaFragment(url)) {
            return false
        }
        return true
    }

    data class ResolvedMediaInfo(
        val mediaUrl: String,
        val thumbnailUrl: String?,
        val cleanTitle: String? = null
    )

    private val WIKIMEDIA_THUMB_REGEX = Regex(
        """^https?://(?:upload|thumb)\.wikimedia\.org/wikipedia/([^/]+)/thumb/([0-9a-fA-F]/[0-9a-fA-F]{2}/([^/]+))/.+"""
    )
    private val MEDIAWIKI_GENERIC_THUMB_REGEX = Regex(
        """^(https?://[^/]+(?:/[^/]+)*?)/thumb/([0-9a-fA-F]/[0-9a-fA-F]{2}/([^/]+))/.+"""
    )
    private val WIKIMEDIA_TRANSCODE_REGEX = Regex(
        """^https?://(?:upload|thumb)\.wikimedia\.org/wikipedia/([^/]+)/transcoded/([0-9a-fA-F]/[0-9a-fA-F]{2}/([^/]+))/.+"""
    )
    private val MEDIAWIKI_GENERIC_TRANSCODE_REGEX = Regex(
        """^(https?://[^/]+(?:/[^/]+)*?)/transcoded/([0-9a-fA-F]/[0-9a-fA-F]{2}/([^/]+))/.+"""
    )

    /**
     * Resolves a media URL to its original full-resolution master file URL across major CDNs
     * (Wikimedia, Reddit, Imgur, Twitter/X, WordPress/Jetpack, Base64 Data URIs)
     * while preserving the sharp thumbnail URL for immediate preview.
     */
    fun resolveOriginalMedia(rawUrl: String): ResolvedMediaInfo {
        val trimmed = rawUrl.trim()
        val normalized = if (trimmed.startsWith("//")) "https:$trimmed" else trimmed

        // 0. Base64 Data URI handling (embedded artwork)
        if (normalized.startsWith("data:image/", ignoreCase = true)) {
            val dataInfo = parseDataUriInfo(normalized)
            if (dataInfo != null) {
                return ResolvedMediaInfo(
                    mediaUrl = normalized,
                    thumbnailUrl = normalized,
                    cleanTitle = dataInfo.cleanTitle
                )
            }
        }

        // 0b. Pinterest image CDN (i.pinimg.com / *.pinimg.com -> originals full resolution master)
        if (normalized.contains("pinimg.com")) {
            val cleanUrl = normalized.substringBefore("?").substringBefore("#").trimEnd('/')
            val pinHexRegex = Regex("""([0-9a-fA-F]{32})(?:\.([a-zA-Z0-9]+))?""")
            val match = pinHexRegex.find(cleanUrl)
            if (match != null) {
                val hash = match.groupValues[1]
                val rawExt = match.groupValues.getOrNull(2)?.lowercase(Locale.ROOT)
                val ext = if (rawExt.isNullOrBlank() || rawExt == "jpeg" || rawExt == "webp") "jpg" else rawExt
                val p1 = hash.substring(0, 2)
                val p2 = hash.substring(2, 4)
                val p3 = hash.substring(4, 6)
                val originalUrl = "https://i.pinimg.com/originals/$p1/$p2/$p3/$hash.$ext"
                return ResolvedMediaInfo(
                    mediaUrl = originalUrl,
                    thumbnailUrl = normalized,
                    cleanTitle = "$hash.$ext"
                )
            }
        }

        // 0c. YouTube video thumbnails (i.ytimg.com/vi/<id>/... -> clean master resolution)
        val ytThumbRegex = Regex("""^https?://(?:i[0-4]?|img)\.ytimg\.com/(?:vi|vi_webp)/([a-zA-Z0-9_-]{11})/([a-zA-Z0-9_-]+)""")
        val ytMatch = ytThumbRegex.find(normalized)
        if (ytMatch != null) {
            val videoId = ytMatch.groupValues[1]
            val variant = ytMatch.groupValues[2]
            val masterVariant = if (variant == "oar2") "oar2" else if (variant == "frame0") "maxresdefault" else variant
            val originalUrl = "https://i.ytimg.com/vi/$videoId/$masterVariant.jpg"
            return ResolvedMediaInfo(
                mediaUrl = originalUrl,
                thumbnailUrl = normalized,
                cleanTitle = "youtube_${videoId}_$variant.jpg"
            )
        }

        // 1. Reddit Preview CDN (preview.redd.it / external-preview.redd.it -> i.redd.it)
        if (normalized.contains("preview.redd.it") || normalized.contains("external-preview.redd.it")) {
            val cleanPath = normalized.substringBefore("?")
            val fileName = cleanPath.substringAfterLast('/')
            val originalUrl = "https://i.redd.it/$fileName"
            val cleanTitle = runCatching { java.net.URLDecoder.decode(fileName, "UTF-8") }.getOrDefault(fileName)
            return ResolvedMediaInfo(
                mediaUrl = originalUrl,
                thumbnailUrl = normalized,
                cleanTitle = cleanTitle
            )
        }

        // 2. Imgur thumbnail suffix stripping (i.imgur.com/{id}{s|m|l|h|b|_d}.{ext} -> full resolution)
        if (normalized.contains("i.imgur.com/")) {
            val cleanUrl = normalized.substringBefore("?")
            val fileName = cleanUrl.substringAfterLast('/')
            val baseId = fileName.substringBeforeLast('.')
            val ext = fileName.substringAfterLast('.', "")
            val imgurThumbRegex = Regex("""^([a-zA-Z0-9]{5,8})(?:[smlhbt]|_d)$""")
            val match = imgurThumbRegex.find(baseId)
            if (match != null) {
                val masterId = match.groupValues[1]
                val originalExt = if (ext.equals("webp", ignoreCase = true) || ext.isEmpty()) "jpg" else ext
                val originalUrl = "https://i.imgur.com/$masterId.$originalExt"
                return ResolvedMediaInfo(
                    mediaUrl = originalUrl,
                    thumbnailUrl = normalized,
                    cleanTitle = "$masterId.$originalExt"
                )
            }
        }

        // 3. Twitter / X Media CDN (pbs.twimg.com/media/{id}?name=small -> name=orig)
        if (normalized.contains("pbs.twimg.com/media/")) {
            val noHash = normalized.substringBefore("#")
            val qIdx = noHash.indexOf('?')
            val base = if (qIdx == -1) noHash else noHash.substring(0, qIdx)
            val query = if (qIdx == -1) "" else noHash.substring(qIdx + 1)
            val formatParam = query.split("&").firstOrNull { it.startsWith("format=", ignoreCase = true) } ?: "format=jpg"
            val originalUrl = "$base?$formatParam&name=orig"
            val fileName = base.substringAfterLast('/')
            val ext = formatParam.substringAfter("format=").ifBlank { "jpg" }
            return ResolvedMediaInfo(
                mediaUrl = originalUrl,
                thumbnailUrl = normalized,
                cleanTitle = "$fileName.$ext"
            )
        }

        // 4. WordPress / Jetpack Photon CDN (i0.wp.com, i1.wp.com -> original unscaled image)
        if (Regex("""https?://i[0-3]\.wp\.com/""").containsMatchIn(normalized)) {
            val unwrapped = normalized.replace(Regex("""https?://i[0-3]\.wp\.com/"""), "https://")
            val cleanOriginal = unwrapped.substringBefore("?")
            val fileName = cleanOriginal.substringAfterLast('/')
            return ResolvedMediaInfo(
                mediaUrl = cleanOriginal,
                thumbnailUrl = normalized,
                cleanTitle = fileName
            )
        }

        // 4b. WordPress resized uploads (-WxH or -scaled before extension -> master file)
        if (normalized.contains("/wp-content/uploads/")) {
            val cleanUrl = normalized.substringBefore("?")
            val wpResizeRegex = Regex("""-(?:\d{2,5}x\d{2,5}|scaled)\.([a-zA-Z0-9]+)$""", RegexOption.IGNORE_CASE)
            if (wpResizeRegex.containsMatchIn(cleanUrl)) {
                val masterUrl = wpResizeRegex.replace(cleanUrl, ".$1")
                val fileName = masterUrl.substringAfterLast('/')
                return ResolvedMediaInfo(
                    mediaUrl = masterUrl,
                    thumbnailUrl = normalized,
                    cleanTitle = fileName
                )
            }
        }

        // 4c. Shopify CDN resized images (_WxH or _large etc. before extension)
        if (normalized.contains("cdn.shopify.com")) {
            val cleanUrl = normalized.substringBefore("?")
            val shopifyRegex = Regex("""_(?:\d{2,5}x\d{2,5}|\d{2,5}x|x\d{2,5}|pico|icon|thumb|small|compact|medium|large|grande|original|master|1024x1024|2048x2048)\.([a-zA-Z0-9]+)$""", RegexOption.IGNORE_CASE)
            if (shopifyRegex.containsMatchIn(cleanUrl)) {
                val masterUrl = shopifyRegex.replace(cleanUrl, ".$1")
                val fileName = masterUrl.substringAfterLast('/')
                return ResolvedMediaInfo(
                    mediaUrl = masterUrl,
                    thumbnailUrl = normalized,
                    cleanTitle = fileName
                )
            }
        }

        // 4d. Google User Content (lh3.googleusercontent.com etc. =w... / =s...)
        if (normalized.contains("googleusercontent.com")) {
            val base = normalized.substringBefore("=")
            val originalUrl = "$base=s0"
            return ResolvedMediaInfo(
                mediaUrl = originalUrl,
                thumbnailUrl = normalized,
                cleanTitle = base.substringAfterLast('/')
            )
        }

        // 5. Wikimedia Commons / Wikipedia thumbnails
        val wmMatch = WIKIMEDIA_THUMB_REGEX.find(normalized)
        if (wmMatch != null) {
            val project = wmMatch.groupValues[1]
            val subpath = wmMatch.groupValues[2]
            val rawFileName = wmMatch.groupValues[3]
            val originalUrl = "https://upload.wikimedia.org/wikipedia/$project/$subpath"
            val cleanTitle = runCatching { java.net.URLDecoder.decode(rawFileName, "UTF-8") }.getOrDefault(rawFileName)
            return ResolvedMediaInfo(
                mediaUrl = originalUrl,
                thumbnailUrl = normalized,
                cleanTitle = cleanTitle
            )
        }

        // 6. Wikimedia Commons / Wikipedia transcoded videos (.../transcoded/hash/hash2/filename.webm/filename.webm.480p.vp9.webm)
        val wmTranscodeMatch = WIKIMEDIA_TRANSCODE_REGEX.find(normalized)
        if (wmTranscodeMatch != null) {
            val project = wmTranscodeMatch.groupValues[1]
            val subpath = wmTranscodeMatch.groupValues[2]
            val rawFileName = wmTranscodeMatch.groupValues[3]
            val originalUrl = "https://upload.wikimedia.org/wikipedia/$project/$subpath"
            val cleanTitle = runCatching { java.net.URLDecoder.decode(rawFileName, "UTF-8") }.getOrDefault(rawFileName)
            return ResolvedMediaInfo(
                mediaUrl = originalUrl,
                thumbnailUrl = normalized,
                cleanTitle = cleanTitle
            )
        }

        // 7. Generic MediaWiki installations (.../thumb/hash/hash2/filename/...)
        if (normalized.contains("/thumb/")) {
            val mwMatch = MEDIAWIKI_GENERIC_THUMB_REGEX.find(normalized)
            if (mwMatch != null) {
                val uploadBase = mwMatch.groupValues[1]
                val subpath = mwMatch.groupValues[2]
                val rawFileName = mwMatch.groupValues[3]
                val originalUrl = "$uploadBase/$subpath"
                val cleanTitle = runCatching { java.net.URLDecoder.decode(rawFileName, "UTF-8") }.getOrDefault(rawFileName)
                return ResolvedMediaInfo(
                    mediaUrl = originalUrl,
                    thumbnailUrl = normalized,
                    cleanTitle = cleanTitle
                )
            }
        }

        // 8. Generic MediaWiki installations transcoded videos
        if (normalized.contains("/transcoded/")) {
            val mwTranscodeMatch = MEDIAWIKI_GENERIC_TRANSCODE_REGEX.find(normalized)
            if (mwTranscodeMatch != null) {
                val uploadBase = mwTranscodeMatch.groupValues[1]
                val subpath = mwTranscodeMatch.groupValues[2]
                val rawFileName = mwTranscodeMatch.groupValues[3]
                val originalUrl = "$uploadBase/$subpath"
                val cleanTitle = runCatching { java.net.URLDecoder.decode(rawFileName, "UTF-8") }.getOrDefault(rawFileName)
                return ResolvedMediaInfo(
                    mediaUrl = originalUrl,
                    thumbnailUrl = normalized,
                    cleanTitle = cleanTitle
                )
            }
        }

        return ResolvedMediaInfo(
            mediaUrl = normalized,
            thumbnailUrl = null,
            cleanTitle = null
        )
    }

    /**
     * Creates a canonical deduplication key by removing non-functional tracking query parameters.
     */
    fun canonicalKey(url: String): String = deduplicationKey(url)

    /**
     * Creates a high-fidelity deduplication key that identifies identical media discovered
     * across different extraction engines (Static HTML, Live Browser, gallery-dl, yt-dlp).
     * Normalizes:
     * - Multi-Host CDN mirrors (Reddit preview -> i.redd.it, WordPress i0-i3 -> i.wp.com)
     * - Pinterest pin hashes across resolution variants (236x, 474x, 736x, 1200x, originals, RS) and format variants
     * - Cloudinary / Imgix transformation segments
     * - Imgur thumbnail suffixes
     * - WordPress / Shopify / Retina resolution suffixes (-300x200, _1024x1024, @2x)
     * - Generic path folder resolution descriptors (/736x/, /300x200/, /w1200/)
     * - Raster format variants (.jpg, .jpeg, .webp, .avif, .png of the same asset)
     * - Scheme (http vs https) and URL percent-encoding differences
     * - Wikimedia/MediaWiki thumbnail derivatives vs original full-resolution uploads
     * - Responsive image dimensions / resize query parameters (?w=, ?h=, ?width=, ?size=, ?quality=, ?fit=, ?format=, etc.)
     * - Tracking query parameters
     */
    fun deduplicationKey(url: String): String {
        return try {
            val resolved = resolveOriginalMedia(url).mediaUrl

            // If it's a data URI, hash its base64 content
            if (resolved.startsWith("data:", ignoreCase = true)) {
                return "data_uri_${abs(resolved.hashCode())}"
            }

            val clean = cleanMediaUrl(resolved)
            val noHash = clean.substringBefore("#")

            val withHttps = when {
                noHash.startsWith("http://", ignoreCase = true) -> "https://" + noHash.substring(7)
                noHash.startsWith("//") -> "https:$noHash"
                else -> noHash
            }

            val qIdx = withHttps.indexOf('?')
            val basePart = if (qIdx == -1) withHttps else withHttps.substring(0, qIdx)
            val queryPart = if (qIdx == -1) "" else withHttps.substring(qIdx + 1)

            val normalizedPath = try {
                java.net.URLDecoder.decode(basePart, "UTF-8").lowercase(Locale.ROOT)
            } catch (_: Exception) {
                basePart.lowercase(Locale.ROOT)
            }

            var normalizedHostAndPath = normalizedPath
                .replace("thumb.wikimedia.org", "upload.wikimedia.org")
                .replace(Regex("""https?://preview\.redd\.it/"""), "https://i.redd.it/")
                .replace(Regex("""https?://external-preview\.redd\.it/"""), "https://i.redd.it/")
                .replace(Regex("""https?://i[0-3]\.wp\.com/"""), "https://i.wp.com/")

            // 1. Pinterest Deduplication Key: Content-addressable 32-hex hash
            if (normalizedHostAndPath.contains("pinimg.com")) {
                val pinHash = Regex("""([0-9a-fA-F]{32})""").find(normalizedHostAndPath)?.groupValues?.get(1)
                if (pinHash != null) {
                    return "pinimg:$pinHash"
                }
            }

            // 1b. YouTube Video Thumbnails: All resolution, crop, and format variants of the same video's thumbnail
            val ytThumbMatch = Regex("""(?:ytimg\.com|youtube\.com)/(?:vi|vi_webp)/([a-zA-Z0-9_-]{11})/""").find(normalizedHostAndPath)
            if (ytThumbMatch != null) {
                val videoId = ytThumbMatch.groupValues[1]
                return "ytthumb:$videoId"
            }

            // 1c. YouTube Video Playback Stream CDN (googlevideo.com/videoplayback)
            if (normalizedHostAndPath.contains("googlevideo.com/videoplayback") || normalizedHostAndPath.contains("/videoplayback")) {
                val qParams = queryPart.split("&").associate {
                    it.substringBefore("=").lowercase(Locale.ROOT) to it.substringAfter("=", "")
                }
                val videoId = qParams["id"] ?: ""
                val itag = qParams["itag"] ?: ""
                if (videoId.isNotBlank()) {
                    return "googlevideo:${videoId}_itag_$itag"
                }
            }

            // 2. Google User Content: Strip dynamic sizing modifiers (=w... / =s...)
            if (normalizedHostAndPath.contains("googleusercontent.com")) {
                normalizedHostAndPath = normalizedHostAndPath.substringBefore("=")
            }

            // 3. Imgur suffix stripping in deduplication key
            if (normalizedHostAndPath.contains("i.imgur.com/")) {
                val fn = normalizedHostAndPath.substringAfterLast('/')
                val id = fn.substringBeforeLast('.')
                val ext = fn.substringAfterLast('.', "")
                val m = Regex("""^([a-zA-Z0-9]{5,8})(?:[smlhbt]|_d)$""").find(id)
                if (m != null) {
                    val root = normalizedHostAndPath.substringBeforeLast('/')
                    normalizedHostAndPath = "$root/${m.groupValues[1]}.$ext"
                }
            }

            // 4. WordPress / CMS resized image files (-300x200, -scaled, -thumb before extension)
            normalizedHostAndPath = normalizedHostAndPath.replace(
                Regex("""-(?:\d{2,5}x\d{2,5}|scaled|thumb|medium|large|small)\.([a-zA-Z0-9]+)$"""),
                ".$1"
            )

            // 5. Shopify CDN image size suffix (_1024x1024, _grande, _medium etc. before extension)
            normalizedHostAndPath = normalizedHostAndPath.replace(
                Regex("""_(?:\d{2,5}x\d{2,5}|\d{2,5}x|x\d{2,5}|pico|icon|thumb|small|compact|medium|large|grande|original|master|1024x1024|2048x2048)\.([a-zA-Z0-9]+)$"""),
                ".$1"
            )

            // 6. Retina display image suffixes (@2x, @3x)
            normalizedHostAndPath = normalizedHostAndPath.replace(
                Regex("""@(?:\d+x|\d+\.\d+x)\.([a-zA-Z0-9]+)$"""),
                ".$1"
            )

            // 7. Generic path folder dimension descriptors (e.g. /736x/, /236x/, /474x/, /1200x/, /300x200/, /w1200/, /h800/)
            normalizedHostAndPath = normalizedHostAndPath.replace(
                Regex("""/(?:\d{2,5}x\d{0,5}|\d{0,5}x\d{2,5}|w\d{2,5}|h\d{2,5}|s\d{2,5}|width[_-]\d+|height[_-]\d+)/"""),
                "/"
            )

            // 8. Cloudflare Image Resizing / Cloudinary / Imgix dynamic resize transform stripping (e.g. /cdn-cgi/image/.../ -> /, /c_scale,w_500/ -> /)
            normalizedHostAndPath = normalizedHostAndPath
                .replace(Regex("""/cdn-cgi/image/[^/]+/"""), "/")
                .replace(Regex("""/(?:[a-z]_[a-zA-Z0-9_-]+,?)+/"""), "/")

            // 9. Strip video quality / transcode suffixes to deduplicate multiple transcode qualities of same video
            val videoQualityRegex = Regex("""(?:\.(?:144|240|360|480|720|1080|1440|2160)p(?:\.vp9|\.av1)?|[._-](?:144|240|360|480|720|1080|1440|2160)p)\.(webm|mp4|mkv)$""", RegexOption.IGNORE_CASE)
            val normalizedVideoPath = videoQualityRegex.replace(normalizedHostAndPath) { match ->
                "." + match.groupValues[1]
            }

            // 10. Format variants normalization (raster image formats .jpg, .jpeg, .webp, .avif, .png of the same asset)
            // Deduplicate format variants by normalizing raster image extensions to .img
            val normalizedFormatPath = if (Regex("""\.(?:jpe?g|webp|png|avif)$""", RegexOption.IGNORE_CASE).containsMatchIn(normalizedVideoPath)) {
                normalizedVideoPath.replace(Regex("""\.(?:jpe?g|webp|png|avif)$""", RegexOption.IGNORE_CASE), ".img")
            } else {
                normalizedVideoPath
            }

            val sizingParams = setOf(
                "w", "width", "h", "height", "size", "s", "quality", "q",
                "fit", "crop", "auto", "format", "fmt", "fm", "f", "dpr", "lossless", "strip",
                "scale", "resize", "maxwidth", "maxheight",
                "sqp", "rs", "sigh", "sig", "sparams",
                "itag", "range", "ratebypass", "rn", "rbuf", "mime", "gir", "clen", "dur", "lmt"
            )
            val filteredQuery = if (queryPart.isNotBlank()) {
                queryPart.split("&").filter { param ->
                    val key = param.substringBefore("=").lowercase(Locale.ROOT)
                    !TRACKING_QUERY_PARAMS.contains(key) && !sizingParams.contains(key)
                }.sorted().joinToString("&")
            } else ""

            if (filteredQuery.isEmpty()) normalizedFormatPath else "$normalizedFormatPath?$filteredQuery"
        } catch (_: Exception) {
            url.lowercase(Locale.ROOT)
        }
    }

    /**
     * Parses an approximate pixel area (width * height) from a media item's resolution hint or URL,
     * used for deterministic quality comparisons when deduplicating variants.
     */
    fun parsePixelArea(item: ScrapedMediaItem): Long? {
        return parsePixelArea(item.resolutionHint, item.url)
    }

    /**
     * Parses pixel area from resolution hint string or URL path.
     */
    fun parsePixelArea(resolutionHint: String?, url: String?): Long? {
        if (!resolutionHint.isNullOrBlank()) {
            val resMatch = Regex("""(\d{2,5})\s*[xX*×]\s*(\d{2,5})""").find(resolutionHint)
            if (resMatch != null) {
                val w = resMatch.groupValues[1].toLongOrNull()
                val h = resMatch.groupValues[2].toLongOrNull()
                if (w != null && h != null && w > 0 && h > 0) {
                    return w * h
                }
            }
            val pMatch = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE).find(resolutionHint)
            if (pMatch != null) {
                val h = pMatch.groupValues[1].toLongOrNull()
                if (h != null && h > 0) return ((h * 16L) / 9L) * h
            }
            val wMatch = Regex("""(\d{2,5})[wW]""").find(resolutionHint)
            if (wMatch != null) {
                val w = wMatch.groupValues[1].toLongOrNull()
                if (w != null && w > 0) return w * w
            }
            val pxMatch = Regex("""(\d{2,5})px""", RegexOption.IGNORE_CASE).find(resolutionHint)
            if (pxMatch != null) {
                val px = pxMatch.groupValues[1].toLongOrNull()
                if (px != null && px > 0) return px * px
            }
        }

        if (!url.isNullOrBlank()) {
            if (url.contains("/originals/", ignoreCase = true)) {
                return 25_000_000L // Original master file ranks highest
            }
            val urlResMatch = Regex("""[/_-](\d{2,5})x(\d{2,5})[._/-]""").find(url)
            if (urlResMatch != null) {
                val w = urlResMatch.groupValues[1].toLongOrNull()
                val h = urlResMatch.groupValues[2].toLongOrNull()
                if (w != null && h != null && w > 0 && h > 0) {
                    return w * h
                }
            }
            val folderDimMatch = Regex("""/(\d{2,5})x/""").find(url)
            if (folderDimMatch != null) {
                val w = folderDimMatch.groupValues[1].toLongOrNull()
                if (w != null && w > 0) return w * w
            }
            val pxUrlMatch = Regex("""[/_-](\d{2,5})px[._/-]""").find(url)
            if (pxUrlMatch != null) {
                val px = pxUrlMatch.groupValues[1].toLongOrNull()
                if (px != null && px > 0) return px * px
            }
            val queryWidthMatch = Regex("""[?&](?:w|width)=(\d{2,5})""").find(url)
            if (queryWidthMatch != null) {
                val w = queryWidthMatch.groupValues[1].toLongOrNull()
                if (w != null && w > 0) return w * w
            }
        }
        return null
    }

    /**
     * Compares two duplicate scraped media items to determine which offers superior quality
     * (e.g. master original over thumbnail, higher pixel resolution, larger file size, preferred format).
     * Returns > 0 if [a] is higher quality, < 0 if [b] is higher quality, 0 if equivalent.
     */
    fun compareMediaQuality(a: ScrapedMediaItem, b: ScrapedMediaItem): Int {
        // 1. Originals master URL preference
        val aIsOriginals = a.url.contains("/originals/", ignoreCase = true)
        val bIsOriginals = b.url.contains("/originals/", ignoreCase = true)
        if (aIsOriginals && !bIsOriginals) return 1
        if (!aIsOriginals && bIsOriginals) return -1

        // 2. Direct upload vs thumbnail/transcoded path
        val aIsThumb = a.url.contains("/thumb/") || a.url.contains("/transcoded/")
        val bIsThumb = b.url.contains("/thumb/") || b.url.contains("/transcoded/")
        if (!aIsThumb && bIsThumb) return 1
        if (aIsThumb && !bIsThumb) return -1

        // 3. Pixel area from resolutionHint or URL
        val aArea = parsePixelArea(a)
        val bArea = parsePixelArea(b)
        if (aArea != null && bArea != null && aArea != bArea) {
            return aArea.compareTo(bArea)
        }
        if (aArea != null && bArea == null) return 1
        if (aArea == null && bArea != null) return -1

        // 4. File size in bytes
        val aSize = a.sizeBytes ?: 0L
        val bSize = b.sizeBytes ?: 0L
        if (aSize != bSize) {
            return aSize.compareTo(bSize)
        }

        // 5. Format preference for master downloads (JPEG / JPG / PNG over lossy WEBP / AVIF)
        val formatPriority = mapOf("PNG" to 4, "JPEG" to 3, "JPG" to 3, "WEBP" to 2, "AVIF" to 1)
        val aFmt = formatPriority[a.format.uppercase(Locale.ROOT)] ?: 0
        val bFmt = formatPriority[b.format.uppercase(Locale.ROOT)] ?: 0
        if (aFmt != bFmt) {
            return aFmt.compareTo(bFmt)
        }

        // 6. Prefer candidate with existing thumbnail preview
        if (a.thumbnailUrl != null && b.thumbnailUrl == null) return 1
        if (a.thumbnailUrl == null && b.thumbnailUrl != null) return -1

        return 0
    }

    /**
     * Merges incoming media items into a target map keyed by deduplicationKey.
     * Unified pipeline for deduplication, video-poster folding, quality upgrades,
     * resolution hint selection, provenance merging, and header retention.
     * Returns true if any item was added, replaced, or upgraded.
     */
    fun mergeMediaItems(
        targetMap: MutableMap<String, ScrapedMediaItem>,
        incoming: List<ScrapedMediaItem>
    ): Boolean {
        if (incoming.isEmpty()) return false
        var modified = false

        fun getVideoThumbKeys(): Set<String> {
            val keys = mutableSetOf<String>()
            for (entry in targetMap.values) {
                if (entry.isVideo) {
                    entry.thumbnailUrl?.let { keys.add(deduplicationKey(it)) }
                    val ytId = extractYouTubeVideoId(entry.url)
                        ?: entry.thumbnailUrl?.let { extractYouTubeVideoId(it) }
                        ?: extractYouTubeVideoId(entry.provenance.pageUrl)
                        ?: entry.metadata["source_url"]?.let { extractYouTubeVideoId(it) }
                        ?: entry.metadata["webpage_url"]?.let { extractYouTubeVideoId(it) }
                    if (ytId != null) {
                        keys.add("ytthumb:${ytId.lowercase(Locale.ROOT)}")
                    }
                }
            }
            return keys
        }

        for (item in incoming) {
            val key = deduplicationKey(item.url)

            // Case A: Incoming item is a Video -> fold existing matching poster image into thumbnail
            if (item.isVideo) {
                val ytId = extractYouTubeVideoId(item.url)
                    ?: item.thumbnailUrl?.let { extractYouTubeVideoId(it) }
                    ?: extractYouTubeVideoId(item.provenance.pageUrl)
                    ?: item.metadata["source_url"]?.let { extractYouTubeVideoId(it) }
                    ?: item.metadata["webpage_url"]?.let { extractYouTubeVideoId(it) }
                val videoPosterKeys = mutableSetOf<String>()
                item.thumbnailUrl?.let { videoPosterKeys.add(deduplicationKey(it)) }
                if (ytId != null) videoPosterKeys.add("ytthumb:${ytId.lowercase(Locale.ROOT)}")

                var bestThumb = item.thumbnailUrl
                for (pKey in videoPosterKeys) {
                    val existingImg = targetMap[pKey]
                    if (existingImg != null && !existingImg.isVideo) {
                        if (bestThumb == null || compareMediaQuality(existingImg, item.copy(url = bestThumb)) > 0) {
                            bestThumb = existingImg.thumbnailUrl ?: existingImg.url
                        }
                        targetMap.remove(pKey)
                        modified = true
                    }
                }
                val candidateVideo = if (bestThumb != item.thumbnailUrl) item.copy(thumbnailUrl = bestThumb) else item
                val existing = targetMap[key]
                if (existing == null) {
                    targetMap[key] = candidateVideo
                    modified = true
                } else {
                    val cmp = compareMediaQuality(candidateVideo, existing)
                    val preferred = if (cmp > 0) candidateVideo else existing
                    val secondary = if (preferred === candidateVideo) existing else candidateVideo
                    val upgraded = preferred.copy(
                        id = existing.id,
                        thumbnailUrl = preferred.thumbnailUrl ?: secondary.thumbnailUrl
                    )
                    if (upgraded != existing) {
                        targetMap[key] = upgraded
                        modified = true
                    }
                }
                continue
            }

            // Case B: Incoming item is an Image -> check if it matches an existing video's thumbnail/poster
            val currentVideoThumbKeys = getVideoThumbKeys()
            if (key in currentVideoThumbKeys) {
                for ((vKey, vItem) in targetMap.entries.toList()) {
                    if (vItem.isVideo) {
                        val ytId = extractYouTubeVideoId(vItem.url)
                            ?: vItem.thumbnailUrl?.let { extractYouTubeVideoId(it) }
                            ?: vItem.provenance.pageUrl.let { extractYouTubeVideoId(it) }
                            ?: vItem.metadata["source_url"]?.let { extractYouTubeVideoId(it) }
                            ?: vItem.metadata["webpage_url"]?.let { extractYouTubeVideoId(it) }
                        val matches = (vItem.thumbnailUrl != null && deduplicationKey(vItem.thumbnailUrl) == key) ||
                                (ytId != null && "ytthumb:${ytId.lowercase(Locale.ROOT)}" == key)
                        if (matches) {
                            if (vItem.thumbnailUrl == null || compareMediaQuality(item, vItem.copy(url = vItem.thumbnailUrl)) > 0) {
                                targetMap[vKey] = vItem.copy(thumbnailUrl = item.thumbnailUrl ?: item.url)
                                modified = true
                            }
                        }
                    }
                }
                continue
            }

            // Case C: Standard media insertion / quality upgrade
            val existing = targetMap[key]
            if (existing == null) {
                targetMap[key] = item
                modified = true
            } else {
                val cmp = compareMediaQuality(item, existing)
                val preferred = if (cmp > 0) item else existing
                val secondary = if (preferred === item) existing else item

                val engines = listOf(existing.provenance.extractorEngine, item.provenance.extractorEngine).distinct()
                val mergedEngine = engines.joinToString(" + ")
                val mergedThumb = preferred.thumbnailUrl ?: secondary.thumbnailUrl
                val mergedResolution = when {
                    parsePixelArea(preferred) != null -> preferred.resolutionHint ?: secondary.resolutionHint
                    parsePixelArea(secondary) != null -> secondary.resolutionHint ?: preferred.resolutionHint
                    !preferred.resolutionHint.isNullOrBlank() && preferred.resolutionHint != "Network" -> preferred.resolutionHint
                    !secondary.resolutionHint.isNullOrBlank() && secondary.resolutionHint != "Network" -> secondary.resolutionHint
                    else -> preferred.resolutionHint ?: secondary.resolutionHint
                }
                val mergedHeaders = secondary.downloadHeaders + preferred.downloadHeaders
                val resolvedUrl = resolveOriginalMedia(preferred.url).mediaUrl

                val upgraded = preferred.copy(
                    id = existing.id,
                    url = resolvedUrl,
                    thumbnailUrl = mergedThumb,
                    resolutionHint = mergedResolution,
                    provenance = preferred.provenance.copy(extractorEngine = mergedEngine),
                    downloadHeaders = mergedHeaders
                )
                if (upgraded != existing) {
                    targetMap[key] = upgraded
                    modified = true
                }
            }
        }
        return modified
    }

    /**
     * Deduplicates a list of scraped media items discovered by one or more extraction engines.
     * Retains the highest-quality master file, folds video posters, combines provenance,
     * and preserves required download headers.
     */
    fun deduplicateScrapedItems(items: List<ScrapedMediaItem>): List<ScrapedMediaItem> {
        if (items.isEmpty()) return emptyList()
        val map = LinkedHashMap<String, ScrapedMediaItem>()
        mergeMediaItems(map, items)
        return map.values.toList()
    }

    /**
     * Extracts an 11-character YouTube video ID from a watch/shorts/embed URL or thumbnail CDN URL.
     */
    fun extractYouTubeVideoId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val regex = Regex("""(?:youtu\.be/|youtube\.com/(?:embed/|v/|shorts/|watch\?v=|watch\?.+&v=))([a-zA-Z0-9_-]{11})""")
        val m = regex.find(url)
        if (m != null) return m.groupValues[1]
        val thumbRegex = Regex("""(?:ytimg\.com|youtube\.com)/(?:vi|vi_webp)/([a-zA-Z0-9_-]{11})""")
        return thumbRegex.find(url)?.groupValues?.get(1)
    }

    /**
     * Checks if a media item is an SVG vector asset based on format, MIME type, or URL extension.
     */
    fun isSvg(url: String, mimeHint: String? = null, format: String? = null): Boolean {
        if (format?.equals("SVG", ignoreCase = true) == true) return true
        if (mimeHint?.contains("svg", ignoreCase = true) == true) return true
        val clean = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        return clean.endsWith(".svg")
    }

    /**
     * Classifies a media URL into category (IMAGE, VIDEO, AUDIO, STREAM) and specific format string.
     */
    fun classify(url: String, mimeHint: String? = null): ClassificationResult {
        val lower = url.lowercase(Locale.ROOT)
        val mimeLower = mimeHint?.lowercase(Locale.ROOT) ?: ""

        // 1. Streaming media (HLS & DASH)
        val cleanPath = lower.substringBefore("?").substringBefore("#")
        val isHlsManifest = cleanPath.endsWith(".m3u8") || lower.contains(".m3u8") ||
                mimeLower.contains("x-mpegurl") || mimeLower.contains("vnd.apple.mpegurl") || mimeLower.contains("application/mpegurl")
        if (isHlsManifest) {
            return ClassificationResult(
                category = MediaCategory.STREAMS,
                format = "HLS (m3u8)",
                mimeType = "application/x-mpegURL",
                isVideo = true,
                isAudio = false,
                isStream = true
            )
        }
        val isDashManifest = cleanPath.endsWith(".mpd") || lower.contains(".mpd") ||
                mimeLower.contains("dash+xml") || mimeLower.contains("video/vnd.mpeg.dash.mpd")
        if (isDashManifest) {
            return ClassificationResult(
                category = MediaCategory.STREAMS,
                format = "DASH (mpd)",
                mimeType = "application/dash+xml",
                isVideo = true,
                isAudio = false,
                isStream = true
            )
        }

        // 2. Video formats
        if (lower.contains("/videoplayback") || lower.contains("googlevideo.com")) {
            val isAudioStream = lower.contains("mime=audio") || mimeLower.contains("audio/")
            return if (isAudioStream) {
                ClassificationResult(
                    category = MediaCategory.AUDIO,
                    format = "M4A",
                    mimeType = "audio/mp4",
                    isVideo = false,
                    isAudio = true,
                    isStream = false
                )
            } else {
                ClassificationResult(
                    category = MediaCategory.VIDEOS,
                    format = "MP4",
                    mimeType = "video/mp4",
                    isVideo = true,
                    isAudio = false,
                    isStream = false
                )
            }
        }

        if (lower.contains(".mp4") || mimeLower.contains("video/mp4") || lower.contains("video/mp4")) {
            return ClassificationResult(
                category = MediaCategory.VIDEOS,
                format = "MP4",
                mimeType = "video/mp4",
                isVideo = true,
                isAudio = false,
                isStream = false
            )
        }
        if (lower.contains(".webm") || mimeLower.contains("video/webm")) {
            return ClassificationResult(
                category = MediaCategory.VIDEOS,
                format = "WEBM",
                mimeType = "video/webm",
                isVideo = true,
                isAudio = false,
                isStream = false
            )
        }
        if (lower.contains(".mov") || mimeLower.contains("video/quicktime")) {
            return ClassificationResult(
                category = MediaCategory.VIDEOS,
                format = "MOV",
                mimeType = "video/quicktime",
                isVideo = true,
                isAudio = false,
                isStream = false
            )
        }
        if (lower.contains(".mkv") || mimeLower.contains("video/x-matroska")) {
            return ClassificationResult(
                category = MediaCategory.VIDEOS,
                format = "MKV",
                mimeType = "video/x-matroska",
                isVideo = true,
                isAudio = false,
                isStream = false
            )
        }
        if (mimeLower.startsWith("video/")) {
            val sub = mimeLower.substringAfter("video/").uppercase(Locale.ROOT)
            return ClassificationResult(
                category = MediaCategory.VIDEOS,
                format = sub,
                mimeType = mimeLower,
                isVideo = true,
                isAudio = false,
                isStream = false
            )
        }

        // 3. Audio formats
        if (lower.contains(".mp3") || mimeLower.contains("audio/mpeg") || mimeLower.contains("audio/mp3")) {
            return ClassificationResult(
                category = MediaCategory.AUDIO,
                format = "MP3",
                mimeType = "audio/mpeg",
                isVideo = false,
                isAudio = true,
                isStream = false
            )
        }
        if (lower.contains(".m4a") || lower.contains(".aac") || mimeLower.contains("audio/aac") || mimeLower.contains("audio/mp4")) {
            return ClassificationResult(
                category = MediaCategory.AUDIO,
                format = if (lower.contains(".aac")) "AAC" else "M4A",
                mimeType = "audio/mp4",
                isVideo = false,
                isAudio = true,
                isStream = false
            )
        }
        if (lower.contains(".wav") || mimeLower.contains("audio/wav") || mimeLower.contains("audio/x-wav")) {
            return ClassificationResult(
                category = MediaCategory.AUDIO,
                format = "WAV",
                mimeType = "audio/wav",
                isVideo = false,
                isAudio = true,
                isStream = false
            )
        }
        if (lower.contains(".ogg") || lower.contains(".opus") || mimeLower.contains("audio/ogg") || mimeLower.contains("audio/opus")) {
            return ClassificationResult(
                category = MediaCategory.AUDIO,
                format = if (lower.contains(".opus")) "OPUS" else "OGG",
                mimeType = "audio/ogg",
                isVideo = false,
                isAudio = true,
                isStream = false
            )
        }
        if (lower.contains(".flac") || mimeLower.contains("audio/flac")) {
            return ClassificationResult(
                category = MediaCategory.AUDIO,
                format = "FLAC",
                mimeType = "audio/flac",
                isVideo = false,
                isAudio = true,
                isStream = false
            )
        }
        if (mimeLower.startsWith("audio/")) {
            val sub = mimeLower.substringAfter("audio/").uppercase(Locale.ROOT)
            return ClassificationResult(
                category = MediaCategory.AUDIO,
                format = sub,
                mimeType = mimeLower,
                isVideo = false,
                isAudio = true,
                isStream = false
            )
        }

        // 4. Image formats
        val format = when {
            lower.contains(".webp") || mimeLower.contains("webp") -> "WEBP"
            lower.contains(".png") || mimeLower.contains("png") -> "PNG"
            lower.contains(".gif") || mimeLower.contains("gif") -> "GIF"
            lower.contains(".svg") || mimeLower.contains("svg") -> "SVG"
            lower.contains(".avif") || mimeLower.contains("avif") -> "AVIF"
            lower.contains(".bmp") || mimeLower.contains("bmp") -> "BMP"
            else -> "JPEG"
        }
        val imgMime = when (format) {
            "WEBP" -> "image/webp"
            "PNG" -> "image/png"
            "GIF" -> "image/gif"
            "SVG" -> "image/svg+xml"
            "AVIF" -> "image/avif"
            "BMP" -> "image/bmp"
            else -> "image/jpeg"
        }

        return ClassificationResult(
            category = MediaCategory.IMAGES,
            format = format,
            mimeType = imgMime,
            isVideo = false,
            isAudio = false,
            isStream = false
        )
    }

    /**
     * Derives a clean filename from URL and format.
     */
    fun deriveFileName(url: String, format: String, fallbackIndex: Int): String {
        return runCatching {
            val cleanUrl = url.substringBefore("?").substringBefore("#")
            val lastSlash = cleanUrl.lastIndexOf('/')
            val segment = if (lastSlash != -1 && lastSlash < cleanUrl.length - 1) cleanUrl.substring(lastSlash + 1) else ""
            val extInSegment = segment.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (segment.length > 2 && extInSegment in KNOWN_MEDIA_EXTENSIONS) {
                segment
            } else {
                val ext = when (format.uppercase(Locale.ROOT)) {
                    "HLS (M3U8)", "HLS" -> "m3u8"
                    "DASH (MPD)", "DASH" -> "mpd"
                    "MP4" -> "mp4"
                    "WEBM" -> "webm"
                    "MOV" -> "mov"
                    "MP3" -> "mp3"
                    "M4A" -> "m4a"
                    "AAC" -> "aac"
                    "WAV" -> "wav"
                    "FLAC" -> "flac"
                    "WEBP" -> "webp"
                    "PNG" -> "png"
                    "GIF" -> "gif"
                    "SVG" -> "svg"
                    "AVIF" -> "avif"
                    else -> "jpg"
                }
                "media_${fallbackIndex + 1}.$ext"
            }
        }.getOrDefault("media_${fallbackIndex + 1}.bin")
    }

    /**
     * Parses srcset attribute (e.g., "img1.jpg 300w, img2.jpg 600w, img3.jpg 1200w").
     * Returns a list of Pair(url, resolutionDescriptor).
     */
    fun parseSrcset(srcset: String, baseUrl: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val entries = srcset.split(",")
        for (entry in entries) {
            val parts = entry.trim().split(Regex("\\s+"))
            if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                val resolved = resolveUrl(parts[0], baseUrl)
                if (resolved != null && isValidMedia(resolved)) {
                    val descriptor = if (parts.size > 1) parts[1] else ""
                    results.add(Pair(resolved, descriptor))
                }
            }
        }
        return results
    }

    fun formatQueryOrUrl(input: String, searchEngine: BrowserSearchEngine = BrowserSearchEngine.DUCKDUCKGO): String {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return ""
        return if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else if (trimmed.contains(".") && !trimmed.contains(" ") && trimmed.length > 3) {
            "https://$trimmed"
        } else {
            "${searchEngine.searchUrl}${URLEncoder.encode(trimmed, "UTF-8")}"
        }
    }
}

enum class BrowserSearchEngine(val label: String, val shortLabel: String, val searchUrl: String) {
    DUCKDUCKGO("DuckDuckGo", "DDG", "https://duckduckgo.com/?q="),
    GOOGLE("Google", "Google", "https://www.google.com/search?q="),
    BING("Bing", "Bing", "https://www.bing.com/search?q="),
    WIKIPEDIA("Wikipedia", "Wiki", "https://en.wikipedia.org/wiki/Special:Search?search=")
}

data class ClassificationResult(
    val category: MediaCategory,
    val format: String,
    val mimeType: String,
    val isVideo: Boolean,
    val isAudio: Boolean,
    val isStream: Boolean
)
