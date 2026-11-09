@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.amresalehin.emregallery.ui

import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
import androidx.media3.ui.PlayerView
import com.amresalehin.emregallery.data.OkHttpDownloader
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * State holder for video playback in the media viewer.
 * Respects session-wide playback intent so manually paused videos don't autoplay when swiping.
 */
@Stable
class ViewerVideoState(
    val player: ExoPlayer?,
    val isVideo: Boolean,
    initialPlayIntent: Boolean,
    private val onPlayIntentChange: (Boolean) -> Unit
) {
    var isFirstFrameRendered by mutableStateOf(false)
    var isPlaying by mutableStateOf(if (isVideo && player != null) initialPlayIntent else false)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var isMuted by mutableStateOf(false)
    var isFullscreen by mutableStateOf(false)

    fun togglePlayPause() {
        val targetPlay = !isPlaying
        if (targetPlay) {
            player?.play()
        } else {
            player?.pause()
        }
        isPlaying = targetPlay
        onPlayIntentChange(targetPlay)
    }

    fun seekTo(position: Long) {
        positionMs = position
        player?.seekTo(position)
    }

    fun toggleMute() {
        val newMuted = !isMuted
        isMuted = newMuted
        player?.volume = if (newMuted) 0f else 1f
    }

    fun toggleFullscreen() {
        isFullscreen = !isFullscreen
    }
}

/**
 * Manages the ExoPlayer lifecycle and produces a [ViewerVideoState].
 * Applies [sessionPlayIntent] when creating and preparing new media items.
 */
@Composable
fun rememberViewerVideoState(
    uri: String,
    isVideo: Boolean,
    showControls: Boolean,
    sessionPlayIntent: Boolean = true,
    onPlayIntentChange: (Boolean) -> Unit = {}
): ViewerVideoState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val player = remember(uri, isVideo) {
        if (isVideo) {
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 1000,
                    /* maxBufferMs = */ 3000,
                    /* bufferForPlaybackMs = */ 100,
                    /* bufferForPlaybackAfterRebufferMs = */ 250
                )
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
                    if (isHls) {
                        setMimeType(MimeTypes.APPLICATION_M3U8)
                    } else if (isDash) {
                        setMimeType(MimeTypes.APPLICATION_MPD)
                    }
                }
                .build()

            ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(loadControl)
                .build().apply {
                    setMediaItem(mediaItem)
                    repeatMode = if (isHls || isDash) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
                    videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                    prepare()
                    // Respect user's playback intent for this session:
                    playWhenReady = sessionPlayIntent
                }
        } else {
            null
        }
    }

    val state = remember(uri, isVideo) {
        ViewerVideoState(
            player = player,
            isVideo = isVideo,
            initialPlayIntent = sessionPlayIntent,
            onPlayIntentChange = onPlayIntentChange
        )
    }

    // Attach ExoPlayer listener to current active player
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                state.isFirstFrameRendered = true
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                state.isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    state.durationMs = player?.duration?.takeIf { it > 0L } ?: 0L
                }
            }
        }
        player?.addListener(listener)
        onDispose {
            player?.removeListener(listener)
            player?.stop()
            player?.release()
        }
    }

    // Pause player when activity/lifecycle moves to background
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                player?.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Poll current playback position while controls are visible
    LaunchedEffect(player, uri, showControls) {
        if (!showControls) return@LaunchedEffect
        while (player != null && isActive) {
            state.positionMs = player.currentPosition.coerceAtLeast(0L)
            val d = player.duration
            if (d > 0L && d != state.durationMs) {
                state.durationMs = d
            }
            delay(250)
        }
    }

    return state
}

/**
 * AndroidView host for ExoPlayer rendering within the HorizontalPager.
 */
@Composable
fun VideoPlayerSurface(
    videoState: ViewerVideoState,
    modifier: Modifier = Modifier
) {
    if (!videoState.isVideo || videoState.player == null) return

    // Keep the cached poster underneath and fade the first rendered video frame in over it.
    // SurfaceView alpha is supported on API 26+, which is this app's minimum Android version.
    val surfaceAlpha by animateFloatAsState(
        targetValue = if (videoState.isFirstFrameRendered) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "video_frame_handoff"
    )

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = videoState.player
                useController = false
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isClickable = false
                isFocusable = false
            }
        },
        modifier = modifier
            .fillMaxSize()
            .alpha(surfaceAlpha),
        update = { pv ->
            if (pv.player != videoState.player) {
                pv.player = videoState.player
            }
            pv.useController = false
            pv.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            pv.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
    )
}

/**
 * Floating video playback controller bar overlay (Play/Pause, timestamps, seek slider, mute, fullscreen).
 * Features spacious touch targets, adaptive seek slider compression, distinct adjacent buttons,
 * and clean translucent styling that never overlaps surrounding action bars.
 */
@Composable
fun VideoPlayerControlsBar(
    videoState: ViewerVideoState,
    modifier: Modifier = Modifier
) {
    if (!videoState.isVideo || videoState.player == null) return

    val position = videoState.positionMs
    val duration = videoState.durationMs
    val isPlaying = videoState.isPlaying
    val isMuted = videoState.isMuted
    val isFullscreen = videoState.isFullscreen

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.68f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 4.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Play / Pause button with generous touch target
            IconButton(
                onClick = { videoState.togglePlayPause() },
                modifier = Modifier
                    .size(42.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f), CircleShape)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause video" else "Play video",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Current time with tabular figures
            Text(
                text = formatTime(position),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFeatureSettings = "tnum",
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )

            // Flexible Seek Slider that shrinks before compressing timestamps or buttons
            Slider(
                value = position.toFloat().coerceAtMost(duration.coerceAtLeast(1L).toFloat()),
                onValueChange = { videoState.positionMs = it.toLong() },
                onValueChangeFinished = { videoState.seekTo(videoState.positionMs) },
                valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
                ),
                modifier = Modifier
                    .weight(1f, fill = true)
                    .padding(horizontal = 2.dp)
            )

            // Duration with tabular figures
            Text(
                text = formatTime(duration),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFeatureSettings = "tnum",
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )

            // Adjacent Mute and Fullscreen buttons with distinct touch targets
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Mute toggle
                IconButton(
                    onClick = { videoState.toggleMute() },
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f), CircleShape)
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (isMuted) "Unmute video" else "Mute video",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Fullscreen toggle
                IconButton(
                    onClick = { videoState.toggleFullscreen() },
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f), CircleShape)
                ) {
                    Icon(
                        imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        contentDescription = if (isFullscreen) "Exit fullscreen" else "Enter fullscreen",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

private fun formatTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
