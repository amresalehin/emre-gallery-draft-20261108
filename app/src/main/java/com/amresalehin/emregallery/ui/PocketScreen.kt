package com.amresalehin.emregallery.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.amresalehin.emregallery.data.LinkMetadataFetcher
import com.amresalehin.emregallery.data.MediaClassifier
import com.amresalehin.emregallery.data.MediaInboxItem
import com.amresalehin.emregallery.data.MediaInboxStack
import com.amresalehin.emregallery.data.PocketRepository
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Strict monochrome color palette for PocketScreen, conforming to Emre Gallery's design:
 * Built exclusively from pure neutrals: whites, grays, and near-blacks.
 * Perfectly calibrated and optimized for both Day (Light) and Night (Dark) mode.
 */
object PocketThemeColors {
    fun background(isDark: Boolean): Color =
        if (isDark) Color(0xFF101010) else Color(0xFFFFFFFF)

    fun barSurface(isDark: Boolean): Color =
        if (isDark) Color(0xFF1C1C1C) else Color(0xFFF7F7F7)

    fun cardSurface(isDark: Boolean): Color =
        if (isDark) Color(0xFF181818) else Color(0xFFFFFFFF)

    fun primaryText(isDark: Boolean): Color =
        if (isDark) Color(0xFFEDEDED) else Color(0xFF141414)

    fun secondaryText(isDark: Boolean): Color =
        if (isDark) Color(0xFFA0A0A0) else Color(0xFF6E6E6E)

    fun divider(isDark: Boolean): Color =
        if (isDark) Color(0xFF2C2C2C) else Color(0xFFE2E2E2)

    fun thumbnailBg(isDark: Boolean): Color =
        if (isDark) Color(0xFF202020) else Color(0xFFF2F2F2)

    fun placeholderIcon(isDark: Boolean): Color =
        if (isDark) Color(0xFF707070) else Color(0xFFA0A0A0)

    fun pillSurface(isDark: Boolean): Color =
        if (isDark) Color(0xFF242424) else Color(0xFFF2F2F2)

    fun badgeBg(isDark: Boolean): Color =
        if (isDark) Color(0xFF222222).copy(alpha = 0.9f) else Color(0xFF1A1A1A).copy(alpha = 0.85f)

    fun badgeText(isDark: Boolean): Color =
        if (isDark) Color(0xFFEDEDED) else Color(0xFFFFFFFF)

    fun stackLayer1(isDark: Boolean): Color =
        if (isDark) Color(0xFF18161D) else Color(0xFFEAE5ED)

    fun stackLayer2(isDark: Boolean): Color =
        if (isDark) Color(0xFF1E1C22) else Color(0xFFFFFFFF)

    fun stackFront(isDark: Boolean): Color =
        if (isDark) Color(0xFF26242C) else Color(0xFFE6E0E9)

    fun webGradient(isDark: Boolean): List<Color> =
        if (isDark) listOf(Color(0xFF222222), Color(0xFF181818))
        else listOf(Color(0xFFF7F7F7), Color(0xFFEAEAEA))

    fun faviconSurface(isDark: Boolean): Color =
        if (isDark) Color(0xFF282828) else Color(0xFFFFFFFF)

    fun drawerSurface(isDark: Boolean): Color =
        if (isDark) Color(0xFF181818) else Color(0xFFFFFFFF)

    fun drawerHandle(isDark: Boolean): Color =
        if (isDark) Color(0xFF484848) else Color(0xFFBDBDBD)

    fun detailRowSurface(isDark: Boolean): Color =
        if (isDark) Color(0xFF202020) else Color(0xFFF7F7F7)

    fun tagChipBg(isDark: Boolean): Color =
        if (isDark) Color(0xFF282828) else Color(0xFFECECEC)
}

fun parseDimensionsFromText(text: String): Pair<Int?, Int?> {
    val match = Regex("""(\d{2,5})\s*[xX*×]\s*(\d{2,5})""").find(text)
    if (match != null) {
        val w = match.groupValues[1].toIntOrNull()
        val h = match.groupValues[2].toIntOrNull()
        return w to h
    }
    return null to null
}

fun parseMediaItemDimensions(item: MediaInboxItem): Pair<Int?, Int?> {
    return parseDimensionsFromText("${item.title} ${item.sourceUrl}")
}

