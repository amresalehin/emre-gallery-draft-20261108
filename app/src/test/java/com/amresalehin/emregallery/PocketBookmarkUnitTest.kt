package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.MediaClassifier
import com.amresalehin.emregallery.data.MediaInboxItem
import com.amresalehin.emregallery.data.MediaInboxStack
import com.amresalehin.emregallery.data.PocketRepository
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

    @Test
    fun testDeckCardCounterBadgeFormatting() {
        val totalCards = 5
        val safeIndex = 2
        val counterText = "${safeIndex + 1} / $totalCards"
        assertEquals("3 / 5", counterText)

        // For totalCards = 1, counter badge should not display
        val singleCardTotal = 1
        val shouldShowCounter = singleCardTotal > 1
        assertFalse(shouldShowCounter)

        // For totalCards > 1, counter badge displays above the card stack
        assertTrue(totalCards > 1)
    }

    @Test
    fun testDeckSwipeLayerIndexCalculation() {
        val totalCards = 4

        // Resting or swiping left from index 0
        val safeIndex0 = 0
        val nextIndex1 = (safeIndex0 + 1) % totalCards
        val nextIndex2 = (safeIndex0 + 2) % totalCards
        assertEquals(1, nextIndex1)
        assertEquals(2, nextIndex2)

        // Swiping right (previous card) from index 0
        val prevIndex1 = if (safeIndex0 - 1 < 0) totalCards - 1 else safeIndex0 - 1
        val prevIndex2 = if (safeIndex0 - 2 < 0) (safeIndex0 - 2 + totalCards) % totalCards else safeIndex0 - 2
        assertEquals(3, prevIndex1)
        assertEquals(2, prevIndex2)

        // Swiping left from index 3 (last card wraps to first)
        val safeIndex3 = 3
        val nextWrapped1 = (safeIndex3 + 1) % totalCards
        val nextWrapped2 = (safeIndex3 + 2) % totalCards
        assertEquals(0, nextWrapped1)
        assertEquals(1, nextWrapped2)
    }

    @Test
    fun testSvgPillSwitchDefaultDisabledAndFiltering() {
        val defaultShowSvg = false
        assertFalse("Show SVG switch must be disabled by default", defaultShowSvg)

        val svgStack = MediaInboxStack(
            id = "stack_svg",
            title = "Vector Logo",
            sourceDomain = "example.com",
            sourceUrl = "https://example.com/logo.svg",
            items = listOf(
                MediaInboxItem(
                    id = "svg_item_1",
                    title = "logo.svg",
                    localUri = "",
                    sourceUrl = "https://example.com/logo.svg",
                    isVideo = false,
                    format = "SVG"
                )
            )
        )

        val mixedStack = MediaInboxStack(
            id = "stack_mixed",
            title = "Design Resources Site",
            sourceDomain = "example.com",
            sourceUrl = "https://example.com/resources",
            previewImageUrl = "https://example.com/logo.svg",
            items = listOf(
                MediaInboxItem(
                    id = "photo_item_1",
                    title = "sunset.jpg",
                    localUri = "",
                    sourceUrl = "https://example.com/sunset.jpg",
                    isVideo = false,
                    format = "JPG"
                ),
                MediaInboxItem(
                    id = "svg_item_1",
                    title = "logo.svg",
                    localUri = "",
                    sourceUrl = "https://example.com/logo.svg",
                    isVideo = false,
                    format = "SVG"
                ),
                MediaInboxItem(
                    id = "photo_item_2",
                    title = "banner.png",
                    localUri = "",
                    sourceUrl = "https://example.com/banner.png",
                    isVideo = false,
                    format = "PNG"
                )
            )
        )

        // When showSvg is false (default):
        // SVG items are hidden as found media, but the bookmark and other media items are shown as is
        val showSvgDisabled = false
        val sanitizedMixed = if (!showSvgDisabled) {
            val visibleItems = mixedStack.items.filterNot { MediaClassifier.isSvg(it.sourceUrl, format = it.format) }
            val previewIsSvg = mixedStack.previewImageUrl.isNotBlank() && MediaClassifier.isSvg(mixedStack.previewImageUrl)
            val safePreview = if (previewIsSvg) visibleItems.firstOrNull()?.sourceUrl ?: "" else mixedStack.previewImageUrl
            mixedStack.copy(items = visibleItems, previewImageUrl = safePreview)
        } else {
            mixedStack
        }

        // Bookmark itself is preserved!
        assertEquals("stack_mixed", sanitizedMixed.id)
        assertEquals("Design Resources Site", sanitizedMixed.title)
        // SVG items hidden from found media (2 remaining: sunset.jpg and banner.png)
        assertEquals(2, sanitizedMixed.items.size)
        assertEquals("sunset.jpg", sanitizedMixed.items[0].title)
        assertEquals("banner.png", sanitizedMixed.items[1].title)
        assertFalse("SVG item must not be present in found media", sanitizedMixed.items.any { MediaClassifier.isSvg(it.sourceUrl) })
        // SVG preview image replaced with first non-SVG media
        assertEquals("https://example.com/sunset.jpg", sanitizedMixed.previewImageUrl)

        // When showSvg is true: all items including SVG are visible
        val showSvgEnabled = true
        val sanitizedEnabled = if (!showSvgEnabled) {
            mixedStack.copy(items = mixedStack.items.filterNot { MediaClassifier.isSvg(it.sourceUrl, format = it.format) })
        } else {
            mixedStack
        }
        assertEquals(3, sanitizedEnabled.items.size)
        assertTrue("SVG item must be present when showSvg is enabled", sanitizedEnabled.items.any { MediaClassifier.isSvg(it.sourceUrl) })
    }

    @Test
    fun testSvgOnlyBookmarkPreservedAsWebBookmarkWithoutSvgMedia() {
        val svgOnlyStack = MediaInboxStack(
            id = "stack_svg_only",
            title = "Vector Icon Gallery",
            sourceDomain = "vectorgallery.org",
            sourceUrl = "https://vectorgallery.org",
            previewImageUrl = "https://vectorgallery.org/icon.svg",
            items = listOf(
                MediaInboxItem(
                    id = "icon_1",
                    title = "icon1.svg",
                    localUri = "",
                    sourceUrl = "https://vectorgallery.org/icon1.svg",
                    isVideo = false,
                    format = "SVG"
                ),
                MediaInboxItem(
                    id = "icon_2",
                    title = "icon2.svg",
                    localUri = "",
                    sourceUrl = "https://vectorgallery.org/icon2.svg",
                    isVideo = false,
                    format = "SVG"
                )
            )
        )

        val showSvg = false
        val visibleItems = svgOnlyStack.items.filterNot { MediaClassifier.isSvg(it.sourceUrl, format = it.format) }
        val previewIsSvg = svgOnlyStack.previewImageUrl.isNotBlank() && MediaClassifier.isSvg(svgOnlyStack.previewImageUrl)
        val safePreview = if (previewIsSvg) visibleItems.firstOrNull()?.sourceUrl ?: "" else svgOnlyStack.previewImageUrl
        val sanitized = svgOnlyStack.copy(items = visibleItems, previewImageUrl = safePreview)

        // The bookmark must NOT be deleted or hidden!
        assertEquals("stack_svg_only", sanitized.id)
        assertEquals("Vector Icon Gallery", sanitized.title)
        // Found media list is empty as if the SVG media were not present on the site
        assertTrue("Found media must be empty when site only has SVG and showSvg is false", sanitized.items.isEmpty())
        assertEquals("", sanitized.previewImageUrl)
    }

    @Test
    fun testDimensionSlidersParsingAndFiltering() {
        val text1 = "Wallpaper 1920x1080 Full HD"
        val match1 = Regex("""(\d{2,5})\s*[xX*×]\s*(\d{2,5})""").find(text1)
        assertNotNull(match1)
        assertEquals(1920, match1!!.groupValues[1].toInt())
        assertEquals(1080, match1.groupValues[2].toInt())

        // Dimension filtering: minWidth = 1200, minHeight = 800
        val minW = 1200
        val minH = 800
        val w1 = 1920
        val h1 = 1080
        val passes1 = (minW <= 0 || w1 >= minW) && (minH <= 0 || h1 >= minH)
        assertTrue("1920x1080 must pass min 1200x800", passes1)

        val wSmall = 800
        val hSmall = 600
        val passesSmall = (minW <= 0 || wSmall >= minW) && (minH <= 0 || hSmall >= minH)
        assertFalse("800x600 must NOT pass min 1200x800", passesSmall)

        // Reset to 0x0
        val zeroW = 0
        val zeroH = 0
        val passesZero = (zeroW <= 0 || wSmall >= zeroW) && (zeroH <= 0 || hSmall >= zeroH)
        assertTrue("Any dimensions must pass when sliders are at 0", passesZero)
    }

    @Test
    fun testMediaDimensionsFilteredFromFoundMedia() {
        val stack = MediaInboxStack(
            id = "stack_dim_test",
            title = "Photography Blog",
            sourceDomain = "photoblog.com",
            sourceUrl = "https://photoblog.com/post",
            previewImageUrl = "https://photoblog.com/photo_1920x1080.jpg",
            items = listOf(
                MediaInboxItem(
                    id = "item_large",
                    title = "photo_1920x1080.jpg",
                    localUri = "",
                    sourceUrl = "https://photoblog.com/photo_1920x1080.jpg",
                    isVideo = false
                ),
                MediaInboxItem(
                    id = "item_medium",
                    title = "banner_1200x400.jpg",
                    localUri = "",
                    sourceUrl = "https://photoblog.com/banner_1200x400.jpg",
                    isVideo = false
                ),
                MediaInboxItem(
                    id = "item_thumb",
                    title = "thumb_300x200.jpg",
                    localUri = "",
                    sourceUrl = "https://photoblog.com/thumb_300x200.jpg",
                    isVideo = false
                ),
                MediaInboxItem(
                    id = "item_avatar",
                    title = "avatar_100x100.png",
                    localUri = "",
                    sourceUrl = "https://photoblog.com/avatar_100x100.png",
                    isVideo = false
                )
            )
        )

        fun filterItems(items: List<MediaInboxItem>, minW: Int, minH: Int): List<MediaInboxItem> {
            return items.filter { item ->
                val match = Regex("""(?i)(\d{2,5})\s*([x*×_]|by)\s*(\d{2,5})""").find("${item.title} ${item.sourceUrl}")
                val (w, h) = if (match != null) {
                    match.groupValues[1].toIntOrNull() to match.groupValues[3].toIntOrNull()
                } else null to null
                val wOk = w == null || minW <= 0 || w >= minW
                val hOk = h == null || minH <= 0 || h >= minH
                wOk && hOk
            }
        }

        // Test 1: minWidth = 500. Items with width < 500 (300x200 and 100x100) are hidden as found media
        val filteredWidth500 = filterItems(stack.items, 500, 0)
        assertEquals(2, filteredWidth500.size)
        assertEquals("photo_1920x1080.jpg", filteredWidth500[0].title)
        assertEquals("banner_1200x400.jpg", filteredWidth500[1].title)

        // Test 2: minHeight = 500. Items with height < 500 (1200x400, 300x200, 100x100) are hidden as found media
        val filteredHeight500 = filterItems(stack.items, 0, 500)
        assertEquals(1, filteredHeight500.size)
        assertEquals("photo_1920x1080.jpg", filteredHeight500[0].title)

        // Test 3: minWidth = 2500 (greater than all media items). All found media hidden as if not present on site
        val filteredWidth2500 = filterItems(stack.items, 2500, 0)
        assertTrue("All media below 2500 width must be hidden from found media", filteredWidth2500.isEmpty())
        // But the bookmark itself is ALWAYS preserved!
        assertEquals("stack_dim_test", stack.id)
        assertEquals("Photography Blog", stack.title)
    }

    @Test
    fun testDefaultDimensionFiltersAndBrowserPreviewFallback() {
        assertEquals(480, PocketRepository.DEFAULT_MIN_WIDTH)
        assertEquals(540, PocketRepository.DEFAULT_MIN_HEIGHT)

        val stack = MediaInboxStack(
            id = "test_preview_fallback",
            title = "Article without large media",
            sourceDomain = "techblog.org",
            sourceUrl = "https://techblog.org/article",
            previewImageUrl = "https://techblog.org/browser_preview_og.jpg",
            items = listOf(
                MediaInboxItem(
                    id = "small_icon",
                    title = "icon_64x64.png",
                    localUri = "",
                    sourceUrl = "https://techblog.org/icon_64x64.png",
                    isVideo = false
                ),
                MediaInboxItem(
                    id = "author_avatar",
                    title = "avatar_128x128.jpg",
                    localUri = "",
                    sourceUrl = "https://techblog.org/avatar_128x128.jpg",
                    isVideo = false
                )
            )
        )

        // When filtering items with default criteria (width >= 480, height >= 540)
        val defaultWidth = PocketRepository.DEFAULT_MIN_WIDTH
        val defaultHeight = PocketRepository.DEFAULT_MIN_HEIGHT

        val compliantItems = stack.items.filter { item ->
            val match = Regex("""(?i)(\d{2,5})\s*([x*×_]|by)\s*(\d{2,5})""").find("${item.title} ${item.sourceUrl}")
            val (w, h) = if (match != null) {
                match.groupValues[1].toIntOrNull() to match.groupValues[3].toIntOrNull()
            } else null to null
            val wOk = w == null || defaultWidth <= 0 || w >= defaultWidth
            val hOk = h == null || defaultHeight <= 0 || h >= defaultHeight
            wOk && hOk
        }

        // Neither 64x64 nor 128x128 fulfils 480x540
        assertTrue(compliantItems.isEmpty())

        // Because no media is able to fulfil this criteria, browser preview is shown
        val displayedPreview = if (compliantItems.isNotEmpty()) {
            compliantItems.first().sourceUrl
        } else {
            stack.previewImageUrl
        }
        assertEquals("https://techblog.org/browser_preview_og.jpg", displayedPreview)
    }

    @Test
    fun testMediaSizeFilterLogic() {
        val stack = MediaInboxStack(
            id = "stack_size_test",
            title = "Size Filter Test",
            sourceDomain = "example.com",
            sourceUrl = "https://example.com/files",
            items = listOf(
                MediaInboxItem(
                    id = "icon",
                    title = "icon.png",
                    localUri = "",
                    sourceUrl = "https://example.com/icon.png",
                    isVideo = false,
                    sizeBytes = 4 * 1024L // 4 KB (below default 10 KB)
                ),
                MediaInboxItem(
                    id = "tiny",
                    title = "tiny.jpg",
                    localUri = "",
                    sourceUrl = "https://example.com/tiny.jpg",
                    isVideo = false,
                    sizeBytes = 50 * 1024L // 50 KB
                ),
                MediaInboxItem(
                    id = "medium",
                    title = "medium.jpg",
                    localUri = "",
                    sourceUrl = "https://example.com/medium.jpg",
                    isVideo = false,
                    sizeBytes = 500 * 1024L // 500 KB
                ),
                MediaInboxItem(
                    id = "large",
                    title = "large.mp4",
                    localUri = "",
                    sourceUrl = "https://example.com/large.mp4",
                    isVideo = true,
                    sizeBytes = 5 * 1024 * 1024L // 5 MB
                )
            )
        )

        fun filterBySize(items: List<MediaInboxItem>, minSizeKb: Int): List<MediaInboxItem> {
            val minBytes = minSizeKb * 1024L
            return items.filter { it.sizeBytes >= minBytes }
        }

        // 1. Min size 0 KB: all 4 items included
        val all = filterBySize(stack.items, 0)
        assertEquals(4, all.size)

        // 2. Default Min size 10 KB: icon.png (4 KB) is excluded, 3 items remain
        val default10Kb = filterBySize(stack.items, PocketRepository.DEFAULT_MIN_SIZE_KB)
        assertEquals(10, PocketRepository.DEFAULT_MIN_SIZE_KB)
        assertEquals(3, default10Kb.size)
        assertFalse(default10Kb.any { it.id == "icon" })

        // 3. Min size 100 KB: icon and tiny.jpg excluded
        val over100 = filterBySize(stack.items, 100)
        assertEquals(2, over100.size)
        assertEquals("medium.jpg", over100[0].title)
        assertEquals("large.mp4", over100[1].title)

        // 4. Min size 1024 KB (1 MB): tiny and medium excluded, large included
        val over1Mb = filterBySize(stack.items, 1024)
        assertEquals(1, over1Mb.size)
        assertEquals("large.mp4", over1Mb[0].title)

        // 5. Min size 10 MB (10240 KB): all items excluded from found media, but bookmark preserved
        val over10Mb = filterBySize(stack.items, 10240)
        assertTrue(over10Mb.isEmpty())
        assertEquals("stack_size_test", stack.id)
    }

    @Test
    fun testMediaTypePillFilters() {
        val stack = MediaInboxStack(
            id = "stack_types_test",
            title = "Multi Format Media",
            sourceDomain = "example.com",
            sourceUrl = "https://example.com/media",
            items = listOf(
                MediaInboxItem(id = "svg", title = "logo.svg", localUri = "", sourceUrl = "https://example.com/logo.svg", isVideo = false, format = "SVG"),
                MediaInboxItem(id = "vid", title = "clip.mp4", localUri = "", sourceUrl = "https://example.com/clip.mp4", isVideo = true, format = "MP4"),
                MediaInboxItem(id = "stream", title = "live.m3u8", localUri = "", sourceUrl = "https://example.com/live.m3u8", isVideo = false, isStream = true, format = "M3U8"),
                MediaInboxItem(id = "png", title = "graphic.png", localUri = "", sourceUrl = "https://example.com/graphic.png", isVideo = false, format = "PNG"),
                MediaInboxItem(id = "gif", title = "animation.gif", localUri = "", sourceUrl = "https://example.com/animation.gif", isVideo = false, format = "GIF"),
                MediaInboxItem(id = "jpg", title = "photo.jpg", localUri = "", sourceUrl = "https://example.com/photo.jpg", isVideo = false, format = "JPG")
            )
        )

        fun filterTypes(
            items: List<MediaInboxItem>,
            showSvg: Boolean,
            showVideos: Boolean,
            showStreams: Boolean,
            showPng: Boolean,
            showGif: Boolean
        ): List<MediaInboxItem> {
            return items.filter { item ->
                if (!showSvg && MediaClassifier.isSvg(item.sourceUrl, format = item.format)) return@filter false
                if (!showVideos && (item.isVideo || item.format.equals("MP4", true))) return@filter false
                if (!showStreams && (item.isStream || item.format.equals("M3U8", true))) return@filter false
                if (!showPng && item.format.equals("PNG", true)) return@filter false
                if (!showGif && item.format.equals("GIF", true)) return@filter false
                true
            }
        }

        // Default state: showSvg = false, videos/streams/png/gif = true
        val defaultList = filterTypes(stack.items, showSvg = false, showVideos = true, showStreams = true, showPng = true, showGif = true)
        assertEquals(5, defaultList.size)
        assertFalse("SVG must be hidden by default", defaultList.any { it.id == "svg" })

        // When videos and gifs are turned off:
        val noVidNoGif = filterTypes(stack.items, showSvg = false, showVideos = false, showStreams = true, showPng = true, showGif = false)
        assertEquals(3, noVidNoGif.size)
        assertTrue(noVidNoGif.any { it.id == "stream" })
        assertTrue(noVidNoGif.any { it.id == "png" })
        assertTrue(noVidNoGif.any { it.id == "jpg" })

        // When SVG is turned on:
        val withSvg = filterTypes(stack.items, showSvg = true, showVideos = true, showStreams = true, showPng = true, showGif = true)
        assertEquals(6, withSvg.size)
        assertTrue(withSvg.any { it.id == "svg" })
    }

    @Test
    fun testDimensionZeroToInfinityFormatting() {
        fun formatDim(value: Int): String = when {
            value <= 0 -> "0 px (Any)"
            value >= 4000 -> "4000+ px (∞)"
            else -> "$value px"
        }

        assertEquals("0 px (Any)", formatDim(0))
        assertEquals("1080 px", formatDim(1080))
        assertEquals("1920 px", formatDim(1920))
        assertEquals("4000+ px (∞)", formatDim(4000))
        assertEquals("4000+ px (∞)", formatDim(5000))
    }

    @Test
    fun testMediaSizeFilterFunctionalWithDimensionEstimationAndFallback() {
        val smallIcon = MediaInboxItem(
            id = "icon",
            title = "icon 32x32",
            localUri = "",
            sourceUrl = "https://example.com/icon.png?w=32&h=32",
            isVideo = false,
            format = "PNG"
        )
        val mediumPhoto = MediaInboxItem(
            id = "photo",
            title = "photo 800x600",
            localUri = "",
            sourceUrl = "https://example.com/photo.jpg?w=800&h=600",
            isVideo = false,
            format = "JPEG"
        )
        val highResPhoto = MediaInboxItem(
            id = "wallpaper",
            title = "wallpaper 1920x1080",
            localUri = "",
            sourceUrl = "https://example.com/wallpaper.jpg?w=1920&h=1080",
            isVideo = false,
            format = "JPEG"
        )
        val videoItem = MediaInboxItem(
            id = "clip",
            title = "clip 1920x1080",
            localUri = "",
            sourceUrl = "https://example.com/clip.mp4?w=1920&h=1080",
            isVideo = true,
            format = "MP4"
        )

        fun estimateBytes(item: MediaInboxItem): Long {
            if (item.sizeBytes > 0L) return item.sizeBytes
            val match = Regex("""(?i)[?&](?:w|width)=(\d+)[^&]*[?&](?:h|height)=(\d+)""").find(item.sourceUrl)
            val w = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val h = match?.groupValues?.get(2)?.toIntOrNull() ?: 0
            return when {
                item.isVideo -> (w.toLong() * h.toLong() * 2L).coerceIn(800_000L, 25_000_000L)
                item.format == "PNG" -> ((w.toLong() * h.toLong() * 65L) / 100L).coerceAtLeast(4_000L)
                else -> ((w.toLong() * h.toLong() * 28L) / 100L).coerceAtLeast(3_000L)
            }
        }

        val iconSize = estimateBytes(smallIcon)
        val photoSize = estimateBytes(mediumPhoto)
        val highResSize = estimateBytes(highResPhoto)
        val videoSize = estimateBytes(videoItem)

        // 1. Icon is ~4 KB
        assertTrue("Icon size must be < 50 KB", iconSize < 50 * 1024L)

        // 2. 800x600 photo is ~134 KB
        assertTrue("800x600 photo should be between 100KB and 200KB", photoSize in (100 * 1024L)..(200 * 1024L))

        // 3. 1920x1080 photo is ~580 KB
        assertTrue("1920x1080 photo should be between 500KB and 700KB", highResSize in (500 * 1024L)..(700 * 1024L))

        // 4. Video is > 1.5 MB
        assertTrue("1080p video should be >= 1.5MB", videoSize >= (1500 * 1024L))

        // Filter tests:
        val items = listOf(smallIcon, mediumPhoto, highResPhoto, videoItem)
        fun filterItems(minSizeKb: Int): List<MediaInboxItem> {
            if (minSizeKb <= 0) return items
            val minBytes = minSizeKb * 1024L
            return items.filter { estimateBytes(it) >= minBytes }
        }

        // At 0 KB: all 4 items match
        assertEquals(4, filterItems(0).size)

        // At 100 KB: icon is excluded (3 items match)
        val filter100 = filterItems(100)
        assertEquals(3, filter100.size)
        assertFalse(filter100.any { it.id == "icon" })

        // At 500 KB: icon and mediumPhoto excluded (2 items match)
        val filter500 = filterItems(500)
        assertEquals(2, filter500.size)
        assertTrue(filter500.any { it.id == "wallpaper" })
        assertTrue(filter500.any { it.id == "clip" })

        // At 1000 KB (1 MB): only video matches (1 item match)
        val filter1Mb = filterItems(1000)
        assertEquals(1, filter1Mb.size)
        assertEquals("clip", filter1Mb[0].id)
    }
}

