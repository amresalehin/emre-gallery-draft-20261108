package com.amresalehin.emregallery.ui

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.ViewConfiguration
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import com.amresalehin.emregallery.data.StoryGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import coil.size.Size
import com.amresalehin.emregallery.data.CustomAlbum
import com.amresalehin.emregallery.data.MediaItem
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Fullscreen media viewer screen supporting:
 * - Shared-element return animation back to original source thumbnail
 * - Playback continuity between videos (respects user Play/Pause intent)
 * - Safe layout of video playback controls without overlapping bottom action buttons
 * - True fullscreen immersive video playback with status/navigation bar hiding and pure black backdrop
 * - Fluid pinch/double-tap zoom/pan
 * - Unified 1:1 gesture-coordinated details drawer
 */
@Composable
fun ViewerScreen(
    item: MediaItem,
    items: List<MediaItem>,
    customAlbums: List<CustomAlbum>,
    storyTitle: String? = null,
    storyGroups: List<StoryGroup> = emptyList(),
    initialStoryIndex: Int = 0,
    defaultOcrLanguages: Set<String> = setOf("eng"),
    defaultOcrModel: String = "fast",
    defaultMlKitLanguages: Set<String> = setOf("latin"),
    customTags: Set<String>,
    customPeople: Set<String>,
    onAddTag: (String) -> Unit,
    onAddPerson: (String) -> Unit,
    favourite: Boolean,
    isHidden: Boolean,
    isBin: Boolean,
    isArchived: Boolean,
    showControls: Boolean,
    showDetails: Boolean,
    onToggleControls: () -> Unit,
    onToggleDetails: () -> Unit,
    onBack: () -> Unit,
    onFavourite: () -> Unit,
    onToggleHide: () -> Unit,
    onToggleBin: () -> Unit,
    onToggleArchive: () -> Unit,
    onDeletePermanently: (() -> Unit)? = null,
    onAddTo: () -> Unit,
    onOpenNeighbor: (MediaItem) -> Unit,
    onOpenMap: () -> Unit,
    onOpenPerson: ((personId: String, engineId: String) -> Unit)? = null,
    transitionProgress: Animatable<Float, AnimationVector1D> = remember { Animatable(0f) }
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val viewConfig = remember(context) { ViewConfiguration.get(context) }
    val minFlingVelocity = remember(viewConfig) { viewConfig.scaledMinimumFlingVelocity.toFloat() }

    // Session-wide playback intent: true by default, updated on manual user Play/Pause.
    // Switching videos retains user's paused or playing intent.
    var sessionPlayIntent by remember { mutableStateOf(true) }

    // Video playback management with intent continuity
    val videoState = rememberViewerVideoState(
        uri = item.uri,
        isVideo = item.isVideo,
        showControls = showControls,
        sessionPlayIntent = sessionPlayIntent,
        onPlayIntentChange = { sessionPlayIntent = it }
    )

    // True fullscreen window immersion management
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(videoState.isFullscreen, activity) {
        val window = activity?.window
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            if (videoState.isFullscreen) {
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
                insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    var isClosing by remember { mutableStateOf(false) }

    LaunchedEffect(item.uri) {
        ThumbnailBoundsTracker.activeViewerUri = item.uri
        transitionProgress.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = 500f
            )
        )
    }

    // "More" bottom sheet state
    var showMore by remember { mutableStateOf(false) }
    var confirmPermanentDelete by remember { mutableStateOf(false) }
    var confirmMoveToBin by remember { mutableStateOf(false) }

    // Zoom state of current active image
    var isCurrentZoomed by remember { mutableStateOf(false) }

    // Downward drag-to-dismiss offset
    val dismissOffsetY = remember { Animatable(0f) }
    LaunchedEffect(item.uri) {
        dismissOffsetY.snapTo(0f)
    }

    // Story mode state
    val isStoryMode = storyTitle != null || storyGroups.isNotEmpty()
    var activeStoryIndex by remember(storyGroups, initialStoryIndex) {
        mutableIntStateOf(initialStoryIndex.coerceIn(0, (storyGroups.size - 1).coerceAtLeast(0)))
    }

    val currentStory = remember(storyGroups, activeStoryIndex, storyTitle, items) {
        if (storyGroups.isNotEmpty()) {
            storyGroups.getOrElse(activeStoryIndex.coerceIn(0, storyGroups.size - 1)) {
                StoryGroup(storyTitle ?: "", items)
            }
        } else {
            StoryGroup(storyTitle ?: "", items)
        }
    }
    val currentStoryTitle = currentStory.title
    val currentStoryItems = currentStory.items.ifEmpty { items }
    val allStoryItems = remember(storyGroups, items) {
        if (storyGroups.isNotEmpty()) storyGroups.flatMap { it.items }.ifEmpty { items } else items
    }

    // Horizontal pager for media browsing
    val initialIndex = remember(items, currentStoryItems, isStoryMode, item.uri) {
        val list = if (isStoryMode) currentStoryItems else items
        val idx = list.indexOfFirst { it.uri == item.uri }
        if (idx >= 0) idx else 0
    }
    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { if (isStoryMode) currentStoryItems.size else items.size }
    )

    var storyProgress by remember { mutableFloatStateOf(0f) }
    var isStoryHolding by remember { mutableStateOf(false) }
    var showEndOfStory by remember { mutableStateOf(false) }

    // When moving between story groups, reset pager position and notify neighbor
    LaunchedEffect(activeStoryIndex) {
        if (isStoryMode) {
            storyProgress = 0f
            if (pagerState.currentPage != 0) {
                pagerState.scrollToPage(0)
            }
            val first = currentStoryItems.firstOrNull()
            if (first != null && first.uri != item.uri) {
                onOpenNeighbor(first)
                ThumbnailBoundsTracker.activeViewerUri = first.uri
            }
        }
    }

    // Synchronize pager page settlement with parent selected media only when page has completely settled
    LaunchedEffect(pagerState, currentStoryItems) {
        snapshotFlow { pagerState.settledPage }.collect { settledPage ->
            storyProgress = 0f
            val activeList = if (isStoryMode) currentStoryItems else items
            val current = activeList.getOrNull(settledPage)
            if (current != null && current.uri != item.uri) {
                onOpenNeighbor(current)
                ThumbnailBoundsTracker.activeViewerUri = current.uri
            }
        }
    }

    val stepNext: () -> Unit = {
        if (!showEndOfStory && !isClosing && !pagerState.isScrollInProgress) {
            val activeList = if (isStoryMode) currentStoryItems else items
            if (pagerState.currentPage < activeList.size - 1) {
                val targetPage = pagerState.currentPage + 1
                storyProgress = 0f
                coroutineScope.launch {
                    pagerState.animateScrollToPage(
                        page = targetPage,
                        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
                    )
                }
            } else {
                // Reached last photo of current story. Check if more stories exist in sequence
                if (isStoryMode && activeStoryIndex < storyGroups.size - 1) {
                    activeStoryIndex++
                } else {
                    // All memories have finished playing
                    showEndOfStory = true
                }
            }
        }
    }

    val stepPrevious: () -> Unit = {
        if (!showEndOfStory && !isClosing && !pagerState.isScrollInProgress) {
            if (pagerState.currentPage > 0) {
                val targetPage = pagerState.currentPage - 1
                storyProgress = 0f
                coroutineScope.launch {
                    pagerState.animateScrollToPage(
                        page = targetPage,
                        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
                    )
                }
            } else {
                // At first photo of current story: return to previous story's last photo if available
                if (isStoryMode && activeStoryIndex > 0) {
                    val prevStoryIndex = activeStoryIndex - 1
                    val prevStoryItems = storyGroups[prevStoryIndex].items
                    activeStoryIndex = prevStoryIndex
                    storyProgress = 0f
                    coroutineScope.launch {
                        pagerState.scrollToPage((prevStoryItems.size - 1).coerceAtLeast(0))
                    }
                }
            }
        }
    }

    // Auto-advance story timer (~4.5s per photo, synchronized with video playback for videos)
    if (isStoryMode) {
        val currentMediaList = currentStoryItems
        val currentActiveItem = currentMediaList.getOrNull(pagerState.currentPage) ?: item
        val isCurrentItemVideo = currentActiveItem.isVideo
        val isPaused = isStoryHolding || showDetails || showMore || confirmMoveToBin || confirmPermanentDelete || isCurrentZoomed || isClosing || showEndOfStory || (isCurrentItemVideo && !videoState.isPlaying)

        LaunchedEffect(
            pagerState.settledPage,
            activeStoryIndex,
            isPaused,
            isCurrentItemVideo,
            videoState.durationMs,
            videoState.positionMs,
            videoState.isPlaying
        ) {
            if (showEndOfStory || isClosing || pagerState.isScrollInProgress) return@LaunchedEffect

            if (isCurrentItemVideo) {
                if (videoState.durationMs > 0L) {
                    storyProgress = (videoState.positionMs.toFloat() / videoState.durationMs.toFloat()).coerceIn(0f, 1f)
                    if (videoState.positionMs >= videoState.durationMs - 150L && videoState.durationMs > 500L) {
                        stepNext()
                    }
                }
            } else {
                if (!isPaused) {
                    val totalDurationMs = 4500f
                    val startProgress = storyProgress
                    val startTime = System.currentTimeMillis()
                    while (!isPaused && storyProgress < 1f) {
                        val elapsed = System.currentTimeMillis() - startTime
                        val newProgress = (startProgress + elapsed / totalDurationMs).coerceIn(0f, 1f)
                        storyProgress = newProgress
                        if (newProgress >= 1f) {
                            stepNext()
                            break
                        }
                        delay(16L)
                    }
                }
            }
        }
    }

    // Scroll to target page if parent changes selected media (non-story mode only)
    LaunchedEffect(item.uri) {
        if (!isStoryMode && !pagerState.isScrollInProgress) {
            val targetIndex = items.indexOfFirst { it.uri == item.uri }
            if (targetIndex >= 0 && targetIndex != pagerState.currentPage) {
                pagerState.scrollToPage(targetIndex)
            }
        }
    }

    val shareMedia: () -> Unit = {
        val current = (if (isStoryMode) currentStoryItems else items).getOrNull(pagerState.currentPage) ?: item
        shareMediaItem(context, current)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenWidthPx = constraints.maxWidth.toFloat()
        val screenHeightPx = constraints.maxHeight.toFloat()

        val ReturnSpringSpec = remember {
            spring<Float>(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = 650f
            )
        }

        // Shared return animation close initiator
        val initiateClose: () -> Unit = {
            if (!isClosing) {
                isClosing = true
                coroutineScope.launch {
                    if (showDetails) {
                        onToggleDetails()
                    }
                    ThumbnailBoundsTracker.activeViewerUri = item.uri
                    transitionProgress.animateTo(
                        targetValue = 0f,
                        animationSpec = ReturnSpringSpec
                    )
                    ThumbnailBoundsTracker.activeViewerUri = null
                    onBack()
                }
            }
        }

        // --- Android Back Handling Priority ---
        // Priority 1: Video fullscreen mode exits first
        BackHandler(enabled = videoState.isFullscreen) {
            videoState.isFullscreen = false
        }

        // Priority 2: Only when details sheet is NOT showing does Predictive Back exit the viewer
        PredictiveBackHandler(enabled = !videoState.isFullscreen && !showDetails && !isClosing) { progressFlow: Flow<BackEventCompat> ->
            try {
                ThumbnailBoundsTracker.activeViewerUri = item.uri
                progressFlow.collect { backEvent ->
                    transitionProgress.snapTo((1f - backEvent.progress).coerceIn(0f, 1f))
                }
                isClosing = true
                transitionProgress.animateTo(
                    targetValue = 0f,
                    animationSpec = ReturnSpringSpec
                )
                ThumbnailBoundsTracker.activeViewerUri = null
                onBack()
            } catch (e: CancellationException) {
                // Return transition was cancelled or interrupted
                transitionProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = ReturnSpringSpec
                )
            }
        }

        // Standard Back when details sheet is NOT showing
        BackHandler(enabled = !videoState.isFullscreen && !showDetails && !isClosing) {
            initiateClose()
        }

        val targetBounds = ThumbnailBoundsTracker.getBounds(item.uri)
        val bgThemeColor = MaterialTheme.colorScheme.background

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    if (videoState.isFullscreen) {
                        drawRect(Color.Black)
                    } else {
                        val p = transitionProgress.value
                        val currentDismissY = dismissOffsetY.value
                        val dismissFraction = (abs(currentDismissY) / 540f).coerceIn(0f, 1f)
                        val backdropAlpha = (1f - dismissFraction * 0.70f).coerceIn(0.30f, 1f)
                        val scrimAlpha = (p * backdropAlpha).coerceIn(0f, 1f)
                        drawRect(bgThemeColor.copy(alpha = scrimAlpha))
                    }
                }
        ) {
            // Horizontal Media Pager with coordinated shared-element geometry
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                flingBehavior = PagerDefaults.flingBehavior(state = pagerState),
                userScrollEnabled = !isCurrentZoomed && !videoState.isFullscreen && !showDetails && !isClosing && transitionProgress.value >= 0.99f,
                pageSpacing = 16.dp,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(if (showDetails) 16.dp else 0.dp),
                key = { pageIndex ->
                    val activeList = if (isStoryMode) currentStoryItems else items
                    val uri = activeList.getOrNull(pageIndex)?.uri ?: pageIndex.toString()
                    if (isStoryMode) "${activeStoryIndex}_$uri" else uri
                }
            ) { pageIndex ->
                val activeList = if (isStoryMode) currentStoryItems else items
                val currentItem = activeList.getOrNull(pageIndex) ?: item
                val isCurrent = pageIndex == pagerState.currentPage
                val currentBounds = if (isCurrent) (targetBounds ?: ThumbnailBoundsTracker.getBounds(currentItem.uri)) else null

                ZoomableMediaItem(
                    item = currentItem,
                    isCurrentPage = isCurrent,
                    isVideo = currentItem.isVideo,
                    videoState = if (currentItem.isVideo && currentItem.uri == item.uri) videoState else null,
                    touchSlop = viewConfig.scaledTouchSlop.toFloat(),
                    minFlingVelocity = minFlingVelocity,
                    onToggleControls = {
                        if (!showDetails) onToggleControls()
                    },
                    onLongPress = {
                        if (!showDetails && !videoState.isFullscreen) showMore = true
                    },
                    onSwipeUp = {
                        if (!showDetails && !videoState.isFullscreen) {
                            onToggleDetails()
                        }
                    },
                    onSwipeDownDelta = { deltaDown ->
                        // Downward gesture on media is strictly for media closing when details are closed
                        if (!isCurrentZoomed && !showDetails && !videoState.isFullscreen && !isClosing) {
                            coroutineScope.launch {
                                val next = (dismissOffsetY.value + deltaDown).coerceAtLeast(0f)
                                dismissOffsetY.snapTo(next)
                                val p = (1f - (next / (screenHeightPx * 0.45f)).coerceIn(0f, 0.40f))
                                transitionProgress.snapTo(p)
                            }
                        }
                    },
                    onSwipeDownRelease = { velocityDown ->
                        // Media closer cannot activate while details are open
                        if (!isCurrentZoomed && !showDetails && !videoState.isFullscreen && !isClosing) {
                            val dismissDistanceThreshold = screenHeightPx * 0.12f
                            if (dismissOffsetY.value > dismissDistanceThreshold || velocityDown > minFlingVelocity * 1.2f) {
                                coroutineScope.launch {
                                    isClosing = true
                                    ThumbnailBoundsTracker.activeViewerUri = item.uri
                                    // The preparation drag has ended and the user committed the stroke.
                                    // Do NOT animate dismissOffsetY back to 0 with a spring, which creates an
                                    // opposing upward bounce that inhibits the closing stroke.
                                    // Instead, allow transitionProgress to animate to 0f with the stroke velocity,
                                    // seamlessly guiding the media directly into the target thumbnail.
                                    val progressVelocity = if (screenHeightPx > 0f) {
                                        -(velocityDown / (screenHeightPx * 0.45f)).coerceIn(0f, 15f)
                                    } else 0f
                                    transitionProgress.animateTo(
                                        targetValue = 0f,
                                        initialVelocity = progressVelocity,
                                        animationSpec = ReturnSpringSpec
                                    )
                                    ThumbnailBoundsTracker.activeViewerUri = null
                                    onBack()
                                }
                            } else {
                                coroutineScope.launch {
                                    launch {
                                        dismissOffsetY.animateTo(
                                            targetValue = 0f,
                                            initialVelocity = velocityDown,
                                            animationSpec = ReturnSpringSpec
                                        )
                                    }
                                    transitionProgress.animateTo(
                                        targetValue = 1f,
                                        animationSpec = ReturnSpringSpec
                                    )
                                }
                            }
                        }
                    },
                    onZoomChanged = { zoomed ->
                        if (isCurrent) isCurrentZoomed = zoomed
                    },
                    isStoryMode = isStoryMode,
                    onStoryTapLeft = stepPrevious,
                    onStoryTapRight = stepNext,
                    onStoryHoldingChanged = { isStoryHolding = it },
                    transitionProgress = transitionProgress.value,
                    targetBounds = currentBounds,
                    dismissOffsetY = dismissOffsetY.value
                )
            }

            // Edge gradient shading behind top and bottom bars (hidden in fullscreen or when sheet rises)
            if (!videoState.isFullscreen) {
                ViewerEdgeShading(visible = ((showControls && !isStoryMode) || (isStoryMode && !isStoryHolding && !showEndOfStory)) && !isClosing)
            }

            // Top App Bar in standard or story view
            if (!videoState.isFullscreen) {
                if (isStoryMode) {
                    val showStoryBar = !isStoryHolding && !isClosing && !showEndOfStory
                    AnimatedVisibility(
                        visible = showStoryBar,
                        enter = fadeIn(animationSpec = tween(150)),
                        exit = fadeOut(animationSpec = tween(150)),
                        modifier = Modifier.align(Alignment.TopCenter)
                    ) {
                        ViewerStoryTopBar(
                            storyTitle = currentStoryTitle,
                            currentIndex = pagerState.currentPage,
                            itemCount = currentStoryItems.size,
                            currentProgress = storyProgress,
                            isFavourite = favourite,
                            onBack = { initiateClose() },
                            onShare = shareMedia,
                            onToggleFavourite = onFavourite,
                            onMore = { showMore = true },
                            modifier = Modifier.graphicsLayer {
                                val p = transitionProgress.value
                                translationY = -(80.dp.toPx()) * (1f - p)
                                alpha = if (isClosing) ((p - 0.35f) / 0.65f).coerceIn(0f, 1f) else 1f
                            }
                        )
                    }
                } else if (showControls) {
                    ViewerTopAppBar(
                        isFavourite = favourite,
                        onBack = { initiateClose() },
                        onShare = shareMedia,
                        onToggleFavourite = onFavourite,
                        onMore = { showMore = true },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .graphicsLayer {
                                val p = transitionProgress.value
                                translationY = -(80.dp.toPx()) * (1f - p)
                                alpha = if (isClosing) ((p - 0.35f) / 0.65f).coerceIn(0f, 1f) else 1f
                            }
                    )
                }
            }

            // Fullscreen quick exit back button
            if (videoState.isFullscreen && showControls) {
                IconButton(
                    onClick = { videoState.isFullscreen = false },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(16.dp)
                        .size(44.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Exit fullscreen",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Coordinated bottom controls cluster: Video Player Controls Bar stacked cleanly above ViewerBottomActionBar.
            if (showControls) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .graphicsLayer {
                            val p = transitionProgress.value
                            translationY = (80.dp.toPx()) * (1f - p)
                            alpha = if (isClosing) ((p - 0.35f) / 0.65f).coerceIn(0f, 1f) else 1f
                        },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (item.isVideo) {
                        VideoPlayerControlsBar(
                            videoState = videoState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = if (videoState.isFullscreen) 20.dp else 4.dp)
                        )
                    }

                    if (!videoState.isFullscreen && !isStoryMode) {
                        ViewerBottomActionBar(
                            isBin = isBin,
                            isHidden = isHidden,
                            isArchived = isArchived,
                            onToggleBin = {
                                if (isBin) {
                                    onToggleBin()
                                } else {
                                    confirmMoveToBin = true
                                }
                            },
                            onToggleHide = onToggleHide,
                            onToggleArchive = onToggleArchive,
                            onMove = onAddTo,
                            onDeletePermanently = if (isBin && onDeletePermanently != null) { { confirmPermanentDelete = true } } else null
                        )
                    }
                }
            }

            // Native Media Details Drawer (Modal Bottom Sheet)
            if (showDetails && !videoState.isFullscreen && !isClosing) {
                ViewerDetailsDrawer(
                    item = item,
                    items = items,
                    customAlbums = customAlbums,
                    customTags = customTags,
                    onClose = {
                        if (showDetails) onToggleDetails()
                    },
                    onShare = shareMedia,
                    onAddTo = onAddTo,
                    onToggleBin = {
                        if (isBin) {
                            onToggleBin()
                        } else {
                            confirmMoveToBin = true
                        }
                    },
                    onShowMore = { showMore = true },
                    onOpenMap = onOpenMap,
                    onAddTag = onAddTag,
                    isBin = isBin,
                    defaultOcrLanguages = defaultOcrLanguages,
                    defaultOcrModel = defaultOcrModel,
                    defaultMlKitLanguages = defaultMlKitLanguages,
                    onOpenPerson = onOpenPerson
                )
            }

            // End-of-memory constellation screen
            if (isStoryMode && showEndOfStory && !isClosing) {
                EndOfStoryOverlay(
                    storyItems = allStoryItems,
                    storyTitle = currentStoryTitle,
                    itemCount = allStoryItems.size,
                    onReplay = {
                        showEndOfStory = false
                        activeStoryIndex = 0
                        storyProgress = 0f
                        coroutineScope.launch {
                            pagerState.scrollToPage(0)
                        }
                    },
                    onClose = { initiateClose() }
                )
            }
        }
    }

    // "More" options modal bottom sheet
    if (showMore) {
        ViewerMoreBottomSheet(
            isBin = isBin,
            isHidden = isHidden,
            isArchived = isArchived,
            onToggleBin = {
                if (isBin) {
                    onToggleBin()
                } else {
                    confirmMoveToBin = true
                }
            },
            onToggleHide = onToggleHide,
            onToggleArchive = onToggleArchive,
            onMove = onAddTo,
            onDeletePermanently = if (isBin && onDeletePermanently != null) { { confirmPermanentDelete = true } } else null,
            onShowDetails = { if (!showDetails) onToggleDetails() },
            onDismiss = { showMore = false }
        )
    }

    // Confirmation dialog for moving to Bin
    if (confirmMoveToBin) {
        AlertDialog(
            onDismissRequest = { confirmMoveToBin = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface) },
            title = { Text("Move to Bin?") },
            text = { Text("Move \"${item.name}\" to the device Bin? You can restore it later from the Bin.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmMoveToBin = false
                        onToggleBin()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface,
                        contentColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Text("Move to Bin")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmMoveToBin = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    // Confirmation dialog for permanent delete
    if (confirmPermanentDelete) {
        AlertDialog(
            onDismissRequest = { confirmPermanentDelete = false },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface) },
            title = { Text("Delete permanently?") },
            text = { Text("Permanently delete \"${item.name}\" from this device? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmPermanentDelete = false
                        onDeletePermanently?.invoke()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface,
                        contentColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmPermanentDelete = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}

/**
 * Individual media page supporting pinch-to-zoom, pan while zoomed,
 * double-tap to zoom in/reset, and vertical upward/downward gestures when unzoomed.
 */
@Composable
private fun ZoomableMediaItem(
    item: MediaItem,
    isCurrentPage: Boolean,
    isVideo: Boolean,
    videoState: ViewerVideoState?,
    touchSlop: Float,
    minFlingVelocity: Float,
    onToggleControls: () -> Unit,
    onLongPress: () -> Unit,
    onSwipeUp: () -> Unit,
    onSwipeDownDelta: (Float) -> Unit,
    onSwipeDownRelease: (Float) -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    isStoryMode: Boolean = false,
    onStoryTapLeft: () -> Unit = {},
    onStoryTapRight: () -> Unit = {},
    onStoryHoldingChanged: (Boolean) -> Unit = {},
    transitionProgress: Float = 1f,
    targetBounds: androidx.compose.ui.geometry.Rect? = null,
    dismissOffsetY: Float = 0f,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    var scale by remember(item.uri) { mutableFloatStateOf(1f) }
    var offsetX by remember(item.uri) { mutableFloatStateOf(0f) }
    var offsetY by remember(item.uri) { mutableFloatStateOf(0f) }

    var intrinsicWidth by remember(item.uri) { mutableIntStateOf(item.width) }
    var intrinsicHeight by remember(item.uri) { mutableIntStateOf(item.height) }

    // Keep video posters on the same cached MediaStore thumbnail used by the grid.
    // Decoding an independent video frame here can make the poster change before ExoPlayer's first frame.
    val context = LocalContext.current
    val imageRequest = remember(item.uri, isVideo, context) {
        ThumbnailUtils.buildViewerRequest(context, item.uri, isVideo)
    }

    // Reset zoom when navigating away from this page
    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) {
            scale = 1f
            offsetX = 0f
            offsetY = 0f
            onZoomChanged(false)
        }
    }

    LaunchedEffect(scale) {
        onZoomChanged(scale > 1.05f)
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val containerWidth = constraints.maxWidth.toFloat()
        val containerHeight = constraints.maxHeight.toFloat()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(item.uri, isVideo) {
                    val width = containerWidth
                    val height = containerHeight

                    var lastTapTime = 0L
                    var lastTapPos = Offset.Zero

                    coroutineScope {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val velocityTracker = VelocityTracker()
                            velocityTracker.addPosition(down.uptimeMillis, down.position)

                            var isPinching = false
                            var isDragging = false
                            var isVerticalSwipe = false
                            var isHorizontalBrowse = false
                            var swipeDirection = 0 // 1 = down (dismiss), -1 = up (drawer)
                            var totalMovementX = 0f
                            var totalMovementY = 0f
                            var longPressTriggered = false

                            val longPressJob = launch {
                                delay(if (isStoryMode) 180L else 500L)
                                if (!isDragging && !isPinching && scale <= 1.02f) {
                                    if (isStoryMode) {
                                        onStoryHoldingChanged(true)
                                    } else {
                                        longPressTriggered = true
                                        onLongPress()
                                    }
                                }
                            }

                            while (true) {
                                val event = awaitPointerEvent()
                                val pointerCount = event.changes.size

                                if (pointerCount >= 2 && !isVideo) {
                                    isPinching = true
                                    longPressJob.cancel()

                                    val zoomChange = event.calculateZoom()
                                    val panChange = event.calculatePan()
                                    val centroid = event.calculateCentroid()

                                    val oldScale = scale
                                    val newScale = (oldScale * zoomChange).coerceIn(1f, 4.5f)

                                    if (oldScale > 0f) {
                                        val scaleRatio = newScale / oldScale
                                        val focalX = centroid.x - width / 2f
                                        val focalY = centroid.y - height / 2f
                                        val newOffsetX = (offsetX - focalX) * scaleRatio + focalX + panChange.x
                                        val newOffsetY = (offsetY - focalY) * scaleRatio + focalY + panChange.y

                                        val maxX = (width * (newScale - 1f)) / 2f
                                        val maxY = (height * (newScale - 1f)) / 2f

                                        scale = newScale
                                        offsetX = newOffsetX.coerceIn(-maxX, maxX)
                                        offsetY = newOffsetY.coerceIn(-maxY, maxY)
                                    }

                                    event.changes.forEach { it.consume() }
                                } else if (pointerCount == 1) {
                                    val change = event.changes.first()
                                    if (!change.pressed) break

                                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                                    val posChange = change.positionChange()
                                    totalMovementX += abs(posChange.x)
                                    totalMovementY += abs(posChange.y)

                                    if (totalMovementX > touchSlop || totalMovementY > touchSlop) {
                                        longPressJob.cancel()
                                        if (isStoryMode) onStoryHoldingChanged(false)
                                    }

                                    if (scale > 1.02f && !isVideo) {
                                        // Zero-Tolerance Zoom Lock:
                                        // If underlying media is zoomed in beyond 1.0x scale, disable all sheet gestures.
                                        // All drag touches must strictly belong to media panning.
                                        val maxX = (width * (scale - 1f)) / 2f
                                        val maxY = (height * (scale - 1f)) / 2f

                                        offsetX = (offsetX + posChange.x).coerceIn(-maxX, maxX)
                                        offsetY = (offsetY + posChange.y).coerceIn(-maxY, maxY)
                                        change.consume()
                                    } else {
                                        // Directional Slop Disambiguation:
                                        // If |dx| > |dy|, yield control to horizontal pager browsing.
                                        // Lock into vertical sheet manipulation only when |dy| > |dx|.
                                        if (!isVerticalSwipe && !isHorizontalBrowse) {
                                            val dist = kotlin.math.hypot(totalMovementX, totalMovementY)
                                            if (dist > touchSlop) {
                                                if (totalMovementX >= totalMovementY) {
                                                    isHorizontalBrowse = true
                                                } else {
                                                    isVerticalSwipe = true
                                                    swipeDirection = if (posChange.y < 0) -1 else 1
                                                }
                                            }
                                        }

                                        if (isVerticalSwipe) {
                                            change.consume()
                                            onSwipeDownDelta(posChange.y)
                                        }
                                    }
                                } else {
                                    break
                                }
                            }

                            longPressJob.cancel()
                            if (isStoryMode) onStoryHoldingChanged(false)

                            val velocityY = velocityTracker.calculateVelocity().y
                            if (isVerticalSwipe) {
                                if (swipeDirection < 0) {
                                    if (velocityY < -minFlingVelocity || totalMovementY > touchSlop * 2f) {
                                        onSwipeUp()
                                    }
                                } else {
                                    onSwipeDownRelease(velocityY)
                                }
                            } else if (!isPinching && !longPressTriggered && totalMovementX < touchSlop && totalMovementY < touchSlop) {
                                if (isStoryMode) {
                                    if (down.position.x < width * 0.30f) {
                                        onStoryTapLeft()
                                    } else {
                                        onStoryTapRight()
                                    }
                                } else {
                                    val now = System.currentTimeMillis()
                                    if (now - lastTapTime < 280L && (down.position - lastTapPos).getDistance() < touchSlop * 3f && !isVideo) {
                                        lastTapTime = 0L
                                        if (scale > 1.2f) {
                                            launch {
                                                val startScale = scale
                                                val startX = offsetX
                                                val startY = offsetY
                                                animate(0f, 1f, animationSpec = tween(200)) { fraction, _ ->
                                                    scale = startScale + (1f - startScale) * fraction
                                                    offsetX = startX * (1f - fraction)
                                                    offsetY = startY * (1f - fraction)
                                                }
                                            }
                                        } else {
                                            launch {
                                                val targetScale = 2.5f
                                                val focalX = down.position.x - width / 2f
                                                val focalY = down.position.y - height / 2f
                                                val maxX = (width * (targetScale - 1f)) / 2f
                                                val maxY = (height * (targetScale - 1f)) / 2f
                                                val targetOffsetX = (-focalX * (targetScale - 1f)).coerceIn(-maxX, maxX)
                                                val targetOffsetY = (-focalY * (targetScale - 1f)).coerceIn(-maxY, maxY)

                                                val startScale = scale
                                                val startX = offsetX
                                                val startY = offsetY
                                                animate(0f, 1f, animationSpec = tween(220)) { fraction, _ ->
                                                    scale = startScale + (targetScale - startScale) * fraction
                                                    offsetX = startX + (targetOffsetX - startX) * fraction
                                                    offsetY = startY + (targetOffsetY - startY) * fraction
                                                }
                                            }
                                        }
                                    } else {
                                        lastTapTime = now
                                        lastTapPos = down.position
                                        launch {
                                            delay(280L)
                                            if (lastTapTime == now) {
                                                onToggleControls()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            val imgW = intrinsicWidth.toFloat()
            val imgH = intrinsicHeight.toFloat()
            val imageAspect = if (imgW > 0f && imgH > 0f) {
                imgW / imgH
            } else if (targetBounds != null && targetBounds.width > 0f && targetBounds.height > 0f) {
                targetBounds.width / targetBounds.height
            } else if (containerHeight > 0f) {
                containerWidth / containerHeight
            } else {
                1f
            }
            val containerAspect = if (containerHeight > 0f) containerWidth / containerHeight else 1f

            val fittedW: Float
            val fittedH: Float
            if (containerWidth > 0f && containerHeight > 0f) {
                if (imageAspect >= containerAspect) {
                    fittedW = containerWidth
                    fittedH = if (imageAspect > 0f) containerWidth / imageAspect else containerHeight
                } else {
                    fittedH = containerHeight
                    fittedW = containerHeight * imageAspect
                }
            } else {
                fittedW = 0f
                fittedH = 0f
            }

            val fittedWidthDp = if (fittedW > 0f) with(density) { fittedW.toDp() } else 0.dp
            val fittedHeightDp = if (fittedH > 0f) with(density) { fittedH.toDp() } else 0.dp

            // Outer container for user pinch-to-zoom and pan gestures
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    },
                contentAlignment = Alignment.Center
            ) {
                val fittedModifier = if (fittedW > 0f && fittedH > 0f) {
                    Modifier.size(fittedWidthDp, fittedHeightDp)
                } else {
                    Modifier.fillMaxSize()
                }

                // Inner fitted container executing uniform shared-element scale, position, and crop to thumbnail
                Box(
                    modifier = fittedModifier
                        .graphicsLayer {
                            val p = transitionProgress.coerceIn(0f, 1f)
                            val bounds = if (isCurrentPage) targetBounds else null

                            if (bounds != null && fittedW > 0f && fittedH > 0f && containerWidth > 0f && containerHeight > 0f) {
                                val thumbW = bounds.width
                                val thumbH = bounds.height
                                val thumbCenterX = bounds.left + thumbW / 2f
                                val thumbCenterY = bounds.top + thumbH / 2f
                                val screenCenterX = containerWidth / 2f
                                val screenCenterY = containerHeight / 2f

                                // Calculate uniform scale to cover the target thumbnail without aspect ratio distortion
                                val sx = thumbW / fittedW
                                val sy = thumbH / fittedH
                                val targetScale = maxOf(sx, sy).coerceIn(0.001f, 10f)

                                val currentScale = targetScale + (1f - targetScale) * p

                                // Translate center to target thumbnail center
                                val destTransX = thumbCenterX - screenCenterX
                                val destTransY = thumbCenterY - screenCenterY
                                val currentTransX = destTransX * (1f - p)
                                val currentTransY = destTransY * (1f - p) + dismissOffsetY * p

                                // Physical clip window on screen interpolating from fitted size down to thumbnail bounds
                                val currentPhysClipW = thumbW + (fittedW - thumbW) * p
                                val currentPhysClipH = thumbH + (fittedH - thumbH) * p

                                // Local unscaled clip window within this Box
                                val localClipW = (currentPhysClipW / currentScale).coerceIn(1f, fittedW)
                                val localClipH = (currentPhysClipH / currentScale).coerceIn(1f, fittedH)

                                // Local corner radius scaling to exactly 10.dp on screen at p = 0
                                val targetLocalCornerRadiusPx = 10.dp.toPx() / targetScale
                                val currentLocalCornerRadiusPx = targetLocalCornerRadiusPx * (1f - p)

                                val clipLeft = (fittedW - localClipW) / 2f
                                val clipTop = (fittedH - localClipH) / 2f
                                val clipRect = androidx.compose.ui.geometry.Rect(
                                    left = clipLeft,
                                    top = clipTop,
                                    right = clipLeft + localClipW,
                                    bottom = clipTop + localClipH
                                )

                                translationX = currentTransX
                                translationY = currentTransY
                                scaleX = currentScale
                                scaleY = currentScale

                                clip = p < 0.999f
                                shape = object : androidx.compose.ui.graphics.Shape {
                                    override fun createOutline(
                                        size: androidx.compose.ui.geometry.Size,
                                        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                        density: androidx.compose.ui.unit.Density
                                    ): androidx.compose.ui.graphics.Outline {
                                        return androidx.compose.ui.graphics.Outline.Rounded(
                                            androidx.compose.ui.geometry.RoundRect(
                                                rect = clipRect,
                                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(currentLocalCornerRadiusPx)
                                            )
                                        )
                                    }
                                }
                            } else {
                                val currentScale = 0.85f + 0.15f * p
                                val exitY = if (dismissOffsetY > 0f) {
                                    dismissOffsetY + (1f - p) * (containerHeight * 0.35f)
                                } else {
                                    0f
                                }
                                translationX = 0f
                                translationY = exitY
                                scaleX = currentScale
                                scaleY = currentScale
                                alpha = if (isCurrentPage) p.coerceIn(0f, 1f) else if (p < 0.99f) 0f else 1f
                                clip = p < 0.999f
                                shape = RoundedCornerShape(((1f - p) * 16f).dp)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = item.name,
                        onSuccess = { state ->
                            val d = state.result.drawable
                            val w = if (d.intrinsicWidth > 0) d.intrinsicWidth.toFloat() else state.painter.intrinsicSize.width
                            val h = if (d.intrinsicHeight > 0) d.intrinsicHeight.toFloat() else state.painter.intrinsicSize.height
                            if (w > 0f && h > 0f && !w.isNaN() && !h.isNaN()) {
                                intrinsicWidth = w.roundToInt()
                                intrinsicHeight = h.roundToInt()
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )

                    // Video Surface when current item is a video
                    if (isVideo && videoState != null) {
                        VideoPlayerSurface(videoState = videoState)
                    }
                }
            }
        }
    }
}

/**
 * Continuously interpolates image scale factors from cover/crop at the thumbnail end
 * of the shared-element transition to contain/fit at the fullscreen end.
 */
internal fun transitionContentScale(progress: Float): ContentScale {
    val fraction = progress.coerceIn(0f, 1f)
    return object : ContentScale {
        override fun computeScaleFactor(
            srcSize: androidx.compose.ui.geometry.Size,
            dstSize: androidx.compose.ui.geometry.Size
        ): ScaleFactor {
            val crop = ContentScale.Crop.computeScaleFactor(srcSize, dstSize)
            val fit = ContentScale.Fit.computeScaleFactor(srcSize, dstSize)
            return ScaleFactor(
                scaleX = crop.scaleX + (fit.scaleX - crop.scaleX) * fraction,
                scaleY = crop.scaleY + (fit.scaleY - crop.scaleY) * fraction
            )
        }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private fun shareMediaItem(context: Context, item: MediaItem) {
    runCatching {
        val parsedUri = Uri.parse(item.uri)
        val mimeType = item.mimeType ?: if (item.isVideo) "video/*" else "image/*"
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, parsedUri)
            clipData = ClipData.newUri(context.contentResolver, item.name, parsedUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, "Share media").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(chooser)
    }
}