/**
 * Pocket Screen
 * Displays saved bookmarks and web collections in a clean, modern staggered masonry layout.
 * Unified search & paste bar aligns with gallery search bar behavior, removing separate headers
 * and embedding navigation and filter controls within the capsule.
 * Clicking any bookmark card opens the Bookmark Viewer / Detail page with stacked card presentation
 * and structured metadata (Time, Source, Note, Tags, Title, Description).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PocketScreen(
    topContentPadding: Dp = 0.dp,
    bottomContentPadding: Dp = 0.dp,
    onMenuClick: () -> Unit = {},
    onDetailViewerStateChange: ((Boolean) -> Unit)? = null,
    onOpenDiscoveredMedia: () -> Unit = {},
    onOpenBookmarkDetail: (MediaInboxStack) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val pocketRepo = remember { PocketRepository.getInstance(context) }
    val stacks by pocketRepo.bookmarks.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var isSearchFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var selectedStackIds by remember { mutableStateOf(setOf<String>()) }
    val isSelectionMode = selectedStackIds.isNotEmpty()

    var sortOrder by remember { mutableStateOf("Newest") } // Newest, Oldest, Title, Domain
    var filterType by remember { mutableStateOf("All") } // All, Web Links, Notes
    val showSvg by pocketRepo.showSvg.collectAsState()
    val showVideos by pocketRepo.showVideos.collectAsState()
    val showStreams by pocketRepo.showStreams.collectAsState()
    val showPng by pocketRepo.showPng.collectAsState()
    val showGif by pocketRepo.showGif.collectAsState()
    val minWidth by pocketRepo.minWidth.collectAsState()
    val minHeight by pocketRepo.minHeight.collectAsState()
    val minSizeKb by pocketRepo.minSizeKb.collectAsState()
    var showFilterSheet by remember { mutableStateOf(false) }

    var showAddBookmarkDialog by remember { mutableStateOf(false) }
    var editingStack by remember { mutableStateOf<MediaInboxStack?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    var showFullScreenBrowser by remember { mutableStateOf(false) }
    var browserUrl by remember { mutableStateOf("") }

    val snackbarHostState = remember { SnackbarHostState() }

    // Clipboard check for quick paste affordance
    val clipboardManager = remember {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    var clipboardUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isSearchFocused) {
        if (isSearchFocused) {
            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val clipText = clip.getItemAt(0).text?.toString()?.trim() ?: ""
                if (MediaClassifier.isValidWebUrl(clipText) || clipText.startsWith("http://") || clipText.startsWith("https://")) {
                    clipboardUrl = clipText
                } else {
                    clipboardUrl = null
                }
            }
        }
    }

    // Filter and sort stacks
    val filteredStacks = remember(
        stacks, searchQuery, sortOrder, filterType,
        showSvg, showVideos, showStreams, showPng, showGif,
        minWidth, minHeight, minSizeKb
    ) {
        // When type filters, dimensions, or size are set, hide non-compliant media items from found media
        // within the bookmark stack, displaying the bookmark itself and remaining compliant media items.
        val sanitized = stacks.map { stack ->
            pocketRepo.sanitizeStack(
                stack = stack,
                showSvg = showSvg,
                minWidth = minWidth,
                minHeight = minHeight,
                showVideos = showVideos,
                showStreams = showStreams,
                showPng = showPng,
                showGif = showGif,
                minSizeKb = minSizeKb
            )
        }

        var result = sanitized.filter { stack ->
            val matchesQuery = if (searchQuery.isBlank()) {
                true
            } else {
                stack.title.contains(searchQuery, ignoreCase = true) ||
                        stack.sourceDomain.contains(searchQuery, ignoreCase = true) ||
                        stack.sourceUrl.contains(searchQuery, ignoreCase = true) ||
                        stack.description.contains(searchQuery, ignoreCase = true)
            }

            val matchesFilter = when (filterType) {
                "Web Links" -> stack.sourceUrl.isNotBlank()
                "Notes" -> stack.description.isNotBlank()
                else -> true
            }

            matchesQuery && matchesFilter
        }

        result = when (sortOrder) {
            "Oldest" -> result.sortedBy { it.timestampMillis }
            "Title" -> result.sortedBy { it.title.lowercase() }
            "Domain" -> result.sortedBy { it.sourceDomain.lowercase() }
            else -> result.sortedByDescending { it.timestampMillis } // Newest
        }

        result
    }

    // Handle back button for in-screen states (browser, selection, search, filter)
    BackHandler(enabled = !showFullScreenBrowser && (isSelectionMode || isSearchFocused || searchQuery.isNotEmpty() || showFilterSheet)) {
        when {
            showFullScreenBrowser -> showFullScreenBrowser = false
            isSelectionMode -> selectedStackIds = emptySet()
            isSearchFocused || searchQuery.isNotEmpty() -> {
                searchQuery = ""
                isSearchFocused = false
                focusManager.clearFocus()
                keyboardController?.hide()
            }
            showFilterSheet -> showFilterSheet = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PocketThemeColors.background(isDark))
            .testTag("pocket_screen")
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = PocketThemeColors.background(isDark),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Surface(
                    color = PocketThemeColors.background(isDark),
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        if (isSelectionMode) {
                            // Selection mode toolbar
                            PocketSelectionToolbar(
                                selectedCount = selectedStackIds.size,
                                allSelected = filteredStacks.isNotEmpty() && filteredStacks.all { it.id in selectedStackIds },
                                onClearSelection = { selectedStackIds = emptySet() },
                                onToggleSelectAll = {
                                    val all = filteredStacks.isNotEmpty() && filteredStacks.all { it.id in selectedStackIds }
                                    selectedStackIds = if (all) emptySet() else filteredStacks.map { it.id }.toSet()
                                },
                                onShare = {
                                    val urlsToShare = filteredStacks
                                        .filter { it.id in selectedStackIds && it.sourceUrl.isNotBlank() }
                                        .map { "${it.title}: ${it.sourceUrl}" }
                                    if (urlsToShare.isNotEmpty()) {
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, urlsToShare.joinToString("\n\n"))
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, "Share bookmarks"))
                                    }
                                },
                                onDelete = { showDeleteConfirmDialog = true },
                                isDark = isDark
                            )
                        } else {
                            // Unified Search & Paste Bar (aligning with other search bar behavior, no separate headers)
                            val isUrlInQuery = searchQuery.isNotBlank() && (
                                    MediaClassifier.isValidWebUrl(searchQuery.trim()) ||
                                            searchQuery.trim().startsWith("http://") ||
                                            searchQuery.trim().startsWith("https://")
                                    )
                            val hasActiveFilters = filterType != "All" || sortOrder != "Newest" || showSvg ||
                                    !showVideos || !showStreams || !showPng || !showGif ||
                                    minWidth != PocketRepository.DEFAULT_MIN_WIDTH || minHeight != PocketRepository.DEFAULT_MIN_HEIGHT || minSizeKb != PocketRepository.DEFAULT_MIN_SIZE_KB

                            PocketSearchPasteCapsule(
                                query = searchQuery,
                                onQueryChange = { searchQuery = it },
                                isFocused = isSearchFocused,
                                onFocusChange = { isSearchFocused = it },
                                onMenuClick = onMenuClick,
                                onOpenFilterSheet = { showFilterSheet = true },
                                hasActiveFilters = hasActiveFilters,
                                onBookmarkAction = {
                                    val trimmed = searchQuery.trim()
                                    val liveClip = clipboardManager.primaryClip
                                    val clipText = if (liveClip != null && liveClip.itemCount > 0) {
                                        liveClip.getItemAt(0).text?.toString()?.trim() ?: ""
                                    } else ""

                                    val rawTarget = when {
                                        trimmed.isNotBlank() -> trimmed
                                        clipText.isNotBlank() -> clipText
                                        else -> ""
                                    }

                                    if (rawTarget.isNotBlank()) {
                                        val targetUrl = MediaClassifier.extractFirstUrl(rawTarget)
                                            ?: if (rawTarget.contains(".") || rawTarget.startsWith("http://", ignoreCase = true) || rawTarget.startsWith("https://", ignoreCase = true) || rawTarget.startsWith("file://", ignoreCase = true) || rawTarget.startsWith("content://", ignoreCase = true)) {
                                                MediaClassifier.sanitizeUrl(rawTarget)
                                            } else {
                                                rawTarget
                                            }

                                        val domain = MediaClassifier.cleanDomainFromUrl(targetUrl)
                                        pocketRepo.addBookmark(
                                            title = domain.ifBlank { targetUrl },
                                            url = targetUrl
                                        )
                                        searchQuery = ""
                                        isSearchFocused = false
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Saved to Pocket")
                                        }
                                    } else {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Enter or paste a media link or URL to save")
                                        }
                                    }
                                },
                                onSearchSubmit = { target ->
                                    val trimmed = target.trim()
                                    if (trimmed.isNotBlank()) {
                                        val targetUrl = MediaClassifier.extractFirstUrl(trimmed)
                                            ?: if (trimmed.contains(".") || trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("file://", ignoreCase = true) || trimmed.startsWith("content://", ignoreCase = true)) {
                                                MediaClassifier.sanitizeUrl(trimmed)
                                            } else {
                                                ""
                                            }

                                        if (targetUrl.isNotBlank()) {
                                            val domain = MediaClassifier.cleanDomainFromUrl(targetUrl)
                                            pocketRepo.addBookmark(
                                                title = domain.ifBlank { targetUrl },
                                                url = targetUrl
                                            )
                                            searchQuery = ""
                                            isSearchFocused = false
                                            focusManager.clearFocus()
                                            keyboardController?.hide()
                                            scope.launch {
                                                snackbarHostState.showSnackbar("Saved to Pocket")
                                            }
                                        } else {
                                            keyboardController?.hide()
                                        }
                                    }
                                },
                                isDark = isDark,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (filteredStacks.isNotEmpty()) {
                    // Staggered 2-column masonry grid matching image_15171895.png
                    LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Fixed(2),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp)
                            .testTag("pocket_bookmarks_grid"),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 120.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalItemSpacing = 14.dp
                    ) {
                        itemsIndexed(filteredStacks, key = { _, stack -> stack.id }) { index, stack ->
                            val isSelected = stack.id in selectedStackIds
                            PocketBookmarkStaggeredCard(
                                stack = stack,
                                index = index,
                                isSelected = isSelected,
                                isSelectionMode = isSelectionMode,
                                onClick = {
                                    if (isSelectionMode) {
                                        selectedStackIds = if (isSelected) {
                                            selectedStackIds - stack.id
                                        } else {
                                            selectedStackIds + stack.id
                                        }
                                    } else {
                                        // Clicking bookmark opens a separate full screen bookmark detail page
                                        onOpenBookmarkDetail(stack)
                                    }
                                },
                                onLongClick = {
                                    selectedStackIds = if (isSelectionMode) {
                                        if (isSelected) selectedStackIds - stack.id else selectedStackIds + stack.id
                                    } else {
                                        setOf(stack.id)
                                    }
                                },
                                isDark = isDark,
                                modifier = Modifier.testTag("pocket_bookmark_card_${stack.id}")
                            )
                        }
                    }
                } else {
                    // When no bookmark is there: full empty as requested
                    PocketEmptyState(
                        searchQuery = searchQuery,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // Fullscreen in-app web browser
        if (showFullScreenBrowser) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(100f)
            ) {
                DirectBrowserView(
                    initialUrl = browserUrl,
                    onClose = { showFullScreenBrowser = false },
                    onMenuClick = onMenuClick,
                    onMediaExtracted = {},
                    onBatchExtracted = { _, _, _ -> },
                    onSwitchToMediaTab = onOpenDiscoveredMedia,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Add Bookmark Dialog
        if (showAddBookmarkDialog) {
            AddBookmarkDialog(
                onDismiss = { showAddBookmarkDialog = false },
                onSave = { title, url, description, previewUrl ->
                    val added = pocketRepo.addBookmark(
                        title = title,
                        url = url,
                        description = description,
                        previewImageUrl = previewUrl
                    )
                    showAddBookmarkDialog = false
                    scope.launch {
                        snackbarHostState.showSnackbar("Bookmark saved to Pocket")
                    }
                },
                isDark = isDark
            )
        }

        // Edit Bookmark Dialog
        editingStack?.let { stack ->
            EditBookmarkDialog(
                stack = stack,
                onDismiss = { editingStack = null },
                onSave = { updatedTitle, updatedDesc, updatedPreview ->
                    val updated = stack.copy(
                        title = updatedTitle,
                        description = updatedDesc,
                        previewImageUrl = updatedPreview
                    )
                    pocketRepo.updateBookmark(updated)
                    editingStack = null
                    scope.launch {
                        snackbarHostState.showSnackbar("Bookmark updated")
                    }
                },
                isDark = isDark
            )
        }

        // Delete confirmation dialog
        if (showDeleteConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = false },
                containerColor = if (isDark) Color(0xFF1C1C1C) else Color(0xFFFFFFFF),
                title = {
                    Text(
                        text = "Delete Bookmarks",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = PocketThemeColors.primaryText(isDark)
                    )
                },
                text = {
                    Text(
                        text = "Are you sure you want to remove ${selectedStackIds.size} bookmarks from Pocket?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PocketThemeColors.secondaryText(isDark)
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            pocketRepo.deleteBookmarks(selectedStackIds)
                            selectedStackIds = emptySet()
                            showDeleteConfirmDialog = false
                            scope.launch {
                                snackbarHostState.showSnackbar("Bookmarks deleted")
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDark) Color(0xFFEEEEEE) else Color(0xFF202020),
                            contentColor = if (isDark) Color(0xFF161616) else Color(0xFFFFFFFF)
                        )
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showDeleteConfirmDialog = false },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = PocketThemeColors.secondaryText(isDark)
                        )
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Filter & Sort Bottom Sheet
        if (showFilterSheet) {
            PocketFilterBottomSheet(
                currentSort = sortOrder,
                currentFilter = filterType,
                showSvg = showSvg,
                showVideos = showVideos,
                showStreams = showStreams,
                showPng = showPng,
                showGif = showGif,
                minWidth = minWidth,
                minHeight = minHeight,
                minSizeKb = minSizeKb,
                onSelectSort = { sortOrder = it },
                onSelectFilter = { filterType = it },
                onToggleShowSvg = { pocketRepo.setShowSvg(it) },
                onToggleShowVideos = { pocketRepo.setShowVideos(it) },
                onToggleShowStreams = { pocketRepo.setShowStreams(it) },
                onToggleShowPng = { pocketRepo.setShowPng(it) },
                onToggleShowGif = { pocketRepo.setShowGif(it) },
                onWidthChange = { pocketRepo.setMinWidth(it) },
                onHeightChange = { pocketRepo.setMinHeight(it) },
                onSizeChange = { pocketRepo.setMinSizeKb(it) },
                onDismiss = { showFilterSheet = false },
                isDark = isDark
            )
        }
    }
}

/**
 * Single unified capsule search and paste bar.
 * Aligned with Maindownloader page behavior:
 * - When focused: leading icon becomes Back arrow, and trailing controls show Cross (clear) and Bookmark icon
 * - When not focused: leading icon is Hamburger menu, and trailing control shows Filter sliders
 */
