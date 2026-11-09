package com.amresalehin.emregallery.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.amresalehin.emregallery.data.MediaInboxItem
import com.amresalehin.emregallery.data.MediaInboxStack
import com.amresalehin.emregallery.data.PocketRepository
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Fullscreen separate page for Bookmark Detail view in Emre Gallery.
 * Completely decoupled from popup modals with dedicated navigation, backstack handling,
 * and edge-to-edge layout.
 */
@Composable
fun BookmarkDetailScreen(
    stack: MediaInboxStack,
    onBack: () -> Unit,
    onOpenBrowser: (String) -> Unit,
    onDelete: () -> Unit,
    onRefreshMetadata: (() -> Unit)? = null,
    onFetchSiteMedia: (() -> Unit)? = null,
    isDark: Boolean = isSystemInDarkTheme(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pocketRepo = remember { PocketRepository.getInstance(context) }
    val allBookmarks by pocketRepo.bookmarks.collectAsState()

    // Keep live data synced if metadata refreshes or is updated
    val liveStack = remember(allBookmarks, stack.id) {
        allBookmarks.find { it.id == stack.id } ?: stack
    }

    var editingStack by remember { mutableStateOf<MediaInboxStack?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    // Intercept back gesture / key to return cleanly to previous page
    BackHandler {
        onBack()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PocketThemeColors.background(isDark))
            .testTag("bookmark_detail_screen")
    ) {
        PocketBookmarkViewerPage(
            stack = liveStack,
            onBack = onBack,
            onOpenBrowser = onOpenBrowser,
            onDelete = { showDeleteConfirmDialog = true },
            onEditNote = { editingStack = liveStack },
            onRefreshMetadata = onRefreshMetadata,
            onFetchSiteMedia = onFetchSiteMedia,
            isDark = isDark,
            modifier = Modifier.fillMaxSize()
        )

        // Edit Bookmark Dialog
        editingStack?.let { currentEditing ->
            EditBookmarkDialog(
                stack = currentEditing,
                onDismiss = { editingStack = null },
                onSave = { updatedTitle, updatedDesc, updatedPreview ->
                    val updated = currentEditing.copy(
                        title = updatedTitle,
                        description = updatedDesc,
                        previewImageUrl = updatedPreview
                    )
                    pocketRepo.updateBookmark(updated)
                    editingStack = null
                },
                isDark = isDark
            )
        }

        // Delete confirmation dialog
        if (showDeleteConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = false },
                containerColor = if (isDark) Color(0xFF1C1C1C) else Color(0xFFFFFFFF),
                titleContentColor = PocketThemeColors.primaryText(isDark),
                textContentColor = PocketThemeColors.secondaryText(isDark),
                icon = {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = null,
                        tint = PocketThemeColors.primaryText(isDark)
                    )
                },
                title = {
                    Text(
                        text = "Delete Bookmark",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text("Are you sure you want to remove this bookmark from Pocket?")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteConfirmDialog = false
                            onDelete()
                        }
                    ) {
                        Text(
                            text = "Delete",
                            color = if (isDark) Color(0xFFFF6B6B) else Color(0xFFD32F2F),
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirmDialog = false }) {
                        Text(
                            text = "Cancel",
                            color = PocketThemeColors.secondaryText(isDark)
                        )
                    }
                }
            )
        }
    }
}

/**
 * Bookmark Viewer / Detail Page conforming strictly to Emre Gallery specifications:
 * - Floating circular buttons (Back, Reader, Copy, Refresh, Delete)
 * - Major screen area: 9:14 Card Deck container taking maximum screen space
 * - Interactive swiping across card deck layers with tilt and elevation
 * - Expandable, resizable, and dismissable details drawer anchored to the bottom
 * - Reusable detail row cards for Time, Source, Title, Tags, Site Media, Description, Note
 */
