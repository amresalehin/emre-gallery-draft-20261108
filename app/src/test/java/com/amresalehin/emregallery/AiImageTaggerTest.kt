package com.amresalehin.emregallery

import com.amresalehin.emregallery.ai.local.LocalModelRegistry
import com.amresalehin.emregallery.ai.tagging.AiImageTagger
import com.amresalehin.emregallery.data.CloudAiProvider
import com.amresalehin.emregallery.data.CloudProviderStatus
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
    fun testAllFourLocalImageTaggingRuntimesAreAvailable() {
        assertTrue(LocalModelRegistry.MINI_CPM_V46_SPEC.hasRuntimeAdapter)
        assertTrue(LocalModelRegistry.GEMMA_4_SPEC.hasRuntimeAdapter)
        assertTrue(LocalModelRegistry.LFM2_5_SPEC.hasRuntimeAdapter)
        assertTrue(LocalModelRegistry.QWEN3_5_SPEC.hasRuntimeAdapter)
    }

    @Test
    fun testCloudVisionRespectsExplicitModelOverride() = runBlocking {
        val jsonReply = """
            {
              "choices": [
                {
                  "message": {
                    "content": "sunset, beach, waves"
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
            name = "OpenAI",
            baseUrl = baseUrl,
            apiKey = "secret",
            modelId = "gpt-4o-mini",
            status = CloudProviderStatus.CONNECTED
        )

        val sampleJpeg = ByteArray(50) { 0x33 }
        AiImageTagger.tagWithCloudVisionDirect(provider, sampleJpeg, modelOverride = "gpt-4o")

        val recordedRequest = mockWebServer.takeRequest()
        val bodyString = recordedRequest.body.readUtf8()
        val jsonBody = org.json.JSONObject(bodyString)

        assertEquals("Request payload must use the explicit model override", "gpt-4o", jsonBody.getString("model"))
    }

    @Test
    fun testCloudVisionExtractsFromGeminiCandidatesResponse() = runBlocking {
        val geminiReply = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "lake, mountains, reflection, wilderness, alpine"
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(geminiReply)
        )

        val baseUrl = mockWebServer.url("/v1/").toString()
        val provider = CloudAiProvider(
            id = "gemini_provider",
            name = "Google Gemini",
            baseUrl = baseUrl,
            apiKey = "gemini_key",
            modelId = "gemini-1.5-flash",
            status = CloudProviderStatus.CONNECTED
        )

        val sampleJpeg = ByteArray(50) { 0x44 }
        val tags = AiImageTagger.tagWithCloudVisionDirect(provider, sampleJpeg)

        assertTrue("Tags should contain lake", tags.contains("lake"))
        assertTrue("Tags should contain mountains", tags.contains("mountains"))
        assertTrue("Tags should contain alpine", tags.contains("alpine"))
    }

    @Test
    fun testOlderGenerationsAreNotAliasesForNewerModels() {
        assertNull(LocalModelRegistry.findSpec("gemma_3_1b_it"))
        assertNull(LocalModelRegistry.findSpec("gemma3_1b"))
        assertNull(LocalModelRegistry.findSpec("lfm2_350m"))
        assertNull(LocalModelRegistry.findSpec("qwen2_5_0_5b"))
        assertEquals("gemma_4_e2b", LocalModelRegistry.findSpec("gemma4")?.id)
    }

}
