package com.amresalehin.emregallery

import android.content.Context
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.amresalehin.emregallery.data.ContentAwareMediaFilter
import com.amresalehin.emregallery.data.DualMediaExtractor
import com.amresalehin.emregallery.data.MediaCategory
import com.amresalehin.emregallery.data.MediaClassifier
import com.amresalehin.emregallery.data.MediaInboxItem
import com.amresalehin.emregallery.data.MediaInboxStack
import com.amresalehin.emregallery.data.MediaProvenance
import com.amresalehin.emregallery.data.OkHttpDownloader
import com.amresalehin.emregallery.data.ScrapedMediaItem
import com.amresalehin.emregallery.data.ScrapeResult
import com.amresalehin.emregallery.ui.Destination
import com.amresalehin.emregallery.ui.DownloaderViewMode
import com.amresalehin.emregallery.ui.DetectedPlatform
import com.amresalehin.emregallery.ui.detectPlatform
import com.amresalehin.emregallery.ui.NavBackStackEntry
import com.amresalehin.emregallery.ui.passesDimensionFilter
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config
class MediaDownloaderTest {

    @Test
    fun destinationEnumContainsMediaInbox() {
        assertEquals("MEDIA_INBOX", Destination.MEDIA_INBOX.name)
    }

    @Test
    fun navBackStackEntrySupportsMediaInbox() {
        val entry = NavBackStackEntry(
            destination = Destination.MEDIA_INBOX
        )
        assertEquals(Destination.MEDIA_INBOX, entry.destination)
    }

    @Test
    fun mediaInboxStackModelProperties() {
        val item1 = MediaInboxItem(
            id = "item_1",
            title = "scraped_image.jpg",
            localUri = "/data/downloads/scraped_image.jpg",
            sourceUrl = "https://example.com/scraped_image.jpg",
            isVideo = false,
            sizeBytes = 204800L
        )
        val stack = MediaInboxStack(
            id = "stack_1",
            title = "Example Site",
            sourceDomain = "example.com",
            sourceUrl = "https://example.com/gallery",
            items = listOf(item1)
        )

        assertEquals("stack_1", stack.id)
        assertEquals("Example Site", stack.title)
        assertEquals("example.com", stack.sourceDomain)
        assertEquals(1, stack.items.size)
        assertEquals("scraped_image.jpg", stack.items.first().title)
    }

    @Test
    fun scrapeResultFiltering() {
        val items = listOf(
            ScrapedMediaItem("1", "https://site.com/1.jpg", "Photo1", isVideo = false),
            ScrapedMediaItem("2", "https://site.com/2.mp4", "Video1", isVideo = true),
            ScrapedMediaItem("3", "https://site.com/3.png", "Photo2", isVideo = false)
        )
        val result = ScrapeResult(
            sourceUrl = "https://site.com",
            pageTitle = "My Site",
            domain = "site.com",
            items = items
        )

        assertEquals(3, result.items.size)
        val imagesOnly = result.items.filter { !it.isVideo && !it.isAudio }
        val videosOnly = result.items.filter { it.isVideo }
        val audioOnly = result.items.filter { it.isAudio }

        assertEquals(2, imagesOnly.size)
        assertEquals(1, videosOnly.size)
        assertEquals(0, audioOnly.size)
    }

    @Test
    fun scrapeResultAudioFiltering() {
        val items = listOf(
            ScrapedMediaItem("1", "https://site.com/track.mp3", "Track 1", isVideo = false, isAudio = true),
            ScrapedMediaItem("2", "https://site.com/video.mp4", "Video 1", isVideo = true, isAudio = false),
            ScrapedMediaItem("3", "https://site.com/photo.jpg", "Photo 1", isVideo = false, isAudio = false)
        )
        val result = ScrapeResult("https://site.com", "Site", "site.com", items)
        val audios = result.items.filter { it.isAudio }
        assertEquals(1, audios.size)
        assertEquals("audio/mpeg", audios.first().mimeType)
    }

    @Test
    fun mediaSelectionBatchOperations() {
        var selectedIds = setOf("1", "2")
        val toggleId = "3"

        // Add
        selectedIds = selectedIds + toggleId
        assertTrue(selectedIds.contains("3"))
        assertEquals(3, selectedIds.size)

        // Remove
        selectedIds = selectedIds - "1"
        assertEquals(false, selectedIds.contains("1"))
        assertEquals(2, selectedIds.size)
    }

    @Test
    fun selectAllAndSortLogic() {
        val items = listOf(
            ScrapedMediaItem("1", "https://site.com/b.jpg", "Beta", isVideo = false),
            ScrapedMediaItem("2", "https://site.com/a.mp4", "Alpha", isVideo = true)
        )
        val allIds = items.map { it.id }.toSet()
        assertEquals(2, allIds.size)

        val sortedByTitle = items.sortedBy { it.title }
        assertEquals("Alpha", sortedByTitle[0].title)
        assertEquals("Beta", sortedByTitle[1].title)
    }

    @Test
    fun mediaClassifierDetectsStreamsAndFormats() {
        val hls = com.amresalehin.emregallery.data.MediaClassifier.classify("https://example.com/live/playlist.m3u8")
        assertEquals(com.amresalehin.emregallery.data.MediaCategory.STREAMS, hls.category)
        assertTrue(hls.isStream)
        assertTrue(hls.isVideo)
        assertEquals("HLS (m3u8)", hls.format)

        val dash = com.amresalehin.emregallery.data.MediaClassifier.classify("https://example.com/video/manifest.mpd")
        assertEquals(com.amresalehin.emregallery.data.MediaCategory.STREAMS, dash.category)
        assertTrue(dash.isStream)
        assertEquals("DASH (mpd)", dash.format)

        val mp4 = com.amresalehin.emregallery.data.MediaClassifier.classify("https://example.com/clip.mp4")
        assertEquals(com.amresalehin.emregallery.data.MediaCategory.VIDEOS, mp4.category)
        assertEquals("MP4", mp4.format)

        val mp3 = com.amresalehin.emregallery.data.MediaClassifier.classify("https://example.com/audio.mp3")
        assertEquals(com.amresalehin.emregallery.data.MediaCategory.AUDIO, mp3.category)
        assertTrue(mp3.isAudio)

        val webp = com.amresalehin.emregallery.data.MediaClassifier.classify("https://example.com/photo.webp")
        assertEquals(com.amresalehin.emregallery.data.MediaCategory.IMAGES, webp.category)
        assertEquals("WEBP", webp.format)
    }

    @Test
    fun mediaClassifierCanonicalDeduplicationStripsTrackingParams() {
        val urlWithTracking = "https://example.com/image.jpg?utm_source=twitter&utm_medium=social&id=42&fbclid=xyz"
        val canonical = com.amresalehin.emregallery.data.MediaClassifier.canonicalKey(urlWithTracking)
        assertTrue(canonical.contains("id=42"))
        assertEquals(false, canonical.contains("utm_source"))
        assertEquals(false, canonical.contains("fbclid"))

        // Duplicate URL with different tracking params resolves to same canonical key
        val urlDifferentTracking = "https://example.com/image.jpg?fbclid=different&id=42&utm_campaign=winter"
        val canonical2 = com.amresalehin.emregallery.data.MediaClassifier.canonicalKey(urlDifferentTracking)
        assertEquals(canonical, canonical2)
    }

    @Test
    fun mediaClassifierUrlResolution() {
        val base = "https://example.com/articles/index.html"
        val relative = com.amresalehin.emregallery.data.MediaClassifier.resolveUrl("/images/hero.png", base)
        assertEquals("https://example.com/images/hero.png", relative)

        val protocolRelative = com.amresalehin.emregallery.data.MediaClassifier.resolveUrl("//cdn.example.com/video.mp4", base)
        assertEquals("https://cdn.example.com/video.mp4", protocolRelative)
    }

    @Test
    fun mediaClassifierSrcsetParsing() {
        val srcset = "thumb.jpg 320w, medium.jpg 640w, large.jpg 1280w"
        val parsed = com.amresalehin.emregallery.data.MediaClassifier.parseSrcset(srcset, "https://example.com/")
        assertEquals(3, parsed.size)
        assertEquals("https://example.com/thumb.jpg", parsed[0].first)
        assertEquals("320w", parsed[0].second)
        assertEquals("https://example.com/large.jpg", parsed[2].first)
        assertEquals("1280w", parsed[2].second)
    }