@Composable
fun PocketSearchPasteCapsule(
    query: String,
    onQueryChange: (String) -> Unit,
    isFocused: Boolean,
    onFocusChange: (Boolean) -> Unit,
    onMenuClick: () -> Unit,
    onOpenFilterSheet: () -> Unit,
    hasActiveFilters: Boolean,
    onBookmarkAction: () -> Unit,
    onSearchSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    isDark: Boolean = false
) {
    val barColor = PocketThemeColors.barSurface(isDark)
    val textColor = PocketThemeColors.primaryText(isDark)
    val secondaryText = PocketThemeColors.secondaryText(isDark)
    val dividerCol = PocketThemeColors.divider(isDark)
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = CircleShape,
        color = barColor,
        border = BorderStroke(1.dp, if (isDark) dividerCol else Color(0xFFDCDCDC)),
        tonalElevation = if (isDark) 2.dp else 1.dp,
        shadowElevation = if (isDark) 2.dp else 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Leading Button: Back button if focused or query is non-empty, otherwise Hamburger Menu
            IconButton(
                onClick = {
                    if (isFocused || query.isNotEmpty()) {
                        onQueryChange("")
                        onFocusChange(false)
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    } else {
                        onMenuClick()
                    }
                },
                modifier = Modifier
                    .size(42.dp)
                    .testTag(if (isFocused || query.isNotEmpty()) "pocket_search_back_button" else "pocket_menu_button")
            ) {
                Icon(
                    imageVector = if (isFocused || query.isNotEmpty()) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Menu,
                    contentDescription = if (isFocused || query.isNotEmpty()) "Back" else "Open navigation menu",
                    tint = textColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            if (!isFocused && query.isEmpty()) {
                // Divider |
                Spacer(
                    modifier = Modifier
                        .height(20.dp)
                        .width(1.dp)
                        .background(if (isDark) dividerCol.copy(alpha = 0.8f) else Color(0xFFDCDCDC))
                )
            }

            Spacer(Modifier.width(8.dp))

            // Search / URL Input with visible caret
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.CenterStart
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = "Search library or paste URL",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                        color = secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focusState ->
                            onFocusChange(focusState.isFocused)
                        }
                        .testTag("pocket_search_input"),
                    textStyle = TextStyle(
                        color = textColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(textColor),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            if (query.isNotBlank()) {
                                onSearchSubmit(query.trim())
                            }
                        }
                    )
                )
            }

            // Trailing actions:
            // When focused (or query non-empty), shows cross (clear) and bookmark icon (matching Maindownloader behavior)
            if (isFocused || query.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Cross (clear) button only when text is present
                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = { onQueryChange("") },
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("pocket_search_clear_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear search",
                                tint = secondaryText,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Save / Bookmark icon button (monochrome theme)
                    IconButton(
                        onClick = onBookmarkAction,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("pocket_search_bookmark_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.BookmarkAdd,
                            contentDescription = "Save media to Pocket",
                            tint = textColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            } else {
                // Divider |
                Spacer(
                    modifier = Modifier
                        .height(20.dp)
                        .width(1.dp)
                        .background(if (isDark) dividerCol.copy(alpha = 0.8f) else Color(0xFFDCDCDC))
                )

                // Trailing Filter Sliders Action inside the search bar
                Box {
                    IconButton(
                        onClick = onOpenFilterSheet,
                        modifier = Modifier
                            .size(42.dp)
                            .testTag("pocket_tune_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Filter and sort bookmarks",
                            tint = if (hasActiveFilters) textColor else secondaryText,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (hasActiveFilters) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 10.dp, end = 10.dp)
                                .size(7.dp)
                                .background(textColor, CircleShape)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Pocket Selection Mode Toolbar
 */
@Composable
fun PocketSelectionToolbar(
    selectedCount: Int,
    allSelected: Boolean,
    onClearSelection: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    isDark: Boolean = false
) {
    val barColor = PocketThemeColors.barSurface(isDark)
    val textColor = PocketThemeColors.primaryText(isDark)

    Surface(
        shape = CircleShape,
        color = barColor,
        tonalElevation = if (isDark) 4.dp else 2.dp,
        shadowElevation = if (isDark) 4.dp else 2.dp,
        border = BorderStroke(1.5.dp, textColor.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .testTag("pocket_selection_toolbar")
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClearSelection,
                modifier = Modifier.size(38.dp).testTag("selection_close_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Exit selection",
                    tint = textColor
                )
            }

            Spacer(Modifier.width(4.dp))

            Text(
                text = "$selectedCount selected",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = textColor,
                modifier = Modifier.weight(1f).testTag("selection_count_text")
            )

            IconButton(
                onClick = onToggleSelectAll,
                modifier = Modifier.size(38.dp).testTag("selection_select_all_button")
            ) {
                Icon(
                    imageVector = if (allSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                    contentDescription = if (allSelected) "Deselect all" else "Select all",
                    tint = textColor
                )
            }

            IconButton(
                onClick = onShare,
                modifier = Modifier.size(38.dp).testTag("selection_share_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Share selected bookmarks",
                    tint = textColor
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(38.dp).testTag("pocket_delete_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete selected",
                    tint = textColor
                )
            }
        }
    }
}

/**
 * Pocket Bookmark Staggered Card conforming to image_15171895.png
 * Features:
 * - Rounded rectangle container (22.dp)
 * - Top preview image placeholder or real image/preview with varied aspect heights for masonry stagger
 * - Bottom white/surface pill section with Line 1 (Title) and Line 2 (Subtitle/Domain)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PocketBookmarkStaggeredCard(
    stack: MediaInboxStack,
    index: Int,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isDark: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cardSurface = PocketThemeColors.cardSurface(isDark)
    val textColor = PocketThemeColors.primaryText(isDark)
    val secondaryText = PocketThemeColors.secondaryText(isDark)
    val dividerCol = PocketThemeColors.divider(isDark)

    // Vary preview height to produce the natural staggered masonry flow from image_15171895.png
    val previewHeight = remember(index) {
        when (index % 4) {
            0 -> 180.dp
            1 -> 130.dp
            2 -> 140.dp
            else -> 195.dp
        }
    }

    val firstMedia = stack.items.firstOrNull()
    val mediaModel = remember(firstMedia, stack.previewImageUrl) {
        firstMedia?.let {
            if (it.localUri.isNotBlank()) {
                val f = File(it.localUri.removePrefix("file://"))
                if (f.exists()) f else it.sourceUrl.ifBlank { it.localUri }
            } else {
                it.sourceUrl
            }
        } ?: stack.previewImageUrl.takeIf { it.isNotBlank() }
    }

    val faviconUrl = remember(stack.sourceDomain) {
        if (stack.sourceDomain.isNotBlank()) {
            "https://www.google.com/s2/favicons?domain=${stack.sourceDomain}&sz=128"
        } else ""
    }

    val dateTimeFormatted = remember(stack.timestampMillis) {
        val sdf = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())
        sdf.format(Date(stack.timestampMillis))
    }

    Box(modifier = modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = cardSurface,
            tonalElevation = if (isSelected) 4.dp else 1.dp,
            shadowElevation = if (isSelected) 6.dp else 2.dp,
            border = BorderStroke(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) textColor else (if (isDark) dividerCol.copy(alpha = 0.7f) else dividerCol)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Top Preview Image Area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(previewHeight)
                        .background(PocketThemeColors.thumbnailBg(isDark)),
                    contentAlignment = Alignment.Center
                ) {
                    if (mediaModel != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(mediaModel)
                                .crossfade(true)
                                .build(),
                            contentDescription = stack.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (stack.items.isEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color.Black.copy(alpha = 0.65f),
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = "Preview",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    } else if (faviconUrl.isNotBlank()) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = PocketThemeColors.faviconSurface(isDark),
                                shadowElevation = 2.dp,
                                modifier = Modifier.size(44.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(faviconUrl)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = stack.sourceDomain,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stack.sourceDomain,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = secondaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        // Skeleton image placeholder icon matching image_15171895.png
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = null,
                            tint = PocketThemeColors.placeholderIcon(isDark),
                            modifier = Modifier.size(48.dp)
                        )
                    }

                    // Media badge overlay if stack has multiple items
                    if (stack.items.size > 1) {
                        Surface(
                            shape = CircleShape,
                            color = PocketThemeColors.badgeBg(isDark),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PhotoLibrary,
                                    contentDescription = null,
                                    tint = PocketThemeColors.badgeText(isDark),
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "${stack.items.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = PocketThemeColors.badgeText(isDark)
                                )
                            }
                        }
                    }
                }

                // Bottom Content Section: Title, Date & Time, Favicon & Site Address (matching image_026bd3b6.png)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    // Line 1: Title (e.g. "Ancient India")
                    Text(
                        text = stack.title.ifBlank { stack.sourceDomain.ifBlank { "Untitled Bookmark" } },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        ),
                        color = textColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (stack.description.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = stack.description,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            ),
                            color = secondaryText,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(Modifier.height(3.dp))

                    // Line 2: Date & Time (e.g. "Oct 29, 2026 · 4:06 AM")
                    Text(
                        text = dateTimeFormatted,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 12.sp
                        ),
                        color = secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(6.dp))

                    // Line 3: Favicon & Site Address Pill (e.g. [🏛️ | britannica.com])
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = PocketThemeColors.pillSurface(isDark),
                        border = BorderStroke(0.5.dp, if (isDark) dividerCol.copy(alpha = 0.7f) else dividerCol),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (faviconUrl.isNotBlank()) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(faviconUrl)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Public,
                                    contentDescription = null,
                                    tint = secondaryText,
                                    modifier = Modifier.size(13.dp)
                                )
                            }

                            Spacer(Modifier.width(5.dp))

                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(10.dp)
                                    .background(if (isDark) dividerCol else Color(0xFFD0D0D0))
                            )

                            Spacer(Modifier.width(5.dp))

                            Text(
                                text = stack.sourceDomain.ifBlank { "bookmark" },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = textColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Selection Checkbox indicator
        if (isSelectionMode) {
            Surface(
                shape = CircleShape,
                color = if (isSelected) (if (isDark) Color(0xFFEEEEEE) else Color(0xFF202020)) else (if (isDark) Color(0xFF242424) else Color(0xFFF2F2F2)),
                border = BorderStroke(
                    1.5.dp,
                    if (isSelected) (if (isDark) Color(0xFF101010) else Color(0xFFFFFFFF)) else (if (isDark) Color(0xFF666666) else Color(0xFFB0B0B0))
                ),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(26.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = if (isDark) Color(0xFF161616) else Color(0xFFFFFFFF),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Reusable detail row card conforming to image_a086dfff.png
 */
@Composable
fun PocketDetailCardRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    trailingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: (() -> Unit)? = null,
    textColor: Color,
    secondaryText: Color,
    barSurface: Color,
    dividerCol: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = barSurface,
        border = BorderStroke(1.dp, if (textColor.luminance() > 0.5f) dividerCol.copy(alpha = 0.5f) else Color(0xFFE6E6E6)),
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = textColor
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (trailingIcon != null) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = trailingIcon,
                    contentDescription = null,
                    tint = secondaryText,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Empty state for Pocket
 * Full empty as requested: remove everything on screen when no bookmark is there.
 */
@Composable
fun PocketEmptyState(
    searchQuery: String = "",
    onAddBookmark: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // When no bookmark is there: full empty as requested (clean empty background, no graphics/text/buttons)
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("pocket_empty_state")
    )
}

/**
 * Add Bookmark Dialog
 */
@Composable
fun AddBookmarkDialog(
    onDismiss: () -> Unit,
    onSave: (title: String, url: String, description: String, previewImageUrl: String) -> Unit,
    isDark: Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val textColor = PocketThemeColors.primaryText(isDark)
    val secondaryText = PocketThemeColors.secondaryText(isDark)
    val dialogSurface = if (isDark) Color(0xFF1E1E1E) else Color(0xFFFFFFFF)
    val borderCol = if (isDark) Color(0xFF333333) else Color(0xFFE0E0E0)

    var urlText by remember { mutableStateOf("") }
    var titleText by remember { mutableStateOf("") }
    var descText by remember { mutableStateOf("") }
    var previewImageUrl by remember { mutableStateOf("") }
    var isFetchingMetadata by remember { mutableStateOf(false) }

    fun triggerAutoFetch(urlToFetch: String) {
        val trimmed = urlToFetch.trim()
        if (trimmed.length < 4) return
        val isValid = MediaClassifier.isValidWebUrl(trimmed) ||
                trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
        if (!isValid) return

        scope.launch {
            isFetchingMetadata = true
            try {
                val meta = LinkMetadataFetcher.fetch(trimmed)
                if (urlText.trim() == trimmed) {
                    val domain = MediaClassifier.cleanDomainFromUrl(trimmed)
                    if (titleText.isBlank() || titleText.equals(domain, ignoreCase = true)) {
                        if (meta.title.isNotBlank()) titleText = meta.title
                    }
                    if (descText.isBlank() && meta.description.isNotBlank()) {
                        descText = meta.description
                    }
                    if (meta.previewImageUrl.isNotBlank()) {
                        previewImageUrl = meta.previewImageUrl
                    }
                }
            } catch (_: Exception) {
            } finally {
                isFetchingMetadata = false
            }
        }
    }

    LaunchedEffect(urlText) {
        val trimmed = urlText.trim()
        if (trimmed.length > 7 && (MediaClassifier.isValidWebUrl(trimmed) || trimmed.startsWith("http://") || trimmed.startsWith("https://"))) {
            kotlinx.coroutines.delay(500)
            triggerAutoFetch(trimmed)
        }
    }

    val clipboard = remember {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    val clipboardUrl = remember {
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString()?.trim() ?: ""
            if (MediaClassifier.isValidWebUrl(text) || text.startsWith("http://") || text.startsWith("https://")) text else null
        } else null
    }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = textColor,
        unfocusedBorderColor = if (isDark) Color(0xFF444444) else Color(0xFFD0D0D0),
        focusedLabelColor = textColor,
        unfocusedLabelColor = secondaryText,
        cursorColor = textColor,
        focusedTextColor = textColor,
        unfocusedTextColor = textColor
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = dialogSurface,
            tonalElevation = if (isDark) 4.dp else 2.dp,
            shadowElevation = if (isDark) 6.dp else 3.dp,
            border = BorderStroke(1.dp, borderCol),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.BookmarkAdd,
                        contentDescription = null,
                        tint = textColor,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Add Bookmark to Pocket",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = textColor
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Paste from clipboard suggestion
                if (clipboardUrl != null && urlText.isBlank()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isDark) Color(0xFF282828) else Color(0xFFF2F2F2),
                        border = BorderStroke(0.5.dp, if (isDark) Color(0xFF404040) else Color(0xFFE0E0E0)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                urlText = clipboardUrl
                                if (titleText.isBlank()) {
                                    titleText = MediaClassifier.cleanDomainFromUrl(clipboardUrl)
                                }
                                triggerAutoFetch(clipboardUrl)
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = null,
                                tint = textColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Paste: $clipboardUrl",
                                style = MaterialTheme.typography.bodySmall,
                                color = textColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                OutlinedTextField(
                    value = urlText,
                    onValueChange = {
                        urlText = it
                        if (titleText.isBlank() && it.isNotBlank()) {
                            val domain = MediaClassifier.cleanDomainFromUrl(it)
                            if (domain.isNotBlank()) titleText = domain
                        }
                    },
                    label = { Text("Web URL / Link *") },
                    placeholder = { Text("https://example.com") },
                    singleLine = true,
                    colors = textFieldColors,
                    trailingIcon = {
                        if (isFetchingMetadata) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = textColor
                            )
                        } else if (urlText.isNotBlank()) {
                            IconButton(onClick = { triggerAutoFetch(urlText) }) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Fetch link preview",
                                    tint = secondaryText,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )

                // Fetching progress status
                if (isFetchingMetadata) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Text(
                            text = "Fetching link title, description & preview...",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = secondaryText
                        )
                    }
                }

                // Live Preview Card
                if (previewImageUrl.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = PocketThemeColors.thumbnailBg(isDark),
                        border = BorderStroke(0.5.dp, borderCol),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(previewImageUrl)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Link Preview",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = PocketThemeColors.badgeBg(isDark),
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(6.dp)
                            ) {
                                Text(
                                    text = "Preview Image",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PocketThemeColors.badgeText(isDark),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    label = { Text("Title (auto-fetched or custom)") },
                    placeholder = { Text("Article or page title") },
                    singleLine = true,
                    colors = textFieldColors,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = descText,
                    onValueChange = { descText = it },
                    label = { Text("Notes / Description (auto-fetched or custom)") },
                    placeholder = { Text("Add notes or details") },
                    maxLines = 3,
                    colors = textFieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = secondaryText)
                    ) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (urlText.isNotBlank()) {
                                onSave(titleText, urlText, descText, previewImageUrl)
                            }
                        },
                        enabled = urlText.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDark) Color(0xFFEEEEEE) else Color(0xFF202020),
                            contentColor = if (isDark) Color(0xFF161616) else Color(0xFFFFFFFF),
                            disabledContainerColor = if (isDark) Color(0xFF282828) else Color(0xFFE8E8E8),
                            disabledContentColor = if (isDark) Color(0xFF666666) else Color(0xFFA0A0A0)
                        )
                    ) {
                        Text("Save Bookmark")
                    }
                }
            }
        }
    }
}

