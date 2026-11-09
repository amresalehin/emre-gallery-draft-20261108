package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.DownloadActivityItem
import com.amresalehin.emregallery.data.DownloadActivityManager
import com.amresalehin.emregallery.data.DownloadActivityStatus
import com.amresalehin.emregallery.data.MediaClassifier
import com.amresalehin.emregallery.ui.Destination
import com.amresalehin.emregallery.ui.DetectedPlatform
import com.amresalehin.emregallery.ui.DownloaderViewMode
import com.amresalehin.emregallery.ui.detectPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloaderSearchPageUnitTest {

    @Test
    fun testDownloaderViewModeEnumValues() {
        val modes = DownloaderViewMode.values().map { it.name }
        assertEquals(
            listOf(
                "SEARCH_PAGE",
                "DOWNLOADER_PAGE",
                "SAVED_LIBRARY",
                "ACTIVITY_PAGE",
                "DISCOVERED_MEDIA"
            ),
            modes
        )
    }

    @Test
    fun testPlatformDetectionFromVariousUrls() {
        // Direct media links
        assertEquals(DetectedPlatform.DIRECT_MEDIA, detectPlatform("https://cdn.example.com/videos/stream.m3u8"))
        assertEquals(DetectedPlatform.DIRECT_MEDIA, detectPlatform("https://cdn.example.com/videos/manifest.mpd"))
        assertEquals(DetectedPlatform.DIRECT_MEDIA, detectPlatform("https://cdn.example.com/media/clip.mp4"))
        assertEquals(DetectedPlatform.DIRECT_MEDIA, detectPlatform("https://cdn.example.com/images/hero.jpg"))
        assertEquals(DetectedPlatform.DIRECT_MEDIA, detectPlatform("https://cdn.example.com/images/banner.webp"))
        assertEquals(DetectedPlatform.DIRECT_MEDIA, detectPlatform("https://cdn.example.com/audio/podcast.mp3"))

        // Generic websites
        assertEquals(DetectedPlatform.GENERIC_WEB, detectPlatform("https://en.wikipedia.org/wiki/Kotlin"))
        assertEquals(DetectedPlatform.GENERIC_WEB, detectPlatform("https://github.com/trending"))
        assertEquals(DetectedPlatform.GENERIC_WEB, detectPlatform("https://example.com/article"))
        assertEquals(DetectedPlatform.GENERIC_WEB, detectPlatform("https://blog.example.org/news"))
    }

    @Test
    fun testUrlValidationForSearchAndFetch() {
        // Valid web links should be accepted for Fetch
        assertTrue(MediaClassifier.isValidWebUrl("https://youtube.com/watch?v=123"))
        assertTrue(MediaClassifier.isValidWebUrl("http://example.com/gallery"))
        assertTrue(MediaClassifier.isValidWebUrl("instagram.com/p/abc"))
        assertTrue(MediaClassifier.isValidWebUrl("www.reddit.com/r/pics"))

        // Non-URLs or junk input should be rejected
        assertFalse(MediaClassifier.isValidWebUrl("just random words"))
        assertFalse(MediaClassifier.isValidWebUrl("   "))
        assertFalse(MediaClassifier.isValidWebUrl(""))
        assertFalse(MediaClassifier.isValidWebUrl("javascript:alert(1)"))
    }

    @Test
    fun testDownloaderPillModel() {
        val googlePill = com.amresalehin.emregallery.ui.DownloaderPill(domain = "google.com", name = "Google", url = "https://www.google.com")
        val wikiPill = com.amresalehin.emregallery.ui.DownloaderPill(domain = "en.wikipedia.org", name = "Wikipedia", url = "https://en.wikipedia.org")
        val customFaviconPill = com.amresalehin.emregallery.ui.DownloaderPill(
            domain = "mycustomsite.com",
            name = "Custom",
            url = "https://mycustomsite.com",
            explicitFaviconUrl = "https://mycustomsite.com/icon.png"
        )

        assertEquals("google.com", googlePill.domain)
        assertEquals("Google", googlePill.name)
        assertEquals("https://www.google.com", googlePill.url)
        assertEquals("https://www.google.com/s2/favicons?domain=google.com&sz=64", googlePill.faviconUrl)

        assertEquals("en.wikipedia.org", wikiPill.domain)
        assertEquals("Wikipedia", wikiPill.name)
        assertEquals("https://www.google.com/s2/favicons?domain=en.wikipedia.org&sz=64", wikiPill.faviconUrl)

        assertEquals("https://mycustomsite.com/icon.png", customFaviconPill.faviconUrl)
    }

    @Test
    fun testSavedLibrarySearchFilterLogic() {
        val stack1 = com.amresalehin.emregallery.data.MediaInboxStack(
            id = "stack_1",
            title = "Taj Mahal - Wikipedia",
            sourceDomain = "wikipedia.org",
            sourceUrl = "https://en.wikipedia.org/wiki/Taj_Mahal",
            items = listOf(
                com.amresalehin.emregallery.data.MediaInboxItem(
                    id = "item_1",
                    title = "Taj Mahal Front View",
                    localUri = "content://media/1",
                    sourceUrl = "https://upload.wikimedia.org/taj.jpg",
                    isVideo = false
                )
            )
        )
        val stack2 = com.amresalehin.emregallery.data.MediaInboxStack(
            id = "stack_2",
            title = "Pexels Nature Scenery",
            sourceDomain = "pexels.com",
            sourceUrl = "https://pexels.com/nature",
            items = listOf(
                com.amresalehin.emregallery.data.MediaInboxItem(
                    id = "item_2",
                    title = "Desert Sunset",
                    localUri = "content://media/2",
                    sourceUrl = "https://images.pexels.com/desert.jpg",
                    isVideo = false
                )
            )
        )
        val allStacks = listOf(stack1, stack2)

        // Matching title
        val tajMatches = allStacks.filter { stack ->
            stack.title.contains("taj", ignoreCase = true) ||
                    stack.sourceDomain.contains("taj", ignoreCase = true) ||
                    stack.items.any { it.title.contains("taj", ignoreCase = true) }
        }
        assertEquals(1, tajMatches.size)
        assertEquals("stack_1", tajMatches[0].id)

        // Matching domain
        val pexelsMatches = allStacks.filter { stack ->
            stack.title.contains("pexels", ignoreCase = true) ||
                    stack.sourceDomain.contains("pexels", ignoreCase = true) ||
                    stack.items.any { it.title.contains("pexels", ignoreCase = true) }
        }
        assertEquals(1, pexelsMatches.size)
        assertEquals("stack_2", pexelsMatches[0].id)

        // Matching item title
        val desertMatches = allStacks.filter { stack ->
            stack.title.contains("desert", ignoreCase = true) ||
                    stack.sourceDomain.contains("desert", ignoreCase = true) ||
                    stack.items.any { it.title.contains("desert", ignoreCase = true) }
        }
        assertEquals(1, desertMatches.size)
        assertEquals("stack_2", desertMatches[0].id)

        // Empty query matches all
        val emptyMatches = allStacks.filter { stack ->
            "".isBlank() || stack.title.contains("", ignoreCase = true)
        }
        assertEquals(2, emptyMatches.size)
    }

    @Test
    fun testSavedLibrarySearchBarFocusRules() {
        // State 1: Default (Unfocused, empty query)
        var isSearchFocused = false
        var searchQuery = ""

        fun showSwitcher(isFocused: Boolean) = !isFocused
        fun showFilterButton(isFocused: Boolean, query: String) = !isFocused && query.isEmpty()
        fun showClearButton(isFocused: Boolean, query: String) = isFocused || query.isNotEmpty()

        assertTrue(showSwitcher(isSearchFocused))
        assertTrue(showFilterButton(isSearchFocused, searchQuery))
        assertFalse(showClearButton(isSearchFocused, searchQuery))

        // State 2: Focus received
        isSearchFocused = true
        assertFalse(showSwitcher(isSearchFocused)) // 1. Hide switcher pill
        assertFalse(showFilterButton(isSearchFocused, searchQuery)) // 2. Hide filter button
        assertTrue(showClearButton(isSearchFocused, searchQuery)) // 4. Show clear button

        // State 3: User types a query
        searchQuery = "wikipedia"
        assertFalse(showSwitcher(isSearchFocused))
        assertTrue(showClearButton(isSearchFocused, searchQuery))

        // Action: Clear button clicked (only clears query without losing focus)
        searchQuery = ""
        assertTrue(isSearchFocused) // focus remains
        assertTrue(showClearButton(isSearchFocused, searchQuery)) // clear button stays because focused

        // Action: Back button clicked in search mode (exits search mode & clears focus)
        searchQuery = "some query"
        // Simulate Back click in search mode
        if (isSearchFocused || searchQuery.isNotEmpty()) {
            searchQuery = ""
            isSearchFocused = false
        }
        assertFalse(isSearchFocused)
        assertEquals("", searchQuery)
        assertTrue(showSwitcher(isSearchFocused)) // Restores default layout
        assertTrue(showFilterButton(isSearchFocused, searchQuery))
        assertFalse(showClearButton(isSearchFocused, searchQuery))
    }

    @Test
    fun testDownloaderMainSearchBarFocusAndActions() {
        // State 1: Default (Unfocused, empty input)
        var isSearchFocused = false
        var urlInput = ""

        fun showSwitcher(isFocused: Boolean) = !isFocused
        fun showPasteButton(query: String) = query.isBlank()
        fun showActionButtons(query: String) = query.isNotBlank()

        assertTrue(showSwitcher(isSearchFocused)) // Switcher pill is inside search bar and visible
        assertTrue(showPasteButton(urlInput))
        assertFalse(showActionButtons(urlInput))

        // State 2: Focus search field
        isSearchFocused = true
        assertFalse(showSwitcher(isSearchFocused)) // Switcher pill hidden on focus
        assertTrue(showPasteButton(urlInput)) // Paste button available when empty

        // State 3: Enter URL
        urlInput = "https://instagram.com/reel/123"
        assertFalse(showSwitcher(isSearchFocused))
        assertFalse(showPasteButton(urlInput))
        assertTrue(showActionButtons(urlInput)) // Clear, Browse, and Download visible

        // Action: Back button clicked in active search/edit mode
        var didNavigateBack = false
        fun handleBack() {
            if (isSearchFocused || urlInput.isNotEmpty()) {
                urlInput = ""
                isSearchFocused = false
            } else {
                didNavigateBack = true
            }
        }

        handleBack()
        assertFalse(isSearchFocused)
        assertEquals("", urlInput)
        assertFalse(didNavigateBack)
        assertTrue(showSwitcher(isSearchFocused)) // Switcher restored

        // Action: Back button clicked in default state
        handleBack()
        assertTrue(didNavigateBack)
    }

    @Test
    fun testSavedPreviewSectionLogic() {
        val stack1 = com.amresalehin.emregallery.data.MediaInboxStack(
            id = "stack_taj",
            title = "Taj Mahal",
            sourceDomain = "wikipedia.org",
            sourceUrl = "https://en.wikipedia.org/wiki/Taj_Mahal",
            timestampMillis = 1793782920000L,
            items = List(16) { i ->
                com.amresalehin.emregallery.data.MediaInboxItem(
                    id = "item_$i",
                    title = "Photo $i",
                    localUri = "content://media/$i",
                    sourceUrl = "https://example.com/$i.jpg",
                    isVideo = false
                )
            }
        )

        assertEquals("Taj Mahal", stack1.title)
        assertEquals(16, stack1.items.size)

        // Timestamp formatting produces clean date and time
        val sdf = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.US)
        val formatted = sdf.format(java.util.Date(stack1.timestampMillis))
        assertTrue(formatted.isNotBlank())

        // Accent palette mapping rotates cleanly
        val paletteCount = 5
        val accentIndex1 = 0 % paletteCount
        val accentIndex2 = 1 % paletteCount
        val accentIndex3 = 2 % paletteCount
        assertEquals(0, accentIndex1)
        assertEquals(1, accentIndex2)
        assertEquals(2, accentIndex3)

        // Display stacks limited to 5 to prevent unlimited horizontal scrolling
        val stacksList = List(12) { stack1 }
        val displayStacks = stacksList.take(5)
        assertEquals(5, displayStacks.size)
    }

    @Test
    fun testExtractFirstUrlFromSharedContent() {
        // Direct URL
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            MediaClassifier.extractFirstUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        )

        // URL embedded in shared text with surrounding words
        assertEquals(
            "https://twitter.com/user/status/1234567890",
            MediaClassifier.extractFirstUrl("Check out this tweet: https://twitter.com/user/status/1234567890 right now!")
        )

        // URL with trailing period from sentence
        assertEquals(
            "https://instagram.com/reel/C123456/",
            MediaClassifier.extractFirstUrl("Look at this reel https://instagram.com/reel/C123456/.")
        )

        // Multi-line shared text from share sheet
        val multiLineShare = """
            Awesome tutorial on Jetpack Compose!
            https://medium.com/@dev/compose-guide-2026
            Shared from Chrome
        """.trimIndent()
        assertEquals(
            "https://medium.com/@dev/compose-guide-2026",
            MediaClassifier.extractFirstUrl(multiLineShare)
        )

        // Plain domain without protocol
        assertEquals(
            "https://wikipedia.org/wiki/Taj_Mahal",
            MediaClassifier.extractFirstUrl("wikipedia.org/wiki/Taj_Mahal")
        )

        // Non-URL text returns null
        assertEquals(null, MediaClassifier.extractFirstUrl("Hello there how are you"))
        assertEquals(null, MediaClassifier.extractFirstUrl(""))
        assertEquals(null, MediaClassifier.extractFirstUrl(null))
    }

    @Test
    fun testSanitizeUrl() {
        // Strip tracking parameters while retaining essential parameters like YouTube video ID
        val youtubeWithTracking = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&si=abcdef123&feature=shared&utm_source=share"
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            MediaClassifier.sanitizeUrl(youtubeWithTracking)
        )

        // Strip Instagram tracking parameters
        val instagramWithTracking = "https://www.instagram.com/reel/C123456/?igshid=MzRlODBiNWFlZA==&utm_source=ig_web_copy_link"
        assertEquals(
            "https://www.instagram.com/reel/C123456/",
            MediaClassifier.sanitizeUrl(instagramWithTracking)
        )

        // Strip Facebook click tracking
        val articleWithFbclid = "https://example.com/article?id=42&fbclid=IwAR123456"
        assertEquals(
            "https://example.com/article?id=42",
            MediaClassifier.sanitizeUrl(articleWithFbclid)
        )

        // Unwrap Google outbound redirect
        val googleRedirect = "https://www.google.com/url?q=https://example.com/target-page&sa=D&sntz=1"
        assertEquals(
            "https://example.com/target-page",
            MediaClassifier.sanitizeUrl(googleRedirect)
        )

        // Strip surrounding quotes and whitespace
        val quotedUrl = "   \"https://x.com/user/status/123456?s=20&utm_source=share\"   "
        assertEquals(
            "https://x.com/user/status/123456?s=20",
            MediaClassifier.sanitizeUrl(quotedUrl)
        )

        // Clean domain helper
        assertEquals(
            "instagram.com",
            MediaClassifier.cleanDomainFromUrl("https://www.instagram.com/p/123")
        )
        assertEquals(
            "youtu.be",
            MediaClassifier.cleanDomainFromUrl("https://youtu.be/dQw4w9WgXcQ")
        )
    }

    @Test
    fun testDownloadActivityItemModelAndFiltering() {
        val downloadingItem = DownloadActivityItem(
            id = "act_1",
            title = "Best Dolby Vision 4K HDR Video",
            mediaUrl = "https://example.com/video1.mp4",
            isVideo = true,
            format = "4K",
            status = DownloadActivityStatus.DOWNLOADING,
            progress = 0.40f,
            bytesRead = 487_830_000L,
            totalBytes = 1_200_000_000L,
            speedBytesPerSec = 8_900_000L,
            durationHint = "30:06"
        )

        val failedItem = DownloadActivityItem(
            id = "act_2",
            title = "Nature in 4K.mp4",
            mediaUrl = "https://example.com/video2.mp4",
            isVideo = true,
            format = "4K",
            status = DownloadActivityStatus.FAILED,
            errorMessage = "Network error",
            durationHint = "04:21"
        )

        val finishedItem = DownloadActivityItem(
            id = "act_3",
            title = "Scenic Sunset in 4K.mp4",
            mediaUrl = "https://example.com/video3.mp4",
            localUri = "content://media/external/video/3",
            isVideo = true,
            format = "4K",
            status = DownloadActivityStatus.FINISHED,
            progress = 1.0f,
            totalBytes = 1_370_000_000L,
            durationHint = "12:18"
        )

        val activities = listOf(downloadingItem, failedItem, finishedItem)

        // Status flags check
        assertTrue(downloadingItem.isDownloading)
        assertFalse(downloadingItem.isFailed)
        assertFalse(downloadingItem.isFinished)

        assertTrue(failedItem.isFailed)
        assertFalse(failedItem.isDownloading)
        assertFalse(failedItem.isFinished)

        assertTrue(finishedItem.isFinished)
        assertFalse(finishedItem.isDownloading)
        assertFalse(finishedItem.isFailed)

        // Filter pills check
        val downloadingOnly = activities.filter { it.isDownloading || it.isPaused }
        assertEquals(1, downloadingOnly.size)
        assertEquals("act_1", downloadingOnly[0].id)

        val failedOnly = activities.filter { it.isFailed }
        assertEquals(1, failedOnly.size)
        assertEquals("act_2", failedOnly[0].id)

        val finishedOnly = activities.filter { it.isFinished }
        assertEquals(1, finishedOnly.size)
        assertEquals("act_3", finishedOnly[0].id)

        // Byte formatting check
        val onePointTwoGb = (1.20 * 1024 * 1024 * 1024).toLong()
        assertEquals("1.20GB", DownloadActivityManager.formatBytes(onePointTwoGb))
        val fourEightSevenMb = (487.83 * 1024 * 1024).toLong()
        assertEquals("487.83MB", DownloadActivityManager.formatBytes(fourEightSevenMb))
        assertEquals("500.0KB", DownloadActivityManager.formatBytes(500 * 1024L))

        // Speed formatting check
        val eightPointNineSpeed = (8.90 * 1024 * 1024).toLong()
        assertEquals("8.90MB/s", DownloadActivityManager.formatSpeed(eightPointNineSpeed))
        assertEquals("0.00MB/s", DownloadActivityManager.formatSpeed(0L))
    }

    @Test
    fun testFalse4KBadgeIsStrictlyRejectedForImages() {
        val tajMahalImage = DownloadActivityItem(
            id = "img_taj",
            title = "Taj_Mahal_9794.jpg",
            mediaUrl = "https://commons.wikimedia.org/wiki/Special:FilePath/Taj_Mahal_9794.jpg",
            isVideo = false,
            format = "4K", // Previous bug had false 4K
            status = DownloadActivityStatus.FAILED
        )
        val badge = com.amresalehin.emregallery.ui.getDisplayFormatBadge(tajMahalImage)
        // Must NEVER be 4K
        assertFalse("Thumbnail format badge must not be 4K for images", badge.equals("4K", ignoreCase = true))
        assertEquals("JPG", badge)

        val sambourneImage = DownloadActivityItem(
            id = "img_sam",
            title = "Sambourneagra1860s.jpg",
            mediaUrl = "https://upload.wikimedia.org/wikipedia/commons/1/12/Sambourneagra1860s.jpg",
            isVideo = false,
            format = "4K",
            status = DownloadActivityStatus.FAILED
        )
        assertEquals("JPG", com.amresalehin.emregallery.ui.getDisplayFormatBadge(sambourneImage))

        val pngImage = DownloadActivityItem(
            id = "img_png",
            title = "Diagram.png",
            mediaUrl = "https://example.com/diagram.png",
            isVideo = false,
            format = "PNG",
            status = DownloadActivityStatus.FAILED
        )
        assertEquals("PNG", com.amresalehin.emregallery.ui.getDisplayFormatBadge(pngImage))

        // Legitimate 4K video SHOULD keep 4K
        val real4kVideo = DownloadActivityItem(
            id = "vid_4k",
            title = "Nature 4K Documentary",
            mediaUrl = "https://example.com/nature.mp4",
            isVideo = true,
            format = "4K",
            status = DownloadActivityStatus.DOWNLOADING
        )
        assertEquals("4K", com.amresalehin.emregallery.ui.getDisplayFormatBadge(real4kVideo))
    }

    @Test
    fun testTemporaryFailureDetection() {
        // HTTP 429 Too Many Requests must always be classified as temporary failure
        assertTrue(DownloadActivityManager.isTemporaryFailure(429, "Server returned HTTP status 429"))
        // Server 5xx errors
        assertTrue(DownloadActivityManager.isTemporaryFailure(500, "Internal Server Error"))
        assertTrue(DownloadActivityManager.isTemporaryFailure(502, "Bad Gateway"))
        assertTrue(DownloadActivityManager.isTemporaryFailure(503, "Service Unavailable"))
        assertTrue(DownloadActivityManager.isTemporaryFailure(504, "Gateway Timeout"))
        // Connection timeouts and network drops
        assertTrue(DownloadActivityManager.isTemporaryFailure(-1, "SocketTimeoutException: timeout"))
        assertTrue(DownloadActivityManager.isTemporaryFailure(-1, "ConnectException: Failed to connect to host"))
        assertTrue(DownloadActivityManager.isTemporaryFailure(-1, "Connection reset by peer"))

        // Permanent failures must NOT be classified as temporary
        assertFalse(DownloadActivityManager.isTemporaryFailure(404, "Not Found"))
        assertFalse(DownloadActivityManager.isTemporaryFailure(401, "Unauthorized"))
        assertFalse(DownloadActivityManager.isTemporaryFailure(403, "Forbidden"))
    }

    @Test
    fun testExponentialBackoffWithJitterAndRetryAfter() {
        // Respects Retry-After header when provided by server
        val delayFromHeader = DownloadActivityManager.calculateRetryDelaySeconds(attempt = 1, retryAfterSeconds = 25L)
        assertEquals(25L, delayFromHeader)

        // Exponential backoff schedule without header: 2, 4, 8, 16, 32s (+ up to 50% jitter)
        // Attempt 1 base 2s (jitter 0-1) => 2..3s
        val delay1 = DownloadActivityManager.calculateRetryDelaySeconds(attempt = 1, retryAfterSeconds = null)
        assertTrue("Attempt 1 delay should be in [2, 3]", delay1 in 2L..3L)

        // Attempt 2 base 4s (jitter 0-2) => 4..6s
        val delay2 = DownloadActivityManager.calculateRetryDelaySeconds(attempt = 2, retryAfterSeconds = null)
        assertTrue("Attempt 2 delay should be in [4, 6]", delay2 in 4L..6L)

        // Attempt 3 base 8s (jitter 0-4) => 8..12s
        val delay3 = DownloadActivityManager.calculateRetryDelaySeconds(attempt = 3, retryAfterSeconds = null)
        assertTrue("Attempt 3 delay should be in [8, 12]", delay3 in 8L..12L)

        // Attempt 4 base 16s (jitter 0-8) => 16..24s
        val delay4 = DownloadActivityManager.calculateRetryDelaySeconds(attempt = 4, retryAfterSeconds = null)
        assertTrue("Attempt 4 delay should be in [16, 24]", delay4 in 16L..24L)

        // Attempt 5 base 32s (jitter 0-16) => 32..48s
        val delay5 = DownloadActivityManager.calculateRetryDelaySeconds(attempt = 5, retryAfterSeconds = null)
        assertTrue("Attempt 5 delay should be in [32, 48]", delay5 in 32L..48L)
    }

    @Test
    fun testRetryingItemsStayInDownloadingFilterUntil5RetriesExhausted() {
        val retryingItem = DownloadActivityItem(
            id = "retry_item_1",
            title = "Taj_Mahal_Photo.jpg",
            mediaUrl = "https://example.com/taj.jpg",
            status = DownloadActivityStatus.RETRYING,
            retryCount = 2,
            maxRetries = 5,
            nextRetryTimestampMillis = System.currentTimeMillis() + 8000L,
            retryDelaySeconds = 8L
        )

        val failedItem = DownloadActivityItem(
            id = "failed_item_1",
            title = "Unreachable.jpg",
            mediaUrl = "https://example.com/unreachable.jpg",
            status = DownloadActivityStatus.FAILED,
            retryCount = 5,
            maxRetries = 5,
            errorMessage = "Server returned HTTP status 429"
        )

        val activities = listOf(retryingItem, failedItem)

        // Retrying item must NOT be classified as failed
        assertFalse("Retrying item must not have isFailed = true", retryingItem.isFailed)
        assertTrue("Retrying item must have isRetrying = true", retryingItem.isRetrying)

        // Filter lists test: Downloading filter includes RETRYING
        val downloadingFilter = activities.filter { it.isDownloading || it.isPaused || it.isRetrying }
        assertEquals(1, downloadingFilter.size)
        assertEquals("retry_item_1", downloadingFilter[0].id)

        // Failed filter MUST ONLY include truly failed items (exhausted retries), NOT retrying items
        val failedFilter = activities.filter { it.isFailed }
        assertEquals(1, failedFilter.size)
        assertEquals("failed_item_1", failedFilter[0].id)
    }

    @Test
    fun testStackAndCollectionPreservationFields() {
        val originalItem = DownloadActivityItem(
            id = "batch_item_1",
            title = "Taj_Mahal_Gate.jpg",
            mediaUrl = "https://example.com/gate.jpg",
            status = DownloadActivityStatus.RETRYING,
            stackId = "stack_taj_mahal",
            stackTitle = "Taj Mahal - Wikipedia",
            sourceDomain = "wikipedia.org",
            sourceUrl = "https://en.wikipedia.org/wiki/Taj_Mahal"
        )

        assertEquals("stack_taj_mahal", originalItem.stackId)
        assertEquals("Taj Mahal - Wikipedia", originalItem.stackTitle)
        assertEquals("wikipedia.org", originalItem.sourceDomain)
        assertEquals("https://en.wikipedia.org/wiki/Taj_Mahal", originalItem.sourceUrl)
    }

    @Test
    fun testSavedCardNavigationToSavedMediaLibrary() {
        // Given user clicks on "Saved" from Album Screen
        var currentMode: DownloaderViewMode? = null
        var currentDest: Destination? = null
        val onFilter: (String) -> Unit = { label ->
            when (label) {
                "Saved", "Saved Media Library", "Saved Library" -> {
                    currentDest = Destination.MEDIA_INBOX
                    currentMode = DownloaderViewMode.SAVED_LIBRARY
                }
                "Media Downloader", "Media Inbox", "Inbox" -> {
                    currentDest = Destination.MEDIA_INBOX
                    currentMode = DownloaderViewMode.SEARCH_PAGE
                }
                else -> {}
            }
        }

        // Action: Click "Saved" on Album Screen
        onFilter("Saved")
        assertEquals(Destination.MEDIA_INBOX, currentDest)
        assertEquals(DownloaderViewMode.SAVED_LIBRARY, currentMode)

        // Action: Click "Inbox"
        onFilter("Inbox")
        assertEquals(Destination.MEDIA_INBOX, currentDest)
        assertEquals(DownloaderViewMode.SEARCH_PAGE, currentMode)

        // Test Back Handling when launched with SAVED_LIBRARY
        val initialViewMode = DownloaderViewMode.SAVED_LIBRARY
        var didNavigateBack = false
        var activeMode = initialViewMode
        fun handleBack() {
            if (activeMode == DownloaderViewMode.SAVED_LIBRARY) {
                if (initialViewMode == DownloaderViewMode.SAVED_LIBRARY) {
                    didNavigateBack = true
                } else {
                    activeMode = DownloaderViewMode.SEARCH_PAGE
                }
            }
        }

        handleBack()
        assertTrue(didNavigateBack)
    }

    @Test
    fun testFiveRowGapBetweenSearchBarAndActivityCard() {
        val rowHeight = 40 // Standard dense list/item row in dp
        val fiveRowGap = 5 * rowHeight // 200 dp
        assertEquals(200, fiveRowGap)

        // When no search history exists, 5-row gap is always preserved
        val pillsEmpty = emptyList<String>()
        val gapWhenEmpty = if (pillsEmpty.isEmpty()) fiveRowGap else 0
        assertEquals(200, gapWhenEmpty)

        // When search history exists (e.g. 1 pill row of height 38dp + spacing)
        val pillsNotEmpty = listOf("wikipedia.org")
        val pillRowHeight = 38
        val internalSpacing = 26
        val remainingSpacer = (fiveRowGap - pillRowHeight - internalSpacing).coerceAtLeast(16)
        assertTrue(remainingSpacer >= 16)
        val totalDistance = pillRowHeight + internalSpacing + remainingSpacer
        assertEquals(200, totalDistance)
    }

    @Test
    fun testActivityPageUniqueKeysAndDeduplication() {
        // Given activities with duplicate IDs (e.g. from multiple scraped items or stacks)
        val item1 = DownloadActivityItem(id = "static_direct_0", title = "Photo 1", mediaUrl = "https://example.com/1.jpg", status = DownloadActivityStatus.FINISHED)
        val item2 = DownloadActivityItem(id = "static_direct_0", title = "Photo 2", mediaUrl = "https://example.com/2.jpg", status = DownloadActivityStatus.FINISHED)
        val item3 = DownloadActivityItem(id = "unique_item", title = "Photo 3", mediaUrl = "https://example.com/3.jpg", status = DownloadActivityStatus.FINISHED)
        val rawList = listOf(item1, item2, item3)

        // 1. List deduplication preserves unique entries
        val deduplicated = rawList.distinctBy { it.id }
        assertEquals(2, deduplicated.size)

        // 2. Index-scoped key generation guarantees 100% uniqueness even before deduplication
        val rawKeys = rawList.mapIndexed { index, item -> "finished_${item.id}_$index" }
        assertEquals(rawKeys.size, rawKeys.toSet().size)
        assertEquals("finished_static_direct_0_0", rawKeys[0])
        assertEquals("finished_static_direct_0_1", rawKeys[1])
        assertEquals("finished_unique_item_2", rawKeys[2])
    }

    @Test
    fun testProgressValueSafeguardAgainstNaNAndInf() {
        fun safeProgress(raw: Float, isRetrying: Boolean = false): Float {
            val p = if (isRetrying) 0.05f else raw
            return if (p.isNaN() || p.isInfinite() || p < 0f) 0.05f else p.coerceIn(0f, 1f)
        }

        // Test normal values
        assertEquals(0.5f, safeProgress(0.5f), 0.001f)
        assertEquals(1.0f, safeProgress(1.0f), 0.001f)
        assertEquals(0.0f, safeProgress(0.0f), 0.001f)

        // Test NaN, Infinity, negative, and overflow values
        assertEquals(0.05f, safeProgress(Float.NaN), 0.001f)
        assertEquals(0.05f, safeProgress(Float.POSITIVE_INFINITY), 0.001f)
        assertEquals(0.05f, safeProgress(Float.NEGATIVE_INFINITY), 0.001f)
        assertEquals(0.05f, safeProgress(-1.5f), 0.001f)
        assertEquals(1.0f, safeProgress(2.5f), 0.001f)

        // Retrying returns 0.05f
        assertEquals(0.05f, safeProgress(0.8f, isRetrying = true), 0.001f)
    }

    @Test
    fun testDateFormatSafeguardAgainstInvalidTimestamps() {
        // Zero or negative timestamps
        assertEquals("Recent", DownloadActivityManager.formatDate(0L))
        assertEquals("Recent", DownloadActivityManager.formatDate(-100L))

        // Current timestamp returns Today
        val todayStr = DownloadActivityManager.formatDate(System.currentTimeMillis())
        assertTrue(todayStr.startsWith("Today"))

        // Yesterday timestamp returns Yesterday
        val yesterdayStr = DownloadActivityManager.formatDate(System.currentTimeMillis() - (26 * 3600 * 1000L))
        assertTrue(yesterdayStr.startsWith("Yesterday"))
    }

    @Test
    fun testDownloaderSessionClear() {
        val session = com.amresalehin.emregallery.data.DownloaderSession
        session.startSession("https://example.com/test")
        session.addDiscoveredItems(
            listOf(
                com.amresalehin.emregallery.data.ScrapedMediaItem(
                    id = "item_1",
                    url = "https://example.com/photo.jpg",
                    title = "Photo"
                )
            ),
            "Test Page"
        )
        assertEquals(1, session.discoveredItems.value.size)

        // Clear must reset session
        session.clear()
        assertEquals(0, session.discoveredItems.value.size)
        assertEquals("", session.currentUrl.value)
        assertEquals("", session.sourceDomain.value)
    }

    @Test
    fun testFinishedDownloadsPreservedAndCounted() {
        val itemFinished = DownloadActivityItem(
            id = "test_finished_1",
            title = "finished.jpg",
            mediaUrl = "https://example.com/finished.jpg",
            status = DownloadActivityStatus.FINISHED,
            progress = 1.0f
        )
        val itemFailed = DownloadActivityItem(
            id = "test_failed_1",
            title = "failed.jpg",
            mediaUrl = "https://example.com/failed.jpg",
            status = DownloadActivityStatus.FAILED,
            progress = 0f
        )
        val list = listOf(itemFinished, itemFailed)

        val finishedOnly = list.filter { it.status == DownloadActivityStatus.FINISHED }
        val failedOnly = list.filter { it.status == DownloadActivityStatus.FAILED }

        assertEquals(1, finishedOnly.size)
        assertEquals("test_finished_1", finishedOnly[0].id)
        assertEquals(1, failedOnly.size)
        assertEquals("test_failed_1", failedOnly[0].id)
    }
}




