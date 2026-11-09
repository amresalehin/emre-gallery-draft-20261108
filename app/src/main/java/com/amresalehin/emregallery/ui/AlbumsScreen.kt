package com.amresalehin.emregallery.ui

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.amresalehin.emregallery.face.FaceClusterStore
import com.amresalehin.emregallery.face.FaceRecognitionSettings
import com.amresalehin.emregallery.face.FaceStoreEvents
import androidx.compose.ui.draw.alpha
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amresalehin.emregallery.face.FaceProcessingCoordinator
import com.amresalehin.emregallery.face.PersonSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.amresalehin.emregallery.data.CustomAlbum
import com.amresalehin.emregallery.data.MediaItem
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(
    media: List<MediaItem>,
    favourites: Set<String>,
    hidden: Set<String>,
    inBin: Set<String>,
    archive: Set<String>,
    customAlbums: List<CustomAlbum>,
    topContentPadding: Dp,
    bottomContentPadding: Dp,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    onFilter: (String) -> Unit,
    onOpen: (MediaItem) -> Unit,
    onCreateAlbumClick: (parentPreset: String) -> Unit,
    onDeleteAlbum: (String) -> Unit,
    onRenameAlbum: (String, String) -> Unit,
    onOpenPerson: ((String) -> Unit)? = null,
    onOpenSavedLibrary: (() -> Unit)? = null,
    onOpenSavedStack: ((com.amresalehin.emregallery.data.MediaInboxStack) -> Unit)? = null,
    onOpenBrowser: (() -> Unit)? = null
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        com.amresalehin.emregallery.GalleryApplication.warmupChromium(context)
    }
    val isProcessingActive by FaceProcessingCoordinator.isProcessingActive.collectAsStateWithLifecycle()
    val isBusy = isProcessingActive || FaceProcessingCoordinator.isBusy(context)
    var selectedEngineId by remember { mutableStateOf(FaceRecognitionSettings.getActiveEngine(context)) }
    val faceStore = remember(context) { FaceClusterStore(context.applicationContext) }
    var detectedPeople by remember { mutableStateOf(emptyList<PersonSummary>()) }
    var engineMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(selectedEngineId) {
        suspend fun refreshPeople() {
            detectedPeople = withContext(Dispatchers.IO) {
                faceStore.personSummaries(engineId = selectedEngineId)
            }
        }
        refreshPeople()
        kotlinx.coroutines.coroutineScope {
            launch { FaceStoreEvents.peopleUpdates.collect { refreshPeople() } }
            launch { FaceStoreEvents.mediaUpdates.collect { refreshPeople() } }
        }
    }
    val normalMedia = remember(media, hidden, inBin, archive) {
        media.filter { it.uri !in hidden && it.uri !in inBin && it.uri !in archive }
    }
    var folders by remember(normalMedia) {
        mutableStateOf(
            if (normalMedia.size <= 250) {
                normalMedia.groupBy { it.relativePath.substringBeforeLast('/', "Internal storage").ifBlank { "Internal storage" } }
                    .entries.sortedByDescending { it.value.size }
            } else {
                normalMedia.take(250).groupBy { it.relativePath.substringBeforeLast('/', "Internal storage").ifBlank { "Internal storage" } }
                    .entries.sortedByDescending { it.value.size }
            }
        )
    }
    LaunchedEffect(normalMedia) {
        if (normalMedia.size > 250) {
            val fullFolders = withContext(Dispatchers.Default) {
                normalMedia.groupBy { it.relativePath.substringBeforeLast('/', "Internal storage").ifBlank { "Internal storage" } }
                    .entries.sortedByDescending { it.value.size }
            }
            folders = fullFolders
        }
    }

    val favouritesCount = favourites.size
    val binCount = inBin.size
    val hiddenCount = hidden.size
    val archiveCount = archive.size

    val downloadActivities by com.amresalehin.emregallery.data.DownloadActivityManager.activities.collectAsState()
    val activityCountText = remember(downloadActivities) {
        val active = downloadActivities.count { it.status == com.amresalehin.emregallery.data.DownloadActivityStatus.DOWNLOADING || it.status == com.amresalehin.emregallery.data.DownloadActivityStatus.RETRYING }
        val failed = downloadActivities.count { it.status == com.amresalehin.emregallery.data.DownloadActivityStatus.FAILED }
        when {
            active > 0 -> "$active active"
            failed > 0 -> "$failed failed"
            downloadActivities.isNotEmpty() -> "${downloadActivities.size} items"
            else -> ""
        }
    }

    var albumToRename by remember { mutableStateOf<CustomAlbum?>(null) }
    var renameText by remember { mutableStateOf("") }

    val activeAlbumToRename = albumToRename
    if (activeAlbumToRename != null) {
        AlertDialog(
            onDismissRequest = { albumToRename = null },
            title = { Text("Rename album") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("New album name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val album = albumToRename ?: activeAlbumToRename
                        if (renameText.isNotBlank()) {
                            onRenameAlbum(album.id, renameText.trim())
                        }
                        albumToRename = null
                    },
                    enabled = renameText.isNotBlank()
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { albumToRename = null }) { Text("Cancel") }
            }
        )
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
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = topContentPadding,
            bottom = bottomContentPadding
        )
    ) {
        // 0. The tiles - Collections (Favourites, Bin, Locked folder, Inbox) moved to top
        item {
            // Top primary pills: Favourites & Bin
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CollectionPill(
                    title = "Favourites",
                    count = "$favouritesCount items",
                    icon = Icons.Default.Favorite,
                    iconTint = MaterialTheme.colorScheme.onSurface,
                    iconBg = MaterialTheme.colorScheme.surfaceContainerHigh,
                    onClick = { onFilter("Favourites") },
                    modifier = Modifier.weight(1f)
                )
                CollectionPill(
                    title = "Bin",
                    count = "$binCount items",
                    icon = Icons.Default.Delete,
                    iconTint = MaterialTheme.colorScheme.onSurface,
                    iconBg = MaterialTheme.colorScheme.surfaceContainerHigh,
                    onClick = { onFilter("Bin") },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(10.dp))

            // 2-column tiles: Locked folder & Activity
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CollectionTile(
                    title = "Locked folder",
                    count = if (hiddenCount > 0) "$hiddenCount items" else "Private",
                    icon = Icons.Default.Lock,
                    iconTint = MaterialTheme.colorScheme.onSurface,
                    iconBg = MaterialTheme.colorScheme.surfaceContainerHigh,
                    onClick = { onFilter("Hidden") },
                    modifier = Modifier.weight(1f)
                )
                CollectionTile(
                    title = "Activity",
                    count = activityCountText,
                    icon = Icons.Default.MonitorHeart,
                    iconTint = MaterialTheme.colorScheme.onSurface,
                    iconBg = MaterialTheme.colorScheme.surfaceContainerHigh,
                    onClick = { onFilter("Activity") },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(10.dp))

            // 2-column tiles: Browser & Archive
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CollectionTile(
                    title = "Browser",
                    count = "",
                    icon = Icons.Default.TravelExplore,
                    iconTint = MaterialTheme.colorScheme.onSurface,
                    iconBg = MaterialTheme.colorScheme.surfaceContainerHigh,
                    onClick = { onOpenBrowser?.invoke() ?: onFilter("Browser") },
                    modifier = Modifier.weight(1f)
                )
                CollectionTile(
                    title = "Archive",
                    count = "$archiveCount items",
                    icon = Icons.Default.Archive,
                    iconTint = MaterialTheme.colorScheme.onSurface,
                    iconBg = MaterialTheme.colorScheme.surfaceContainerHigh,
                    onClick = { onFilter("Archive") },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 1. People card: single row, very large clickable faces, model dropdown pill, and 'See all >'
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 2.dp,
                shadowElevation = 4.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("albums_people_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Header Row: Model selector pill on left, "See all >" on right
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Model Dropdown Pill
                        Box {
                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                                modifier = Modifier
                                    .clickable(enabled = !isBusy) { engineMenuExpanded = true }
                                    .alpha(if (!isBusy) 1.0f else 0.5f)
                                    .testTag("album_face_model_chip")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Face,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = if (selectedEngineId == com.amresalehin.emregallery.face.EnteFaceConstants.ENGINE_ID) "YOLO5Face" else "Buffalo-L",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Select engine",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = engineMenuExpanded && !isBusy,
                                onDismissRequest = { engineMenuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Buffalo-L") },
                                    enabled = !isBusy,
                                    trailingIcon = if (selectedEngineId == com.amresalehin.emregallery.face.BuffaloLFaceEngine.ENGINE_ID) {
                                        { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                                    } else null,
                                    onClick = {
                                        engineMenuExpanded = false
                                        if (!FaceProcessingCoordinator.isBusy(context)) {
                                            selectedEngineId = com.amresalehin.emregallery.face.BuffaloLFaceEngine.ENGINE_ID
                                            FaceRecognitionSettings.setActiveEngine(context, com.amresalehin.emregallery.face.BuffaloLFaceEngine.ENGINE_ID)
                                            com.amresalehin.emregallery.face.FaceEngineRegistry.setActiveEngine(com.amresalehin.emregallery.face.BuffaloLFaceEngine.ENGINE_ID)
                                            FaceStoreEvents.notifyAllUpdated()
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("YOLO5Face + MobileFaceNet") },
                                    enabled = !isBusy,
                                    trailingIcon = if (selectedEngineId == com.amresalehin.emregallery.face.EnteFaceConstants.ENGINE_ID) {
                                        { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                                    } else null,
                                    onClick = {
                                        engineMenuExpanded = false
                                        if (!FaceProcessingCoordinator.isBusy(context)) {
                                            selectedEngineId = com.amresalehin.emregallery.face.EnteFaceConstants.ENGINE_ID
                                            FaceRecognitionSettings.setActiveEngine(context, com.amresalehin.emregallery.face.EnteFaceConstants.ENGINE_ID)
                                            com.amresalehin.emregallery.face.FaceEngineRegistry.setActiveEngine(com.amresalehin.emregallery.face.EnteFaceConstants.ENGINE_ID)
                                            FaceStoreEvents.notifyAllUpdated()
                                        }
                                    }
                                )
                            }
                        }

                        // "See all >" Pill
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .clickable { onFilter("People") }
                                .testTag("album_face_see_all_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "See all",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = "See all people",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Single horizontal row containing very large circular faces
                    if (detectedPeople.isEmpty()) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            items(4) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                                    modifier = Modifier
                                        .size(96.dp)
                                        .clickable { onFilter("People") }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.Face,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                            modifier = Modifier.size(38.dp)
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        androidx.compose.foundation.lazy.LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            items(detectedPeople.size, key = { detectedPeople[it].id }) { index ->
                                val person = detectedPeople[index]
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .width(96.dp)
                                        .clickable(enabled = !isBusy) {
                                            if (!FaceProcessingCoordinator.isBusy(context)) {
                                                if (onOpenPerson != null) {
                                                    onOpenPerson(person.id)
                                                } else {
                                                    onFilter("People")
                                                }
                                            }
                                        }
                                        .alpha(if (!isBusy) 1.0f else 0.5f)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                        modifier = Modifier.size(96.dp)
                                    ) {
                                        FaceCropThumbnail(
                                            mediaUri = person.portraitUri ?: person.sampleUri,
                                            faceIndex = person.portraitFaceIndex,
                                            engineId = person.engineId.ifBlank { selectedEngineId },
                                            contentDescription = person.displayName.takeIf { it.isNotBlank() } ?: "Person",
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(CircleShape)
                                        )
                                    }

                                    // Under each face: show name ONLY if configured/available. If not, show nothing!
                                    if (person.isNamed && person.displayName.isNotBlank()) {
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            text = person.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Albums Section (full-width section with header, count, 'See all >' pill, and carousel)
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 2.dp,
                shadowElevation = 4.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("albums_section_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Header Row: album icon, "Albums", count, and "See all >" pill
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoLibrary,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                text = "Albums",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = customAlbums.size.toString(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // "See all >" Pill
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .clickable { onFilter("Albums") }
                                .testTag("albums_see_all_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "See all",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = "See all albums",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Content Carousel or Empty State
                    if (customAlbums.isEmpty()) {
                        AlbumsEmptyState(onCreateClick = { onCreateAlbumClick("") })
                    } else {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(customAlbums, key = { it.id }) { album ->
                                val palette = AlbumPalettes[customAlbums.indexOf(album) % AlbumPalettes.size]
                                AlbumCardItem(
                                    album = album,
                                    palette = palette,
                                    normalMedia = normalMedia,
                                    onClick = { onFilter(album.name) },
                                    onRename = {
                                        albumToRename = album
                                        renameText = album.name
                                    },
                                    onDelete = { onDeleteAlbum(album.id) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Folders Section (full-width section with header, count, 'See all >' pill, and carousel)
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 2.dp,
                shadowElevation = 4.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("folders_section_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Header Row: folder icon, "Folders", count, and "See all >" pill
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                text = "Folders",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = folders.size.toString(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // "See all >" Pill
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .clickable { onFilter("Folders") }
                                .testTag("folders_see_all_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "See all",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = "See all folders",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Content Carousel or Empty State
                    if (folders.isEmpty()) {
                        FoldersEmptyState()
                    } else {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(folders, key = { it.key }) { folder ->
                                val palette = FolderPalettes[folders.indexOf(folder) % FolderPalettes.size]
                                FolderCardItem(
                                    folderKey = folder.key,
                                    items = folder.value,
                                    palette = palette,
                                    onClick = { onFilter(folder.key) }
                                )
                            }
                        }
                    }
                }
            }
        }


        // 3. Maps Card (strictly black-grey-white)
        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 2.dp,
                shadowElevation = 5.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onFilter("Map") }
                    .testTag("albums_map_card")
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                ) {
                    var mapPreviewReady by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        kotlinx.coroutines.delay(160)
                        mapPreviewReady = true
                    }
                    if (mapPreviewReady) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                Configuration.getInstance().userAgentValue = ctx.packageName
                                MapView(ctx).apply {
                                    setTileSource(TileSourceFactory.MAPNIK)
                                    setMultiTouchControls(false)
                                    setBuiltInZoomControls(false)
                                    controller.setZoom(4.5)
                                    controller.setCenter(GeoPoint(22.5, 79.0))
                                    isClickable = false
                                    isFocusable = false
                                }
                            },
                            update = { preview ->
                                preview.setOnTouchListener { _, _ -> false }
                                preview.invalidate()
                            }
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                            MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    )
                                )
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clickable { onFilter("Map") }
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.08f),
                                        Color.Transparent,
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.76f)
                                    )
                                )
                            )
                    )

                    // Content overlay
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                modifier = Modifier.size(50.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Place,
                                        contentDescription = "Map and Places",
                                        modifier = Modifier.size(28.dp),
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        Text(
                            text = "Map",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
    }
}

data class CardPalette(
    val bgColor: Color,
    val textColor: Color,
    val badgeBg: Color
)

private val FolderPalettes = listOf(
    CardPalette(bgColor = Color(0xFFFFD572), textColor = Color(0xFF231B05), badgeBg = Color(0xFF383120)),
    CardPalette(bgColor = Color(0xFFA5D1FE), textColor = Color(0xFF0F1E2E), badgeBg = Color(0xFF22364A)),
    CardPalette(bgColor = Color(0xFFD6B4FC), textColor = Color(0xFF251336), badgeBg = Color(0xFF3B274B)),
    CardPalette(bgColor = Color(0xFFA6E5A8), textColor = Color(0xFF132B14), badgeBg = Color(0xFF243E26)),
    CardPalette(bgColor = Color(0xFFFFB5B5), textColor = Color(0xFF331414), badgeBg = Color(0xFF4A2626)),
    CardPalette(bgColor = Color(0xFF98E8DE), textColor = Color(0xFF0E2B27), badgeBg = Color(0xFF1E3F3C)),
    CardPalette(bgColor = Color(0xFFFFC39A), textColor = Color(0xFF331B0A), badgeBg = Color(0xFF4D301C))
)

private val AlbumPalettes = listOf(
    CardPalette(bgColor = Color(0xFFE8DEF8), textColor = Color(0xFF25163D), badgeBg = Color(0xFF3C2754)),
    CardPalette(bgColor = Color(0xFFFFD8E4), textColor = Color(0xFF3B1020), badgeBg = Color(0xFF522234)),
    CardPalette(bgColor = Color(0xFFD0E4FF), textColor = Color(0xFF0A223B), badgeBg = Color(0xFF1E3A57)),
    CardPalette(bgColor = Color(0xFFBCEBD9), textColor = Color(0xFF0D2D23), badgeBg = Color(0xFF234539)),
    CardPalette(bgColor = Color(0xFFFFF0B3), textColor = Color(0xFF332505), badgeBg = Color(0xFF4A3815)),
    CardPalette(bgColor = Color(0xFFFFD9D0), textColor = Color(0xFF3B120B), badgeBg = Color(0xFF54251D))
)

class FolderTabShape(
    private val tabWidthRatio: Float = 0.44f,
    private val tabHeightDp: Dp = 12.dp,
    private val cornerRadiusDp: Dp = 18.dp
) : Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density
    ): androidx.compose.ui.graphics.Outline {
        val r = with(density) { cornerRadiusDp.toPx() }
        val th = with(density) { tabHeightDp.toPx() }
        val tw = (size.width * tabWidthRatio).coerceAtMost(size.width - r * 2)
        val w = size.width
        val h = size.height

        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, r)
            quadraticTo(0f, 0f, r, 0f)
            lineTo(tw - r * 0.5f, 0f)
            cubicTo(
                tw, 0f,
                tw + 3f, th,
                tw + r * 0.7f, th
            )
            lineTo(w - r, th)
            quadraticTo(w, th, w, th + r)
            lineTo(w, h - r)
            quadraticTo(w, h, w - r, h)
            lineTo(r, h)
            quadraticTo(0f, h, 0f, h - r)
            close()
        }
        return androidx.compose.ui.graphics.Outline.Generic(path)
    }
}

