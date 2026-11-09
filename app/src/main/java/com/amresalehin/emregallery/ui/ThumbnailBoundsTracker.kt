package com.amresalehin.emregallery.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.dp

/**
 * Tracks on-screen bounding boxes of thumbnails across the gallery screens (Home, Albums, Search, Favourites, Bin).
 * Used to coordinate smooth shared-element return animations when opening or closing the media viewer.
 *
 * Optimization: Coordinates are stored onPlaced (only upon item placement) rather than onGloballyPositioned.
 * Bounding boxes are computed strictly on-demand when the media viewer opens or closes, completely eliminating
 * coordinate matrix calculations and Rect allocations during active scrolling.
 */
object ThumbnailBoundsTracker {
    private val coordinatesMap = mutableMapOf<String, LayoutCoordinates>()
    private val cachedBounds = mutableMapOf<String, Rect>()

    /**
     * Currently active viewer media item URI.
     * When non-null, the corresponding source thumbnail in the grid can suppress its foreground image
     * to prevent visual duplication during the opening or return animation.
     */
    var activeViewerUri by mutableStateOf<String?>(null)

    fun registerCoordinates(uri: String, coordinates: LayoutCoordinates) {
        coordinatesMap[uri] = coordinates
    }

    fun updateBounds(uri: String, bounds: Rect) {
        if (bounds.width > 5f && bounds.height > 5f) {
            cachedBounds[uri] = bounds
        }
    }

    fun getBounds(uri: String): Rect? {
        val coords = coordinatesMap[uri]
        if (coords != null && coords.isAttached) {
            val rootBounds = coords.boundsInRoot()
            if (rootBounds.width > 5f && rootBounds.height > 5f) {
                cachedBounds[uri] = rootBounds
                return rootBounds
            }
        }
        return cachedBounds[uri]
    }

    fun removeBounds(uri: String) {
        coordinatesMap.remove(uri)
        cachedBounds.remove(uri)
    }

    fun clear() {
        coordinatesMap.clear()
        cachedBounds.clear()
        activeViewerUri = null
    }
}

/**
 * Modifier extension to track the window/root coordinates of a media thumbnail.
 * Stores lightweight layout coordinates on placement without calculating root bounds until queried.
 */
fun Modifier.trackThumbnailBounds(uri: String): Modifier = this.onPlaced { coordinates ->
    if (coordinates.isAttached) {
        ThumbnailBoundsTracker.registerCoordinates(uri, coordinates)
    }
}

/**
 * Coordinated top bar entrance modifier with progress provider to avoid recomposing caller.
 */
fun Modifier.destinationTopBarEntrance(progressProvider: () -> Float): Modifier = this.graphicsLayer {
    val progress = progressProvider()
    if (progress > 0.001f) {
        translationY = -(size.height + 60.dp.toPx()) * progress
        alpha = (1f - progress * 1.15f).coerceIn(0f, 1f)
    }
}

fun Modifier.destinationTopBarEntrance(progress: Float): Modifier = this.graphicsLayer {
    if (progress > 0.001f) {
        translationY = -(size.height + 60.dp.toPx()) * progress
        alpha = (1f - progress * 1.15f).coerceIn(0f, 1f)
    }
}

/**
 * Coordinated bottom bar entrance modifier with progress provider.
 */
fun Modifier.destinationBottomBarEntrance(progressProvider: () -> Float): Modifier = this.graphicsLayer {
    val progress = progressProvider()
    if (progress > 0.001f) {
        translationY = (size.height + 60.dp.toPx()) * progress
        alpha = (1f - progress * 1.15f).coerceIn(0f, 1f)
    }
}

fun Modifier.destinationBottomBarEntrance(progress: Float): Modifier = this.graphicsLayer {
    if (progress > 0.001f) {
        translationY = (size.height + 60.dp.toPx()) * progress
        alpha = (1f - progress * 1.15f).coerceIn(0f, 1f)
    }
}
