package com.amresalehin.emregallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.amresalehin.emregallery.data.DownloadActivityItem
import com.amresalehin.emregallery.data.DownloadActivityManager
import com.amresalehin.emregallery.data.DownloadActivityStatus
import kotlinx.coroutines.delay

/**
 * Returns a display-friendly format badge text (e.g. JPG, PNG, MP4, WEBP).
 * Strictly prevents false "4K" labels from appearing on thumbnails.
 */
fun getDisplayFormatBadge(item: DownloadActivityItem): String {
    val raw = item.format.trim().uppercase()
    // Strictly prevent false 4K on non-video items
    if (!item.isVideo && (raw == "4K" || raw.contains("4K"))) {
        // Fall through to determine genuine format from URL/title
    } else if (raw.isNotEmpty() && !raw.contains("4K") && raw != "UNKNOWN") {
        return if (raw == "JPEG") "JPG" else raw.take(4)
    } else if (item.isVideo && (raw.contains("4K") || item.title.contains("4K", ignoreCase = true))) {
        return "4K"
    }

    val lowerUrl = item.mediaUrl.lowercase()
    val lowerTitle = item.title.lowercase()
    return when {
        lowerUrl.contains(".jpg") || lowerUrl.contains(".jpeg") || lowerTitle.endsWith(".jpg") || lowerTitle.endsWith(".jpeg") -> "JPG"
        lowerUrl.contains(".png") || lowerTitle.endsWith(".png") -> "PNG"
        lowerUrl.contains(".webp") || lowerTitle.endsWith(".webp") -> "WEBP"
        lowerUrl.contains(".gif") || lowerTitle.endsWith(".gif") -> "GIF"
        lowerUrl.contains(".svg") || lowerTitle.endsWith(".svg") -> "SVG"
        lowerUrl.contains(".mp4") || lowerTitle.endsWith(".mp4") -> "MP4"
        lowerUrl.contains(".webm") || lowerTitle.endsWith(".webm") -> "WEBM"
        lowerUrl.contains(".m3u8") -> "HLS"
        lowerUrl.contains(".mp3") || lowerTitle.endsWith(".mp3") -> "MP3"
        item.isVideo -> "MP4"
        item.isAudio -> "MP3"
        else -> "JPG"
    }
}

/**
 * Custom heartbeat/pulse icon matching image_4fd87912.png
 */