@Composable
private fun FolderCardItem(
    folderKey: String,
    items: List<MediaItem>,
    palette: CardPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val folderTitle = folderKey.substringAfterLast('/').ifBlank { "Internal storage" }
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        shape = FolderTabShape(),
        color = palette.bgColor,
        modifier = modifier
            .width(160.dp)
            .height(220.dp)
            .clickable(onClick = onClick)
            .testTag("folder_card_${folderTitle.replace(' ', '_')}")
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Count badge at top-right in recessed area beside folder tab
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = palette.badgeBg.copy(alpha = 0.92f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 8.dp)
            ) {
                Text(
                    text = items.size.toString(),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    ),
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 10.dp, end = 10.dp, top = 26.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 2x2 Collage of up to 4 images
                Collage2x2(
                    items = items.take(4),
                    palette = palette,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(136.dp)
                )

                // Bottom title row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = folderTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = palette.textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Box {
                        IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Folder options",
                                tint = palette.textColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Open folder") },
                                onClick = {
                                    menuOpen = false
                                    onClick()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumCardItem(
    album: CustomAlbum,
    palette: CardPalette,
    normalMedia: List<MediaItem>,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    val albumMedia = remember(album, normalMedia) {
        normalMedia.filter { it.uri in album.mediaUris }.take(4)
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = palette.bgColor,
        border = BorderStroke(1.5.dp, palette.textColor.copy(alpha = 0.22f)),
        modifier = modifier
            .width(160.dp)
            .height(220.dp)
            .clickable(onClick = onClick)
            .testTag("album_card_${album.id}")
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Album spine binding accent on left
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .padding(vertical = 14.dp)
                    .align(Alignment.CenterStart)
                    .background(
                        palette.textColor.copy(alpha = 0.16f),
                        RoundedCornerShape(2.dp)
                    )
            )

            // Count badge at top-right
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = palette.badgeBg.copy(alpha = 0.92f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp)
            ) {
                Text(
                    text = album.mediaUris.size.toString(),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    ),
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 12.dp, end = 10.dp, top = 26.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 2x2 collage
                Collage2x2(
                    items = albumMedia,
                    palette = palette,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(136.dp)
                )

                // Bottom title row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = album.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = palette.textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Box {
                        IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Album options",
                                tint = palette.textColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Open album") },
                                onClick = {
                                    menuOpen = false
                                    onClick()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = {
                                    menuOpen = false
                                    onRename()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Edit, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = {
                                    menuOpen = false
                                    onDelete()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Delete, contentDescription = null)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Collage2x2(
    items: List<MediaItem>,
    palette: CardPalette,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            CollageCell(
                item = items.getOrNull(0),
                palette = palette,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
            CollageCell(
                item = items.getOrNull(1),
                palette = palette,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            CollageCell(
                item = items.getOrNull(2),
                palette = palette,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
            CollageCell(
                item = items.getOrNull(3),
                palette = palette,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }
    }
}

@Composable
private fun CollageCell(
    item: MediaItem?,
    palette: CardPalette,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(palette.textColor.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center
    ) {
        if (item != null) {
            val context = LocalContext.current
            AsyncImage(
                model = remember(item.uri, context) {
                    ThumbnailUtils.buildThumbnailRequest(context, item.uri, 200, 200)
                },
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                tint = palette.textColor.copy(alpha = 0.22f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun AlbumsEmptyState(
    onCreateClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface
    val cardBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.4f else 0.55f)
    val textBarPrimary = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isDark) 0.32f else 0.22f)
    val textBarSecondary = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isDark) 0.20f else 0.12f)

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(4) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = cardBg,
                border = BorderStroke(1.dp, cardBorder),
                modifier = Modifier
                    .width(160.dp)
                    .height(220.dp)
                    .clickable { onCreateClick() }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // 2x2 placeholder grid
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(136.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            PlaceholderGridCell(Modifier.weight(1f).fillMaxHeight())
                            PlaceholderGridCell(Modifier.weight(1f).fillMaxHeight())
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            PlaceholderGridCell(Modifier.weight(1f).fillMaxHeight())
                            PlaceholderGridCell(Modifier.weight(1f).fillMaxHeight())
                        }
                    }

                    // Skeleton text bars at bottom
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.68f)
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(textBarPrimary)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.42f)
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(textBarSecondary)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FoldersEmptyState() {
    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface
    val cardBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.4f else 0.55f)
    val textBarPrimary = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isDark) 0.32f else 0.22f)
    val textBarSecondary = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isDark) 0.20f else 0.12f)

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(4) {
            Surface(
                shape = FolderTabShape(),
                color = cardBg,
                border = BorderStroke(1.dp, cardBorder),
                modifier = Modifier
                    .width(160.dp)
                    .height(220.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 10.dp, end = 10.dp, top = 26.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(136.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            PlaceholderGridCell(Modifier.weight(1f).fillMaxHeight())
                            PlaceholderGridCell(Modifier.weight(1f).fillMaxHeight())
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            PlaceholderGridCell(Modifier.weight(1f).fillMaxHeight())
                            PlaceholderGridCell(Modifier.weight(1f).fillMaxHeight())
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.68f)
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(textBarPrimary)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.42f)
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(textBarSecondary)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceholderGridCell(modifier: Modifier = Modifier) {
    val isDark = isSystemInDarkTheme()
    val cellBg = if (isDark) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer
    val iconTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isDark) 0.38f else 0.32f)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(cellBg),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Image,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(20.dp)
        )
    }
}
