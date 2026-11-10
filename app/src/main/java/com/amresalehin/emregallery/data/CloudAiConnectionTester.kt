package com.amresalehin.emregallery.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object CloudAiConnectionTester {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    suspend fun testConnection(provider: CloudAiProvider): CloudTestResult = withContext(Dispatchers.IO) {
        val startNs = System.nanoTime()
        val rawBase = provider.baseUrl.trim()
        if (rawBase.isBlank()) {
            return@withContext CloudTestResult(
                status = CloudProviderStatus.ERROR,
                message = "Base URL cannot be blank",
                latencyMs = 0L
            )
        }

        val normalizedBase = when {
            rawBase.startsWith("http://", ignoreCase = true) || rawBase.startsWith("https://", ignoreCase = true) -> rawBase
            else -> "https://$rawBase"
        }.trimEnd('/')

        // Typical test endpoints: /models or /v1/models or root endpoint
        val testUrl = if (normalizedBase.endsWith("/v1", ignoreCase = true)) {
            "$normalizedBase/models"
        } else {
            "$normalizedBase/v1/models"
        }

        try {
            val requestBuilder = Request.Builder()
                .url(testUrl)
                .get()

            if (provider.apiKey.isNotBlank()) {
                requestBuilder.header("Authorization", "Bearer ${provider.apiKey.trim()}")
            }

            val response = try {
                httpClient.newCall(requestBuilder.build()).execute()
            } catch (e: Exception) {
                // If /models failed, fallback test to root url (common for Ollama or custom gateways)
                val fallbackReq = Request.Builder()
                    .url(normalizedBase)
                    .get()
                if (provider.apiKey.isNotBlank()) {
                    fallbackReq.header("Authorization", "Bearer ${provider.apiKey.trim()}")
                }
                httpClient.newCall(fallbackReq.build()).execute()
            }

            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs).coerceAtLeast(1L)
            val code = response.code
            response.close()

            when (code) {
                in 200..299 -> {
                    CloudTestResult(
                        status = CloudProviderStatus.CONNECTED,
                        message = "Connected (HTTP $code • ${elapsedMs}ms)",
                        latencyMs = elapsedMs
                    )
                }
                401, 403 -> {
                    CloudTestResult(
                        status = CloudProviderStatus.ERROR,
                        message = "Unauthorized (HTTP $code) — check API key",
                        latencyMs = elapsedMs
                    )
                }
                404 -> {
                    // Endpoint reached but /models not found. If response is from host, the server is still alive
                    CloudTestResult(
                        status = CloudProviderStatus.CONNECTED,
                        message = "Host reachable (HTTP 404 • ${elapsedMs}ms)",
                        latencyMs = elapsedMs
                    )
                }
                else -> {
                    CloudTestResult(
                        status = CloudProviderStatus.ERROR,
                        message = "Server response: HTTP $code (${elapsedMs}ms)",
                        latencyMs = elapsedMs
                    )
                }
            }
        } catch (e: Exception) {
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs).coerceAtLeast(1L)
            val errorMsg = when {
                e is java.net.UnknownHostException -> "Unknown host. Check Base URL."
                e is java.net.SocketTimeoutException -> "Connection timed out."
                e is java.net.ConnectException -> "Connection refused. Is server running?"
                else -> e.localizedMessage ?: "Connection failed"
            }
            CloudTestResult(
                status = CloudProviderStatus.ERROR,
                message = errorMsg,
                latencyMs = elapsedMs
            )
        }
    }
}
