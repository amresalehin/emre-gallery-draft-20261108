package com.amresalehin.emregallery.ui

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import com.amresalehin.emregallery.data.PhotoMapLocationCache
import com.amresalehin.emregallery.geotag.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import java.util.Locale

@Composable
fun GeoTimelineCard(
    media: List<MediaItem>,
    cardBackground: Color,
    cardBorder: BorderStroke,
    initiallyExpanded: Boolean = false,
    onShowSnackbar: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isExpanded by remember { mutableStateOf(initiallyExpanded) }

    val geotagPrefs = remember { com.amresalehin.emregallery.data.preferences.GeotagPreferencesManager(context) }
    val prefWriteToExif by geotagPrefs.writeToExif.collectAsState(initial = geotagPrefs.isWriteToExifSync())
    val prefMaxGapMinutes by geotagPrefs.maxInterpolationGapMinutes.collectAsState(initial = geotagPrefs.getMaxInterpolationGapMinutesSync())

    // Minimal state - writeToExif defaults to false (EXIF safety default)
    var selectedFolder by remember { mutableStateOf("All") }
    var customTreeUri by remember { mutableStateOf<Uri?>(null) }
    var writeToExif by remember(prefWriteToExif) { mutableStateOf(prefWriteToExif) }
    var maxInterpolationGapMinutes by remember(prefMaxGapMinutes) { mutableIntStateOf(prefMaxGapMinutes) }

    // Timeline data state
    var timelineFileName by remember { mutableStateOf<String?>(null) }
    var timelinePoints by remember { mutableStateOf<List<TimelineLocationPoint>>(emptyList()) }
    var segmentedTimeline by remember { mutableStateOf<SegmentedTimeline?>(null) }
    var isParsingJson by remember { mutableStateOf(false) }

    // Geotagging job state
    var isRunning by remember { mutableStateOf(false) }
    var progressFraction by remember { mutableFloatStateOf(0f) }
    var statusSummary by remember { mutableStateOf<String?>(null) }

    // Complete folder listing: collect all distinct folder paths and leaf folder names from all media
    val detectedFolders = remember(media, selectedFolder, customTreeUri) {
        val list = mutableListOf("All")
        val folderSet = linkedSetOf<String>()
        for (item in media) {
            val rel = item.relativePath.trim().trim('/')
            if (rel.isNotBlank()) {
                val leaf = rel.substringAfterLast('/')
                if (leaf.isNotBlank()) {
                    folderSet.add(leaf)
                }
                if (rel != leaf) {
                    folderSet.add(rel)
                }
            }
        }
        val sorted = folderSet.sortedWith(String.CASE_INSENSITIVE_ORDER)
        list.addAll(sorted)
        if (customTreeUri != null && selectedFolder != "All" && selectedFolder !in list && selectedFolder != "+ Pick") {
            list.add(selectedFolder)
        }
        list.add("+ Pick")
        list
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

    // Directory tree picker
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            customTreeUri = uri
            val folderName = uri.lastPathSegment?.substringAfterLast(':') ?: "Custom"
            selectedFolder = folderName
        }
    }

    fun startGeotag() {
        if (timelinePoints.isEmpty() && segmentedTimeline == null) {
            jsonPicker.launch(arrayOf("application/json", "text/*", "*/*"))
            return
        }
        if (isRunning) return

        isRunning = true
        progressFraction = 0f
        statusSummary = null

        scope.launch(Dispatchers.IO) {
            // Collect candidates
            val candidates = mutableListOf<ImageGeotagCandidate>()
            if (customTreeUri != null && selectedFolder != "All") {
                scanTreeUri(context, customTreeUri!!, candidates)
            } else {
                val filteredMedia = if (selectedFolder == "All") {
                    media.filter { !it.isVideo }
                } else {
                    media.filter {
                        !it.isVideo && (
                            it.relativePath.trim('/').substringAfterLast('/').equals(selectedFolder, ignoreCase = true) ||
                            it.relativePath.trim('/').equals(selectedFolder, ignoreCase = true) ||
                            it.relativePath.trim('/').endsWith(selectedFolder, ignoreCase = true)
                        )
                    }
                }
                for (item in filteredMedia) {
                    candidates.add(
                        TimelineGeotagEngine.extractCandidate(
                            context = context,
                            uri = Uri.parse(item.uri),
                            fallbackPath = null,
                            displayName = item.name,
                            isVideo = false
                        )
                    )
                }
            }

            val total = candidates.size
            if (total == 0) {
                withContext(Dispatchers.Main) {
                    isRunning = false
                    statusSummary = "No photos"
                }
                return@launch
            }

            val config = GeotagConfig(
                skipAlreadyGeotagged = true,
                maxInterpolationGapSeconds = maxInterpolationGapMinutes * 60L,
                maxTimeDeltaSeconds = geotagPrefs.getNearestPointToleranceMinutesSync() * 60L
            )

            var tagged = 0
            var skipped = 0
            var processed = 0

            val currentTimeline = segmentedTimeline

            for (candidate in candidates) {
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
                withContext(Dispatchers.Main) {
                    progressFraction = processed.toFloat() / total.toFloat()
                }
            }

            withContext(Dispatchers.Main) {
                isRunning = false
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

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = when {
                        isRunning -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                        isParsingJson -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    border = BorderStroke(
                        1.dp,
                        when {
                            isRunning || isParsingJson -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        }
                    ),
                    modifier = Modifier
                        .height(36.dp)
                        .defaultMinSize(minWidth = 84.dp)
                        .clickable(enabled = !isParsingJson && !isRunning) {
                            if (timelinePoints.isEmpty()) {
                                jsonPicker.launch(arrayOf("application/json", "text/*", "*/*"))
                            } else {
                                startGeotag()
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
                    // Timezone is handled automatically by the device.

                    // 1. Folder Section
                    Text(
                        text = "Folder",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        detectedFolders.forEach { folder ->
                            val isSelected = selectedFolder == folder
                            CapsulePill(
                                label = folder,
                                isSelected = isSelected,
                                onClick = {
                                    if (folder == "+ Pick") {
                                        folderPicker.launch(null)
                                    } else {
                                        selectedFolder = folder
                                        customTreeUri = null
                                    }
                                }
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

                    // 3. Interpolation Max Gap Slider (Phase 6 requirement)
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

                    // 4. Write to Exif and Dynamic Full-Width Action Button on the SAME LINE
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // "Write to Exif" toggle (default false for safety)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    val next = !writeToExif
                                    writeToExif = next
                                    scope.launch { geotagPrefs.setWriteToExif(next) }
                                }
                        ) {
                            Text(
                                text = "Write to Exif",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Switch(
                                checked = writeToExif,
                                onCheckedChange = { next ->
                                    writeToExif = next
                                    scope.launch { geotagPrefs.setWriteToExif(next) }
                                },
                                modifier = Modifier.testTag("write_to_exif_switch")
                            )
                        }

                        // Bottom Button: Dynamic and wide to full width
                        Button(
                            onClick = { startGeotag() },
                            enabled = !isRunning && !isParsingJson,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .testTag("geo_timeline_run_button")
                        ) {
                            Text(
                                text = when {
                                    isRunning -> "Geotagging..."
                                    isParsingJson -> "Loading..."
                                    timelinePoints.isEmpty() -> "Select timeline"
                                    else -> "Geotag"
                                },
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (isRunning) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

/**
 * Capsule pill matching the exact capsule shape in the user's mockup.
 */
@Composable
private fun CapsulePill(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.let {
        (it.red * 0.299f + it.green * 0.587f + it.blue * 0.114f) < 0.5f
    }

    val selectedBg = if (isDark) Color(0xFFE5E7EB) else Color(0xFF1F1F1F)
    val selectedText = if (isDark) Color(0xFF1F1F1F) else Color(0xFFFFFFFF)

    val unselectedBg = if (isDark) Color(0xFF2C2C2E) else Color(0xFFE5E7EB)
    val unselectedText = if (isDark) Color(0xFFD1D5DB) else Color(0xFF4B5563)

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) selectedBg else unselectedBg,
        modifier = Modifier
            .height(36.dp)
            .widthIn(min = 68.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = if (isSelected) selectedText else unselectedText
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

private fun scanTreeUri(
    context: Context,
    treeUri: Uri,
    results: MutableList<ImageGeotagCandidate>
) {
    val resolver = context.contentResolver
    val docId = DocumentsContract.getTreeDocumentId(treeUri)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)

    fun queryChildren(uri: Uri) {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

            while (cursor.moveToNext()) {
                val childId = cursor.getString(idCol)
                val name = cursor.getString(nameCol) ?: "file"
                val mime = cursor.getString(mimeCol) ?: ""

                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val subTree = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, childId)
                    queryChildren(subTree)
                } else if (mime.startsWith("image/", ignoreCase = true) ||
                    name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true) ||
                    name.endsWith(".png", ignoreCase = true) || name.endsWith(".cr2", ignoreCase = true) ||
                    name.endsWith(".nef", ignoreCase = true) || name.endsWith(".arw", ignoreCase = true) ||
                    name.endsWith(".dng", ignoreCase = true)
                ) {
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                    results.add(
                        TimelineGeotagEngine.extractCandidate(
                            context = context,
                            uri = docUri,
                            displayName = name
                        )
                    )
                }
            }
        }
    }

    queryChildren(childrenUri)
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
