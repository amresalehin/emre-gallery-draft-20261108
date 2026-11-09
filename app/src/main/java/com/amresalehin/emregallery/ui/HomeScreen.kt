package com.amresalehin.emregallery.ui

import android.Manifest
import android.os.Build
import com.amresalehin.emregallery.data.StoryGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.amresalehin.emregallery.data.MediaItem
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.roundToInt
import com.amresalehin.emregallery.data.ThumbnailPrewarmManager
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    media: List<MediaItem>,
    loading: Boolean,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    albumTitle: String? = null,
    sortOrder: String = "date_desc",
    groupBy: String = "day",
    gridColumns: Int = 3,
    gridStyle: String = "masonry",
    topContentPadding: Dp,
    bottomContentPadding: Dp,
    onClearAlbumFilter: () -> Unit = {},
    onAddPhotosToAlbum: (() -> Unit)? = null,
    onPermissionGranted: () -> Unit = {},
    onMoveToBin: (Set<String>) -> Unit = {},
    onMoveToLocked: (Set<String>) -> Unit = {},
    onShareItems: (Set<String>) -> Unit = {},
    onAddToAlbum: ((Set<String>) -> Unit)? = null,
    selectedUris: Set<String> = emptySet(),
    onSelectionChange: (Set<String>) -> Unit = {},
    onOpenMemory: (stories: List<StoryGroup>, startIndex: Int) -> Unit = { _, _ -> },
    archiveCount: Int = 0,
    onOpenArchive: (() -> Unit)? = null,
    precomputedGroups: List<DayGroup>? = null,
    precomputedStories: List<StoryGroup>? = null,
    onOpen: (MediaItem) -> Unit
) {
    val context = LocalContext.current
    val homeScope = rememberCoroutineScope()
    var pinchGridColumns by remember(gridColumns) { mutableIntStateOf(gridColumns.coerceIn(1, 6)) }
    var pinchZoomRemainder by remember { mutableFloatStateOf(1f) }
    val selectionMode = selectedUris.isNotEmpty()

    BackHandler(enabled = selectionMode) {
        onSelectionChange(emptySet())
    }
    var granted by remember { mutableStateOf(hasMediaPermission(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val hasPermission = hasMediaPermission(context)
        granted = hasPermission
        if (hasPermission) onPermissionGranted()
    }
    LaunchedEffect(Unit) { granted = hasMediaPermission(context) }
    if (!granted) {
        val permissionsToRequest = when {
            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            )
            Build.VERSION.SDK_INT >= 29 -> arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            )
            else -> arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
        PermissionState { launcher.launch(permissionsToRequest) }
        return
    }

    val sortedMedia = if (precomputedGroups != null) {
        media
    } else {
        remember(media, sortOrder) {
            sortMediaItems(media, sortOrder)
        }
    }

    val groups = precomputedGroups ?: remember(sortedMedia, groupBy) {
        val dayGroups = groupMediaByDay(sortedMedia)
        if (groupBy == "day") {
            dayGroups
        } else {
            when (groupBy) {
                "month" -> groupMediaByMonth(sortedMedia)
                "year" -> groupMediaByYear(sortedMedia)
                "folder" -> groupMediaByFolder(sortedMedia)
                "initial" -> groupMediaByInitial(sortedMedia)
                "none" -> listOf(DayGroup(dayKey = 0L, dayLabel = "", items = sortedMedia))
                else -> dayGroups
            }
        }
    }

    val groupedRows = remember(groups, pinchGridColumns) {
        groups.map { group -> group to group.items.chunked(pinchGridColumns.coerceIn(1, 6)) }
    }

    // Keep a stable map from LazyColumn row keys to compact media rows. Scroll prediction uses
    // row indexes, not all photo cells, so fling planning stays cheap for very large libraries.
    val predictivePrefetchRows = remember(groupedRows) {
        groupedRows.flatMap { (group, rowChunks) ->
            rowChunks.map { row ->
                "day_${group.dayKey}_row_${row.first().id}" to row
            }
        }
    }
    val predictivePrefetchIndex = remember(predictivePrefetchRows) {
        predictivePrefetchRows.mapIndexed { index, row -> row.first to index }.toMap()
    }
    var observedScrollDirection by remember(listState) { mutableIntStateOf(1) }

    val predictivePrefetchConnection = remember(listState, predictivePrefetchRows, predictivePrefetchIndex) {
        object : NestedScrollConnection {
            override suspend fun onPreFling(available: Velocity): Velocity {
                val velocityY = available.y
                if (abs(velocityY) < 1200f || predictivePrefetchRows.isEmpty()) return Velocity.Zero

                // Use the direction observed during the drag rather than relying on platform-specific
                // fling-velocity sign conventions. The velocity magnitude still estimates travel distance.
                val forward = observedScrollDirection > 0
                val visibleRowInfos = listState.layoutInfo.visibleItemsInfo.filter {
                    predictivePrefetchIndex.containsKey(it.key.toString())
                }
                val visibleIndices = visibleRowInfos.mapNotNull {
                    predictivePrefetchIndex[it.key.toString()]
                }
                if (visibleIndices.isEmpty()) return Velocity.Zero

                val anchor = (if (forward) visibleIndices.maxOrNull() else visibleIndices.minOrNull()) ?: return Velocity.Zero
                val averageRowHeight = visibleRowInfos.map { it.size.toFloat() }
                    .average().toFloat().coerceAtLeast(1f)
                // Estimate travel from velocity, then cap work to a bounded window. The fling itself
                // remains native; this only moves likely destination thumbnails to the worker's front.
                val predictedRows = (abs(velocityY) * 0.32f / averageRowHeight)
                    .roundToInt().coerceIn(4, 36)
                val lastIndex = predictivePrefetchRows.lastIndex
                val targetIndices = if (forward) {
                    (anchor + 1..(anchor + predictedRows).coerceAtMost(lastIndex)).toList()
                } else {
                    (anchor - 1 downTo (anchor - predictedRows).coerceAtLeast(0)).toList()
                }
                ThumbnailPrewarmManager.prioritize(
                    targetIndices.flatMap { predictivePrefetchRows[it].second }.take(120)
                )
                return Velocity.Zero
            }
        }
    }

    // Also predict while the finger is dragging (not just when a fling starts). Update the priority
    // window every couple of rows to avoid repeatedly churning the worker queue on every frame.
    LaunchedEffect(listState, predictivePrefetchRows, predictivePrefetchIndex) {
        var previousPosition: Pair<Int, Int>? = null
        var previousAnchor = -1
        var previousDirection = 1

        snapshotFlow {
            val visibleRows = listState.layoutInfo.visibleItemsInfo
                .mapNotNull { predictivePrefetchIndex[it.key.toString()] }
                .distinct()
                .sorted()
            Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, visibleRows)
        }.distinctUntilChanged().collect { (firstIndex, offset, visibleRows) ->
            if (visibleRows.isEmpty() || predictivePrefetchRows.isEmpty()) {
                previousPosition = firstIndex to offset
                return@collect
            }

            val direction = previousPosition?.let { previous ->
                when {
                    firstIndex > previous.first -> 1
                    firstIndex < previous.first -> -1
                    offset > previous.second -> 1
                    offset < previous.second -> -1
                    else -> previousDirection
                }
            } ?: 1
            observedScrollDirection = direction
            val moving = listState.isScrollInProgress
            val anchor = if (direction > 0) visibleRows.last() else visibleRows.first()
            val movedEnough = previousAnchor < 0 || abs(anchor - previousAnchor) >= 2
            if (movedEnough || direction != previousDirection) {
                val forwardCount = if (moving) 14 else 6
                val backwardCount = if (moving) 2 else 4
                val lastIndex = predictivePrefetchRows.lastIndex
                val targetIndices = if (direction > 0) {
                    (anchor..(anchor + forwardCount).coerceAtMost(lastIndex)).toList() +
                        (anchor - 1 downTo (anchor - backwardCount).coerceAtLeast(0)).toList()
                } else {
                    (anchor downTo (anchor - forwardCount).coerceAtLeast(0)).toList() +
                        (anchor + 1..(anchor + backwardCount).coerceAtMost(lastIndex)).toList()
                }
                ThumbnailPrewarmManager.prioritize(
                    targetIndices.distinct().flatMap { predictivePrefetchRows[it].second }.take(120)
                )
                previousAnchor = anchor
                previousDirection = direction
            }
            previousPosition = firstIndex to offset
        }
    }

    val hapticFeedback = LocalHapticFeedback.current
    val pullRefreshState = rememberPullToRefreshState()

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            onRefresh?.invoke()
        },
        modifier = Modifier.fillMaxSize(),
        state = pullRefreshState,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullRefreshState,
                isRefreshing = isRefreshing,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = topContentPadding)
            )
        }
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().nestedScroll(predictivePrefetchConnection)
        ) {
        val containerWidth = maxWidth - 24.dp
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            // Pinch-to-zoom requires 2 or more touches. Single-finger vertical scrolls
                            // are NEVER consumed or intercepted, preserving native Android system scroll physics,
                            // fling decay, touch slop, and overscroll on LazyColumn.
                            if (event.changes.size >= 2) {
                                val zoomChange = event.calculateZoom()
                                if (zoomChange != 1f) {
                                    pinchZoomRemainder *= zoomChange
                                    if (pinchZoomRemainder < 0.82f) {
                                        pinchGridColumns = (pinchGridColumns + 1).coerceAtMost(6)
                                        pinchZoomRemainder = 1f
                                    } else if (pinchZoomRemainder > 1.22f) {
                                        pinchGridColumns = (pinchGridColumns - 1).coerceAtLeast(1)
                                        pinchZoomRemainder = 1f
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = topContentPadding,
                bottom = bottomContentPadding
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (albumTitle != null) {
                val isCollection = albumTitle in listOf("Locked folder", "Bin", "Archive", "Favourites", "Videos", "Recently added")
                val collectionIcon = when (albumTitle) {
                    "Favourites" -> Icons.Default.Favorite
                    "Videos" -> Icons.Default.PlayCircle
                    "Recently added" -> Icons.Default.Schedule
                    "Locked folder" -> Icons.Default.Lock
                    "Bin" -> Icons.Default.Delete
                    "Archive" -> Icons.Default.Archive
                    else -> Icons.Default.Folder
                }
                item {
                    val returnProgress = LocalViewerCoordinatedProgress.current
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                            .destinationTopBarEntrance(returnProgress),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(collectionIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (isCollection) "Collection" else "Album", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
                                Text(albumTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (onAddPhotosToAlbum != null) {
                                FilledTonalButton(
                                    onClick = onAddPhotosToAlbum,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Add photos", style = MaterialTheme.typography.labelMedium)
                                }
                                Spacer(Modifier.width(6.dp))
                            }
                            IconButton(onClick = onClearAlbumFilter, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Close, "Clear filter", tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }
                }
            }

            if (sortedMedia.isEmpty()) {
                if (!loading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp, horizontal = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (albumTitle != null) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(54.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.height(12.dp))
                                    Text("No photos in this album", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                    Spacer(Modifier.height(6.dp))
                                    Text("Add photos from your gallery to populate this album.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                    if (onAddPhotosToAlbum != null) {
                                        Spacer(Modifier.height(16.dp))
                                        Button(onClick = onAddPhotosToAlbum) {
                                            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Add photos")
                                        }
                                    }
                                }
                            } else {
                                Text("No photos or videos found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            } else {
                if (loading) {
                    item { LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape)) }
                }
                if (albumTitle == null) {
                    item(key = "memories_strip") {
                        MemoriesStrip(
                            media = sortedMedia,
                            precomputedStories = precomputedStories,
                            onOpenMemory = onOpenMemory
                        )
                    }
                }

                groupedRows.forEach { (group, rowChunks) ->
                    if (group.dayLabel.isNotBlank()) {
                        item(key = "header_${group.dayKey}", contentType = "header") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = group.dayLabel,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "${group.items.size} ${if (group.items.size == 1) "item" else "items"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    items(
                        count = rowChunks.size,
                        key = { rIdx -> "day_${group.dayKey}_row_${rowChunks[rIdx].first().id}" },
                        contentType = { "photo_row" }
                    ) { rowIndex ->
                        JustifiedMosaicRow(
                            items = rowChunks[rowIndex],
                            containerWidth = containerWidth,
                            rowIndex = rowIndex,
                            isSquare = (gridStyle == "square"),
                            selectedUris = selectedUris,
                            onLongPress = { item -> onSelectionChange(selectedUris + item.uri) },
                            onToggleSelection = { item -> onSelectionChange(if (item.uri in selectedUris) selectedUris - item.uri else selectedUris + item.uri) },
                            onOpen = { item -> if (selectionMode) { onSelectionChange(if (item.uri in selectedUris) selectedUris - item.uri else selectedUris + item.uri) } else onOpen(item) }
                        )
                    }
                }
            }
        }

        HomeFastScrollbar(
            listState = listState,
            topContentPadding = topContentPadding,
            bottomContentPadding = bottomContentPadding,
            thumbColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    }
}


