package com.amresalehin.emregallery.data

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Shared session HTTP client and high-performance streaming downloader powered by OkHttp.
 * Provides:
 * 1. Unified, persistent cookie jar synchronized bidirectionally with Android WebView CookieManager.
 * 2. Shared connection pooling (HTTP/2, keep-alive) and automatic redirect handling.
 * 3. Resumable downloads via HTTP Range (RFC 7233) with byte-level progress reporting.
 * 4. Atomic file creation, MIME validation, and corrupt partial download cleanup.
 */
object OkHttpDownloader {

    private const val TAG = "OkHttpDownloader"
    const val DEFAULT_USER_AGENT = BotDetectionMitigator.STEALTH_USER_AGENT

    /**
     * Shared bidirectional CookieJar bridging Android WebView CookieManager and in-memory cache.
     */
    class SharedSessionCookieJar : CookieJar {
        private val cookieLock = Any()
        private val inMemoryCookies = ConcurrentHashMap<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            val host = url.host
            val urlString = url.toString()

            synchronized(cookieLock) {
                val currentList = inMemoryCookies.getOrPut(host) { mutableListOf() }
                for (newCookie in cookies) {
                    currentList.removeAll { it.name == newCookie.name }
                    currentList.add(newCookie)
                }

                // Sync into Android WebView CookieManager if available
                try {
                    val cookieManager = CookieManager.getInstance()
                    for (cookie in cookies) {
                        cookieManager.setCookie(urlString, cookie.toString())
                    }
                    cookieManager.flush()
                } catch (_: Throwable) {
                    // Ignore in non-Android or JVM unit test environments
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val host = url.host
            val urlString = url.toString()
            val now = System.currentTimeMillis()

            synchronized(cookieLock) {
                val result = mutableListOf<Cookie>()

                // 1. Load from in-memory cache
                inMemoryCookies[host]?.let { list ->
                    list.removeAll { it.expiresAt <= now }
                    result.addAll(list)
                }

                // 2. Load from Android WebView CookieManager if available
                try {
                    val cookieManager = CookieManager.getInstance()
                    val rawCookieStr = cookieManager.getCookie(urlString)
                    if (!rawCookieStr.isNullOrBlank()) {
                        val parsed = rawCookieStr.split(";").mapNotNull { part ->
                            val trimmed = part.trim()
                            if (trimmed.isNotEmpty()) {
                                Cookie.parse(url, trimmed)
                            } else null
                        }
                        for (c in parsed) {
                            if (result.none { it.name == c.name }) {
                                result.add(c)
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // Ignore in unit test environments
                }

                return result
            }
        }

        fun getCookieString(url: String): String {
            val httpUrl = url.toHttpUrlOrNull() ?: return ""
            return loadForRequest(httpUrl).joinToString("; ") { "${it.name}=${it.value}" }
        }

        fun setManualCookie(url: String, cookieName: String, cookieValue: String) {
            val httpUrl = url.toHttpUrlOrNull() ?: return
            val cookie = Cookie.Builder()
                .domain(httpUrl.host)
                .path("/")
                .name(cookieName)
                .value(cookieValue)
                .build()
            saveFromResponse(httpUrl, listOf(cookie))
        }

        fun clearSession() {
            synchronized(cookieLock) {
                inMemoryCookies.clear()
                try {
                    val cm = CookieManager.getInstance()
                    cm.removeAllCookies(null)
                    cm.flush()
                } catch (_: Throwable) {}
            }
        }
    }

    val sharedCookieJar = SharedSessionCookieJar()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(sharedCookieJar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val original = chain.request()
                val requestBuilder = original.newBuilder()

                // Attach comprehensive browser stealth headers to prevent anti-bot and Cloudflare 403 blocks
                BotDetectionMitigator.STEALTH_HEADERS.forEach { (key, value) ->
                    if (original.header(key).isNullOrBlank()) {
                        requestBuilder.header(key, value)
                    }
                }
                chain.proceed(requestBuilder.build())
            }
            .build()
    }

    data class HttpResponse(
        val isSuccessful: Boolean,
        val statusCode: Int,
        val contentType: String,
        val finalUrl: String,
        val bodyString: String? = null,
        val errorMessage: String? = null
    )

    data class DownloadResult(
        val file: File?,
        val isSuccessful: Boolean,
        val statusCode: Int,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val mimeType: String? = null,
        val isResumed: Boolean = false,
        val errorMessage: String? = null,
        val retryAfterSeconds: Long? = null
    )

    /**
     * Data class capturing the result of probing a URL for direct media file headers.
     */
    data class DirectMediaProbeResult(
        val isConfirmedDirectMedia: Boolean,
        val finalUrl: String,
        val contentType: String?,
        val contentDisposition: String?,
        val contentLength: Long?,
        val statusCode: Int
    )

    /**
     * Probes an arbitrary URL using OkHttp (HEAD request with fast Range GET fallback)
     * to verify if the URL points directly to an image, video, audio or binary stream.
     */
    suspend fun probeDirectMedia(
        url: String,
        referer: String? = null
    ): DirectMediaProbeResult = withContext(Dispatchers.IO) {
        try {
            val headReq = Request.Builder()
                .url(url)
                .head()
                .apply {
                    if (!referer.isNullOrBlank()) header("Referer", referer)
                }
                .build()

            val headResp = runCatching { client.newCall(headReq).execute() }.getOrNull()
            if (headResp != null && headResp.isSuccessful) {
                headResp.use { resp ->
                    val ct = resp.header("Content-Type")?.lowercase(Locale.ROOT)
                    val cd = resp.header("Content-Disposition")
                    val cl = resp.header("Content-Length")?.toLongOrNull()
                    val finalUrl = resp.request.url.toString()
                    val isMedia = isMediaContentType(ct) || hasMediaContentDisposition(cd) || isDirectMediaExtension(finalUrl)
                    if (isMedia) {
                        return@withContext DirectMediaProbeResult(
                            isConfirmedDirectMedia = true,
                            finalUrl = finalUrl,
                            contentType = ct,
                            contentDisposition = cd,
                            contentLength = cl,
                            statusCode = resp.code
                        )
                    }
                }
            }

            // Fallback: fast Range GET (0-1024) to probe server if HEAD was rejected or inconclusive
            val rangeReq = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-1024")
                .apply {
                    if (!referer.isNullOrBlank()) header("Referer", referer)
                }
                .build()

            client.newCall(rangeReq).execute().use { resp ->
                val ct = resp.header("Content-Type")?.lowercase(Locale.ROOT)
                val cd = resp.header("Content-Disposition")
                val cl = resp.header("Content-Length")?.toLongOrNull()
                val finalUrl = resp.request.url.toString()
                val isMedia = (resp.code in 200..206) && (isMediaContentType(ct) || hasMediaContentDisposition(cd) || isDirectMediaExtension(finalUrl))
                DirectMediaProbeResult(
                    isConfirmedDirectMedia = isMedia,
                    finalUrl = finalUrl,
                    contentType = ct,
                    contentDisposition = cd,
                    contentLength = cl,
                    statusCode = resp.code
                )
            }
        } catch (e: Exception) {
            DirectMediaProbeResult(
                isConfirmedDirectMedia = false,
                finalUrl = url,
                contentType = null,
                contentDisposition = null,
                contentLength = null,
                statusCode = -1
            )
        }
    }

    private fun isMediaContentType(ct: String?): Boolean {
        if (ct.isNullOrBlank()) return false
        val clean = ct.substringBefore(";").trim().lowercase(Locale.ROOT)
        return clean.startsWith("image/") ||
                clean.startsWith("video/") ||
                clean.startsWith("audio/") ||
                clean == "application/x-mpegurl" ||
                clean == "application/vnd.apple.mpegurl" ||
                clean == "application/dash+xml"
    }

    private fun hasMediaContentDisposition(cd: String?): Boolean {
        if (cd.isNullOrBlank()) return false
        val lower = cd.lowercase(Locale.ROOT)
        return lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".mkv") ||
                lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png") ||
                lower.contains(".webp") || lower.contains(".gif") || lower.contains(".mp3") ||
                lower.contains(".m4a") || lower.contains(".flac") || lower.contains(".wav")
    }

    private fun isDirectMediaExtension(url: String): Boolean {
        val clean = url.substringBefore("?").substringBefore("#").lowercase(Locale.ROOT)
        return clean.endsWith(".mp4") || clean.endsWith(".webm") || clean.endsWith(".mkv") ||
                clean.endsWith(".mov") || clean.endsWith(".jpg") || clean.endsWith(".jpeg") ||
                clean.endsWith(".png") || clean.endsWith(".webp") || clean.endsWith(".gif") ||
                clean.endsWith(".m3u8") || clean.endsWith(".mpd") || clean.endsWith(".mp3") ||
                clean.endsWith(".m4a") || clean.endsWith(".flac") || clean.endsWith(".wav")
    }

    /**
     * Parses the HTTP 'Retry-After' header (RFC 7231/9110) as seconds or HTTP-date.
     */
    fun parseRetryAfterHeader(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val trimmed = header.trim()
        val seconds = trimmed.toLongOrNull()
        if (seconds != null) {
            return seconds.coerceIn(1L, 300L)
        }
        return try {
            val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
            val date = format.parse(trimmed)
            if (date != null) {
                val diffSec = (date.time - System.currentTimeMillis()) / 1000L
                if (diffSec > 0) diffSec.coerceIn(1L, 300L) else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Executes an HTTP GET request with OkHttp, following redirects and utilizing shared session cookies.
     * Incorporates multi-stage resilience: Desktop User-Agent retry, www/non-www alternate host,
     * and Jsoup connection fallback to conquer anti-bot, Cloudflare, and CDN 403 blocks.
     */
    suspend fun getHtml(
        url: String,
        referer: String? = null,
        maxBytes: Long = 2 * 1024 * 1024
    ): HttpResponse = withContext(Dispatchers.IO) {
        // Attempt 1: Standard OkHttp GET with mobile stealth headers
        val res1 = executeOkHttpGet(url, referer, maxBytes, isDesktop = false)
        if (res1.isSuccessful && !res1.bodyString.isNullOrBlank()) {
            return@withContext res1
        }

        // Attempt 2: OkHttp GET with Desktop Chrome headers
        val resDesktop = executeOkHttpGet(url, referer, maxBytes, isDesktop = true)
        if (resDesktop.isSuccessful && !resDesktop.bodyString.isNullOrBlank()) {
            return@withContext resDesktop
        }

        // Attempt 3: If domain didn't have 'www.', try 'www.' (or vice-versa)
        val alternateUrl = getAlternateWwwUrl(url)
        if (!alternateUrl.isNullOrBlank() && alternateUrl != url) {
            val resAlt = executeOkHttpGet(alternateUrl, referer, maxBytes, isDesktop = true)
            if (resAlt.isSuccessful && !resAlt.bodyString.isNullOrBlank()) {
                return@withContext resAlt
            }
        }

        // Attempt 4: Direct Jsoup HTTPURLConnection fallback (different TLS/cipher stack than OkHttp)
        val jsoupRes = executeJsoupFallback(url, maxBytes)
        if (jsoupRes != null && jsoupRes.isSuccessful && !jsoupRes.bodyString.isNullOrBlank()) {
            return@withContext jsoupRes
        }

        // Return best response or last failure
        resDesktop.takeIf { it.statusCode > 0 } ?: res1
    }

    private fun executeOkHttpGet(
        url: String,
        referer: String?,
        maxBytes: Long,
        isDesktop: Boolean
    ): HttpResponse {
        val reqBuilder = Request.Builder().url(url)
        if (!referer.isNullOrBlank()) {
            reqBuilder.header("Referer", referer)
        }

        if (isDesktop) {
            reqBuilder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            reqBuilder.header("Sec-Ch-Ua-Platform", "\"Windows\"")
            reqBuilder.header("Sec-Ch-Ua-Mobile", "?0")
            reqBuilder.header("Sec-Ch-Ua", "\"Chromium\";v=\"131\", \"Google Chrome\";v=\"131\", \"Not_A Brand\";v=\"24\"")
            reqBuilder.header("Sec-Fetch-Dest", "document")
            reqBuilder.header("Sec-Fetch-Mode", "navigate")
            reqBuilder.header("Sec-Fetch-Site", "none")
            reqBuilder.header("Sec-Fetch-User", "?1")
            reqBuilder.header("Upgrade-Insecure-Requests", "1")
        }

        return try {
            client.newCall(reqBuilder.build()).execute().use { response ->
                val code = response.code
                val contentType = response.header("Content-Type")?.lowercase(Locale.ROOT) ?: ""
                val finalUrl = response.request.url.toString()

                if (!response.isSuccessful) {
                    return HttpResponse(
                        isSuccessful = false,
                        statusCode = code,
                        contentType = contentType,
                        finalUrl = finalUrl,
                        errorMessage = "Server returned HTTP status $code"
                    )
                }

                val body = response.body
                val responseString = if (body != null) {
                    val source = body.source()
                    source.request(maxBytes)
                    val buffer = source.buffer
                    val toRead = minOf(buffer.size, maxBytes)
                    buffer.clone().readUtf8(toRead)
                } else {
                    ""
                }

                HttpResponse(
                    isSuccessful = true,
                    statusCode = code,
                    contentType = contentType,
                    finalUrl = finalUrl,
                    bodyString = responseString
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "executeOkHttpGet failed for $url (desktop=$isDesktop): ${e.message}")
            HttpResponse(
                isSuccessful = false,
                statusCode = -1,
                contentType = "",
                finalUrl = url,
                errorMessage = e.message ?: "Network request failed"
            )
        }
    }

    private fun getAlternateWwwUrl(url: String): String? {
        return try {
            val uri = java.net.URI(url)
            val host = uri.host ?: return null
            val newHost = if (host.startsWith("www.", ignoreCase = true)) {
                host.substring(4)
            } else {
                "www.$host"
            }
            java.net.URI(uri.scheme ?: "https", uri.userInfo, newHost, uri.port, uri.path, uri.query, uri.fragment).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun executeJsoupFallback(url: String, maxBytes: Long): HttpResponse? {
        return try {
            val conn = org.jsoup.Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .timeout(12000)
                .maxBodySize(maxBytes.toInt())
                .execute()

            val statusCode = conn.statusCode()
            if (statusCode in 200..299) {
                HttpResponse(
                    isSuccessful = true,
                    statusCode = statusCode,
                    contentType = conn.contentType() ?: "text/html",
                    finalUrl = conn.url().toString(),
                    bodyString = conn.body()
                )
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "executeJsoupFallback failed for $url: ${e.message}")
            null
        }
    }

    /**
     * Generates a deterministic file identifier from URL and file name to persist partial downloads across retries.
     */
    fun generateDeterministicId(mediaUrl: String, fileName: String): String {
        val hash = runCatching {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(mediaUrl.toByteArray(Charsets.UTF_8))
            bytes.take(8).joinToString("") { "%02x".format(it) }
        }.getOrDefault(Integer.toHexString(mediaUrl.hashCode()))
        val base = fileName.substringBeforeLast('.').replace(Regex("[^a-zA-Z0-9_-]"), "_").take(32)
        val ext = fileName.substringAfterLast('.', "")
        return if (ext.isNotEmpty()) "${base}_$hash.$ext" else "${base}_$hash"
    }

    /**
     * Resolves and ensures existence of the target storage directory (public Downloads or app external storage).
     */
    fun getTargetStorageDir(context: Context): File {
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val emreGalleryDir = File(publicDownloads, "Emre Gallery")
        if (!emreGalleryDir.exists()) {
            emreGalleryDir.mkdirs()
        }
        return if (emreGalleryDir.exists() && emreGalleryDir.canWrite()) {
            emreGalleryDir
        } else {
            val appExternal = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            val fallbackFolder = File(appExternal ?: context.filesDir, "Emre Gallery")
            if (!fallbackFolder.exists()) fallbackFolder.mkdirs()
            fallbackFolder
        }
    }

    /**
     * Downloads an image, video, audio file, or stream to storage with resumable Range support.
     */
    suspend fun downloadMediaFileDetailed(
        context: Context,
        mediaUrl: String,
        fileName: String,
        referer: String? = null,
        downloadId: String? = null,
        headers: Map<String, String>? = null,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): DownloadResult = withContext(Dispatchers.IO) {
        // Handle Base64 inline data URIs directly without network calls
        if (mediaUrl.startsWith("data:image/", ignoreCase = true)) {
            return@withContext downloadDataUriMedia(context, mediaUrl, fileName, downloadId)
        }

        val targetDir = getTargetStorageDir(context)

        val sanitizedName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val deterministicBase = if (!downloadId.isNullOrBlank()) {
            val cleanId = downloadId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val ext = sanitizedName.substringAfterLast('.', "")
            if (ext.isNotEmpty() && !cleanId.endsWith(".$ext", ignoreCase = true)) {
                "${cleanId}.$ext"
            } else {
                cleanId
            }
        } else {
            generateDeterministicId(mediaUrl, sanitizedName)
        }

        val finalFile = File(targetDir, deterministicBase)
        val tempFile = File(targetDir, "$deterministicBase.downloading")
        val metaFile = File(targetDir, "$deterministicBase.meta")

        var savedEtag: String? = null
        var savedLastModified: String? = null

        if (metaFile.exists()) {
            runCatching {
                metaFile.readLines().forEach { line ->
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) {
                        when (parts[0].trim().lowercase(Locale.ROOT)) {
                            "etag" -> savedEtag = parts[1].trim().takeIf { it.isNotEmpty() }
                            "last_modified" -> savedLastModified = parts[1].trim().takeIf { it.isNotEmpty() }
                        }
                    }
                }
            }
        }

        var existingBytes = if (tempFile.exists()) tempFile.length() else 0L

        val reqBuilder = Request.Builder()
            .url(mediaUrl)
            .header("Accept", "*/*")

        if (!referer.isNullOrBlank()) {
            reqBuilder.header("Referer", referer)
        }

        headers?.forEach { (k, v) ->
            if (!k.equals("Range", ignoreCase = true) && !k.equals("If-Range", ignoreCase = true)) {
                reqBuilder.header(k, v)
            }
        }

        // Enable resumable downloads via HTTP Range header if partial data exists
        if (existingBytes > 0L) {
            reqBuilder.header("Range", "bytes=$existingBytes-")
            val etag = savedEtag
            val lastMod = savedLastModified
            if (!etag.isNullOrBlank()) {
                reqBuilder.header("If-Range", etag)
            } else if (!lastMod.isNullOrBlank()) {
                reqBuilder.header("If-Range", lastMod)
            }
        }

        try {
            var response = client.newCall(reqBuilder.build()).execute()

            // Handle 416 Range Not Satisfiable:
            if (response.code == 416) {
                val cr = response.header("Content-Range")
                val totalLength = cr?.substringAfterLast('/', "")?.toLongOrNull()
                if (totalLength != null && totalLength > 0L && existingBytes == totalLength && tempFile.exists() && tempFile.length() == totalLength) {
                    response.close()
                    val commitRes = validateAndCommitDownloadedFile(
                        context = context,
                        mediaUrl = mediaUrl,
                        fileName = fileName,
                        tempFile = tempFile,
                        metaFile = metaFile,
                        finalFile = finalFile,
                        code = 200,
                        totalBytes = totalLength,
                        mimeType = null,
                        isResumed = true
                    )
                    return@withContext commitRes
                } else {
                    response.close()
                    tempFile.delete()
                    metaFile.delete()
                    existingBytes = 0L
                    val cleanRequest = Request.Builder()
                        .url(mediaUrl)
                        .header("Accept", "*/*")
                        .apply {
                            if (!referer.isNullOrBlank()) header("Referer", referer)
                            headers?.forEach { (k, v) ->
                                if (!k.equals("Range", ignoreCase = true) && !k.equals("If-Range", ignoreCase = true)) {
                                    header(k, v)
                                }
                            }
                        }
                        .build()
                    response = client.newCall(cleanRequest).execute()
                }
            }

            var activeResponse = response
            var shouldRestartFromZero = false

            // Check Content-Range start match before entering processing
            if (activeResponse.code == 206 && existingBytes > 0L) {
                val cr = activeResponse.header("Content-Range")
                if (!cr.isNullOrBlank()) {
                    val rangeRegex = Regex("""bytes\s+(\d+)-\d+/.*""", RegexOption.IGNORE_CASE)
                    val match = rangeRegex.find(cr)
                    val startByte = match?.groupValues?.get(1)?.toLongOrNull()
                    if (startByte != null && startByte != existingBytes) {
                        Log.w(TAG, "Content-Range start mismatch: expected $existingBytes, got $startByte. Restarting from byte 0.")
                        shouldRestartFromZero = true
                    }
                }
            }

            if (shouldRestartFromZero) {
                activeResponse.close()
                tempFile.delete()
                metaFile.delete()
                existingBytes = 0L
                val cleanRequest = Request.Builder()
                    .url(mediaUrl)
                    .header("Accept", "*/*")
                    .apply {
                        if (!referer.isNullOrBlank()) header("Referer", referer)
                        headers?.forEach { (k, v) ->
                            if (!k.equals("Range", ignoreCase = true) && !k.equals("If-Range", ignoreCase = true)) {
                                header(k, v)
                            }
                        }
                    }
                    .build()
                activeResponse = client.newCall(cleanRequest).execute()
            }

            activeResponse.use { resp ->
                val code = resp.code
                val isPartial = code == 206
                val isSuccess = code in 200..299

                if (!isSuccess) {
                    val err = "Server returned HTTP status $code"
                    Log.w(TAG, "Download failed HTTP $code for $mediaUrl")
                    val retryAfterHeader = resp.header("Retry-After")
                    val retryAfterSec = parseRetryAfterHeader(retryAfterHeader)
                    return@withContext DownloadResult(
                        file = null,
                        isSuccessful = false,
                        statusCode = code,
                        bytesDownloaded = existingBytes,
                        totalBytes = -1L,
                        errorMessage = err,
                        retryAfterSeconds = retryAfterSec
                    )
                }

                val body = resp.body ?: return@withContext DownloadResult(
                    file = null,
                    isSuccessful = false,
                    statusCode = code,
                    bytesDownloaded = existingBytes,
                    totalBytes = -1L,
                    errorMessage = "Empty response body"
                )

                val rawMime = resp.header("Content-Type")?.lowercase(Locale.ROOT)
                val mimeType = rawMime?.substringBefore(";")?.trim()

                val isHlsManifest = mediaUrl.contains(".m3u8", ignoreCase = true) || fileName.endsWith(".m3u8", ignoreCase = true)
                val isDashManifest = mediaUrl.contains(".mpd", ignoreCase = true) || fileName.endsWith(".mpd", ignoreCase = true)
                val isManifest = isHlsManifest || isDashManifest

                // 1. Unconditionally reject HTML pages and JSON error responses even for manifest URLs
                if (mimeType != null && (
                        mimeType.startsWith("text/html") ||
                        mimeType.startsWith("application/json") ||
                        (mimeType.startsWith("text/plain") && !isHlsManifest && !mediaUrl.contains("playlist", ignoreCase = true))
                    )
                ) {
                    tempFile.delete()
                    metaFile.delete()
                    Log.w(TAG, "Download rejected for $mediaUrl: server returned Content-Type $mimeType")
                    return@withContext DownloadResult(
                        file = null,
                        isSuccessful = false,
                        statusCode = code,
                        bytesDownloaded = 0L,
                        totalBytes = -1L,
                        errorMessage = "Server returned $mimeType webpage or error response instead of media content"
                    )
                }

                // 2. Reject streaming manifest URLs (.m3u8/.mpd) from direct OkHttp file commits
                if (isManifest) {
                    tempFile.delete()
                    metaFile.delete()
                    return@withContext DownloadResult(
                        file = null,
                        isSuccessful = false,
                        statusCode = code,
                        bytesDownloaded = 0L,
                        totalBytes = -1L,
                        errorMessage = "Streaming manifests (.m3u8/.mpd) must be downloaded and remuxed via stream engine"
                    )
                }

                val contentRange = resp.header("Content-Range")
                val append = if (isPartial && existingBytes > 0L) {
                    true
                } else {
                    // Server returned HTTP 200 (ignored Range request) or restarted cleanly:
                    if (existingBytes > 0L) {
                        tempFile.delete()
                        existingBytes = 0L
                    }
                    false
                }

                // Store or update ETag / Last-Modified metadata for next resume attempt
                val currentEtag = resp.header("ETag")
                val currentLastModified = resp.header("Last-Modified")
                if (!currentEtag.isNullOrBlank() || !currentLastModified.isNullOrBlank()) {
                    runCatching {
                        metaFile.writeText("etag=${currentEtag.orEmpty()}\nlast_modified=${currentLastModified.orEmpty()}")
                    }
                }

                val contentLength = body.contentLength()
                val totalBytes = if (isPartial) {
                    val rangeTotal = contentRange?.substringAfterLast('/', "")?.toLongOrNull()
                    if (rangeTotal != null && rangeTotal > 0L) {
                        rangeTotal
                    } else if (contentLength > 0L) {
                        existingBytes + contentLength
                    } else {
                        -1L
                    }
                } else {
                    if (contentLength > 0L) contentLength else -1L
                }

                var downloaded = if (append) existingBytes else 0L

                body.byteStream().use { input: InputStream ->
                    FileOutputStream(tempFile, append).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            onProgress?.invoke(downloaded, totalBytes)
                        }
                        output.flush()
                    }
                }

                // Verify actual downloaded size before committing the file
                if (totalBytes > 0L && tempFile.length() != totalBytes) {
                    Log.w(TAG, "Download incomplete: expected $totalBytes bytes, got ${tempFile.length()}")
                    return@withContext DownloadResult(
                        file = null,
                        isSuccessful = false,
                        statusCode = code,
                        bytesDownloaded = tempFile.length(),
                        totalBytes = totalBytes,
                        errorMessage = "Download interrupted or size mismatch ($downloaded of $totalBytes bytes)"
                    )
                }

                return@withContext validateAndCommitDownloadedFile(
                    context = context,
                    mediaUrl = mediaUrl,
                    fileName = fileName,
                    tempFile = tempFile,
                    metaFile = metaFile,
                    finalFile = finalFile,
                    code = code,
                    totalBytes = totalBytes,
                    mimeType = mimeType,
                    isResumed = append
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download exception for $mediaUrl", e)
            DownloadResult(
                file = null,
                isSuccessful = false,
                statusCode = -1,
                bytesDownloaded = existingBytes,
                totalBytes = -1L,
                errorMessage = e.message ?: "Network error during download"
            )
        }
    }

    private fun validateAndCommitDownloadedFile(
        context: Context,
        mediaUrl: String,
        fileName: String,
        tempFile: File,
        metaFile: File,
        finalFile: File,
        code: Int,
        totalBytes: Long,
        mimeType: String?,
        isResumed: Boolean
    ): DownloadResult {
        if (!tempFile.exists() || tempFile.length() <= 0L) {
            tempFile.delete()
            metaFile.delete()
            return DownloadResult(
                file = null,
                isSuccessful = false,
                statusCode = code,
                bytesDownloaded = 0L,
                totalBytes = totalBytes,
                errorMessage = "Downloaded file was empty or could not be committed"
            )
        }

        val isHlsManifest = mediaUrl.contains(".m3u8", ignoreCase = true) || fileName.endsWith(".m3u8", ignoreCase = true)
        val isDashManifest = mediaUrl.contains(".mpd", ignoreCase = true) || fileName.endsWith(".mpd", ignoreCase = true)

        // Check if file content begins with HTML tags, Cloudflare/anti-bot challenge, or JSON error object
        val head = ByteArray(1024)
        val headRead = runCatching {
            FileInputStream(tempFile).use { fis -> fis.read(head) }
        }.getOrDefault(-1)

        if (headRead > 0) {
            val headStr = String(head, 0, headRead, Charsets.UTF_8).lowercase(Locale.ROOT)
            val isHtmlPayload = headStr.contains("<!doctype html") || headStr.contains("<html") ||
                    headStr.contains("<head") || headStr.contains("<body") || headStr.contains("<title") ||
                    headStr.contains("cloudflare") || headStr.contains("ray id:") ||
                    headStr.contains("access denied") || headStr.contains("just a moment...") ||
                    headStr.contains("ddos protection") || headStr.contains("challenge-running") ||
                    headStr.contains("403 forbidden") || headStr.contains("404 not found") ||
                    headStr.contains("502 bad gateway") || headStr.contains("503 service")

            if (isHtmlPayload) {
                Log.w(TAG, "Downloaded file for $mediaUrl contains HTML webpage or error text, deleting: ${tempFile.name}")
                tempFile.delete()
                metaFile.delete()
                return DownloadResult(
                    file = null,
                    isSuccessful = false,
                    statusCode = code,
                    bytesDownloaded = 0L,
                    totalBytes = -1L,
                    errorMessage = "Downloaded file contains HTML webpage content instead of media"
                )
            }

            val trimmedHead = headStr.trimStart()
            if ((trimmedHead.startsWith("{") || trimmedHead.startsWith("[")) &&
                (trimmedHead.contains("\"error\"") || trimmedHead.contains("\"message\"") ||
                 trimmedHead.contains("\"status\"") || trimmedHead.contains("\"code\"") ||
                 trimmedHead.contains("\"forbidden\"") || trimmedHead.contains("\"unauthorized\"") ||
                 trimmedHead.contains("\"success\":false"))
            ) {
                Log.w(TAG, "Downloaded file for $mediaUrl contains JSON error, deleting: ${tempFile.name}")
                tempFile.delete()
                metaFile.delete()
                return DownloadResult(
                    file = null,
                    isSuccessful = false,
                    statusCode = code,
                    bytesDownloaded = 0L,
                    totalBytes = -1L,
                    errorMessage = "Downloaded file contains JSON error response instead of media"
                )
            }

            // Reject raw streaming playlists (.m3u8 or .mpd) from being saved as finished media files
            if (isHlsManifest || isDashManifest || headStr.contains("#extm3u") || headStr.contains("<mpd") || headStr.contains("urn:mpeg:dash:schema")) {
                Log.w(TAG, "Streaming playlist text (.m3u8 / .mpd) cannot be saved as finished media: ${tempFile.name}")
                tempFile.delete()
                metaFile.delete()
                return DownloadResult(
                    file = null,
                    isSuccessful = false,
                    statusCode = code,
                    bytesDownloaded = 0L,
                    totalBytes = -1L,
                    errorMessage = "Cannot save streaming playlist text (.m3u8/.mpd) as finished media file"
                )
            }
        }

        // For raster images only: verify BitmapFactory can decode bounds.
        // Do NOT attempt BitmapFactory decoding on AVI, FLV, 3GP, TS, AAC, FLAC, OPUS, WMA or other non-raster formats.
        val isRasterImage = when {
            mimeType != null -> mimeType == "image/jpeg" || mimeType == "image/jpg" ||
                    mimeType == "image/png" || mimeType == "image/webp" ||
                    mimeType == "image/gif" || mimeType == "image/bmp" || mimeType == "image/x-ms-bmp"
            else -> fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) ||
                    fileName.endsWith(".png", true) || fileName.endsWith(".webp", true) ||
                    fileName.endsWith(".gif", true) || fileName.endsWith(".bmp", true)
        }

        if (isRasterImage) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tempFile.absolutePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                Log.w(TAG, "Downloaded image could not be decoded: ${tempFile.name}")
                tempFile.delete()
                metaFile.delete()
                return DownloadResult(
                    file = null,
                    isSuccessful = false,
                    statusCode = code,
                    bytesDownloaded = 0L,
                    totalBytes = -1L,
                    errorMessage = "Downloaded file is corrupt or invalid raster image format"
                )
            }
        }

        // Clean up metadata file upon successful completion
        metaFile.delete()

        val moved = if (tempFile.renameTo(finalFile)) {
            true
        } else {
            tempFile.copyTo(finalFile, overwrite = true)
            tempFile.delete()
            true
        }

        if (moved && finalFile.exists() && finalFile.length() > 0L) {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(finalFile.absolutePath),
                arrayOf(mimeType),
                null
            )
            return DownloadResult(
                file = finalFile,
                isSuccessful = true,
                statusCode = code,
                bytesDownloaded = finalFile.length(),
                totalBytes = totalBytes,
                mimeType = mimeType,
                isResumed = isResumed
            )
        }

        tempFile.delete()
        metaFile.delete()
        return DownloadResult(
            file = null,
            isSuccessful = false,
            statusCode = code,
            bytesDownloaded = 0L,
            totalBytes = totalBytes,
            errorMessage = "Downloaded file was empty or could not be committed"
        )
    }

    /**
     * Decodes and stores high-resolution Base64 inline data URIs directly to persistent media storage.
     */
    private fun downloadDataUriMedia(
        context: Context,
        dataUri: String,
        fileName: String,
        downloadId: String?
    ): DownloadResult {
        return try {
            val targetDir = getTargetStorageDir(context)
            val dataInfo = MediaClassifier.parseDataUriInfo(dataUri)
            val ext = dataInfo?.format?.lowercase(Locale.ROOT) ?: "png"
            val mimeType = dataInfo?.mimeType ?: "image/png"
            val cleanName = if (!downloadId.isNullOrBlank()) {
                val base = downloadId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                if (base.endsWith(".$ext", ignoreCase = true)) base else "$base.$ext"
            } else {
                val sanitized = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                if (sanitized.endsWith(".$ext", ignoreCase = true)) sanitized else "$sanitized.$ext"
            }

            val finalFile = File(targetDir, cleanName)
            val base64Content = dataUri.substringAfter(";base64,", "")
            if (base64Content.isBlank()) {
                return DownloadResult(
                    file = null,
                    isSuccessful = false,
                    statusCode = 400,
                    bytesDownloaded = 0L,
                    totalBytes = -1L,
                    errorMessage = "Invalid base64 payload in data URI"
                )
            }

            val decodedBytes = android.util.Base64.decode(base64Content, android.util.Base64.DEFAULT)
            if (decodedBytes == null || decodedBytes.isEmpty()) {
                return DownloadResult(
                    file = null,
                    isSuccessful = false,
                    statusCode = 400,
                    bytesDownloaded = 0L,
                    totalBytes = -1L,
                    errorMessage = "Failed to decode base64 data"
                )
            }

            // Validate raster image bounds if raster
            if (mimeType != "image/svg+xml") {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size, opts)
                if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                    return DownloadResult(
                        file = null,
                        isSuccessful = false,
                        statusCode = 422,
                        bytesDownloaded = 0L,
                        totalBytes = -1L,
                        errorMessage = "Data URI raster image is invalid or corrupt"
                    )
                }
            }

            finalFile.writeBytes(decodedBytes)
            MediaScannerConnection.scanFile(
                context,
                arrayOf(finalFile.absolutePath),
                arrayOf(mimeType),
                null
            )

            DownloadResult(
                file = finalFile,
                isSuccessful = true,
                statusCode = 200,
                bytesDownloaded = finalFile.length(),
                totalBytes = finalFile.length(),
                mimeType = mimeType,
                isResumed = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download data URI", e)
            DownloadResult(
                file = null,
                isSuccessful = false,
                statusCode = 500,
                bytesDownloaded = 0L,
                totalBytes = -1L,
                errorMessage = e.message ?: "Failed to write data URI"
            )
        }
    }

    /**
     * Backward-compatible convenience method returning File?
     */
    suspend fun downloadMediaFile(
        context: Context,
        mediaUrl: String,
        fileName: String,
        referer: String? = null,
        downloadId: String? = null,
        headers: Map<String, String>? = null,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): File? {
        val res = downloadMediaFileDetailed(context, mediaUrl, fileName, referer, downloadId, headers, onProgress)
        return res.file
    }
}
