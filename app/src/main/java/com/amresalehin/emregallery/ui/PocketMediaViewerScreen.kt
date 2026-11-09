@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.amresalehin.emregallery.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.ViewConfiguration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import android.media.MediaScannerConnection
import androidx.compose.material3.*
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.amresalehin.emregallery.data.GalleryDlEngine
import com.amresalehin.emregallery.data.OkHttpDownloader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.amresalehin.emregallery.data.DownloadQueueManager
import com.amresalehin.emregallery.data.MediaCategory
import com.amresalehin.emregallery.data.MediaInboxItem
import com.amresalehin.emregallery.data.MediaInboxStack
import com.amresalehin.emregallery.data.ScrapedMediaItem
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

private val ReturnSpringSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 500f
)

/**
 * Fullscreen media viewer for Bookmark media items conforming strictly to the Native Gallery
 * media viewer canvas styling, UI, UX, swipe down to dismiss, horizontal swipe, and scrim backdrop.
 *
 * Floating buttons are customized for bookmarks:
 * 1. Redirect Button (Opens source URL)
 * 2. Copy Button (Copies media link to clipboard)
 * 3. Save Button (Downloads media directly to device)
 */
@Composable
fun PocketMediaViewerScreen(
    items: List<MediaInboxItem>,
    initialIndex: Int,
    stack: MediaInboxStack,
    onDismiss: () -> Unit,
    onOpenBrowser: (String) -> Unit,
    isDark: Boolean = isSystemInDarkTheme()
) {
    if (items.isEmpty()) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("pocket_media_viewer_screen")
    ) {
        PocketMediaViewerContent(
            items = items,
            initialIndex = initialIndex.coerceIn(0, items.size - 1),
            stack = stack,
            onDismiss = onDismiss,
            onOpenBrowser = onOpenBrowser,
            isDark = isDark
        )
    }
}

