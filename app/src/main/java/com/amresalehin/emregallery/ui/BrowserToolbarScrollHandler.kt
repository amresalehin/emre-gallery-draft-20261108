package com.amresalehin.emregallery.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

/**
 * Manages auto-hide visibility for the browser's floating bottom action bar
 * based on scroll and drag direction with a small movement threshold.
 *
 * Requirements:
 * - Hide smoothly when user scrolls down.
 * - Show again when user scrolls up.
 * - Keep visible when scrolling stops (no idle auto-dismissal).
 * - Always visible at the top of the page (scrollY <= topThreshold).
 * - Does not intercept touches outside its bounds.
 */
class BrowserToolbarScrollHandler(
    val thresholdPx: Float = 20f,
    initialVisible: Boolean = true
) {
    var isVisible by mutableStateOf(initialVisible)
        private set

    // Accumulated scroll delta for continuous scroll listener
    private var accumulatedScrollDy: Float = 0f

    // Accumulated touch delta for gesture movements
    private var accumulatedTouchDy: Float = 0f

    /**
     * Called by WebView.setOnScrollChangeListener
     * @param scrollY Current vertical scroll offset
     * @param dy The change in vertical scroll position (scrollY - oldScrollY)
     */
    fun onScroll(scrollY: Int, dy: Int) {
        // At or near top of page, always keep toolbar visible
        if (scrollY <= 10) {
            isVisible = true
            accumulatedScrollDy = 0f
            return
        }

        if (dy > 0) {
            // Scrolling down (page moving up)
            if (accumulatedScrollDy < 0f) accumulatedScrollDy = 0f
            accumulatedScrollDy += dy
            if (accumulatedScrollDy >= thresholdPx) {
                isVisible = false
            }
        } else if (dy < 0) {
            // Scrolling up (page moving down)
            if (accumulatedScrollDy > 0f) accumulatedScrollDy = 0f
            accumulatedScrollDy += dy
            if (accumulatedScrollDy <= -thresholdPx) {
                isVisible = true
            }
        }
        // When scrolling stops (dy == 0), isVisible maintains its state!
    }

    /**
     * Called by touch listener on ACTION_MOVE with delta movement.
     * @param dy Difference in Y (startY - currentY). Positive = dragged finger up (scrolling down).
     * @param dx Difference in X (startX - currentX). Used to ignore horizontal gestures.
     */
    fun onTouchMove(dy: Float, dx: Float = 0f) {
        // Ignore primarily horizontal gestures (e.g. carousels, tabs, sliders)
        if (abs(dx) > abs(dy)) return

        if (dy > 0f) {
            // Dragged upward -> user is reading down the page -> hide
            if (accumulatedTouchDy < 0f) accumulatedTouchDy = 0f
            accumulatedTouchDy += dy
            if (accumulatedTouchDy >= thresholdPx) {
                isVisible = false
            }
        } else if (dy < 0f) {
            // Dragged downward -> user is scrolling back up -> show
            if (accumulatedTouchDy > 0f) accumulatedTouchDy = 0f
            accumulatedTouchDy += dy
            if (accumulatedTouchDy <= -thresholdPx) {
                isVisible = true
            }
        }
    }

    /**
     * Reset gesture accumulation when finger lifts (ACTION_UP/ACTION_CANCEL).
     * Crucially preserves the current isVisible state so toolbar stays visible/hidden when stopped.
     */
    fun onTouchEnd() {
        accumulatedTouchDy = 0f
    }

    fun show() {
        isVisible = true
        accumulatedScrollDy = 0f
        accumulatedTouchDy = 0f
    }

    fun hide() {
        isVisible = false
        accumulatedScrollDy = 0f
        accumulatedTouchDy = 0f
    }
}
