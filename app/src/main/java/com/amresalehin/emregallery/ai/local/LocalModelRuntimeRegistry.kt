package com.amresalehin.emregallery.ai.local

import android.content.Context
import java.io.File

/**
 * Registry mapping canonical model specs to executable runtime adapters.
 */
object LocalModelRuntimeRegistry {

    private val factories = mutableListOf<LocalModelRuntimeFactory>()

    init {
        // Register Florence-2 factory by default
        register(object : LocalModelRuntimeFactory {
            override fun isApplicable(spec: LocalModelSpec): Boolean =
                spec.architecture == ModelArchitecture.FLORENCE2 && spec.hasRuntimeAdapter

            override fun createRuntime(spec: LocalModelSpec): LocalModelRuntime =
                Florence2RuntimeAdapter(spec)
        })
    }

    @Synchronized
    fun register(factory: LocalModelRuntimeFactory) {
        if (!factories.contains(factory)) {
            factories.add(0, factory)
        }
    }

    fun getRuntime(spec: LocalModelSpec): LocalModelRuntime? {
        val factory = factories.firstOrNull { it.isApplicable(spec) } ?: return null
        return factory.createRuntime(spec)
    }

    suspend fun validateModel(context: Context, spec: LocalModelSpec, modelDir: File): SmokeTestResult {
        val runtime = getRuntime(spec) ?: return SmokeTestResult.Success(
            durationMs = 0L,
            details = "No local runtime adapter registered for ${spec.displayName} (${spec.architecture.family}). Model stored as assets only."
        )
        return try {
            runtime.validateGraphAndRunSmokeTest(context, modelDir)
        } finally {
            runtime.close()
        }
    }
}