@Composable
private fun PocketMediaViewerContent(
    items: List<MediaInboxItem>,
    initialIndex: Int,
    stack: MediaInboxStack,
    onDismiss: () -> Unit,
    onOpenBrowser: (String) -> Unit,
    isDark: Boolean = isSystemInDarkTheme()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val viewConfig = remember(context) { ViewConfiguration.get(context) }
    val minFlingVelocity = remember(viewConfig) { viewConfig.scaledMinimumFlingVelocity.toFloat() }

    // Dynamic theme colors matching Light/Dark Mode
    val baseBgColor = if (isDark) Color.Black else Color(0xFFFAFAFA)
    val scrimTintColor = if (isDark) Color.Black else Color.White
    val scrimTintAlpha = if (isDark) 0.50f else 0.35f
    val btnBgColor = if (isDark) Color.Black.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.90f)
    val btnBorderColor = if (isDark) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.14f)
    val btnIconColor = if (isDark) Color.White else Color(0xFF141414)
    val titleTextColor = if (isDark) Color.White else Color(0xFF141414)
    val subtitleTextColor = if (isDark) Color.White.copy(alpha = 0.75f) else Color(0xFF6E6E6E)
    val rippleColor = if (isDark) Color.White else Color.Black

    val activity = remember(context) { context.findActivity() }

    // Real full-screen edge-to-edge window configuration:
    // Ensures the media and container cleanly underlay the status bar without any black dead zone or letterboxing.
    DisposableEffect(activity, isDark) {
        val window = activity?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            // Ensure status bar is shown and not blocking screen area
            controller.show(WindowInsetsCompat.Type.statusBars())
            controller.isAppearanceLightStatusBars = !isDark
            controller.isAppearanceLightNavigationBars = !isDark
        }
        onDispose {
            val window = activity?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !isDark
                controller.isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    val transitionProgress = remember { Animatable(1f) }
    val dismissOffsetY = remember { Animatable(0f) }
    var isClosing by remember { mutableStateOf(false) }

    // Toggle overlay controls on tap
    var showControls by remember { mutableStateOf(true) }

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, items.size - 1),
        pageCount = { items.size }
    )

    val currentItem = items.getOrElse(pagerState.currentPage) { items[0] }
    val currentMediaPath = remember(currentItem) {
        if (currentItem.localUri.isNotBlank()) {
            val f = File(currentItem.localUri.removePrefix("file://"))
            if (f.exists()) f.absolutePath else currentItem.sourceUrl
        } else currentItem.sourceUrl
    }
    val isCurrentVideo = remember(currentItem, currentMediaPath) {
        currentItem.isVideo ||
                currentMediaPath.endsWith(".mp4", ignoreCase = true) ||
                currentMediaPath.endsWith(".webm", ignoreCase = true) ||
                currentMediaPath.endsWith(".mov", ignoreCase = true) ||
                currentMediaPath.contains(".m3u8", ignoreCase = true)
    }

    var isCurrentZoomed by remember { mutableStateOf(false) }

    // Video playback management for the currently viewed media item
    val videoState = rememberViewerVideoState(
        uri = currentMediaPath,
        isVideo = isCurrentVideo,
        showControls = showControls,
        sessionPlayIntent = true
    )

    val initiateClose: () -> Unit = {
        if (!isClosing) {
            isClosing = true
            coroutineScope.launch {
                transitionProgress.animateTo(
                    targetValue = 0f,
                    animationSpec = ReturnSpringSpec
                )
                onDismiss()
            }
        }
    }

    BackHandler(enabled = !isClosing) {
        initiateClose()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenHeightPx = constraints.maxHeight.toFloat()

        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            val p = transitionProgress.value
            val currentDismissY = dismissOffsetY.value
            val dismissFraction = (abs(currentDismissY) / 540f).coerceIn(0f, 1f)
            val backdropAlpha = (1f - dismissFraction * 0.70f).coerceIn(0.20f, 1f)
            val scrimAlpha = (p * backdropAlpha).coerceIn(0f, 1f)

            // Base background adapting to dark or light mode
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(baseBgColor.copy(alpha = scrimAlpha))
            )

            // Blurred scrim area: media blurred at 24.dp fills the fullscreen background just like deck cards stack
            if (currentMediaPath.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(currentMediaPath)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(24.dp)
                        .graphicsLayer {
                            alpha = (scrimAlpha * (if (isDark) 0.85f else 0.75f)).coerceIn(0f, 1f)
                        }
                )
                // Soft translucent scrim tint for depth, focus and contrast
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(scrimTintColor.copy(alpha = (scrimAlpha * scrimTintAlpha).coerceIn(0f, 1f)))
                )
            }
            // Horizontal Media Pager with coordinated shared-canvas gestures
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                flingBehavior = PagerDefaults.flingBehavior(state = pagerState),
                userScrollEnabled = !isCurrentZoomed && !isClosing && transitionProgress.value >= 0.99f,
                pageSpacing = 16.dp,
                modifier = Modifier.fillMaxSize(),
                key = { pageIndex -> items[pageIndex].id }
            ) { pageIndex ->
                val pageItem = items.getOrNull(pageIndex) ?: currentItem
                val isCurrent = pageIndex == pagerState.currentPage

                PocketZoomableMediaPage(
                    item = pageItem,
                    isCurrentPage = isCurrent,
                    videoState = if (isCurrent && isCurrentVideo) videoState else null,
                    touchSlop = viewConfig.scaledTouchSlop.toFloat(),
                    minFlingVelocity = minFlingVelocity,
                    onToggleControls = { showControls = !showControls },
                    onSwipeDownDelta = { deltaDown ->
                        if (!isCurrentZoomed && !isClosing) {
                            coroutineScope.launch {
                                val next = (dismissOffsetY.value + deltaDown).coerceAtLeast(0f)
                                dismissOffsetY.snapTo(next)
                                val p = (1f - (next / (screenHeightPx * 0.45f)).coerceIn(0f, 0.40f))
                                transitionProgress.snapTo(p)
                            }
                        }
                    },
                    onSwipeDownRelease = { velocityDown ->
                        if (!isCurrentZoomed && !isClosing) {
                            val dismissDistanceThreshold = screenHeightPx * 0.12f
                            if (dismissOffsetY.value > dismissDistanceThreshold || velocityDown > minFlingVelocity * 1.2f) {
                                coroutineScope.launch {
                                    isClosing = true
                                    val progressVelocity = if (screenHeightPx > 0f) {
                                        -(velocityDown / (screenHeightPx * 0.45f)).coerceIn(0f, 15f)
                                    } else 0f
                                    transitionProgress.animateTo(
                                        targetValue = 0f,
                                        initialVelocity = progressVelocity,
                                        animationSpec = ReturnSpringSpec
                                    )
                                    onDismiss()
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
                    transitionProgress = transitionProgress.value,
                    dismissOffsetY = dismissOffsetY.value,
                    isDark = isDark
                )
            }

            // Top and bottom edge gradient shading (theme-aware)
            PocketViewerEdgeShading(visible = showControls && !isClosing, isDark = isDark)

            // Safe top inset for interactive buttons: container & media underlay status bar from y=0
            val statusBarTopPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val topBarTopMargin = statusBarTopPadding + 8.dp

            AnimatedVisibility(
                visible = showControls && !isClosing,
                enter = fadeIn(animationSpec = tween(180)),
                exit = fadeOut(animationSpec = tween(160)),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(topBarTopMargin + 64.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = if (isDark) listOf(
                                    Color.Black.copy(alpha = 0.72f),
                                    Color.Black.copy(alpha = 0.40f),
                                    Color.Transparent
                                ) else listOf(
                                    Color.White.copy(alpha = 0.85f),
                                    Color.White.copy(alpha = 0.50f),
                                    Color.Transparent
                                )
                            )
                        )
                )
            }

            // Top App Bar: Exactly matches Home Screen Search Bar button position (52.dp height, 16.dp horizontal, 8.dp vertical)
            AnimatedVisibility(
                visible = showControls && !isClosing,
                enter = fadeIn(animationSpec = tween(180)) + slideInVertically { -it },
                exit = fadeOut(animationSpec = tween(160)) + slideOutVertically { -it },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = topBarTopMargin, start = 16.dp, end = 16.dp, bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Back / Close Button (Circle with border, 44dp touch target matching Search Bar menu button)
                    Surface(
                        shape = CircleShape,
                        color = btnBgColor,
                        border = BorderStroke(1.dp, btnBorderColor),
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(44.dp)
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(color = rippleColor),
                                onClick = initiateClose
                            )
                            .testTag("viewer_back_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Close full screen media",
                                tint = btnIconColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    // Media Counter & Title in center
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (items.size > 1) "${pagerState.currentPage + 1} of ${items.size}" else (currentItem.title.ifBlank { stack.title.ifBlank { "Media" } }),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = titleTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (stack.sourceDomain.isNotBlank() || stack.title.isNotBlank()) {
                            Text(
                                text = stack.sourceDomain.ifBlank { stack.title },
                                style = MaterialTheme.typography.labelSmall,
                                color = subtitleTextColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    // Icon-only Action Buttons (Redirect, Copy, Save) in the same row
                    Row(
                        modifier = Modifier.padding(end = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Redirect Button (Icon-only)
                        Surface(
                            shape = CircleShape,
                            color = btnBgColor,
                            border = BorderStroke(1.dp, btnBorderColor),
                            shadowElevation = 4.dp,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(color = rippleColor),
                                    onClick = {
                                        val targetUrl = currentItem.sourceUrl.ifBlank { stack.sourceUrl }
                                        if (targetUrl.isNotBlank()) {
                                            onOpenBrowser(targetUrl)
                                        } else {
                                            Toast.makeText(context, "No URL to open", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                                .testTag("floating_redirect_button")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = "Redirect to source URL",
                                    tint = btnIconColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // 2. Copy Button (Icon-only)
                        Surface(
                            shape = CircleShape,
                            color = btnBgColor,
                            border = BorderStroke(1.dp, btnBorderColor),
                            shadowElevation = 4.dp,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(color = rippleColor),
                                    onClick = {
                                        val textToCopy = currentItem.sourceUrl.ifBlank { stack.sourceUrl }
                                        if (textToCopy.isNotBlank()) {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            val clip = ClipData.newPlainText("Media URL", textToCopy)
                                            clipboard.setPrimaryClip(clip)
                                            Toast.makeText(context, "Link copied to clipboard", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "No URL to copy", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                                .testTag("floating_copy_button")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy media URL",
                                    tint = btnIconColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // 3. Save Button (Icon-only, downloads media to device)
                        Surface(
                            shape = CircleShape,
                            color = btnBgColor,
                            border = BorderStroke(1.dp, btnBorderColor),
                            shadowElevation = 4.dp,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(color = rippleColor),
                                    onClick = {
                                        executeSaveMedia(
                                            context = context,
                                            coroutineScope = coroutineScope,
                                            item = currentItem,
                                            stack = stack
                                        )
                                    }
                                )
                                .testTag("floating_save_button")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Save media to device",
                                    tint = btnIconColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Video Player Controls Bar (only shown when current media is a video)
            if (isCurrentVideo) {
                AnimatedVisibility(
                    visible = showControls && !isClosing,
                    enter = fadeIn(animationSpec = tween(200)) + slideInVertically { it },
                    exit = fadeOut(animationSpec = tween(180)) + slideOutVertically { it },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 20.dp)
                ) {
                    VideoPlayerControlsBar(
                        videoState = videoState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

/**
 * Downloads or copies the current media item directly to the device's public Emre Gallery directory,
 * triggers MediaStore scan, and displays real-time Toast progress and confirmation.
 */
private fun executeSaveMedia(
    context: Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    item: MediaInboxItem,
    stack: MediaInboxStack
) {
    coroutineScope.launch {
        Toast.makeText(context, "Saving media to device...", Toast.LENGTH_SHORT).show()
        val targetDir = OkHttpDownloader.getTargetStorageDir(context)
        val downloadUrl = item.sourceUrl.ifBlank { item.localUri }
        val ext = when {
            item.isVideo -> "mp4"
            item.isAudio -> "mp3"
            item.format.isNotBlank() && !item.format.contains("/") -> item.format.lowercase()
            downloadUrl.contains(".mp4", ignoreCase = true) -> "mp4"
            downloadUrl.contains(".webm", ignoreCase = true) -> "webm"
            downloadUrl.contains(".png", ignoreCase = true) -> "png"
            downloadUrl.contains(".webp", ignoreCase = true) -> "webp"
            else -> "jpg"
        }
        val safeBaseName = (item.title.ifBlank { stack.title.ifBlank { "media_${System.currentTimeMillis()}" } })
            .replace(Regex("[^a-zA-Z0-9._-]"), "_").take(40)
        val fileName = if (safeBaseName.endsWith(".$ext", ignoreCase = true)) safeBaseName else "${safeBaseName}.$ext"

        // 1. If media is already stored locally on device, copy to public Emre Gallery directory directly
        val localFilePath = item.localUri.removePrefix("file://")
        val localFile = if (localFilePath.isNotBlank()) File(localFilePath) else null

        if (localFile != null && localFile.exists() && localFile.length() > 0L) {
            val destFile = File(targetDir, fileName)
            val copied = withContext(Dispatchers.IO) {
                runCatching {
                    localFile.copyTo(destFile, overwrite = true)
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(destFile.absolutePath),
                        arrayOf(if (item.isVideo) "video/*" else "image/*"),
                        null
                    )
                    true
                }.getOrDefault(false)
            }
            if (copied) {
                Toast.makeText(context, "Saved to device: ${destFile.name}", Toast.LENGTH_LONG).show()
                return@launch
            }
        }

        // 2. FFmpeg stream engine for HLS (.m3u8), DASH (.mpd), and fragmented stream manifests
        val isStreamUrl = item.isStream || downloadUrl.contains(".m3u8", ignoreCase = true) || downloadUrl.contains(".mpd", ignoreCase = true)
        if (isStreamUrl) {
            val mp4FileName = if (fileName.endsWith(".mp4", ignoreCase = true)) fileName else "${fileName.substringBeforeLast('.')}.mp4"
            val targetFile = File(targetDir, mp4FileName)
            val remuxResult = withContext(Dispatchers.IO) {
                com.amresalehin.emregallery.ffmpeg.FfmpegKitEngine.remuxStream(
                    streamUrl = downloadUrl,
                    outputFile = targetFile,
                    context = context
                )
            }
            if (remuxResult.isSuccess && targetFile.exists() && targetFile.length() > 0L) {
                Toast.makeText(context, "Saved stream to device via FFmpeg: ${targetFile.name}", Toast.LENGTH_LONG).show()
                return@launch
            }
        }

        // 3. Specialized extractor engine (yt-dlp & gallery-dl) for media from YouTube, Twitter, Reddit, TikTok, etc.
        val isExtractorUrl = GalleryDlEngine.isAvailable() && (
            GalleryDlEngine.checkSupport(downloadUrl).isSupported ||
            (stack.sourceUrl.isNotBlank() && GalleryDlEngine.checkSupport(stack.sourceUrl).isSupported)
        )
        if (isExtractorUrl) {
            val gdlFile = withContext(Dispatchers.IO) {
                GalleryDlEngine.downloadMedia(
                    context = context,
                    mediaUrl = downloadUrl,
                    fileName = fileName,
                    referer = stack.sourceUrl.ifBlank { null }
                ) ?: run {
                    if (stack.sourceUrl.isNotBlank() && stack.sourceUrl != downloadUrl) {
                        GalleryDlEngine.downloadMedia(
                            context = context,
                            mediaUrl = stack.sourceUrl,
                            fileName = fileName,
                            referer = stack.sourceUrl
                        )
                    } else null
                }
            }
            if (gdlFile != null && gdlFile.exists() && gdlFile.length() > 0L) {
                Toast.makeText(context, "Saved to device via extractor engine: ${gdlFile.name}", Toast.LENGTH_LONG).show()
                return@launch
            }
        }

        // 4. Download via OkHttpDownloader on IO thread
        if (downloadUrl.startsWith("http://", ignoreCase = true) ||
            downloadUrl.startsWith("https://", ignoreCase = true) ||
            downloadUrl.startsWith("data:", ignoreCase = true)
        ) {
            val result = withContext(Dispatchers.IO) {
                OkHttpDownloader.downloadMediaFileDetailed(
                    context = context,
                    mediaUrl = downloadUrl,
                    fileName = fileName,
                    referer = stack.sourceUrl.ifBlank { null }
                )
            }
            if (result.isSuccessful && result.file != null && result.file.exists()) {
                withContext(Dispatchers.IO) {
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(result.file.absolutePath),
                        arrayOf(if (item.isVideo) "video/*" else "image/*"),
                        null
                    )
                }
                Toast.makeText(context, "Saved to device: ${result.file.name}", Toast.LENGTH_LONG).show()
                return@launch
            }
        }

        // 3. Fallback: Enqueue download in background with retry flag
        val scraped = ScrapedMediaItem(
            id = item.id.ifBlank { "dl_${System.currentTimeMillis()}" },
            url = downloadUrl,
            title = item.title.ifBlank { stack.title.ifBlank { "Media Download" } },
            isVideo = item.isVideo,
            isAudio = item.isAudio,
            isStream = item.isStream,
            category = if (item.isVideo) MediaCategory.VIDEOS else MediaCategory.IMAGES,
            format = item.format.ifBlank { if (item.isVideo) "MP4" else "IMAGE" },
            source = "Bookmark Viewer"
        )
        DownloadQueueManager.enqueueDownload(
            context = context,
            item = scraped,
            referer = stack.sourceUrl.ifBlank { null },
            stackId = stack.id,
            stackTitle = stack.title.ifBlank { null },
            sourceDomain = stack.sourceDomain.ifBlank { null },
            sourceUrl = stack.sourceUrl.ifBlank { null },
            isRetry = true
        )
        Toast.makeText(context, "Download queued to device", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Top and bottom edge gradient shading behind the overlay controls, adapting to theme.
 */
@Composable
private fun BoxScope.PocketViewerEdgeShading(
    visible: Boolean,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(260)),
        exit = fadeOut(animationSpec = tween(220)),
        modifier = modifier.fillMaxSize()
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val edgeShadeHeight = maxHeight * 0.22f
            val shadeColor = if (isDark) Color.Black else Color.White
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(edgeShadeHeight)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                shadeColor.copy(alpha = if (isDark) 0.62f else 0.70f),
                                shadeColor.copy(alpha = if (isDark) 0.38f else 0.42f),
                                shadeColor.copy(alpha = if (isDark) 0.16f else 0.18f),
                                Color.Transparent
                            )
                        )
                    )
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(edgeShadeHeight)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                shadeColor.copy(alpha = if (isDark) 0.16f else 0.18f),
                                shadeColor.copy(alpha = if (isDark) 0.42f else 0.45f),
                                shadeColor.copy(alpha = if (isDark) 0.68f else 0.72f)
                            )
                        )
                    )
            )
        }
    }
}

/**
 * Individual zoomable media page inside the HorizontalPager, supporting:
 * - Pinch-to-zoom (up to 4.5x)
 * - Double-tap zoom (2.5x) and double-tap reset (1.0x)
 * - Pan when zoomed
 * - Vertical drag-to-dismiss gesture with scrim fade when unzoomed
 * - Single tap to toggle controls
 * - ExoPlayer video playback when item is a video
 */
@Composable
private fun PocketZoomableMediaPage(
    item: MediaInboxItem,
    isCurrentPage: Boolean,
    videoState: ViewerVideoState?,
    touchSlop: Float,
    minFlingVelocity: Float,
    onToggleControls: () -> Unit,
    onSwipeDownDelta: (Float) -> Unit,
    onSwipeDownRelease: (Float) -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    transitionProgress: Float = 1f,
    dismissOffsetY: Float = 0f,
    isDark: Boolean = isSystemInDarkTheme(),
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var offsetX by remember(item.id) { mutableFloatStateOf(0f) }
    var offsetY by remember(item.id) { mutableFloatStateOf(0f) }

    var intrinsicWidth by remember(item.id) { mutableIntStateOf(0) }
    var intrinsicHeight by remember(item.id) { mutableIntStateOf(0) }

    val context = LocalContext.current
    val mediaPath = remember(item) {
        if (item.localUri.isNotBlank()) {
            val f = File(item.localUri.removePrefix("file://"))
            if (f.exists()) f.absolutePath else item.sourceUrl
        } else item.sourceUrl
    }
    val isVideo = videoState != null

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
                .pointerInput(item.id, isVideo) {
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
                            var totalMovementX = 0f
                            var totalMovementY = 0f

                            while (true) {
                                val event = awaitPointerEvent()
                                val pointerCount = event.changes.size

                                if (pointerCount >= 2 && !isVideo) {
                                    isPinching = true
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

                                    if (scale > 1.02f && !isVideo) {
                                        val maxX = (width * (scale - 1f)) / 2f
                                        val maxY = (height * (scale - 1f)) / 2f

                                        offsetX = (offsetX + posChange.x).coerceIn(-maxX, maxX)
                                        offsetY = (offsetY + posChange.y).coerceIn(-maxY, maxY)
                                        change.consume()
                                    } else {
                                        if (!isVerticalSwipe && !isHorizontalBrowse) {
                                            val dist = kotlin.math.hypot(totalMovementX, totalMovementY)
                                            if (dist > touchSlop) {
                                                if (totalMovementX >= totalMovementY) {
                                                    isHorizontalBrowse = true
                                                } else {
                                                    isVerticalSwipe = true
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

                            val velocityY = velocityTracker.calculateVelocity().y
                            if (isVerticalSwipe) {
                                onSwipeDownRelease(velocityY)
                            } else if (!isPinching && totalMovementX < touchSlop && totalMovementY < touchSlop) {
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
                },
            contentAlignment = Alignment.Center
        ) {
            // Blurred scrim area: media blurred at 24.dp fills the unfilled container areas just like the deck cards stack
            if (mediaPath.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(mediaPath)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(24.dp)
                        .graphicsLayer {
                            val pageAlpha = if (isCurrentPage) transitionProgress.coerceIn(0f, 1f) else 1f
                            alpha = (pageAlpha * (if (isDark) 0.85f else 0.75f)).coerceIn(0f, 1f)
                        }
                )
                // Soft translucent scrim tint for depth and focus just like the deck cards stack
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(if (isDark) Color.Black.copy(alpha = 0.50f) else Color.White.copy(alpha = 0.35f))
                )
            }

            val imgW = intrinsicWidth.toFloat()
            val imgH = intrinsicHeight.toFloat()
            val imageAspect = if (imgW > 0f && imgH > 0f) imgW / imgH else (containerWidth / containerHeight.coerceAtLeast(1f))
            val containerAspect = containerWidth / containerHeight.coerceAtLeast(1f)

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

                Box(
                    modifier = fittedModifier
                        .graphicsLayer {
                            val p = transitionProgress.coerceIn(0f, 1f)
                            val currentScale = 0.88f + 0.12f * p
                            val exitY = if (dismissOffsetY > 0f) {
                                dismissOffsetY + (1f - p) * (containerHeight * 0.35f)
                            } else 0f
                            translationX = 0f
                            translationY = exitY
                            scaleX = currentScale
                            scaleY = currentScale
                            alpha = if (isCurrentPage) p.coerceIn(0f, 1f) else if (p < 0.99f) 0f else 1f
                            clip = p < 0.999f
                            shape = RoundedCornerShape(((1f - p) * 16f).dp)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Image layer
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(mediaPath)
                            .crossfade(true)
                            .build(),
                        contentDescription = item.title,
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
 * Auto-playing video player specifically designed for the Bookmark deck container.
 * Loops automatically, manages player lifecycle with pause/resume, and provides
 * an optional mute/unmute toggle control.
 */
@Composable
fun BookmarkDeckVideoPlayer(
    uri: String,
    isPlaying: Boolean = true,
    isMuted: Boolean = false,
    onToggleMute: (() -> Unit)? = null,
    isDark: Boolean = isSystemInDarkTheme(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val player = remember(uri) {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1000, 3000, 100, 250)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val httpDataSourceFactory = OkHttpDataSource.Factory(OkHttpDownloader.client)
            .setUserAgent(OkHttpDownloader.DEFAULT_USER_AGENT)
        val upstreamDataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(upstreamDataSourceFactory)

        val parsedUri = Uri.parse(uri)
        val isHls = uri.contains(".m3u8", ignoreCase = true) || uri.contains("mpegurl", ignoreCase = true)
        val isDash = uri.contains(".mpd", ignoreCase = true) || uri.contains("dash", ignoreCase = true)
        val mediaItem = ExoMediaItem.Builder()
            .setUri(parsedUri)
            .apply {
                if (isHls) setMimeType(MimeTypes.APPLICATION_M3U8)
                else if (isDash) setMimeType(MimeTypes.APPLICATION_MPD)
            }
            .build()

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build().apply {
                setMediaItem(mediaItem)
                repeatMode = Player.REPEAT_MODE_ONE
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                volume = if (isMuted) 0f else 1f
                prepare()
                playWhenReady = true
            }
    }

    LaunchedEffect(isMuted, player) {
        player.volume = if (isMuted) 0f else 1f
    }

    LaunchedEffect(isPlaying, player) {
        if (isPlaying) {
            player.play()
        } else {
            player.pause()
        }
    }

    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                player.pause()
            } else if (event == Lifecycle.Event.ON_RESUME && isPlaying) {
                player.play()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.stop()
            player.release()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { pv ->
                if (pv.player != player) {
                    pv.player = player
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (onToggleMute != null) {
            Surface(
                shape = CircleShape,
                color = if (isDark) Color.Black.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.88f),
                border = BorderStroke(0.5.dp, if (isDark) Color.White.copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.15f)),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .size(36.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = if (isDark) Color.White else Color.Black),
                        onClick = onToggleMute
                    )
                    .testTag("deck_video_mute_toggle")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (isMuted) "Unmute video" else "Mute video",
                        tint = if (isDark) Color.White else Color(0xFF141414),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
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
