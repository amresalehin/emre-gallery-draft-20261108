package com.amresalehin.emregallery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.amresalehin.emregallery.data.MediaRepository
import com.amresalehin.emregallery.data.MediaItem
import com.amresalehin.emregallery.data.PreferencesRepository
import com.amresalehin.emregallery.face.BuffaloLFaceEngine
import com.amresalehin.emregallery.face.BuffaloLFaceEngineFactory
import com.amresalehin.emregallery.face.FaceEngineRegistry
import com.amresalehin.emregallery.face.FaceClusterStore
import com.amresalehin.emregallery.face.FaceIndexingRepository
import com.amresalehin.emregallery.face.FaceProcessingCoordinator
import com.amresalehin.emregallery.face.FaceProcessingOperation
import com.amresalehin.emregallery.face.FaceRecognitionSettings
import com.amresalehin.emregallery.ocr.TesseractOcr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns long-running indexing jobs. No indexing coroutine is tied to a Compose lifecycle.
 * Completed media are checkpointed to the OCR cache / face-index database, so a later run
 * naturally continues with remaining media.
 */
class IndexingForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var taskJob: Job? = null
    private val pauseSignal = MutableStateFlow(false)
    private val prefs by lazy { getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Media indexing", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Progress for OCR, face indexing, and model downloads"; setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_STATUS
        if (action == ACTION_CANCEL) {
            taskJob?.cancel()
            taskJob = null
            pauseSignal.value = false
            updateState(running = false, paused = false, error = "Cancelled")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (action == ACTION_PAUSE) {
            pauseSignal.value = true
            prefs.edit().putBoolean(KEY_PAUSED, true).apply()
            updateState(paused = true)
            refreshNotification("Paused")
            return START_NOT_STICKY
        }
        if (action == ACTION_RESUME) {
            pauseSignal.value = false
            prefs.edit().putBoolean(KEY_PAUSED, false).apply()
            updateState(paused = false)
            refreshNotification("Resuming")
            return START_NOT_STICKY
        }
        if (action == ACTION_STATUS) {
            // Reconcile stale persisted state after process death without restarting work.
            if (taskJob?.isActive != true) {
                updateState(running = false, paused = false)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val title = when (action) {
            ACTION_START_OCR -> "OCR indexing"
            ACTION_START_FACE -> "Face indexing"
            ACTION_START_LOCATION -> "Map location indexing"
            ACTION_DOWNLOAD_MODEL -> "Downloading face model"
            else -> "Media indexing"
        }
        startForegroundCompat(buildNotification(title, "Starting…", 0, 0))
        if (taskJob?.isActive == true) return START_NOT_STICKY

        taskJob = serviceScope.launch {
            try {
                when (action) {
                    ACTION_START_OCR -> runOcr(
                        languages = intent?.getStringArrayListExtra(EXTRA_LANGUAGES)?.ifEmpty { arrayListOf("eng") } ?: arrayListOf("eng"),
                        mlKitLanguages = intent?.getStringArrayListExtra(EXTRA_MLKIT_LANGUAGES)?.ifEmpty { arrayListOf("latin") } ?: arrayListOf("latin"),
                        rescanAll = intent?.getBooleanExtra(EXTRA_RESCAN_ALL, false) == true,
                        modelId = intent?.getStringExtra(EXTRA_MODEL) ?: "fast",
                        engineId = intent?.getStringExtra(EXTRA_ENGINE_ID) ?: "tesseract"
                    )
                    ACTION_START_FACE -> runFace(
                        rescanAll = intent?.getBooleanExtra(EXTRA_RESCAN_ALL, false) == true,
                        engineId = intent?.getStringExtra(EXTRA_ENGINE_ID) ?: BuffaloLFaceEngine.ENGINE_ID,
                        retryFailedOnly = intent?.getBooleanExtra(EXTRA_RETRY_FAILED_ONLY, false) == true,
                        folderFilter = intent?.getStringArrayListExtra(EXTRA_FOLDERS)?.toSet()
                    )
                    ACTION_START_LOCATION -> runLocation(
                        intent?.getBooleanExtra(EXTRA_RESCAN_ALL, false) == true
                    )
                    ACTION_DOWNLOAD_MODEL -> downloadModel(
                        intent?.getBooleanExtra(EXTRA_FORCE, false) == true,
                        intent?.getStringExtra(EXTRA_ENGINE_ID) ?: BuffaloLFaceEngine.ENGINE_ID
                    )
                }
                prefs.edit().putBoolean(KEY_PAUSED, false).apply()
                updateState(running = false, paused = false, error = null)
            } catch (cancelled: CancellationException) {
                updateState(running = false, paused = false, error = "Cancelled")
                throw cancelled
            } catch (e: Exception) {
                updateState(running = false, paused = false, error = e.localizedMessage ?: "Indexing failed")
            } finally {
                taskJob = null
                pauseSignal.value = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runOcr(
        languages: List<String>,
        mlKitLanguages: List<String> = listOf("latin"),
        rescanAll: Boolean,
        modelId: String = "fast",
        engineId: String = "tesseract"
    ) {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
        val isMlKit = engineId.equals("google_mlkit", ignoreCase = true) || engineId.equals("mlkit", ignoreCase = true)
        val model = com.amresalehin.emregallery.ocr.TesseractModel.fromId(modelId)
        val photos = loadPhotos()
        if (rescanAll) withContext(Dispatchers.IO) { TesseractOcr.clearCachedResults(this@IndexingForegroundService) }
        val pending = photos.filterNot { TesseractOcr.hasCachedResult(this, Uri.parse(it.uri)) }
        val initialPaused = prefs.getBoolean(KEY_PAUSED, false)
        pauseSignal.value = initialPaused
        updateState(task = TASK_OCR, running = true, paused = initialPaused, processed = photos.size - pending.size, total = photos.size, error = null)
        val engineLabel = if (isMlKit) {
            "Google ML Kit (${com.amresalehin.emregallery.ocr.MlKitLanguage.getDisplayLabel(mlKitLanguages)})"
        } else model.shortName
        postProgress("OCR indexing", "Preparing ${pending.size} images ($engineLabel)", photos.size - pending.size, photos.size)
        var completed = photos.size - pending.size
        // Unconstrained operation: do not artificially clamp cooldown to at least 30ms.
        // Uses the configured throttle cooldown (0ms for unconstrained/turbo).
        val cooldown = FaceRecognitionSettings.cpuThrottleCooldownMillis(this)
        for (item in pending) {
            awaitResume()
            currentCoroutineContext().ensureActive()
            try {
                val mediaUri = Uri.parse(item.uri)
                val text = if (isMlKit) {
                    com.amresalehin.emregallery.ocr.MlKitOcr.recognize(this, mediaUri, mlKitLanguages)
                } else {
                    TesseractOcr.recognize(this, mediaUri, languages, model)
                }
                TesseractOcr.saveCachedResult(this, mediaUri, text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Failed images are attempted once per run; successful results remain checkpointed.
            }
            completed++
            updateState(processed = completed, total = photos.size)
            if (completed % 4 == 0 || completed == photos.size) {
                val titleLabel = if (isMlKit) "Google ML Kit" else model.title
                postProgress("OCR indexing", "Processed $completed of ${photos.size} images ($titleLabel)", completed, photos.size)
            }
            if (cooldown > 0L) {
                delay(cooldown)
            }
            kotlinx.coroutines.yield()
        }
    }

    private suspend fun runFace(
        rescanAll: Boolean,
        engineId: String = BuffaloLFaceEngine.ENGINE_ID,
        retryFailedOnly: Boolean = false,
        folderFilter: Set<String>? = null
    ) {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
        val allPhotos = loadPhotos()
        val photos = if (folderFilter != null && folderFilter.isNotEmpty()) {
            allPhotos.filter { FaceRecognitionSettings.normalizeFolderPath(it.relativePath) in folderFilter }
        } else {
            FaceRecognitionSettings.filterMediaForFaceIndexing(applicationContext, allPhotos) { it.relativePath }
        }
        val store = FaceClusterStore(applicationContext)
        val factory = FaceEngineRegistry.getFactory(engineId) ?: BuffaloLFaceEngineFactory
        val serviceHandle = FaceProcessingCoordinator.beginOperation(
            operationId = "foreground_service_face_$engineId",
            type = if (rescanAll) FaceProcessingOperation.RESCANNING else FaceProcessingOperation.SCANNING,
            engineId = engineId,
            description = "Service Face Indexing (${factory.displayName})"
        )
        try {
            if (rescanAll) {
                withContext(Dispatchers.IO) {
                    if (FaceRecognitionSettings.isIndexAllFolders(applicationContext) && (folderFilter == null || folderFilter.isEmpty())) {
                        // Reset indexed status for selected engine to reprocess photos without wiping identities
                        store.writableDatabase.delete("indexed_media", "engine_id=?", arrayOf(engineId))
                    } else {
                        // Reset indexed status only for the scoped photos
                        for (p in photos) {
                            store.writableDatabase.delete("indexed_media", "media_uri=? AND engine_id=?", arrayOf(p.uri, engineId))
                        }
                    }
                }
            }

            val targetUris = if (retryFailedOnly) {
                val failedUris = store.getFailedMedia(engineId).map { it.first }.toSet()
                photos.map { it.uri }.filter { it in failedUris }.toSet()
            } else null

            val relevantPhotos = if (targetUris != null) photos.filter { it.uri in targetUris } else photos
            val alreadyDone = if (retryFailedOnly) 0 else store.indexedMediaCount(relevantPhotos.map { it.uri }, engineId = engineId)
            val initialPaused = prefs.getBoolean(KEY_PAUSED, false)
            pauseSignal.value = initialPaused

            updateState(
                task = TASK_FACE,
                running = true,
                paused = initialPaused,
                processed = alreadyDone,
                total = relevantPhotos.size,
                faces = store.indexedFaceCount(engineId),
                failed = store.failedMediaCount(relevantPhotos.map { it.uri }, engineId),
                photosWithFaces = store.mediaWithFacesCount(relevantPhotos.map { it.uri }, engineId),
            engineId = engineId,
            currentItemName = "Initializing ${factory.displayName}…",
            currentItemStatus = "",
            groupingStatus = "",
            detectionState = "PENDING",
            detectionProcessed = 0,
            detectionTotal = relevantPhotos.size,
            embeddingState = "PENDING",
            embeddingProcessed = 0,
            embeddingTotal = relevantPhotos.size,
            clusteringState = "PENDING",
            clusteringProcessed = 0,
            clusteringTotal = relevantPhotos.size,
            error = null
        )
        postProgress("Face indexing", "Preparing ${factory.displayName}", alreadyDone, relevantPhotos.size)

        FaceIndexingRepository(applicationContext, factory).indexNewMedia(
            media = relevantPhotos,
            store = store,
            detectionThreshold = FaceRecognitionSettings.detectionConfidence(applicationContext, engineId),
            recognitionDistance = FaceRecognitionSettings.recognitionDistance(applicationContext, engineId),
            forceRescanUris = if (retryFailedOnly) targetUris ?: emptySet() else emptySet(),
            targetUrisOnly = targetUris,
            awaitIfResumed = { awaitResume(); currentCoroutineContext().ensureActive() },
            onProgress = { p ->
                val done = (alreadyDone + p.processed).coerceAtMost(relevantPhotos.size)
                updateState(
                    processed = done,
                    total = relevantPhotos.size,
                    faces = store.indexedFaceCount(engineId),
                    failed = store.failedMediaCount(relevantPhotos.map { it.uri }, engineId),
                    photosWithFaces = p.photosWithFaces,
                    engineId = engineId,
                    currentItemName = p.currentItemName,
                    currentItemStatus = p.currentItemStatus,
                    groupingStatus = p.groupingStatus,
                    detectionState = p.detectionStage.state.name,
                    detectionProcessed = p.detectionStage.processed,
                    detectionTotal = p.detectionStage.total,
                    embeddingState = p.embeddingStage.state.name,
                    embeddingProcessed = p.embeddingStage.processed,
                    embeddingTotal = p.embeddingStage.total,
                    clusteringState = p.clusteringStage.state.name,
                    clusteringProcessed = p.clusteringStage.processed,
                    clusteringTotal = p.clusteringStage.total
                )
                if (done % 3 == 0 || done == relevantPhotos.size) {
                    val targetName = if (p.currentItemName.isNotBlank()) " · ${p.currentItemName}" else ""
                    val statusSnippet = if (p.currentItemStatus.isNotBlank()) " (${p.currentItemStatus})" else ""
                    val groupingSnippet = if (p.groupingStatus.isNotBlank()) " · ${p.groupingStatus}" else ""
                    postProgress("Face indexing", "Processed $done of ${relevantPhotos.size} images · ${store.indexedFaceCount(engineId)} faces$targetName$statusSnippet$groupingSnippet", done, relevantPhotos.size)
                }
            }
        )
        } finally {
            serviceHandle.close()
        }
    }

    private suspend fun downloadModel(force: Boolean, engineId: String = BuffaloLFaceEngine.ENGINE_ID) {
        val factory = FaceEngineRegistry.getFactory(engineId) ?: BuffaloLFaceEngineFactory
        val serviceHandle = FaceProcessingCoordinator.beginOperation(
            operationId = "foreground_service_model_$engineId",
            type = FaceProcessingOperation.MODEL_DOWNLOAD,
            engineId = engineId,
            description = "Service Model Download (${factory.displayName})"
        )
        try {
            updateState(
                task = TASK_MODEL,
                running = true,
                paused = false,
                processed = 0,
                total = 100,
                bytes = 0L,
                bytesTotal = 0L,
                speed = 0L,
                eta = 0L,
                engineId = engineId,
                error = null
            )
            postProgress("Downloading face model", "Starting download for ${factory.displayName}", 0, 100)

            FaceIndexingRepository(applicationContext, factory).downloadModel(
                force = force,
                onProgress = { received, total ->
                    updateState(
                        bytes = received,
                        bytesTotal = total,
                        processed = if (total > 0) (received * 100 / total).toInt().coerceIn(0, 100) else 0,
                        total = 100,
                        engineId = engineId
                    )
                    postProgress("Downloading face model", "${received / (1024 * 1024)} MB of ${(total / (1024 * 1024)).coerceAtLeast(1)} MB", if (total > 0) (received * 100 / total).toInt().coerceIn(0, 100) else 0, 100)
                },
                onStage = { stage ->
                    updateState(stage = stage, engineId = engineId)
                    postProgress("Preparing face model", when (stage) {
                        "connecting" -> "Connecting to model repository…"
                        "verifying" -> "Verifying cryptographic checksums…"
                        "extracting" -> "Extracting model archive…"
                        "installing" -> "Installing model files…"
                        "ready" -> "${factory.displayName} ready"
                        else -> "Preparing model…"
                    }, -1, 0)
                },
                awaitIfResumed = { awaitResume(); currentCoroutineContext().ensureActive() },
                onDetailedProgress = { detail ->
                    updateState(
                        bytes = detail.bytesReceived,
                        bytesTotal = detail.totalBytes,
                        speed = detail.speedBytesPerSec,
                        eta = detail.etaSeconds,
                        stage = detail.stage,
                        processed = if (detail.totalBytes > 0) (detail.bytesReceived * 100 / detail.totalBytes).toInt().coerceIn(0, 100) else 0,
                        total = 100,
                        engineId = engineId
                    )
                }
            )
        } finally {
            serviceHandle.close()
        }
    }

    private suspend fun awaitResume() {
        pauseSignal.first { paused -> !paused }
        currentCoroutineContext().ensureActive()
    }

    private suspend fun loadPhotos(): List<MediaItem> {
        val prefsRepo = PreferencesRepository(applicationContext)
        val hiddenUris = runCatching { prefsRepo.hidden.first() }.getOrDefault(emptySet())
        val binUris = runCatching { prefsRepo.bin.first() }.getOrDefault(emptySet())
        return MediaRepository(contentResolver).loadMedia().filterNot {
            it.isVideo || it.isTrashed || it.uri in hiddenUris || it.uri in binUris
        }
    }

    private fun updateState(
        task: String? = null,
        running: Boolean? = null,
        paused: Boolean? = null,
        processed: Int? = null,
        total: Int? = null,
        faces: Int? = null,
        failed: Int? = null,
        stage: String? = null,
        bytes: Long? = null,
        bytesTotal: Long? = null,
        speed: Long? = null,
        eta: Long? = null,
        engineId: String? = null,
        currentItemName: String? = null,
        currentItemStatus: String? = null,
        groupingStatus: String? = null,
        detectionState: String? = null,
        detectionProcessed: Int? = null,
        detectionTotal: Int? = null,
        embeddingState: String? = null,
        embeddingProcessed: Int? = null,
        embeddingTotal: Int? = null,
        clusteringState: String? = null,
        clusteringProcessed: Int? = null,
        clusteringTotal: Int? = null,
        photosWithFaces: Int? = null,
        error: String? = null
    ) {
        prefs.edit().apply {
            if (task != null) putString(KEY_TASK, task)
            if (running != null) putBoolean(KEY_RUNNING, running)
            if (paused != null) putBoolean(KEY_PAUSED, paused)
            if (processed != null) putInt(KEY_PROCESSED, processed)
            if (total != null) putInt(KEY_TOTAL, total)
            if (faces != null) putInt(KEY_FACES, faces)
            if (failed != null) putInt(KEY_FAILED, failed)
            if (photosWithFaces != null) putInt(KEY_PHOTOS_WITH_FACES, photosWithFaces)
            if (stage != null) putString(KEY_STAGE, stage)
            if (bytes != null) putLong(KEY_BYTES, bytes)
            if (bytesTotal != null) putLong(KEY_BYTES_TOTAL, bytesTotal)
            if (speed != null) putLong(KEY_SPEED, speed)
            if (eta != null) putLong(KEY_ETA, eta)
            if (engineId != null) putString(KEY_ENGINE_ID, engineId)
            if (currentItemName != null) putString(KEY_CURRENT_ITEM_NAME, currentItemName)
            if (currentItemStatus != null) putString(KEY_CURRENT_ITEM_STATUS, currentItemStatus)
            if (groupingStatus != null) putString(KEY_GROUPING_STATUS, groupingStatus)
            if (detectionState != null) putString(KEY_DETECTION_STATE, detectionState)
            if (detectionProcessed != null) putInt(KEY_DETECTION_PROCESSED, detectionProcessed)
            if (detectionTotal != null) putInt(KEY_DETECTION_TOTAL, detectionTotal)
            if (embeddingState != null) putString(KEY_EMBEDDING_STATE, embeddingState)
            if (embeddingProcessed != null) putInt(KEY_EMBEDDING_PROCESSED, embeddingProcessed)
            if (embeddingTotal != null) putInt(KEY_EMBEDDING_TOTAL, embeddingTotal)
            if (clusteringState != null) putString(KEY_CLUSTERING_STATE, clusteringState)
            if (clusteringProcessed != null) putInt(KEY_CLUSTERING_PROCESSED, clusteringProcessed)
            if (clusteringTotal != null) putInt(KEY_CLUSTERING_TOTAL, clusteringTotal)
            putString(KEY_ERROR, error)
            apply()
        }
        FaceProcessingCoordinator.refreshState()
    }

    private fun postProgress(title: String, message: String, progress: Int, max: Int) {
        updateState()
        val notification = buildNotification(title, message, progress, max)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private suspend fun runLocation(rescanAll: Boolean) {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
        val photos = loadPhotos()
        val cache = com.amresalehin.emregallery.data.PhotoMapLocationCache(applicationContext)
        if (rescanAll) withContext(Dispatchers.IO) { cache.clearAll() }
        val cached = withContext(Dispatchers.IO) { cache.loadAll() }
        val pending = if (rescanAll) photos else photos.filterNot { cached.containsKey(it.uri) }
        var locatedCount = cached.values.count { it.point != null }
        val doneCount = photos.size - pending.size
        val initialPaused = prefs.getBoolean(KEY_PAUSED, false)
        pauseSignal.value = initialPaused
        updateState(task = TASK_LOCATION, running = true, paused = initialPaused, processed = doneCount, total = photos.size, faces = locatedCount, error = null)
        postProgress("Map location indexing", "Scanning ${pending.size} photos for GPS geotags", doneCount, photos.size)
        var completed = doneCount
        for (item in pending) {
            awaitResume()
            currentCoroutineContext().ensureActive()
            val point = withContext(Dispatchers.IO) {
                com.amresalehin.emregallery.data.PhotoMapLocationCache.extractLocation(applicationContext, item.uri, isVideo = item.isVideo)
            }
            withContext(Dispatchers.IO) {
                cache.save(item.uri, point)
            }
            if (point != null) locatedCount++
            completed++
            updateState(processed = completed, total = photos.size, faces = locatedCount)
            if (completed % 8 == 0 || completed == photos.size) {
                postProgress("Map location indexing", "Scanned $completed of ${photos.size} photos · $locatedCount geotagged", completed, photos.size)
            }
            // Continuous unconstrained operation without artificial thermal cooldown
            kotlinx.coroutines.yield()
        }
    }

    private fun refreshNotification(message: String) {
        val title = when (prefs.getString(KEY_TASK, "")) {
            TASK_OCR -> "OCR indexing"
            TASK_FACE -> "Face indexing"
            TASK_LOCATION -> "Map location indexing"
            TASK_MODEL -> "Downloading face model"
            else -> "Media indexing"
        }
        val p = prefs.getInt(KEY_PROCESSED, 0)
        val t = prefs.getInt(KEY_TOTAL, 0)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(title, message, p, t))
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, message: String, progress: Int, max: Int): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title).setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (progress >= 0 && max > 0) b.setProgress(max, progress.coerceIn(0, max), false)
        else b.setProgress(0, 0, true)
        return b.build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val STATE_PREFS = "indexing_service_state"
        const val KEY_TASK = "task"
        const val KEY_RUNNING = "running"
        const val KEY_PAUSED = "paused"
        const val KEY_PROCESSED = "processed"
        const val KEY_TOTAL = "total"
        const val KEY_FACES = "faces"
        const val KEY_FAILED = "failed"
        const val KEY_STAGE = "stage"
        const val KEY_BYTES = "bytes"
        const val KEY_BYTES_TOTAL = "bytes_total"
        const val KEY_SPEED = "speed"
        const val KEY_ETA = "eta"
        const val KEY_CURRENT_ITEM_NAME = "current_item_name"
        const val KEY_CURRENT_ITEM_STATUS = "current_item_status"
        const val KEY_GROUPING_STATUS = "grouping_status"
        const val KEY_DETECTION_STATE = "detection_state"
        const val KEY_DETECTION_PROCESSED = "detection_processed"
        const val KEY_DETECTION_TOTAL = "detection_total"
        const val KEY_EMBEDDING_STATE = "embedding_state"
        const val KEY_EMBEDDING_PROCESSED = "embedding_processed"
        const val KEY_EMBEDDING_TOTAL = "embedding_total"
        const val KEY_CLUSTERING_STATE = "clustering_state"
        const val KEY_CLUSTERING_PROCESSED = "clustering_processed"
        const val KEY_CLUSTERING_TOTAL = "clustering_total"
        const val KEY_ERROR = "error"
        const val KEY_ENGINE_ID = "engine_id"
        const val KEY_PHOTOS_WITH_FACES = "photos_with_faces"

        const val TASK_LOCATION = "location"
        private const val TASK_OCR = "ocr"
        private const val TASK_FACE = "face"
        private const val TASK_MODEL = "model"
        private const val CHANNEL_ID = "media_indexing"
        private const val NOTIFICATION_ID = 4107
        private const val ACTION_START_OCR = "com.amresalehin.emregallery.indexing.START_OCR"
        private const val ACTION_START_FACE = "com.amresalehin.emregallery.indexing.START_FACE"
        private const val ACTION_START_LOCATION = "com.amresalehin.emregallery.indexing.START_LOCATION"
        private const val ACTION_DOWNLOAD_MODEL = "com.amresalehin.emregallery.indexing.DOWNLOAD_MODEL"
        private const val ACTION_PAUSE = "com.amresalehin.emregallery.indexing.PAUSE"
        private const val ACTION_RESUME = "com.amresalehin.emregallery.indexing.RESUME"
        private const val ACTION_CANCEL = "com.amresalehin.emregallery.indexing.CANCEL"
        private const val ACTION_STATUS = "com.amresalehin.emregallery.indexing.STATUS"
        private const val EXTRA_LANGUAGES = "languages"
        private const val EXTRA_MLKIT_LANGUAGES = "mlkit_languages"
        private const val EXTRA_RESCAN_ALL = "rescan_all"
        private const val EXTRA_MODEL = "ocr_model"
        private const val EXTRA_FORCE = "force"
        const val EXTRA_ENGINE_ID = "engine_id"
        const val EXTRA_RETRY_FAILED_ONLY = "retry_failed_only"
        const val EXTRA_FOLDERS = "face_indexing_folders"

        private fun command(context: Context, action: String, configure: Intent.() -> Unit = {}) {
            val intent = Intent(context, IndexingForegroundService::class.java).setAction(action).apply(configure)
            if (action == ACTION_PAUSE || action == ACTION_RESUME || action == ACTION_CANCEL || action == ACTION_STATUS) {
                context.startService(intent)
            } else {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun startOcr(
            context: Context,
            languages: List<String>,
            rescanAll: Boolean = false,
            modelId: String = "fast",
            engineId: String = "tesseract",
            mlKitLanguages: List<String> = listOf("latin")
        ) =
            command(context, ACTION_START_OCR) {
                putStringArrayListExtra(EXTRA_LANGUAGES, ArrayList(languages))
                putStringArrayListExtra(EXTRA_MLKIT_LANGUAGES, ArrayList(mlKitLanguages))
                putExtra(EXTRA_RESCAN_ALL, rescanAll)
                putExtra(EXTRA_MODEL, modelId)
                putExtra(EXTRA_ENGINE_ID, engineId)
            }

        fun startFace(
            context: Context,
            rescanAll: Boolean = false,
            engineId: String = BuffaloLFaceEngine.ENGINE_ID,
            folders: List<String>? = null
        ) =
            command(context, ACTION_START_FACE) {
                putExtra(EXTRA_RESCAN_ALL, rescanAll)
                putExtra(EXTRA_ENGINE_ID, engineId)
                if (folders != null) {
                    putStringArrayListExtra(EXTRA_FOLDERS, ArrayList(folders))
                }
            }

        fun retryFailedFaces(
            context: Context,
            engineId: String = BuffaloLFaceEngine.ENGINE_ID
        ) =
            command(context, ACTION_START_FACE) {
                putExtra(EXTRA_RETRY_FAILED_ONLY, true)
                putExtra(EXTRA_ENGINE_ID, engineId)
            }

        fun startLocation(context: Context, rescanAll: Boolean = false) =
            command(context, ACTION_START_LOCATION) {
                putExtra(EXTRA_RESCAN_ALL, rescanAll)
            }

        fun downloadModel(
            context: Context,
            force: Boolean = false,
            engineId: String = BuffaloLFaceEngine.ENGINE_ID
        ) =
            command(context, ACTION_DOWNLOAD_MODEL) {
                putExtra(EXTRA_FORCE, force)
                putExtra(EXTRA_ENGINE_ID, engineId)
            }

        fun pause(context: Context) = command(context, ACTION_PAUSE)
        fun resume(context: Context) = command(context, ACTION_RESUME)
        fun cancel(context: Context) = command(context, ACTION_CANCEL)
        fun status(context: Context) = command(context, ACTION_STATUS)
    }
}
