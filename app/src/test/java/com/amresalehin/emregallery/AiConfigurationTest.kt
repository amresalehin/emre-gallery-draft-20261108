package com.amresalehin.emregallery

import com.amresalehin.emregallery.ai.local.ArtifactRole
import com.amresalehin.emregallery.ai.local.Florence2RuntimeAdapter
import com.amresalehin.emregallery.ai.local.LocalModelArtifact
import com.amresalehin.emregallery.ai.local.LocalModelDownloadManager
import com.amresalehin.emregallery.ai.local.LocalModelInstaller
import com.amresalehin.emregallery.ai.local.LocalModelRegistry
import com.amresalehin.emregallery.ai.local.ModelArchitecture
import com.amresalehin.emregallery.ai.local.ModelTaskType
import com.amresalehin.emregallery.ai.local.SmokeTestResult
import com.amresalehin.emregallery.data.CloudAiConnectionTester
import com.amresalehin.emregallery.data.CloudAiProvider
import com.amresalehin.emregallery.data.CloudProviderStatus
import com.amresalehin.emregallery.data.FlorenceModelManager
import com.amresalehin.emregallery.data.FlorenceModelState
import com.amresalehin.emregallery.data.FlorenceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class AiConfigurationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockWebServer: MockWebServer
    private lateinit var testFilesDir: File

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        testFilesDir = tempFolder.newFolder("test_files")
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    // -------------------------------------------------------------------------
    // 1. Cloud Provider Tests
    // -------------------------------------------------------------------------

    @Test
    fun testCloudAiProviderDefaultsAndCopy() {
        val provider = CloudAiProvider(
            id = "prov-1",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-123",
            modelId = "gpt-4o"
        )

        assertEquals("prov-1", provider.id)
        assertEquals("OpenAI", provider.name)
        assertEquals("https://api.openai.com/v1", provider.baseUrl)
        assertEquals("sk-123", provider.apiKey)
        assertEquals("gpt-4o", provider.modelId)
        assertFalse(provider.isActive)
        assertEquals(CloudProviderStatus.UNCONFIGURED, provider.status)

        val active = provider.copy(isActive = true, status = CloudProviderStatus.CONNECTED)
        assertTrue(active.isActive)
        assertEquals(CloudProviderStatus.CONNECTED, active.status)
    }

    @Test
    fun testCloudConnectionTesterSuccess() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":[{"id":"gpt-4o"}]}""")
        )

        val testBaseUrl = mockWebServer.url("/v1").toString()
        val provider = CloudAiProvider(
            name = "MockServer",
            baseUrl = testBaseUrl,
            apiKey = "test-token",
            modelId = "gpt-4o"
        )

        val result = CloudAiConnectionTester.testConnection(provider)
        assertEquals(CloudProviderStatus.CONNECTED, result.status)
        assertTrue(result.message.contains("Connected (HTTP 200"))
        assertTrue(result.latencyMs >= 0L)

        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("Bearer test-token", recordedRequest.getHeader("Authorization"))
    }

    @Test
    fun testCloudConnectionTesterUnauthorized() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"message":"Invalid API key"}}""")
        )

        val testBaseUrl = mockWebServer.url("/v1").toString()
        val provider = CloudAiProvider(
            name = "MockServer",
            baseUrl = testBaseUrl,
            apiKey = "bad-token",
            modelId = "gpt-4o"
        )

        val result = CloudAiConnectionTester.testConnection(provider)
        assertEquals(CloudProviderStatus.ERROR, result.status)
        assertTrue(result.message.contains("Unauthorized (HTTP 401)"))
    }

    @Test
    fun testCloudConnectionTesterBlankUrl() = runBlocking {
        val provider = CloudAiProvider(
            name = "BlankServer",
            baseUrl = "   ",
            apiKey = "token",
            modelId = "gpt-4o"
        )

        val result = CloudAiConnectionTester.testConnection(provider)
        assertEquals(CloudProviderStatus.ERROR, result.status)
        assertEquals("Base URL cannot be blank", result.message)
    }

    // -------------------------------------------------------------------------
    // 2. Lifecycle State Transitions Tests
    // -------------------------------------------------------------------------

    @Test
    fun testFlorenceModelStateTransitions() {
        var state = FlorenceModelState(status = FlorenceStatus.NOT_INSTALLED)
        assertEquals(FlorenceStatus.NOT_INSTALLED, state.status)
        assertEquals(0f, state.progress, 0.001f)
        assertFalse(state.status.isUsable)

        state = state.copy(
            status = FlorenceStatus.DOWNLOADING,
            progress = 0.45f,
            bytesDownloaded = 45000L,
            totalBytes = 100000L,
            speedBytesPerSec = 15000L,
            etaSeconds = 3L
        )
        assertEquals(FlorenceStatus.DOWNLOADING, state.status)
        assertEquals(0.45f, state.progress, 0.001f)
        assertEquals(45000L, state.bytesDownloaded)
        assertEquals(15000L, state.speedBytesPerSec)
        assertEquals(3L, state.etaSeconds)

        state = state.copy(status = FlorenceStatus.VERIFYING, progress = 0.95f)
        assertEquals(FlorenceStatus.VERIFYING, state.status)

        state = state.copy(
            status = FlorenceStatus.READY,
            progress = 1.0f,
            installedSizeBytes = 100000L,
            installedPath = "/path/florence2",
            runtimeReady = true
        )
        assertEquals(FlorenceStatus.READY, state.status)
        assertTrue(state.status.isUsable)
        assertTrue(state.runtimeReady)
        assertEquals(1.0f, state.progress, 0.001f)
        assertEquals("/path/florence2", state.installedPath)
        assertEquals(100000L, state.installedSizeBytes)
    }

    // -------------------------------------------------------------------------
    // 3. Typed Model Registry Contract Tests
    // -------------------------------------------------------------------------

    @Test
    fun testRegistryContractsAndImmutableRevisions() {
        val models = LocalModelRegistry.ALL_MODELS
        assertEquals(4, models.size)

        for (spec in models) {
            assertTrue("Model id must not be blank", spec.id.isNotBlank())
            assertTrue("Canonical ID must not be blank", spec.canonicalId.isNotBlank())
            assertTrue("Display name must not be blank", spec.displayName.isNotBlank())
            assertTrue("Upstream repo must be non-empty", spec.upstreamRepo.isNotBlank() && spec.upstreamRepo.contains("/"))
            assertTrue("Upstream revision must be a full 40-char SHA git hash, not 'main'", spec.upstreamRevision.matches(Regex("^[0-9a-f]{40}$")))
            assertTrue("Subdir must be set", spec.subdir.startsWith("models/"))
            assertTrue("Must contain artifacts", spec.artifacts.isNotEmpty())

            // Assert no mutable /resolve/main/ URLs
            for (artifact in spec.artifacts) {
                assertFalse("Artifact URL must never use mutable 'main': ${artifact.sourceUrl}", artifact.sourceUrl.contains("/resolve/main/"))
                assertTrue("Artifact URL must use immutable commit: ${spec.upstreamRevision}", artifact.sourceUrl.contains(spec.upstreamRevision))
                assertTrue("Expected min bytes or exact bytes must be positive", (artifact.exactBytes ?: artifact.expectedMinBytes) > 0L)
            }
        }
    }

    @Test
    fun testFlorence2CompleteArtifactContract() {
        val florence = LocalModelRegistry.FLORENCE_2_SPEC
        assertEquals("Florence-2 Base", florence.displayName)
        assertEquals("onnx-community/Florence-2-base", florence.upstreamRepo)
        assertEquals(ModelTaskType.VISION_LANGUAGE, florence.taskType)
        assertEquals(ModelArchitecture.FLORENCE2, florence.architecture)
        assertTrue("Florence-2 must have runtime adapter", florence.hasRuntimeAdapter)

        val paths = florence.artifacts.map { it.relativePath }
        assertTrue("Must include vision encoder", paths.contains("onnx/vision_encoder_quantized.onnx"))
        assertTrue("Must include embed tokens", paths.contains("onnx/embed_tokens_quantized.onnx"))
        assertTrue("Must include text encoder", paths.contains("onnx/encoder_model_quantized.onnx"))
        assertTrue("Must include merged decoder", paths.contains("onnx/decoder_model_merged_quantized.onnx"))
        assertTrue("Must include preprocessor config", paths.contains("preprocessor_config.json"))
        assertTrue("Must include tokenizer", paths.contains("tokenizer.json"))
        assertTrue("Must include generation config", paths.contains("generation_config.json"))
        assertEquals(12, florence.artifacts.size)
    }

    @Test
    fun testGemma4LiteRtArtifactContract() {
        val gemma = LocalModelRegistry.getSpec("gemma_4_e2b")
        assertEquals("Gemma 4 E2B", gemma.displayName)
        assertEquals("litert-community/gemma-4-E2B-it-litert-lm", gemma.upstreamRepo)
        assertEquals(ModelTaskType.VISION_LANGUAGE, gemma.taskType)
        assertEquals(ModelArchitecture.GEMMA4, gemma.architecture)
        assertFalse("Gemma 4 runtime adapter not active yet", gemma.hasRuntimeAdapter)

        val artifact = gemma.artifacts.firstOrNull { it.role == ArtifactRole.LITERT_MODEL }
        assertNotNull("Gemma 4 must declare generic litertlm artifact", artifact)
        assertEquals("gemma-4-E2B-it.litertlm", artifact?.relativePath)
        assertEquals(2_781_778_740L, artifact?.exactBytes)
    }

    @Test
    fun testLfm25VlArtifactContract() {
        val lfm = LocalModelRegistry.getSpec("lfm2_5_vl_450m_extract")
        assertEquals("LFM2.5-VL-450M-Extract", lfm.displayName)
        assertEquals("LiquidAI/LFM2.5-VL-450M-Extract-GGUF", lfm.upstreamRepo)
        assertEquals(ModelTaskType.VISION_LANGUAGE, lfm.taskType)
        assertEquals(ModelArchitecture.LFM2_5, lfm.architecture)
        assertEquals("lfm1.0", lfm.license)
        assertFalse("LFM2.5 runtime adapter not active yet", lfm.hasRuntimeAdapter)

        assertEquals(2, lfm.artifacts.size)
        val model = lfm.artifacts.firstOrNull { it.role == ArtifactRole.GGUF_MODEL }
        val mmproj = lfm.artifacts.firstOrNull { it.role == ArtifactRole.GGUF_MMPROJ }
        assertNotNull("LFM2.5 must declare model GGUF", model)
        assertNotNull("LFM2.5 must declare vision projector", mmproj)
        assertEquals("LFM2.5-VL-450M-Extract-Q4_K_M.gguf", model?.relativePath)
        assertEquals("mmproj-LFM2.5-VL-450M-Extract-Q8_0.gguf", mmproj?.relativePath)
        assertEquals(240_668_768L, model?.exactBytes)
        assertEquals(108_136_832L, mmproj?.exactBytes)
    }

    @Test
    fun testQwen35ArtifactContract() {
        val qwen = LocalModelRegistry.getSpec("qwen3_5_0_8b")
        assertEquals("Qwen3.5-0.8B", qwen.displayName)
        assertEquals("unsloth/Qwen3.5-0.8B-GGUF", qwen.upstreamRepo)
        assertEquals(ModelTaskType.VISION_LANGUAGE, qwen.taskType)
        assertEquals(ModelArchitecture.QWEN3_5, qwen.architecture)
        assertFalse("Qwen 3.5 runtime adapter not active yet", qwen.hasRuntimeAdapter)

        assertEquals(2, qwen.artifacts.size)
        val model = qwen.artifacts.firstOrNull { it.role == ArtifactRole.GGUF_MODEL }
        val mmproj = qwen.artifacts.firstOrNull { it.role == ArtifactRole.GGUF_MMPROJ }
        assertNotNull("Qwen 3.5 must declare model GGUF", model)
        assertNotNull("Qwen 3.5 must declare vision projector", mmproj)
        assertEquals("Qwen3.5-0.8B-UD-Q4_K_XL.gguf", model?.relativePath)
        assertEquals("mmproj-F16.gguf", mmproj?.relativePath)
        assertEquals(586_303_872L, model?.exactBytes)
        assertEquals(215_286_400L, mmproj?.exactBytes)
    }

    @Test
    fun testLegacyAliasResolution() {
        assertEquals("florence2", FlorenceModelManager.getSpec("florence2").id)
        assertEquals("gemma_4_e2b", FlorenceModelManager.getSpec("gemma_4_e2b").id)
        assertEquals("gemma_4_e2b", FlorenceModelManager.getSpec("gemma_3_1b_it").id)
        assertEquals("lfm2_5_vl_450m_extract", FlorenceModelManager.getSpec("lfm2_5_vl_450m_extract").id)
        assertEquals("lfm2_5_vl_450m_extract", FlorenceModelManager.getSpec("lfm2_350m").id)
        assertEquals("qwen3_5_0_8b", FlorenceModelManager.getSpec("qwen3_5_0_8b").id)
        assertEquals("qwen3_5_0_8b", FlorenceModelManager.getSpec("qwen2_5_0_5b").id)
    }

    // -------------------------------------------------------------------------
    // 4. Downloader Resilience & HTTP MockWebServer Tests
    // -------------------------------------------------------------------------

    @Test
    fun testDownloaderFollowsRedirect() = runBlocking {
        val targetFile = tempFolder.newFile("redirect_target.bin").apply { delete() }
        val finalBody = "FINAL_CONTENT_12345"

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "/final-destination")
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(finalBody)
        )

        val initialUrl = mockWebServer.url("/redirect-origin").toString()
        var sessionRead = 0L

        LocalModelDownloadManager.downloadArtifactFile(
            url = initialUrl,
            destination = targetFile,
            expectedBytes = finalBody.length.toLong(),
            onProgress = { sessionRead += it }
        )

        assertTrue(targetFile.exists())
        assertEquals(finalBody, targetFile.readText())
        assertEquals(finalBody.length.toLong(), sessionRead)
    }

    @Test
    fun testDownloaderHandles404And500Errors() = runBlocking {
        val targetFile = tempFolder.newFile("error_test.bin").apply { delete() }

        mockWebServer.enqueue(MockResponse().setResponseCode(404))
        val url404 = mockWebServer.url("/missing").toString()

        try {
            LocalModelDownloadManager.downloadArtifactFile(url404, targetFile, onProgress = {})
            fail("Expected IOException on 404")
        } catch (e: IOException) {
            assertTrue("Error should mention HTTP 404: ${e.message}", e.message?.contains("404") == true)
        }

        mockWebServer.enqueue(MockResponse().setResponseCode(500))
        val url500 = mockWebServer.url("/server-error").toString()

        try {
            LocalModelDownloadManager.downloadArtifactFile(url500, targetFile, onProgress = {})
            fail("Expected IOException on 500")
        } catch (e: IOException) {
            assertTrue("Error should mention HTTP 500: ${e.message}", e.message?.contains("500") == true)
        }
    }

    @Test
    fun testDownloaderRangeResumeAndIgnoredRange() = runBlocking {
        val targetFile = tempFolder.newFile("range_test.bin")
        targetFile.writeText("PARTIAL_")
        val initialLen = targetFile.length() // 8 bytes

        // Case A: Server honors Range and responds 206 Partial Content
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 8-15/16")
                .setBody("FINISHED")
        )

        var readBytes = 0L
        val url206 = mockWebServer.url("/range-supported").toString()

        LocalModelDownloadManager.downloadArtifactFile(
            url = url206,
            destination = targetFile,
            expectedBytes = 16L,
            onProgress = { readBytes += it }
        )

        assertEquals("PARTIAL_FINISHED", targetFile.readText())
        assertEquals(8L, readBytes)

        val recorded206 = mockWebServer.takeRequest()
        assertEquals("bytes=$initialLen-", recorded206.getHeader("Range"))

        // Case B: Server ignores Range and responds 200 OK -> must overwrite from 0!
        targetFile.writeText("STALE_EXISTING_PREFIX_")
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("FULL_REPLACEMENT_PAYLOAD")
        )

        val url200 = mockWebServer.url("/range-ignored").toString()
        LocalModelDownloadManager.downloadArtifactFile(
            url = url200,
            destination = targetFile,
            expectedBytes = "FULL_REPLACEMENT_PAYLOAD".length.toLong(),
            onProgress = {}
        )

        assertEquals("FULL_REPLACEMENT_PAYLOAD", targetFile.readText())
    }

    @Test
    fun testDownloaderCancellation() = runBlocking {
        val targetFile = tempFolder.newFile("cancel_test.bin").apply { delete() }

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("A".repeat(100_000))
        )

        val url = mockWebServer.url("/slow-download").toString()

        val job = launch {
            LocalModelDownloadManager.downloadArtifactFile(
                url = url,
                destination = targetFile,
                onProgress = {}
            )
        }

        job.cancelAndJoin()
        assertTrue("Job must be cancelled", job.isCancelled)
    }

    // -------------------------------------------------------------------------
    // 5. Integrity Validation & Manifest Rejection Tests
    // -------------------------------------------------------------------------

    private fun createDummyFileOfSize(file: File, sizeBytes: Long) {
        file.parentFile?.mkdirs()
        java.io.RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(sizeBytes)
        }
    }

    @Test
    fun testInstallationStatusRejectsIncompleteOrTruncatedArtifacts() {
        val spec = LocalModelRegistry.FLORENCE_2_SPEC
        val modelDir = File(testFilesDir, spec.subdir)
        modelDir.deleteRecursively()
        modelDir.mkdirs()

        // 1. Empty directory -> NOT_INSTALLED
        var state = LocalModelInstaller.checkInstallationStatus(testFilesDir, spec)
        assertEquals(FlorenceStatus.NOT_INSTALLED, state.status)

        // 2. Write valid manifest, but missing actual artifacts -> NOT_INSTALLED
        val manifest = JSONObject().apply {
            put("id", spec.id)
            put("upstreamRepo", spec.upstreamRepo)
            put("upstreamRevision", spec.upstreamRevision)
            put("status", "READY")
            put("installedAt", System.currentTimeMillis())
        }
        File(modelDir, spec.manifestFileName).writeText(manifest.toString())

        state = LocalModelInstaller.checkInstallationStatus(testFilesDir, spec)
        assertEquals(FlorenceStatus.NOT_INSTALLED, state.status)

        // 3. Create all files but make one truncated
        for (art in spec.artifacts) {
            val f = File(modelDir, art.relativePath)
            createDummyFileOfSize(f, art.exactBytes ?: art.expectedMinBytes)
        }
        // Truncate config.json to 0 bytes
        File(modelDir, "config.json").writeText("")
        state = LocalModelInstaller.checkInstallationStatus(testFilesDir, spec)
        assertEquals(FlorenceStatus.ERROR, state.status)
        assertTrue(state.errorMessage?.contains("truncated") == true)

        modelDir.deleteRecursively()
    }

    @Test
    fun testInstallationStatusRejectsMismatchedCommitRevision() {
        val spec = LocalModelRegistry.FLORENCE_2_SPEC
        val modelDir = File(testFilesDir, spec.subdir)
        modelDir.deleteRecursively()
        modelDir.mkdirs()

        // Create all files with valid lengths
        for (art in spec.artifacts) {
            val f = File(modelDir, art.relativePath)
            createDummyFileOfSize(f, art.exactBytes ?: art.expectedMinBytes)
        }

        // Write manifest with stale/tampered commit revision
        val manifest = JSONObject().apply {
            put("id", spec.id)
            put("upstreamRepo", spec.upstreamRepo)
            put("upstreamRevision", "0000000000000000000000000000000000000000")
            put("status", "READY")
        }
        File(modelDir, spec.manifestFileName).writeText(manifest.toString())

        val state = LocalModelInstaller.checkInstallationStatus(testFilesDir, spec)
        assertEquals(FlorenceStatus.ERROR, state.status)
        assertTrue(state.errorMessage?.contains("revision mismatch") == true)

        modelDir.deleteRecursively()
    }

    @Test
    fun testChecksumCalculationAndMismatchDetection() {
        val testFile = tempFolder.newFile("hash_test.txt")
        testFile.writeText("Hello, World!")

        val expectedHash = "dffd6021bb2bd5b0af676290809ec3a53191dd81c7f70a4b28688a362182986f"
        val computed = LocalModelInstaller.computeSha256(testFile)
        assertEquals(expectedHash, computed)
    }

    @Test
    fun testModelWithoutRuntimeMarkedAssetsOnly() {
        val gemma = LocalModelRegistry.GEMMA_4_SPEC
        val modelDir = File(testFilesDir, gemma.subdir)
        modelDir.deleteRecursively()
        modelDir.mkdirs()

        // Create all files for Gemma
        for (art in gemma.artifacts) {
            val f = File(modelDir, art.relativePath)
            createDummyFileOfSize(f, art.exactBytes ?: art.expectedMinBytes)
        }

        // Write valid manifest
        val manifest = JSONObject().apply {
            put("id", gemma.id)
            put("upstreamRepo", gemma.upstreamRepo)
            put("upstreamRevision", gemma.upstreamRevision)
            put("status", "ASSETS_DOWNLOADED_RUNTIME_UNAVAILABLE")
        }
        File(modelDir, gemma.manifestFileName).writeText(manifest.toString())

        val state = LocalModelInstaller.checkInstallationStatus(testFilesDir, gemma)
        assertEquals(FlorenceStatus.ASSETS_DOWNLOADED_RUNTIME_UNAVAILABLE, state.status)
        assertFalse("Gemma must not be ready as it has no runtime adapter", state.runtimeReady)

        modelDir.deleteRecursively()
    }

    @Test
    fun testFlorence2RuntimeValidationContract() = runBlocking {
        val adapter = Florence2RuntimeAdapter()
        val emptyDir = tempFolder.newFolder("empty_florence")

        val result = adapter.validateGraphAndRunSmokeTest(emptyDir)
        assertTrue("Empty dir should fail validation", result is SmokeTestResult.Failure)
        val failure = result as SmokeTestResult.Failure
        assertEquals("artifact_check", failure.failedStage)
        assertTrue(failure.reason.contains("Missing or empty files"))
        assertFalse(adapter.isReady)
    }
}
