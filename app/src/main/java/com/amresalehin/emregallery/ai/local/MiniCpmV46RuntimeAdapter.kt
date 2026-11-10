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
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * OpenBMB MiniCPM-V 4.6 (1.3B) image-to-text runtime.
 *
 * Runs the official Q4_K_M GGUF plus F16 vision projector through llama.cpp's
 * Android NDK build and libmtmd. Model pixels are passed to native inference;
 * no cloud request or placeholder taxonomy is used.
 */
class MiniCpmV46RuntimeAdapter(
    override val spec: LocalModelSpec = LocalModelRegistry.MINI_CPM_V46_SPEC
) : LocalModelRuntime, LocalImageTaggingRuntime {

    companion object {
        const val MODEL_FILE_NAME = "MiniCPM-V-4_6-Q4_K_M.gguf"
        const val PROJECTOR_FILE_NAME = "mmproj-model-f16.gguf"
        private const val MIN_DEVICE_RAM_BYTES = 5_500L * 1024L * 1024L
        private const val MIN_AVAILABLE_RAM_BYTES = 1_200L * 1024L * 1024L

        private const val TAGGING_PROMPT =
            "Inspect the image itself and identify only visually supported details. " +
            "Return 4 to 8 concise, specific, lowercase search tags covering visible subjects, " +
            "objects, scene, activity, and setting. Return a comma-separated list only. " +
            "Do not include explanations, markdown, generic AI terms, or guesses."

        /** OpenBMB recommends a 6 GB RAM-class device for the Q4_K_M release. */
        fun deviceClassRequirementMessage(context: Context): String? {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return "Unable to verify device memory for MiniCPM-V 4.6."
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            return if (info.totalMem < MIN_DEVICE_RAM_BYTES) {
                "MiniCPM-V 4.6 Q4_K_M is intended for a 6 GB RAM-class Android device. This device is below the recommended memory class."
            } else null
        }

        private fun runtimeMemoryRequirementMessage(context: Context): String? {
            deviceClassRequirementMessage(context)?.let { return it }
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return "Unable to verify available memory for MiniCPM-V 4.6."
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            return if (info.availMem < MIN_AVAILABLE_RAM_BYTES) {
                "Not enough free memory to load MiniCPM-V 4.6 safely. Close other apps and retry."
            } else null
        }
    }

    private val engineLock = Any()
    @Volatile private var ready = false
    @Volatile var lastErrorMessage: String? = null
        private set

    override val isReady: Boolean
        get() = ready

    override suspend fun open(context: Context, modelDir: File): Boolean = withContext(Dispatchers.IO) {
        runtimeMemoryRequirementMessage(context)?.let {
            lastErrorMessage = it
            return@withContext false
        }

        val modelFile = File(modelDir, MODEL_FILE_NAME)
        val projectorFile = File(modelDir, PROJECTOR_FILE_NAME)
        val modelSpec = spec.artifacts.firstOrNull { it.relativePath == MODEL_FILE_NAME }
        val projectorSpec = spec.artifacts.firstOrNull { it.relativePath == PROJECTOR_FILE_NAME }
        if (!modelFile.isFile || modelFile.length() < (modelSpec?.expectedMinBytes ?: 1L) ||
            !projectorFile.isFile || projectorFile.length() < (projectorSpec?.expectedMinBytes ?: 1L)
        ) {
            lastErrorMessage = "MiniCPM-V 4.6 model or vision-projector file is missing or truncated."
            return@withContext false
        }

        synchronized(engineLock) {
            if (ready) return@synchronized true
            try {
                MiniCpmV46Native.initialize()
                if (!MiniCpmV46Native.loadModel(modelFile.absolutePath, projectorFile.absolutePath)) {
                    lastErrorMessage = MiniCpmV46Native.lastError()
                        ?: "llama.cpp could not load MiniCPM-V 4.6 and its vision projector."
                    MiniCpmV46Native.close()
                    ready = false
                    return@synchronized false
                }
                ready = true
                lastErrorMessage = null
                true
            } catch (error: Throwable) {
                runCatching { MiniCpmV46Native.close() }
                ready = false
                lastErrorMessage = "MiniCPM-V 4.6 runtime initialization failed: ${error.message ?: error.javaClass.simpleName}"
                false
            }
        }
    }

    override suspend fun generateImageTags(imageBytes: ByteArray): String = withContext(Dispatchers.IO) {
        require(imageBytes.isNotEmpty()) { "Image data is empty." }
        synchronized(engineLock) {
            check(ready) { lastErrorMessage ?: "MiniCPM-V 4.6 runtime is not initialized." }
            val response = MiniCpmV46Native.generateImage(imageBytes, TAGGING_PROMPT, 96)
            response?.trim()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException(
                    MiniCpmV46Native.lastError() ?: "MiniCPM-V 4.6 returned an empty response."
                )
        }
    }

    override suspend fun validateGraphAndRunSmokeTest(
        context: Context,
        modelDir: File
    ): SmokeTestResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        if (!open(context, modelDir)) {
            return@withContext SmokeTestResult.Failure(
                reason = lastErrorMessage ?: "MiniCPM-V 4.6 native runtime could not be opened.",
                failedStage = "runtime_initialization"
            )
        }

        val smokeImage = createSmokeTestImage()
        try {
            val output = generateImageTags(smokeImage)
            if (output.isBlank()) {
                SmokeTestResult.Failure(
                    reason = "MiniCPM-V 4.6 initialized but returned an empty response for image input.",
                    failedStage = "image_inference"
                )
            } else {
                SmokeTestResult.Success(
                    durationMs = System.currentTimeMillis() - startedAt,
                    details = "llama.cpp loaded both GGUF artifacts and completed image-bytes-to-text inference; the smoke response was non-empty.",
                    validatedTensors = listOf("image.bytes", "vision.projector", "generated.text")
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            SmokeTestResult.Failure(
                reason = "MiniCPM-V 4.6 image-inference smoke test failed: ${error.message ?: error.javaClass.simpleName}",
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
            runCatching { MiniCpmV46Native.close() }
        }
    }
}

private object MiniCpmV46Native {
    init {
        System.loadLibrary("emre_minicpm")
    }

    external fun initialize(): Boolean
    external fun loadModel(modelPath: String, projectorPath: String): Boolean
    external fun generateImage(imageBytes: ByteArray, prompt: String, maxTokens: Int): String?
    external fun lastError(): String?
    external fun close()
}
