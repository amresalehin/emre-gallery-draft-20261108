package com.amresalehin.emregallery.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri

data class DetectedFaceEmbedding(
    val faceIndex: Int,
    val bounds: RectF,
    val embedding: FloatArray,
    val score: Float = 1.0f,
    val quality: Float = 1.0f,
    val qualityMetrics: FaceQualityMetrics? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DetectedFaceEmbedding) return false
        if (faceIndex != other.faceIndex) return false
        if (bounds != other.bounds) return false
        if (!embedding.contentEquals(other.embedding)) return false
        if (score != other.score) return false
        if (quality != other.quality) return false
        if (qualityMetrics != other.qualityMetrics) return false
        return true
    }

    override fun hashCode(): Int {
        var result = faceIndex
        result = 31 * result + bounds.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + score.hashCode()
        result = 31 * result + quality.hashCode()
        result = 31 * result + (qualityMetrics?.hashCode() ?: 0)
        return result
    }
}

data class RawFaceDetection(
    val faceIndex: Int,
    val bounds: RectF,
    val landmarks: FloatArray? = null,
    val score: Float = 1.0f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawFaceDetection) return false
        if (faceIndex != other.faceIndex) return false
        if (bounds != other.bounds) return false
        if (landmarks != null) {
            if (other.landmarks == null || !landmarks.contentEquals(other.landmarks)) return false
        } else if (other.landmarks != null) return false
        if (score != other.score) return false
        return true
    }

    override fun hashCode(): Int {
        var result = faceIndex
        result = 31 * result + bounds.hashCode()
        result = 31 * result + (landmarks?.contentHashCode() ?: 0)
        result = 31 * result + score.hashCode()
        return result
    }
}

/**
 * Standard interface for face detection and recognition engines.
 * Each engine declares its identity, model version, and embedding dimension.
 */
interface FaceEngine : AutoCloseable {
    val engineId: String
    val modelVersion: String
    val embeddingDimension: Int
    val formatVersion: Int get() = 1

    /**
     * Phase 1: Detects raw faces and landmarks in the given media URI.
     */
    fun detectFaces(uri: Uri): List<RawFaceDetection> = emptyList()

    /**
     * Phase 2: Generates embeddings for previously detected faces.
     */
    fun embedFaces(uri: Uri, detections: List<RawFaceDetection>): List<DetectedFaceEmbedding> = emptyList()

    /**
     * Detects faces in the given media URI and produces normalized embeddings.
     */
    fun embed(uri: Uri): List<DetectedFaceEmbedding> {
        val detections = detectFaces(uri)
        if (detections.isEmpty()) return emptyList()
        return embedFaces(uri, detections)
    }

    /**
     * Direct bitmap embedding for in-memory operations and evaluation benchmarks.
     */
    fun embedBitmap(bitmap: Bitmap): List<DetectedFaceEmbedding> = emptyList()

    override fun close() {}
}

/**
 * Strategy contract for face clustering algorithms.
 * Allows decoupling inference from engine-specific clustering workflows.
 */
interface FaceClusteringStrategy {
    val strategyId: String
    val strategyVersion: Int

    /**
     * Executes or updates clusters for the given media and face store.
     */
    fun clusterFaces(
        store: FaceClusterStore,
        mediaUris: List<String>,
        engineId: String,
        modelVersion: String,
        embeddingDimension: Int,
        maxDistance: Float,
        onProgress: (processedFaces: Int, totalFaces: Int) -> Unit = { _, _ -> }
    ): Int
}

/**
 * Factory for creating and managing models of a specific face recognition engine.
 */
interface FaceEngineFactory {
    val engineId: String
    val displayName: String
    val modelVersion: String
    val embeddingDimension: Int
    val isExperimental: Boolean get() = false
    val clusteringStrategy: FaceClusteringStrategy? get() = null

    fun isInstalled(context: Context): Boolean
    fun create(context: Context, detectionThreshold: Float): FaceEngine

    suspend fun downloadModel(
        context: Context,
        force: Boolean = false,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        onStage: (String) -> Unit = {},
        awaitIfResumed: suspend () -> Unit = {},
        onDetailedProgress: (DetailedDownloadProgress) -> Unit = {}
    )
}

data class DetailedDownloadProgress(
    val bytesReceived: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long = 0L,
    val stage: String = "downloading"
) {
    val percentage: Int get() = if (totalBytes > 0) ((bytesReceived * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
}

/**
 * Registry of available face recognition engines, supporting active production
 * engines and experimental engines running side-by-side without interference.
 */
object FaceEngineRegistry {
    private val factories = LinkedHashMap<String, FaceEngineFactory>()

    @Volatile
    private var activeEngineId: String = BuffaloLFaceEngine.ENGINE_ID

    fun register(factory: FaceEngineFactory) {
        synchronized(factories) {
            factories[factory.engineId] = factory
        }
    }

    fun getFactory(engineId: String): FaceEngineFactory? {
        return synchronized(factories) { factories[engineId] }
    }

    fun getActiveFactory(): FaceEngineFactory {
        return synchronized(factories) {
            factories[activeEngineId] ?: BuffaloLFaceEngineFactory
        }
    }

    fun setActiveEngine(engineId: String): Boolean {
        if (FaceProcessingCoordinator.isBusy()) {
            return false
        }
        synchronized(factories) {
            if (factories.containsKey(engineId)) {
                activeEngineId = engineId
                return true
            }
        }
        return false
    }

    internal fun setDirectActiveEngine(engineId: String) {
        synchronized(factories) {
            if (factories.containsKey(engineId)) {
                activeEngineId = engineId
            }
        }
    }

    val activeId: String get() = activeEngineId

    fun listEngines(): List<FaceEngineFactory> {
        return synchronized(factories) { factories.values.toList() }
    }

    fun clearNonDefault() {
        synchronized(factories) {
            factories.clear()
            factories[BuffaloLFaceEngineFactory.engineId] = BuffaloLFaceEngineFactory
            activeEngineId = BuffaloLFaceEngineFactory.engineId
        }
    }
}
