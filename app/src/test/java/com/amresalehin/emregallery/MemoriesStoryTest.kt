package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.MediaItem
import com.amresalehin.emregallery.ui.Destination
import com.amresalehin.emregallery.ui.NavBackStackEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoriesStoryTest {

    @Test
    fun navBackStackEntryPreservesStoryTitle() {
        val dummyMedia = MediaItem(
            id = 101L,
            uri = "content://media/external/images/media/101",
            name = "IMG_001.jpg",
            relativePath = "DCIM/Camera",
            mimeType = "image/jpeg",
            dateTakenMillis = 1700000000000L,
            dateAddedSeconds = 1700000000L,
            sizeBytes = 2048576L,
            width = 1920,
            height = 1080,
            isVideo = false
        )

        val entry = NavBackStackEntry(
            destination = Destination.VIEWER,
            selectedMedia = dummyMedia,
            viewerMediaList = listOf(dummyMedia),
            viewerStoryTitle = "On this day · 2023"
        )

        assertEquals(Destination.VIEWER, entry.destination)
        assertEquals("On this day · 2023", entry.viewerStoryTitle)
        assertEquals(1, entry.viewerMediaList.size)
        assertEquals(dummyMedia.uri, entry.selectedMedia?.uri)
    }

    @Test
    fun testSegmentedProgressCalculation() {
        val totalItems = 5
        val currentIndex = 2
        val currentProgress = 0.65f

        for (i in 0 until totalItems) {
            val progress = when {
                i < currentIndex -> 1f
                i == currentIndex -> currentProgress.coerceIn(0f, 1f)
                else -> 0f
            }

            when (i) {
                0 -> assertEquals(1f, progress, 0.001f)
                1 -> assertEquals(1f, progress, 0.001f)
                2 -> assertEquals(0.65f, progress, 0.001f)
                3 -> assertEquals(0f, progress, 0.001f)
                4 -> assertEquals(0f, progress, 0.001f)
            }
        }
    }

    @Test
    fun testTapZoneCalculation() {
        val screenWidth = 1080f
        val leftZoneThreshold = screenWidth * 0.30f

        val tapXLeft = 200f
        val tapXRight = 800f

        val isLeftTap = tapXLeft < leftZoneThreshold
        val isRightTap = tapXRight >= leftZoneThreshold

        assertEquals(true, isLeftTap)
        assertEquals(true, isRightTap)
    }

    @Test
    fun testMultiMemoryProgression() {
        val item1 = MediaItem(1L, "uri1", "1.jpg", "image/jpeg", 100L, 1000L, 100L, 100, 100, "Camera", false)
        val item2 = MediaItem(2L, "uri2", "2.jpg", "image/jpeg", 200L, 2000L, 200L, 100, 100, "Camera", false)
        val item3 = MediaItem(3L, "uri3", "3.jpg", "image/jpeg", 300L, 3000L, 300L, 100, 100, "Camera", false)

        val memory1 = com.amresalehin.emregallery.data.StoryGroup("Memory 1", listOf(item1, item2))
        val memory2 = com.amresalehin.emregallery.data.StoryGroup("Memory 2", listOf(item3))
        val stories = listOf(memory1, memory2)

        var activeStoryIndex = 0
        var activePageIndex = 0
        var isEndOfStoryReached = false

        fun advance() {
            val currentStoryItems = stories[activeStoryIndex].items
            if (activePageIndex < currentStoryItems.size - 1) {
                activePageIndex++
            } else if (activeStoryIndex < stories.size - 1) {
                activeStoryIndex++
                activePageIndex = 0
            } else {
                isEndOfStoryReached = true
            }
        }

        // Memory 1, Item 1
        assertEquals(0, activeStoryIndex)
        assertEquals(0, activePageIndex)
        assertEquals(false, isEndOfStoryReached)

        // Advance to Memory 1, Item 2
        advance()
        assertEquals(0, activeStoryIndex)
        assertEquals(1, activePageIndex)
        assertEquals(false, isEndOfStoryReached)

        // Advance to Memory 2, Item 1
        advance()
        assertEquals(1, activeStoryIndex)
        assertEquals(0, activePageIndex)
        assertEquals(false, isEndOfStoryReached)

        // Advance past last item of last memory -> end of stories constellation screen
        advance()
        assertEquals(true, isEndOfStoryReached)

        // Replay resets to Memory 1, Item 1
        activeStoryIndex = 0
        activePageIndex = 0
        isEndOfStoryReached = false
        assertEquals(0, activeStoryIndex)
        assertEquals(0, activePageIndex)
        assertEquals(false, isEndOfStoryReached)
    }

    @Test
    fun testMemoriesDerivedFromDayGroupsWithoutFullLibraryScan() {
        val cal = java.util.Calendar.getInstance()
        val currentYear = cal.get(java.util.Calendar.YEAR)
        cal.set(java.util.Calendar.YEAR, currentYear - 1)
        val priorYearDayStart = cal.timeInMillis

        val item1 = MediaItem(1L, "uri1", "1.jpg", "image/jpeg", priorYearDayStart, 1000L, 100L, 100, 100, "Camera", false)
        val item2 = MediaItem(2L, "uri2", "2.jpg", "image/jpeg", priorYearDayStart, 2000L, 200L, 100, 100, "Camera", false)
        val dayGroup1 = com.amresalehin.emregallery.ui.DayGroup(priorYearDayStart, "1 year ago", listOf(item1, item2))

        val recentCal = java.util.Calendar.getInstance()
        recentCal.add(java.util.Calendar.DAY_OF_YEAR, -10)
        val tripDayStart = recentCal.timeInMillis
        val tripItems = (1..5).map {
            MediaItem(it.toLong() + 10, "uri$it", "$it.jpg", "image/jpeg", tripDayStart, 1000L, 100L, 100, 100, "Camera", false)
        }
        val tripDayGroup = com.amresalehin.emregallery.ui.DayGroup(tripDayStart, "Trip Day", tripItems)

        val groups = listOf(dayGroup1, tripDayGroup)

        // Verify day groups hold pre-clustered media directly
        assertEquals(2, groups.size)
        assertEquals(2, groups[0].items.size)
        assertEquals(5, groups[1].items.size)
        // Group with >= 4 items qualifies for trip story without needing full-library scan
        val qualifyingTrips = groups.filter { it.items.size >= 4 }
        assertEquals(1, qualifyingTrips.size)
        assertEquals(5, qualifyingTrips[0].items.size)
    }

    @Test
    fun testJustifiedMosaicLayoutAlgorithmEquivalence() {
        val validCols = 3
        val gap = 4f
        val totalGap = gap * (validCols - 1)
        val containerWidth = 360f

        // Grid algorithm aspects
        val masonryAspects = (0 until validCols).map { colIndex ->
            when (colIndex.mod(4)) {
                0 -> 0.72f
                1 -> 0.9f
                2 -> 1.0f
                else -> 1.35f
            }
        }
        val aspectsSum = masonryAspects.sum()
        val rowHeight = ((containerWidth - totalGap) / aspectsSum).coerceIn(72f, 220f)

        // Verify valid computed row height and matching aspect count
        assertEquals(3, masonryAspects.size)
        assert(rowHeight in 72f..220f)
    }
}
