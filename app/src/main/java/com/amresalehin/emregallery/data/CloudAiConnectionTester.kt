package com.amresalehin.emregallery.data

import com.amresalehin.emregallery.ai.tagging.AiImageTagger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Base64
import java.util.concurrent.TimeUnit

object CloudAiConnectionTester {

    // A tiny 8x8 JPEG. This is a real image payload, not merely a GET/host-reachability check.
    private val smokeTestImage: ByteArray by lazy {
        Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDABALDA4MChAODQ4SERATGCgaGBYWGDEjJR0oOjM9PDkzODdASFxOQERXRTc4UG1RV19iZ2hnPk1xeXBkeFxlZ2P/2wBDARESEhgVGC8aGi9jQjhCY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2P/wAARCAAIAAgDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDs6KKKAP/Z"
        )
    }

    suspend fun testConnection(provider: CloudAiProvider): CloudTestResult = withContext(Dispatchers.IO) {
        val startNs = System.nanoTime()
        if (provider.baseUrl.isBlank()) {
            return@withContext CloudTestResult(
                status = CloudProviderStatus.ERROR,
                message = "Base URL cannot be blank",
                latencyMs = 0L
            )
        }

        try {
            val tags = AiImageTagger.tagWithCloudVisionDirect(provider, smokeTestImage)
            if (tags.isEmpty()) {
                throw IOException("The model accepted the request but returned no usable image tags.")
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs).coerceAtLeast(1L)
            CloudTestResult(
                status = CloudProviderStatus.CONNECTED,
                message = "Vision ready • ${elapsedMs}ms • ${tags.size} tags",
                latencyMs = elapsedMs
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs).coerceAtLeast(1L)
            val message = when (error) {
                is UnknownHostException -> "Unknown host. Check the provider Base URL."
                is SocketTimeoutException -> "Cloud vision test timed out. Check connectivity or try again."
                is ConnectException -> "Connection refused. Check the server address and whether the service is running."
                else -> error.message ?: "Cloud vision test failed."
            }
            CloudTestResult(
                status = CloudProviderStatus.ERROR,
                message = message,
                latencyMs = elapsedMs
            )
        }
    }
}
