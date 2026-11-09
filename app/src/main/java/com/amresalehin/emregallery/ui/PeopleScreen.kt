package com.amresalehin.emregallery.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.asImageBitmap
import com.amresalehin.emregallery.face.FaceEngineRegistry
import com.amresalehin.emregallery.face.FaceThumbnailHelper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.amresalehin.emregallery.data.MediaItem
import com.amresalehin.emregallery.face.BuffaloLFaceEngine
import com.amresalehin.emregallery.face.EnteFaceConstants
import com.amresalehin.emregallery.face.FaceClusterStore
import com.amresalehin.emregallery.face.FaceRecognitionSettings
import com.amresalehin.emregallery.face.FaceStoreEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.draw.alpha
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amresalehin.emregallery.face.FaceProcessingCoordinator
import com.amresalehin.emregallery.face.PersonSummary
import com.amresalehin.emregallery.face.ReviewSuggestion
import java.util.Locale

private data class PeopleScreenSnapshot(
    val people: List<PersonSummary>,
    val hiddenPeople: List<PersonSummary>,
    val suggestions: List<ReviewSuggestion>,
    val mediaIndex: Map<String, com.amresalehin.emregallery.face.PersonMediaIndex>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(
    media: List<MediaItem>,
    onBack: () -> Unit,
    onOpenMedia: (MediaItem) -> Unit,
    onOpenIndexStatus: () -> Unit,
    initialPersonId: String? = null
) {
    val context = LocalContext.current
    val store = remember { FaceClusterStore(context.applicationContext) }
    val photoByUri = remember(media) { media.filterNot { it.isVideo }.associateBy { it.uri } }

    var selectedEngineId by remember { mutableStateOf(FaceRecognitionSettings.getActiveEngine(context)) }
    var allPeople by remember { mutableStateOf(emptyList<PersonSummary>()) }
    var hiddenPeople by remember { mutableStateOf(emptyList<PersonSummary>()) }
    var pendingSuggestions by remember { mutableStateOf(emptyList<ReviewSuggestion>()) }
    var personMediaIndex by remember { mutableStateOf<Map<String, com.amresalehin.emregallery.face.PersonMediaIndex>>(emptyMap()) }
    val reloadScope = rememberCoroutineScope()
    var reloadGeneration by remember { mutableIntStateOf(0) }

    var selectedPersonId by remember(initialPersonId) { mutableStateOf<String?>(initialPersonId) }
    var searchQuery by remember { mutableStateOf("") }
    var showAllPeople by remember { mutableStateOf(false) }
    var isSortAscending by remember { mutableStateOf(false) }
    var engineDropdownExpanded by remember { mutableStateOf(false) }
    var showAvatarPicker by remember { mutableStateOf(false) }

    val isProcessingActive by FaceProcessingCoordinator.isProcessingActive.collectAsStateWithLifecycle()
    val isBusy = isProcessingActive || FaceProcessingCoordinator.isBusy(context)

    fun setEngine(target: String) {
        if (FaceProcessingCoordinator.isBusy(context)) return
        selectedEngineId = target
        FaceRecognitionSettings.setActiveEngine(context, target)
        FaceEngineRegistry.setActiveEngine(target)
        FaceStoreEvents.notifyAllUpdated()
    }

    // Dialog states
    var renamePersonId by remember { mutableStateOf<String?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var personToMerge by remember { mutableStateOf<PersonSummary?>(null) }
    var selectedTargetMergeId by remember { mutableStateOf<String?>(null) }
    var personMenuExpanded by remember { mutableStateOf(false) }
    var selectedMediaForAction by remember { mutableStateOf<MediaItem?>(null) }

    fun reload() {
        reloadGeneration += 1
        val request = reloadGeneration
        val engineId = selectedEngineId
        val availableUris = photoByUri.keys.toList()
        reloadScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                PeopleScreenSnapshot(
                    people = store.personSummaries(includeHidden = false, engineId = engineId),
                    hiddenPeople = store.personSummaries(includeHidden = true, engineId = engineId).filter { it.isHidden },
                    suggestions = store.getPendingSuggestions(engineId = engineId),
                    mediaIndex = store.personMediaIndex(engineId, availableUris)
                )
            }
            if (request == reloadGeneration && engineId == selectedEngineId) {
                allPeople = snapshot.people
                hiddenPeople = snapshot.hiddenPeople
                pendingSuggestions = snapshot.suggestions
                personMediaIndex = snapshot.mediaIndex
            }
        }
    }

    LaunchedEffect(selectedEngineId) {
        if (initialPersonId == null) {
            selectedPersonId = null
        }
    }

    LaunchedEffect(selectedEngineId, photoByUri) {
        reload()
        kotlinx.coroutines.coroutineScope {
            launch { FaceStoreEvents.peopleUpdates.collect { reload() } }
            launch { FaceStoreEvents.mediaUpdates.collect { reload() } }
        }
    }

    val minimumGroupFaces = FaceRecognitionSettings.minGroupFaces(context, selectedEngineId)
    val visiblePeople = remember(allPeople, personMediaIndex, photoByUri, minimumGroupFaces, selectedEngineId, showAllPeople) {
        allPeople.mapNotNull { person ->
            val index = personMediaIndex[person.id]
            val count = index?.faceCount ?: 0
            if (!showAllPeople && count < minimumGroupFaces && !person.isNamed) null
            else person.copy(
                faceCount = count,
                sampleUri = person.sampleUri?.takeIf { it in photoByUri } ?: index?.mediaUris?.firstOrNull()
            )
        }.sortedWith(compareByDescending<PersonSummary> { it.faceCount }
            .thenBy { it.displayName.lowercase(Locale.getDefault()) })
    }

    val selectedPerson = remember(visiblePeople, allPeople, selectedPersonId) {
        selectedPersonId?.let { id ->
            allPeople.firstOrNull { it.id == id } ?: visiblePeople.firstOrNull { it.id == id }
                ?: run {
                    val pName = store.getPersonName(id)
                    val sampleUri = store.facesForCluster(id, selectedEngineId).firstOrNull()?.mediaUri
                    PersonSummary(
                        id = id,
                        displayName = pName,
                        isNamed = pName.isNotBlank() && !pName.equals("Unknown", ignoreCase = true),
                        faceCount = 0,
                        sampleUri = sampleUri,
                        engineId = selectedEngineId
                    )
                }
        }
    }

    val matchingPeople = remember(visiblePeople, searchQuery) {
        if (searchQuery.isBlank()) visiblePeople
        else visiblePeople.filter { it.displayName.contains(searchQuery.trim(), ignoreCase = true) }
    }

    val sortedMatchingPeople = remember(matchingPeople, isSortAscending) {
        if (isSortAscending) {
            matchingPeople.sortedWith(compareBy<PersonSummary> { !it.isNamed }
                .thenBy { it.displayName.lowercase(Locale.getDefault()) })
        } else {
            matchingPeople
        }
    }

    val detailMedia = remember(selectedPersonId, photoByUri, personMediaIndex, selectedEngineId, reloadGeneration) {
        selectedPersonId?.let { id ->
            val urisFromIndex = personMediaIndex[id]?.mediaUris.orEmpty()
            val uris = if (urisFromIndex.isNotEmpty()) {
                urisFromIndex
            } else {
                store.facesForCluster(id, selectedEngineId).map { it.mediaUri }.toSet()
            }
            uris.mapNotNull { photoByUri[it] }
                .distinctBy { it.uri }
                .sortedByDescending { it.dateTakenMillis.takeIf { d -> d > 0 } ?: it.dateAddedSeconds * 1000L }
        }.orEmpty()
    }

    val dayGroups = remember(detailMedia) {
        groupMediaByDay(detailMedia)
    }

    val collapsedDayKeys = remember(selectedPersonId) { mutableStateListOf<Long>() }

    BackHandler(enabled = selectedPersonId != null) {
        if (initialPersonId != null && selectedPersonId == initialPersonId) {
            onBack()
        } else {
            selectedPersonId = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        if (selectedPersonId == null) {
            // Top App Bar / Search Bar (Matching Image 3)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "Back to albums"
                    )
                }

                // Search Bar pill
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        androidx.compose.foundation.text.BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            decorationBox = { innerTextField ->
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        "People and pets",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                innerTextField()
                            },
                            modifier = Modifier.weight(1f)
                        )
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                // Sort toggle button
                IconButton(
                    onClick = { isSortAscending = !isSortAscending },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapVert,
                        contentDescription = "Sort people",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Model dropdown chip
                Box {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .clickable(enabled = !isBusy) { engineDropdownExpanded = true }
                            .alpha(if (!isBusy) 1.0f else 0.5f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = if (selectedEngineId == EnteFaceConstants.ENGINE_ID) "YOLO5Face" else "Buffalo-L",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "Select engine",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = engineDropdownExpanded && !isBusy,
                        onDismissRequest = { engineDropdownExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Buffalo-L") },
                            enabled = !isBusy,
                            trailingIcon = if (selectedEngineId == BuffaloLFaceEngine.ENGINE_ID) {
                                { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                            } else null,
                            onClick = {
                                engineDropdownExpanded = false
                                setEngine(BuffaloLFaceEngine.ENGINE_ID)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("YOLO5Face + MobileFaceNet") },
                            enabled = !isBusy,
                            trailingIcon = if (selectedEngineId == EnteFaceConstants.ENGINE_ID) {
                                { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                            } else null,
                            onClick = {
                                engineDropdownExpanded = false
                                setEngine(EnteFaceConstants.ENGINE_ID)
                            }
                        )
                    }
                }
            }

            // Main People Grid: Clean 3 columns, no photo counts, names shown only if configured
            if (sortedMatchingPeople.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Face,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (searchQuery.isBlank()) "No face groups found yet" else "No matching people",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = if (searchQuery.isBlank()) "Scan photos in Index Status to identify people and group their faces." else "Try another name or clear your search.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        if (searchQuery.isBlank()) {
                            Button(onClick = onOpenIndexStatus) {
                                Text("Open Index Status")
                            }
                        } else {
                            TextButton(onClick = { searchQuery = "" }) {
                                Text("Clear search")
                            }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp)
                ) {
                    gridItems(sortedMatchingPeople, key = { it.id }) { person ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedPersonId = person.id },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Circular face avatar
                            PersonAvatar(person = person, size = 96.dp)

                            // Under avatar: Show name ONLY if configured. If not, show nothing!
                            if (person.isNamed && person.displayName.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = person.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Selected Person Own Page (Matching Image 2)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = {
                        if (initialPersonId != null && selectedPersonId == initialPersonId) {
                            onBack()
                        } else {
                            selectedPersonId = null
                        }
                    }
                ) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "Back to people"
                    )
                }

                // Search in person pill
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Search in ${if (selectedPerson?.isNamed == true) selectedPerson.displayName else "Person"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // 3-dots overflow menu (Merge, Hide, etc.)
                Box {
                    IconButton(onClick = { personMenuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Person options")
                    }
                    DropdownMenu(
                        expanded = personMenuExpanded,
                        onDismissRequest = { personMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Merge with someone") },
                            leadingIcon = { Icon(Icons.Default.MergeType, contentDescription = null) },
                            onClick = {
                                personMenuExpanded = false
                                personToMerge = selectedPerson
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (selectedPerson?.isHidden == true) "Restore person" else "Hide person") },
                            leadingIcon = {
                                Icon(
                                    if (selectedPerson?.isHidden == true) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                personMenuExpanded = false
                                if (selectedPerson != null) {
                                    reloadScope.launch(Dispatchers.IO) {
                                        store.setPersonHidden(selectedPerson.id, !selectedPerson.isHidden)
                                        reload()
                                    }
                                    if (!selectedPerson.isHidden) selectedPersonId = null
                                }
                            }
                        )
                    }
                }
            }

            // Person Profile Header (Matching Image 2)
            if (selectedPerson != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Circular avatar with camera picker badge
                    Box(contentAlignment = Alignment.BottomEnd) {
                        PersonAvatar(person = selectedPerson, size = 90.dp)
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.surface),
                            modifier = Modifier
                                .size(32.dp)
                                .clickable { showAvatarPicker = true }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.PhotoCamera,
                                    contentDescription = "Pick avatar",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.width(18.dp))

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (selectedPerson.isNamed) selectedPerson.displayName else "Add name",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedPerson.isNamed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable {
                                    renamePersonId = selectedPerson.id
                                    renameValue = if (selectedPerson.isNamed) selectedPerson.displayName else ""
                                }
                            )
                            Spacer(Modifier.width(6.dp))
                            IconButton(
                                onClick = {
                                    renamePersonId = selectedPerson.id
                                    renameValue = if (selectedPerson.isNamed) selectedPerson.displayName else ""
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Edit name",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Text(
                            text = "${java.text.NumberFormat.getInstance().format(detailMedia.size)} ${if (detailMedia.size == 1) "photo" else "photos"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Photos grouped by date headers ("Today", "Yesterday", "Sunday", etc.) matching Image 2
            if (detailMedia.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "No photos in this group",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    dayGroups.forEach { group ->
                        val isCollapsed = group.dayKey in collapsedDayKeys
                        item(key = "header_${group.dayKey}") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isCollapsed) collapsedDayKeys.remove(group.dayKey)
                                        else collapsedDayKeys.add(group.dayKey)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = group.dayLabel,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                IconButton(
                                    onClick = {
                                        if (isCollapsed) collapsedDayKeys.remove(group.dayKey)
                                        else collapsedDayKeys.add(group.dayKey)
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isCollapsed) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = if (isCollapsed) "Expand group" else "Collapse group",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }

                        if (!isCollapsed) {
                            val chunkedRows = group.items.chunked(3)
                            items(chunkedRows.size, key = { rowIndex -> "row_${group.dayKey}_$rowIndex" }) { rowIndex ->
                                val rowItems = chunkedRows[rowIndex]
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 2.dp, vertical = 1.dp),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    for (col in 0 until 3) {
                                        val item = rowItems.getOrNull(col)
                                        if (item != null) {
                                            Box(modifier = Modifier.weight(1f).aspectRatio(1f)) {
                                                val context = LocalContext.current
                                                AsyncImage(
                                                    model = remember(item.uri, context) {
                                                        ThumbnailUtils.buildThumbnailRequest(context, item.uri)
                                                    },
                                                    contentDescription = item.name,
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .clickable { onOpenMedia(item) },
                                                    contentScale = ContentScale.Crop
                                                )
                                            }
                                        } else {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Avatar Picker Modal Bottom Sheet
    if (showAvatarPicker && selectedPerson != null) {
        ModalBottomSheet(
            onDismissRequest = { showAvatarPicker = false },
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "Choose profile photo",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Tap any photo to set it as this person's avatar",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    gridItems(detailMedia, key = { "avatar_${it.uri}" }) { mediaItem ->
                        val context = LocalContext.current
                        AsyncImage(
                            model = remember(mediaItem.uri, context) {
                                ThumbnailUtils.buildThumbnailRequest(context, mediaItem.uri)
                            },
                            contentDescription = "Select as avatar",
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    reloadScope.launch(Dispatchers.IO) {
                                        store.setPersonCover(selectedPerson.id, mediaItem.uri)
                                        reload()
                                    }
                                    showAvatarPicker = false
                                },
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        }
    }

    // Rename Person Dialog
    val currentRenamePersonId = renamePersonId
    if (currentRenamePersonId != null) {
        val targetId = currentRenamePersonId
        AlertDialog(
            onDismissRequest = { renamePersonId = null },
            title = { Text(if (renameValue.isBlank()) "Name this person" else "Rename person") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    singleLine = true,
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleanName = renameValue.trim()
                        val target = targetId
                        renamePersonId = null
                        if (cleanName.isNotBlank()) {
                            reloadScope.launch(Dispatchers.IO) {
                                store.renamePerson(target, cleanName)
                                reload()
                            }
                        }
                    },
                    enabled = renameValue.isNotBlank()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { renamePersonId = null }) { Text("Cancel") }
            }
        )
    }

    // Merge People Dialog
    val currentPersonToMerge = personToMerge
    if (currentPersonToMerge != null) {
        val sourcePerson = currentPersonToMerge
        val mergeCandidates = visiblePeople.filter { it.id != sourcePerson.id }

        AlertDialog(
            onDismissRequest = { personToMerge = null },
            title = { Text("Merge with someone") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Select the person to combine with ${if (sourcePerson.isNamed) sourcePerson.displayName else "this person"}:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    mergeCandidates.forEach { candidate ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (selectedTargetMergeId == candidate.id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedTargetMergeId = candidate.id }
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedTargetMergeId == candidate.id,
                                    onClick = { selectedTargetMergeId = candidate.id }
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (candidate.isNamed) candidate.displayName else "Unnamed (${candidate.faceCount} photos)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val targetMerge = selectedTargetMergeId
                        if (targetMerge != null) {
                            val srcId = sourcePerson.id
                            selectedPersonId = targetMerge
                            personToMerge = null
                            selectedTargetMergeId = null
                            reloadScope.launch(Dispatchers.IO) {
                                store.mergePeople(srcId, targetMerge)
                                reload()
                            }
                        }
                    },
                    enabled = selectedTargetMergeId != null
                ) {
                    Text("Merge")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    personToMerge = null
                    selectedTargetMergeId = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Single Photo Action Dialog (Set Cover / Remove Face)
    val currentSelectedMediaForAction = selectedMediaForAction
    val currentSelectedPersonId = selectedPersonId
    if (currentSelectedMediaForAction != null && currentSelectedPersonId != null) {
        val activeMedia = currentSelectedMediaForAction
        val activePersonId = currentSelectedPersonId

        AlertDialog(
            onDismissRequest = { selectedMediaForAction = null },
            title = { Text("Photo options") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            val pId = activePersonId
                            val mUri = activeMedia.uri
                            selectedMediaForAction = null
                            reloadScope.launch(Dispatchers.IO) {
                                store.setPersonCover(pId, mUri)
                                reload()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Photo, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Use as representative cover")
                    }
                    TextButton(
                        onClick = {
                            val pId = activePersonId
                            val mUri = activeMedia.uri
                            val engId = selectedEngineId
                            selectedMediaForAction = null
                            reloadScope.launch(Dispatchers.IO) {
                                val faces = store.facesForMedia(mUri, engineId = engId)
                                faces.filter { it.personId == pId }.forEach { face ->
                                    store.removeFaceFromPerson(face.mediaUri, face.faceIndex, engineId = engId)
                                }
                                reload()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Remove face from this person")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { selectedMediaForAction = null }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun PersonAvatar(
    person: PersonSummary,
    size: androidx.compose.ui.unit.Dp = 88.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var faceBitmap by remember(person.id, person.portraitUri, person.portraitFaceIndex, person.sampleUri, person.engineId) {
        mutableStateOf<Bitmap?>(null)
    }

    LaunchedEffect(person.id, person.portraitUri, person.portraitFaceIndex, person.sampleUri, person.engineId) {
        val targetUri = person.portraitUri ?: person.sampleUri
        if (targetUri != null) {
            val crop = withContext(Dispatchers.IO) {
                val parsed = runCatching { Uri.parse(targetUri) }.getOrNull() ?: return@withContext null
                val fIdx = person.portraitFaceIndex ?: run {
                    val st = FaceClusterStore(context.applicationContext)
                    st.facesForCluster(person.id, person.engineId).firstOrNull()?.faceIndex ?: 0
                }
                FaceThumbnailHelper.getFaceCrop(context, parsed, fIdx, person.engineId)
            }
            faceBitmap = crop
        }
    }

    val currentFaceBitmap = faceBitmap
    if (currentFaceBitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = currentFaceBitmap.asImageBitmap(),
            contentDescription = if (person.isNamed) person.displayName else "Person",
            modifier = modifier
                .size(size)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        Surface(
            modifier = modifier.size(size),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (person.isNamed && person.displayName.isNotBlank()) {
                    Text(
                        text = person.displayName.trim().take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = (size.value * 0.38f).sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                } else {
                    Icon(
                        Icons.Default.Face,
                        contentDescription = null,
                        modifier = Modifier.size(size * 0.45f),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

