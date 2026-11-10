package com.amresalehin.emregallery.ai.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class ModelDownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long = 0L,
    val stage: String = "downloading",
    val currentArtifact: String = "",
    val artifactIndex: Int = 0,
    val totalArtifacts: Int = 0
) {
    val fraction: Float
        get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

object LocalModelDownloadManager {
    private const val BUFFER_SIZE = 64 * 1024
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 120_000
    private const val MAX_REDIRECTS = 8
    private const val MAX_ATTEMPTS = 3
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) EmreGallery/1.0"
    private val retryableHttpCodes = setOf(408, 425, 429, 500, 502, 503, 504)
    private data class ContentRange(val start: Long, val end: Long, val total: Long?)

    suspend fun downloadArtifactFile(
        url: String,
        destination: File,
        expectedBytes: Long? = null,
        expectedSha256: String? = null,
        expectedMd5: String? = null,
        onProgress: (bytesReadThisSession: Long) -> Unit,
        onStage: (stage: String) -> Unit = {}
    ): Unit = withContext(Dispatchers.IO) {
        require(url.isNotBlank()) { "Download URL cannot be blank." }
        require(expectedBytes == null || expectedBytes > 0L) { "expectedBytes must be positive." }
        destination.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
                throw IOException("Unable to create download directory: " + parent.absolutePath)
            }
        }

        if (destination.isFile && expectedBytes != null && destination.length() == expectedBytes) {
            val shaOk = expectedSha256.isNullOrBlank() || sha256(destination).equals(expectedSha256, ignoreCase = true)
            val md5Ok = expectedMd5.isNullOrBlank() || md5(destination).equals(expectedMd5, ignoreCase = true)
            if (shaOk && md5Ok) {
                onProgress(0L)
                return@withContext
            }
            destination.delete()
        }

        var lastFailure: IOException? = null
        onStage("connecting")
        for (attempt in 1..MAX_ATTEMPTS) {
            coroutineContext.ensureActive()
            var offset = if (destination.isFile) destination.length() else 0L
            if (expectedBytes != null && offset > expectedBytes) {
                destination.delete()
                offset = 0L
            }
            var connection: HttpURLConnection? = null
            try {
                connection = openConnection(url, offset)
                val status = connection.responseCode

                if (status == 416) {
                    val serverLength = parseUnsatisfiedRange(connection.getHeaderField("Content-Range"))
                    if (serverLength != null && destination.isFile &&
                        destination.length() == serverLength &&
                        (expectedBytes == null || serverLength == expectedBytes)
                    ) {
                        val shaOk = expectedSha256.isNullOrBlank() || sha256(destination).equals(expectedSha256, ignoreCase = true)
                        val md5Ok = expectedMd5.isNullOrBlank() || md5(destination).equals(expectedMd5, ignoreCase = true)
                        if (shaOk && md5Ok) {
                            onProgress(0L)
                            return@withContext
                        }
                    }
                    // Truncate instead of relying on delete(): some filesystems/providers can
                    // refuse deletion while retaining the stale length, which would repeat 416.
                    clearPartialFile(destination)
                    if (attempt == MAX_ATTEMPTS) throw IOException("HTTP 416: server rejected resume offset for " + url)
                    lastFailure = IOException("HTTP 416: invalid resume offset; restarting from byte zero.")
                    onStage("retrying")
                    delay(250L * attempt)
                    continue
                }
                if (status in retryableHttpCodes) {
                    lastFailure = IOException("Temporary HTTP " + status + " while downloading " + url)
                    if (attempt == MAX_ATTEMPTS) throw lastFailure!!
                    onStage("retrying")
                    delay(300L * attempt)
                    continue
                }
                if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                    throw IOException("HTTP " + status + " while downloading " + url)
                }

                val range = if (status == HttpURLConnection.HTTP_PARTIAL) {
                    parseContentRange(connection.getHeaderField("Content-Range"))
                        ?: throw IOException("HTTP 206 response is missing a valid Content-Range header.")
                } else null
                if (range != null) {
                    if (range.start != offset || range.end < range.start) {
                        destination.delete()
                        throw IOException("Invalid Content-Range start. Expected " + offset +
                            " but received " + range.start + "-" + range.end)
                    }
                    if (expectedBytes != null && range.total != null && range.total != expectedBytes) {
                        destination.delete()
                        throw IOException("Content-Range total mismatch. Expected " + expectedBytes +
                            " but server reported " + range.total)
                    }
                    val span = range.end - range.start + 1L
                    val contentLength = connection.getHeaderFieldLong("Content-Length", -1L)
                    if (contentLength >= 0L && contentLength != span) {
                        destination.delete()
                        throw IOException("Content-Length does not match Content-Range span.")
                    }
                }

                // A 200 response starts from byte zero even if a server ignored Range.
                val append = status == HttpURLConnection.HTTP_PARTIAL && offset > 0L
                onStage(if (append) "resuming" else if (offset > 0L) "restarting" else "downloading")
                var received = 0L
                BufferedInputStream(connection.inputStream, BUFFER_SIZE).use { input ->
                    FileOutputStream(destination, append).buffered(BUFFER_SIZE).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            received += count.toLong()
                            onProgress(count.toLong())
                        }
                        output.flush()
                    }
                }
                if (range != null && received != range.end - range.start + 1L) {
                    throw IOException("Truncated range response. Expected " +
                        (range.end - range.start + 1L) + " bytes, received " + received)
                }
                if (expectedBytes != null && destination.length() != expectedBytes) {
                    throw IOException("Downloaded size mismatch. Expected " + expectedBytes +
                        " bytes, received " + destination.length())
                }
                if (!expectedSha256.isNullOrBlank() && !sha256(destination).equals(expectedSha256, ignoreCase = true)) {
                    destination.delete()
                    throw IOException("SHA-256 mismatch for downloaded artifact: " + url)
                }
                if (!expectedMd5.isNullOrBlank() && !md5(destination).equals(expectedMd5, ignoreCase = true)) {
                    destination.delete()
                    throw IOException("MD5 mismatch for downloaded artifact: " + url)
                }
                onStage("complete")
                return@withContext
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                val message = failure.message.orEmpty()
                val isRetryable = message.startsWith("Temporary HTTP ") ||
                    message.startsWith("HTTP 416:") ||
                    !message.startsWith("HTTP ")
                if (!isRetryable || attempt == MAX_ATTEMPTS) throw failure
                lastFailure = failure
                onStage("retrying")
                delay(300L * attempt)
            } finally {
                connection?.disconnect()
            }
        }
        throw lastFailure ?: IOException("Download failed after " + MAX_ATTEMPTS + " attempts: " + url)
    }

    private fun openConnection(initialUrl: String, offset: Long): HttpURLConnection {
        var current = URL(initialUrl)
        var redirects = 0
        while (true) {
            if (current.protocol != "http" && current.protocol != "https") {
                throw IOException("Unsupported URL scheme: " + current.protocol)
            }
            val connection = current.openConnection() as? HttpURLConnection
                ?: throw IOException("Unsupported connection type for " + current)
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0L) connection.setRequestProperty("Range", "bytes=" + offset + "-")
                val status = connection.responseCode
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location.isNullOrBlank()) throw IOException("HTTP " + status + " redirect has no Location.")
                    if (redirects >= MAX_REDIRECTS) throw IOException("Too many redirects while downloading " + initialUrl)
                    current = URL(current, location)
                    redirects++
                    continue
                }
                return connection
            } catch (failure: Throwable) {
                connection.disconnect()
                throw failure
            }
        }
    }

    private fun clearPartialFile(file: File) {
        if (!file.exists()) return
        try {
            FileOutputStream(file, false).use { it.flush() }
        } catch (error: IOException) {
            throw IOException("Unable to clear invalid partial download at " + file.absolutePath, error)
        }
        if (file.length() != 0L) {
            throw IOException("Unable to reset invalid partial download at " + file.absolutePath)
        }
    }

    private fun parseContentRange(header: String?): ContentRange? {
        if (header.isNullOrBlank()) return null
        val match = Regex("^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$", RegexOption.IGNORE_CASE)
            .matchEntire(header.trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].takeIf { it != "*" }?.toLongOrNull()
        if (total != null && (total <= 0L || end >= total)) return null
        return ContentRange(start, end, total)
    }

    private fun parseUnsatisfiedRange(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        return Regex("^bytes\\s+\\*/(\\d+)$", RegexOption.IGNORE_CASE)
            .matchEntire(header.trim())?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
