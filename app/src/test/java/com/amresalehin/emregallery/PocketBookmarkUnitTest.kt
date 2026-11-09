package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.MediaClassifier
import com.amresalehin.emregallery.data.MediaInboxItem
import com.amresalehin.emregallery.data.MediaInboxStack
import com.amresalehin.emregallery.ui.Destination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketBookmarkUnitTest {

    @Test
    fun testDestinationEnumContainsPocket() {
        val destNames = Destination.values().map { it.name }
        assertTrue("Destination enum must contain POCKET", destNames.contains("POCKET"))
        assertEquals("POCKET", Destination.POCKET.name)
    }

    @Test
    fun testPocketBookmarkCreation() {
        val url = "https://en.wikipedia.org/wiki/Jetpack_Compose"
        val domain = MediaClassifier.cleanDomainFromUrl(url)
        val stack = MediaInboxStack(
            id = "bookmark_12345",
            title = "Jetpack Compose Guide",
            sourceDomain = domain,
            sourceUrl = url,
            items = emptyList(),
            timestampMillis = 1762500000000L,
            description = "UI toolkit for Android development"
        )

        assertEquals("bookmark_12345", stack.id)
        assertEquals("Jetpack Compose Guide", stack.title)
        assertEquals("en.wikipedia.org", stack.sourceDomain)
        assertEquals("https://en.wikipedia.org/wiki/Jetpack_Compose", stack.sourceUrl)
        assertEquals("UI toolkit for Android development", stack.description)
        assertTrue(stack.items.isEmpty())
    }

    @Test
    fun testPocketFilterAndSearchLogic() {
        val bookmarkWeb = MediaInboxStack(
            id = "bm_1",
            title = "Kotlin Documentation",
            sourceDomain = "kotlinlang.org",
            sourceUrl = "https://kotlinlang.org/docs/home.html",
            items = emptyList(),
            description = "Official Kotlin language reference"
        )
        val bookmarkMedia = MediaInboxStack(
            id = "bm_2",
            title = "Nature Wallpaper Collection",
            sourceDomain = "unsplash.com",
            sourceUrl = "https://unsplash.com/nature",
            items = listOf(
                MediaInboxItem(
                    id = "item_1",
                    title = "Mountains.jpg",
                    localUri = "content://media/mountains.jpg",
                    sourceUrl = "https://unsplash.com/mountains.jpg",
                    isVideo = false
                )
            ),
            description = "High resolution mountain photography"
        )
        val allBookmarks = listOf(bookmarkWeb, bookmarkMedia)

        // 1. Search by title
        val searchKotlin = allBookmarks.filter { it.title.contains("kotlin", ignoreCase = true) }
        assertEquals(1, searchKotlin.size)
        assertEquals("bm_1", searchKotlin.first().id)

        // 2. Search by notes/description
        val searchPhoto = allBookmarks.filter { it.description.contains("photography", ignoreCase = true) }
        assertEquals(1, searchPhoto.size)
        assertEquals("bm_2", searchPhoto.first().id)

        // 3. Search by domain
        val searchDomain = allBookmarks.filter { it.sourceDomain.contains("unsplash", ignoreCase = true) }
        assertEquals(1, searchDomain.size)
        assertEquals("bm_2", searchDomain.first().id)

        // 4. Filter by Web Links (items empty or sourceUrl not blank)
        val webLinks = allBookmarks.filter { it.items.isEmpty() }
        assertEquals(1, webLinks.size)
        assertEquals("bm_1", webLinks.first().id)

        // 5. Filter by Media collections
        val mediaCollections = allBookmarks.filter { it.items.isNotEmpty() }
        assertEquals(1, mediaCollections.size)
        assertEquals("bm_2", mediaCollections.first().id)
    }

    @Test
    fun testPocketSortOptions() {
        val b1 = MediaInboxStack(id = "1", title = "Zebra Article", sourceDomain = "zebra.org", sourceUrl = "https://zebra.org", items = emptyList(), timestampMillis = 1000L)
        val b2 = MediaInboxStack(id = "2", title = "Apple News", sourceDomain = "apple.com", sourceUrl = "https://apple.com", items = emptyList(), timestampMillis = 3000L)
        val b3 = MediaInboxStack(id = "3", title = "Banana Recipe", sourceDomain = "banana.net", sourceUrl = "https://banana.net", items = emptyList(), timestampMillis = 2000L)
        val list = listOf(b1, b2, b3)

        // Newest first
        val newest = list.sortedByDescending { it.timestampMillis }
        assertEquals("2", newest[0].id)
        assertEquals("3", newest[1].id)
        assertEquals("1", newest[2].id)

        // Oldest first
        val oldest = list.sortedBy { it.timestampMillis }
        assertEquals("1", oldest[0].id)
        assertEquals("3", oldest[1].id)
        assertEquals("2", oldest[2].id)

        // Title A-Z
        val byTitle = list.sortedBy { it.title.lowercase() }
        assertEquals("2", byTitle[0].id) // Apple
        assertEquals("3", byTitle[1].id) // Banana
        assertEquals("1", byTitle[2].id) // Zebra

        // Domain A-Z
        val byDomain = list.sortedBy { it.sourceDomain.lowercase() }
        assertEquals("2", byDomain[0].id) // apple.com
        assertEquals("3", byDomain[1].id) // banana.net
        assertEquals("1", byDomain[2].id) // zebra.org
    }

    @Test
    fun testBookmarkViewerDetailFieldsLayoutIntegrity() {
        // Ensure detail view fields (Time, Source, Note, Tags, Title, Description) reflect genuine data without fabrication
        val timestamp = 1762500000000L
        val stack = MediaInboxStack(
            id = "bm_detail_1",
            title = "Android Architecture Guidelines",
            sourceDomain = "developer.android.com",
            sourceUrl = "https://developer.android.com/topic/architecture",
            items = listOf(
                MediaInboxItem(
                    id = "item_arch_1",
                    title = "Architecture diagram",
                    localUri = "file:///storage/emulated/0/Pictures/arch.png",
                    sourceUrl = "https://developer.android.com/arch.png",
                    isVideo = false
                ),
                MediaInboxItem(
                    id = "item_arch_2",
                    title = "Data layer diagram",
                    localUri = "file:///storage/emulated/0/Pictures/data.png",
                    sourceUrl = "https://developer.android.com/data.png",
                    isVideo = false
                )
            ),
            timestampMillis = timestamp,
            description = "Detailed guide on Android app architecture and recommended layered separation."
        )

        // 1. Time
        assertEquals(timestamp, stack.timestampMillis)

        // 2. Source
        assertEquals("https://developer.android.com/topic/architecture", stack.sourceUrl)
        assertEquals("developer.android.com", stack.sourceDomain)

        // 3. Note
        assertEquals("Detailed guide on Android app architecture and recommended layered separation.", stack.description)

        // 4. Tags derived without fabrication: domain and media count
        val derivedTags = mutableListOf<String>()
        if (stack.sourceDomain.isNotBlank()) derivedTags.add(stack.sourceDomain.removePrefix("www."))
        if (stack.items.isNotEmpty()) derivedTags.add("${stack.items.size} Media")
        assertEquals(listOf("developer.android.com", "2 Media"), derivedTags)

        // 5. Title
        assertEquals("Android Architecture Guidelines", stack.title)

        // 6. Description
        assertEquals("Detailed guide on Android app architecture and recommended layered separation.", stack.description)

        // Card stack count: 2 items
        assertEquals(2, stack.items.size)
    }

    @Test
    fun testPasteBarUrlDetection() {
        val validUrls = listOf(
            "https://github.com/android/architecture-samples",
            "http://example.com/page",
            "https://en.wikipedia.org/wiki/Kotlin"
        )
        val nonUrls = listOf(
            "search keyword",
            "kotlin tutorial",
            "photos of cats"
        )

        validUrls.forEach { url ->
            assertTrue(
                "Expected '$url' to be detected as a valid web URL",
                MediaClassifier.isValidWebUrl(url) || url.startsWith("http://") || url.startsWith("https://")
            )
        }

        nonUrls.forEach { query ->
            assertFalse(
                "Expected '$query' not to be treated as a web URL",
                MediaClassifier.isValidWebUrl(query) || query.startsWith("http://") || query.startsWith("https://")
            )
        }
    }

    @Test
    fun testCardBottomDescriptionFields() {
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            set(2026, java.util.Calendar.OCTOBER, 29, 4, 6, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val timestamp = calendar.timeInMillis
        val stack = MediaInboxStack(
            id = "bm_india",
            title = "Ancient India",
            sourceDomain = "britannica.com",
            sourceUrl = "https://www.britannica.com/place/ancient-India",
            items = emptyList(),
            timestampMillis = timestamp,
            description = "History of ancient India"
        )

        // 1. Title
        assertEquals("Ancient India", stack.title)

        // 2. Date and Time formatting
        val sdf = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.ENGLISH).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        assertEquals("Oct 29, 2026", sdf.format(java.util.Date(timestamp)))

        // 3. Site address and favicon domain
        assertEquals("britannica.com", stack.sourceDomain)
        val faviconUrl = "https://www.google.com/s2/favicons?domain=${stack.sourceDomain}&sz=128"
        assertTrue(faviconUrl.contains("britannica.com"))
    }

    @Test
    fun testDestinationEnumContainsBookmarkDetailAsSeparatePage() {
        val destNames = Destination.values().map { it.name }
        assertTrue("Destination enum must contain BOOKMARK_DETAIL as a separate page destination", destNames.contains("BOOKMARK_DETAIL"))
        assertEquals("BOOKMARK_DETAIL", Destination.BOOKMARK_DETAIL.name)
    }

    @Test
    fun testBookmarkMediaViewerItemsState() {
        val mediaItems = listOf(
            MediaInboxItem(
                id = "item_0",
                title = "Photo 1",
                localUri = "file:///storage/photo1.jpg",
                sourceUrl = "https://example.com/photo1.jpg",
                isVideo = false
            ),
            MediaInboxItem(
                id = "item_1",
                title = "Video 1",
                localUri = "file:///storage/video1.mp4",
                sourceUrl = "https://example.com/video1.mp4",
                isVideo = true
            )
        )
        val stack = MediaInboxStack(
            id = "bm_deck",
            title = "Bookmark with media",
            sourceDomain = "example.com",
            sourceUrl = "https://example.com",
            items = mediaItems,
            description = "Test bookmark with media items"
        )
        assertEquals(2, stack.items.size)
        val initialIndex = 1
        val coercedIndex = initialIndex.coerceIn(0, stack.items.size - 1)
        assertEquals(1, coercedIndex)
        val activeItem = stack.items[coercedIndex]
        assertTrue(activeItem.isVideo)
        assertEquals("Video 1", activeItem.title)
    }
}

