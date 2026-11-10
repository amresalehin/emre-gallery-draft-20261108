package com.amresalehin.emregallery.ai.local

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Gemma 4 E2B image-to-text adapter backed by Google's LiteRT-LM Android runtime.
 * CPU backends are used for both text and vision to avoid optional vendor GPU dependencies.
 */
class Gemma4RuntimeAdapter(
    override val spec: LocalModelSpec = LocalModelRegistry.GEMMA_4_SPEC
) : LocalModelRuntime, LocalImageTaggingRuntime {

    companion object {
        const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
        private const val MIN_DEVICE_RAM_BYTES = 7L * 1024L * 1024L * 1024L
        private const val MIN_AVAILABLE_RAM_BYTES = 1024L * 1024L * 1024L

        private const val TAGGING_PROMPT =
            "Inspect the image itself. Identify its visible subjects, objects, setting, activity, " +
            "and visual characteristics. Return 4 to 8 concise, specific, lowercase search tags " +
            "as a comma-separated list only. Do not include model names, generic AI terms, " +
            "explanations, markdown, or tags that are not supported by the image."

        /** The official model allowlist specifies a minimum 8 GB device-memory class. */
        fun deviceClassRequirementMessage(context: Context): String? {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return "Unable to verify device memory for Gemma 4 E2B."
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            return if (info.totalMem < MIN_DEVICE_RAM_BYTES) {
                "Gemma 4 E2B requires an 8 GB RAM-class Android device. This device is below the published minimum."
            } else null
        }

        private fun runtimeMemoryRequirementMessage(context: Context): String? {
            deviceClassRequirementMessage(context)?.let { return it }
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return "Unable to verify available memory for Gemma 4 E2B."
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            return if (info.availMem < MIN_AVAILABLE_RAM_BYTES) {
                "Not enough free memory to load Gemma 4 E2B safely. Close other apps and retry."
            } else null
        }
    }

    private val engineLock = Any()
    @Volatile private var engine: Engine? = null
    @Volatile private var ready = false
    @Volatile var lastErrorMessage: String? = null
        private set

    override val isReady: Boolean
        get() = ready && engine?.isInitialized() == true

    override suspend fun open(context: Context, modelDir: File): Boolean = withContext(Dispatchers.IO) {
        runtimeMemoryRequirementMessage(context)?.let {
            lastErrorMessage = it
            return@withContext false
        }
        val modelFile = File(modelDir, MODEL_FILE_NAME)
        val expectedBytes = spec.artifacts.firstOrNull { it.relativePath == MODEL_FILE_NAME }?.exactBytes
        if (!modelFile.isFile || modelFile.length() <= 0L ||
            (expectedBytes != null && modelFile.length() != expectedBytes)
        ) {
            lastErrorMessage = "Gemma 4 model file is missing or does not match the pinned artifact size."
            return@withContext false
        }

        synchronized(engineLock) {
            if (isReady) return@synchronized true
            val candidate = Engine(
                EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.CPU(),
                    visionBackend = Backend.CPU(),
                    maxNumTokens = 4096,
                    maxNumImages = 1,
                    cacheDir = context.cacheDir.absolutePath
                )
            )
            try {
                candidate.initialize()
                engine = candidate
                ready = true
                lastErrorMessage = null
                true
            } catch (error: Throwable) {
                runCatching { if (candidate.isInitialized()) candidate.close() }
                engine = null
                ready = false
                lastErrorMessage = "LiteRT-LM could not initialize Gemma 4: ${error.message ?: error.javaClass.simpleName}"
                false
            }
        }
    }

    override suspend fun generateImageTags(imageBytes: ByteArray): String = withContext(Dispatchers.IO) {
        require(imageBytes.isNotEmpty()) { "Image data is empty." }
        synchronized(engineLock) {
            val activeEngine = engine
                ?: throw IllegalStateException(lastErrorMessage ?: "Gemma 4 runtime is not initialized.")
            check(isReady) { "Gemma 4 runtime is not ready." }
            val conversationConfig = ConversationConfig(
                samplerConfig = SamplerConfig(topK = 20, topP = 0.85, temperature = 0.2),
                maxOutputToken = 96
            )
            activeEngine.createConversation(conversationConfig).use { conversation ->
                val request = Message.user(
                    Contents.of(Content.ImageBytes(imageBytes), Content.Text(TAGGING_PROMPT))
                )
                conversation.sendMessage(request).toString().trim()
            }
        }
    }

    override suspend fun validateGraphAndRunSmokeTest(
        context: Context,
        modelDir: File
    ): SmokeTestResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        if (!open(context, modelDir)) {
            return@withContext SmokeTestResult.Failure(
                reason = lastErrorMessage ?: "Gemma 4 LiteRT-LM engine could not be opened.",
                failedStage = "runtime_initialization"
            )
        }
        val smokeImage = createSmokeTestImage()
        try {
            val output = generateImageTags(smokeImage)
            if (output.isBlank()) {
                SmokeTestResult.Failure(
                    reason = "Gemma 4 initialized but returned an empty response for an image input.",
                    failedStage = "image_inference"
                )
            } else {
                SmokeTestResult.Success(
                    durationMs = System.currentTimeMillis() - startedAt,
                    details = "LiteRT-LM initialized and completed image-bytes-to-text inference; the smoke response was non-empty.",
                    validatedTensors = listOf("image.bytes", "generated.text")
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            SmokeTestResult.Failure(
                reason = "Gemma 4 image inference smoke test failed: ${error.message ?: error.javaClass.simpleName}",
                failedStage = "image_inference",
                cause = error
            )
        } finally {
            smokeImage.fill(0)
        }
    }

    private fun createSmokeTestImage(): ByteArray {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.LTGRAY)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLUE }
            canvas.drawRect(8f, 10f, 42f, 62f, paint)
            paint.color = Color.RED
            canvas.drawCircle(67f, 34f, 17f, paint)
            paint.color = Color.GREEN
            canvas.drawRect(50f, 60f, 86f, 86f, paint)
            return ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() {
        synchronized(engineLock) {
            ready = false
            val activeEngine = engine
            engine = null
            runCatching { if (activeEngine?.isInitialized() == true) activeEngine.close() }
        }
    }
}
