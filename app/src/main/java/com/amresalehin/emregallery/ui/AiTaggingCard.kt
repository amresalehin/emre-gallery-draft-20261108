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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CloudDownload
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
import com.amresalehin.emregallery.ai.tagging.AiTaggingSettings
import com.amresalehin.emregallery.data.FlorenceModelManager
import com.amresalehin.emregallery.data.FlorenceStatus
import com.amresalehin.emregallery.data.PreferencesRepository
import kotlinx.coroutines.launch
import java.text.NumberFormat
import kotlin.math.roundToInt

data class TaggingModelOption(
    val id: String,
    val displayName: String,
    val isLocalAiModel: Boolean = true
)

@Composable
fun AiTaggingCard(
    totalPhotos: Int,
    taggingState: AiTaggingState,
    prefs: PreferencesRepository,
    countsReady: Boolean,
    cardBackground: Color,
    cardBorder: BorderStroke,
    numberFormat: NumberFormat,
    onStartTagging: (rescanAll: Boolean) -> Unit,
    onStopTagging: () -> Unit,
    modifier: Modifier = Modifier,
    availableFolders: List<FaceFolderInfo> = emptyList(),
    onStartTaggingWithScope: ((rescanAll: Boolean, modelId: String?, folders: Set<String>?) -> Unit)? = null,
    initiallyExpanded: Boolean = false,
    onShowSnackbar: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isExpanded by remember { mutableStateOf(initiallyExpanded) }
    LaunchedEffect(initiallyExpanded) {
        if (initiallyExpanded) {
            isExpanded = true
        }
    }

    val cloudProviders by prefs.cloudProviders.collectAsState(initial = emptyList())
    val activeCloudProvider by prefs.activeCloudProvider.collectAsState(initial = null)
    val allModelStates by FlorenceModelManager.allModelStates.collectAsState()

    // Model selection state
    var selectedModelId by remember {
        mutableStateOf(AiTaggingSettings.getSelectedModel(context))
    }

    // Folder selection state
    var isAllFolders by remember {
        mutableStateOf(AiTaggingSettings.isAllFolders(context))
    }
    var selectedFolders by remember {
        mutableStateOf(AiTaggingSettings.getSelectedFolders(context))
    }

    // Sync installed states on mount
    LaunchedEffect(Unit) {
        FlorenceModelManager.refreshAllStates(context)
    }

    // If legacy smart_vision was selected, migrate to Florence-2
    if (selectedModelId == "smart_vision") {
        selectedModelId = AiTaggingSettings.MODEL_FLORENCE_2
        AiTaggingSettings.setSelectedModel(context, AiTaggingSettings.MODEL_FLORENCE_2)
    }

    // List of available models (The 4 downloaded on-device models + Cloud models)
    val availableModels = remember(activeCloudProvider, cloudProviders) {
        val models = mutableListOf(
            TaggingModelOption(AiTaggingSettings.MODEL_FLORENCE_2, "Florence-2", isLocalAiModel = true),
            TaggingModelOption(AiTaggingSettings.MODEL_GEMMA_4, "Gemma 4", isLocalAiModel = true),
            TaggingModelOption(AiTaggingSettings.MODEL_LFM_2_5, "LFM2.5-VL", isLocalAiModel = true),
            TaggingModelOption(AiTaggingSettings.MODEL_QWEN_3_5, "Qwen3.5", isLocalAiModel = true)
        )
        cloudProviders.forEach { provider ->
            val label = if (provider.modelId.isNotBlank()) {
                "${provider.name} (${provider.modelId})"
            } else {
                provider.name
            }
            models.add(TaggingModelOption(provider.id, label, isLocalAiModel = false))
        }
        if (cloudProviders.isEmpty() && activeCloudProvider != null && activeCloudProvider?.baseUrl?.isNotBlank() == true) {
            val p = activeCloudProvider!!
            val label = if (p.modelId.isNotBlank()) "${p.name} (${p.modelId})" else p.name
            models.add(TaggingModelOption(AiTaggingSettings.MODEL_CLOUD, label, isLocalAiModel = false))
        }
        models
    }

    // Selected model state inspection
    val isCloud = selectedModelId == AiTaggingSettings.MODEL_CLOUD ||
            selectedModelId.startsWith("cloud") ||
            cloudProviders.any { it.id == selectedModelId }
    val currentModelState = allModelStates[selectedModelId] ?: FlorenceModelManager.getModelState(selectedModelId)

    val isModelInstalled = when {
        isCloud -> true
        else -> FlorenceModelManager.isInstalled(context, selectedModelId) || currentModelState.status.isUsable
    }

    val isModelDownloading = !isCloud && currentModelState.status == FlorenceStatus.DOWNLOADING
    val modelDownloadPercent = (currentModelState.progress * 100f).roundToInt().coerceIn(0, 100)

    val isRunning = taggingState.isRunning && !taggingState.isPaused
    val isPaused = taggingState.isRunning && taggingState.isPaused

    // Effective total photos based on chosen folders
    val effectiveTotalPhotos = remember(totalPhotos, isAllFolders, selectedFolders, availableFolders) {
        if (isAllFolders || selectedFolders.isEmpty()) {
            totalPhotos
        } else {
            availableFolders.filter { it.path in selectedFolders }.sumOf { it.photoCount }.let { count ->
                if (count > 0) count else totalPhotos
            }
        }
    }

    val scanProgress = if (effectiveTotalPhotos > 0) (taggingState.processed.toFloat() / effectiveTotalPhotos).coerceIn(0f, 1f) else 0f
    val scanPercent = (scanProgress * 100f).roundToInt()
    val animatedProgress by animateFloatAsState(targetValue = scanProgress, label = "ai_tagging_progress")
    val isCompleted = effectiveTotalPhotos > 0 && taggingState.processed >= effectiveTotalPhotos && !taggingState.isRunning

    var showActionMenu by remember { mutableStateOf(false) }

    fun triggerTagging(rescanAll: Boolean) {
        val folders = if (isAllFolders) emptySet() else selectedFolders
        if (onStartTaggingWithScope != null) {
            onStartTaggingWithScope(rescanAll, selectedModelId, folders)
        } else {
            onStartTagging(rescanAll)
        }
    }

    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = cardBackground),
        border = cardBorder,
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .testTag("ai_tagging_card")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Row: Tag Icon, Title "Tag", Dynamic Corner Button, Expand Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Clickable Left area (Icon + Title "Tag") to toggle expansion
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { isExpanded = !isExpanded },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Circular Halo with Tag in Brackets icon matching skeleton
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(46.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            AiTaggingBracketIcon(tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }

                    Spacer(Modifier.width(16.dp))

                    // Minimalist Title: "Tag" (No explanatory texts)
                    Text(
                        text = "Tag",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }

                // Stop action button when actively scanning
                if (taggingState.isRunning) {
                    IconButton(
                        onClick = onStopTagging,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("tagging_stop_button")
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = "Stop AI Tagging",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }

                // Dynamic Corner Button matching skeleton
                Box {
                    Surface(
                        onClick = {
                            when {
                                isModelDownloading -> {
                                    FlorenceModelManager.cancelDownload(context, selectedModelId)
                                    onShowSnackbar?.invoke("Download cancelled")
                                }
                                !isModelInstalled -> {
                                    scope.launch {
                                        val modelName = availableModels.firstOrNull { it.id == selectedModelId }?.displayName ?: selectedModelId
                                        onShowSnackbar?.invoke("Downloading $modelName model...")
                                        val ok = FlorenceModelManager.downloadAndInstallModel(context, selectedModelId)
                                        if (ok) {
                                            onShowSnackbar?.invoke("$modelName model ready")
                                        } else {
                                            val state = FlorenceModelManager.getModelState(selectedModelId)
                                            onShowSnackbar?.invoke(state.errorMessage ?: "Download failed")
                                        }
                                    }
                                }
                                isRunning -> onStopTagging()
                                isCompleted -> showActionMenu = true
                                else -> triggerTagging(false)
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        color = when {
                            isModelDownloading -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f)
                            isRunning -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            isPaused -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                            !isModelInstalled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                            isCompleted -> MaterialTheme.colorScheme.surfaceContainerHigh
                            else -> MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        border = BorderStroke(
                            1.dp,
                            when {
                                isModelDownloading -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
                                isRunning -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                isPaused -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
                                !isModelInstalled -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                            }
                        ),
                        modifier = Modifier
                            .height(36.dp)
                            .defaultMinSize(minWidth = 84.dp)
                            .testTag("tagging_corner_box_action")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = if (!isModelInstalled && !isModelDownloading) 12.dp else 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            when {
                                isModelDownloading -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(13.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "$modelDownloadPercent%",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    )
                                }
                                !isModelInstalled -> {
                                    // Cloud download icon as shown in skeleton
                                    Icon(
                                        imageVector = Icons.Outlined.CloudDownload,
                                        contentDescription = "Download model",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                                isRunning -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(13.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "$scanPercent%",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                }
                                isCompleted -> {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = "Done",
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
                                        text = if (taggingState.processed > 0) "Start ($scanPercent%)" else "Start",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // Dropdown menu for additional actions (Scan Untagged, Re-tag All)
                    DropdownMenu(
                        expanded = showActionMenu,
                        onDismissRequest = { showActionMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Scan Untagged Photos") },
                            onClick = {
                                showActionMenu = false
                                triggerTagging(false)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Re-tag Entire Library") },
                            onClick = {
                                showActionMenu = false
                                triggerTagging(true)
                            }
                        )
                    }
                }

                Spacer(Modifier.width(6.dp))

                // Expand / Collapse Chevron
                IconButton(
                    onClick = { isExpanded = !isExpanded },
                    modifier = Modifier
                        .size(34.dp)
                        .testTag("tagging_expand_toggle")
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse Tagging" else "Expand Tagging",
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Divider with progress indicator
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
                if (isModelDownloading) {
                    LinearProgressIndicator(
                        progress = { (modelDownloadPercent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.secondary,
                        trackColor = Color.Transparent
                    )
                } else if (isRunning || scanProgress > 0f) {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Transparent
                    )
                }
            }

            // Expanded content: Model section + Folder section (NO explanatory texts)
            if (isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp)
                ) {
                    // SECTION 1: Model (Title only, no explanatory text)
                    Text(
                        text = "Model",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )

                    Spacer(Modifier.height(8.dp))

                    // Horizontal Pill Row of Available Models
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .testTag("tagging_models_horizontal_row"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        availableModels.forEach { model ->
                            val isSelected = selectedModelId == model.id
                            ModelPill(
                                label = model.displayName,
                                isSelected = isSelected,
                                onClick = {
                                    selectedModelId = model.id
                                    AiTaggingSettings.setSelectedModel(context, model.id)
                                    val matchingCloud = cloudProviders.firstOrNull { it.id == model.id }
                                    if (matchingCloud != null) {
                                        scope.launch { prefs.setActiveCloudProvider(matchingCloud.id) }
                                    }
                                },
                                testTag = "tagging_model_pill_${model.id}"
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // SECTION 2: Folder (Title only, no explanatory text)
                    Text(
                        text = "Folder",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )

                    Spacer(Modifier.height(8.dp))

                    // Horizontal Pill Row of Folders (All folders + specific device folders)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .testTag("tagging_folders_horizontal_row"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // First Pill: "All folders" (selected by default with dark pill fill)
                        FolderPill(
                            label = "All folders",
                            isSelected = isAllFolders,
                            onClick = {
                                isAllFolders = true
                                selectedFolders = emptySet()
                                AiTaggingSettings.selectAllFolders(context)
                            },
                            testTag = "tagging_folder_all_pill"
                        )

                        // Real device folders
                        availableFolders.forEach { folder ->
                            val isSelected = !isAllFolders && folder.path in selectedFolders
                            FolderPill(
                                label = folder.name,
                                isSelected = isSelected,
                                onClick = {
                                    val newSelected = selectedFolders.toMutableSet()
                                    if (isAllFolders) {
                                        isAllFolders = false
                                        newSelected.clear()
                                        newSelected.add(folder.path)
                                    } else {
                                        if (newSelected.contains(folder.path)) {
                                            newSelected.remove(folder.path)
                                        } else {
                                            newSelected.add(folder.path)
                                        }
                                        if (newSelected.isEmpty()) {
                                            isAllFolders = true
                                        }
                                    }
                                    selectedFolders = newSelected
                                    AiTaggingSettings.setAllFolders(context, isAllFolders)
                                    AiTaggingSettings.setSelectedFolders(context, newSelected)
                                },
                                testTag = "tagging_folder_pill_${folder.name}"
                            )
                        }
                    }

                    // Scan Progress / Current Item Live Feedback when running
                    if (isRunning) {
                        Spacer(Modifier.height(14.dp))
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Scanning photos...",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "${taggingState.processed} / $effectiveTotalPhotos photos",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (taggingState.currentItemName.isNotBlank()) {
                                    Text(
                                        text = taggingState.currentItemName,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                if (taggingState.currentItemStatus.isNotBlank()) {
                                    Text(
                                        text = "Tags: ${taggingState.currentItemStatus}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }

                    // Quick Action Buttons Row: "Tag Untagged" | "Re-tag All"
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                triggerTagging(false)
                                onShowSnackbar?.invoke("Started AI tagging for untagged photos")
                            },
                            enabled = !isRunning && !isModelDownloading,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 44.dp)
                                .testTag("tagging_start_untagged_button")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Tag Untagged")
                        }

                        OutlinedButton(
                            onClick = {
                                triggerTagging(true)
                                onShowSnackbar?.invoke("Re-tagging photos in background")
                            },
                            enabled = !isRunning && !isModelDownloading,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 44.dp)
                                .testTag("tagging_rescan_all_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Re-tag All")
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

/**
 * Model selection pill matching the skeleton aesthetic.
 * Selected item is filled with solid dark background; unselected items have tonal/outline background.
 */
@Composable
private fun ModelPill(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (isSelected) Color(0xFF161A20) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(
            1.dp,
            if (isSelected) Color(0xFF161A20) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        ),
        modifier = Modifier
            .height(38.dp)
            .defaultMinSize(minWidth = 76.dp)
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
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

/**
 * Folder selection pill matching the skeleton aesthetic.
 */
@Composable
private fun FolderPill(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
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

/**
 * Iconic bracketed tag icon matching the skeleton diagram top-left icon.
 */
@Composable
private fun AiTaggingBracketIcon(modifier: Modifier = Modifier, tint: Color = Color(0xFF181C22)) {
    Box(
        modifier = modifier.size(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 1.6.dp.toPx()
            val cornerLength = 4.5.dp.toPx()
            val w = size.width
            val h = size.height

            // Top-left bracket
            drawLine(tint, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(cornerLength, 0f), strokeWidth)
            drawLine(tint, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(0f, cornerLength), strokeWidth)

            // Top-right bracket
            drawLine(tint, androidx.compose.ui.geometry.Offset(w, 0f), androidx.compose.ui.geometry.Offset(w - cornerLength, 0f), strokeWidth)
            drawLine(tint, androidx.compose.ui.geometry.Offset(w, 0f), androidx.compose.ui.geometry.Offset(w, cornerLength), strokeWidth)

            // Bottom-left bracket
            drawLine(tint, androidx.compose.ui.geometry.Offset(0f, h), androidx.compose.ui.geometry.Offset(cornerLength, h), strokeWidth)
            drawLine(tint, androidx.compose.ui.geometry.Offset(0f, h), androidx.compose.ui.geometry.Offset(0f, h - cornerLength), strokeWidth)

            // Bottom-right bracket
            drawLine(tint, androidx.compose.ui.geometry.Offset(w, h), androidx.compose.ui.geometry.Offset(w - cornerLength, h), strokeWidth)
            drawLine(tint, androidx.compose.ui.geometry.Offset(w, h), androidx.compose.ui.geometry.Offset(w, h - cornerLength), strokeWidth)
        }
        Icon(
            imageVector = Icons.Default.Sell,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = tint
        )
    }
}
