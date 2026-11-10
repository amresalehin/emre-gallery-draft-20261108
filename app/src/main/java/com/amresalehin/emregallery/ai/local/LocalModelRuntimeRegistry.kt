package com.amresalehin.emregallery.ai.local

import android.content.Context
import java.io.File

/** Registry for model adapters, with one long-lived runtime per batch model. */
object LocalModelRuntimeRegistry {

    private val factories = mutableListOf<LocalModelRuntimeFactory>()
    private val activeRuntimes = mutableMapOf<String, LocalModelRuntime>()

    init {
        register(object : LocalModelRuntimeFactory {
            override fun isApplicable(spec: LocalModelSpec): Boolean =
                spec.architecture == ModelArchitecture.GEMMA4
            override fun createRuntime(spec: LocalModelSpec): LocalModelRuntime =
                Gemma4RuntimeAdapter(spec)
        })
        register(object : LocalModelRuntimeFactory {
            override fun isApplicable(spec: LocalModelSpec): Boolean =
                spec.architecture == ModelArchitecture.MINICPM_V46
            override fun createRuntime(spec: LocalModelSpec): LocalModelRuntime =
                MiniCpmV46RuntimeAdapter(spec)
        })

        register(object : LocalModelRuntimeFactory {
            override fun isApplicable(spec: LocalModelSpec): Boolean =
                spec.architecture == ModelArchitecture.LFM2_5
            override fun createRuntime(spec: LocalModelSpec): LocalModelRuntime =
                Lfm25VlRuntimeAdapter(spec)
        })
        register(object : LocalModelRuntimeFactory {
            override fun isApplicable(spec: LocalModelSpec): Boolean =
                spec.architecture == ModelArchitecture.QWEN3_5
            override fun createRuntime(spec: LocalModelSpec): LocalModelRuntime =
                Qwen35RuntimeAdapter(spec)
        })
    }

    @Synchronized
    fun register(factory: LocalModelRuntimeFactory) {
        if (factories.none { it === factory }) factories.add(0, factory)
    }

    @Synchronized
    fun getRuntime(spec: LocalModelSpec): LocalModelRuntime? {
        activeRuntimes[spec.id]?.let { return it }
        val factory = factories.firstOrNull { it.isApplicable(spec) } ?: return null
        return factory.createRuntime(spec).also { activeRuntimes[spec.id] = it }
    }

    @Synchronized
    fun closeRuntime(modelId: String) {
        val id = LocalModelRegistry.findSpec(modelId)?.id ?: modelId
        activeRuntimes.remove(id)?.let { runCatching { it.close() } }
    }

    @Synchronized
    fun closeAllRuntimes() {
        activeRuntimes.values.forEach { runCatching { it.close() } }
        activeRuntimes.clear()
    }

    suspend fun validateModel(context: Context, spec: LocalModelSpec, modelDir: File): SmokeTestResult {
        val factory = synchronized(this) { factories.firstOrNull { it.isApplicable(spec) } }
            ?: return SmokeTestResult.Success(
                durationMs = 0L,
                details = "No local runtime adapter registered for ${spec.displayName} (${spec.architecture.family}). Model stored as assets only."
            )
        val runtime = factory.createRuntime(spec)
        return try {
            runtime.validateGraphAndRunSmokeTest(context, modelDir)
        } finally {
            runtime.close()
        }
    }
}
