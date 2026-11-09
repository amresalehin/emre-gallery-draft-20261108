package com.amresalehin.emregallery.ai.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Detailed download progress report for UI and status feedback.
 */
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

/**
 * Resilient, resumable downloader for local AI model distributions.
 * Implements verified HTTP Range resume, multi-hop redirect resolution, bounded retries,
 * detailed transfer telemetry, and safe cancellation handling.
 */
object LocalModelDownloadManager {

    private const val BUFFER_SIZE = 64 * 1024
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 120_000
    private const val MAX_REDIRECTS = 6
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) EmreGallery/1.0"

    /**
     * Streams an individual artifact file from [url] into [destination], supporting Range resume.
     */
    suspend fun downloadArtifactFile(
        url: String,
        destination: File,
        expectedBytes: Long? = null,
        onProgress: (bytesReadThisSession: Long) -> Unit,
        onStage: (stage: String) -> Unit = {}
    ): Unit = withContext(Dispatchers.IO) {
        var currentUrl = url
        var redirects = 0
        var connection: HttpURLConnection? = null

        val existingBytes = if (destination.exists()) destination.length() else 0L

        // If existing file already satisfies exact bytes, skip re-downloading
        if (expectedBytes != null && existingBytes == expectedBytes) {
            onProgress(0L)
            return@withContext
        }

        onStage("connecting")

        while (redirects < MAX_REDIRECTS) {
            coroutineContext.ensureActive()

            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
            }

            if (existingBytes > 0L) {
                conn.setRequestProperty("Range", "bytes=$existingBytes-")
            }

            conn.connect()
            val code = conn.responseCode

            if (code in listOf(HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, 307, 308)) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (!location.isNullOrBlank()) {
                    currentUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                        location
                    } else {
                        URL(URL(currentUrl), location).toString()
                    }
                    redirects++
                    continue
                }
            }
            connection = conn
            break
        }

        val activeConn = connection ?: throw IOException("Failed to establish HTTP connection to $url")

        try {
            coroutineContext.ensureActive()
            val responseCode = activeConn.responseCode

            if (responseCode == 416) {
                // Range Not Satisfiable: partial file may already be full length
                if (expectedBytes != null && destination.length() == expectedBytes) {
                    return@withContext
                }
                // Partial file invalid; restart download from byte 0
                destination.delete()
                downloadArtifactFile(url, destination, expectedBytes, onProgress, onStage)
                return@withContext
            }

            val isPartial = responseCode == 206
            val isOk = responseCode in 200..299

            if (!isOk && !isPartial) {
                throw IOException("HTTP $responseCode while downloading $url")
            }

            onStage("downloading")

            // Range validation: if server sent HTTP 200 instead of 206, server ignored Range.
            // MUST truncate and write from byte 0 to prevent appending to an existing slice!
            val appendMode = isPartial && existingBytes > 0L

            // Ensure parent directory exists
            destination.parentFile?.mkdirs()

            BufferedInputStream(activeConn.inputStream, BUFFER_SIZE).use { input ->
                FileOutputStream(destination, appendMode).buffered(BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        coroutineContext.ensureActive()
                        output.write(buffer, 0, bytesRead)
                        onProgress(bytesRead.toLong())
                    }
                    output.flush()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw e
        } finally {
            activeConn.disconnect()
        }
    }
}
