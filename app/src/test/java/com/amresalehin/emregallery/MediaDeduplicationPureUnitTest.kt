package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.BrowserTabSession
import com.amresalehin.emregallery.data.MediaCategory
import com.amresalehin.emregallery.data.MediaClassifier
import com.amresalehin.emregallery.data.MediaProvenance
import com.amresalehin.emregallery.data.ScrapedMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDeduplicationPureUnitTest {

    @Test
    fun browserTabMediaCountResetsWhenNavigatingToAnotherPage() {
        val session = BrowserTabSession(
            tabId = "browser_navigation_test",
            initialUrl = "https://example.com/first"
        )
        session.registerMedia(
            ScrapedMediaItem(
                id = "first-page-image",
                url = "https://cdn.example.com/first.jpg",
                title = "First page image"
            )
        )
        assertEquals(1, session.detectedCount.value)

        // A different path on the same domain is still a different page.
        session.updateNavigation("https://example.com/second")
        assertEquals(0, session.detectedCount.value)
        assertTrue(session.detectedItems.value.isEmpty())

        session.registerMedia(
            ScrapedMediaItem(
                id = "second-page-image",
                url = "https://cdn.example.com/second.jpg",
                title = "Second page image"
            )
        )
        assertEquals(1, session.detectedCount.value)

        // Returning to the browser's home/blank page clears the page badge too.
        session.updateNavigation("")
        assertEquals(0, session.detectedCount.value)
    }

    @Test
    fun testPinterestResolutionAndFormatVariantDeduplication() {
        val hash = "f732ca2684db4685ff263b65cb466ff4"
        val p1 = "f7"
        val p2 = "32"
        val p3 = "ca"

        val url757 = "https://i.pinimg.com/757x945/$p1/$p2/$p3/$hash.jpg"
        val url736 = "https://i.pinimg.com/736x/$p1/$p2/$p3/$hash.jpeg"
        val url474 = "https://i.pinimg.com/474x/$p1/$p2/$p3/$hash.webp"
        val url236 = "https://i.pinimg.com/236x/$p1/$p2/$p3/$hash.jpg"
        val urlOrig = "https://i.pinimg.com/originals/$p1/$p2/$p3/$hash.jpg"

        val key757 = MediaClassifier.deduplicationKey(url757)
        val key736 = MediaClassifier.deduplicationKey(url736)
        val key474 = MediaClassifier.deduplicationKey(url474)
        val key236 = MediaClassifier.deduplicationKey(url236)
        val keyOrig = MediaClassifier.deduplicationKey(urlOrig)

        val expectedKey = "pinimg:${hash.lowercase()}"
        assertEquals(expectedKey, key757)
        assertEquals(expectedKey, key736)
        assertEquals(expectedKey, key474)
        assertEquals(expectedKey, key236)
        assertEquals(expectedKey, keyOrig)

        // All should resolve to originals
        val res736 = MediaClassifier.resolveOriginalMedia(url736)
        assertEquals("https://i.pinimg.com/originals/$p1/$p2/$p3/$hash.jpg", res736.mediaUrl)
        assertEquals(url736, res736.thumbnailUrl)
    }

    @Test
    fun testResponsiveRasterFormatVariantsDeduplication() {
        // WordPress / Shopify / CDN resize suffixes and format variants
        val base = "https://example.com/wp-content/uploads/2024/05/hero"
        val wpJpg = "$base-736x918.jpg"
        val wpWebp = "$base-757x945.webp"
        val wpOrig = "$base.jpeg"

        val key1 = MediaClassifier.deduplicationKey(wpJpg)
        val key2 = MediaClassifier.deduplicationKey(wpWebp)
        val key3 = MediaClassifier.deduplicationKey(wpOrig)

        assertEquals(key3, key1)
        assertEquals(key3, key2)

        // CDN folder dimensions
        val cdnThumb = "https://images.example.com/236x/products/shoe.webp?w=236&format=webp"
        val cdnLarge = "https://images.example.com/736x/products/shoe.jpg?w=736&format=jpg"
        val cdnKey1 = MediaClassifier.deduplicationKey(cdnThumb)
        val cdnKey2 = MediaClassifier.deduplicationKey(cdnLarge)
        assertEquals(cdnKey2, cdnKey1)
    }

    @Test
    fun testDeduplicateScrapedItemsCollapsesResolutionAndFormatVariants() {
        val hash = "2d4c7a99e43de785bde6214ec042e947"
        val item1 = ScrapedMediaItem(
            id = "item_1",
            url = "https://i.pinimg.com/736x/2d/4c/7a/$hash.jpeg",
            title = "Aishwarya Rai Portrait",
            isVideo = false,
            resolutionHint = "736x1227",
            thumbnailUrl = "https://i.pinimg.com/736x/2d/4c/7a/$hash.jpeg",
            provenance = MediaProvenance(extractorEngine = "Static HTML (DOM Scrape)")
        )
        val item2 = ScrapedMediaItem(
            id = "item_2",
            url = "https://i.pinimg.com/236x/2d/4c/7a/$hash.jpeg",
            title = "Aishwarya Rai Portrait",
            isVideo = false,
            resolutionHint = "236x393",
            thumbnailUrl = "https://i.pinimg.com/236x/2d/4c/7a/$hash.jpeg",
            provenance = MediaProvenance(extractorEngine = "Browser Chromium (Network Intercept)")
        )
        val item3 = ScrapedMediaItem(
            id = "item_3",
            url = "https://i.pinimg.com/474x/2d/4c/7a/$hash.webp",
            title = "Aishwarya Rai Portrait",
            isVideo = false,
            resolutionHint = "474x790",
            thumbnailUrl = "https://i.pinimg.com/474x/2d/4c/7a/$hash.webp",
            provenance = MediaProvenance(extractorEngine = "DOM Scrape")
        )

        val deduplicated = MediaClassifier.deduplicateScrapedItems(listOf(item1, item2, item3))
        assertEquals(1, deduplicated.size)

        val winner = deduplicated[0]
        // Master original URL should be resolved
        assertEquals("https://i.pinimg.com/originals/2d/4c/7a/$hash.jpg", winner.url)
        // Highest resolution hint should be preserved
        assertEquals("736x1227", winner.resolutionHint)
        // Provenance should be merged
        assertTrue(winner.provenance.extractorEngine.contains("DOM Scrape"))
        assertTrue(winner.provenance.extractorEngine.contains("Network Intercept"))
        // Thumbnail should be present
        assertNotNull(winner.thumbnailUrl)
    }

    @Test
    fun testUserScreenshotExactDuplicateItemsAreCollapsed() {
        // From user screenshot:
        // Item 1: f732ca2684db4685ff26... JPG 757x945
        // Item 2: f732ca2684db4685ff26... JPEG 736x918
        val hash = "f732ca2684db4685ff263b65cb466ff4"
        val item1 = ScrapedMediaItem(
            id = "f7_item_1",
            url = "https://i.pinimg.com/757x945/f7/32/ca/$hash.jpg",
            title = "$hash...",
            isVideo = false,
            resolutionHint = "757x945",
            thumbnailUrl = "https://i.pinimg.com/757x945/f7/32/ca/$hash.jpg"
        )
        val item2 = ScrapedMediaItem(
            id = "f7_item_2",
            url = "https://i.pinimg.com/736x/f7/32/ca/$hash.jpeg",
            title = "$hash...",
            isVideo = false,
            resolutionHint = "736x918",
            thumbnailUrl = "https://i.pinimg.com/736x/f7/32/ca/$hash.jpeg"
        )

        val deduplicated = MediaClassifier.deduplicateScrapedItems(listOf(item1, item2))
        assertEquals(1, deduplicated.size)
        val winner = deduplicated.first()
        assertEquals("https://i.pinimg.com/originals/f7/32/ca/$hash.jpg", winner.url)
        assertEquals("757x945", winner.resolutionHint)
    }

    @Test
    fun testCompareMediaQualityPrefersOriginalsAndHigherResolution() {
        val lowRes = ScrapedMediaItem(
            id = "low",
            url = "https://example.com/images/hero-236x150.webp",
            title = "Hero",
            resolutionHint = "236x150",
            sizeBytes = 15000L
        )
        val highRes = ScrapedMediaItem(
            id = "high",
            url = "https://example.com/images/hero-1920x1080.jpg",
            title = "Hero",
            resolutionHint = "1920x1080",
            sizeBytes = 350000L
        )

        // highRes quality should be strictly greater than lowRes
        assertTrue(MediaClassifier.compareMediaQuality(highRes, lowRes) > 0)
        assertTrue(MediaClassifier.compareMediaQuality(lowRes, highRes) < 0)
    }

    @Test
    fun testCloudflareAndCdnTransformsDeduplication() {
        val cdnOriginal = "https://images.example.com/products/watch.png"
        val cdnOptimized = "https://images.example.com/cdn-cgi/image/width=800,quality=75,format=auto/products/watch.png"
        val cdnThumb = "https://images.example.com/cdn-cgi/image/w_200,h_200,f_auto/products/watch.png"

        val keyOrig = MediaClassifier.deduplicationKey(cdnOriginal)
        val keyOpt = MediaClassifier.deduplicationKey(cdnOptimized)
        val keyThumb = MediaClassifier.deduplicationKey(cdnThumb)

        assertEquals(keyOrig, keyOpt)
        assertEquals(keyOrig, keyThumb)
    }

    @Test
    fun testDuplicateDownloadItemsAreFilteredOut() {
        val item1 = com.amresalehin.emregallery.data.MediaInboxItem(
            id = "item_1",
            title = "sample.jpg",
            localUri = "/data/downloads/sample.jpg",
            sourceUrl = "https://example.com/sample.jpg",
            isVideo = false,
            sizeBytes = 1000L
        )
        val duplicateItem = com.amresalehin.emregallery.data.MediaInboxItem(
            id = "item_1_alt",
            title = "sample.jpg",
            localUri = "/data/downloads/sample.jpg",
            sourceUrl = "https://example.com/sample.jpg",
            isVideo = false,
            sizeBytes = 1000L
        )

        val list = listOf(item1)
        val updated = list.filter {
            it.id != duplicateItem.id && it.sourceUrl != duplicateItem.sourceUrl && it.localUri != duplicateItem.localUri
        } + duplicateItem

        // Should have exactly 1 item (replaced/deduplicated), not 2 copies
        assertEquals(1, updated.size)
    }

    @Test
    fun testMediaRepositoryRejectsXmlAndNonMediaFiles() {
        val nonMediaSamples = listOf(
            Pair("AndroidManifest.xml", "text/xml"),
            Pair("strings.xml", "application/xml"),
            Pair("document.pdf", "application/pdf"),
            Pair("archive.zip", "application/zip"),
            Pair("notes.txt", "text/plain"),
            Pair("payload.json", "application/json"),
            Pair("app.apk", "application/vnd.android.package-archive"),
            Pair("script.js", "text/javascript"),
            Pair("style.css", "text/css"),
            Pair(".nomedia", null)
        )

        for ((name, mime) in nonMediaSamples) {
            org.junit.Assert.assertFalse(
                "Expected $name ($mime) to be rejected by isSupportedMedia",
                com.amresalehin.emregallery.data.MediaRepository.isSupportedMedia(name, mime, isVideo = false)
            )
            org.junit.Assert.assertFalse(
                "Expected $name ($mime) to be rejected as video by isSupportedMedia",
                com.amresalehin.emregallery.data.MediaRepository.isSupportedMedia(name, mime, isVideo = true)
            )
        }

        val validMediaSamples = listOf(
            Pair("photo.jpg", "image/jpeg"),
            Pair("picture.png", "image/png"),
            Pair("graphic.webp", "image/webp"),
            Pair("camera.heic", "image/heif"),
            Pair("video.mp4", "video/mp4"),
            Pair("clip.webm", "video/webm")
        )

        for ((name, mime) in validMediaSamples) {
            val isVid = name.endsWith(".mp4") || name.endsWith(".webm")
            org.junit.Assert.assertTrue(
                "Expected $name ($mime) to be accepted by isSupportedMedia",
                com.amresalehin.emregallery.data.MediaRepository.isSupportedMedia(name, mime, isVideo = isVid)
            )
        }
    }

    @Test
    fun testMediaClassifierRejectsSvgXmlDataUriPlaceholders() {
        // LiteSpeed / WordPress placeholder transparent rectangle SVG
        val svgXmlDataUri = "data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIzMDAiIGhlaWdodD0iMTY4IiB2aWV3Qm94PSIwIDAgMzAwIDE2OCI+PHJlY3Qgd2lkdGg9IjEwMCUiIGhlaWdodD0iMTAwJSIgc3R5bGU9ImZpbGw6I2NmZDRkYjtmaWxsLW9wYWNpdHk6IDAuMTsiLz48L3N2Zz4="
        org.junit.Assert.assertFalse(
            "Expected SVG XML placeholder data URI to be rejected",
            MediaClassifier.isSubstantialDataUri(svgXmlDataUri)
        )
        org.junit.Assert.assertFalse(
            "Expected SVG XML placeholder data URI to be invalid media",
            MediaClassifier.isValidMedia(svgXmlDataUri)
        )
    }

    @Test
    fun testYouTubeThumbnailDeduplicationAndResolutionFolding() {
        val oar2Url = "https://i.ytimg.com/vi/FaEeQPmv1D4/oar2.jpg"
        val frame0_268 = "https://i.ytimg.com/vi/FaEeQPmv1D4/frame0.jpg?sqp=-oaymwEdCJUDENAFSFWQAgHyq4qpAw8IARUAAIhCcAHAAQ==&rs=AOn4CLB..."
        val frame0_270 = "https://i.ytimg.com/vi/FaEeQPmv1D4/frame0.jpg?sqp=-oaymwEdCK4DENAFSFWQAgHyq4qpAw8IARUAAIhCcAHAAQ==&rs=AOn4CLB..."

        // All share the same canonical YouTube thumbnail key
        val key1 = MediaClassifier.deduplicationKey(oar2Url)
        val key2 = MediaClassifier.deduplicationKey(frame0_268)
        val key3 = MediaClassifier.deduplicationKey(frame0_270)

        assertEquals("ytthumb:${"FaEeQPmv1D4".lowercase(java.util.Locale.ROOT)}", key1)
        assertEquals(key1, key2)
        assertEquals(key1, key3)

        val item1 = ScrapedMediaItem(
            id = "item_1",
            url = oar2Url,
            title = "oar2.jpg",
            isVideo = false,
            resolutionHint = "405x720",
            category = MediaCategory.IMAGES
        )
        val item2 = ScrapedMediaItem(
            id = "item_2",
            url = frame0_268,
            title = "frame0.jpg",
            isVideo = false,
            resolutionHint = "268x480",
            category = MediaCategory.IMAGES
        )
        val item3 = ScrapedMediaItem(
            id = "item_3",
            url = frame0_270,
            title = "frame0.jpg",
            isVideo = false,
            resolutionHint = "270x480",
            category = MediaCategory.IMAGES
        )

        val deduplicated = MediaClassifier.deduplicateScrapedItems(listOf(item1, item2, item3))
        // Multiple frame and size variants must collapse to exactly 1 highest-quality thumbnail
        assertEquals(1, deduplicated.size)
        // High resolution 405x720 must be selected over 268x480 or 270x480
        assertEquals("405x720", deduplicated[0].resolutionHint)
    }

    @Test
    fun testVideoPosterFoldingLeavesOnlyVideo() {
        val videoItem = ScrapedMediaItem(
            id = "video_1",
            url = "https://rr1---sn-uxa-i5ne.googlevideo.com/videoplayback?expire=123&id=FaEeQPmv1D4",
            title = "Abba Harmonium Bajate...",
            isVideo = true,
            category = MediaCategory.VIDEOS,
            format = "WEBM",
            resolutionHint = "720x1280",
            thumbnailUrl = "https://i.ytimg.com/vi/FaEeQPmv1D4/hqdefault.jpg"
        )

        val oar2PosterItem = ScrapedMediaItem(
            id = "img_oar2",
            url = "https://i.ytimg.com/vi/FaEeQPmv1D4/oar2.jpg",
            title = "oar2.jpg",
            isVideo = false,
            category = MediaCategory.IMAGES,
            resolutionHint = "405x720"
        )

        val frame0PosterItem = ScrapedMediaItem(
            id = "img_frame0",
            url = "https://i.ytimg.com/vi/FaEeQPmv1D4/frame0.jpg?sqp=abc",
            title = "frame0.jpg",
            isVideo = false,
            category = MediaCategory.IMAGES,
            resolutionHint = "270x480"
        )

        val result = MediaClassifier.deduplicateScrapedItems(listOf(videoItem, oar2PosterItem, frame0PosterItem))

        // Standalone poster/storyboard image duplicates of the video must be folded into the video
        assertEquals(1, result.size)
        val finalVideo = result[0]
        assertTrue(finalVideo.isVideo)
        assertEquals("Abba Harmonium Bajate...", finalVideo.title)
        // Thumbnail should be upgraded to oar2.jpg (405x720) from the folded poster item
        assertTrue(finalVideo.thumbnailUrl?.contains("FaEeQPmv1D4") == true)
    }

    @Test
    fun testNonMediaEndpointsAndIframeWebPagesAreRejected() {
        val rejectedUrls = listOf(
            "https://www.youtube.com/embed/FaEeQPmv1D4",
            "https://www.youtube.com/shorts/FaEeQPmv1D4",
            "https://www.youtube.com/watch?v=FaEeQPmv1D4",
            "https://www.youtube.com/api/stats/playback?ns=yt&cpn=123",
            "https://play.google.com/log?format=json&has_webp=1",
            "https://i.ytimg.com/generate_204",
            "https://accounts.google.com/ServiceLogin?service=youtube",
            "https://example.com/embed/player_widget",
            "https://example.com/tracking/pixel"
        )

        for (url in rejectedUrls) {
            org.junit.Assert.assertFalse(
                "Expected $url to be rejected by isValidMedia",
                MediaClassifier.isValidMedia(url)
            )
        }
    }

    @Test
    fun testBrowserTabSessionMaintainsMediaRegistryWithoutRescan() {
        val tabSession = com.amresalehin.emregallery.data.BrowserTabSession(tabId = "test_tab_1")
        assertEquals(0, tabSession.detectedCount.value)
        assertTrue(tabSession.detectedItems.value.isEmpty())

        val item1 = ScrapedMediaItem(
            id = "item_1",
            url = "https://example.com/video.mp4",
            title = "Video 1",
            isVideo = true,
            category = MediaCategory.VIDEOS,
            thumbnailUrl = "https://example.com/video_thumb_low.jpg"
        )
        val posterItem = ScrapedMediaItem(
            id = "item_poster",
            url = "https://example.com/video_thumb_low.jpg",
            title = "Video Poster",
            isVideo = false,
            category = MediaCategory.IMAGES,
            resolutionHint = "1280x720"
        )

        tabSession.registerMedia(item1)
        assertEquals(1, tabSession.detectedCount.value)
        assertEquals(1, tabSession.getDetectedMedia().size)

        // Registering poster image of the same video should fold into the video without increasing count
        tabSession.registerMedia(posterItem)
        assertEquals(1, tabSession.detectedCount.value)
        val detected = tabSession.getDetectedMedia()[0]
        assertEquals("item_1", detected.id)
        assertEquals("https://example.com/video_thumb_low.jpg", detected.thumbnailUrl)

        // Clearing tab media resets registry cleanly
        tabSession.clearMedia()
        assertEquals(0, tabSession.detectedCount.value)
        assertTrue(tabSession.getDetectedMedia().isEmpty())
    }

    @Test
    fun testMediaNormalizerDeduplicatesAndFoldsPosters() {
        val masterVideo = ScrapedMediaItem(
            id = "vid_1080",
            url = "https://cdn.example.com/media/clip_1080p.mp4",
            title = "Clip 1080p",
            isVideo = true,
            resolutionHint = "1920x1080"
        )
        val lowResVideo = ScrapedMediaItem(
            id = "vid_720",
            url = "https://cdn.example.com/media/clip_720p.mp4",
            title = "Clip 720p",
            isVideo = true,
            resolutionHint = "1280x720"
        )
        val folded = com.amresalehin.emregallery.data.MediaNormalizer.deduplicate(listOf(lowResVideo, masterVideo))
        assertEquals(1, folded.size)
        assertEquals("1920x1080", folded[0].resolutionHint)
    }

    @Test
    fun testExtractionPhaseAndExtractionSourceCompatibility() {
        assertEquals("gallery-dl", com.amresalehin.emregallery.data.ExtractionSource.GALLERY_DL.label)
        assertEquals("GALLERY_DL", com.amresalehin.emregallery.data.DualMediaExtractor.ExtractionPhase.GALLERY_DL.name)
        assertEquals("DIRECT_MEDIA", com.amresalehin.emregallery.data.DualMediaExtractor.ExtractionPhase.DIRECT_MEDIA.name)
        assertEquals("STATIC_SCRAPING", com.amresalehin.emregallery.data.DualMediaExtractor.ExtractionPhase.STATIC_SCRAPING.name)
        assertEquals("BROWSER_INTERCEPTING", com.amresalehin.emregallery.data.DualMediaExtractor.ExtractionPhase.BROWSER_INTERCEPTING.name)
    }

    @Test
    fun testAdBlockEngineIdentifiesAdHostsAndTrackers() {
        // Exact and subdomain matching
        assertTrue(com.amresalehin.emregallery.data.AdBlockEngine.isAdOrTracker("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"))
        assertTrue(com.amresalehin.emregallery.data.AdBlockEngine.isAdOrTracker("https://securepubads.g.doubleclick.net/gampad/ads?iu=123"))
        assertTrue(com.amresalehin.emregallery.data.AdBlockEngine.isAdOrTracker("https://ad.doubleclick.net/ddm/track/123"))
        assertTrue(com.amresalehin.emregallery.data.AdBlockEngine.isAdOrTracker("https://static.criteo.net/js/ld/ld.js"))
        assertTrue(com.amresalehin.emregallery.data.AdBlockEngine.isAdOrTracker("https://script.hotjar.com/modules.js"))

        // Path patterns
        assertTrue(com.amresalehin.emregallery.data.AdBlockEngine.isAdOrTracker("https://example.com/pagead/banner.png"))
        assertTrue(com.amresalehin.emregallery.data.AdBlockEngine.isAdOrTracker("https://example.com/track/pixel?id=456"))

        // Legitimate media URLs should NOT be blocked
        assertFalse(com.amresalehin.emregallery.data.AdBlockEngine.isAdOrTracker("https://images.unsplash.com/photo-12345?w=1080"))
        assertFalse(com.amresalehin.emregallery.data.AdBlockEngine.isAdOrTracker("https://cdn.example.com/videos/nature_documentary.mp4"))
        assertFalse(com.amresalehin.emregallery.data.AdBlockEngine.isAdOrTracker("https://upload.wikimedia.org/wikipedia/commons/1/10/Sample.jpg"))
    }

    @Test
    fun testYouTubeDecipheredStreamIsRecognizedAndClassifiedAsVideo() {
        val streamUrl = "https://rr2---sn-4g5ednkk.googlevideo.com/videoplayback?expire=1715000000&id=e6nigwabc4s&itag=22&ip=1.2.3.4&sparams=expire,id,ip,itag&sig=abc"
        
        // 1. Must not be rejected as a fragment
        assertFalse(MediaClassifier.isMediaFragment(streamUrl))
        
        // 2. Must be considered valid media
        assertTrue(MediaClassifier.isValidMedia(streamUrl))
        
        // 3. Must be classified as VIDEOS
        val classification = MediaClassifier.classify(streamUrl)
        assertEquals(MediaCategory.VIDEOS, classification.category)
        assertTrue(classification.isVideo)
        assertFalse(classification.isAudio)
    }

    @Test
    fun testYouTubeVideoPlaybackDeduplicationKeyMapsAcrossSlices() {
        val base = "https://rr2---sn-4g5ednkk.googlevideo.com/videoplayback?expire=1715000000&id=e6nigwabc4s&itag=22&ip=1.2.3.4"
        val slice1 = "$base&sq=0&range=0-1048576"
        val slice2 = "$base&sq=1&range=1048577-2097152"
        
        val key1 = MediaClassifier.deduplicationKey(slice1)
        val key2 = MediaClassifier.deduplicationKey(slice2)
        
        assertEquals(key1, key2)
        assertEquals("googlevideo:e6nigwabc4s_itag_22", key1)
    }

    @Test
    fun testYouTubePosterFoldsIntoDirectVideoItem() {
        val videoUrl = "https://rr2---sn-4g5ednkk.googlevideo.com/videoplayback?expire=1715000000&id=e6nigwabc4s&itag=22&ip=1.2.3.4"
        val posterUrl = "https://i.ytimg.com/vi/e6nigwabc4s/hqdefault.jpg"
        
        val posterItem = ScrapedMediaItem(
            id = "yt_img_1",
            url = posterUrl,
            title = "youtube_e6nigwabc4s_hqdefault.jpg",
            isVideo = false,
            category = MediaCategory.IMAGES,
            resolutionHint = "480x360"
        )
        
        val videoItem = ScrapedMediaItem(
            id = "ytdlp_1",
            url = videoUrl,
            title = "BEHIND THE SCENES FILMING THE JUTSU.mp4",
            isVideo = true,
            category = MediaCategory.VIDEOS,
            resolutionHint = "720x1280",
            metadata = mapOf("source_url" to "https://www.youtube.com/shorts/e6nigwabc4s")
        )
        
        val map = LinkedHashMap<String, ScrapedMediaItem>()
        // Merge poster first (as static scraper might)
        com.amresalehin.emregallery.data.MediaNormalizer.mergeCandidates(map, listOf(posterItem))
        assertEquals(1, map.size)
        
        // Merge video second (from yt-dlp)
        com.amresalehin.emregallery.data.MediaNormalizer.mergeCandidates(map, listOf(videoItem))
        // Poster image must fold into the video, leaving exactly 1 video item
        assertEquals(1, map.size)
        val mergedVideo = map.values.first()
        assertTrue(mergedVideo.isVideo)
        assertEquals(MediaCategory.VIDEOS, mergedVideo.category)
        assertNotNull(mergedVideo.thumbnailUrl)
        assertTrue(mergedVideo.thumbnailUrl!!.contains("e6nigwabc4s"))
    }

    @Test
    fun testTargetMediaExpectationAndSufficiencyCriteria() {
        val posterOnly = listOf(
            ScrapedMediaItem(
                id = "thumb_1",
                url = "https://i.ytimg.com/vi/abc123xyz/hqdefault.jpg",
                title = "Thumbnail",
                isVideo = false,
                isAudio = false,
                isStream = false,
                category = MediaCategory.IMAGES
            )
        )

        val videoFound = listOf(
            ScrapedMediaItem(
                id = "vid_1",
                url = "https://rr---sn.googlevideo.com/videoplayback?id=123",
                title = "Video.mp4",
                isVideo = true,
                isAudio = false,
                isStream = false,
                category = MediaCategory.VIDEOS
            )
        )

        // 1. URL Expectation Classification
        val ytExpectation = com.amresalehin.emregallery.data.ExtractionCoordinator.determineExpectation("https://www.youtube.com/shorts/E6nigwAbc4s")
        assertEquals(com.amresalehin.emregallery.data.TargetMediaExpectation.VIDEO_OR_STREAM, ytExpectation)

        val redditExpectation = com.amresalehin.emregallery.data.ExtractionCoordinator.determineExpectation("https://www.reddit.com/r/pics/comments/123/art")
        assertEquals(com.amresalehin.emregallery.data.TargetMediaExpectation.GALLERY_OR_ALBUM, redditExpectation)

        val genericExpectation = com.amresalehin.emregallery.data.ExtractionCoordinator.determineExpectation("https://example.com/blog/article")
        assertEquals(com.amresalehin.emregallery.data.TargetMediaExpectation.GENERIC_ANY, genericExpectation)

        // 2. For VIDEO_OR_STREAM: a static thumbnail alone MUST NOT be treated as sufficient success
        assertFalse(
            com.amresalehin.emregallery.data.ExtractionCoordinator.isExtractionSufficient(
                com.amresalehin.emregallery.data.TargetMediaExpectation.VIDEO_OR_STREAM,
                posterOnly
            )
        )

        // 3. For VIDEO_OR_STREAM: a real playable video stream is sufficient success
        assertTrue(
            com.amresalehin.emregallery.data.ExtractionCoordinator.isExtractionSufficient(
                com.amresalehin.emregallery.data.TargetMediaExpectation.VIDEO_OR_STREAM,
                videoFound
            )
        )

        // 4. For GENERIC_ANY: images or any media are sufficient
        assertTrue(
            com.amresalehin.emregallery.data.ExtractionCoordinator.isExtractionSufficient(
                com.amresalehin.emregallery.data.TargetMediaExpectation.GENERIC_ANY,
                posterOnly
            )
        )
    }

    @Test
    fun testYtDlpIsPrimaryAndGalleryDlIsSecondary() {
        // ExtractionPhase includes both engines
        assertEquals("YT_DLP", com.amresalehin.emregallery.data.DualMediaExtractor.ExtractionPhase.YT_DLP.name)
        assertEquals("GALLERY_DL", com.amresalehin.emregallery.data.DualMediaExtractor.ExtractionPhase.GALLERY_DL.name)

        // SupportInfo defaults to yt-dlp primary
        val infoYt = com.amresalehin.emregallery.data.GalleryDlSupportInfo(
            isSupported = true,
            category = "video",
            subcategory = "stream",
            extractorName = "Youtube",
            displayName = "YouTube",
            extractorEngine = "yt-dlp"
        )
        assertEquals("yt-dlp", infoYt.extractorEngine)

        // Gallery-dl fallback
        val infoGdl = com.amresalehin.emregallery.data.GalleryDlSupportInfo(
            isSupported = true,
            category = "reddit",
            subcategory = "gallery",
            extractorName = "RedditExtractor",
            displayName = "Reddit",
            extractorEngine = "gallery-dl"
        )
        assertEquals("gallery-dl", infoGdl.extractorEngine)

        // In absence of Python runtime, popular sites fallback check identifies gallery sites correctly
        val redditCheck = com.amresalehin.emregallery.data.GalleryDlEngine.checkGalleryDlSupport("https://www.reddit.com/r/pics/comments/123/mountains")
        assertTrue(redditCheck.isSupported)
        assertEquals("gallery-dl", redditCheck.extractorEngine)

        val imgurCheck = com.amresalehin.emregallery.data.GalleryDlEngine.checkGalleryDlSupport("https://imgur.com/a/abcde")
        assertTrue(imgurCheck.isSupported)
        assertEquals("gallery-dl", imgurCheck.extractorEngine)
    }

    @Test
    fun testBrowserSessionFallbackOnlyForUnsupportedSites() {
        // Supported site is detected as supported -> routes to engine
        val supportedCheck = com.amresalehin.emregallery.data.GalleryDlEngine.checkGalleryDlSupport("https://reddit.com/r/pics")
        assertTrue(supportedCheck.isSupported)

        // Unsupported site is not supported by gallery-dl -> triggers browser session fallback
        val unsupportedCheck = com.amresalehin.emregallery.data.GalleryDlEngine.checkGalleryDlSupport("https://unknown-random-news-blog.org/article")
        assertFalse(unsupportedCheck.isSupported)

        // Phase enum maintains BROWSER_INTERCEPTING for this fallback
        assertEquals("BROWSER_INTERCEPTING", com.amresalehin.emregallery.data.DualMediaExtractor.ExtractionPhase.BROWSER_INTERCEPTING.name)
    }

    @Test
    fun testBotDetectionChallengeIdentification() {
        // Cloudflare / Turnstile challenges correctly identified
        assertTrue(com.amresalehin.emregallery.data.BotDetectionMitigator.isBotChallenge("Just a moment...", "https://example.com"))
        assertTrue(com.amresalehin.emregallery.data.BotDetectionMitigator.isBotChallenge("Attention Required! | Cloudflare", "https://example.com"))
        assertTrue(com.amresalehin.emregallery.data.BotDetectionMitigator.isBotChallenge("Security Check", "https://example.com"))
        assertTrue(com.amresalehin.emregallery.data.BotDetectionMitigator.isBotChallenge("Checking your browser before accessing...", "https://example.com"))
        assertTrue(com.amresalehin.emregallery.data.BotDetectionMitigator.isBotChallenge("Welcome", "https://challenges.cloudflare.com/cdn-cgi/challenge-platform/h/b/turnstile"))

        // Standard pages are not flagged as challenges
        assertFalse(com.amresalehin.emregallery.data.BotDetectionMitigator.isBotChallenge("Google Photos", "https://photos.google.com"))
        assertFalse(com.amresalehin.emregallery.data.BotDetectionMitigator.isBotChallenge("Reddit - Dive into anything", "https://reddit.com/r/pics"))
    }

    @Test
    fun testStealthUserAgentAndHeadersFormat() {
        val ua = com.amresalehin.emregallery.data.BotDetectionMitigator.STEALTH_USER_AGENT
        assertTrue(ua.contains("Chrome/"))
        assertTrue(ua.contains("Mobile Safari"))
        assertTrue(ua.contains("Android"))

        val headers = com.amresalehin.emregallery.data.BotDetectionMitigator.STEALTH_HEADERS
        assertTrue(headers.containsKey("Sec-Ch-Ua"))
        assertTrue(headers.containsKey("Sec-Ch-Ua-Mobile"))
        assertTrue(headers.containsKey("Sec-Ch-Ua-Platform"))
        assertEquals("?1", headers["Sec-Ch-Ua-Mobile"])
        assertEquals("\"Android\"", headers["Sec-Ch-Ua-Platform"])
    }
}



