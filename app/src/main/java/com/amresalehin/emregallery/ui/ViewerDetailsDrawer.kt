package com.amresalehin.emregallery.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.location.Geocoder
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amresalehin.emregallery.data.CustomAlbum
import com.amresalehin.emregallery.data.MediaItem
import com.amresalehin.emregallery.data.PhotoMapLocationCache
import com.amresalehin.emregallery.face.*
import com.amresalehin.emregallery.ocr.TesseractOcr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import java.util.Locale

/**
 * State holder for the media details drawer (metadata and OCR).
 */
@Stable
class ViewerDetailsState(
    val context: Context,
    val item: MediaItem,
    defaultOcrLanguages: Set<String>,
    initialOcrModel: String = "fast",
    defaultMlKitLanguages: Set<String> = setOf("latin")
) {
    var metadata by mutableStateOf<Map<String, String>>(emptyMap())
    var ocrText by mutableStateOf("")
    var ocrError by mutableStateOf<String?>(null)
    var ocrBusy by mutableStateOf(false)
    var ocrModel by mutableStateOf(initialOcrModel)
    val ocrLanguages = mutableStateListOf<String>().apply {
        val initial = if (defaultOcrLanguages.isNotEmpty()) defaultOcrLanguages else setOf("eng")
        addAll(initial)
    }
    val mlKitLanguages = mutableStateListOf<String>().apply {
        val initial = if (defaultMlKitLanguages.isNotEmpty()) defaultMlKitLanguages else setOf("latin")
        addAll(initial)
    }

    suspend fun loadData() {
        metadata = withContext(Dispatchers.IO) {
            runCatching { readMediaMetadata(context, item) }.getOrDefault(emptyMap())
        }
        ocrText = withContext(Dispatchers.IO) {
            TesseractOcr.readCachedResult(context, Uri.parse(item.uri)).orEmpty()
        }
        ocrError = null
    }


    suspend fun runOcr(engine: String = "tesseract") {
        val isMlKit = engine.equals("google_mlkit", ignoreCase = true) || engine.equals("mlkit", ignoreCase = true)
        if (item.isVideo || ocrBusy || (!isMlKit && ocrLanguages.isEmpty()) || (isMlKit && mlKitLanguages.isEmpty())) return
        ocrBusy = true
        ocrError = null
        ocrText = ""
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val mediaUri = Uri.parse(item.uri)
                val recognized = if (isMlKit) {
                    com.amresalehin.emregallery.ocr.MlKitOcr.recognize(context, mediaUri, mlKitLanguages)
                } else {
                    val model = com.amresalehin.emregallery.ocr.TesseractModel.fromId(ocrModel)
                    TesseractOcr.recognize(context, mediaUri, ocrLanguages.toList(), model)
                }
                runCatching { TesseractOcr.saveCachedResult(context, mediaUri, recognized) }
                recognized
            }
        }
        result.onSuccess { text ->
            ocrText = text.ifBlank { "No text detected in this image." }
        }.onFailure { error ->
            ocrError = error.message ?: "OCR failed. Please try again."
        }
        ocrBusy = false
    }

    fun copyOcrText() {
        if (ocrText.isNotBlank() && ocrText != "No text detected in this image.") {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("Recognized text", ocrText))
        }
    }
}

@Composable
fun rememberViewerDetailsState(
    item: MediaItem,
    defaultOcrLanguages: Set<String>,
    defaultOcrModel: String = "fast",
    defaultMlKitLanguages: Set<String> = setOf("latin")
): ViewerDetailsState {
    val context = LocalContext.current
    val state = remember(item.uri, defaultOcrLanguages, defaultOcrModel, defaultMlKitLanguages) {
        ViewerDetailsState(context, item, defaultOcrLanguages, defaultOcrModel, defaultMlKitLanguages)
    }

    LaunchedEffect(item.uri, defaultOcrLanguages, defaultOcrModel, defaultMlKitLanguages) {
        state.loadData()
    }

    return state
}

private data class FaceStateSnapshot(
    val allFaces: List<DetectedMediaFace>,
    val visibleFaces: List<DetectedMediaFace>,
    val info: FaceClusterStore.MediaIndexedInfo?,
    val groupingRequired: Boolean,
    val groupingError: String?
)

