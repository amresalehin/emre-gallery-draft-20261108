package com.amresalehin.emregallery.ui

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amresalehin.emregallery.data.MediaItem
import com.amresalehin.emregallery.face.FaceRecognitionSettings
import com.amresalehin.emregallery.geotag.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Live information structure for the currently processed photo in a batch geotagging operation.
 */
data class GeotagLiveItemInfo(
    val imageName: String,
    val timestampFormatted: String,
    val coordinatesFormatted: String,
    val addressFormatted: String,
    val statusText: String,
    val isMatched: Boolean
)

@Composable
fun GeoTimelineCard(
    media: List<MediaItem>,
    cardBackground: Color,
    cardBorder: BorderStroke,
    initiallyExpanded: Boolean = false,
    onShowSnackbar: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    availableFolders: List<FaceFolderInfo> = emptyList()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isExpanded by remember { mutableStateOf(initiallyExpanded) }

    val geotagPrefs = remember { com.amresalehin.emregallery.data.preferences.GeotagPreferencesManager(context) }
    val prefWriteToExif by geotagPrefs.writeToExif.collectAsState(initial = geotagPrefs.isWriteToExifSync())
    val prefMaxGapMinutes by geotagPrefs.maxInterpolationGapMinutes.collectAsState(initial = geotagPrefs.getMaxInterpolationGapMinutesSync())

    // Multiple selection allowed: tap to select, tap again to unselect.
    var isAllFolders by remember { mutableStateOf(true) }
    var selectedFolders by remember { mutableStateOf<Set<String>>(emptySet()) }
    var writeToExif by remember(prefWriteToExif) { mutableStateOf(prefWriteToExif) }
    var maxInterpolationGapMinutes by remember(prefMaxGapMinutes) { mutableIntStateOf(prefMaxGapMinutes) }

    // Timeline data state
    var timelineFileName by remember { mutableStateOf<String?>(null) }
    var timelinePoints by remember { mutableStateOf<List<TimelineLocationPoint>>(emptyList()) }
    var segmentedTimeline by remember { mutableStateOf<SegmentedTimeline?>(null) }
    var isParsingJson by remember { mutableStateOf(false) }

    // Geotagging job state
    var isRunning by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var isCancelled by remember { mutableStateOf(false) }
    var runCompleted by remember { mutableStateOf(false) }
    var progressFraction by remember { mutableFloatStateOf(0f) }
    var statusSummary by remember { mutableStateOf<String?>(null) }
    var currentLiveItem by remember { mutableStateOf<GeotagLiveItemInfo?>(null) }
    var processedCount by remember { mutableIntStateOf(0) }
    var totalCandidatesCount by remember { mutableIntStateOf(0) }
    var taggedCount by remember { mutableIntStateOf(0) }
    var skippedCount by remember { mutableIntStateOf(0) }
    var geotagJob by remember { mutableStateOf<Job?>(null) }
    val runLogs = remember { mutableStateListOf<String>() }

    // Resolved folders list: displays ONLY final folder names, matching Face card
    val resolvedFolders = remember(media, availableFolders) {
        if (availableFolders.isNotEmpty()) {
            availableFolders
        } else {
            media.filterNot { it.isVideo }
                .groupBy { FaceRecognitionSettings.normalizeFolderPath(it.relativePath) }
                .map { (path, items) ->
                    FaceFolderInfo(
                        path = path,
                        name = path.substringAfterLast('/').ifBlank { "Internal storage" },
                        photoCount = items.size,
                        coverUri = items.firstOrNull()?.uri
                    )
                }
                .sortedByDescending { it.photoCount }
        }
    }

    // JSON file picker
    val jsonPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            isParsingJson = true
            statusSummary = null
            scope.launch(Dispatchers.IO) {
                val name = queryDisplayName(context.contentResolver, uri) ?: "Timeline.json"
                val parsed = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        GoogleTimelineParser.parseSegmentedTimeline(stream)
                    }
                }.getOrNull()

                val points = parsed?.rawPoints ?: emptyList()

                withContext(Dispatchers.Main) {
                    timelineFileName = name
                    segmentedTimeline = parsed
                    timelinePoints = points
                    isParsingJson = false
                    if (points.isNotEmpty()) {
                        statusSummary = "${points.size} points"
                    } else {
                        statusSummary = "0 points"
                    }
                }
            }
        }
    }

    fun startGeotag(forceRerunAll: Boolean = false) {
        if (timelinePoints.isEmpty() && segmentedTimeline == null) {
            jsonPicker.launch(arrayOf("application/json", "text/*", "*/*"))
            return
        }
        if (isRunning) return

        isRunning = true
        isPaused = false
        isCancelled = false
        runCompleted = false
        progressFraction = 0f
        statusSummary = null
        currentLiveItem = null
        runLogs.clear()

        val startTimeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        runLogs.add("=== Geo Timeline Batch Run Started at $startTimeStr ===")
        runLogs.add("Write to EXIF: $writeToExif | Max Gap: ${maxInterpolationGapMinutes}m | Scope: ${if (isAllFolders) "All Folders" else selectedFolders.joinToString()}")

        geotagJob = scope.launch(Dispatchers.IO) {
            val filteredMedia = if (isAllFolders || selectedFolders.isEmpty()) {
                media.filter { !it.isVideo }
            } else {
                media.filter { item ->
                    if (item.isVideo) return@filter false
                    val normalized = FaceRecognitionSettings.normalizeFolderPath(item.relativePath)
                    val leaf = item.relativePath.trim('/').substringAfterLast('/')
                    normalized in selectedFolders || leaf in selectedFolders || item.relativePath.trim('/') in selectedFolders
                }
            }

            val candidates = mutableListOf<ImageGeotagCandidate>()
            for (item in filteredMedia) {
                val fallbackTaken = if (item.dateTakenMillis > 0L) item.dateTakenMillis else null
                val fallbackModified = if (item.dateModifiedSeconds > 0L) {
                    item.dateModifiedSeconds * 1000L
                } else if (item.dateAddedSeconds > 0L) {
                    item.dateAddedSeconds * 1000L
                } else null

                candidates.add(
                    TimelineGeotagEngine.extractCandidate(
                        context = context,
                        uri = Uri.parse(item.uri),
                        fallbackPath = null,
                        displayName = item.name,
                        isVideo = false,
                        fallbackTimestampMillis = fallbackTaken,
                        fallbackLastModifiedMillis = fallbackModified
                    )
                )
            }

            val total = candidates.size
            withContext(Dispatchers.Main) {
                totalCandidatesCount = total
                processedCount = 0
                taggedCount = 0
                skippedCount = 0
            }

            if (total == 0) {
                withContext(Dispatchers.Main) {
                    isRunning = false
                    runCompleted = true
                    statusSummary = "No photos"
                }
                return@launch
            }

            val config = GeotagConfig(
                skipAlreadyGeotagged = !forceRerunAll,
                maxInterpolationGapSeconds = maxInterpolationGapMinutes * 60L,
                maxTimeDeltaSeconds = geotagPrefs.getNearestPointToleranceMinutesSync() * 60L
            )

            var tagged = 0
            var skipped = 0
            var processed = 0
            val currentTimeline = segmentedTimeline

            for (candidate in candidates) {
                if (!isRunning || isCancelled) break

                while (isPaused && isRunning && !isCancelled) {
                    delay(200)
                }
                if (!isRunning || isCancelled) break

                val rawTime = candidate.dateTimeOriginalMillis
                val timestampStr = if (rawTime != null && rawTime > 0L) {
                    val base = formatTimestamp(rawTime)
                    val src = candidate.timestampSource
                    if (!src.isNullOrBlank() && src != "DateTimeOriginal") {
                        "$base ($src)"
                    } else {
                        base
                    }
                } else {
                    "No timestamp"
                }

                val match = if (currentTimeline != null) {
                    TimelineGeotagEngine.matchCandidateWithTimeline(
                        candidate = candidate,
                        timeline = currentTimeline,
                        config = config
                    )
                } else {
                    TimelineGeotagEngine.matchCandidate(
                        candidate = candidate,
                        sortedPoints = timelinePoints,
                        config = config
                    )
                }

                val lat = match.matchedPoint?.latitude ?: candidate.existingLatitude
                val lng = match.matchedPoint?.longitude ?: candidate.existingLongitude

                var resolvedAddress: String? = null
                if (lat != null && lng != null) {
                    resolvedAddress = reverseGeocodeLocation(context, lat, lng)
                        ?: getPlaceDisplayName(context, lat, lng)
                }

                val coordsStr = if (lat != null && lng != null) {
                    formatCoordinates(lat, lng)
                } else {
                    "No GPS coordinates"
                }

                val addressStr = resolvedAddress ?: when (match.status) {
                    GeotagStatus.ALREADY_GEOTAGGED -> "Previously geotagged location"
                    GeotagStatus.MATCHED -> "Coordinates resolved"
                    GeotagStatus.UNMATCHED -> "Out of timeline range"
                    GeotagStatus.NO_TIMESTAMP -> "Missing photo timestamp"
                    else -> "Location not found"
                }

                val statusStr = when (match.status) {
                    GeotagStatus.MATCHED -> if (match.isInterpolated) "Matched (Interpolated)" else "Matched (Exact point)"
                    GeotagStatus.ALREADY_GEOTAGGED -> "Skipped (Already tagged)"
                    GeotagStatus.UNMATCHED -> "No timeline match"
                    GeotagStatus.NO_TIMESTAMP -> "No timestamp"
                    GeotagStatus.ERROR -> "Error reading EXIF"
                }

                if (match.status == GeotagStatus.ALREADY_GEOTAGGED) {
                    skipped++
                } else if (match.status == GeotagStatus.MATCHED && match.matchedPoint != null) {
                    val ok = TimelineGeotagEngine.applyGeotag(
                        context = context,
                        result = match,
                        writeExif = writeToExif
                    ).getOrDefault(false)
                    if (ok) tagged++
                }

                processed++
                val fraction = processed.toFloat() / total.toFloat()

                val liveInfo = GeotagLiveItemInfo(
                    imageName = candidate.displayName,
                    timestampFormatted = timestampStr,
                    coordinatesFormatted = coordsStr,
                    addressFormatted = addressStr,
                    statusText = statusStr,
                    isMatched = match.status == GeotagStatus.MATCHED
                )

                val logLine = "[$processed/$total] ${candidate.displayName} | Time: $timestampStr | GPS: $coordsStr | Addr: $addressStr | Status: $statusStr"
                runLogs.add(logLine)

                withContext(Dispatchers.Main) {
                    progressFraction = fraction
                    processedCount = processed
                    taggedCount = tagged
                    skippedCount = skipped
                    currentLiveItem = liveInfo
                }
            }

            val endTimeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            runLogs.add("=== Geo Timeline Batch Run Ended at $endTimeStr ===")
            runLogs.add("Summary: $tagged tagged, $skipped skipped, $processed total processed.")

            withContext(Dispatchers.Main) {
                isRunning = false
                isPaused = false
                runCompleted = true
                statusSummary = "$tagged tagged · $skipped skipped"
                val modeDesc = if (writeToExif) "written to EXIF & map" else "cached to in-app map (EXIF safe)"
                onShowSnackbar?.invoke("Geo Timeline: $tagged photos $modeDesc, $skipped skipped")
            }
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .testTag("geo_timeline_card"),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = cardBackground),
        border = cardBorder
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp)
        ) {
            // Header follows the shared OCR / Face / Tag settings-card system.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { isExpanded = !isExpanded },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(46.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            GeoTimelineViewfinderIcon(
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(16.dp))

                    Text(
                        text = "Geo Timeline",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Top Corner Action Pill
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = when {
                        isRunning && isPaused -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                        isRunning -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                        isParsingJson -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    border = BorderStroke(
                        1.dp,
                        when {
                            isRunning && isPaused -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
                            isRunning || isParsingJson -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        }
                    ),
                    modifier = Modifier
                        .height(36.dp)
                        .defaultMinSize(minWidth = 84.dp)
                        .clickable(enabled = !isParsingJson) {
                            when {
                                isRunning -> isPaused = !isPaused
                                timelinePoints.isEmpty() -> jsonPicker.launch(arrayOf("application/json", "text/*", "*/*"))
                                else -> startGeotag()
                            }
                        }
                        .testTag("geo_timeline_header_pill")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        when {
                            isRunning -> {
                                if (isPaused) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = "${(progressFraction * 100).toInt()}% · Resume",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                } else {
                                    CircularProgressIndicator(
                                        progress = { progressFraction },
                                        modifier = Modifier.size(13.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "${(progressFraction * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            isParsingJson -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(13.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Loading",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            else -> {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (timelinePoints.isNotEmpty()) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outline
                                        )
                                )
                                Text(
                                    text = statusSummary ?: if (timelinePoints.isEmpty()) "Select timeline" else "Ready",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.width(6.dp))

                IconButton(
                    onClick = { isExpanded = !isExpanded },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Expanded content
            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                ) {
                    // 1. Folders Section: Matching Face Card layout & styling
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
                            color = if (isAllFolders) Color(0xFF161A20) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            border = BorderStroke(
                                1.dp,
                                if (isAllFolders) Color(0xFF161A20) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                            ),
                            modifier = Modifier
                                .height(36.dp)
                                .clickable {
                                    isAllFolders = true
                                    selectedFolders = emptySet()
                                }
                                .testTag("geo_timeline_folders_all_pill")
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 14.dp)
                            ) {
                                Text(
                                    text = "All folders",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 13.sp,
                                        fontWeight = if (isAllFolders) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isAllFolders) Color.White else MaterialTheme.colorScheme.onSurface
                                    )
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Horizontally scrollable row of device folders showing ONLY final folder names.
                    // Multiple selection allowed: tap to select, tap again to unselect. No pick pill.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .testTag("geo_timeline_folders_horizontal_row"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        resolvedFolders.forEach { folder ->
                            val isSelected = !isAllFolders && (folder.path in selectedFolders || folder.name in selectedFolders)
                            FolderChip(
                                label = folder.name,
                                isSelected = isSelected,
                                onClick = {
                                    if (isAllFolders) {
                                        isAllFolders = false
                                        selectedFolders = setOf(folder.path)
                                    } else {
                                        val newSet = if (folder.path in selectedFolders || folder.name in selectedFolders) {
                                            selectedFolders - folder.path - folder.name
                                        } else {
                                            selectedFolders + folder.path
                                        }
                                        if (newSet.isEmpty() || newSet.size >= resolvedFolders.size) {
                                            isAllFolders = true
                                            selectedFolders = emptySet()
                                        } else {
                                            selectedFolders = newSet
                                        }
                                    }
                                },
                                testTag = "geo_timeline_folder_chip_${folder.name}"
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // 2. Timeline file info (if selected)
                    if (timelinePoints.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isRunning) {
                                    jsonPicker.launch(arrayOf("application/json", "text/*", "*/*"))
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = timelineFileName ?: "Timeline file",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "${timelinePoints.size} pts · Change",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    // 3. Interpolation Max Gap Slider
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Max Interpolation Gap",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "$maxInterpolationGapMinutes min",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = maxInterpolationGapMinutes.toFloat(),
                            onValueChange = { newVal ->
                                val mins = newVal.toInt()
                                maxInterpolationGapMinutes = mins
                                scope.launch { geotagPrefs.setMaxInterpolationGapMinutes(mins) }
                            },
                            valueRange = 1f..60f,
                            steps = 58,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("interpolation_gap_slider")
                        )
                    }

                    // 4. "Write to Exif" switch row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Write to Exif",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (writeToExif) "Writes GPS tags directly into image files" else "Safe mode: in-app map cache only",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }

                        Switch(
                            checked = writeToExif,
                            onCheckedChange = { next ->
                                writeToExif = next
                                scope.launch { geotagPrefs.setWriteToExif(next) }
                            },
                            modifier = Modifier.testTag("write_to_exif_switch")
                        )
                    }

                    // 5. Detailed Progress Bar & Live Media Item Card
                    if (isRunning || isPaused || currentLiveItem != null) {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 14.dp)
                                .testTag("geo_timeline_live_progress_box")
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Progress Status Header
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        if (isRunning && !isPaused) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(13.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        } else if (isPaused) {
                                            Icon(
                                                imageVector = Icons.Default.Pause,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.secondary
                                            )
                                        } else if (runCompleted) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Text(
                                            text = when {
                                                isRunning && !isPaused -> "Geotagging in progress…"
                                                isPaused -> "Paused"
                                                runCompleted -> "Geotagging Completed"
                                                else -> "Batch Progress"
                                            },
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (isPaused) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                                            )
                                        )
                                    }
                                    Text(
                                        text = "$processedCount / $totalCandidatesCount photos (${(progressFraction * 100).toInt()}%)",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                }

                                // Detailed Progress Bar
                                val animatedProgress by animateFloatAsState(targetValue = progressFraction, label = "geotag_progress_fraction")
                                LinearProgressIndicator(
                                    progress = { animatedProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = if (isPaused) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )

                                // Live media item details (name, timestamp, resolved coordinates, address)
                                currentLiveItem?.let { item ->
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                                        thickness = 1.dp,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )

                                    // Currently processed image name & status badge
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Image,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = item.imageName,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (item.isMatched) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
                                        ) {
                                            Text(
                                                text = item.statusText,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = if (item.isMatched) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                                ),
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    // Image timestamp
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.AccessTime,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = item.timestampFormatted,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    // Resolved coordinates
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.MyLocation,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = item.coordinatesFormatted,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    // Resolved human-readable address
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Place,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = item.addressFormatted,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontWeight = FontWeight.Normal,
                                                color = MaterialTheme.colorScheme.onSurface
                                            ),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 6. Dynamic Bottom Action Controls
                    if (isRunning) {
                        // Running state: Pause and Cancel buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { isPaused = !isPaused },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isPaused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = if (isPaused) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("geo_timeline_pause_button")
                            ) {
                                Icon(
                                    imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = if (isPaused) "Resume" else "Pause",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (isPaused) "Resume" else "Pause",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    isCancelled = true
                                    isRunning = false
                                    isPaused = false
                                    geotagJob?.cancel()
                                    runCompleted = true
                                    statusSummary = "Cancelled ($processedCount/$totalCandidatesCount)"
                                    onShowSnackbar?.invoke("Geotagging cancelled")
                                },
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("geo_timeline_cancel_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "Cancel",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }
                        }
                    } else if (runCompleted) {
                        // After run ends: Rerun all, Reset, and Logs download buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { startGeotag(forceRerunAll = true) },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1.1f)
                                    .height(44.dp)
                                    .testTag("geo_timeline_rerun_all_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Rerun all",
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "Rerun all",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    runCompleted = false
                                    isRunning = false
                                    isPaused = false
                                    isCancelled = false
                                    progressFraction = 0f
                                    statusSummary = null
                                    currentLiveItem = null
                                    processedCount = 0
                                    totalCandidatesCount = 0
                                    taggedCount = 0
                                    skippedCount = 0
                                },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(0.95f)
                                    .height(44.dp)
                                    .testTag("geo_timeline_reset_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RestartAlt,
                                    contentDescription = "Reset",
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "Reset",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    val logText = if (runLogs.isNotEmpty()) {
                                        runLogs.joinToString("\n")
                                    } else {
                                        "Geo Timeline: ${statusSummary ?: "No activity"}"
                                    }
                                    val fileName = exportGeotagLogs(context, logText)
                                    onShowSnackbar?.invoke("Logs downloaded: $fileName")
                                },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1.15f)
                                    .height(44.dp)
                                    .testTag("geo_timeline_download_logs_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Download logs",
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "Logs",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    } else {
                        // Default Idle State: Primary "Geotag" action button
                        Button(
                            onClick = { startGeotag() },
                            enabled = !isRunning && !isParsingJson,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .testTag("geo_timeline_run_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = when {
                                    isParsingJson -> "Loading..."
                                    timelinePoints.isEmpty() -> "Select timeline"
                                    else -> "Geotag"
                                },
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatCoordinates(lat: Double, lng: Double): String {
    val latDir = if (lat >= 0) "N" else "S"
    val lngDir = if (lng >= 0) "E" else "W"
    return String.format(Locale.US, "%.5f° %s, %.5f° %s", abs(lat), latDir, abs(lng), lngDir)
}

private fun formatTimestamp(epochMillis: Long): String {
    if (epochMillis <= 0L) return "No capture time"
    val sdf = SimpleDateFormat("MMM d, yyyy · h:mm:ss a", Locale.getDefault())
    return sdf.format(Date(epochMillis))
}

private fun exportGeotagLogs(context: Context, logContent: String): String {
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val fileName = "geotag_log_$timeStamp.txt"
    return runCatching {
        val targetDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = if (targetDir != null && (targetDir.exists() || targetDir.mkdirs())) {
            File(targetDir, fileName)
        } else {
            File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, fileName)
        }
        file.writeText(logContent)
        file.name
    }.getOrDefault(fileName)
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

/**
 * Custom vector icon matching the viewfinder frame with a center location pin from the user screenshot.
 */
@Composable
fun GeoTimelineViewfinderIcon(
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = 2.4.dp.toPx()
        val cornerLen = w * 0.26f
        val radius = 3.5.dp.toPx()

        // 4 viewfinder corners
        // Top-left
        val pathTL = Path().apply {
            moveTo(0f, cornerLen)
            lineTo(0f, radius)
            quadraticTo(0f, 0f, radius, 0f)
            lineTo(cornerLen, 0f)
        }
        drawPath(pathTL, tint, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Top-right
        val pathTR = Path().apply {
            moveTo(w - cornerLen, 0f)
            lineTo(w - radius, 0f)
            quadraticTo(w, 0f, w, radius)
            lineTo(w, cornerLen)
        }
        drawPath(pathTR, tint, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Bottom-left
        val pathBL = Path().apply {
            moveTo(0f, h - cornerLen)
            lineTo(0f, h - radius)
            quadraticTo(0f, h, radius, h)
            lineTo(cornerLen, h)
        }
        drawPath(pathBL, tint, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Bottom-right
        val pathBR = Path().apply {
            moveTo(w - cornerLen, h)
            lineTo(w - radius, h)
            quadraticTo(w, h, w, h - radius)
            lineTo(w, h - cornerLen)
        }
        drawPath(pathBR, tint, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Center map pin
        val cx = w / 2f
        val cy = h / 2f - 1.dp.toPx()
        val pinR = w * 0.17f
        val pinPath = Path().apply {
            moveTo(cx, cy + pinR * 1.55f)
            cubicTo(
                cx - pinR * 1.05f, cy + pinR * 0.4f,
                cx - pinR, cy - pinR * 0.8f,
                cx, cy - pinR * 0.8f
            )
            cubicTo(
                cx + pinR, cy - pinR * 0.8f,
                cx + pinR * 1.05f, cy + pinR * 0.4f,
                cx, cy + pinR * 1.55f
            )
            close()
        }
        drawPath(pinPath, tint, style = Stroke(strokeWidth * 0.85f))
        drawCircle(tint, radius = pinR * 0.38f, center = Offset(cx, cy - pinR * 0.05f))
    }
}

private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
    return runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
    }.getOrNull()
}
