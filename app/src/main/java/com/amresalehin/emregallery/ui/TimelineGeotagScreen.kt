package com.amresalehin.emregallery.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.amresalehin.emregallery.data.MediaItem
import com.amresalehin.emregallery.geotag.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineGeotagScreen(
    media: List<MediaItem>,
    topContentPadding: Dp,
    bottomContentPadding: Dp,
    onBack: () -> Unit,
    onOpenPhotoMap: () -> Unit,
    viewModel: TimelineGeotagViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showHelpDialog by remember { mutableStateOf(false) }
    var showAlbumSelectDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Intercept back key to return to previous screen
    BackHandler {
        if (uiState.progress.isRunning) {
            viewModel.stopGeotagging()
        }
        onBack()
    }

    // File picker for Google Maps Timeline JSON
    val timelineFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.selectTimelineFile(uri)
        }
    }

    // Directory picker (SAF folder tree)
    val directoryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            viewModel.selectDirectory(treeUri)
            viewModel.scanCandidates(media)
        }
    }

    // GPX export launcher
    val gpxExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { destUri: Uri? ->
        if (destUri != null) {
            runCatching {
                context.contentResolver.openOutputStream(destUri)?.use { out ->
                    out.write(viewModel.getExportableGpxContent().toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    // Distinct albums from gallery media
    val availableAlbums = remember(media) {
        media.filter { !it.isVideo && it.relativePath.isNotBlank() }
            .map { it.relativePath.trim('/') }
            .distinct()
            .sorted()
    }

    // Auto-scan candidates on initial load if empty
    LaunchedEffect(uiState.sourceType, media) {
        if (uiState.candidates.isEmpty() && !uiState.isScanningCandidates) {
            viewModel.scanCandidates(media)
        }
    }

    val filteredResults = remember(uiState.results, uiState.activeFilter) {
        when (uiState.activeFilter) {
            GeotagResultFilter.ALL -> uiState.results
            GeotagResultFilter.MATCHED -> uiState.results.filter { it.status == GeotagStatus.MATCHED }
            GeotagResultFilter.SKIPPED_ALREADY_TAGGED -> uiState.results.filter { it.status == GeotagStatus.ALREADY_GEOTAGGED }
            GeotagResultFilter.UNMATCHED -> uiState.results.filter { it.status == GeotagStatus.UNMATCHED }
            GeotagResultFilter.ERRORS -> uiState.results.filter { it.status == GeotagStatus.ERROR || it.status == GeotagStatus.NO_TIMESTAMP }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Timeline Geotag",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Match photos with Google Maps Timeline",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (uiState.progress.isRunning) viewModel.stopGeotagging()
                            onBack()
                        },
                        modifier = Modifier.testTag("timeline_geotag_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showHelpDialog = true },
                        modifier = Modifier.testTag("timeline_geotag_help_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "How to export Timeline")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .testTag("timeline_geotag_scroll_content"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = bottomContentPadding + 24.dp)
        ) {
            // STEP 1: Google Maps Timeline JSON Card
            item {
                TimelineFileCard(
                    fileName = uiState.timelineFileName,
                    summary = uiState.timelineSummary,
                    isParsing = uiState.isParsingTimeline,
                    errorMessage = uiState.parseError,
                    onPickFile = {
                        timelineFilePicker.launch(arrayOf("application/json", "text/*", "*/*"))
                    }
                )
            }

            // STEP 2: Photos Source Selection Card
            item {
                ImageSourceCard(
                    sourceType = uiState.sourceType,
                    selectedDirName = uiState.selectedDirectoryDisplayName,
                    selectedAlbumName = uiState.selectedAlbumName,
                    candidatesCount = uiState.candidates.size,
                    isScanning = uiState.isScanningCandidates,
                    onPickDirectory = { directoryPicker.launch(null) },
                    onSelectAllPhotos = {
                        viewModel.setSourceType(GeotagSourceType.ALL_GALLERY_PHOTOS)
                        viewModel.scanCandidates(media)
                    },
                    onOpenAlbumDialog = { showAlbumSelectDialog = true },
                    onRescan = { viewModel.scanCandidates(media) }
                )
            }

            // STEP 3: Matching Settings Card
            item {
                MatchingSettingsCard(
                    config = uiState.config,
                    onOpenSettings = { showSettingsDialog = true }
                )
            }

            // STEP 4: Execution / Progress Card
            item {
                ExecutionProgressCard(
                    progress = uiState.progress,
                    canStart = uiState.timelineSummary != null && (uiState.timelineSummary?.totalPoints ?: 0) > 0 && uiState.candidates.isNotEmpty(),
                    hasTimeline = uiState.timelineSummary != null && (uiState.timelineSummary?.totalPoints ?: 0) > 0,
                    hasCandidates = uiState.candidates.isNotEmpty(),
                    onStart = { viewModel.startGeotagging(media) },
                    onStop = { viewModel.stopGeotagging() },
                    onOpenPhotoMap = onOpenPhotoMap,
                    onExportGpx = { gpxExportLauncher.launch("timeline_track.gpx") }
                )
            }

            // Results Section
            if (uiState.results.isNotEmpty()) {
                item {
                    ResultsHeader(
                        totalResults = uiState.results.size,
                        activeFilter = uiState.activeFilter,
                        onFilterChange = { viewModel.setFilter(it) }
                    )
                }

                items(filteredResults, key = { it.candidate.uriString }) { item ->
                    GeotagResultItemRow(item = item)
                }
            }
        }
    }

    // Help Dialog explaining Google Takeout and Timeline export
    if (showHelpDialog) {
        TimelineHelpDialog(onDismiss = { showHelpDialog = false })
    }

    // Album Selection Dialog
    if (showAlbumSelectDialog) {
        AlbumSelectDialog(
            albums = availableAlbums,
            selected = uiState.selectedAlbumName,
            onSelect = { album ->
                viewModel.setSelectedAlbum(album)
                viewModel.scanCandidates(media)
                showAlbumSelectDialog = false
            },
            onDismiss = { showAlbumSelectDialog = false }
        )
    }

    // Detailed Settings Dialog
    if (showSettingsDialog) {
        GeotagSettingsDialog(
            config = uiState.config,
            onSave = { updated ->
                viewModel.updateConfig { updated }
                showSettingsDialog = false
            },
            onDismiss = { showSettingsDialog = false }
        )
    }
}

@Composable
private fun TimelineFileCard(
    fileName: String?,
    summary: TimelineParseSummary?,
    isParsing: Boolean,
    errorMessage: String?,
    onPickFile: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("timeline_file_card"),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Timeline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "1. Google Maps Timeline JSON",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    Text(
                        text = "Takeout Records.json or Timeline.json",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(
                    onClick = onPickFile,
                    enabled = !isParsing,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .height(36.dp)
                        .defaultMinSize(minWidth = 84.dp)
                        .testTag("pick_timeline_file_button")
                ) {
                    Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (fileName == null) "Select JSON" else "Change",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (isParsing) {
                Spacer(Modifier.height(14.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Parsing timeline locations...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (errorMessage != null) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            if (summary != null && summary.totalPoints > 0) {
                Spacer(Modifier.height(14.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "${summary.fileName} (${summary.formatDetected})",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Loaded ${summary.totalPoints} location points",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (summary.earliestTimestampMillis != null && summary.latestTimestampMillis != null) {
                            val dtf = DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.getDefault())
                            val startStr = dtf.format(Instant.ofEpochMilli(summary.earliestTimestampMillis).atZone(ZoneId.systemDefault()))
                            val endStr = dtf.format(Instant.ofEpochMilli(summary.latestTimestampMillis).atZone(ZoneId.systemDefault()))
                            Text(
                                text = "Span: $startStr to $endStr",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageSourceCard(
    sourceType: GeotagSourceType,
    selectedDirName: String?,
    selectedAlbumName: String?,
    candidatesCount: Int,
    isScanning: Boolean,
    onPickDirectory: () -> Unit,
    onSelectAllPhotos: () -> Unit,
    onOpenAlbumDialog: () -> Unit,
    onRescan: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("image_source_card"),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "2. Photos Directory / Source",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    Text(
                        text = "Pick camera folder, album, or all photos",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Source Mode Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = sourceType == GeotagSourceType.DIRECTORY_PICKER,
                    onClick = onPickDirectory,
                    label = { Text("Pick Folder") },
                    leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.weight(1f).testTag("source_pick_folder_chip")
                )
                FilterChip(
                    selected = sourceType == GeotagSourceType.DEVICE_ALBUM,
                    onClick = onOpenAlbumDialog,
                    label = { Text("Album") },
                    leadingIcon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.weight(1f).testTag("source_album_chip")
                )
                FilterChip(
                    selected = sourceType == GeotagSourceType.ALL_GALLERY_PHOTOS,
                    onClick = onSelectAllPhotos,
                    label = { Text("All Photos") },
                    leadingIcon = { Icon(Icons.Default.Collections, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.weight(1f).testTag("source_all_photos_chip")
                )
            }

            Spacer(Modifier.height(12.dp))

            // Active Source Details
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        val label = when (sourceType) {
                            GeotagSourceType.DIRECTORY_PICKER -> selectedDirName ?: "No directory picked yet"
                            GeotagSourceType.DEVICE_ALBUM -> selectedAlbumName?.let { "Album: $it" } ?: "Select an album"
                            GeotagSourceType.ALL_GALLERY_PHOTOS -> "All unarchived gallery photos"
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (isScanning) "Scanning photos..." else "$candidatesCount photos found",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onRescan, enabled = !isScanning) {
                        Icon(Icons.Default.Refresh, contentDescription = "Rescan")
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchingSettingsCard(
    config: GeotagConfig,
    onOpenSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("matching_settings_card"),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "3. Matching Settings",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    Text(
                        text = "Clock drift correction and interpolation",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("open_matching_settings_button")) {
                    Icon(Icons.Default.Settings, contentDescription = "Edit settings")
                }
            }

            Spacer(Modifier.height(10.dp))

            // Notice about already geotagged images being skipped
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Already geotagged images will be skipped automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Summary chips of settings
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = onOpenSettings,
                    label = { Text("Max: ${config.maxTimeDeltaSeconds / 60}m") },
                    leadingIcon = { Icon(Icons.Default.HourglassEmpty, contentDescription = null, modifier = Modifier.size(14.dp)) }
                )
                if (config.interpolateBetweenPoints) {
                    AssistChip(
                        onClick = onOpenSettings,
                        label = { Text("Interpolate") },
                        leadingIcon = { Icon(Icons.Default.AutoGraph, contentDescription = null, modifier = Modifier.size(14.dp)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ExecutionProgressCard(
    progress: GeotagJobProgress,
    canStart: Boolean,
    hasTimeline: Boolean,
    hasCandidates: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenPhotoMap: () -> Unit,
    onExportGpx: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("execution_progress_card"),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (progress.isCompleted) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        ),
        border = BorderStroke(
            1.dp,
            if (progress.isCompleted) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            if (!progress.isRunning && !progress.isCompleted) {
                Button(
                    onClick = onStart,
                    enabled = canStart,
                    modifier = Modifier.fillMaxWidth().height(52.dp).testTag("start_geotagging_button"),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.AddLocationAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (!hasTimeline) "Select Timeline File First"
                        else if (!hasCandidates) "No Photos Selected"
                        else "Start Geotagging",
                        fontWeight = FontWeight.Bold
                    )
                }
            } else if (progress.isRunning) {
                val fraction = if (progress.totalImages > 0) {
                    progress.processedCount.toFloat() / progress.totalImages.toFloat()
                } else 0f

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier.size(58.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.size(54.dp),
                            strokeWidth = 4.dp
                        )
                        Text(
                            text = "${(fraction * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = "Geotagging in progress...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = progress.currentImageName?.let { "Processing: $it" } ?: "Evaluating timestamps...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${progress.processedCount} of ${progress.totalImages} processed",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onStop, modifier = Modifier.testTag("stop_geotagging_button")) {
                        Text("Stop")
                    }
                }
            } else if (progress.isCompleted) {
                // Completed Summary
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.DoneAll, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Geotagging Complete!",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Processed ${progress.processedCount} images",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Stats Grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatPill(
                        label = "Tagged",
                        count = progress.successfullyTagged,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    StatPill(
                        label = "Skipped",
                        count = progress.skippedAlreadyTagged,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f)
                    )
                    StatPill(
                        label = "Unmatched",
                        count = progress.unmatchedCount,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onOpenPhotoMap,
                        modifier = Modifier.weight(1f).testTag("view_on_photo_map_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("View on Map")
                    }
                    OutlinedButton(
                        onClick = onExportGpx,
                        modifier = Modifier.weight(1f).testTag("export_gpx_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.IosShare, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Export GPX")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatPill(
    label: String,
    count: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ResultsHeader(
    totalResults: Int,
    activeFilter: GeotagResultFilter,
    onFilterChange: (GeotagResultFilter) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Results ($totalResults)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(GeotagResultFilter.values()) { filter ->
                val label = when (filter) {
                    GeotagResultFilter.ALL -> "All"
                    GeotagResultFilter.MATCHED -> "Matched"
                    GeotagResultFilter.SKIPPED_ALREADY_TAGGED -> "Skipped (Already Tagged)"
                    GeotagResultFilter.UNMATCHED -> "Unmatched"
                    GeotagResultFilter.ERRORS -> "Issues"
                }
                FilterChip(
                    selected = activeFilter == filter,
                    onClick = { onFilterChange(filter) },
                    label = { Text(label) }
                )
            }
        }
    }
}

@Composable
private fun GeotagResultItemRow(item: GeotagResultItem) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("geotag_result_item_${item.candidate.displayName}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(52.dp)
            ) {
                AsyncImage(
                    model = item.candidate.uriString,
                    contentDescription = item.candidate.displayName,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = item.candidate.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Subtitle
                val subtitle = when (item.status) {
                    GeotagStatus.MATCHED -> {
                        val lat = String.format(Locale.US, "%.5f", item.matchedPoint?.latitude ?: 0.0)
                        val lng = String.format(Locale.US, "%.5f", item.matchedPoint?.longitude ?: 0.0)
                        val interp = if (item.isInterpolated) " (interpolated)" else ""
                        "GPS: $lat, $lng$interp"
                    }
                    GeotagStatus.ALREADY_GEOTAGGED -> {
                        val lat = item.candidate.existingLatitude?.let { String.format(Locale.US, "%.4f", it) } ?: ""
                        val lng = item.candidate.existingLongitude?.let { String.format(Locale.US, "%.4f", it) } ?: ""
                        "Already had GPS ($lat, $lng) - Skipped"
                    }
                    GeotagStatus.UNMATCHED -> item.errorMessage ?: "No timeline record within time window"
                    GeotagStatus.NO_TIMESTAMP -> "No capture time in EXIF"
                    GeotagStatus.ERROR -> item.errorMessage ?: "Failed to write EXIF"
                }

                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(8.dp))

            // Status Badge
            val (badgeText, badgeBg, badgeFg) = when (item.status) {
                GeotagStatus.MATCHED -> Triple("Matched", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                GeotagStatus.ALREADY_GEOTAGGED -> Triple("Skipped", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                GeotagStatus.UNMATCHED -> Triple("Unmatched", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                GeotagStatus.NO_TIMESTAMP -> Triple("No Time", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                GeotagStatus.ERROR -> Triple("Error", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = badgeBg,
                modifier = Modifier.padding(start = 4.dp)
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeFg,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun TimelineHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How to Export Timeline JSON") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Google Maps Timeline records your location history. To use it for geotagging:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "1. Visit takeout.google.com on your computer or browser.\n" +
                        "2. Select only 'Location History (Timeline)'.\n" +
                        "3. Download and extract the ZIP file.\n" +
                        "4. In 'Location History (Timeline)', pick 'Records.json' or monthly files from 'Semantic Location History'.\n" +
                        "5. Transfer or select the JSON file here to match your photos with your timeline!",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Got it") }
        }
    )
}

@Composable
private fun AlbumSelectDialog(
    albums: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Album to Geotag") },
        text = {
            if (albums.isEmpty()) {
                Text("No device albums found in gallery.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(albums) { album ->
                        val isSelected = album == selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { onSelect(album) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = album.substringAfterLast('/'),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun GeotagSettingsDialog(
    config: GeotagConfig,
    onSave: (GeotagConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var maxDeltaMin by remember { mutableStateOf((config.maxTimeDeltaSeconds / 60).toFloat()) }
    var cameraDriftSec by remember { mutableStateOf(config.cameraClockOffsetSeconds.toFloat()) }
    var interpolate by remember { mutableStateOf(config.interpolateBetweenPoints) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Matching Settings") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Max time delta
                Text(
                    text = "Max Match Window: ${maxDeltaMin.toInt()} minutes",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Slider(
                    value = maxDeltaMin,
                    onValueChange = { maxDeltaMin = it },
                    valueRange = 5f..120f,
                    steps = 22
                )

                // Camera drift
                Text(
                    text = "Camera Clock Drift: ${cameraDriftSec.toInt()}s",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Slider(
                    value = cameraDriftSec,
                    onValueChange = { cameraDriftSec = it },
                    valueRange = -300f..300f,
                    steps = 19
                )

                // Interpolation toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Interpolate between track points", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        Text("Calculates intermediate position when moving", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = interpolate, onCheckedChange = { interpolate = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        config.copy(
                            maxTimeDeltaSeconds = (maxDeltaMin * 60).toLong(),
                            cameraClockOffsetSeconds = cameraDriftSec.toLong(),
                            interpolateBetweenPoints = interpolate
                        )
                    )
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
