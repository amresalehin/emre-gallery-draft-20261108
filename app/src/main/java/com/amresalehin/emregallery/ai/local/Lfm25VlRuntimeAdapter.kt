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
 * Liquid AI LFM2.5-VL-450M-Extract image-to-JSON runtime.
 *
 * Uses the pinned llama.cpp MTMD implementation for LFM2 vision tiling and
 * Q8_0 projector decoding. The model is prompted using its extraction schema
 * format (system YAML fields + user image) and deterministic greedy decoding.
 */
class Lfm25VlRuntimeAdapter(
    override val spec: LocalModelSpec = LocalModelRegistry.LFM2_5_SPEC
) : LocalModelRuntime, LocalImageTaggingRuntime {

    companion object {
        const val MODEL_FILE_NAME = "LFM2.5-VL-450M-Extract-Q4_K_M.gguf"
        const val PROJECTOR_FILE_NAME = "mmproj-LFM2.5-VL-450M-Extract-Q8_0.gguf"
        private const val MIN_AVAILABLE_RAM_BYTES = 768L * 1024L * 1024L

        /**
         * LFM2.5 is a compact 450M-parameter model. We only gate on currently
         * available memory, rather than imposing a fixed total-RAM device class.
         */
        fun memoryRequirementMessage(context: Context): String? {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return "Unable to verify available memory for LFM2.5-VL-450M-Extract."
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            return if (info.availMem < MIN_AVAILABLE_RAM_BYTES) {
                "LFM2.5-VL-450M-Extract needs approximately 768 MB of currently available memory. Close other apps and retry."
            } else null
        }

        /**
         * Liquid AI trains this Extract variant for a YAML field schema in the
         * system message and a JSON object response. Keep requested values short
         * so they can be converted into useful searchable gallery tags.
         */
        private const val SYSTEM_PROMPT = """
Extract the following visually supported information from the image.

subjects: Identify the main visible people, animals, objects, products, or landmarks. Return short noun phrases.
scene: Identify the environment or location, such as indoor room, street, beach, forest, or office. Return a short phrase.
activities: List clearly visible actions or interactions. Use short phrases and do not infer intent.
visual_attributes: List important visible colors, materials, patterns, clothing, shapes, and image characteristics as short phrases.
visible_text: Include only short, clearly readable text that is important to identifying the image. Use an empty string when none is legible.

Refrain from guesses. Include only details directly visible in the image.
Return only one valid JSON object with exactly these keys: subjects, scene, activities, visual_attributes, visible_text.
Use concise comma-separated phrases for list-like values. Do not include markdown, code fences, explanations, or text outside the JSON object.
""".trimIndent()

        /** Converts the model-card JSON extraction response into candidates for gallery tags. */
        internal fun parseStructuredResponse(raw: String): String {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            require(start >= 0 && end > start) { "LFM2.5 did not return a JSON object." }

            val json = JSONObject(raw.substring(start, end + 1))
            val values = mutableListOf<String>()

            fun collect(value: Any?) {
                when (value) {
                    null, JSONObject.NULL -> Unit
                    is String -> value.split(Regex("[,;\\n\\r|]"))
                        .map { it.trim().trim('"', '\'', ' ') }
                        .filter { it.length >= 2 }
                        .forEach { candidate -> if (candidate !in values) values.add(candidate) }
                    is JSONArray -> for (index in 0 until value.length()) collect(value.opt(index))
                    is JSONObject -> {
                        val keys = value.keys()
                        while (keys.hasNext()) collect(value.opt(keys.next()))
                    }
                    else -> collect(value.toString())
                }
            }

            val keys = json.keys()
            while (keys.hasNext()) collect(json.opt(keys.next()))
            require(values.isNotEmpty()) { "LFM2.5 returned JSON, but no usable tag values were present." }
            return values.take(24).joinToString(", ")
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
            lastErrorMessage = "LFM2.5 model or Q8_0 vision-projector file is missing or does not match the pinned artifact size."
            return@withContext false
        }

        synchronized(engineLock) {
            if (ready) return@synchronized true
            try {
                Lfm25VlNative.initialize()
                if (!Lfm25VlNative.loadModel(modelFile.absolutePath, projectorFile.absolutePath)) {
                    lastErrorMessage = Lfm25VlNative.lastError()
                        ?: "llama.cpp could not load LFM2.5-VL-450M-Extract and its vision projector."
                    Lfm25VlNative.close()
                    ready = false
                    return@synchronized false
                }
                ready = true
                lastErrorMessage = null
                true
            } catch (error: Throwable) {
                runCatching { Lfm25VlNative.close() }
                ready = false
                lastErrorMessage = "LFM2.5 runtime initialization failed: ${error.message ?: error.javaClass.simpleName}"
                false
            }
        }
    }

    override suspend fun generateImageTags(imageBytes: ByteArray): String = withContext(Dispatchers.IO) {
        require(imageBytes.isNotEmpty()) { "Image data is empty." }
        synchronized(engineLock) {
            check(ready) { lastErrorMessage ?: "LFM2.5 runtime is not initialized." }
            val response = Lfm25VlNative.generateImage(imageBytes, SYSTEM_PROMPT, 160)
                ?: throw IllegalStateException(Lfm25VlNative.lastError() ?: "LFM2.5 returned no output.")
            try {
                parseStructuredResponse(response)
            } catch (error: Throwable) {
                throw IllegalStateException(
                    "LFM2.5 returned an invalid extraction response: ${error.message ?: error.javaClass.simpleName}",
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
                reason = lastErrorMessage ?: "LFM2.5 llama.cpp runtime could not be opened.",
                failedStage = "runtime_initialization"
            )
        }

        val smokeImage = createSmokeTestImage()
        try {
            val extracted = generateImageTags(smokeImage)
            if (extracted.isBlank()) {
                SmokeTestResult.Failure(
                    reason = "LFM2.5 initialized but produced no structured image tags.",
                    failedStage = "image_inference"
                )
            } else {
                SmokeTestResult.Success(
                    durationMs = System.currentTimeMillis() - startedAt,
                    details = "llama.cpp loaded the Q4_K_M model and Q8_0 projector, applied the LFM2 extraction prompt, and returned parseable JSON tag fields.",
                    validatedTensors = listOf("image.bytes", "lfm2.5.vision.projector", "json.fields")
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            SmokeTestResult.Failure(
                reason = "LFM2.5 image-extraction smoke test failed: ${error.message ?: error.javaClass.simpleName}",
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
            runCatching { Lfm25VlNative.close() }
        }
    }
}

private object Lfm25VlNative {
    init {
        System.loadLibrary("emre_minicpm")
    }

    external fun initialize(): Boolean
    external fun loadModel(modelPath: String, projectorPath: String): Boolean
    external fun generateImage(imageBytes: ByteArray, systemPrompt: String, maxTokens: Int): String?
    external fun lastError(): String?
    external fun close()
}