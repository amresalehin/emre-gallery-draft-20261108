package com.amresalehin.emregallery

import com.amresalehin.emregallery.ai.local.LocalModelRegistry
import com.amresalehin.emregallery.ai.local.Qwen35RuntimeAdapter
import com.amresalehin.emregallery.data.LocalModelManager
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
import java.io.IOException

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
    fun testCloudVisionTagExtractionReturnsActionableUnauthorizedError() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("{\\\"error\\\": {\\\"message\\\": \\\"Invalid API key\\\"}}")
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
        val error = try {
            AiImageTagger.tagWithCloudVisionDirect(provider, sampleJpeg)
            null
        } catch (failure: IOException) {
            failure
        }

        assertNotNull("Direct cloud inference should expose the provider error", error)
        assertTrue(error?.message.orEmpty().contains("HTTP 401"))
        assertTrue(error?.message.orEmpty().contains("Invalid API key"))
    }
    @Test
    fun testCloudVisionAcceptsOpenAiContentArrayResponse() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"content":[{"type":"text","text":"forest, pine trees, mountain"}]}}]}""")
        )
        val provider = CloudAiProvider(
            id = "array_provider",
            name = "OpenAI-compatible",
            baseUrl = mockWebServer.url("/v1").toString(),
            apiKey = "secret",
            modelId = "gpt-6.1-sol",
            status = CloudProviderStatus.CONNECTED
        )

        val tags = AiImageTagger.tagWithCloudVisionDirect(provider, byteArrayOf(1, 2, 3))
        assertTrue(tags.contains("forest"))
        assertTrue(tags.contains("pine trees"))
        assertTrue(tags.contains("mountain"))
    }

    @Test
    fun testCloudVisionRejectsEmptyImagePayload() = runBlocking {
        val provider = CloudAiProvider(
            id = "empty_image_provider",
            name = "OpenAI",
            baseUrl = mockWebServer.url("/v1").toString(),
            apiKey = "secret",
            modelId = "gpt-6.1-sol"
        )
        try {
            AiImageTagger.tagWithCloudVisionDirect(provider, byteArrayOf())
            fail("Expected an empty image payload to be rejected")
        } catch (error: java.io.IOException) {
            assertTrue(error.message.orEmpty().contains("image payload is empty"))
        }
    }

    @Test
    fun testCloudVisionReturnsProviderHttpError() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"Invalid API key"}}""")
        )
        val provider = CloudAiProvider(
            id = "unauthorized_provider",
            name = "OpenAI-compatible",
            baseUrl = mockWebServer.url("/v1").toString(),
            apiKey = "wrong",
            modelId = "gpt-6.1-sol"
        )
        try {
            AiImageTagger.tagWithCloudVisionDirect(provider, byteArrayOf(1, 2, 3))
            fail("Expected authentication failure")
        } catch (error: java.io.IOException) {
            assertTrue(error.message.orEmpty().contains("HTTP 401"))
            assertTrue(error.message.orEmpty().contains("Invalid API key"))
        }
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
    fun testAllFourRuntimeModelsAreAvailableToTheTaggingSelector() {
        val selectableLocalIds = LocalModelManager.SUPPORTED_MODELS
            .filter { it.hasRuntimeAdapter }
            .map { it.id }
            .toSet()

        assertTrue(selectableLocalIds.contains(LocalModelRegistry.MINI_CPM_V46_ID))
        assertTrue(selectableLocalIds.contains(LocalModelRegistry.GEMMA_4_E2B_ID))
        assertTrue(selectableLocalIds.contains(LocalModelRegistry.LFM2_5_VL_ID))
        assertTrue(selectableLocalIds.contains(LocalModelRegistry.QWEN3_5_ID))
        assertEquals("All four implemented local image runtimes must be offered", 4, selectableLocalIds.size)
    }

    @Test
    fun testQwenDeviceCompatibilityIsSeparateFromTransientAvailableMemory() {
        val belowMinimum = Qwen35RuntimeAdapter.deviceClassRequirementMessage(3L * 1024L * 1024L * 1024L)
        val aboveMinimum = Qwen35RuntimeAdapter.deviceClassRequirementMessage(4L * 1024L * 1024L * 1024L)

        assertNotNull("A device below the 3.5 GB class must be marked incompatible", belowMinimum)
        assertNull("A device above the 3.5 GB class remains device-compatible", aboveMinimum)
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
