package com.amresalehin.emregallery.ai.local

import android.content.Context
import java.io.File

/**
 * Result of smoke-testing and validating a model's ONNX graph, inputs, and inference execution.
 */
sealed class SmokeTestResult {
    data class Success(
        val durationMs: Long,
        val details: String,
        val validatedTensors: List<String> = emptyList()
    ) : SmokeTestResult()

    data class Failure(
        val reason: String,
        val failedStage: String = "validation",
        val cause: Throwable? = null
    ) : SmokeTestResult()
}

/**
 * Contract for a local model execution runtime and adapter.
 */
interface LocalModelRuntime : AutoCloseable {
    val spec: LocalModelSpec
    val isReady: Boolean

    /**
     * Initializes and opens model sessions (e.g. ONNX Runtime sessions, tokenizers).
     */
    suspend fun open(context: Context, modelDir: File): Boolean

    /**
     * Validates input/output graph signatures, tensor dimensions, and executes
     * a deterministic smoke inference test.
     */
    suspend fun validateGraphAndRunSmokeTest(context: Context, modelDir: File): SmokeTestResult

    override fun close()
}

/**
 * Factory for creating runtime adapters for supported model families.
 */
interface LocalModelRuntimeFactory {
    fun isApplicable(spec: LocalModelSpec): Boolean
    fun createRuntime(spec: LocalModelSpec): LocalModelRuntime
}
