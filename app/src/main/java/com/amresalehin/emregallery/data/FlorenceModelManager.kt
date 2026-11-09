package com.amresalehin.emregallery.data

import android.content.Context
import com.amresalehin.emregallery.ai.local.LocalModelInstaller
import com.amresalehin.emregallery.ai.local.LocalModelRegistry
import com.amresalehin.emregallery.ai.local.LocalModelStateStore
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Facade managing local AI models, bridging the UI and background workers
 * to the robust LocalModelRegistry, LocalModelInstaller, and LocalModelStateStore subsystems.
 */
object FlorenceModelManager {

    const val MODEL_NAME = "Florence-2"
    const val MODEL_VERSION = "Florence-2-base"

    data class ModelArtifact(
        val fileName: String,
        val url: String,
        val estimatedSize: Long,
        val exactBytes: Long? = null,
        val role: String = "ONNX_GRAPH",
        val externalDataFor: String? = null
    )

    data class LocalModelSpec(
        val id: String,
        val canonicalId: String = id,
        val displayName: String,
        val upstreamRepo: String = "",
        val upstreamRevision: String = "",
        val description: String,
        val subdir: String,
        val manifestFileName: String,
        val testTagPrefix: String,
        val hasRuntimeAdapter: Boolean = false,
        val taskType: String = "Vision-Language",
        val artifacts: List<ModelArtifact>
    )

    val modelState: StateFlow<FlorenceModelState>
        get() = LocalModelStateStore.florenceState

    val allModelStates: StateFlow<Map<String, FlorenceModelState>>
        get() = LocalModelStateStore.allModelStates

    val SUPPORTED_MODELS: List<LocalModelSpec> = LocalModelRegistry.ALL_MODELS.map { reg ->
        LocalModelSpec(
            id = reg.id,
            canonicalId = reg.canonicalId,
            displayName = reg.displayName,
            upstreamRepo = reg.upstreamRepo,
            upstreamRevision = reg.upstreamRevision,
            description = reg.description,
            subdir = reg.subdir,
            manifestFileName = reg.manifestFileName,
            testTagPrefix = reg.testTagPrefix,
            hasRuntimeAdapter = reg.hasRuntimeAdapter,
            taskType = reg.taskType.label,
            artifacts = reg.artifacts.map { art ->
                ModelArtifact(
                    fileName = art.relativePath,
                    url = art.sourceUrl,
                    estimatedSize = art.exactBytes ?: art.expectedMinBytes,
                    exactBytes = art.exactBytes,
                    role = art.role.name,
                    externalDataFor = art.externalDataFor
                )
            }
        )
    }

    fun getSpec(modelId: String): LocalModelSpec {
        val reg = LocalModelRegistry.getSpec(modelId)
        return SUPPORTED_MODELS.firstOrNull { it.id == reg.id } ?: SUPPORTED_MODELS.first { it.id == "florence2" }
    }

    fun getModelDir(context: Context, modelId: String = "florence2"): File {
        val reg = LocalModelRegistry.getSpec(modelId)
        return LocalModelInstaller.getModelDir(context, reg)
    }

    fun isInstalled(context: Context, modelId: String = "florence2"): Boolean {
        val reg = LocalModelRegistry.getSpec(modelId)
        return LocalModelInstaller.isInstalled(context, reg)
    }

    fun getInstalledSizeBytes(context: Context, modelId: String = "florence2"): Long {
        val dir = getModelDir(context, modelId)
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.map { it.length() }.sum()
    }

    fun refreshAllStates(context: Context): Map<String, FlorenceModelState> {
        return LocalModelStateStore.refreshAll(context)
    }

    fun refreshState(context: Context): FlorenceModelState {
        val all = refreshAllStates(context)
        return all["florence2"] ?: FlorenceModelState()
    }

    fun getModelState(modelId: String): FlorenceModelState {
        return LocalModelStateStore.getState(modelId)
    }

    suspend fun downloadAndInstallModel(
        context: Context,
        modelId: String,
        onProgress: ((progress: Float, downloaded: Long, total: Long) -> Unit)? = null
    ): Boolean {
        return LocalModelStateStore.downloadAndInstall(context, modelId, onProgress)
    }

    suspend fun downloadAndInstall(
        context: Context,
        onProgress: ((progress: Float, downloaded: Long, total: Long) -> Unit)? = null
    ): Boolean = downloadAndInstallModel(context, "florence2", onProgress)

    suspend fun uninstallModel(context: Context, modelId: String): Boolean {
        return LocalModelStateStore.uninstall(context, modelId)
    }

    suspend fun uninstall(context: Context): Boolean = uninstallModel(context, "florence2")

    fun cancelDownload(context: Context, modelId: String = "florence2") {
        LocalModelStateStore.cancelDownload(context, modelId)
    }
}
