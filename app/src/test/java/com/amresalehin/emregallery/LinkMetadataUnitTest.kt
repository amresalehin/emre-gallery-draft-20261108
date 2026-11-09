package com.amresalehin.emregallery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.amresalehin.emregallery.data.LinkMetadataFetcher
import com.amresalehin.emregallery.data.MediaInboxStack
import com.amresalehin.emregallery.data.PocketRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config
class LinkMetadataUnitTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pocket_bookmarks_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun testParseOpenGraphMetadata() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta property="og:title" content="Jetpack Compose Architecture Guide &amp; Best Practices" />
                <meta property="og:description" content="A comprehensive deep dive into modern Android UI development with Jetpack Compose." />
                <meta property="og:image" content="https://developer.android.com/images/compose-hero.png" />
                <meta property="og:site_name" content="Android Developers" />
                <link rel="icon" href="/favicon.ico" />
            </head>
            <body>
                <h1>Android Compose</h1>
            </body>
            </html>
        """.trimIndent()

        val metadata = LinkMetadataFetcher.parseHtml(html, "https://developer.android.com/jetpack/compose")

        assertEquals("Jetpack Compose Architecture Guide & Best Practices", metadata.title)
        assertEquals("A comprehensive deep dive into modern Android UI development with Jetpack Compose.", metadata.description)
        assertEquals("https://developer.android.com/images/compose-hero.png", metadata.previewImageUrl)
        assertEquals("Android Developers", metadata.siteName)
        assertEquals("developer.android.com", metadata.domain)
        assertEquals("https://developer.android.com/favicon.ico", metadata.faviconUrl)
    }

    @Test
    fun testParseTwitterCardMetadataFallback() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Fallback Standard Title</title>
                <meta name="twitter:title" content="Twitter Card Title Example" />
                <meta name="twitter:description" content="Twitter Card Summary Description" />
                <meta name="twitter:image" content="/assets/twitter-card.webp" />
            </head>
            <body>
            </body>
            </html>
        """.trimIndent()

        val metadata = LinkMetadataFetcher.parseHtml(html, "https://news.example.org/article/42")

        assertEquals("Twitter Card Title Example", metadata.title)
        assertEquals("Twitter Card Summary Description", metadata.description)
        assertEquals("https://news.example.org/assets/twitter-card.webp", metadata.previewImageUrl)
        assertEquals("news.example.org", metadata.domain)
    }

    @Test
    fun testParseStandardHtmlMetadataFallback() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Standard Page Title - Example Blog</title>
                <meta name="description" content="This is a standard meta description tag for search engines." />
                <link rel="image_src" href="//cdn.example.org/preview.jpg" />
                <link rel="apple-touch-icon" href="https://example.org/apple-touch-icon.png" />
            </head>
            <body>
                <p>Some body text that shouldn't override meta description.</p>
            </body>
            </html>
        """.trimIndent()

        val metadata = LinkMetadataFetcher.parseHtml(html, "https://example.org/post/100")

        assertEquals("Standard Page Title - Example Blog", metadata.title)
        assertEquals("This is a standard meta description tag for search engines.", metadata.description)
        assertEquals("https://cdn.example.org/preview.jpg", metadata.previewImageUrl)
    }

    @Test
    fun testRelativeImageResolution() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta property="og:title" content="Documentation Page" />
                <meta property="og:image" content="images/art.jpg" />
            </head>
            <body></body>
            </html>
        """.trimIndent()

        val metadata = LinkMetadataFetcher.parseHtml(html, "https://docs.kotlinlang.org/intro/start.html")

        assertEquals("https://docs.kotlinlang.org/intro/images/art.jpg", metadata.previewImageUrl)
    }

    @Test
    fun testPocketRepositoryAutomaticMetadataAndPersistence() = runBlocking {
        val testScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val repo = PocketRepository(context, testScope)

        // Add a bookmark with title, description, and previewImageUrl
        val bookmark = repo.addBookmark(
            title = "Sample Title",
            url = "https://example.com/article",
            description = "Sample Description",
            previewImageUrl = "https://example.com/cover.jpg",
            autoFetchMetadata = false
        )

        assertEquals("Sample Title", bookmark.title)
        assertEquals("Sample Description", bookmark.description)
        assertEquals("https://example.com/cover.jpg", bookmark.previewImageUrl)
        assertEquals(1, repo.bookmarks.value.size)

        // Verify persistence in SharedPreferences
        val repo2 = PocketRepository(context, testScope)
        val restored = repo2.bookmarks.value.first()
        assertEquals(bookmark.id, restored.id)
        assertEquals("Sample Title", restored.title)
        assertEquals("Sample Description", restored.description)
        assertEquals("https://example.com/cover.jpg", restored.previewImageUrl)
    }

    @Test
    fun testMediaInboxStackPreviewImageUrlField() {
        val stackWithPreview = MediaInboxStack(
            id = "test_preview_1",
            title = "Article Preview",
            sourceDomain = "blog.example.com",
            sourceUrl = "https://blog.example.com/post",
            items = emptyList(),
            description = "Post description",
            previewImageUrl = "https://blog.example.com/hero.jpg"
        )

        assertEquals("https://blog.example.com/hero.jpg", stackWithPreview.previewImageUrl)
        assertTrue(stackWithPreview.items.isEmpty())

        val stackWithoutPreview = MediaInboxStack(
            id = "test_preview_2",
            title = "No Preview",
            sourceDomain = "example.com",
            sourceUrl = "https://example.com",
            items = emptyList()
        )

        assertEquals("", stackWithoutPreview.previewImageUrl)
    }

    @Test
    fun testParseJsonLdMetadata() {
        val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <script type="application/ld+json">
                {
                    "@context": "https://schema.org",
                    "@graph": [
                        {
                            "@type": "WebPage",
                            "name": "Magnific AI (by Freepik) | The AI Creative Platform",
                            "description": "The complete platform of creative AI tools for image, video, and audio generation.",
                            "image": "https://media.magnific.com/meta-tags-social/mg-home-og.jpg?w=1200&amp;h=675"
                        }
                    ]
                }
                </script>
            </head>
            <body></body>
            </html>
        """.trimIndent()

        val metadata = LinkMetadataFetcher.parseHtml(html, "https://magnific.com")

        assertEquals("Magnific AI (by Freepik) | The AI Creative Platform", metadata.title)
        assertEquals("The complete platform of creative AI tools for image, video, and audio generation.", metadata.description)
        assertEquals("https://media.magnific.com/meta-tags-social/mg-home-og.jpg?w=1200&h=675", metadata.previewImageUrl)
        assertFalse(metadata.mediaItems.isEmpty())
        assertEquals("https://media.magnific.com/meta-tags-social/mg-home-og.jpg?w=1200&h=675", metadata.mediaItems.first().sourceUrl)
    }

    @Test
    fun testHtmlEntityUnescapingInImageUrls() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta property="og:title" content="Entity Test" />
                <meta property="og:image" content="https://cdn.example.org/photo.jpg?size=large&amp;quality=high" />
            </head>
            <body></body>
            </html>
        """.trimIndent()

        val metadata = LinkMetadataFetcher.parseHtml(html, "https://example.org/item")
        assertEquals("https://cdn.example.org/photo.jpg?size=large&quality=high", metadata.previewImageUrl)
    }

    @Test
    fun testGalleryDlCheckSupportPopularSites() {
        val ytSupport = com.amresalehin.emregallery.data.GalleryDlEngine.checkSupport("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertTrue("YouTube should be recognized as supported extractor", ytSupport.isSupported)

        val redditSupport = com.amresalehin.emregallery.data.GalleryDlEngine.checkSupport("https://reddit.com/r/EarthPorn/comments/abc123")
        assertTrue("Reddit should be recognized as supported extractor", redditSupport.isSupported)

        val xSupport = com.amresalehin.emregallery.data.GalleryDlEngine.checkSupport("https://x.com/Android/status/123456789")
        assertTrue("X / Twitter should be recognized as supported extractor", xSupport.isSupported)

        val tiktokSupport = com.amresalehin.emregallery.data.GalleryDlEngine.checkSupport("https://www.tiktok.com/@user/video/123456")
        assertTrue("TikTok should be recognized as supported extractor", tiktokSupport.isSupported)
    }
}
