package com.amresalehin.emregallery.face

import android.content.Context
import android.net.Uri
import com.amresalehin.emregallery.data.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

enum class StageState {
    PENDING,
    RUNNING,
    COMPLETED,
    PAUSED,
    FAILED
}

data class StageProgress(
    val state: StageState = StageState.PENDING,
    val processed: Int = 0,
    val total: Int = 0,
    val percentage: Int = 0
)

data class FaceIndexingProgress(
    val processed: Int = 0,
    val total: Int = 0,
    val facesFound: Int = 0,
    val photosWithFaces: Int = 0,
    val failed: Int = 0,
    val currentItemName: String = "",
    val currentItemStatus: String = "",
    val groupingStatus: String = "",
    val currentItemFaces: Int? = null,
    val engineId: String = BuffaloLFaceEngine.ENGINE_ID,
    val engineName: String = "",
    val detectionStage: StageProgress = StageProgress(),
    val embeddingStage: StageProgress = StageProgress(),
    val clusteringStage: StageProgress = StageProgress(),
    val failedItems: List<Pair<String, String>> = emptyList()
)

/**
 * Clean, decoupled media face indexing repository.
 * Operates with any FaceEngine implementation supplied via constructor injection.
 */
class FaceIndexingRepository(
    private val context: Context,
    private val engineFactory: FaceEngineFactory = BuffaloLFaceEngineFactory
) {

    companion object {
        @Volatile
        var activeEngineDescription: String = "Detecting..."
            private set

        private val lastEngineFailures = ConcurrentHashMap<String, String>()

        fun queryEngineInfo(context: Context, factory: FaceEngineFactory = BuffaloLFaceEngineFactory): Pair<String, String?> {
            val failure = lastEngineFailures[factory.engineId]
            return factory.displayName to failure
        }
    }

    suspend fun indexNewMedia(
        media: List<MediaItem>,
        store: FaceClusterStore,
        onProgress: suspend (FaceIndexingProgress) -> Unit = {},
        awaitIfResumed: suspend () -> Unit = {},
        detectionThreshold: Float = FaceRecognitionSettings.DEFAULT_DETECTION_CONFIDENCE,
        recognitionDistance: Float = FaceRecognitionSettings.DEFAULT_RECOGNITION_DISTANCE,
        forceRescanUris: Set<String> = emptySet(),
        targetUrisOnly: Set<String>? = null
    ): FaceIndexingProgress = withContext(Dispatchers.IO) {
        // Enforce background scheduling priority so inference threads run on efficiency cores
        // and never starve the UI/render threads.
        runCatching {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        }
        val photos = media.filterNot { it.isVideo }
        val engineId = engineFactory.engineId
        val cooldownMillis = FaceRecognitionSettings.cpuThrottleCooldownMillis(context)
        val operationHandle = FaceProcessingCoordinator.beginOperation(
            operationId = "repo_index_${System.nanoTime()}",
            type = if (forceRescanUris.isNotEmpty()) FaceProcessingOperation.RESCANNING else FaceProcessingOperation.SCANNING,
            engineId = engineId,
            description = "Face indexing (${engineFactory.displayName})"
        )
        val candidates = if (targetUrisOnly != null) {
            photos.filter { it.uri in targetUrisOnly }
        } else photos

        val pending = candidates.filter { it.uri in forceRescanUris || !store.isMediaIndexed(it.uri, engineId) }
        var processed = 0
        var facesFound = 0
        var photosWithFaces = 0
        var failed = 0
        val failedList = mutableListOf<Pair<String, String>>()
        var engine: FaceEngine? = null
        var groupingStatus: String? = null
        var lastCompletedItemName = ""
        var lastCompletedItemStatus = ""
        var lastCompletedItemFaces: Int? = null

        val totalPending = pending.size
        var detectionStage = StageProgress(
            state = if (totalPending > 0) StageState.RUNNING else StageState.COMPLETED,
            processed = 0,
            total = totalPending,
            percentage = 0
        )
        var embeddingStage = StageProgress(
            state = if (totalPending > 0) StageState.PENDING else StageState.COMPLETED,
            processed = 0,
            total = totalPending,
            percentage = 0
        )
        var clusteringStage = StageProgress(
            state = if (totalPending > 0) StageState.PENDING else StageState.COMPLETED,
            processed = 0,
            total = totalPending,
            percentage = 0
        )

        try {
            if (pending.isNotEmpty()) {
                if (!engineFactory.isInstalled(context)) {
                    val msg = "${engineFactory.displayName} is not installed. Please download the model."
                    lastEngineFailures[engineId] = msg
                    activeEngineDescription = "${engineFactory.displayName} (Not downloaded)"
                    error(msg)
                }

                engine = try {
                    val created = engineFactory.create(context, detectionThreshold)
                    activeEngineDescription = engineFactory.displayName
                    lastEngineFailures.remove(engineId)
                    created
                } catch (e: Exception) {
                    val msg = "${engineFactory.displayName} initialization failed: " + (e.localizedMessage ?: e.message ?: "Unknown error")
                    lastEngineFailures[engineId] = msg
                    activeEngineDescription = "${engineFactory.displayName} (Initialization failed)"
                    throw e
                }
            }

            val actualDimension = engine?.embeddingDimension ?: engineFactory.embeddingDimension
            val provenance = EmbeddingProvenance(
                engineId = engineFactory.engineId,
                modelVersion = engineFactory.modelVersion,
                dimension = actualDimension
            )

            for ((index, item) in pending.withIndex()) {
                awaitIfResumed()
                currentCoroutineContext().ensureActive()

                val itemName = item.name.ifBlank { item.uri.substringAfterLast('/') }
                val currentPct = if (totalPending > 0) (index * 100 / totalPending) else 0

                detectionStage = StageProgress(StageState.RUNNING, index, totalPending, currentPct)
                embeddingStage = StageProgress(StageState.PENDING, index, totalPending, currentPct)
                var lastItemStatus = "Scanning for faces…"
                var currentItemFacesCount: Int? = null

                onProgress(
                    FaceIndexingProgress(
                        processed = processed,
                        total = totalPending,
                        facesFound = facesFound,
                        photosWithFaces = photosWithFaces,
                        failed = failed,
                        currentItemName = itemName,
                        currentItemStatus = lastItemStatus,
                        currentItemFaces = null,
                        engineId = engineId,
                        engineName = engineFactory.displayName,
                        detectionStage = detectionStage,
                        embeddingStage = embeddingStage,
                        clusteringStage = clusteringStage,
                        failedItems = failedList.toList()
                    )
                )

                try {
                    val activeEngine = engine ?: error("Face recognition engine is not ready.")
                    val uri = Uri.parse(item.uri)

                    // Phase 1: Face Detection
                    val rawDetections = activeEngine.detectFaces(uri)
                    val detectedCount = rawDetections.size
                    currentItemFacesCount = detectedCount

                    detectionStage = StageProgress(
                        state = if (index + 1 >= totalPending) StageState.COMPLETED else StageState.RUNNING,
                        processed = index + 1,
                        total = totalPending,
                        percentage = if (totalPending > 0) ((index + 1) * 100 / totalPending) else 100
                    )

                    if (detectedCount == 0) {
                        // 0 faces found - completed for this item
                        store.markMediaIndexed(item.uri, 0, engineId)
                        store.pruneStaleFacesForMedia(item.uri, engineId, 0)
                        lastItemStatus = "0 faces found"
                        embeddingStage = StageProgress(
                            state = if (index + 1 >= totalPending) StageState.COMPLETED else StageState.RUNNING,
                            processed = index + 1,
                            total = totalPending,
                            percentage = if (totalPending > 0) ((index + 1) * 100 / totalPending) else 100
                        )
                        onProgress(
                            FaceIndexingProgress(
                                processed = processed,
                                total = totalPending,
                                facesFound = facesFound,
                                photosWithFaces = photosWithFaces,
                                failed = failed,
                                currentItemName = itemName,
                                currentItemStatus = lastItemStatus,
                                currentItemFaces = 0,
                                engineId = engineId,
                                engineName = engineFactory.displayName,
                                detectionStage = detectionStage,
                                embeddingStage = embeddingStage,
                                clusteringStage = clusteringStage,
                                failedItems = failedList.toList()
                            )
                        )
                    } else {
                        // Phase 2: Embedding Generation
                        embeddingStage = StageProgress(StageState.RUNNING, index, totalPending, currentPct)
                        lastItemStatus = "$detectedCount face${if (detectedCount > 1) "s" else ""} found, generating embeddings…"
                        onProgress(
                            FaceIndexingProgress(
                                processed = processed,
                                total = totalPending,
                                facesFound = facesFound,
                                photosWithFaces = photosWithFaces,
                                failed = failed,
                                currentItemName = itemName,
                                currentItemStatus = lastItemStatus,
                                currentItemFaces = detectedCount,
                                engineId = engineId,
                                engineName = engineFactory.displayName,
                                detectionStage = detectionStage,
                                embeddingStage = embeddingStage,
                                clusteringStage = clusteringStage,
                                failedItems = failedList.toList()
                            )
                        )

                        val detections = activeEngine.embedFaces(uri, rawDetections)
                        detections.forEach { face ->
                            if (engineFactory.clusteringStrategy != null) {
                                store.addRawFaceObservation(
                                    mediaUri = item.uri,
                                    faceIndex = face.faceIndex,
                                    rawEmbedding = face.embedding,
                                    bounds = face.bounds,
                                    confidence = face.score,
                                    quality = face.quality,
                                    provenance = provenance,
                                    qualityMetrics = face.qualityMetrics
                                )
                            } else {
                                store.addFace(
                                    mediaUri = item.uri,
                                    faceIndex = face.faceIndex,
                                    rawEmbedding = face.embedding,
                                    maxDistance = recognitionDistance,
                                    bounds = face.bounds,
                                    confidence = face.score,
                                    quality = face.quality,
                                    provenance = provenance,
                                    qualityMetrics = face.qualityMetrics
                                )
                            }
                        }
                        store.markMediaIndexed(item.uri, detections.size, engineId)
                        store.pruneStaleFacesForMedia(item.uri, engineId, detections.map { it.faceIndex })
                        facesFound += detections.size
                        photosWithFaces++
                        lastItemStatus = "Indexed ${detections.size} face${if (detections.size > 1) "s" else ""}"
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (err: Exception) {
                    val errorDetail = err.localizedMessage ?: "Detection/embedding failed"
                    store.markMediaFailed(item.uri, engineId, errorDetail)
                    failedList.add(item.uri to errorDetail)
                    failed++
                    lastItemStatus = "Failed: $errorDetail"
                } finally {
                    processed++
                    lastCompletedItemName = itemName
                    lastCompletedItemStatus = lastItemStatus
                    lastCompletedItemFaces = currentItemFacesCount
                    val completedPct = if (totalPending > 0) (processed * 100 / totalPending) else 100
                    detectionStage = StageProgress(
                        state = if (processed >= totalPending) StageState.COMPLETED else StageState.RUNNING,
                        processed = processed,
                        total = totalPending,
                        percentage = completedPct
                    )
                    embeddingStage = StageProgress(
                        state = if (processed >= totalPending) StageState.COMPLETED else StageState.RUNNING,
                        processed = processed,
                        total = totalPending,
                        percentage = completedPct
                    )

                    onProgress(
                        FaceIndexingProgress(
                            processed = processed,
                            total = totalPending,
                            facesFound = facesFound,
                            photosWithFaces = photosWithFaces,
                            failed = failed,
                            currentItemName = itemName,
                            currentItemStatus = lastItemStatus,
                            currentItemFaces = currentItemFacesCount,
                            engineId = engineId,
                            engineName = engineFactory.displayName,
                            detectionStage = detectionStage,
                            embeddingStage = embeddingStage,
                            clusteringStage = clusteringStage,
                            failedItems = failedList.toList()
                        )
                    )
                    // Thermal throttling prevention: duty-cycle cooling pause between photos keeps
                    // CPU core temperatures within safe limits and prevents system thermal throttling.
                    if (cooldownMillis > 0L) {
                        kotlinx.coroutines.delay(cooldownMillis)
                    }
                    kotlinx.coroutines.yield()
                }
            }

            // Group only after photo embeddings finish, but retry when all photos are already indexed.
            val strategy = engineFactory.clusteringStrategy
            if (strategy != null) {
                val faceTotal = store.faceEmbeddingCount(engineId, engineFactory.modelVersion)
                if (store.isGroupingRequired(engineId, engineFactory.modelVersion, strategy.strategyVersion)) {
                    store.markGroupingPending(engineId, engineFactory.modelVersion, strategy.strategyVersion, faceTotal)
                    clusteringStage = StageProgress(StageState.RUNNING, 0, faceTotal, 0)
                    groupingStatus = "Grouping people…"
                    onProgress(FaceIndexingProgress(
                        processed=processed,total=totalPending,facesFound=facesFound,photosWithFaces=photosWithFaces,failed=failed,
                        currentItemName=lastCompletedItemName,currentItemStatus=lastCompletedItemStatus,
                        groupingStatus=groupingStatus.orEmpty(),currentItemFaces=lastCompletedItemFaces,
                        engineId=engineId,engineName=engineFactory.displayName,
                        detectionStage=detectionStage,embeddingStage=embeddingStage,
                        clusteringStage=clusteringStage,failedItems=failedList.toList()
                    ))
                    try {
                        val groupedPeople = strategy.clusterFaces(
                            store=store,mediaUris=pending.map { it.uri },engineId=engineId,
                            modelVersion=engineFactory.modelVersion,embeddingDimension=actualDimension,
                            maxDistance=recognitionDistance,
                            onProgress={ done,total ->
                                val safeTotal=total.coerceAtLeast(0)
                                val safeDone=done.coerceIn(0,safeTotal)
                                val percentage=if(safeTotal>0) safeDone*100/safeTotal else 100
                                clusteringStage=StageProgress(StageState.RUNNING,safeDone,safeTotal,percentage.coerceIn(0,99))
                                groupingStatus=if(safeTotal>0) "Grouping people… $safeDone/$safeTotal faces" else "Grouping people…"
                                runBlocking {
                                    onProgress(FaceIndexingProgress(
                                        processed=processed,total=totalPending,facesFound=facesFound,photosWithFaces=photosWithFaces,failed=failed,
                                        currentItemName=lastCompletedItemName,currentItemStatus=lastCompletedItemStatus,
                        groupingStatus=groupingStatus.orEmpty(),currentItemFaces=lastCompletedItemFaces,
                                        engineId=engineId,engineName=engineFactory.displayName,
                                        detectionStage=detectionStage,embeddingStage=embeddingStage,
                                        clusteringStage=clusteringStage,failedItems=failedList.toList()
                                    ))
                                }
                            }
                        )
                        store.ensurePersonCovers(engineId)
                        val finalCount=store.faceEmbeddingCount(engineId,engineFactory.modelVersion)
                        store.markGroupingCompleted(engineId,engineFactory.modelVersion,strategy.strategyVersion,groupedPeople,finalCount)
                        clusteringStage=StageProgress(StageState.COMPLETED,faceTotal,faceTotal,100)
                        val unassigned = store.unassignedFaceCount(engineId, engineFactory.modelVersion)
                        groupingStatus="Grouping complete · $groupedPeople people · $unassigned unassigned"
                        FaceStoreEvents.notifyAllUpdated()
                    } catch (cancelled: CancellationException) {
                        runCatching { store.markGroupingPending(engineId,engineFactory.modelVersion,strategy.strategyVersion,
                            store.faceEmbeddingCount(engineId,engineFactory.modelVersion)) }
                        FaceStoreEvents.notifyAllUpdated()
                        throw cancelled
                    } catch (err: Exception) {
                        val detail=err.localizedMessage ?: err.message ?: "Unknown grouping error"
                        runCatching { store.markGroupingFailed(engineId,engineFactory.modelVersion,strategy.strategyVersion,detail,
                            store.faceEmbeddingCount(engineId,engineFactory.modelVersion)) }
                        FaceStoreEvents.notifyAllUpdated()
                        clusteringStage=StageProgress(StageState.FAILED,clusteringStage.processed,faceTotal,clusteringStage.percentage)
                        groupingStatus="Grouping failed: $detail"
                    }
                    onProgress(FaceIndexingProgress(
                        processed=processed,total=totalPending,facesFound=facesFound,photosWithFaces=photosWithFaces,failed=failed,
                        currentItemName=lastCompletedItemName,currentItemStatus=lastCompletedItemStatus,
                        groupingStatus=groupingStatus.orEmpty(),currentItemFaces=lastCompletedItemFaces,
                        engineId=engineId,engineName=engineFactory.displayName,
                        detectionStage=detectionStage,embeddingStage=embeddingStage,
                        clusteringStage=clusteringStage,failedItems=failedList.toList()
                    ))
                } else {
                    clusteringStage=StageProgress(StageState.COMPLETED,faceTotal,faceTotal,100)
                }
            } else if(totalPending>0) {
                clusteringStage=StageProgress(StageState.COMPLETED,totalPending,totalPending,100)
            }
        } finally {
            engine?.close()
            FaceStoreEvents.notifyAllUpdated()
            operationHandle.close()
        }

        val finalProgress = FaceIndexingProgress(
            processed = processed,
            total = totalPending,
            facesFound = facesFound,
            photosWithFaces = photosWithFaces,
            failed = failed,
            currentItemName = lastCompletedItemName,
            currentItemStatus = lastCompletedItemStatus,
            groupingStatus = groupingStatus.orEmpty(),
            currentItemFaces = lastCompletedItemFaces,
            engineId = engineId,
            engineName = engineFactory.displayName,
            detectionStage = detectionStage,
            embeddingStage = embeddingStage,
            clusteringStage = clusteringStage,
            failedItems = failedList.toList()
        )
        onProgress(finalProgress)
        FaceStoreEvents.notifyAllUpdated()
        finalProgress
    }

    suspend fun downloadModel(
        force: Boolean = false,
        onProgress: (receivedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
        onStage: (String) -> Unit = {},
        awaitIfResumed: suspend () -> Unit = {},
        onDetailedProgress: (DetailedDownloadProgress) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val engineId = engineFactory.engineId
        FaceProcessingCoordinator.withProcessing(
            operationId = "repo_download_${System.nanoTime()}",
            type = FaceProcessingOperation.MODEL_DOWNLOAD,
            engineId = engineId,
            description = "Downloading ${engineFactory.displayName}"
        ) {
            try {
                engineFactory.downloadModel(context, force, onProgress, onStage, awaitIfResumed, onDetailedProgress)
                lastEngineFailures.remove(engineId)
                activeEngineDescription = engineFactory.displayName
            } catch (e: Exception) {
                lastEngineFailures[engineId] = e.localizedMessage ?: "Download failed"
                throw e
            }
        }
    }
}
