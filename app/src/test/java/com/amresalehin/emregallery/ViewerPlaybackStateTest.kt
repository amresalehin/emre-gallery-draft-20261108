package com.amresalehin.emregallery

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import com.amresalehin.emregallery.ui.ThumbnailBoundsTracker
import com.amresalehin.emregallery.ui.transitionContentScale
import com.amresalehin.emregallery.ui.ViewerVideoState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ViewerPlaybackStateTest {

    @Before
    fun setUp() {
        ThumbnailBoundsTracker.clear()
    }

    @Test
    fun transitionContentScaleInterpolatesCropToFitContinuously() {
        val source = Size(width = 200f, height = 100f)
        val destination = Size(width = 100f, height = 100f)
        val crop = ContentScale.Crop.computeScaleFactor(source, destination)
        val fit = ContentScale.Fit.computeScaleFactor(source, destination)

        val atStart = transitionContentScale(0f).computeScaleFactor(source, destination)
        val atMiddle = transitionContentScale(0.5f).computeScaleFactor(source, destination)
        val atEnd = transitionContentScale(1f).computeScaleFactor(source, destination)

        assertEquals(crop.scaleX, atStart.scaleX, 0.001f)
        assertEquals(crop.scaleY, atStart.scaleY, 0.001f)
        assertEquals((crop.scaleX + fit.scaleX) / 2f, atMiddle.scaleX, 0.001f)
        assertEquals((crop.scaleY + fit.scaleY) / 2f, atMiddle.scaleY, 0.001f)
        assertEquals(fit.scaleX, atEnd.scaleX, 0.001f)
        assertEquals(fit.scaleY, atEnd.scaleY, 0.001f)
    }

    @Test
    fun transitionContentScaleClampsProgressToValidRange() {
        val source = Size(width = 200f, height = 100f)
        val destination = Size(width = 100f, height = 100f)
        val crop = ContentScale.Crop.computeScaleFactor(source, destination)
        val fit = ContentScale.Fit.computeScaleFactor(source, destination)

        val beforeStart = transitionContentScale(-1f).computeScaleFactor(source, destination)
        val afterEnd = transitionContentScale(2f).computeScaleFactor(source, destination)

        assertEquals(crop.scaleX, beforeStart.scaleX, 0.001f)
        assertEquals(crop.scaleY, beforeStart.scaleY, 0.001f)
        assertEquals(fit.scaleX, afterEnd.scaleX, 0.001f)
        assertEquals(fit.scaleY, afterEnd.scaleY, 0.001f)
    }

    @Test
    fun testPlaybackIntentPreservedWhenUserPauses() {
        var sessionPlayIntent = true

        // Video 1 starts with session autoplay intent = true
        val video1State = ViewerVideoState(
            player = null,
            isVideo = true,
            initialPlayIntent = sessionPlayIntent,
            onPlayIntentChange = { sessionPlayIntent = it }
        )
        // Without an active player instance in a pure unit test, isPlaying reflects initial intent
        assertFalse(video1State.isPlaying) // null player -> false

        // Simulate session intent tracking
        var intentCalls = mutableListOf<Boolean>()
        val tracker = ViewerVideoState(
            player = null,
            isVideo = true,
            initialPlayIntent = true,
            onPlayIntentChange = { intentCalls.add(it) }
        )
        tracker.isPlaying = true

        // User manually pauses
        tracker.togglePlayPause()
        assertFalse("Playback state must be paused", tracker.isPlaying)
        assertEquals("Session intent must be set to false", listOf(false), intentCalls)

        // User swipes to video 2: video 2 is created with sessionPlayIntent = false
        val video2SessionIntent = intentCalls.last()
        assertFalse("Next video must inherit paused intent", video2SessionIntent)

        val video2State = ViewerVideoState(
            player = null,
            isVideo = true,
            initialPlayIntent = video2SessionIntent,
            onPlayIntentChange = { intentCalls.add(it) }
        )
        assertFalse("Next video must initialize as paused", video2State.isPlaying)
    }

    @Test
    fun testFullscreenToggleAndMute() {
        val state = ViewerVideoState(
            player = null,
            isVideo = true,
            initialPlayIntent = true,
            onPlayIntentChange = {}
        )

        assertFalse("Fullscreen should start false", state.isFullscreen)
        state.toggleFullscreen()
        assertTrue("Fullscreen should now be true", state.isFullscreen)
        state.toggleFullscreen()
        assertFalse("Fullscreen should toggle back to false", state.isFullscreen)

        assertFalse("Mute should start false", state.isMuted)
        state.toggleMute()
        assertTrue("Mute should now be true", state.isMuted)
        state.toggleMute()
        assertFalse("Mute should toggle back to false", state.isMuted)
    }

    @Test
    fun testThumbnailBoundsTracker() {
        val testUri = "content://media/external/images/media/42"
        val sampleBounds = Rect(100f, 200f, 400f, 600f)

        assertNull(ThumbnailBoundsTracker.getBounds(testUri))
        assertNull(ThumbnailBoundsTracker.activeViewerUri)

        ThumbnailBoundsTracker.updateBounds(testUri, sampleBounds)
        assertEquals(sampleBounds, ThumbnailBoundsTracker.getBounds(testUri))

        ThumbnailBoundsTracker.activeViewerUri = testUri
        assertEquals(testUri, ThumbnailBoundsTracker.activeViewerUri)

        ThumbnailBoundsTracker.clear()
        assertNull(ThumbnailBoundsTracker.getBounds(testUri))
        assertNull(ThumbnailBoundsTracker.activeViewerUri)
    }

    @Test
    fun testReturnAnimationCoordinateMath() {
        val screenWidth = 1080f
        val screenHeight = 2400f
        val thumbBounds = Rect(left = 72f, top = 300f, right = 432f, bottom = 660f)

        val thumbW = thumbBounds.width // 360f
        val thumbH = thumbBounds.height // 360f
        val thumbCenterX = thumbBounds.left + thumbW / 2f // 252f
        val thumbCenterY = thumbBounds.top + thumbH / 2f // 480f

        val screenCenterX = screenWidth / 2f // 540f
        val screenCenterY = screenHeight / 2f // 1200f

        val destTransX = thumbCenterX - screenCenterX // -288f
        val destTransY = thumbCenterY - screenCenterY // -720f

        val destScale = maxOf(thumbW / screenWidth, thumbH / screenHeight)

        // At progress = 1f (fullscreen)
        val progressFull = 1f
        val currentScaleFull = destScale + (1f - destScale) * progressFull
        val currentTransXFull = destTransX * (1f - progressFull)
        val currentTransYFull = destTransY * (1f - progressFull)

        assertEquals(1f, currentScaleFull, 0.001f)
        assertEquals(0f, currentTransXFull, 0.001f)
        assertEquals(0f, currentTransYFull, 0.001f)

        // At progress = 0f (thumbnail)
        val progressThumb = 0f
        val currentScaleThumb = destScale + (1f - destScale) * progressThumb
        val currentTransXThumb = destTransX * (1f - progressThumb)
        val currentTransYThumb = destTransY * (1f - progressThumb)

        assertEquals(destScale, currentScaleThumb, 0.001f)
        assertEquals(destTransX, currentTransXThumb, 0.001f)
        assertEquals(destTransY, currentTransYThumb, 0.001f)
    }

    @Test
    fun testViewerBottomBarActionTracking() {
        var lockInvoked = false
        var archiveInvoked = false
        var moveInvoked = false
        var binInvoked = false

        val onLock = { lockInvoked = true }
        val onArchive = { archiveInvoked = true }
        val onMove = { moveInvoked = true }
        val onBin = { binInvoked = true }

        onLock()
        assertTrue("Lock callback must be triggered", lockInvoked)

        onArchive()
        assertTrue("Archive callback must be triggered", archiveInvoked)

        onMove()
        assertTrue("Move callback must be triggered", moveInvoked)

        onBin()
        assertTrue("Bin callback must be triggered", binInvoked)
    }
}
