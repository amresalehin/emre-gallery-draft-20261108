package com.amresalehin.emregallery.ai.local

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Alibaba Qwen3.5-0.8B vision-language inference on Android.
 *
 * Uses llama.cpp's Qwen35 text architecture together with its Qwen3VL
 * multimodal projector path. The Q4_K_XL model and F16 projector are loaded
 * as separate GGUF artifacts and receive actual image bytes through libmtmd.
 */
class Qwen35RuntimeAdapter(
    override val spec: LocalModelSpec = LocalModelRegistry.QWEN3_5_SPEC
) : LocalModelRuntime, LocalImageTaggingRuntime {

    companion object {
        const val MODEL_FILE_NAME = "Qwen3.5-0.8B-UD-Q4_K_XL.gguf"
        const val PROJECTOR_FILE_NAME = "mmproj-F16.gguf"
        private const val MIN_DEVICE_RAM_BYTES = 3_500L * 1024L * 1024L
        private const val MIN_AVAILABLE_RAM_BYTES = 1_024L * 1024L * 1024L

        /** Unsloth's published estimate for 4-bit Qwen3.5-0.8B is about 3.5 GB total memory. */
        fun memoryRequirementMessage(context: Context): String? {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return "Unable to verify device memory for Qwen3.5-0.8B."
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            return when {
                info.totalMem < MIN_DEVICE_RAM_BYTES ->
                    "Qwen3.5-0.8B UD-Q4_K_XL is intended for a device with at least 3.5 GB RAM. This device is below the minimum memory class."
                info.availMem < MIN_AVAILABLE_RAM_BYTES ->
                    "Qwen3.5-0.8B needs about 1 GB of currently available memory to load safely. Close other apps and retry."
                else -> null
            }
        }

        /**
         * Qwen3.5 Small is non-thinking by default. Ask explicitly for a compact
         * JSON response so its reasoning tokens do not inflate gallery metadata.
         */
        private val SYSTEM_PROMPT = """
You are an accurate image captioning and tagging engine for a personal photo gallery.
Inspect the supplied image itself. Identify only people, animals, objects, places, activities, and visual details that are clearly visible.

Create:
- caption: one short, objective sentence describing the main visible content.
- tags: 6 to 12 concise, useful search tags. Prefer specific nouns and short phrases. Include the main subjects, setting, activity, and distinctive visible attributes when relevant.

Return exactly one valid JSON object with this shape:
{"caption":"short factual caption","tags":["tag one","tag two"]}

Do not guess identities, locations, dates, relationships, intentions, or details that cannot be seen. Avoid generic tags such as image, photo, picture, or AI. Use lowercase tags. Do not include markdown, code fences, analysis, or any text outside the JSON object.
""".trimIndent()

        /** Extracts only the model's tags array; the caption is not stored as a search tag. */
        internal fun parseStructuredResponse(raw: String): String {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            require(start >= 0 && end > start) { "Qwen3.5 did not return a JSON object." }
            val json = JSONObject(raw.substring(start, end + 1))
            val tagValue = json.opt("tags")
            val candidates = mutableListOf<String>()

            fun addCandidate(value: String) {
                value.split(Regex("[,;\\n\\r|]"))
                    .map { it.trim().trim('"', '\'', ' ', '#') }
                    .filter { it.length in 2..40 }
                    .forEach { if (it !in candidates) candidates.add(it) }
            }

            when (tagValue) {
                is JSONArray -> for (index in 0 until tagValue.length()) {
                    val item = tagValue.opt(index)
                    if (item is String) addCandidate(item)
                }
                is String -> addCandidate(tagValue)
                else -> Unit
            }

            require(candidates.isNotEmpty()) { "Qwen3.5 returned JSON, but its tags array contained no usable tags." }
            return candidates.take(16).joinToString(", ")
        }
    }

    private val engineLock = Any()
    @Volatile private var ready = false
    @Volatile var lastErrorMessage: String? = null
        private set

    override val isReady: Boolean
        get() = ready

    override suspend fun open(context: Context, modelDir: File): Boolean = withContext(Dispatchers.IO) {
        memoryRequirementMessage(context)?.let {
            lastErrorMessage = it
            return@withContext false
        }

        val modelFile = File(modelDir, MODEL_FILE_NAME)
        val projectorFile = File(modelDir, PROJECTOR_FILE_NAME)
        val modelSpec = spec.artifacts.firstOrNull { it.relativePath == MODEL_FILE_NAME }
        val projectorSpec = spec.artifacts.firstOrNull { it.relativePath == PROJECTOR_FILE_NAME }
        if (!modelFile.isFile || modelFile.length() != modelSpec?.exactBytes ||
            !projectorFile.isFile || projectorFile.length() != projectorSpec?.exactBytes
        ) {
            lastErrorMessage = "Qwen3.5 model or F16 vision-projector file is missing or does not match the pinned artifact size."
            return@withContext false
        }

        synchronized(engineLock) {
            if (ready) return@synchronized true
            try {
                Qwen35Native.initialize()
                if (!Qwen35Native.loadModel(modelFile.absolutePath, projectorFile.absolutePath)) {
                    lastErrorMessage = Qwen35Native.lastError()
                        ?: "llama.cpp could not load Qwen3.5-0.8B and its F16 vision projector."
                    Qwen35Native.close()
                    ready = false
                    return@synchronized false
                }
                ready = true
                lastErrorMessage = null
                true
            } catch (error: Throwable) {
                runCatching { Qwen35Native.close() }
                ready = false
                lastErrorMessage = "Qwen3.5 runtime initialization failed: ${error.message ?: error.javaClass.simpleName}"
                false
            }
        }
    }

    override suspend fun generateImageTags(imageBytes: ByteArray): String = withContext(Dispatchers.IO) {
        require(imageBytes.isNotEmpty()) { "Image data is empty." }
        synchronized(engineLock) {
            check(ready) { lastErrorMessage ?: "Qwen3.5 runtime is not initialized." }
            val response = Qwen35Native.generateImage(imageBytes, SYSTEM_PROMPT, 160)
                ?: throw IllegalStateException(Qwen35Native.lastError() ?: "Qwen3.5 returned no output.")
            try {
                parseStructuredResponse(response)
            } catch (error: Throwable) {
                throw IllegalStateException(
                    "Qwen3.5 returned an invalid tag response: ${error.message ?: error.javaClass.simpleName}",
                    error
                )
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
                reason = lastErrorMessage ?: "Qwen3.5 llama.cpp runtime could not be opened.",
                failedStage = "runtime_initialization"
            )
        }

        val smokeImage = createSmokeTestImage()
        try {
            val tags = generateImageTags(smokeImage)
            if (tags.isBlank()) {
                SmokeTestResult.Failure(
                    reason = "Qwen3.5 initialized but returned no usable tags for image input.",
                    failedStage = "image_inference"
                )
            } else {
                SmokeTestResult.Success(
                    durationMs = System.currentTimeMillis() - startedAt,
                    details = "llama.cpp loaded the Qwen35 model and Qwen3VL F16 projector, processed image bytes through libmtmd, and returned parseable JSON tags.",
                    validatedTensors = listOf("image.bytes", "qwen3vl.vision.projector", "json.tags")
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            SmokeTestResult.Failure(
                reason = "Qwen3.5 image-inference smoke test failed: ${error.message ?: error.javaClass.simpleName}",
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
                bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() {
        synchronized(engineLock) {
            ready = false
            runCatching { Qwen35Native.close() }
        }
    }
}

private object Qwen35Native {
    init {
        System.loadLibrary("emre_minicpm")
    }

    external fun initialize(): Boolean
    external fun loadModel(modelPath: String, projectorPath: String): Boolean
    external fun generateImage(imageBytes: ByteArray, systemPrompt: String, maxTokens: Int): String?
    external fun lastError(): String?
    external fun close()
}