@Composable
fun PocketBookmarkViewerPage(
    stack: MediaInboxStack,
    onBack: () -> Unit,
    onOpenBrowser: (String) -> Unit,
    onDelete: () -> Unit,
    onEditNote: () -> Unit,
    onRefreshMetadata: (() -> Unit)? = null,
    onFetchSiteMedia: (() -> Unit)? = null,
    isDark: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val textColor = PocketThemeColors.primaryText(isDark)
    val secondaryText = PocketThemeColors.secondaryText(isDark)
    val cardSurface = PocketThemeColors.stackFront(isDark)
    val barSurface = PocketThemeColors.barSurface(isDark)
    val dividerCol = PocketThemeColors.divider(isDark)

    // Fullscreen media inspection state
    var fullScreenMediaIndex by remember { mutableStateOf<Int?>(null) }
    var isDeckVideoMuted by remember { mutableStateOf(false) }

    // Drawer state: resizable, dismissable, occupies less place
    var isDrawerDismissed by remember { mutableStateOf(false) }
    var isDrawerExpanded by remember { mutableStateOf(false) }
    var userDrawerHeightDp by remember { mutableStateOf<Dp?>(null) }

    val compactDrawerHeight = 135.dp
    val expandedDrawerHeight = 440.dp
    val minDrawerHeight = 85.dp
    val maxDrawerHeight = 520.dp

    val currentTargetHeight = userDrawerHeightDp ?: if (isDrawerExpanded) expandedDrawerHeight else compactDrawerHeight
    val animatedDrawerHeight by animateDpAsState(
        targetValue = currentTargetHeight,
        label = "pocket_drawer_height"
    )

    // Effective media items discovered from the site
    val effectiveMediaList = remember(stack) {
        if (stack.items.isNotEmpty()) {
            stack.items
        } else if (stack.previewImageUrl.isNotBlank()) {
            listOf(
                MediaInboxItem(
                    id = "preview_${stack.id}",
                    title = stack.title.ifBlank { "Preview" },
                    localUri = "",
                    sourceUrl = stack.previewImageUrl,
                    isVideo = false
                )
            )
        } else if (stack.sourceDomain.isNotBlank()) {
            listOf(
                MediaInboxItem(
                    id = "icon_${stack.id}",
                    title = stack.title.ifBlank { stack.sourceDomain },
                    localUri = "",
                    sourceUrl = "https://www.google.com/s2/favicons?domain=${stack.sourceDomain}&sz=256",
                    isVideo = false
                )
            )
        } else {
            emptyList()
        }
    }

    var currentIndex by remember(stack.id) { mutableIntStateOf(0) }
    val totalCards = effectiveMediaList.size
    val safeIndex = if (totalCards > 0) currentIndex.coerceIn(0, totalCards - 1) else 0
    val offsetX = remember { Animatable(0f) }
    var isSwiping by remember { mutableStateOf(false) }

    // Inspect the live page on entry
    LaunchedEffect(stack.id, stack.sourceUrl) {
        if (stack.sourceUrl.startsWith("http://", ignoreCase = true) ||
            stack.sourceUrl.startsWith("https://", ignoreCase = true)
        ) {
            onFetchSiteMedia?.invoke()
        }
    }

    val dateFormatted = remember(stack.timestampMillis) {
        val sdf = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())
        sdf.format(Date(stack.timestampMillis))
    }

    val faviconUrl = remember(stack.sourceDomain) {
        if (stack.sourceDomain.isNotBlank()) {
            "https://www.google.com/s2/favicons?domain=${stack.sourceDomain}&sz=128"
        } else ""
    }

    // Derive tags without fabricating any information
    val derivedTags = remember(stack, effectiveMediaList) {
        val tags = mutableListOf<String>()
        if (stack.sourceDomain.isNotBlank()) {
            tags.add(stack.sourceDomain.removePrefix("www."))
        }
        if (effectiveMediaList.isNotEmpty()) {
            tags.add("${effectiveMediaList.size} Media")
        } else if (stack.sourceUrl.isNotBlank()) {
            tags.add("Web Link")
        }
        if (tags.isEmpty()) {
            tags.add("Bookmark")
        }
        tags
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .background(PocketThemeColors.background(isDark)),
        color = PocketThemeColors.background(isDark)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Main Screen Area: Top Bar, 9:14 Card Deck (Major Part of Screen)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = if (!isDrawerDismissed) animatedDrawerHeight else 0.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top App Bar with Circular Buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Back Button (Circle)
                    Surface(
                        shape = CircleShape,
                        color = barSurface,
                        shadowElevation = if (isDark) 2.dp else 1.dp,
                        border = BorderStroke(1.dp, if (isDark) dividerCol.copy(alpha = 0.7f) else dividerCol),
                        modifier = Modifier
                            .size(44.dp)
                            .clickable(onClick = onBack)
                            .testTag("bookmark_viewer_back_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = textColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Action Buttons on Right (Reader, Copy, Refresh, Delete)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Open Reader / Browser
                        if (stack.sourceUrl.isNotBlank()) {
                            Surface(
                                shape = CircleShape,
                                color = barSurface,
                                shadowElevation = if (isDark) 2.dp else 1.dp,
                                border = BorderStroke(1.dp, if (isDark) dividerCol.copy(alpha = 0.7f) else dividerCol),
                                modifier = Modifier
                                    .size(44.dp)
                                    .clickable { onOpenBrowser(stack.sourceUrl) }
                                    .testTag("bookmark_viewer_reader_button")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.MenuBook,
                                        contentDescription = "Open in Reader",
                                        tint = textColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }

                        // 2. Copy Link
                        Surface(
                            shape = CircleShape,
                            color = barSurface,
                            shadowElevation = if (isDark) 2.dp else 1.dp,
                            border = BorderStroke(1.dp, if (isDark) dividerCol.copy(alpha = 0.7f) else dividerCol),
                            modifier = Modifier
                                .size(44.dp)
                                .clickable {
                                    val textToCopy = stack.sourceUrl.ifBlank { stack.title }
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("URL", textToCopy)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Link copied to clipboard", Toast.LENGTH_SHORT).show()
                                }
                                .testTag("bookmark_viewer_copy_button")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy link",
                                    tint = textColor,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }

                        // 3. Refresh Link Preview & Metadata
                        if (stack.sourceUrl.isNotBlank()) {
                            Surface(
                                shape = CircleShape,
                                color = barSurface,
                                shadowElevation = if (isDark) 2.dp else 1.dp,
                                border = BorderStroke(1.dp, if (isDark) dividerCol.copy(alpha = 0.7f) else dividerCol),
                                modifier = Modifier
                                    .size(44.dp)
                                    .clickable { onRefreshMetadata?.invoke() }
                                    .testTag("bookmark_viewer_refresh_button")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Refresh link preview and details",
                                        tint = textColor,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                        }

                        // 4. Delete (monochrome)
                        Surface(
                            shape = CircleShape,
                            color = barSurface,
                            shadowElevation = if (isDark) 2.dp else 1.dp,
                            border = BorderStroke(1.dp, if (isDark) dividerCol.copy(alpha = 0.7f) else dividerCol),
                            modifier = Modifier
                                .size(44.dp)
                                .clickable(onClick = onDelete)
                                .testTag("bookmark_viewer_delete_button")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Delete bookmark",
                                    tint = textColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                // Major Screen Area: Deck Card Stack (Strict 1:1 Aspect Ratio)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BoxWithConstraints(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        val maxDeckW = maxWidth - 24.dp
                        val maxDeckH = maxHeight - 16.dp
                        val deckSide = minOf(maxDeckW, maxDeckH).coerceAtLeast(180.dp)

                        Box(
                            modifier = Modifier.size(deckSide),
                            contentAlignment = Alignment.Center
                        ) {
                            // Layer 1 (Bottom peeking card: 2 cards ahead, offset x = 18.dp, y = -12.dp, rotationZ = 6.5f)
                            if (totalCards > 2) {
                                val backIndex2 = (safeIndex + 2) % totalCards
                                Surface(
                                    shape = RoundedCornerShape(32.dp),
                                    color = PocketThemeColors.stackLayer1(isDark),
                                    border = BorderStroke(0.5.dp, if (isDark) dividerCol.copy(alpha = 0.35f) else Color(0xFFDDD8E2)),
                                    shadowElevation = if (isDark) 2.dp else 1.5.dp,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            translationX = 18.dp.toPx()
                                            translationY = (-12).dp.toPx()
                                            rotationZ = 6.5f
                                        }
                                ) {
                                    Box(modifier = Modifier.fillMaxSize())
                                }
                            }

                            // Layer 2 (Middle peeking card: next media, offset x = 10.dp, y = -6.dp, rotationZ = 3.5f)
                            if (totalCards > 1) {
                                val backIndex1 = (safeIndex + 1) % totalCards
                                Surface(
                                    shape = RoundedCornerShape(32.dp),
                                    color = PocketThemeColors.stackLayer2(isDark),
                                    border = BorderStroke(0.5.dp, if (isDark) dividerCol.copy(alpha = 0.45f) else Color(0xFFE2DCE6)),
                                    shadowElevation = if (isDark) 4.dp else 2.5.dp,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            translationX = 10.dp.toPx()
                                            translationY = (-6).dp.toPx()
                                            rotationZ = 3.5f
                                        }
                                        .clickable {
                                            scope.launch {
                                                offsetX.animateTo(-600f, tween(180))
                                                currentIndex = backIndex1
                                                offsetX.snapTo(0f)
                                            }
                                        }
                                ) {
                                    Box(modifier = Modifier.fillMaxSize())
                                }
                            }

                            // Layer 3 (Top Front Card in Deck - Exact 1:1 Container, Interactive Swipe Gesture)
                            val currentItem = effectiveMediaList.getOrNull(safeIndex)
                            val currentMediaPath = remember(currentItem) {
                                if (currentItem?.localUri?.isNotBlank() == true) {
                                    val f = File(currentItem.localUri.removePrefix("file://"))
                                    if (f.exists()) f.absolutePath else currentItem.sourceUrl
                                } else currentItem?.sourceUrl ?: ""
                            }

                            Surface(
                                shape = RoundedCornerShape(32.dp),
                                color = PocketThemeColors.stackFront(isDark),
                                shadowElevation = if (isDark) 8.dp else 4.dp,
                                border = BorderStroke(1.dp, if (isDark) dividerCol.copy(alpha = 0.6f) else Color(0xFFDED8E2)),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        translationX = offsetX.value
                                        rotationZ = (offsetX.value / 32f).coerceIn(-12f, 12f)
                                    }
                                    .draggable(
                                        orientation = Orientation.Horizontal,
                                        enabled = totalCards > 1,
                                        state = rememberDraggableState { delta ->
                                            scope.launch {
                                                offsetX.snapTo(offsetX.value + delta)
                                            }
                                        },
                                        onDragStarted = { isSwiping = true },
                                        onDragStopped = { velocity ->
                                            isSwiping = false
                                            val swipeThreshold = 160f
                                            val shouldSwipeLeft = offsetX.value < -swipeThreshold || velocity < -500f
                                            val shouldSwipeRight = offsetX.value > swipeThreshold || velocity > 500f
                                            scope.launch {
                                                if (totalCards > 1 && shouldSwipeLeft) {
                                                    offsetX.animateTo(
                                                        targetValue = -1200f,
                                                        animationSpec = tween(180)
                                                    )
                                                    currentIndex = (safeIndex + 1) % totalCards
                                                    offsetX.snapTo(0f)
                                                } else if (totalCards > 1 && shouldSwipeRight) {
                                                    offsetX.animateTo(
                                                        targetValue = 1200f,
                                                        animationSpec = tween(180)
                                                    )
                                                    currentIndex = if (safeIndex - 1 < 0) totalCards - 1 else safeIndex - 1
                                                    offsetX.snapTo(0f)
                                                } else {
                                                    offsetX.animateTo(
                                                        targetValue = 0f,
                                                        animationSpec = spring(
                                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                                            stiffness = Spring.StiffnessLow
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    )
                                    .clickable {
                                        fullScreenMediaIndex = safeIndex
                                    }
                                    .testTag("deck_front_card_$safeIndex")
                            ) {
                                if (currentItem != null && currentMediaPath.isNotBlank()) {
                                    val isCurrentVideo = remember(currentItem, currentMediaPath) {
                                        currentItem.isVideo ||
                                                currentMediaPath.endsWith(".mp4", ignoreCase = true) ||
                                                currentMediaPath.endsWith(".webm", ignoreCase = true) ||
                                                currentMediaPath.endsWith(".mov", ignoreCase = true) ||
                                                currentMediaPath.contains(".m3u8", ignoreCase = true)
                                    }

                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(14.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isCurrentVideo) {
                                            BookmarkDeckVideoPlayer(
                                                uri = currentMediaPath,
                                                isPlaying = !isSwiping && fullScreenMediaIndex == null,
                                                isMuted = isDeckVideoMuted,
                                                onToggleMute = { isDeckVideoMuted = !isDeckVideoMuted },
                                                isDark = isDark,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .clip(RoundedCornerShape(22.dp))
                                            )
                                        } else {
                                            AsyncImage(
                                                model = ImageRequest.Builder(context)
                                                    .data(currentMediaPath)
                                                    .crossfade(true)
                                                    .build(),
                                                contentDescription = currentItem.title.ifBlank { "Media $safeIndex" },
                                                contentScale = ContentScale.Fit,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .clip(RoundedCornerShape(22.dp))
                                            )
                                        }
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(20.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            if (faviconUrl.isNotBlank()) {
                                                Surface(
                                                    shape = RoundedCornerShape(18.dp),
                                                    color = PocketThemeColors.faviconSurface(isDark),
                                                    shadowElevation = 3.dp,
                                                    modifier = Modifier.size(56.dp)
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        AsyncImage(
                                                            model = ImageRequest.Builder(context)
                                                                .data(faviconUrl)
                                                                .crossfade(true)
                                                                .build(),
                                                            contentDescription = stack.sourceDomain,
                                                            modifier = Modifier.size(36.dp)
                                                        )
                                                    }
                                                }
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Default.Bookmark,
                                                    contentDescription = null,
                                                    tint = textColor,
                                                    modifier = Modifier.size(48.dp)
                                                )
                                            }
                                            Spacer(Modifier.height(10.dp))
                                            Text(
                                                text = stack.sourceDomain.ifBlank { "Bookmark Preview" },
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = textColor
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Card counter pill indicator (e.g. "2 / 5") from design
                if (totalCards > 1) {
                    Surface(
                        shape = CircleShape,
                        color = if (isDark) Color(0xFF26242C) else Color(0xFFEDE8F0),
                        border = BorderStroke(0.5.dp, if (isDark) dividerCol.copy(alpha = 0.4f) else Color(0xFFDAD5DF)),
                        modifier = Modifier
                            .padding(bottom = 8.dp)
                            .testTag("bookmark_deck_counter_badge")
                    ) {
                        Text(
                            text = "${safeIndex + 1} / $totalCards",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                letterSpacing = 0.5.sp
                            ),
                            color = textColor,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // Resizable & Dismissable Drawer (Anchored from Bottom)
            AnimatedVisibility(
                visible = !isDrawerDismissed,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Surface(
                    shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                    color = PocketThemeColors.drawerSurface(isDark),
                    tonalElevation = 6.dp,
                    shadowElevation = 16.dp,
                    border = BorderStroke(1.dp, if (isDark) dividerCol.copy(alpha = 0.7f) else dividerCol),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(animatedDrawerHeight)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding()
                    ) {
                        // Resizable Drag Header & Controls
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(Unit) {
                                    detectVerticalDragGestures(
                                        onVerticalDrag = { change, dragAmount ->
                                            change.consume()
                                            val current = userDrawerHeightDp ?: if (isDrawerExpanded) expandedDrawerHeight else compactDrawerHeight
                                            val newHeight = (current - dragAmount.dp).coerceIn(minDrawerHeight, maxDrawerHeight)
                                            userDrawerHeightDp = newHeight
                                        },
                                        onDragEnd = {
                                            val current = userDrawerHeightDp ?: compactDrawerHeight
                                            if (current < 95.dp) {
                                                isDrawerDismissed = true
                                                userDrawerHeightDp = null
                                                isDrawerExpanded = false
                                            } else if (current > 240.dp) {
                                                isDrawerExpanded = true
                                                userDrawerHeightDp = null
                                            } else {
                                                isDrawerExpanded = false
                                                userDrawerHeightDp = null
                                            }
                                        }
                                    )
                                }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            IconButton(
                                onClick = {
                                    isDrawerExpanded = !isDrawerExpanded
                                    userDrawerHeightDp = null
                                },
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("drawer_resize_toggle")
                            ) {
                                Icon(
                                    imageVector = if (isDrawerExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                    contentDescription = if (isDrawerExpanded) "Collapse drawer" else "Expand drawer",
                                    tint = textColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .width(44.dp)
                                    .height(4.dp)
                                    .clip(CircleShape)
                                    .background(PocketThemeColors.drawerHandle(isDark))
                            )

                            IconButton(
                                onClick = {
                                    isDrawerDismissed = true
                                    userDrawerHeightDp = null
                                    isDrawerExpanded = false
                                },
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("drawer_dismiss_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss drawer",
                                    tint = secondaryText,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Scrollable Drawer Content
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 18.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // 1. Time Row
                            PocketDetailCardRow(
                                icon = Icons.Default.CalendarToday,
                                label = "Time",
                                value = dateFormatted,
                                textColor = textColor,
                                secondaryText = secondaryText,
                                barSurface = barSurface,
                                dividerCol = dividerCol
                            )

                            // 2. Source Row (Clickable to open link)
                            PocketDetailCardRow(
                                icon = Icons.Default.Link,
                                label = "Source",
                                value = stack.sourceUrl.ifBlank { stack.sourceDomain.ifBlank { "No source URL" } },
                                trailingIcon = if (stack.sourceUrl.isNotBlank()) Icons.AutoMirrored.Filled.OpenInNew else null,
                                onClick = if (stack.sourceUrl.isNotBlank()) { { onOpenBrowser(stack.sourceUrl) } } else null,
                                textColor = textColor,
                                secondaryText = secondaryText,
                                barSurface = barSurface,
                                dividerCol = dividerCol
                            )

                            // 3. Title Row
                            PocketDetailCardRow(
                                icon = Icons.Default.ShortText,
                                label = "Title",
                                value = stack.title.ifBlank { "Untitled Bookmark" },
                                textColor = textColor,
                                secondaryText = secondaryText,
                                barSurface = barSurface,
                                dividerCol = dividerCol
                            )

                            // 4. Tags Row
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = barSurface,
                                border = BorderStroke(1.dp, if (isDark) dividerCol.copy(alpha = 0.5f) else Color(0xFFE6E6E6)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Sell,
                                        contentDescription = null,
                                        tint = textColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        text = "Tags",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = textColor
                                    )
                                    Spacer(Modifier.width(16.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        derivedTags.forEach { tag ->
                                            Surface(
                                                shape = CircleShape,
                                                color = PocketThemeColors.tagChipBg(isDark),
                                                border = BorderStroke(0.5.dp, if (isDark) dividerCol else Color(0xFFDDDDDD)),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                Box(
                                                    contentAlignment = Alignment.Center,
                                                    modifier = Modifier.padding(horizontal = 10.dp)
                                                ) {
                                                    Text(
                                                        text = tag,
                                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                                        color = textColor
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // 5. Site Media Collection Strip
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = barSurface,
                                border = BorderStroke(1.dp, if (isDark) dividerCol.copy(alpha = 0.5f) else Color(0xFFE6E6E6)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("site_media_collection_card")
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Collections,
                                                contentDescription = null,
                                                tint = textColor,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = "Site Media",
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                color = textColor
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Surface(
                                                shape = CircleShape,
                                                color = PocketThemeColors.tagChipBg(isDark),
                                                modifier = Modifier.height(20.dp)
                                            ) {
                                                Box(
                                                    contentAlignment = Alignment.Center,
                                                    modifier = Modifier.padding(horizontal = 6.dp)
                                                ) {
                                                    Text(
                                                        text = "${effectiveMediaList.size}",
                                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                        color = textColor
                                                    )
                                                }
                                            }
                                        }

                                        if (stack.sourceUrl.isNotBlank()) {
                                            IconButton(
                                                onClick = { onFetchSiteMedia?.invoke() },
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .testTag("scan_site_media_button")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Sync,
                                                    contentDescription = "Scan site for media",
                                                    tint = secondaryText,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }

                                    Spacer(Modifier.height(8.dp))

                                    if (effectiveMediaList.isNotEmpty()) {
                                        LazyRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            itemsIndexed(effectiveMediaList) { index, item ->
                                                val isSelected = (index == safeIndex)
                                                val mediaPath = remember(item) {
                                                    if (item.localUri.isNotBlank()) {
                                                        val f = File(item.localUri.removePrefix("file://"))
                                                        if (f.exists()) f else item.sourceUrl
                                                    } else item.sourceUrl
                                                }

                                                Surface(
                                                    shape = RoundedCornerShape(12.dp),
                                                    color = PocketThemeColors.thumbnailBg(isDark),
                                                    border = BorderStroke(
                                                        width = if (isSelected) 2.dp else 1.dp,
                                                        color = if (isSelected) textColor else dividerCol
                                                    ),
                                                    shadowElevation = if (isSelected) 3.dp else 0.dp,
                                                    modifier = Modifier
                                                        .size(width = 80.dp, height = 66.dp)
                                                        .clickable {
                                                            if (isSelected) {
                                                                fullScreenMediaIndex = index
                                                            } else {
                                                                currentIndex = index
                                                                scope.launch { offsetX.snapTo(0f) }
                                                            }
                                                        }
                                                        .testTag("site_media_thumbnail_$index")
                                                ) {
                                                    Box(modifier = Modifier.fillMaxSize()) {
                                                        AsyncImage(
                                                            model = ImageRequest.Builder(context)
                                                                .data(mediaPath)
                                                                .crossfade(true)
                                                                .build(),
                                                            contentDescription = item.title,
                                                            contentScale = ContentScale.Crop,
                                                            modifier = Modifier.fillMaxSize()
                                                        )

                                                        Box(
                                                            modifier = Modifier
                                                                .align(Alignment.BottomEnd)
                                                                .padding(3.dp)
                                                                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                                        ) {
                                                            Text(
                                                                text = if (item.isVideo) "VID" else "#${index + 1}",
                                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                                                color = Color.White
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        Text(
                                            text = "No site media detected yet. Tap refresh to inspect web page.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = secondaryText,
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        )
                                    }
                                }
                            }

                            // 6. Description Row
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = barSurface,
                                border = BorderStroke(1.dp, if (isDark) dividerCol.copy(alpha = 0.5f) else Color(0xFFE6E6E6)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Subject,
                                            contentDescription = null,
                                            tint = textColor,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Text(
                                            text = "Description",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = textColor
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = if (stack.description.isNotBlank()) stack.description else "No additional description provided.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (stack.description.isNotBlank()) textColor else secondaryText
                                    )
                                }
                            }

                            // 7. Note Row (Clickable to edit note)
                            PocketDetailCardRow(
                                icon = Icons.Default.Description,
                                label = "Note",
                                value = stack.description.ifBlank { "Tap to add note" },
                                trailingIcon = Icons.Default.Edit,
                                onClick = onEditNote,
                                textColor = textColor,
                                secondaryText = secondaryText,
                                barSurface = barSurface,
                                dividerCol = dividerCol
                            )

                            Spacer(Modifier.height(20.dp))
                        }
                    }
                }
            }

            // Floating capsule to re-open drawer when dismissed
            AnimatedVisibility(
                visible = isDrawerDismissed,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 14.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = barSurface,
                    border = BorderStroke(1.dp, dividerCol),
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .height(42.dp)
                        .clickable {
                            isDrawerDismissed = false
                            isDrawerExpanded = false
                            userDrawerHeightDp = null
                        }
                        .testTag("reopen_drawer_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = null,
                            tint = textColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Details & Media (${effectiveMediaList.size})",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = textColor
                        )
                    }
                }
            }
        }

        // Fullscreen media inspection with Native Gallery Media Viewer canvas styling, UI, UX, swipe, scrim
        fullScreenMediaIndex?.let { initialIndex ->
            PocketMediaViewerScreen(
                items = effectiveMediaList,
                initialIndex = initialIndex,
                stack = stack,
                onDismiss = { fullScreenMediaIndex = null },
                onOpenBrowser = onOpenBrowser,
                isDark = isDark
            )
        }
    }
}