    @Test
    fun isValidMediaRejectsWikiArticlesAndNonMediaPages() {
        // Wiki File and article pages should be rejected (they are HTML pages, not raw media files)
        assertEquals(false, com.amresalehin.emregallery.data.MediaClassifier.isValidMedia("https://en.wikipedia.org/wiki/File:Taj_Mahal.jpg"))
        assertEquals(false, com.amresalehin.emregallery.data.MediaClassifier.isValidMedia("https://en.wikipedia.org/wiki/Taj_Mahal"))
        assertEquals(false, com.amresalehin.emregallery.data.MediaClassifier.isValidMedia("https://commons.wikimedia.org/wiki/File:Mumtaz_Mahal.jpg"))
        assertEquals(false, com.amresalehin.emregallery.data.MediaClassifier.isValidMedia("https://example.com/w/index.php?title=File:Photo.png"))
        assertEquals(false, com.amresalehin.emregallery.data.MediaClassifier.isValidMedia("https://reddit.com/r/pics/comments/abc/pic.jpg"))

        // Genuine media endpoints on CDN/upload hosts should be accepted
        assertTrue(com.amresalehin.emregallery.data.MediaClassifier.isValidMedia("https://upload.wikimedia.org/wikipedia/commons/1/1d/Taj_Mahal_%28Edited%29.jpeg"))
        assertTrue(com.amresalehin.emregallery.data.MediaClassifier.isValidMedia("https://thumb.wikimedia.org/wikipedia/commons/thumb/1/1d/Taj_Mahal.jpeg/500px-Taj_Mahal.jpeg"))
        assertTrue(com.amresalehin.emregallery.data.MediaClassifier.isValidMedia("https://example.com/images/hero.jpg"))
    }

    @Test
    fun testWikimediaThumbnailResolvesToFullResolutionOriginal() {
        val thumbUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/1/1d/Taj_Mahal_%28Edited%29.jpeg/500px-Taj_Mahal_%28Edited%29.jpeg"
        val resolved = com.amresalehin.emregallery.data.MediaClassifier.resolveOriginalMedia(thumbUrl)

        assertEquals("https://upload.wikimedia.org/wikipedia/commons/1/1d/Taj_Mahal_%28Edited%29.jpeg", resolved.mediaUrl)
        assertEquals(thumbUrl, resolved.thumbnailUrl)
        assertEquals("Taj_Mahal_(Edited).jpeg", resolved.cleanTitle)

        // Protocol-relative test
        val protoRelative = "//thumb.wikimedia.org/wikipedia/commons/thumb/b/ba/Taj_Mahal-10_%28cropped%29.jpg/330px-Taj_Mahal-10_%28cropped%29.jpg"
        val resolvedProto = com.amresalehin.emregallery.data.MediaClassifier.resolveOriginalMedia(protoRelative)
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/b/ba/Taj_Mahal-10_%28cropped%29.jpg", resolvedProto.mediaUrl)
        assertEquals("Taj_Mahal-10_(cropped).jpg", resolvedProto.cleanTitle)
    }

    @Test
    fun testWebsiteAndRouteAwareMediaExclusions() {
        // Generic CDN URLs containing gallery, pin, comments, or wiki substrings MUST be accepted
        assertTrue(com.amresalehin.emregallery.data.MediaClassifier.isValidMedia("https://mycdn.example.com/gallery/photo.jpg"))
        assertTrue(com.amresalehin.emregallery.data.MediaClassifier.isValidMedia("https://mycdn.example.com/pin/12345.png"))
        assertTrue(com.amresalehin.emregallery.data.MediaClassifier.isValidMedia("https://mycdn.example.com/comments/image.jpg"))
        assertTrue(com.amresalehin.emregallery.data.MediaClassifier.isValidMedia("https://mycdn.example.com/wiki/hero.png"))

        // Direct platform CDNs MUST be accepted
        assertTrue(com.amresalehin.emregallery.data.MediaClassifier.isValidMedia("https://i.redd.it/comments/abc.jpg"))
        assertTrue(com.amresalehin.emregallery.data.MediaClassifier.isValidMedia("https://i.pinimg.com/736x/ab/cd/ef.jpg"))
        assertTrue(com.amresalehin.emregallery.data.MediaClassifier.isValidMedia("https://i.imgur.com/gallery/abcde.jpg"))

        // Platform discussion and album pages MUST be rejected
        assertEquals(false, com.amresalehin.emregallery.data.MediaClassifier.isValidMedia("https://reddit.com/r/pics/comments/abc/pic.jpg"))
        assertEquals(false, com.amresalehin.emregallery.data.MediaClassifier.isValidMedia("https://pinterest.com/pin/12345/"))
        assertEquals(false, com.amresalehin.emregallery.data.MediaClassifier.isValidMedia("https://imgur.com/gallery/abcde"))
        assertEquals(false, com.amresalehin.emregallery.data.MediaClassifier.isValidMedia("https://twitter.com/user/status/12345"))
    }

    @Test
    fun browserUrlFormattingAndNormalization() {
        fun formatUrl(input: String): String {
            val trimmed = input.trim()
            if (trimmed.isBlank()) return ""
            return if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                if (trimmed.contains(".") && !trimmed.contains(" ")) {
                    "https://$trimmed"
                } else {
                    "https://duckduckgo.com/?q=${java.net.URLEncoder.encode(trimmed, "UTF-8")}"
                }
            } else {
                trimmed
            }
        }