@Composable
fun ActivityPulseIcon(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(0f, h * 0.5f)
            lineTo(w * 0.22f, h * 0.5f)
            lineTo(w * 0.35f, h * 0.15f)
            lineTo(w * 0.55f, h * 0.88f)
            lineTo(w * 0.72f, h * 0.35f)
            lineTo(w * 0.82f, h * 0.5f)
            lineTo(w, h * 0.5f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

/**
 * Prominent Activity Card container on the download inbox main page,
 * exactly matching the layout of image_4fd87912.png:
 * - Rounded card container with heartbeat icon, "Activity" bold title, and status pill ("1 failed") + chevron
 * - Horizontal thumbnails: downloading items (with progress bar), failed items (with ! badge), divider, finished items (with ✓ badge)
 * - Tapping opens the Activity Page
 */
@Composable
fun ActivityPreviewCard(
    activities: List<DownloadActivityItem>,
    onOpenActivity: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val containerShape = RoundedCornerShape(26.dp)

    val downloading = remember(activities) { activities.filter { it.isDownloading || it.isPaused || it.isRetrying } }
    val failed = remember(activities) { activities.filter { it.isFailed } }
    val finished = remember(activities) { activities.filter { it.isFinished } }

    val failedCount = activities.count { it.isFailed }
    val retryingCount = activities.count { it.isRetrying }
    val downloadingCount = activities.count { it.isDownloading || it.isPaused || it.isRetrying }

    Surface(
        onClick = onOpenActivity,
        shape = containerShape,
        color = if (isDark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 6.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.65f else 0.75f)
        ),
        modifier = modifier
            .shadow(
                elevation = 6.dp,
                shape = containerShape,
                ambientColor = if (isDark) Color.Black.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.12f),
                spotColor = if (isDark) Color.Black.copy(alpha = 0.65f) else Color.Black.copy(alpha = 0.18f)
            )
            .testTag("downloader_activity_preview_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp, horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Row: [Pulse Icon] Activity         [X failed pill] [>]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ActivityPulseIcon(
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "Activity",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (failedCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isDark) Color(0xFF3B1212) else Color(0xFFFDE8E8),
                            modifier = Modifier.testTag("activity_failed_badge")
                        ) {
                            Text(
                                text = "$failedCount failed",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isDark) Color(0xFFF87171) else Color(0xFF9B1C1C),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    } else if (downloadingCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isDark) Color(0xFF1E293B) else Color(0xFFE1EFFE),
                            modifier = Modifier.testTag("activity_downloading_badge")
                        ) {
                            Text(
                                text = "$downloadingCount downloading",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isDark) Color(0xFF60A5FA) else Color(0xFF1E429F),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Open Activity",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Thumbnail Row matching image_4fd87912.png
            if (activities.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val activeOrFailedList = (downloading.take(2) + failed.take(2)).take(2)
                    val finishedList = finished.take(3)

                    // Active or Failed Thumbnails
                    for (item in activeOrFailedList) {
                        ActivityThumbnailBox(item = item)
                    }

                    // Vertical Divider if both active/failed and finished items exist
                    if (activeOrFailedList.isNotEmpty() && finishedList.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(54.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        )
                    }

                    // Finished Thumbnails (with green checkmark badge)
                    for (item in finishedList) {
                        ActivityThumbnailBox(item = item)
                    }
                }
            } else {
                // Empty state when no downloads have occurred yet
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No active downloads • Activity appears here",
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

/**
 * Thumbnail item for the Activity Preview Card
 */
@Composable
private fun ActivityThumbnailBox(
    item: DownloadActivityItem,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(width = 68.dp, height = 64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        val previewModel = item.localUri ?: item.thumbnailUrl ?: item.mediaUrl
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(previewModel)
                .crossfade(true)
                .build(),
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Top-left format tag (e.g. JPG, PNG, MP4)
        val formatBadge = getDisplayFormatBadge(item)
        if (formatBadge.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color.Black.copy(alpha = 0.75f),
                modifier = Modifier
                    .padding(top = 4.dp, start = 4.dp)
                    .align(Alignment.TopStart)
            ) {
                Text(
                    text = formatBadge,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    fontSize = 8.5.sp,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }

        when {
            item.isDownloading -> {
                // Center play overlay if video
                if (item.isVideo) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.55f),
                        modifier = Modifier
                            .size(24.dp)
                            .align(Alignment.Center)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // Bottom linear progress bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .align(Alignment.BottomCenter)
                        .background(Color.Black.copy(alpha = 0.4f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction = item.progress.coerceIn(0.08f, 1f))
                            .background(Color.White)
                    )
                }
            }
            item.isRetrying -> {
                // Bottom-right circular amber retry badge
                Surface(
                    shape = CircleShape,
                    color = Color(0xFFFFB74D),
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .padding(bottom = 4.dp, end = 4.dp)
                        .size(18.dp)
                        .align(Alignment.BottomEnd)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Retrying",
                            tint = Color.Black,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
            item.isFailed -> {
                // Bottom-right circular white exclamation mark badge
                Surface(
                    shape = CircleShape,
                    color = Color.White,
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .padding(bottom = 4.dp, end = 4.dp)
                        .size(18.dp)
                        .align(Alignment.BottomEnd)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.PriorityHigh,
                            contentDescription = "Failed",
                            tint = Color.Black,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
            item.isFinished -> {
                // Bottom-right circular green checkmark badge
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF2E7D32),
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .padding(bottom = 4.dp, end = 4.dp)
                        .size(18.dp)
                        .align(Alignment.BottomEnd)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Finished",
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Activity Page matching image_ae11436d.png with various controls:
 * - Top bar with Back arrow and Tune/controls button
 * - Filter pills: All, Downloading (X), Failed (Y), Finished (Z)
 * - Section for active downloads (progress bar, speed, pause/resume)
 * - Section for failed downloads (error reason, retry, delete)
 * - Section for past downloads (finished items with green checkmark, size, timestamp)
 * - Floating action button '+' on bottom right
 * - Modal bottom sheet with global controls (retry all, cancel all, clear history)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloaderActivityPage(
    onBack: () -> Unit,
    onAddNewDownload: () -> Unit,
    onOpenMedia: ((DownloadActivityItem) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        DownloadActivityManager.initialize(context)
        DownloadActivityManager.syncWithStorageDir(context)
        DownloadActivityManager.syncWithInboxRepository()
    }

    val activities by DownloadActivityManager.activities.collectAsState()

    var selectedFilter by remember { mutableStateOf("All") }
    var showControlsSheet by remember { mutableStateOf(false) }

    var currentTimeMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(activities) {
        if (activities.any { it.isRetrying }) {
            while (true) {
                delay(500L)
                currentTimeMillis = System.currentTimeMillis()
            }
        }
    }

    BackHandler { onBack() }

    val downloadingItems = remember(activities) {
        activities.filter { it.isDownloading || it.isPaused || it.isRetrying }.distinctBy { it.id }
    }
    val failedItems = remember(activities) {
        activities.filter { it.isFailed }.distinctBy { it.id }
    }
    val finishedItems = remember(activities) {
        activities.filter { it.isFinished }.distinctBy { it.id }
    }

    val filteredList = remember(activities, selectedFilter, downloadingItems, failedItems, finishedItems) {
        when (selectedFilter) {
            "Downloading" -> downloadingItems
            "Failed" -> failedItems
            "Finished" -> finishedItems
            else -> activities.distinctBy { it.id }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize().testTag("downloader_activity_page"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(44.dp).testTag("activity_back_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    IconButton(
                        onClick = { showControlsSheet = true },
                        modifier = Modifier.size(44.dp).testTag("activity_controls_tune_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Activity Controls",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddNewDownload,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = CircleShape,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp, end = 6.dp)
                    .size(56.dp)
                    .testTag("activity_fab_add")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Download",
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Filter Pills Row matching image_ae11436d.png
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("activity_filter_pills_row"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val filters = listOf(
                    "All" to activities.size,
                    "Downloading" to downloadingItems.size,
                    "Failed" to failedItems.size,
                    "Finished" to finishedItems.size
                )
                items(filters) { (name, count) ->
                    val isSelected = selectedFilter == name
                    val labelText = if (name == "All") name else "$name ($count)"
                    Surface(
                        onClick = { selectedFilter = name },
                        shape = RoundedCornerShape(20.dp),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
                        },
                        border = if (!isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)) else null,
                        modifier = Modifier.height(36.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            Text(
                                text = labelText,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.surface
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                fontSize = 13.5.sp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Activities List matching image_ae11436d.png
            if (filteredList.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(bottom = 90.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Active Downloads Section
                    if (selectedFilter == "All" || selectedFilter == "Downloading") {
                        if (downloadingItems.isNotEmpty()) {
                            itemsIndexed(downloadingItems, key = { index, item -> "active_${item.id}_$index" }) { _, item ->
                                ActiveDownloadRow(
                                    item = item,
                                    currentTimeMillis = currentTimeMillis,
                                    onPauseResume = {
                                        if (item.isPaused) {
                                            DownloadActivityManager.resumeDownload(context, item.id)
                                        } else {
                                            DownloadActivityManager.pauseDownload(context, item.id)
                                        }
                                    }
                                )
                            }
                        }
                    }

                    // Failed Downloads Section
                    if (selectedFilter == "All" || selectedFilter == "Failed") {
                        if (failedItems.isNotEmpty()) {
                            itemsIndexed(failedItems, key = { index, item -> "failed_${item.id}_$index" }) { _, item ->
                                FailedDownloadRow(
                                    item = item,
                                    currentTimeMillis = currentTimeMillis,
                                    onRetry = {
                                        DownloadActivityManager.retryDownload(context, item.id)
                                    },
                                    onDelete = {
                                        DownloadActivityManager.deleteActivity(item.id)
                                    }
                                )
                            }
                        }
                    }

                    // Past Downloads Header & Items
                    if (selectedFilter == "All" || selectedFilter == "Finished") {
                        if (finishedItems.isNotEmpty()) {
                            item(key = "past_downloads_header_label") {
                                Text(
                                    text = "Past downloads",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 15.sp,
                                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                                )
                            }
                            itemsIndexed(finishedItems, key = { index, item -> "finished_${item.id}_$index" }) { _, item ->
                                FinishedDownloadRow(
                                    item = item,
                                    onClick = {
                                        onOpenMedia?.invoke(item)
                                    }
                                )
                            }
                        }
                    }
                }
            } else {
                // Empty state for current filter
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Inbox,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(52.dp)
                        )
                        Text(
                            text = when (selectedFilter) {
                                "Downloading" -> "No active downloads"
                                "Failed" -> "No failed downloads"
                                "Finished" -> "No past downloads"
                                else -> "No download activity yet"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Add media links to start downloading.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // Controls Modal Bottom Sheet
    if (showControlsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showControlsSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Activity Controls",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (failedItems.isNotEmpty()) {
                    Button(
                        onClick = {
                            DownloadActivityManager.retryAllFailed(context)
                            showControlsSheet = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Retry All Failed (${failedItems.size})")
                    }
                }

                if (downloadingItems.isNotEmpty()) {
                    OutlinedButton(
                        onClick = {
                            DownloadActivityManager.cancelAllActive(context)
                            showControlsSheet = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Cancel All Active Downloads")
                    }
                }

                if (finishedItems.isNotEmpty()) {
                    OutlinedButton(
                        onClick = {
                            DownloadActivityManager.clearFinished()
                            showControlsSheet = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Clear Past Download History")
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/**
 * Row displaying an active downloading item matching image_ae11436d.png
 */
@Composable
private fun ActiveDownloadRow(
    item: DownloadActivityItem,
    onPauseResume: () -> Unit,
    modifier: Modifier = Modifier,
    currentTimeMillis: Long = System.currentTimeMillis()
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Thumbnail with format tag and play icon
        Box(
            modifier = Modifier
                .size(width = 96.dp, height = 62.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {
            AsyncImage(
                model = item.thumbnailUrl ?: item.mediaUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Dynamic format badge top-left (no false 4K)
            val formatBadge = getDisplayFormatBadge(item)
            if (formatBadge.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Black.copy(alpha = 0.8f),
                    modifier = Modifier
                        .padding(4.dp)
                        .align(Alignment.TopStart)
                ) {
                    Text(
                        text = formatBadge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        fontSize = 8.5.sp,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }

            // Play overlay if video
            if (item.isVideo) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.5f),
                    modifier = Modifier
                        .size(22.dp)
                        .align(Alignment.Center)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        // Title, byte progress & speed / retry status, and linear progress bar
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (item.isRetrying) {
                val remainingSec = if (item.nextRetryTimestampMillis != null) {
                    ((item.nextRetryTimestampMillis - currentTimeMillis + 999L) / 1000L).coerceAtLeast(0L)
                } else 0L
                val retryLabel = if (remainingSec > 0) "Retrying in ${remainingSec}s" else "Retrying now..."
                Text(
                    text = retryLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFFB74D),
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp
                )
            } else {
                val speedText = DownloadActivityManager.formatSpeed(item.speedBytesPerSec)
                val bytesText = if (item.totalBytes > 0) {
                    "${DownloadActivityManager.formatBytes(item.bytesRead)} / ${DownloadActivityManager.formatBytes(item.totalBytes)}"
                } else {
                    "${DownloadActivityManager.formatBytes(item.bytesRead)} downloaded"
                }

                Text(
                    text = "$bytesText • $speedText",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }

            LinearProgressIndicator(
                progress = {
                    val p = if (item.isRetrying) 0.05f else item.progress
                    if (p.isNaN() || p.isInfinite() || p < 0f) 0.05f else p.coerceIn(0f, 1f)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = if (item.isRetrying) Color(0xFFFFB74D) else MaterialTheme.colorScheme.onSurface,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        }

        // Pause/Resume button matching image_ae11436d.png
        Surface(
            onClick = onPauseResume,
            shape = CircleShape,
            color = Color.Transparent,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (item.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = if (item.isPaused) "Resume" else "Pause",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Row displaying a failed item matching image_ae11436d.png with Retry and Delete buttons
 */
@Composable
private fun FailedDownloadRow(
    item: DownloadActivityItem,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    currentTimeMillis: Long = System.currentTimeMillis()
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Thumbnail with dynamic format tag (NO false 4K)
        Box(
            modifier = Modifier
                .size(width = 96.dp, height = 62.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {
            AsyncImage(
                model = item.thumbnailUrl ?: item.mediaUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            val formatBadge = getDisplayFormatBadge(item)
            if (formatBadge.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Black.copy(alpha = 0.8f),
                    modifier = Modifier
                        .padding(4.dp)
                        .align(Alignment.TopStart)
                ) {
                    Text(
                        text = formatBadge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        fontSize = 8.5.sp,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
        }

        // Title and status/error message
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (item.isRetrying && item.nextRetryTimestampMillis != null) {
                val remainingSec = ((item.nextRetryTimestampMillis - currentTimeMillis + 999L) / 1000L).coerceAtLeast(1L)
                Text(
                    text = "Retrying in ${remainingSec}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFFB74D),
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                val errorMsg = item.errorMessage ?: "Network error"
                Text(
                    text = "Failed • $errorMsg",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Retry Button
        IconButton(
            onClick = onRetry,
            enabled = !item.isRetrying,
            modifier = Modifier.size(36.dp)
        ) {
            if (item.isRetrying) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFFFFB74D)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Retry download",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Delete Button
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.DeleteOutline,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Row displaying a finished past download item matching image_ae11436d.png with green checkmark
 */
@Composable
private fun FinishedDownloadRow(
    item: DownloadActivityItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Thumbnail with format tag
        Box(
            modifier = Modifier
                .size(width = 96.dp, height = 62.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {
            val previewModel = item.localUri ?: item.thumbnailUrl ?: item.mediaUrl
            AsyncImage(
                model = previewModel,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            val formatBadge = getDisplayFormatBadge(item)
            if (formatBadge.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Black.copy(alpha = 0.8f),
                    modifier = Modifier
                        .padding(4.dp)
                        .align(Alignment.TopStart)
                ) {
                    Text(
                        text = formatBadge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        fontSize = 8.5.sp,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
        }

        // Title and size • date
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            val sizeStr = DownloadActivityManager.formatBytes(item.bytesRead)
            val dateStr = DownloadActivityManager.formatDate(item.timestampMillis)
            Text(
                text = "$sizeStr • $dateStr",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.5.sp
            )
        }

        // Green checkmark icon matching image_ae11436d.png
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = "Finished",
            tint = Color(0xFF2E7D32),
            modifier = Modifier.size(24.dp)
        )
    }
}
