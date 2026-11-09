package com.amresalehin.emregallery.face

import android.content.Context

/**
 * Factory for creating and managing models of the Ente-Style face engine.
 * Registered deterministically with FaceEngineRegistry.
 */
object EnteFaceEngineFactory : FaceEngineFactory {
    override val engineId: String = EnteFaceConstants.ENGINE_ID
    override val displayName: String = EnteFaceConstants.DISPLAY_NAME
    override val modelVersion: String = EnteFaceConstants.MODEL_VERSION
    override val embeddingDimension: Int = EnteFaceConstants.EMBEDDING_DIMENSION
    override val isExperimental: Boolean = false
    override val clusteringStrategy: FaceClusteringStrategy = EnteFaceClusteringStrategy

    override fun isInstalled(context: Context): Boolean =
        EnteFaceModelManager.isInstalled(context)

    override fun create(context: Context, detectionThreshold: Float): FaceEngine =
        EnteFaceEngine.open(context, detectionThreshold)

    override suspend fun downloadModel(
        context: Context,
        force: Boolean,
        onProgress: (Long, Long) -> Unit,
        onStage: (String) -> Unit,
        awaitIfResumed: suspend () -> Unit,
        onDetailedProgress: (DetailedDownloadProgress) -> Unit
    ) {
        EnteFaceModelManager.downloadOrInstallModel(
            context = context,
            force = force,
            onProgress = onProgress,
            onStage = onStage,
            awaitIfResumed = awaitIfResumed,
            onDetailedProgress = onDetailedProgress
        )
    }

    init {
        FaceEngineRegistry.register(this)
    }
}
