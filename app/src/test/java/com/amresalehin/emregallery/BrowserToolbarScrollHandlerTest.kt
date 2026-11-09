package com.amresalehin.emregallery

import com.amresalehin.emregallery.ui.BrowserToolbarScrollHandler
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserToolbarScrollHandlerTest {

    @Test
    fun testInitialStateIsVisible() {
        val handler = BrowserToolbarScrollHandler(thresholdPx = 20f, initialVisible = true)
        assertTrue("Toolbar should initially be visible", handler.isVisible)
    }

    @Test
    fun testSmallScrollBelowThresholdDoesNotHideToolbar() {
        val handler = BrowserToolbarScrollHandler(thresholdPx = 20f, initialVisible = true)

        // Scroll down with delta of 8px (< 20px threshold)
        handler.onScroll(scrollY = 100, dy = 8)
        assertTrue("Small scroll below threshold must not hide toolbar", handler.isVisible)

        // Another small scroll of 5px (total 13px < 20px)
        handler.onScroll(scrollY = 105, dy = 5)
        assertTrue("Accumulated scroll below threshold must not hide toolbar", handler.isVisible)
    }

    @Test
    fun testScrollDownExceedingThresholdHidesToolbar() {
        val handler = BrowserToolbarScrollHandler(thresholdPx = 20f, initialVisible = true)

        // Scroll down exceeding threshold (15 + 10 = 25px > 20px)
        handler.onScroll(scrollY = 100, dy = 15)
        handler.onScroll(scrollY = 110, dy = 10)
        assertFalse("Scrolling down past threshold should hide the toolbar", handler.isVisible)
    }

    @Test
    fun testToolbarKeepsStateWhenScrollingStops() {
        val handler = BrowserToolbarScrollHandler(thresholdPx = 20f, initialVisible = true)

        // User scrolls down past threshold
        handler.onScroll(scrollY = 200, dy = 30)
        assertFalse(handler.isVisible)

        // User stops scrolling (dy = 0)
        handler.onScroll(scrollY = 200, dy = 0)
        assertFalse("Toolbar should remain hidden when scroll-down stops", handler.isVisible)

        // User scrolls up past threshold
        handler.onScroll(scrollY = 150, dy = -30)
        assertTrue("Toolbar should show when scrolling up", handler.isVisible)

        // User stops scrolling again (dy = 0)
        handler.onScroll(scrollY = 150, dy = 0)
        assertTrue("Toolbar must keep being visible when scrolling stops (no timeout dismissal)", handler.isVisible)
    }

    @Test
    fun testScrollUpExceedingThresholdShowsToolbar() {
        val handler = BrowserToolbarScrollHandler(thresholdPx = 20f, initialVisible = false)

        // Small scroll up (dy = -10, |dy| < 20)
        handler.onScroll(scrollY = 200, dy = -10)
        assertFalse("Small scroll up should not show toolbar yet", handler.isVisible)

        // Exceed threshold
        handler.onScroll(scrollY = 185, dy = -15)
        assertTrue("Scrolling up exceeding threshold must show toolbar", handler.isVisible)
    }

    @Test
    fun testTopPageAlwaysKeepsToolbarVisible() {
        val handler = BrowserToolbarScrollHandler(thresholdPx = 20f, initialVisible = false)

        // Even with positive dy, if at or near top of page (scrollY <= 10), toolbar should show
        handler.onScroll(scrollY = 5, dy = 2)
        assertTrue("At the top of the webpage, toolbar must always be visible", handler.isVisible)

        handler.onScroll(scrollY = 0, dy = 0)
        assertTrue("At scrollY == 0, toolbar must be visible", handler.isVisible)
    }

    @Test
    fun testTouchDragUpwardHidesToolbar() {
        val handler = BrowserToolbarScrollHandler(thresholdPx = 20f, initialVisible = true)

        // Dragging finger up moves viewport down (dy > 0)
        handler.onTouchMove(dy = 10f, dx = 0f)
        assertTrue("Under threshold should remain visible", handler.isVisible)

        handler.onTouchMove(dy = 15f, dx = 0f)
        assertFalse("Touch drag upward past threshold should hide toolbar", handler.isVisible)

        handler.onTouchEnd()
        assertFalse("Lifting finger preserves hidden state", handler.isVisible)
    }

    @Test
    fun testTouchDragDownwardShowsToolbar() {
        val handler = BrowserToolbarScrollHandler(thresholdPx = 20f, initialVisible = false)

        // Dragging finger down moves viewport up (dy < 0)
        handler.onTouchMove(dy = -25f, dx = 2f)
        assertTrue("Touch drag downward past threshold should show toolbar", handler.isVisible)

        handler.onTouchEnd()
        assertTrue("Lifting finger preserves visible state", handler.isVisible)
    }

    @Test
    fun testHorizontalSwipeIgnored() {
        val handler = BrowserToolbarScrollHandler(thresholdPx = 20f, initialVisible = true)

        // Horizontal swipe (carousel): dx = 50, dy = 10
        handler.onTouchMove(dy = 10f, dx = 50f)
        assertTrue("Horizontal swipe must be ignored and not change visibility", handler.isVisible)
    }

    @Test
    fun testShowAndHideDirectCalls() {
        val handler = BrowserToolbarScrollHandler(thresholdPx = 20f, initialVisible = true)

        handler.hide()
        assertFalse(handler.isVisible)

        handler.show()
        assertTrue(handler.isVisible)
    }
}