/**
 * Edit Bookmark Dialog
 */
@Composable
fun EditBookmarkDialog(
    stack: MediaInboxStack,
    onDismiss: () -> Unit,
    onSave: (title: String, description: String, previewImageUrl: String) -> Unit,
    isDark: Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val textColor = PocketThemeColors.primaryText(isDark)
    val secondaryText = PocketThemeColors.secondaryText(isDark)
    val dialogSurface = if (isDark) Color(0xFF1E1E1E) else Color(0xFFFFFFFF)
    val borderCol = if (isDark) Color(0xFF333333) else Color(0xFFE0E0E0)

    var titleText by remember { mutableStateOf(stack.title) }
    var descText by remember { mutableStateOf(stack.description) }
    var previewImageUrl by remember { mutableStateOf(stack.previewImageUrl) }
    var isFetching by remember { mutableStateOf(false) }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = textColor,
        unfocusedBorderColor = if (isDark) Color(0xFF444444) else Color(0xFFD0D0D0),
        focusedLabelColor = textColor,
        unfocusedLabelColor = secondaryText,
        cursorColor = textColor,
        focusedTextColor = textColor,
        unfocusedTextColor = textColor
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = dialogSurface,
            tonalElevation = if (isDark) 4.dp else 2.dp,
            shadowElevation = if (isDark) 6.dp else 3.dp,
            border = BorderStroke(1.dp, borderCol),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Edit Bookmark",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = textColor
                    )

                    if (stack.sourceUrl.isNotBlank()) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    isFetching = true
                                    try {
                                        val meta = LinkMetadataFetcher.fetch(stack.sourceUrl)
                                        if (meta.title.isNotBlank()) titleText = meta.title
                                        if (meta.description.isNotBlank()) descText = meta.description
                                        if (meta.previewImageUrl.isNotBlank()) previewImageUrl = meta.previewImageUrl
                                    } catch (_: Exception) {
                                    } finally {
                                        isFetching = false
                                    }
                                }
                            },
                            enabled = !isFetching
                        ) {
                            if (isFetching) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = textColor)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = textColor)
                                Spacer(Modifier.width(4.dp))
                                Text("Re-fetch", style = MaterialTheme.typography.labelSmall, color = textColor)
                            }
                        }
                    }
                }

                if (previewImageUrl.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = PocketThemeColors.thumbnailBg(isDark),
                        border = BorderStroke(0.5.dp, borderCol),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(previewImageUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Preview Image",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    label = { Text("Title") },
                    singleLine = true,
                    colors = textFieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = descText,
                    onValueChange = { descText = it },
                    label = { Text("Notes / Description") },
                    maxLines = 3,
                    colors = textFieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = secondaryText)
                    ) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onSave(titleText, descText, previewImageUrl) },
                        enabled = titleText.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDark) Color(0xFFEEEEEE) else Color(0xFF202020),
                            contentColor = if (isDark) Color(0xFF161616) else Color(0xFFFFFFFF),
                            disabledContainerColor = if (isDark) Color(0xFF282828) else Color(0xFFE8E8E8),
                            disabledContentColor = if (isDark) Color(0xFF666666) else Color(0xFFA0A0A0)
                        )
                    ) {
                        Text("Update")
                    }
                }
            }
        }
    }
}