/**
 * Native Android media detail drawer implemented with Material 3 ModalBottomSheet.
 * Features native gestures, partial expansion, flinging, nested scrolling, scrim, and dismissal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerDetailsDrawer(
    item: MediaItem,
    items: List<MediaItem>,
    customAlbums: List<CustomAlbum>,
    customTags: Set<String>,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onAddTo: () -> Unit,
    onToggleBin: () -> Unit,
    onShowMore: () -> Unit,
    onOpenMap: () -> Unit,
    onAddTag: (String) -> Unit,
    isBin: Boolean,
    defaultOcrLanguages: Set<String> = setOf("eng"),
    defaultOcrModel: String = "fast",
    defaultMlKitLanguages: Set<String> = setOf("latin"),
    onOpenPerson: ((personId: String, engineId: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val detailsState = rememberViewerDetailsState(item, defaultOcrLanguages, defaultOcrModel, defaultMlKitLanguages)

    var selectedTab by remember { mutableStateOf(DetailDrawerTab.OVERVIEW) }
    val faceStore = remember(context) { FaceClusterStore(context.applicationContext) }
    var faceReloadKey by remember { mutableIntStateOf(0) }
    val activeEngineId = remember(context) { FaceRecognitionSettings.getActiveEngine(context) }
    var selectedDrawerEngineId by remember(activeEngineId) { mutableStateOf(activeEngineId) }
    val factory = remember(selectedDrawerEngineId) {
        FaceEngineRegistry.getFactory(selectedDrawerEngineId) ?: BuffaloLFaceEngineFactory
    }

    var detectedFaces by remember(item.uri, selectedDrawerEngineId) { mutableStateOf<List<DetectedMediaFace>>(emptyList()) }
    var allDetectedFaces by remember(item.uri, selectedDrawerEngineId) { mutableStateOf<List<DetectedMediaFace>>(emptyList()) }
    var mediaIndexInfo by remember(item.uri, selectedDrawerEngineId) { mutableStateOf<FaceClusterStore.MediaIndexedInfo?>(null) }
    var groupingRequired by remember(item.uri, selectedDrawerEngineId) { mutableStateOf(false) }
    var groupingError by remember(item.uri, selectedDrawerEngineId) { mutableStateOf<String?>(null) }
    var isLoadingIndexInfo by remember(item.uri, selectedDrawerEngineId) { mutableStateOf(true) }

    var singleScanJob by remember(item.uri) { mutableStateOf<Job?>(null) }
    var scanPhase by remember(item.uri) { mutableStateOf<String?>(null) }
    var lastScanError by remember(item.uri) { mutableStateOf<String?>(null) }
    var showErrorDetailsDialog by remember { mutableStateOf(false) }
    var showFaceChooserDialog by remember { mutableStateOf(false) }
    val isProcessingActive by FaceProcessingCoordinator.isProcessingActive.collectAsStateWithLifecycle()
    LaunchedEffect(isProcessingActive) {
        if (isProcessingActive) {
            showFaceChooserDialog = false
        }
    }
    var isInstallingModel by remember { mutableStateOf(false) }
    var modelInstallProgress by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(item.uri, faceReloadKey, selectedDrawerEngineId, factory) {
        suspend fun refreshFaceState() {
            val snapshot = withContext(Dispatchers.IO) {
                val allFaces = faceStore.facesForMedia(item.uri, selectedDrawerEngineId)
                val info = faceStore.getMediaIndexedInfo(item.uri, selectedDrawerEngineId)
                val strategy = factory.clusteringStrategy
                val needsGrouping = strategy?.let {
                    faceStore.isGroupingRequired(selectedDrawerEngineId, factory.modelVersion, it.strategyVersion)
                } ?: false
                val error = strategy?.let {
                    faceStore.groupingFailure(selectedDrawerEngineId, factory.modelVersion, it.strategyVersion)
                }
                val visibleFaces = allFaces
                FaceStateSnapshot(allFaces, visibleFaces, info, needsGrouping, error)
            }
            allDetectedFaces = snapshot.allFaces
            detectedFaces = snapshot.visibleFaces
            mediaIndexInfo = snapshot.info
            groupingRequired = snapshot.groupingRequired
            groupingError = snapshot.groupingError
            isLoadingIndexInfo = false
        }

        kotlinx.coroutines.coroutineScope {
            launch {
                FaceStoreEvents.mediaUpdates.collect { updatedUri ->
                    if (updatedUri.isBlank() || updatedUri == item.uri) refreshFaceState()
                }
            }
            launch {
                FaceStoreEvents.peopleUpdates.collect { refreshFaceState() }
            }
            // Start the initial read only after both update collectors have subscribed.
            refreshFaceState()
        }
    }

    val isModelInstalled = remember(selectedDrawerEngineId, faceReloadKey) {
        factory.isInstalled(context)
    }

    val startScan: (Boolean) -> Unit = { lowThreshold ->
        if (singleScanJob?.isActive == true || item.isVideo || FaceProcessingCoordinator.isBusy(context)) {
            // mutex: active scan or busy
        } else if (!isModelInstalled) {
            // missing dependencies
        } else {
            lastScanError = null
            scanPhase = "Initializing..."
            singleScanJob = coroutineScope.launch {
                val scanHandle = FaceProcessingCoordinator.beginOperation(
                    operationId = "viewer_single_scan_${item.uri}",
                    type = FaceProcessingOperation.SCANNING,
                    engineId = selectedDrawerEngineId,
                    description = "Scanning single photo"
                )
                try {
                    val retryGroupingOnly = groupingError != null || groupingRequired
                    val threshold = if (lowThreshold) FaceRecognitionSettings.LOW_DETECTION_CONFIDENCE_THRESHOLD else FaceRecognitionSettings.detectionConfidence(context, selectedDrawerEngineId)
                    val repo = FaceIndexingRepository(context.applicationContext, factory)
                    repo.indexNewMedia(
                        media = listOf(item),
                        store = faceStore,
                        forceRescanUris = if (retryGroupingOnly) emptySet() else setOf(item.uri),
                        targetUrisOnly = setOf(item.uri),
                        detectionThreshold = threshold,
                        onProgress = { progress ->
                            scanPhase = when {
                                progress.clusteringStage.state == StageState.RUNNING -> "Clustering..."
                                progress.currentItemStatus.isNotBlank() -> progress.currentItemStatus
                                progress.embeddingStage.state == StageState.RUNNING -> "Generating embeddings..."
                                progress.detectionStage.state == StageState.RUNNING -> "Detecting faces..."
                                else -> "Processing..."
                            }
                        }
                    )
                    faceReloadKey++
                } catch (e: CancellationException) {
                    scanPhase = null
                } catch (e: Exception) {
                    val msg = e.localizedMessage ?: e.message ?: "Failed to scan photo"
                    lastScanError = msg
                    withContext(Dispatchers.IO) {
                        faceStore.markMediaFailed(item.uri, selectedDrawerEngineId, msg)
                    }
                    faceReloadKey++
                } finally {
                    scanHandle.close()
                    singleScanJob = null
                    scanPhase = null
                }
            }
        }
    }

    val cancelScan: () -> Unit = {
        singleScanJob?.cancel()
        singleScanJob = null
        scanPhase = null
    }

    val installModel: () -> Unit = {
        if (!isInstallingModel && !FaceProcessingCoordinator.isBusy(context)) {
            isInstallingModel = true
            modelInstallProgress = "Starting download..."
            coroutineScope.launch {
                val installHandle = FaceProcessingCoordinator.beginOperation(
                    operationId = "viewer_model_install",
                    type = FaceProcessingOperation.MODEL_DOWNLOAD,
                    engineId = selectedDrawerEngineId,
                    description = "Installing face model"
                )
                try {
                    val repo = FaceIndexingRepository(context.applicationContext, factory)
                    repo.downloadModel(
                        force = true,
                        onDetailedProgress = { p ->
                            modelInstallProgress = "${p.stage.replaceFirstChar { it.uppercase() }} (${p.percentage}%)"
                        }
                    )
                    faceReloadKey++
                } catch (e: Exception) {
                    lastScanError = "Failed to download model: ${e.localizedMessage ?: e.message}"
                } finally {
                    installHandle.close()
                    isInstallingModel = false
                    modelInstallProgress = null
                }
            }
        }
    }

    val isScanning = singleScanJob?.isActive == true
    val faceUiState = remember(
        isModelInstalled,
        isInstallingModel,
        modelInstallProgress,
        isScanning,
        scanPhase,
        detectedFaces,
        mediaIndexInfo,
        lastScanError,
        isLoadingIndexInfo,
        groupingRequired,
        groupingError,
        allDetectedFaces
    ) {
        val detectedCount = maxOf(mediaIndexInfo?.faceCount ?: 0, allDetectedFaces.size)
        when {
            isScanning -> ViewerFaceUiState(
                state = ViewerFaceState.IN_PROGRESS,
                progressPhase = scanPhase ?: "Scanning photo...",
                engineName = factory.displayName,
                engineId = selectedDrawerEngineId
            )
            isInstallingModel -> ViewerFaceUiState(
                state = ViewerFaceState.IN_PROGRESS,
                progressPhase = modelInstallProgress ?: "Installing model...",
                engineName = factory.displayName,
                engineId = selectedDrawerEngineId
            )
            !isModelInstalled -> ViewerFaceUiState(
                state = ViewerFaceState.MODEL_MISSING,
                engineName = factory.displayName,
                engineId = selectedDrawerEngineId
            )
            isLoadingIndexInfo -> ViewerFaceUiState(
                state = ViewerFaceState.NOT_INDEXED,
                engineName = factory.displayName,
                engineId = selectedDrawerEngineId
            )
            lastScanError != null && detectedFaces.isEmpty() && groupingError == null -> ViewerFaceUiState(
                state = ViewerFaceState.FAILED,
                errorMessage = lastScanError,
                engineName = factory.displayName,
                engineId = selectedDrawerEngineId
            )
            mediaIndexInfo?.status == "failed" -> ViewerFaceUiState(
                state = ViewerFaceState.FAILED,
                errorMessage = mediaIndexInfo?.errorMessage ?: "Face scan failed for this photo",
                engineName = factory.displayName,
                engineId = selectedDrawerEngineId
            )
            groupingError != null -> ViewerFaceUiState(
                state = ViewerFaceState.FAILED,
                errorMessage = "Grouping failed: $groupingError",
                engineName = factory.displayName,
                engineId = selectedDrawerEngineId,
                groupingFailure = true
            )
            detectedFaces.isNotEmpty() -> ViewerFaceUiState(
                state = ViewerFaceState.COMPLETED_FACES_FOUND,
                faces = detectedFaces,
                progressPhase = if (groupingRequired) "$detectedCount faces found · waiting to be grouped" else null,
                engineName = factory.displayName,
                engineId = selectedDrawerEngineId
            )
            groupingRequired && detectedCount > 0 -> ViewerFaceUiState(
                state = ViewerFaceState.WAITING_FOR_GROUPING,
                progressPhase = "$detectedCount faces found · waiting to be grouped",
                engineName = factory.displayName,
                engineId = selectedDrawerEngineId
            )
            detectedCount > 0 -> ViewerFaceUiState(
                state = ViewerFaceState.GROUPING_INCOMPLETE,
                progressPhase = "$detectedCount faces found · waiting for a person group",
                engineName = factory.displayName,
                engineId = selectedDrawerEngineId
            )
            mediaIndexInfo?.status == "indexed" -> ViewerFaceUiState(
                state = ViewerFaceState.COMPLETED_NO_FACES,
                engineName = factory.displayName,
                engineId = selectedDrawerEngineId
            )
            else -> ViewerFaceUiState(
                state = ViewerFaceState.NOT_INDEXED,
                engineName = factory.displayName,
                engineId = selectedDrawerEngineId
            )
        }
    }

    val handleRemoveTag: (String) -> Unit = { tagToRemove ->
        coroutineScope.launch {
            com.amresalehin.emregallery.data.PreferencesRepository(context).removeTagForMedia(item.uri, tagToRemove)
        }
    }

    val overviewListState = rememberLazyListState()
    val actionsListState = rememberLazyListState()

    var showAddLocationDialog by remember { mutableStateOf(false) }
    var showAddTagDialog by remember { mutableStateOf(false) }
    var renamingFace by remember { mutableStateOf<DetectedMediaFace?>(null) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        },
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
        ) {
            DetailDrawerHeader(
                item = item,
                selectedTab = selectedTab,
                onSelectTab = { selectedTab = it }
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (selectedTab) {
                    DetailDrawerTab.OVERVIEW -> {
                        OverviewTabContent(
                            item = item,
                            customAlbums = customAlbums,
                            customTags = customTags,
                            detectedFaces = detectedFaces,
                            metadata = detailsState.metadata,
                            onOpenMap = onOpenMap,
                            onAddToAlbum = onAddTo,
                            onAddTag = { showAddTagDialog = true },
                            onEditLocation = { showAddLocationDialog = true },
                            onRemoveTag = handleRemoveTag,
                            listState = overviewListState,
                            faceUiState = faceUiState,
                            selectedEngineId = selectedDrawerEngineId,
                            onSelectEngine = { newEngine ->
                                if (!FaceProcessingCoordinator.isBusy(context)) {
                                    val switched = FaceRecognitionSettings.setActiveEngine(context, newEngine)
                                    if (switched) {
                                        selectedDrawerEngineId = newEngine
                                        FaceEngineRegistry.setActiveEngine(newEngine)
                                        FaceStoreEvents.notifyAllUpdated()
                                    }
                                }
                            },
                            onScanThisPhoto = { startScan(false) },
                            onCancelScan = cancelScan,
                            onRescanPhoto = { startScan(false) },
                            onRescanLowThreshold = { startScan(true) },
                            onInstallModel = installModel,
                            onRetryScan = { startScan(false) },
                            onViewErrorDetails = { showErrorDetailsDialog = true },
                            onEditTagOrPerson = {
                                if (!FaceProcessingCoordinator.isBusy(context)) {
                                    if (detectedFaces.size == 1) {
                                        val f = detectedFaces.first()
                                        coroutineScope.launch {
                                            if (FaceProcessingCoordinator.isBusy(context)) return@launch
                                            val resolvedPersonId = if (!f.personId.isNullOrBlank()) {
                                                f.personId
                                            } else {
                                                withContext(Dispatchers.IO) {
                                                    FaceProcessingCoordinator.runIfIdle {
                                                        faceStore.ensurePersonForFace(
                                                            mediaUri = f.mediaUri,
                                                            faceIndex = f.faceIndex,
                                                            engineId = f.engineId.ifBlank { selectedDrawerEngineId }
                                                        )
                                                    }
                                                }
                                            }
                                            if (!resolvedPersonId.isNullOrBlank() && !FaceProcessingCoordinator.isBusy(context)) {
                                                onClose()
                                                onOpenPerson?.invoke(resolvedPersonId, f.engineId.ifBlank { selectedDrawerEngineId })
                                            }
                                        }
                                    } else if (detectedFaces.size > 1) {
                                        if (!FaceProcessingCoordinator.isBusy(context)) {
                                            showFaceChooserDialog = true
                                        }
                                    } else {
                                        showAddTagDialog = true
                                    }
                                }
                            },
                            onFaceClick = { face ->
                                if (!FaceProcessingCoordinator.isBusy(context)) {
                                    coroutineScope.launch {
                                        if (FaceProcessingCoordinator.isBusy(context)) return@launch
                                        val resolvedPersonId = if (!face.personId.isNullOrBlank()) {
                                            face.personId
                                        } else {
                                            withContext(Dispatchers.IO) {
                                                FaceProcessingCoordinator.runIfIdle {
                                                    faceStore.ensurePersonForFace(
                                                        mediaUri = face.mediaUri,
                                                        faceIndex = face.faceIndex,
                                                        engineId = face.engineId.ifBlank { selectedDrawerEngineId }
                                                    )
                                                }
                                            }
                                        }
                                        if (!resolvedPersonId.isNullOrBlank() && !FaceProcessingCoordinator.isBusy(context)) {
                                            onClose()
                                            onOpenPerson?.invoke(resolvedPersonId, face.engineId.ifBlank { selectedDrawerEngineId })
                                        }
                                    }
                                }
                            }
                        )
                    }
                    DetailDrawerTab.ACTIONS -> {
                        ActionsTabContent(
                            item = item,
                            customTags = customTags,
                            detectedFaces = detectedFaces,
                            metadata = detailsState.metadata,
                            detailsState = detailsState,
                            onRenameFaceClick = { renamingFace = it },
                            onRescanPhoto = { startScan(false) },
                            onAddTagClick = { showAddTagDialog = true },
                            onRemoveTag = handleRemoveTag,
                            listState = actionsListState
                        )
                    }
                }
            }
        }
    }

    // Add Location Dialog
    if (showAddLocationDialog) {
        var locationInput by remember { mutableStateOf("") }
        var isGeocoding by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showAddLocationDialog = false },
            title = { Text("Add location") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Enter a place name, city, or coordinates:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = locationInput,
                        onValueChange = { locationInput = it },
                        label = { Text("Location name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(
                        onClick = {
                            showAddLocationDialog = false
                            onOpenMap()
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Select on map")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val input = locationInput.trim()
                        if (input.isNotEmpty()) {
                            isGeocoding = true
                            coroutineScope.launch {
                                val geocoder = runCatching {
                                    @Suppress("DEPRECATION")
                                    Geocoder(context, Locale.getDefault()).getFromLocationName(input, 1)
                                }.getOrNull()?.firstOrNull()

                                val point = if (geocoder != null) {
                                    GeoPoint(geocoder.latitude, geocoder.longitude)
                                } else {
                                    val parts = input.split(",").mapNotNull { it.trim().toDoubleOrNull() }
                                    if (parts.size >= 2) GeoPoint(parts[0], parts[1]) else null
                                }

                                withContext(Dispatchers.IO) {
                                    PhotoMapLocationCache(context).save(item.uri, point)
                                }

                                val updated = detailsState.metadata.toMutableMap()
                                updated["location"] = input
                                if (point != null) {
                                    updated["latitude"] = point.latitude.toString()
                                    updated["longitude"] = point.longitude.toString()
                                }
                                detailsState.metadata = updated
                                isGeocoding = false
                                showAddLocationDialog = false
                            }
                        }
                    },
                    enabled = !isGeocoding && locationInput.isNotBlank()
                ) {
                    if (isGeocoding) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Save")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddLocationDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Add Tag Dialog
    if (showAddTagDialog) {
        var tagInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddTagDialog = false },
            title = { Text("Add tag") },
            text = {
                OutlinedTextField(
                    value = tagInput,
                    onValueChange = { tagInput = it },
                    label = { Text("Tag name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = tagInput.trim()
                        if (trimmed.isNotEmpty()) {
                            onAddTag(trimmed)
                            showAddTagDialog = false
                        }
                    },
                    enabled = tagInput.isNotBlank()
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddTagDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Rename / Manage Person Dialog
    val currentRenamingFace = renamingFace
    if (currentRenamingFace != null) {
        val face = currentRenamingFace
        val personId = face.personId.orEmpty()
        var nameInput by remember(face) {
            val initial = if (face.displayName.isBlank() || face.displayName.equals("Unknown", ignoreCase = true)) "" else face.displayName
            mutableStateOf(initial)
        }
        AlertDialog(
            onDismissRequest = { renamingFace = null },
            title = {
                Text(if (face.displayName.isBlank() || face.displayName.equals("Unknown", ignoreCase = true)) "Name this person" else "Person details")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Person name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(
                        onClick = {
                            faceStore.removeFaceFromPerson(face.mediaUri, face.faceIndex, engineId = face.engineId)
                            faceReloadKey++
                            renamingFace = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Remove face from this person")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleanName = nameInput.trim()
                        if (cleanName.isNotEmpty()) {
                            if (personId.isNotBlank()) faceStore.renamePerson(personId, cleanName)
                            faceReloadKey++
                            renamingFace = null
                        }
                    },
                    enabled = nameInput.isNotBlank() && personId.isNotBlank()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingFace = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showErrorDetailsDialog) {
        val err = lastScanError ?: mediaIndexInfo?.errorMessage ?: "Unknown error"
        FaceErrorDetailsDialog(
            errorMessage = err,
            engineName = factory.displayName,
            onDismiss = { showErrorDetailsDialog = false }
        )
    }

    if (showFaceChooserDialog && detectedFaces.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showFaceChooserDialog = false },
            title = { Text("Select person to name") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    detectedFaces.forEach { face ->
                        val isNamed = face.displayName.isNotBlank() && !face.displayName.equals("Unknown", ignoreCase = true)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isProcessingActive) {
                                    if (FaceProcessingCoordinator.isBusy(context)) return@clickable
                                    showFaceChooserDialog = false
                                    coroutineScope.launch {
                                        if (FaceProcessingCoordinator.isBusy(context)) return@launch
                                        val resolvedPersonId = if (!face.personId.isNullOrBlank()) {
                                            face.personId
                                        } else {
                                            withContext(Dispatchers.IO) {
                                                FaceProcessingCoordinator.runIfIdle {
                                                    faceStore.ensurePersonForFace(
                                                        mediaUri = face.mediaUri,
                                                        faceIndex = face.faceIndex,
                                                        engineId = face.engineId.ifBlank { selectedDrawerEngineId }
                                                    )
                                                }
                                            }
                                        }
                                        if (!resolvedPersonId.isNullOrBlank() && !FaceProcessingCoordinator.isBusy(context)) {
                                            onClose()
                                            onOpenPerson?.invoke(resolvedPersonId, face.engineId.ifBlank { selectedDrawerEngineId })
                                        }
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    FaceCropThumbnail(
                                        mediaUri = face.portraitMediaUri ?: face.mediaUri,
                                        faceIndex = face.portraitFaceIndex ?: face.faceIndex,
                                        engineId = face.engineId,
                                        contentDescription = if (isNamed) face.displayName else "Person",
                                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = if (isNamed) face.displayName else "Name this person",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFaceChooserDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
