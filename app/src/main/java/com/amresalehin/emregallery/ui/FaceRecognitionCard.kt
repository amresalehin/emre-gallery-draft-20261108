package com.amresalehin.emregallery.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amresalehin.emregallery.face.BuffaloLFaceEngine
import com.amresalehin.emregallery.face.EnteFaceConstants
import com.amresalehin.emregallery.face.FaceRecognitionSettings
import com.amresalehin.emregallery.face.StageState
import java.text.NumberFormat
import kotlin.math.roundToInt

@Composable
fun FaceRecognitionCard(
    totalPhotos: Int,
    faceFindingState: FaceFindingState,
    engineState: EngineState,
    modelDownloadState: ModelDownloadState,
    clusteringState: FaceClusteringState,
    enteFaceFindingState: FaceFindingState = FaceFindingState(),
    enteModelDownloadState: ModelDownloadState = ModelDownloadState(),
    enteClusteringState: FaceClusteringState = FaceClusteringState(),
    countsReady: Boolean,
    cardBackground: Color,
    cardBorder: BorderStroke,
    numberFormat: NumberFormat,
    onConfidenceChange: (Float, String) -> Unit,
    onMinGroupFacesChange: (Int, String) -> Unit,
    onClusterDistanceChange: (Float, String) -> Unit,
    onToggleFaceFinding: (String) -> Unit,
    onStopFaceFinding: (String) -> Unit,
    onRescanRequested: (String) -> Unit,
    onDownloadModel: (force: Boolean, engineId: String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onClusterFaces: (Float, String) -> Unit,
    onResetClusters: (String) -> Unit,
    onComparePipelines: (() -> Unit)? = null,
    onRetryFailedFaces: ((String) -> Unit)? = null,
    availableFolders: List<FaceFolderInfo> = emptyList(),
    onToggleFolder: (String) -> Unit = {},
    onSelectAllFolders: () -> Unit = {},
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false
) {
    var isExpanded by remember { mutableStateOf(initiallyExpanded) }
    LaunchedEffect(initiallyExpanded) {
        if (initiallyExpanded) {
            isExpanded = true
        }
    }
    val context = LocalContext.current
    val activeEngineInSettings = FaceRecognitionSettings.getActiveEngine(context)
    val isFaceRecognitionEnabled = FaceRecognitionSettings.preferences(context)
        .getBoolean("face_recognition_enabled", true) && activeEngineInSettings != "disabled"

    var selectedEngineId by remember {
        mutableStateOf(
            when {
                activeEngineInSettings == EnteFaceConstants.ENGINE_ID -> EnteFaceConstants.ENGINE_ID
                activeEngineInSettings == BuffaloLFaceEngine.ENGINE_ID -> BuffaloLFaceEngine.ENGINE_ID
                engineState.isBuffaloLInstalled -> BuffaloLFaceEngine.ENGINE_ID
                engineState.isEnteInstalled -> EnteFaceConstants.ENGINE_ID
                else -> BuffaloLFaceEngine.ENGINE_ID
            }
        )
    }

    val selectedOption = when {
        !isFaceRecognitionEnabled -> "disable"
        selectedEngineId == EnteFaceConstants.ENGINE_ID -> "yolo"
        else -> "buffalo"
    }

    val isEnte = selectedEngineId == EnteFaceConstants.ENGINE_ID
    val currentFindingState = if (isEnte) enteFaceFindingState else faceFindingState
    val currentDownloadState = if (isEnte) enteModelDownloadState else modelDownloadState
    val currentClusteringState = if (isEnte) enteClusteringState else clusteringState

    val isModelInstalled = when (selectedEngineId) {
        EnteFaceConstants.ENGINE_ID -> engineState.isEnteInstalled
        BuffaloLFaceEngine.ENGINE_ID -> engineState.isBuffaloLInstalled
        else -> false
    }

    val isDownloading = currentDownloadState.isDownloading
    val isClusteringActive = currentClusteringState.isClusteringRunning || currentFindingState.clusteringStage.state == StageState.RUNNING
    val isScanningRunning = currentFindingState.isRunning && !currentFindingState.isPaused && !isClusteringActive
    val isScanningPaused = currentFindingState.isRunning && currentFindingState.isPaused

    val downloadPercent = if (currentDownloadState.totalBytes > 0) {
        ((currentDownloadState.bytesReceived.toFloat() / currentDownloadState.totalBytes) * 100f).roundToInt().coerceIn(0, 100)
    } else if (currentDownloadState.bytesReceived > 0) {
        val estimatedTotal = if (isEnte) 25L * 1024 * 1024 else 288L * 1024 * 1024
        ((currentDownloadState.bytesReceived.toFloat() / estimatedTotal) * 100f).roundToInt().coerceIn(0, 99)
    } else 0

    val effectiveTotalPhotos = if (currentFindingState.scopedTotalPhotos > 0 || !currentFindingState.isAllFolders) {
        currentFindingState.scopedTotalPhotos
    } else totalPhotos

    val scanProgress = if (effectiveTotalPhotos > 0) (currentFindingState.processed.toFloat() / effectiveTotalPhotos).coerceIn(0f, 1f) else 0f
    val scanPercent = (scanProgress * 100f).roundToInt()
    val animatedScanProgress by animateFloatAsState(targetValue = scanProgress, label = "face_scan_progress")

    val isCompleted = isModelInstalled && !currentFindingState.isRunning && !isClusteringActive && effectiveTotalPhotos > 0 && currentFindingState.processed >= effectiveTotalPhotos

    var showActionMenu by remember { mutableStateOf(false) }

    fun selectOption(option: String) {
        when (option) {
            "disable" -> {
                if (currentFindingState.isRunning) {
                    onStopFaceFinding(selectedEngineId)
                }
                if (currentDownloadState.isDownloading) {
                    onCancelDownload(selectedEngineId)
                }
                FaceRecognitionSettings.preferences(context).edit()
                    .putBoolean("face_recognition_enabled", false)
                    .putString(FaceRecognitionSettings.KEY_ACTIVE_ENGINE, "disabled")
                    .apply()
            }
            "yolo" -> {
                if (currentDownloadState.isDownloading && selectedEngineId != EnteFaceConstants.ENGINE_ID) {
                    onCancelDownload(selectedEngineId)
                }
                selectedEngineId = EnteFaceConstants.ENGINE_ID
                FaceRecognitionSettings.preferences(context).edit()
                    .putBoolean("face_recognition_enabled", true)
                    .apply()
                FaceRecognitionSettings.setActiveEngine(context, EnteFaceConstants.ENGINE_ID)
            }
            "buffalo" -> {
                if (currentDownloadState.isDownloading && selectedEngineId != BuffaloLFaceEngine.ENGINE_ID) {
                    onCancelDownload(selectedEngineId)
                }
                selectedEngineId = BuffaloLFaceEngine.ENGINE_ID
                FaceRecognitionSettings.preferences(context).edit()
                    .putBoolean("face_recognition_enabled", true)
                    .apply()
                FaceRecognitionSettings.setActiveEngine(context, BuffaloLFaceEngine.ENGINE_ID)
            }
        }
    }

    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = cardBackground),
        border = cardBorder,
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .testTag("face_recognition_card")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Row: Face Icon, Title, Dynamic Corner Box, Expand Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Clickable Left area (Icon + Title) to toggle expansion
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { isExpanded = !isExpanded },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Circular Halo with FaceRecognitionIcon
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(46.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            FaceRecognitionIcon(tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }

                    Spacer(Modifier.width(16.dp))

                    Text(
                        text = "Face Recognition",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }

                // Stop action button when actively scanning
                if (currentFindingState.isRunning) {
                    IconButton(
                        onClick = { onStopFaceFinding(selectedEngineId) },
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("face_stop_button")
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = "Stop Face Recognition",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }

                // Dynamic Corner Box
                Box {
                    Surface(
                        onClick = {
                            when {
                                isDownloading -> onCancelDownload(selectedEngineId)
                                !isModelInstalled && selectedOption != "disable" -> onDownloadModel(true, selectedEngineId)
                                isCompleted -> showActionMenu = true
                                isClusteringActive -> { /* clustering active */ }
                                isScanningRunning || isScanningPaused -> onToggleFaceFinding(selectedEngineId)
                                selectedOption != "disable" -> onToggleFaceFinding(selectedEngineId)
                            }
                        },
                        enabled = selectedOption != "disable",
                        shape = RoundedCornerShape(14.dp),
                        color = when {
                            isDownloading -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                            isScanningRunning || isClusteringActive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            isScanningPaused -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                            !isModelInstalled && selectedOption != "disable" -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            isCompleted -> MaterialTheme.colorScheme.surfaceContainerHigh
                            else -> MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        border = BorderStroke(
                            1.dp,
                            when {
                                isDownloading -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
                                isScanningRunning || isClusteringActive -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                isScanningPaused -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
                                !isModelInstalled && selectedOption != "disable" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            }
                        ),
                        modifier = Modifier
                            .height(36.dp)
                            .defaultMinSize(minWidth = 84.dp)
                            .testTag("face_corner_box_action")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            when {
                                selectedOption == "disable" -> {
                                    Text(
                                        text = "Disabled",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.Normal,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                    )
                                }
                                isDownloading -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(13.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "$downloadPercent%",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    )
                                }
                                !isModelInstalled -> {
                                    Icon(
                                        Icons.Default.CloudDownload,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        text = "Download",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                }
                                isClusteringActive -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(13.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "Clustering…",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                }
                                isScanningRunning -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(13.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "$scanPercent% · Pause",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                }
                                isScanningPaused -> {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        text = "$scanPercent% · Resume",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    )
                                }
                                isCompleted -> {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        text = "100% · Done",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    )
                                }
                                else -> {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        text = if (currentFindingState.processed > 0) "Start ($scanPercent%)" else "Start",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // Dropdown menu for additional actions (Rescan, Recluster, Compare)
                    DropdownMenu(
                        expanded = showActionMenu,
                        onDismissRequest = { showActionMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Rescan All Photos") },
                            onClick = {
                                showActionMenu = false
                                onRescanRequested(selectedEngineId)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Re-cluster Faces") },
                            onClick = {
                                showActionMenu = false
                                onClusterFaces(FaceRecognitionSettings.recognitionDistance(context, selectedEngineId), selectedEngineId)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Reset Clusters") },
                            onClick = {
                                showActionMenu = false
                                onResetClusters(selectedEngineId)
                            }
                        )
                        if (onComparePipelines != null) {
                            DropdownMenuItem(
                                text = { Text("Compare Pipelines") },
                                onClick = {
                                    showActionMenu = false
                                    onComparePipelines()
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.width(6.dp))

                IconButton(
                    onClick = { isExpanded = !isExpanded },
                    modifier = Modifier
                        .size(34.dp)
                        .testTag("face_expand_toggle")
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse Face Recognition" else "Expand Face Recognition",
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Minimalist Divider with integrated Progress Indicator
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
            ) {
                if (isExpanded) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        thickness = 1.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (isDownloading) {
                    LinearProgressIndicator(
                        progress = { (downloadPercent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.secondary,
                        trackColor = Color.Transparent
                    )
                } else if (scanProgress > 0f) {
                    LinearProgressIndicator(
                        progress = { animatedScanProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Transparent
                    )
                }
            }

            if (isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp)
                ) {
                    // Engine Label (No explanatory text)
                    Text(
                        text = "Engine",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )

                    Spacer(Modifier.height(8.dp))

                    // Engine Segmented Buttons: Disable | Yolo V5 | Buffalo L
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FaceSegmentOption(
                                text = "Disable",
                                isSelected = selectedOption == "disable",
                                onClick = { selectOption("disable") },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("face_engine_segment_disable")
                            )

                            VerticalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                modifier = Modifier.fillMaxHeight(0.6f).width(1.dp)
                            )

                            FaceSegmentOption(
                                text = "Yolo V5",
                                isSelected = selectedOption == "yolo",
                                onClick = { selectOption("yolo") },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("face_engine_segment_yolo")
                            )

                            VerticalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                modifier = Modifier.fillMaxHeight(0.6f).width(1.dp)
                            )

                            FaceSegmentOption(
                                text = "Buffalo L",
                                isSelected = selectedOption == "buffalo",
                                onClick = { selectOption("buffalo") },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("face_engine_segment_buffalo")
                            )
                        }
                    }

                    if (selectedOption != "disable") {
                        Spacer(Modifier.height(16.dp))

                        // Folders Header Row: Icon + "Folders" on left, "All folders" pill on right
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Folders",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                )
                            }

                            // "All folders" toggle pill
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (currentFindingState.isAllFolders) Color(0xFF161A20) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                border = BorderStroke(
                                    1.dp,
                                    if (currentFindingState.isAllFolders) Color(0xFF161A20) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                ),
                                modifier = Modifier
                                    .height(36.dp)
                                    .clickable(onClick = onSelectAllFolders)
                                    .testTag("face_folders_all_pill")
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 14.dp)
                                ) {
                                    Text(
                                        text = "All folders",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 13.sp,
                                            fontWeight = if (currentFindingState.isAllFolders) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (currentFindingState.isAllFolders) Color.White else MaterialTheme.colorScheme.onSurface
                                        )
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // Horizontally scrollable row of real device folders
                        if (availableFolders.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .testTag("face_folders_horizontal_row"),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                availableFolders.forEach { folder ->
                                    val isSelected = !currentFindingState.isAllFolders && folder.path in currentFindingState.selectedFolders
                                    FolderChip(
                                        label = folder.name,
                                        isSelected = isSelected,
                                        onClick = { onToggleFolder(folder.path) },
                                        testTag = "face_folder_chip_${folder.name}"
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun FolderChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) Color(0xFF161A20) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(
            1.dp,
            if (isSelected) Color(0xFF161A20) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        ),
        modifier = Modifier
            .height(38.dp)
            .defaultMinSize(minWidth = 72.dp)
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 14.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun FaceSegmentOption(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isSelected) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF161A20),
            modifier = modifier.fillMaxHeight()
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                )
            }
        }
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .fillMaxHeight()
                .clip(RoundedCornerShape(20.dp))
                .clickable(onClick = onClick)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    }
}

@Composable
private fun FaceRecognitionIcon(modifier: Modifier = Modifier, tint: Color = Color(0xFF181C22)) {
    Box(
        modifier = modifier.size(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 1.6.dp.toPx()
            val cornerLength = 4.5.dp.toPx()
            val w = size.width
            val h = size.height

            // Top-left
            drawLine(tint, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(cornerLength, 0f), strokeWidth)
            drawLine(tint, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(0f, cornerLength), strokeWidth)

            // Top-right
            drawLine(tint, androidx.compose.ui.geometry.Offset(w, 0f), androidx.compose.ui.geometry.Offset(w - cornerLength, 0f), strokeWidth)
            drawLine(tint, androidx.compose.ui.geometry.Offset(w, 0f), androidx.compose.ui.geometry.Offset(w, cornerLength), strokeWidth)

            // Bottom-left
            drawLine(tint, androidx.compose.ui.geometry.Offset(0f, h), androidx.compose.ui.geometry.Offset(cornerLength, h), strokeWidth)
            drawLine(tint, androidx.compose.ui.geometry.Offset(0f, h), androidx.compose.ui.geometry.Offset(0f, h - cornerLength), strokeWidth)

            // Bottom-right
            drawLine(tint, androidx.compose.ui.geometry.Offset(w, h), androidx.compose.ui.geometry.Offset(w - cornerLength, h), strokeWidth)
            drawLine(tint, androidx.compose.ui.geometry.Offset(w, h), androidx.compose.ui.geometry.Offset(w, h - cornerLength), strokeWidth)
        }
        Icon(
            imageVector = Icons.Default.Face,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = tint
        )
    }
}
