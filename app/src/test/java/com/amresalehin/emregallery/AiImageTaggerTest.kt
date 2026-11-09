package com.amresalehin.emregallery

import com.amresalehin.emregallery.ai.tagging.AiImageTagger
import com.amresalehin.emregallery.data.CloudAiProvider
import com.amresalehin.emregallery.data.CloudProviderStatus
import com.amresalehin.emregallery.data.MediaItem
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AiImageTaggerTest {

    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun testCleanTagsListSanitization() {
        val rawTags = listOf(
            "#sunset",
            "SUNSET",
            "A",
            "the",
            "ocean/beach",
            "golden hour!",
            "photo of nature",
            "nature",
            "unbelievable_scenery_with_lots_of_words_that_are_too_long_for_a_tag"
        )
        val cleaned = AiImageTagger.cleanTagsList(rawTags)

        assertTrue("Should contain sunset", cleaned.contains("sunset"))
        assertTrue("Should contain nature", cleaned.contains("nature"))
        assertFalse("Should strip hashtags", cleaned.any { it.startsWith("#") })
        assertFalse("Should filter out overly long tags", cleaned.any { it.length > 30 })
        assertEquals("Duplicate sunset should be merged", 1, cleaned.count { it == "sunset" })
    }

    @Test
    fun testCloudVisionTagExtractionFromJsonArrayResponse() = runBlocking {
        val jsonReply = """
            {
              "choices": [
                {
                  "message": {
                    "content": "[\"mountain\", \"snow\", \"alps\", \"hiking\", \"landscape\"]"
                  }
                }
              ]
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(jsonReply)
        )

        val baseUrl = mockWebServer.url("/v1/").toString()
        val provider = CloudAiProvider(
            id = "test_provider",
            name = "Test Vision",
            baseUrl = baseUrl,
            apiKey = "secret_key",
            modelId = "gpt-4o-mini",
            status = CloudProviderStatus.CONNECTED
        )

        val sampleJpeg = ByteArray(50) { 0x55 }
        val tags = AiImageTagger.tagWithCloudVisionDirect(provider, sampleJpeg)

        assertTrue("Should contain mountain", tags.contains("mountain"))
        assertTrue("Should contain snow", tags.contains("snow"))
        assertTrue("Should contain landscape", tags.contains("landscape"))
    }

    @Test
    fun testCloudVisionTagExtractionFromCommaSeparatedResponse() = runBlocking {
        val jsonReply = """
            {
              "choices": [
                {
                  "message": {
                    "content": "sunset, golden hour, beach, reflections, warm lighting"
                  }
                }
              ]
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(jsonReply)
        )

        val baseUrl = mockWebServer.url("/v1/").toString()
        val provider = CloudAiProvider(
            id = "test_provider",
            name = "Groq Vision",
            baseUrl = baseUrl,
            apiKey = "secret_key",
            modelId = "llama-3.2-11b-vision-preview",
            status = CloudProviderStatus.CONNECTED
        )

        val sampleJpeg = ByteArray(50) { 0x22 }
        val tags = AiImageTagger.tagWithCloudVisionDirect(provider, sampleJpeg)

        assertTrue("Tags should contain sunset", tags.contains("sunset"))
        assertTrue("Tags should contain beach", tags.contains("beach"))
    }

    @Test
    fun testCloudVisionTagExtractionHandlesErrorGracefully() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\": {\"message\": \"Invalid API key\"}}")
        )

        val baseUrl = mockWebServer.url("/v1/").toString()
        val provider = CloudAiProvider(
            id = "test_provider",
            name = "Failing Vision",
            baseUrl = baseUrl,
            apiKey = "invalid_key",
            modelId = "gpt-4o",
            status = CloudProviderStatus.CONNECTED
        )

        val sampleJpeg = ByteArray(50) { 0x11 }
        val tags = AiImageTagger.tagWithCloudVisionDirect(provider, sampleJpeg)

        assertTrue("Failed API call should return empty list gracefully", tags.isEmpty())
    }

    @Test
    fun testSmartVisionHeuristicsOnScreenshot() {
        val item = MediaItem(
            id = 101L,
            uri = "content://media/external/images/media/101",
            name = "Screenshot_20261108_120000.png",
            mimeType = "image/png",
            dateAddedSeconds = 1700000000L,
            dateTakenMillis = 1700000000000L,
            sizeBytes = 2048000L,
            width = 1080,
            height = 2400,
            relativePath = "Pictures/Screenshots/",
            isVideo = false
        )

        val tags = AiImageTagger.analyzeImageWithSmartVision(null, item)
        assertTrue("Should tag as screenshot", tags.contains("screenshot"))
        assertTrue("Should tag as portrait orientation", tags.contains("portrait"))
    }

    @Test
    fun testSmartVisionHeuristicsOnPanoramicLandscape() {
        val item = MediaItem(
            id = 102L,
            uri = "content://media/external/images/media/102",
            name = "PANO_20261108.jpg",
            mimeType = "image/jpeg",
            dateAddedSeconds = 1700000000L,
            dateTakenMillis = 1700000000000L,
            sizeBytes = 4048000L,
            width = 4000,
            height = 1200,
            relativePath = "DCIM/Camera/",
            isVideo = false
        )

        val tags = AiImageTagger.analyzeImageWithSmartVision(null, item)
        assertTrue("Should tag as panoramic", tags.contains("panoramic"))
        assertTrue("Should tag as landscape", tags.contains("landscape"))
        assertTrue("Should tag as camera", tags.contains("camera"))
    }
}