/**
 * Pocket Filter & Sort Bottom Sheet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PocketFilterBottomSheet(
    currentSort: String,
    currentFilter: String,
    showSvg: Boolean,
    showVideos: Boolean,
    showStreams: Boolean,
    showPng: Boolean,
    showGif: Boolean,
    minWidth: Int,
    minHeight: Int,
    minSizeKb: Int,
    onSelectSort: (String) -> Unit,
    onSelectFilter: (String) -> Unit,
    onToggleShowSvg: (Boolean) -> Unit,
    onToggleShowVideos: (Boolean) -> Unit,
    onToggleShowStreams: (Boolean) -> Unit,
    onToggleShowPng: (Boolean) -> Unit,
    onToggleShowGif: (Boolean) -> Unit,
    onWidthChange: (Int) -> Unit,
    onHeightChange: (Int) -> Unit,
    onSizeChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    isDark: Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f
) {
    val textColor = PocketThemeColors.primaryText(isDark)
    val secondaryText = PocketThemeColors.secondaryText(isDark)
    val sheetSurface = if (isDark) Color(0xFF1C1C1C) else Color(0xFFFFFFFF)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = sheetSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Sort & Filter Bookmarks",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = textColor
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Sort By",
                style = MaterialTheme.typography.labelMedium,
                color = secondaryText
            )

            Spacer(Modifier.height(8.dp))

            val sortOptions = listOf("Newest", "Oldest", "Title", "Domain")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sortOptions.forEach { sort ->
                    val selected = currentSort == sort
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectSort(sort) },
                        label = { Text(sort, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = if (isDark) Color(0xFFEEEEEE) else Color(0xFF202020),
                            selectedLabelColor = if (isDark) Color(0xFF161616) else Color(0xFFFFFFFF),
                            containerColor = if (isDark) Color(0xFF242424) else Color(0xFFF2F2F2),
                            labelColor = if (isDark) Color(0xFFCCCCCC) else Color(0xFF444444)
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (selected) Color.Transparent else (if (isDark) Color(0xFF383838) else Color(0xFFE0E0E0))
                        )
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Text(
                text = "Show",
                style = MaterialTheme.typography.labelMedium,
                color = secondaryText
            )

            Spacer(Modifier.height(8.dp))

            val filterOptions = listOf("All", "Web Links", "Notes")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                filterOptions.forEach { filter ->
                    val selected = currentFilter == filter
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectFilter(filter) },
                        label = { Text(filter, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = if (isDark) Color(0xFFEEEEEE) else Color(0xFF202020),
                            selectedLabelColor = if (isDark) Color(0xFF161616) else Color(0xFFFFFFFF),
                            containerColor = if (isDark) Color(0xFF242424) else Color(0xFFF2F2F2),
                            labelColor = if (isDark) Color(0xFFCCCCCC) else Color(0xFF444444)
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (selected) Color.Transparent else (if (isDark) Color(0xFF383838) else Color(0xFFE0E0E0))
                        )
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // Media Type Pills in the same row as SVG for Videos, Streams and PNGs, GIFs (Icon only, no text)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // SVG Pill Switch (Disabled by default, icon only)
                PocketMediaFilterPill(
                    icon = Icons.Default.Polyline,
                    contentDesc = "SVG",
                    checked = showSvg,
                    onCheckedChange = onToggleShowSvg,
                    isDark = isDark,
                    testTag = "filter_svg_pill_switch"
                )

                // Videos Pill Switch (Icon only)
                PocketMediaFilterPill(
                    icon = Icons.Default.Videocam,
                    contentDesc = "Videos",
                    checked = showVideos,
                    onCheckedChange = onToggleShowVideos,
                    isDark = isDark,
                    testTag = "filter_videos_pill_switch"
                )

                // Streams Pill Switch (Icon only)
                PocketMediaFilterPill(
                    icon = Icons.Default.Sensors,
                    contentDesc = "Streams",
                    checked = showStreams,
                    onCheckedChange = onToggleShowStreams,
                    isDark = isDark,
                    testTag = "filter_streams_pill_switch"
                )

                // PNGs Pill Switch (Icon only)
                PocketMediaFilterPill(
                    icon = Icons.Default.Image,
                    contentDesc = "PNG",
                    checked = showPng,
                    onCheckedChange = onToggleShowPng,
                    isDark = isDark,
                    testTag = "filter_png_pill_switch"
                )

                // GIFs Pill Switch (Icon only)
                PocketMediaFilterPill(
                    icon = Icons.Default.Gif,
                    contentDesc = "GIF",
                    checked = showGif,
                    onCheckedChange = onToggleShowGif,
                    isDark = isDark,
                    testTag = "filter_gif_pill_switch"
                )
            }

            Spacer(Modifier.height(20.dp))

            // Minimum Horizontal Dimension in its own separate row with 0 to infinity and current value displayed
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("filter_min_width_section")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = "Minimum Horizontal Dimension",
                            tint = if (minWidth > 0) textColor else secondaryText,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Minimum Width",
                            style = MaterialTheme.typography.labelMedium,
                            color = secondaryText
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (minWidth > 0) (if (isDark) Color(0xFF2A2A2A) else Color(0xFFE8E8E8)) else Color.Transparent
                    ) {
                        val textDisplay = when {
                            minWidth <= 0 -> "0 px (Any)"
                            minWidth >= 4000 -> "4000+ px (∞)"
                            else -> "$minWidth px"
                        }
                        Text(
                            text = textDisplay,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (minWidth > 0) textColor else secondaryText,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                Slider(
                    value = minWidth.toFloat().coerceIn(0f, 4000f),
                    onValueChange = { onWidthChange(it.roundToInt()) },
                    valueRange = 0f..4000f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("filter_min_width_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = if (minWidth > 0) textColor else secondaryText,
                        activeTrackColor = textColor,
                        inactiveTrackColor = if (isDark) Color(0xFF383838) else Color(0xFFE0E0E0)
                    )
                )
            }

            Spacer(Modifier.height(14.dp))

            // Minimum Vertical Dimension in its own separate row with 0 to infinity and current value displayed
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("filter_min_height_section")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapVert,
                            contentDescription = "Minimum Vertical Dimension",
                            tint = if (minHeight > 0) textColor else secondaryText,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Minimum Height",
                            style = MaterialTheme.typography.labelMedium,
                            color = secondaryText
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (minHeight > 0) (if (isDark) Color(0xFF2A2A2A) else Color(0xFFE8E8E8)) else Color.Transparent
                    ) {
                        val textDisplay = when {
                            minHeight <= 0 -> "0 px (Any)"
                            minHeight >= 4000 -> "4000+ px (∞)"
                            else -> "$minHeight px"
                        }
                        Text(
                            text = textDisplay,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (minHeight > 0) textColor else secondaryText,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                Slider(
                    value = minHeight.toFloat().coerceIn(0f, 4000f),
                    onValueChange = { onHeightChange(it.roundToInt()) },
                    valueRange = 0f..4000f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("filter_min_height_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = if (minHeight > 0) textColor else secondaryText,
                        activeTrackColor = textColor,
                        inactiveTrackColor = if (isDark) Color(0xFF383838) else Color(0xFFE0E0E0)
                    )
                )
            }

            Spacer(Modifier.height(14.dp))

            // Minimum Media Size Slider (0KB to 10MB) in separate row with current size position displayed
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("filter_min_size_section")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderZip,
                            contentDescription = "Minimum Media Size",
                            tint = if (minSizeKb > 0) textColor else secondaryText,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Minimum Media Size",
                            style = MaterialTheme.typography.labelMedium,
                            color = secondaryText
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (minSizeKb > 0) (if (isDark) Color(0xFF2A2A2A) else Color(0xFFE8E8E8)) else Color.Transparent
                    ) {
                        val sizeDisplay = when {
                            minSizeKb <= 0 -> "0 KB (Any)"
                            minSizeKb < 1024 -> "$minSizeKb KB"
                            minSizeKb >= 10240 -> "10 MB"
                            else -> "${String.format(Locale.US, "%.1f", minSizeKb / 1024f)} MB"
                        }
                        Text(
                            text = sizeDisplay,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (minSizeKb > 0) textColor else secondaryText,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                Slider(
                    value = minSizeKb.toFloat().coerceIn(0f, 10240f),
                    onValueChange = { onSizeChange(it.roundToInt()) },
                    valueRange = 0f..10240f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("filter_min_size_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = if (minSizeKb > 0) textColor else secondaryText,
                        activeTrackColor = textColor,
                        inactiveTrackColor = if (isDark) Color(0xFF383838) else Color(0xFFE0E0E0)
                    )
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Clean, icon-only pill switch for media type filtering matching the SVG pill design.
 * No text or explanation, strictly icon + toggle switch.
 */
