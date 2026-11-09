package com.amresalehin.emregallery.data

import java.util.UUID

/**
 * Represents a configured Cloud AI Provider (e.g. OpenAI, Groq, Ollama, OpenRouter, Anthropic).
 */
data class CloudAiProvider(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val modelId: String,
    val isActive: Boolean = false,
    val status: CloudProviderStatus = CloudProviderStatus.UNCONFIGURED,
    val statusMessage: String? = null,
    val lastCheckedTimestamp: Long = 0L
)

enum class CloudProviderStatus {
    UNCONFIGURED,
    CHECKING,
    CONNECTED,
    ERROR
}

data class CloudTestResult(
    val status: CloudProviderStatus,
    val message: String,
    val latencyMs: Long = 0L
)

/**
 * Represents the comprehensive installation, verification, and runtime readiness state
 * of local on-device AI models.
 */
enum class FlorenceStatus {
    NOT_INSTALLED,
    QUEUED,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    LOADING,
    READY,
    ASSETS_DOWNLOADED_RUNTIME_UNAVAILABLE,
    INSTALLED, // Backwards-compatible alias for READY
    ERROR,
    UNINSTALLING;

    val isUsable: Boolean get() = this == READY || this == INSTALLED
}

data class FlorenceModelState(
    val status: FlorenceStatus = FlorenceStatus.NOT_INSTALLED,
    val progress: Float = 0f,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long = 0L,
    val currentStage: String? = null,
    val currentArtifact: String? = null,
    val errorMessage: String? = null,
    val installedSizeBytes: Long = 0L,
    val installedPath: String? = null,
    val lastUpdated: Long = 0L,
    val validatedAt: Long = 0L,
    val runtimeReady: Boolean = false,
    val statusDetail: String? = null
)