        assertEquals("https://example.com/videos", formatUrl("example.com/videos"))
        assertEquals("https://duckduckgo.com/?q=funny+cats", formatUrl("funny cats"))
        assertEquals("https://test.org", formatUrl("https://test.org"))
    }

    @Test
    fun testBrowserSearchEnginesAndFormatQueryOrUrl() {
        val query = "vintage photography"
        val ddgUrl = com.amresalehin.emregallery.data.MediaClassifier.formatQueryOrUrl(
            query,
            com.amresalehin.emregallery.data.BrowserSearchEngine.DUCKDUCKGO
        )
        assertTrue(ddgUrl.contains("duckduckgo.com/?q=vintage+photography"))

        val googleUrl = com.amresalehin.emregallery.data.MediaClassifier.formatQueryOrUrl(
            query,
            com.amresalehin.emregallery.data.BrowserSearchEngine.GOOGLE
        )
        assertTrue(googleUrl.contains("google.com/search?q=vintage+photography"))

        val bingUrl = com.amresalehin.emregallery.data.MediaClassifier.formatQueryOrUrl(
            query,
            com.amresalehin.emregallery.data.BrowserSearchEngine.BING
        )
        assertTrue(bingUrl.contains("bing.com/search?q=vintage+photography"))

        val wikiUrl = com.amresalehin.emregallery.data.MediaClassifier.formatQueryOrUrl(
            query,
            com.amresalehin.emregallery.data.BrowserSearchEngine.WIKIPEDIA
        )
        assertTrue(wikiUrl.contains("wikipedia.org/wiki/Special:Search?search=vintage+photography"))

        // Standard URLs without http/https should add https://
        val siteUrl = com.amresalehin.emregallery.data.MediaClassifier.formatQueryOrUrl("commons.wikimedia.org")
        assertEquals("https://commons.wikimedia.org", siteUrl)

        // Empty string remains empty
        val emptyUrl = com.amresalehin.emregallery.data.MediaClassifier.formatQueryOrUrl("   ")
        assertEquals("", emptyUrl)
    }

    @Test
    fun testValidWebUrlValidation() {
        val validCases = listOf(
            "https://example.com/gallery",
            "http://sub.domain.org/path/to/image.jpg",
            "commons.wikimedia.org",
            "m.youtube.com/watch?v=123",
            "https://site.co.uk/media.mp4?id=50"
        )
        for (url in validCases) {
            assertTrue("Expected valid: $url", com.amresalehin.emregallery.data.MediaClassifier.isValidWebUrl(url))
        }

        val invalidCases = listOf(
            "not a valid url",
            "random pasted text from notes",
            "justwords",
            "   ",
            "javascript:void(0)",
            "file:///data/local/tmp",
            "invalid.123"
        )
        for (invalid in invalidCases) {
            assertEquals("Expected invalid: $invalid", false, com.amresalehin.emregallery.data.MediaClassifier.isValidWebUrl(invalid))
        }
    }

    @Test
    fun testGalleryDlEngineStatus() {
        val status = com.amresalehin.emregallery.data.GalleryDlEngine.getStatus()
        assertTrue(status.popularSites.isNotEmpty())
        assertTrue(status.popularSites.any { it.name == "Reddit" })
        assertTrue(status.popularSites.any { it.name == "Imgur" })
        assertTrue(status.popularSites.any { it.name == "Twitter / X" })
        assertTrue(status.popularSites.any { it.name == "Pinterest" })
        assertTrue(status.popularSites.any { it.name == "ArtStation" })
    }

    @Test
    fun testGalleryDlSupportInfoDetection() {
        val redditCheck = com.amresalehin.emregallery.data.GalleryDlEngine.checkSupport("https://www.reddit.com/r/earthporn/comments/123/mountains")
        assertTrue(redditCheck.isSupported)

        val imgurCheck = com.amresalehin.emregallery.data.GalleryDlEngine.checkSupport("https://imgur.com/a/abcde")
        assertTrue(imgurCheck.isSupported)

        val artstationCheck = com.amresalehin.emregallery.data.GalleryDlEngine.checkSupport("https://artstation.com/artwork/xyz123")
        assertTrue(artstationCheck.isSupported)

        val genericCheck = com.amresalehin.emregallery.data.GalleryDlEngine.checkSupport("https://nonexistent-random-domain-12345.com")
        assertEquals(false, genericCheck.isSupported)
    }

    @Test
    fun testExtractionSourceGalleryDlEnum() {
        assertEquals("gallery-dl", com.amresalehin.emregallery.data.ExtractionSource.GALLERY_DL.label)
        assertEquals(
            com.amresalehin.emregallery.data.DualMediaExtractor.ExtractionPhase.GALLERY_DL.name,
            "GALLERY_DL"
        )
    }

    @Test
    fun testJsoupHtmlParserExtractsMedia() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Sample Gallery Page</title>
                <meta property="og:image" content="https://example.com/og_hero.jpg" />
                <meta property="og:video" content="https://example.com/promo.mp4" />
            </head>
            <body>
                <img src="/images/pic1.jpg" data-src="/images/pic1_large.jpg" />
                <video poster="/images/poster.jpg">
                    <source src="/videos/clip.webm" type="video/webm" />
                </video>
                <div style="background-image: url('/images/bg.png');"></div>
                <a href="/downloads/wallpaper_4k.jpg">Download 4K</a>
            </body>
            </html>
        """.trimIndent()

        val doc = org.jsoup.Jsoup.parse(html, "https://example.com")
        assertEquals("Sample Gallery Page", doc.title())

        val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")
        assertEquals("https://example.com/og_hero.jpg", ogImage)

        val images = doc.select("img")
        assertEquals(1, images.size)
        assertEquals("https://example.com/images/pic1.jpg", images[0].absUrl("src"))

        val videoSources = doc.select("video source")
        assertEquals(1, videoSources.size)
        assertEquals("https://example.com/videos/clip.webm", videoSources[0].absUrl("src"))

        val directLinks = doc.select("a[href]")
        assertEquals(1, directLinks.size)
        assertEquals("https://example.com/downloads/wallpaper_4k.jpg", directLinks[0].absUrl("href"))
    }

    @Test
    fun testOkHttpSharedSessionCookieJar() {
        val cookieJar = com.amresalehin.emregallery.data.OkHttpDownloader.SharedSessionCookieJar()
        val testUrl = "https://example.com/gallery"
        
        cookieJar.setManualCookie(testUrl, "session_token", "abc123xyz")
        cookieJar.setManualCookie(testUrl, "cf_clearance", "token987")

        val cookieString = cookieJar.getCookieString(testUrl)
        assertTrue(cookieString.contains("session_token=abc123xyz"))
        assertTrue(cookieString.contains("cf_clearance=token987"))

        val httpUrl = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("example.com")
            .encodedPath("/gallery")
            .build()
        val loadedCookies = cookieJar.loadForRequest(httpUrl)
        assertEquals(2, loadedCookies.size)
        assertTrue(loadedCookies.any { it.name == "session_token" && it.value == "abc123xyz" })
        assertTrue(loadedCookies.any { it.name == "cf_clearance" && it.value == "token987" })

        // Clear session
        cookieJar.clearSession()
        val afterClear = cookieJar.loadForRequest(httpUrl)
        assertEquals(0, afterClear.size)
    }

    @Test
    fun testOkHttpClientConfiguration() {
        val client = com.amresalehin.emregallery.data.OkHttpDownloader.client
        assertTrue(client.followRedirects)
        assertTrue(client.followSslRedirects)
        assertEquals(15000, client.connectTimeoutMillis)
        assertEquals(30000, client.readTimeoutMillis)
        assertEquals(30000, client.writeTimeoutMillis)
        assertEquals(com.amresalehin.emregallery.data.OkHttpDownloader.sharedCookieJar, client.cookieJar)
        assertTrue(client.interceptors.isNotEmpty())
    }

    @Test
    fun testWorkManagerDownloadWorkerConstants() {
        assertEquals("media_download", com.amresalehin.emregallery.data.MediaDownloadWorker.WORK_TAG_DOWNLOAD)
        assertEquals("media_url", com.amresalehin.emregallery.data.MediaDownloadWorker.KEY_MEDIA_URL)
        assertEquals("file_name", com.amresalehin.emregallery.data.MediaDownloadWorker.KEY_FILE_NAME)
        assertEquals("stack_id", com.amresalehin.emregallery.data.MediaDownloadWorker.KEY_STACK_ID)
        assertEquals("progress", com.amresalehin.emregallery.data.MediaDownloadWorker.KEY_PROGRESS)
    }

    @Test
    fun testMedia3StreamingMimeTypesDetection() {
        val hlsUrl = "https://example.com/live/stream.m3u8"
        val isHls = hlsUrl.contains(".m3u8", ignoreCase = true) || hlsUrl.contains("mpegurl", ignoreCase = true)
        assertTrue(isHls)

        val dashUrl = "https://example.com/vod/manifest.mpd"
        val isDash = dashUrl.contains(".mpd", ignoreCase = true) || dashUrl.contains("dash", ignoreCase = true)
        assertTrue(isDash)

        assertEquals("application/x-mpegURL", androidx.media3.common.MimeTypes.APPLICATION_M3U8)
        assertEquals("application/dash+xml", androidx.media3.common.MimeTypes.APPLICATION_MPD)
    }

    @Test
    fun testMockWebServerRejectsHtmlMimeTypeForImages() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val context: Context = ApplicationProvider.getApplicationContext()
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody("<!DOCTYPE html><html><body>Error page</body></html>")
            )
            val url = server.url("/test_img.jpg").toString()
            val result = OkHttpDownloader.downloadMediaFileDetailed(context, url, "test_img.jpg")
            assertFalse(result.isSuccessful)
            assertNull(result.file)
            assertTrue(result.errorMessage?.contains("webpage") == true || result.errorMessage?.contains("HTML") == true)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testMockWebServerRejectsJsonErrorPagesEvenForManifest() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val context: Context = ApplicationProvider.getApplicationContext()
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"error":"forbidden","message":"Invalid token"}""")
            )
            val url = server.url("/stream.m3u8").toString()
            val result = OkHttpDownloader.downloadMediaFileDetailed(context, url, "stream.m3u8")
            assertFalse(result.isSuccessful)
            assertNull(result.file)
            assertTrue(result.errorMessage?.contains("error") == true || result.errorMessage?.contains("JSON") == true || result.errorMessage?.contains("json") == true)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testMockWebServerResumableRange206() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val context: Context = ApplicationProvider.getApplicationContext()
            val downloadId = "test_resumable_video"
            val targetDir = OkHttpDownloader.getTargetStorageDir(context)
            val tempFile = File(targetDir, "$downloadId.mp4.downloading")
            tempFile.writeBytes(ByteArray(5) { 0x41.toByte() })

            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Type", "video/mp4")
                    .setHeader("Content-Range", "bytes 5-9/10")
                    .setHeader("Content-Length", "5")
                    .setBody(Buffer().write(ByteArray(5) { 0x42.toByte() }))
            )

            val url = server.url("/video.mp4").toString()
            val result = OkHttpDownloader.downloadMediaFileDetailed(
                context = context,
                mediaUrl = url,
                fileName = "video.mp4",
                downloadId = downloadId
            )

            val recorded = server.takeRequest()
            assertEquals("bytes=5-", recorded.getHeader("Range"))
            assertTrue(result.isSuccessful)
            assertTrue(result.isResumed)
            assertEquals(10L, result.bytesDownloaded)
            assertNotNull(result.file)
            assertEquals(10L, result.file?.length())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testMockWebServerHttp200FallbackClearsPartialFile() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val context: Context = ApplicationProvider.getApplicationContext()
            val downloadId = "test_fallback_video"
            val targetDir = OkHttpDownloader.getTargetStorageDir(context)
            val tempFile = File(targetDir, "$downloadId.mp4.downloading")
            tempFile.writeBytes(ByteArray(8) { 0x55.toByte() })

            // Server ignores Range and returns HTTP 200 with full 10 bytes
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "video/mp4")
                    .setBody(Buffer().write(ByteArray(10) { 0x77.toByte() }))
                    .setHeader("Content-Length", "10")
            )

            val url = server.url("/video.mp4").toString()
            val result = OkHttpDownloader.downloadMediaFileDetailed(
                context = context,
                mediaUrl = url,
                fileName = "video.mp4",
                downloadId = downloadId
            )

            assertTrue(result.isSuccessful)
            assertFalse(result.isResumed)
            assertEquals(10L, result.bytesDownloaded)
            assertNotNull(result.file)
            assertEquals(10L, result.file?.length())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testMockWebServerHttp416SafeHandling() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val context: Context = ApplicationProvider.getApplicationContext()
            val downloadId = "test_416_video"
            val targetDir = OkHttpDownloader.getTargetStorageDir(context)
            val tempFile = File(targetDir, "$downloadId.mp4.downloading")
            tempFile.writeBytes(ByteArray(50) { 0x11.toByte() })

            // First request with Range fails with 416
            server.enqueue(
                MockResponse()
                    .setResponseCode(416)
                    .setHeader("Content-Range", "bytes */10")
            )
            // Follow-up clean request returns 200 with 10 bytes
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "video/mp4")
                    .setBody(Buffer().write(ByteArray(10) { 0x22.toByte() }))
                    .setHeader("Content-Length", "10")
            )

            val url = server.url("/video.mp4").toString()
            val result = OkHttpDownloader.downloadMediaFileDetailed(
                context = context,
                mediaUrl = url,
                fileName = "video.mp4",
                downloadId = downloadId
            )

            assertTrue(result.isSuccessful)
            assertEquals(10L, result.bytesDownloaded)
            assertNotNull(result.file)
            assertEquals(10L, result.file?.length())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testMockWebServerInterruptedTransferRejected() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val context: Context = ApplicationProvider.getApplicationContext()
            // Server claims Content-Length 50, but disconnects after sending only 15 bytes
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "video/mp4")
                    .setBody(Buffer().write(ByteArray(15) { 0x33.toByte() }))
                    .setHeader("Content-Length", "50")
                    .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END)
            )

            val url = server.url("/video.mp4").toString()
            val result = OkHttpDownloader.downloadMediaFileDetailed(context, url, "video.mp4")
            assertFalse(result.isSuccessful)
            assertNull(result.file)
            assertTrue(
                result.errorMessage?.contains("interrupted") == true ||
                result.errorMessage?.contains("incomplete") == true ||
                result.errorMessage?.contains("size mismatch") == true ||
                result.errorMessage?.contains("stream") == true ||
                result.errorMessage?.contains("unexpected") == true ||
                result.errorMessage?.contains("timeout") == true
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testSupportedNonRasterFormatsValidation() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val context: Context = ApplicationProvider.getApplicationContext()
            val nonRasterFormats = listOf(
                Pair("audio/aac", "song.aac"),
                Pair("audio/flac", "music.flac"),
                Pair("audio/opus", "voice.opus"),
                Pair("audio/x-ms-wma", "audio.wma"),
                Pair("video/x-msvideo", "movie.avi"),
                Pair("video/x-flv", "video.flv"),
                Pair("video/3gpp", "clip.3gp"),
                Pair("video/mp2t", "segment.ts")
            )

            for ((mime, name) in nonRasterFormats) {
                val dummyBytes = ByteArray(32) { (it + 1).toByte() }
                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", mime)
                        .setHeader("Content-Length", dummyBytes.size.toString())
                        .setBody(Buffer().write(dummyBytes))
                )
                val url = server.url("/$name").toString()
                val result = OkHttpDownloader.downloadMediaFileDetailed(context, url, name)
                assertTrue("Format $name should succeed without BitmapFactory failure", result.isSuccessful)
                assertNotNull(result.file)
                assertEquals(32L, result.file?.length())
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testMediaWikiThumbResolvesWithCustomUploadPath() {
        val customWikiUrl = "https://mywiki.example.org/custom_folder/thumb/9/91/Diagram.png/400px-Diagram.png"
        val resolved = MediaClassifier.resolveOriginalMedia(customWikiUrl)
        assertEquals("https://mywiki.example.org/custom_folder/9/91/Diagram.png", resolved.mediaUrl)
        assertEquals(customWikiUrl, resolved.thumbnailUrl)
        assertEquals("Diagram.png", resolved.cleanTitle)

        val wikimediaUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/1/12/Taj_Mahal.jpg/500px-Taj_Mahal.jpg"
        val resolvedWm = MediaClassifier.resolveOriginalMedia(wikimediaUrl)
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/1/12/Taj_Mahal.jpg", resolvedWm.mediaUrl)
        assertEquals(wikimediaUrl, resolvedWm.thumbnailUrl)
        assertEquals("Taj_Mahal.jpg", resolvedWm.cleanTitle)
    }

    @Test
    fun testHostAndPathAwareExclusionRules() {
        // Legitimate media URLs containing gallery/comments/pin on other domains should NOT be excluded
        assertTrue(MediaClassifier.isValidMedia("https://mycdn.example.com/gallery/photo.jpg"))
        assertTrue(MediaClassifier.isValidMedia("https://cdn.example.org/comments/attachment.png"))
        assertTrue(MediaClassifier.isValidMedia("https://assets.example.net/pin/banner.webp"))

        // Webpage/comment pages on known hosting platforms SHOULD be excluded
        assertFalse(MediaClassifier.isValidMedia("https://imgur.com/gallery/a8f2k"))
        assertFalse(MediaClassifier.isValidMedia("https://reddit.com/r/Android/comments/xyz123/discussion"))
        assertFalse(MediaClassifier.isValidMedia("https://pinterest.com/pin/987654321/"))
        assertFalse(MediaClassifier.isValidMedia("https://en.wikipedia.org/wiki/File:Taj_Mahal.jpg"))
    }

    @Test
    fun testStrictDimensionFilteringAndAudioPreservation() {
        val imgSmall = ScrapedMediaItem("img1", "https://site.com/sm.jpg", "Small", isVideo = false, isAudio = false, category = MediaCategory.IMAGES)
        val imgMid = ScrapedMediaItem("img2", "https://site.com/mid.jpg", "Mid", isVideo = false, isAudio = false, category = MediaCategory.IMAGES)
        val imgLarge = ScrapedMediaItem("img3", "https://site.com/lg.jpg", "Large", isVideo = false, isAudio = false, category = MediaCategory.IMAGES)
        val imgUnknown = ScrapedMediaItem("img4", "https://site.com/unk.jpg", "Unknown", isVideo = false, isAudio = false, category = MediaCategory.IMAGES)
        val audioItem = ScrapedMediaItem("aud1", "https://site.com/song.mp3", "Song", isVideo = false, isAudio = true, category = MediaCategory.AUDIO)

        val dims = mapOf(
            "img1" to Pair(400, 300),
            "img2" to Pair(800, 500),
            "img3" to Pair(1200, 900)
            // img4 has no probed dims (unknown)
        )

        // Strict filter: minWidth = 800, minHeight = 600
        // img1 fails both
        assertFalse(passesDimensionFilter(imgSmall, 800, 600, dims))
        // img2 satisfies width (800) but fails height (500 < 600) -> strictly rejected
        assertFalse(passesDimensionFilter(imgMid, 800, 600, dims))
        // img3 satisfies both (1200 >= 800 and 900 >= 600) -> accepted
        assertTrue(passesDimensionFilter(imgLarge, 800, 600, dims))
        // img4 has unknown dimensions -> excluded in strict mode
        assertFalse(passesDimensionFilter(imgUnknown, 800, 600, dims))
        // audioItem has category AUDIO -> never filtered out because of image dimensions
        assertTrue(passesDimensionFilter(audioItem, 800, 600, dims))

        // Any dimensions (0, 0) accepts all items
        assertTrue(passesDimensionFilter(imgSmall, 0, 0, dims))
        assertTrue(passesDimensionFilter(imgMid, 0, 0, dims))
        assertTrue(passesDimensionFilter(imgLarge, 0, 0, dims))
        assertTrue(passesDimensionFilter(imgUnknown, 0, 0, dims))
        assertTrue(passesDimensionFilter(audioItem, 0, 0, dims))
    }

    @Test
    fun testVideosExcludedFromDimensionFiltering() {
        val videoMp4 = ScrapedMediaItem("vid1", "https://site.com/video.mp4", "Video", isVideo = true, isAudio = false, category = MediaCategory.VIDEOS)
        val streamHls = ScrapedMediaItem("str1", "https://site.com/stream.m3u8", "Stream", isVideo = true, isAudio = false, isStream = true, category = MediaCategory.STREAMS)
        val videoWebm = ScrapedMediaItem("vid2", "https://site.com/clip.webm", "Clip", isVideo = true, isAudio = false, category = MediaCategory.VIDEOS)

        val emptyDims = emptyMap<String, Pair<Int, Int>>()

        // Strict 4K filter (3840x2160): videos and streams should always pass regardless of dimensions
        assertTrue(passesDimensionFilter(videoMp4, 3840, 2160, emptyDims))
        assertTrue(passesDimensionFilter(streamHls, 3840, 2160, emptyDims))
        assertTrue(passesDimensionFilter(videoWebm, 1920, 1080, emptyDims))
    }

    @Test
    fun testVideoFragmentsAndStreamingChunksRejected() {
        val fragments = listOf(
            "https://cdn.example.com/stream/segment-001.ts",
            "https://cdn.example.com/hls/seg_12.ts",
            "https://cdn.example.com/dash/chunk-stream0-00001.m4s",
            "https://cdn.example.com/dash/init-stream0.m4s",
            "https://cdn.example.com/video/init.mp4",
            "https://cdn.example.com/video/frag-1.mp4",
            "https://cdn.example.com/stream/track-1-frag-5.m4v",
            "https://cdn.example.com/stream.mp4?sq=3&rn=2",
            "blob:https://example.com/39a2f-1234"
        )

        for (frag in fragments) {
            assertTrue("Fragment $frag should be detected as media fragment", MediaClassifier.isMediaFragment(frag))
            assertFalse("Fragment $frag should not be accepted as valid media", MediaClassifier.isValidMedia(frag))
        }

        // Legitimate standalone videos should NOT be marked as fragments
        val validVideos = listOf(
            "https://cdn.example.com/videos/full_trailer.mp4",
            "https://cdn.example.com/hls/master.m3u8",
            "https://cdn.example.com/dash/manifest.mpd",
            "https://cdn.example.com/assets/animation.webm"
        )

        for (vid in validVideos) {
            assertFalse("Valid video $vid should not be a fragment", MediaClassifier.isMediaFragment(vid))
            assertTrue("Valid video $vid should be accepted as valid media", MediaClassifier.isValidMedia(vid))
        }
    }

    @Test
    fun testVideoStreamingSliceCleaningAndCanonicalDeduplication() {
        val slice1 = "https://rr1---sn.googlevideo.com/videoplayback?expire=123&id=abc&itag=22&range=0-1048575&rn=1&sq=1"
        val slice2 = "https://rr1---sn.googlevideo.com/videoplayback?expire=123&id=abc&itag=22&range=1048576-2097151&rn=2&sq=2"

        // Both slices must produce the EXACT same canonical deduplication key
        val key1 = MediaClassifier.canonicalKey(slice1)
        val key2 = MediaClassifier.canonicalKey(slice2)
        assertEquals(key1, key2)

        // Cleaning media URL removes range and sequence params so full file is downloaded
        val cleaned = MediaClassifier.cleanMediaUrl(slice1)
        assertFalse(cleaned.contains("range="))
        assertFalse(cleaned.contains("rn="))
        assertFalse(cleaned.contains("sq="))
        assertTrue(cleaned.contains("id=abc"))
        assertTrue(cleaned.contains("itag=22"))
    }

    @Test
    fun testStreamingClassificationAccuracy() {
        // An MP4 located under /hls/ path should be classified as MP4, NOT an HLS stream
        val hlsPathMp4 = "https://cdn.example.com/hls/sample.mp4"
        val classMp4 = MediaClassifier.classify(hlsPathMp4)
        assertEquals(MediaCategory.VIDEOS, classMp4.category)
        assertEquals("MP4", classMp4.format)
        assertFalse(classMp4.isStream)

        // Genuine HLS manifest
        val hlsManifest = "https://cdn.example.com/live/playlist.m3u8"
        val classHls = MediaClassifier.classify(hlsManifest)
        assertEquals(MediaCategory.STREAMS, classHls.category)
        assertEquals("HLS (m3u8)", classHls.format)
        assertTrue(classHls.isStream)

        // Genuine DASH manifest
        val dashManifest = "https://cdn.example.com/dash/video.mpd"
        val classDash = MediaClassifier.classify(dashManifest)
        assertEquals(MediaCategory.STREAMS, classDash.category)
        assertEquals("DASH (mpd)", classDash.format)
        assertTrue(classDash.isStream)
    }

    @Test
    fun testWikipediaFilePagesAndRelativeLinksRejection() {
        // Wikipedia/MediaWiki HTML description pages must be rejected
        assertFalse(MediaClassifier.isValidMedia("/wiki/File:Taj_Mahal-10_(cropped).jpg"))
        assertFalse(MediaClassifier.isValidMedia("/wiki/File:Mumtaz_Mahal.jpg"))
        assertFalse(MediaClassifier.isValidMedia("https://en.wikipedia.org/wiki/File:Taj_Mahal-10_(cropped).jpg"))
        assertFalse(MediaClassifier.isValidMedia("https://en.wikipedia.org/wiki/File:Shahjahan_on_globe.jpg"))
        assertFalse(MediaClassifier.isValidMedia("/w/index.php?title=File:Taj_Mahal.jpg"))

        // Genuine Wikimedia uploads and thumbnails must be accepted
        assertTrue(MediaClassifier.isValidMedia("https://upload.wikimedia.org/wikipedia/commons/1/1d/Taj_Mahal-10_(cropped).jpg"))
        assertTrue(MediaClassifier.isValidMedia("https://upload.wikimedia.org/wikipedia/commons/thumb/1/1d/Taj_Mahal-10_%28cropped%29.jpg/500px-Taj_Mahal-10_%28cropped%29.jpg"))
        assertTrue(MediaClassifier.isValidMedia("//upload.wikimedia.org/wikipedia/commons/thumb/1/1d/Taj_Mahal-10_%28cropped%29.jpg/500px-Taj_Mahal-10_%28cropped%29.jpg"))
    }

    @Test
    fun testMockWebServerHtmlDisguisedAsJpgRejected() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val context: Context = ApplicationProvider.getApplicationContext()
            // Server responds with 200 and image/jpeg Content-Type, but body is an HTML page (like Wikipedia error or interstitial)
            val htmlBody = "<!DOCTYPE html><html><head><title>Wikipedia</title></head><body>File details page</body></html>"
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "image/jpeg")
                    .setBody(htmlBody)
            )

            val url = server.url("/File:Taj_Mahal.jpg").toString()
            val result = OkHttpDownloader.downloadMediaFileDetailed(context, url, "File_Taj_Mahal.jpg")
            assertFalse("HTML content disguised as JPEG must be rejected", result.isSuccessful)
            assertNull("No file must be committed for HTML content", result.file)
            assertTrue(result.errorMessage?.contains("HTML webpage") == true || result.errorMessage?.contains("corrupt or invalid") == true)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testMockWebServerContentRangeMismatchRestartsCleanly() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val context: Context = ApplicationProvider.getApplicationContext()
            val downloadId = "test_mismatch_resume"
            val targetDir = OkHttpDownloader.getTargetStorageDir(context)
            val tempFile = File(targetDir, "$downloadId.mp4.downloading")
            // Local file has 10 bytes
            tempFile.writeBytes(ByteArray(10) { 0x11.toByte() })

            // Server responds with Content-Range starting at 0 instead of 10 or with mismatched range
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Type", "video/mp4")
                    .setHeader("Content-Range", "bytes 0-19/20")
                    .setBody(Buffer().write(ByteArray(20) { 0x22.toByte() }))
            )
            // Follow-up clean restart returns full 20 bytes
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "video/mp4")
                    .setBody(Buffer().write(ByteArray(20) { 0x33.toByte() }))
                    .setHeader("Content-Length", "20")
            )

            val url = server.url("/video.mp4").toString()
            val result = OkHttpDownloader.downloadMediaFileDetailed(
                context = context,
                mediaUrl = url,
                fileName = "video.mp4",
                downloadId = downloadId
            )

            assertTrue(result.isSuccessful)
            assertEquals(20L, result.bytesDownloaded)
            assertNotNull(result.file)
            assertEquals(20L, result.file?.length())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testMockWebServerHttp416AlreadyCompleteCommitsFile() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val context: Context = ApplicationProvider.getApplicationContext()
            val downloadId = "test_416_complete"
            val targetDir = OkHttpDownloader.getTargetStorageDir(context)
            val tempFile = File(targetDir, "$downloadId.mp4.downloading")
            // Local file has full 30 bytes
            tempFile.writeBytes(ByteArray(30) { 0x44.toByte() })

            // Server returns 416 with total length 30 matching local existing bytes
            server.enqueue(
                MockResponse()
                    .setResponseCode(416)
                    .setHeader("Content-Range", "bytes */30")
            )

            val url = server.url("/video.mp4").toString()
            val result = OkHttpDownloader.downloadMediaFileDetailed(
                context = context,
                mediaUrl = url,
                fileName = "video.mp4",
                downloadId = downloadId
            )

            assertTrue(result.isSuccessful)
            assertEquals(30L, result.bytesDownloaded)
            assertNotNull(result.file)
            assertEquals(30L, result.file?.length())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun contentAwareFilterRejectsChromeAssetsAndAcceptsContentMedia() {
        // UI Chrome rejections
        assertTrue(ContentAwareMediaFilter.isUiChromeUrl("https://en.wikipedia.org/static/images/mobile/copyright/wikipedia-wordmark-en.svg"))
        assertTrue(ContentAwareMediaFilter.isUiChromeUrl("https://example.com/assets/logo.png"))
        assertTrue(ContentAwareMediaFilter.isUiChromeUrl("https://example.com/favicon-32x32.png"))
        assertTrue(ContentAwareMediaFilter.isUiChromeUrl("https://example.com/banners/donate_fundraising_banner.jpg"))
        assertTrue(ContentAwareMediaFilter.isUiChromeUrl("https://example.com/icons/nav-arrow-right.png"))
        // Tiny spatial dimensions (< 48x48)
        assertTrue(ContentAwareMediaFilter.isUiChromeUrl("https://example.com/img.jpg", width = 16, height = 16))
        // Extreme aspect ratios (> 12:1 line separators)
        assertTrue(ContentAwareMediaFilter.isUiChromeUrl("https://example.com/divider.png", width = 800, height = 10))

        // Legitimate content media acceptance
        assertFalse(ContentAwareMediaFilter.isUiChromeUrl("https://upload.wikimedia.org/wikipedia/commons/1/10/Taj_Mahal-10_%28cropped%29.jpg"))
        assertFalse(ContentAwareMediaFilter.isUiChromeUrl("https://example.com/photos/landscape_2026.jpg", width = 1920, height = 1080))
        assertFalse(ContentAwareMediaFilter.isUiChromeUrl("https://example.com/stream/playlist.m3u8", isVideoOrAudioOrStream = true))
    }

    @Test
    fun dualExtractorResetClearsDiscoveredItems() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val extractor = DualMediaExtractor(context)

        val item1 = ScrapedMediaItem(
            id = "test_item_1",
            url = "https://example.com/photo.jpg",
            title = "Photo 1",
            isVideo = false,
            provenance = MediaProvenance(
                extractorEngine = "Static HTML",
                pageUrl = "https://example.com"
            ),
            downloadHeaders = mapOf("Referer" to "https://example.com")
        )
        extractor.addDiscoveredItem(item1)
        assertEquals(1, extractor.state.value.items.size)

        // Reset must clear all items and reset state to initial/idle
        extractor.reset()
        assertTrue(extractor.state.value.items.isEmpty())
        assertFalse(extractor.state.value.isRunning)
        assertEquals("", extractor.state.value.pageTitle)
    }

    @Test
    fun scrapedMediaItemUnifiedCandidateModelHasProvenanceAndHeaders() {
        val provenance = MediaProvenance(
            extractorEngine = "Static HTML (img)",
            pageUrl = "https://example.com/gallery",
            domSelector = "img"
        )
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0",
            "Referer" to "https://example.com/gallery"
        )
        val item = ScrapedMediaItem(
            id = "scraped_item_99",
            url = "https://example.com/media/hero.jpg",
            title = "Hero Image",
            isVideo = false,
            category = MediaCategory.IMAGES,
            format = "JPG",
            provenance = provenance,
            metadata = mapOf("resolution" to "1920x1080"),
            downloadHeaders = headers
        )

        assertEquals("Static HTML (img)", item.provenance.extractorEngine)
        assertEquals("https://example.com/gallery", item.provenance.pageUrl)
        assertEquals("img", item.provenance.domSelector)
        assertEquals("1920x1080", item.metadata["resolution"])
        assertEquals("https://example.com/gallery", item.downloadHeaders["Referer"])
    }

    @Test
    fun isSvgDetectionAndDefaultFiltering() {
        val svgItem = ScrapedMediaItem(
            id = "svg_1",
            url = "https://example.com/vectors/illustration.svg",
            title = "Illustration",
            format = "SVG",
            mimeType = "image/svg+xml"
        )
        val rasterItem = ScrapedMediaItem(
            id = "jpg_1",
            url = "https://example.com/photos/landscape.jpg",
            title = "Landscape",
            format = "JPG",
            mimeType = "image/jpeg"
        )

        // isSvg helper verification
        assertTrue(MediaClassifier.isSvg(svgItem.url, svgItem.mimeType, svgItem.format))
        assertFalse(MediaClassifier.isSvg(rasterItem.url, rasterItem.mimeType, rasterItem.format))

        val allItems = listOf(svgItem, rasterItem)

        // Default: keep SVG hidden (showSvg = false)
        val defaultFiltered = allItems.filter { !MediaClassifier.isSvg(it.url, it.mimeType, it.format) }
        assertEquals(1, defaultFiltered.size)
        assertEquals("jpg_1", defaultFiltered.first().id)

        // With toggle enabled (showSvg = true)
        val showSvgFiltered = allItems
        assertEquals(2, showSvgFiltered.size)
    }

    @Test
    fun deduplicateScrapedItemsAcrossMultipleScrapers() {
        // Scraper 1: Static HTML discovered thumbnail
        val itemFromStaticHtml = ScrapedMediaItem(
            id = "static_1",
            url = "https://upload.wikimedia.org/wikipedia/commons/thumb/1/10/Taj_Mahal-10_%28cropped%29.jpg/500px-Taj_Mahal-10_%28cropped%29.jpg",
            title = "Taj Mahal Thumbnail",
            isVideo = false,
            thumbnailUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/1/10/Taj_Mahal-10_%28cropped%29.jpg/500px-Taj_Mahal-10_%28cropped%29.jpg",
            provenance = MediaProvenance(
                extractorEngine = "Static HTML",
                pageUrl = "https://en.wikipedia.org/wiki/Taj_Mahal"
            ),
            downloadHeaders = mapOf("Referer" to "https://en.wikipedia.org/wiki/Taj_Mahal")
        )

        // Scraper 2: Chromium Browser discovered full-resolution image with unencoded parens
        val itemFromBrowser = ScrapedMediaItem(
            id = "browser_1",
            url = "https://upload.wikimedia.org/wikipedia/commons/1/10/Taj_Mahal-10_(cropped).jpg",
            title = "Taj Mahal Original",
            isVideo = false,
            resolutionHint = "2048x1536",
            provenance = MediaProvenance(
                extractorEngine = "Browser",
                pageUrl = "https://en.wikipedia.org/wiki/Taj_Mahal"
            ),
            downloadHeaders = mapOf("User-Agent" to "Mozilla/5.0")
        )

        // Scraper 3: Unrelated different image
        val distinctItem = ScrapedMediaItem(
            id = "static_2",
            url = "https://upload.wikimedia.org/wikipedia/commons/2/22/Red_Fort.jpg",
            title = "Red Fort",
            isVideo = false,
            provenance = MediaProvenance(
                extractorEngine = "Static HTML",
                pageUrl = "https://en.wikipedia.org/wiki/Taj_Mahal"
            )
        )

        val scrapedList = listOf(itemFromStaticHtml, itemFromBrowser, distinctItem)

        // Automatic deduplication before downloading
        val deduplicated = MediaClassifier.deduplicateScrapedItems(scrapedList)

        // Exactly 2 items remain (the duplicate Taj Mahal items merged into 1)
        assertEquals(2, deduplicated.size)

        val mergedTaj = deduplicated.first { it.url.contains("Taj_Mahal-10") }
        // Retains full resolution URL
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/1/10/Taj_Mahal-10_(cropped).jpg", mergedTaj.url)
        // Preserves thumbnail from scraper 1
        assertNotNull(mergedTaj.thumbnailUrl)
        // Merges provenance to show both scrapers contributed
        assertTrue(mergedTaj.provenance.extractorEngine.contains("Static HTML"))
        assertTrue(mergedTaj.provenance.extractorEngine.contains("Browser"))
        // Merges headers from both scrapers
        assertTrue(mergedTaj.downloadHeaders.containsKey("Referer"))
        assertTrue(mergedTaj.downloadHeaders.containsKey("User-Agent"))
    }

    @Test
    fun testEnhancedFragmentAndSegmentFiltering() {
        val fragmentCases = listOf(
            "https://cdn.example.com/stream/segment-001.ts",
            "https://cdn.example.com/hls/seg_12.ts",
            "https://cdn.example.com/live/hls-50.ts",
            "https://cdn.example.com/video/track-1-frag-5.ts",
            "https://cdn.example.com/dash/chunk-stream0-00001.m4s",
            "https://cdn.example.com/dash/init-stream0.m4s",
            "https://cdn.example.com/video/init.mp4",
            "https://cdn.example.com/video/init_video.mp4",
            "https://cdn.example.com/video/header.mp4",
            "https://cdn.example.com/video/frag-1.mp4",
            "https://cdn.example.com/stream.mp4?sq=3&rn=2",
            "https://cdn.example.com/stream.mp4?bytestart=0&byteend=5000",
            "https://cdn.example.com/stream/part-1.dash",
            "blob:https://example.com/abc-123"
        )

        for (frag in fragmentCases) {
            assertTrue("Expected fragment detection for $frag", MediaClassifier.isMediaFragment(frag))
            assertFalse("Expected non-valid media for $frag", MediaClassifier.isValidMedia(frag))
        }

        val validVideos = listOf(
            "https://cdn.example.com/videos/full_trailer.mp4",
            "https://cdn.example.com/hls/master.m3u8",
            "https://cdn.example.com/dash/manifest.mpd",
            "https://cdn.example.com/assets/animation.webm",
            "https://cdn.example.com/shows/episode_01.mkv"
        )

        for (vid in validVideos) {
            assertFalse("Valid video should not be fragment: $vid", MediaClassifier.isMediaFragment(vid))
            assertTrue("Valid video should be accepted as media: $vid", MediaClassifier.isValidMedia(vid))
        }
    }

    @Test
    fun testMultiHostCdnNormalizationAndDeduplication() {
        // 1. Reddit: preview.redd.it -> i.redd.it
        val redditPreview = "https://preview.redd.it/abcdef123.jpg?width=640&crop=smart&auto=webp&s=xyz789"
        val redditResolved = MediaClassifier.resolveOriginalMedia(redditPreview)
        assertEquals("https://i.redd.it/abcdef123.jpg", redditResolved.mediaUrl)
        assertEquals(redditPreview, redditResolved.thumbnailUrl)

        val redditKey1 = MediaClassifier.deduplicationKey(redditPreview)
        val redditKey2 = MediaClassifier.deduplicationKey("https://i.redd.it/abcdef123.jpg")
        assertEquals(redditKey1, redditKey2)

        // 2. Imgur: i.imgur.com/{id}m.jpg -> i.imgur.com/{id}.jpg
        val imgurThumb = "https://i.imgur.com/vAbCdEm.jpg"
        val imgurResolved = MediaClassifier.resolveOriginalMedia(imgurThumb)
        assertEquals("https://i.imgur.com/vAbCdE.jpg", imgurResolved.mediaUrl)

        val imgurKey1 = MediaClassifier.deduplicationKey(imgurThumb)
        val imgurKey2 = MediaClassifier.deduplicationKey("https://i.imgur.com/vAbCdE.jpg")
        assertEquals(imgurKey1, imgurKey2)

        // 3. Twitter / X: pbs.twimg.com name query -> name=orig
        val twitterThumb = "https://pbs.twimg.com/media/F123456?format=jpg&name=small"
        val twitterResolved = MediaClassifier.resolveOriginalMedia(twitterThumb)
        assertEquals("https://pbs.twimg.com/media/F123456?format=jpg&name=orig", twitterResolved.mediaUrl)

        val twitterKey1 = MediaClassifier.deduplicationKey(twitterThumb)
        val twitterKey2 = MediaClassifier.deduplicationKey("https://pbs.twimg.com/media/F123456?format=jpg&name=orig")
        assertEquals(twitterKey1, twitterKey2)

        // 4. WordPress / Jetpack Photon CDN: i0.wp.com -> master
        val wpThumb = "https://i0.wp.com/example.com/wp-content/uploads/2026/04/photo.jpg?resize=300%2C200"
        val wpResolved = MediaClassifier.resolveOriginalMedia(wpThumb)
        assertEquals("https://example.com/wp-content/uploads/2026/04/photo.jpg", wpResolved.mediaUrl)

        val wpKey1 = MediaClassifier.deduplicationKey(wpThumb)
        val wpKey2 = MediaClassifier.deduplicationKey("https://example.com/wp-content/uploads/2026/04/photo.jpg")
        assertEquals(wpKey1, wpKey2)
    }

    @Test
    fun testBase64InlineDataUriSupport() = runBlocking {
        // 1. Tiny spacer GIF must be rejected
        val tinySpacer = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"
        assertFalse(MediaClassifier.isSubstantialDataUri(tinySpacer))
        assertFalse(MediaClassifier.isValidMedia(tinySpacer))

        // 2. Substantial Base64 image (> 2048 chars) must be accepted
        val dummyData = "A".repeat(2500)
        val largeDataUri = "data:image/png;base64,$dummyData"
        assertTrue(MediaClassifier.isSubstantialDataUri(largeDataUri))
        assertTrue(MediaClassifier.isValidMedia(largeDataUri))

        val parsedInfo = MediaClassifier.parseDataUriInfo(largeDataUri)
        assertNotNull(parsedInfo)
        assertEquals("image/png", parsedInfo?.mimeType)
        assertEquals("PNG", parsedInfo?.format)

        // 3. OkHttpDownloader decode data URI
        val context: Context = ApplicationProvider.getApplicationContext()
        // 1x1 valid PNG in Base64 padded to > 2048 chars for test
        // Valid 1x1 PNG bytes:
        val validPngBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        val validDataUri = "data:image/png;base64,$validPngBase64"
        val res = OkHttpDownloader.downloadMediaFileDetailed(context, validDataUri, "embedded_canvas.png")
        assertTrue(res.isSuccessful)
        assertNotNull(res.file)
        assertTrue(res.file?.exists() == true)
        assertEquals("image/png", res.mimeType)
    }

    @Test
    fun testAdBlockEngineIntegration() {
        val adUrls = listOf(
            "https://googleads.g.doubleclick.net/pagead/ads?client=ca-pub-123",
            "https://www.google-analytics.com/analytics.js",
            "https://static.criteo.net/js/ld/ld.js",
            "https://cdn.taboola.com/libtrc/unip/123/tfa.js",
            "https://widgets.outbrain.com/outbrain.js",
            "https://ib.adnxs.com/seg?add=1",
            "https://main.exoclick.com/tag.js",
            "https://b.clarity.ms/collect",
            "https://static.hotjar.com/c/hotjar-123.js",
            "https://connect.facebook.net/en_us/fbevents.js"
        )

        for (ad in adUrls) {
            assertTrue("Expected AdBlock detection for $ad", com.amresalehin.emregallery.data.AdBlockEngine.isAdOrTracker(ad))
            assertFalse("Expected isValidMedia to reject ad URL $ad", MediaClassifier.isValidMedia(ad))
        }

        val emptyResp = com.amresalehin.emregallery.data.AdBlockEngine.createEmptyResponse()
        assertEquals("text/plain", emptyResp.mimeType)
        assertEquals("UTF-8", emptyResp.encoding)
        assertNotNull(emptyResp.data)
        assertEquals(0, emptyResp.data.available())

        val regularMedia = "https://images.unsplash.com/photo-123.jpg"
        assertFalse(com.amresalehin.emregallery.data.AdBlockEngine.isAdOrTracker(regularMedia))
        assertTrue(MediaClassifier.isValidMedia(regularMedia))
    }

    @Test
    fun testCloudflareAndAntiBotChallengePayloadRejection() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val context: Context = ApplicationProvider.getApplicationContext()
            // Server returns HTTP 200 with Cloudflare anti-bot challenge page
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/html; charset=UTF-8")
                    .setBody("""
                        <!DOCTYPE html>
                        <html>
                        <head><title>Just a moment...</title></head>
                        <body>
                            <h2>DDoS protection by Cloudflare</h2>
                            <p>Ray ID: 7abc123456</p>
                        </body>
                        </html>
                    """.trimIndent())
            )

            val url = server.url("/protected_video.mp4").toString()
            val result = OkHttpDownloader.downloadMediaFileDetailed(context, url, "protected_video.mp4")
            assertFalse(result.isSuccessful)
            assertNull(result.file)
            assertTrue(result.errorMessage?.contains("webpage") == true || result.errorMessage?.contains("HTML") == true)
        } finally {
            server.shutdown()
        }
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
    fun downloadQueueManagerActiveDownloadState() {
        val manager = com.amresalehin.emregallery.data.DownloadQueueManager
        // Ensure starting state
        manager.cancelAll(ApplicationProvider.getApplicationContext())
        assertFalse(manager.inMemoryDownloading.value)

        // Mark 2 downloads started
        manager.markDownloadStarted(2)
        assertTrue(manager.inMemoryDownloading.value)

        // 1 completes
        manager.markDownloadCompleted()
        assertTrue(manager.inMemoryDownloading.value)

        // 2nd completes
        manager.markDownloadCompleted()
        assertFalse(manager.inMemoryDownloading.value)
    }

    @Test
    fun downloadQueueManagerCancelAllResetsState() {
        val manager = com.amresalehin.emregallery.data.DownloadQueueManager
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        manager.markDownloadStarted(5)
        assertTrue(manager.inMemoryDownloading.value)

        manager.cancelAll(ctx)
        assertFalse(manager.inMemoryDownloading.value)
    }

    @Test
    fun testDownloaderViewModeEnum() {
        assertEquals("SEARCH_PAGE", DownloaderViewMode.SEARCH_PAGE.name)
        assertEquals("DOWNLOADER_PAGE", DownloaderViewMode.DOWNLOADER_PAGE.name)
        assertEquals("SAVED_LIBRARY", DownloaderViewMode.SAVED_LIBRARY.name)
    }

    @Test
    fun testPlatformDetectionFromUrls() {
        assertEquals(DetectedPlatform.GENERIC_WEB, detectPlatform("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(DetectedPlatform.GENERIC_WEB, detectPlatform("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(DetectedPlatform.GENERIC_WEB, detectPlatform("https://instagram.com/reel/Cx123456/"))
        assertEquals(DetectedPlatform.GENERIC_WEB, detectPlatform("https://www.tiktok.com/@user/video/1234567890"))
        assertEquals(DetectedPlatform.GENERIC_WEB, detectPlatform("https://x.com/user/status/123456"))
        assertEquals(DetectedPlatform.GENERIC_WEB, detectPlatform("https://twitter.com/user/status/123456"))
        assertEquals(DetectedPlatform.GENERIC_WEB, detectPlatform("https://reddit.com/r/pics/comments/xyz123"))
        assertEquals(DetectedPlatform.GENERIC_WEB, detectPlatform("https://pinterest.com/pin/123456789/"))
        assertEquals(DetectedPlatform.GENERIC_WEB, detectPlatform("https://www.facebook.com/watch/?v=123456"))
        assertEquals(DetectedPlatform.DIRECT_MEDIA, detectPlatform("https://example.com/videos/stream.m3u8"))
        assertEquals(DetectedPlatform.DIRECT_MEDIA, detectPlatform("https://example.com/clip.mp4"))
        assertEquals(DetectedPlatform.DIRECT_MEDIA, detectPlatform("https://example.com/photo.jpg"))
        assertEquals(DetectedPlatform.GENERIC_WEB, detectPlatform("https://wikipedia.org/wiki/Earth"))
    }

    @Test
    fun testBrowserHistoryManagerRecordAndClear() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val historyManager = com.amresalehin.emregallery.data.BrowserHistoryManager(ctx)
        historyManager.recordVisit("https://example.com/test", "Test Site")
        historyManager.clearHistory()
        assertTrue(true)
    }

    @Test
    fun handleDownloadFailureDoesNotOverwritePausedStatus() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val manager = com.amresalehin.emregallery.data.DownloadActivityManager
        manager.initialize(ctx)

        val itemId = "test_pause_item_${System.currentTimeMillis()}"
        manager.recordQueued(
            id = itemId,
            title = "Video Clip",
            mediaUrl = "https://example.com/clip.mp4"
        )

        // Pause the item
        manager.pauseDownload(ctx, itemId)
        val paused = manager.activities.value.first { it.id == itemId }
        assertEquals(com.amresalehin.emregallery.data.DownloadActivityStatus.PAUSED, paused.status)

        // Simulating worker cancellation/failure callback
        val handled = manager.handleDownloadFailure(
            context = ctx,
            itemId = itemId,
            statusCode = -1,
            errorMessage = "Socket timeout",
            retryAfterSeconds = null
        )

        assertFalse("handleDownloadFailure should ignore failure callbacks for paused item", handled)
        val afterCallback = manager.activities.value.first { it.id == itemId }
        assertEquals(com.amresalehin.emregallery.data.DownloadActivityStatus.PAUSED, afterCallback.status)
    }

    @Test
    fun handleDownloadFailureDoesNotOverwriteCancelledStatus() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val manager = com.amresalehin.emregallery.data.DownloadActivityManager
        manager.initialize(ctx)

        val itemId = "test_cancel_item_${System.currentTimeMillis()}"
        manager.recordQueued(
            id = itemId,
            title = "Video Clip 2",
            mediaUrl = "https://example.com/clip2.mp4"
        )

        manager.cancelAllActive(ctx)
        val cancelled = manager.activities.value.first { it.id == itemId }
        assertEquals(com.amresalehin.emregallery.data.DownloadActivityStatus.FAILED, cancelled.status)
        assertEquals("Cancelled by user", cancelled.errorMessage)

        // Worker error arrives after cancellation
        val handled = manager.handleDownloadFailure(
            context = ctx,
            itemId = itemId,
            statusCode = -1,
            errorMessage = "Connection reset by peer",
            retryAfterSeconds = null
        )

        assertFalse("handleDownloadFailure should not restart retry for user-cancelled item", handled)
        val afterCallback = manager.activities.value.first { it.id == itemId }
        assertEquals(com.amresalehin.emregallery.data.DownloadActivityStatus.FAILED, afterCallback.status)
        assertEquals("Cancelled by user", afterCallback.errorMessage)
    }

    @Test
    fun stackConsolidationDoesNotMergeOnTitleAlone() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val repo = com.amresalehin.emregallery.data.MediaInboxRepository.getInstance(ctx)
        repo.clearAll()

        val stack1 = MediaInboxStack(
            id = "stack_page_1",
            title = "Home",
            sourceDomain = "example.com",
            sourceUrl = "https://example.com/page1",
            items = listOf(
                MediaInboxItem(
                    id = "item_p1",
                    title = "photo1.jpg",
                    localUri = "/tmp/photo1.jpg",
                    sourceUrl = "https://example.com/photo1.jpg",
                    isVideo = false
                )
            )
        )

        val stack2 = MediaInboxStack(
            id = "stack_page_2",
            title = "Home",
            sourceDomain = "instagram.com",
            sourceUrl = "https://instagram.com/page2",
            items = listOf(
                MediaInboxItem(
                    id = "item_p2",
                    title = "photo2.jpg",
                    localUri = "/tmp/photo2.jpg",
                    sourceUrl = "https://instagram.com/photo2.jpg",
                    isVideo = false
                )
            )
        )

        repo.addStack(stack1)
        repo.addStack(stack2)

        val stacks = repo.stacks.value
        assertEquals("Two distinct pages with title 'Home' must not be merged into one stack", 2, stacks.size)
    }

    @Test
    fun addItemToStackDeletesReplacedFileOnDisk() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val repo = com.amresalehin.emregallery.data.MediaInboxRepository.getInstance(ctx)
        repo.clearAll()

        // Create temporary old file
        val oldFile = File(ctx.cacheDir, "old_download_${System.currentTimeMillis()}.jpg")
        oldFile.writeText("sample bytes")
        assertTrue(oldFile.exists())

        val newFile = File(ctx.cacheDir, "new_download_${System.currentTimeMillis()}.jpg")
        newFile.writeText("new sample bytes")
        assertTrue(newFile.exists())

        val sourceUrl = "https://example.com/asset.jpg"
        val initialItem = MediaInboxItem(
            id = "item_v1",
            title = "asset.jpg",
            localUri = oldFile.absolutePath,
            sourceUrl = sourceUrl,
            isVideo = false
        )

        repo.addItemToStack(
            stackId = "stack_rep_1",
            item = initialItem,
            stackTitle = "Test Stack",
            sourceDomain = "example.com",
            sourceUrl = "https://example.com/post"
        )

        val updatedItem = MediaInboxItem(
            id = "item_v2",
            title = "asset.jpg",
            localUri = newFile.absolutePath,
            sourceUrl = sourceUrl,
            isVideo = false
        )

        // Save new version with same sourceUrl
        repo.addItemToStack(
            stackId = "stack_rep_1",
            item = updatedItem,
            stackTitle = "Test Stack",
            sourceDomain = "example.com",
            sourceUrl = "https://example.com/post"
        )

        assertFalse("Replaced file should be deleted from disk", oldFile.exists())
        assertTrue("New file should still exist", newFile.exists())

        newFile.delete()
    }
}