@Composable
fun PocketMediaFilterPill(
    icon: ImageVector,
    contentDesc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    isDark: Boolean,
    testTag: String
) {
    Surface(
        shape = CircleShape,
        color = if (checked) (if (isDark) Color(0xFF2E2C34) else Color(0xFFE4DFEB))
        else (if (isDark) Color(0xFF242424) else Color(0xFFF2F2F2)),
        border = BorderStroke(
            1.dp,
            if (checked) (if (isDark) Color(0xFF555060) else Color(0xFFC5BED0))
            else (if (isDark) Color(0xFF383838) else Color(0xFFE0E0E0))
        ),
        modifier = Modifier
            .clip(CircleShape)
            .clickable { onCheckedChange(!checked) }
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDesc,
                tint = if (checked) (if (isDark) Color(0xFFEDEDED) else Color(0xFF161616))
                else (if (isDark) Color(0xFF888888) else Color(0xFF757575)),
                modifier = Modifier.size(20.dp)
            )
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.scale(0.85f),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = if (isDark) Color(0xFF161616) else Color(0xFFFFFFFF),
                    checkedTrackColor = if (isDark) Color(0xFFEEEEEE) else Color(0xFF202020),
                    uncheckedThumbColor = if (isDark) Color(0xFF888888) else Color(0xFF757575),
                    uncheckedTrackColor = if (isDark) Color(0xFF323232) else Color(0xFFE0E0E0)
                )
            )
        }
    }
}
