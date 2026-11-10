package com.amresalehin.emregallery.ai.tagging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.amresalehin.emregallery.ai.local.Gemma4RuntimeAdapter
import com.amresalehin.emregallery.ai.local.LocalImageTaggingRuntime
import com.amresalehin.emregallery.ai.local.LocalModelInstaller
import com.amresalehin.emregallery.ai.local.LocalModelRegistry
import com.amresalehin.emregallery.ai.local.LocalModelRuntimeRegistry
import com.amresalehin.emregallery.ai.local.ModelArchitecture
import com.amresalehin.emregallery.data.CloudAiProvider
import com.amresalehin.emregallery.data.FlorenceStatus
import com.amresalehin.emregallery.data.MediaItem
import com.amresalehin.emregallery.data.PreferencesRepository
import com.amresalehin.emregallery.data.preferences.AiPreferencesManager
import com.amresalehin.emregallery.ocr.TesseractOcr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Result of an AI image tagging operation.
 */
data class AiTaggingResult(
    val tags: List<String>,
    val engineName: String,
    val durationMs: Long,
    val isSuccess: Boolean = true,
    val errorMessage: String? = null
)

/**
 * Comprehensive AI Image Tagging Engine.
 * Supports:
 * 1. Cloud Multimodal Vision AI (OpenAI, Groq, Ollama, OpenRouter, Anthropic-compatible endpoints)
 * 2. On-Device Vision-Language Model (Florence-2 Base)
 * 3. Smart On-Device Vision Analyzer (zero-latency offline analysis of color, scene, EXIF, and OCR features)
 */
object AiImageTagger {

    private const val TAG = "AiImageTagger"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * Tags a single media item and commits the tags to the user's preferences repository.
     */
    suspend fun tagAndSave(
        context: Context,
        item: MediaItem,
        prefs: PreferencesRepository,
        preferredModel: String? = null
    ): AiTaggingResult = withContext(Dispatchers.IO) {
        val aiPrefs = AiPreferencesManager(context)
        val allProviders = aiPrefs.cloudProviders.first()
        val activeCloudProvider = aiPrefs.activeProvider.first()
        val effectiveModel = preferredModel ?: AiTaggingSettings.getSelectedModel(context)

        // Find if effectiveModel points to a specific cloud provider by id
        val matchedProvider = allProviders.firstOrNull { it.id == effectiveModel || effectiveModel == "cloud_${it.id}" }
            ?: activeCloudProvider

        val result = generateTags(context, item, matchedProvider, effectiveModel)
        if (result.isSuccess && result.tags.isNotEmpty()) {
            prefs.addTagsForMedia(item.uri, result.tags)
        }
        result
    }

    /**
     * Generates a list of descriptive tags for the specified image item.
     * Supports configured Cloud Vision providers and Gemma 4 E2B local image inference.
     * Other local model files remain unavailable until a real inference adapter is implemented.
     */
    suspend fun generateTags(
        context: Context,
        item: MediaItem,
        cloudProvider: CloudAiProvider? = null,
        preferredModel: String? = null
    ): AiTaggingResult = withContext(Dispatchers.IO) {
        val startNs = System.nanoTime()
        val rawModel = (preferredModel ?: AiTaggingSettings.getSelectedModel(context)).trim()
        val targetModel = rawModel.lowercase()

        fun failure(engineName: String, message: String) = AiTaggingResult(
            tags = emptyList(),
            engineName = engineName,
            durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs),
            isSuccess = false,
            errorMessage = message
        )

        val localSpec = LocalModelRegistry.findSpec(targetModel)
        if (localSpec != null || LocalModelRegistry.isRetiredLocalModelId(targetModel)) {
            if (localSpec == null) {
                return@withContext failure("Local model unavailable", "This older local model generation is no longer supported. Select Gemma 4 or a Cloud AI provider.")
            }
            if (!localSpec.hasRuntimeAdapter || localSpec.architecture != ModelArchitecture.GEMMA4) {
                return@withContext failure(
                    localSpec.displayName,
                    "${localSpec.displayName} files may be downloaded, but this app does not have an image-inference runtime for that model. No tags were generated."
                )
            }
            Gemma4RuntimeAdapter.deviceClassRequirementMessage(context)?.let { message ->
                return@withContext failure("Gemma 4 E2B", message)
            }
            val installedState = LocalModelInstaller.checkInstallationStatus(context, localSpec)
            if (installedState.status != FlorenceStatus.READY || !installedState.runtimeReady) {
                val message = installedState.errorMessage ?: installedState.statusDetail
                    ?: "Gemma 4 E2B is not installed and runtime-validated. Download it in AI Settings first."
                return@withContext failure("Gemma 4 E2B", message)
            }
            if (item.isVideo) {
                return@withContext failure("Gemma 4 E2B", "Gemma 4 image tagging currently accepts photos, not video files.")
            }

            val modelDir = LocalModelInstaller.getModelDir(context, localSpec)
            val runtime = LocalModelRuntimeRegistry.getRuntime(localSpec)
                ?: return@withContext failure("Gemma 4 E2B", "No LiteRT-LM runtime adapter is registered.")
            if (!runtime.isReady && !runtime.open(context, modelDir)) {
                val message = (runtime as? Gemma4RuntimeAdapter)?.lastErrorMessage
                    ?: "LiteRT-LM could not initialize Gemma 4 on this device."
                LocalModelRuntimeRegistry.closeRuntime(localSpec.id)
                return@withContext failure("Gemma 4 E2B", message)
            }
            val imageRuntime = runtime as? LocalImageTaggingRuntime
                ?: return@withContext failure("Gemma 4 E2B", "The selected runtime does not expose image-to-text inference.")
            val bitmap = loadSampledBitmap(context, Uri.parse(item.uri), maxDimension = 448)
                ?: return@withContext failure("Gemma 4 E2B", "The image could not be decoded from the media URI.")
            try {
                val imageBytes = ByteArrayOutputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
                    output.toByteArray()
                }
                try {
                    val tags = cleanTagsList(imageRuntime.generateImageTags(imageBytes))
                    if (tags.isEmpty()) {
                        return@withContext failure("Gemma 4 E2B", "Gemma 4 returned no usable image tags. No placeholder tags were saved.")
                    }
                    return@withContext AiTaggingResult(
                        tags = tags,
                        engineName = "Gemma 4 E2B (On-device)",
                        durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
                    )
                } finally {
                    imageBytes.fill(0)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                return@withContext failure("Gemma 4 E2B", "Local image inference failed: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                bitmap.recycle()
            }
        }

        // 5. CLOUD MODELS        // 5. CLOUD MODELS (OpenAI, Google Gemini, Groq, Ollama, OpenRouter, etc.)
        val isExplicitCloud = targetModel == AiTaggingSettings.MODEL_CLOUD ||
                targetModel.startsWith("cloud_") ||
                (cloudProvider != null && (targetModel == cloudProvider.id.lowercase() || targetModel == cloudProvider.modelId.lowercase()))

        if (isExplicitCloud || (cloudProvider != null && cloudProvider.isActive && cloudProvider.baseUrl.isNotBlank())) {
            if (cloudProvider != null && cloudProvider.baseUrl.isNotBlank() && cloudProvider.isActive) {
                val modelOverride = when {
                    targetModel.startsWith("cloud_") -> null
                    targetModel != AiTaggingSettings.MODEL_CLOUD && targetModel != cloudProvider.id.lowercase() -> rawModel
                    else -> null
                }
                val modelNameUsed = modelOverride?.ifBlank { null } ?: cloudProvider.modelId.ifBlank { "default" }
                val cloudResult = runCatching {
                    tagWithCloudVision(context, item, cloudProvider, modelOverride)
                }.getOrNull()

                val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
                if (cloudResult != null && cloudResult.isNotEmpty()) {
                    return@withContext AiTaggingResult(
                        tags = cloudResult,
                        engineName = "Cloud Vision (${cloudProvider.name} • $modelNameUsed)",
                        durationMs = durationMs
                    )
                } else {
                    return@withContext AiTaggingResult(
                        tags = emptyList(),
                        engineName = "Cloud Vision (${cloudProvider.name} • $modelNameUsed)",
                        durationMs = durationMs,
                        isSuccess = false,
                        errorMessage = "Cloud AI request returned no tags. Check provider connection and API key."
                    )
                }
            }

            val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
            return@withContext AiTaggingResult(
                tags = emptyList(),
                engineName = "Cloud AI",
                durationMs = durationMs,
                isSuccess = false,
                errorMessage = "No active Cloud AI provider configured. Please configure an API endpoint in AI Settings."
            )
        }

        return@withContext failure(
            "AI Vision",
            "No usable local image model or active Cloud AI provider is configured. Select Gemma 4 or configure a Cloud AI provider in Settings."
        )
    }

    /**
     * Tags image using standard OpenAI-compatible or Google Gemini multimodal chat completions endpoint.
     */
    private suspend fun tagWithCloudVision(
        context: Context,
        item: MediaItem,
        provider: CloudAiProvider,
        modelOverride: String? = null
    ): List<String> = withContext(Dispatchers.IO) {
        val bitmap = loadSampledBitmap(context, Uri.parse(item.uri), maxDimension = 512)
            ?: return@withContext emptyList()

        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        val imageBytes = outputStream.toByteArray()
        bitmap.recycle()

        return@withContext tagWithCloudVisionDirect(provider, imageBytes, modelOverride)
    }

    /**
     * Tags directly using image byte array (supports OpenAI-compatible and Google Gemini formats).
     */
    suspend fun tagWithCloudVisionDirect(
        provider: CloudAiProvider,
        imageBytes: ByteArray,
        modelOverride: String? = null
    ): List<String> = withContext(Dispatchers.IO) {
        val base64Image = encodeBase64(imageBytes)

        val rawBase = provider.baseUrl.trim().trimEnd('/')
        val modelToUse = modelOverride?.takeIf { it.isNotBlank() && it != "cloud" && !it.startsWith("cloud_") && it != provider.id }
            ?: provider.modelId.ifBlank { "gpt-4o-mini" }

        // Check if this is a native Google Gemini endpoint (generativelanguage.googleapis.com without /openai)
        val isNativeGemini = rawBase.contains("generativelanguage.googleapis.com", ignoreCase = true) &&
                !rawBase.contains("/openai", ignoreCase = true)

        if (isNativeGemini) {
            val geminiModel = if (modelToUse.contains("/")) modelToUse.substringAfterLast('/') else modelToUse
            val geminiUrl = if (rawBase.contains(":generateContent")) {
                if (!rawBase.contains("key=")) "$rawBase?key=${provider.apiKey.trim()}" else rawBase
            } else {
                val cleanBase = if (rawBase.endsWith("/v1beta") || rawBase.endsWith("/v1")) rawBase else "$rawBase/v1beta"
                "$cleanBase/models/$geminiModel:generateContent?key=${provider.apiKey.trim()}"
            }

            val requestPayload = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", "Generate 4 to 8 concise, specific, lowercase keyword tags describing the subjects, setting, style, and objects in this photo. Return ONLY a comma-separated list of tags, without explanation or markdown.")
                            })
                            put(JSONObject().apply {
                                put("inline_data", JSONObject().apply {
                                    put("mime_type", "image/jpeg")
                                    put("data", base64Image)
                                })
                            })
                        })
                    })
                })
            }

            val reqBuilder = Request.Builder()
                .url(geminiUrl)
                .post(requestPayload.toString().toRequestBody("application/json".toMediaType()))

            val response = try {
                httpClient.newCall(reqBuilder.build()).execute()
            } catch (e: Exception) {
                null
            }

            if (response != null && response.isSuccessful) {
                val responseBody = response.body?.string().orEmpty()
                response.close()
                if (responseBody.isNotBlank()) {
                    val parsedJson = JSONObject(responseBody)
                    val candidates = parsedJson.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                        val text = parts?.optJSONObject(0)?.optString("text").orEmpty()
                        if (text.isNotBlank()) {
                            return@withContext cleanTagsList(text)
                        }
                    }
                }
            } else {
                response?.close()
            }
        }

        // Standard OpenAI-compatible /chat/completions endpoint
        val endpoint = when {
            rawBase.endsWith("/chat/completions", ignoreCase = true) -> rawBase
            rawBase.endsWith("/v1", ignoreCase = true) -> "$rawBase/chat/completions"
            else -> "$rawBase/v1/chat/completions"
        }

        val requestPayload = JSONObject().apply {
            put("model", modelToUse)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", "Generate 4 to 8 concise, specific, lowercase keyword tags describing the subjects, setting, style, and objects in this photo (e.g. sunset, beach, ocean, landscape, nature, portrait, food, architecture, document). Return ONLY a comma-separated list of tags, without explanation or markdown.")
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/jpeg;base64,$base64Image")
                            })
                        })
                    })
                })
            })
            put("max_tokens", 80)
            put("temperature", 0.2)
        }

        val reqBuilder = Request.Builder()
            .url(endpoint)
            .post(requestPayload.toString().toRequestBody("application/json".toMediaType()))

        if (provider.apiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${provider.apiKey.trim()}")
        }

        val response = httpClient.newCall(reqBuilder.build()).execute()
        val responseBody = response.body?.string().orEmpty()
        response.close()

        if (!response.isSuccessful || responseBody.isBlank()) {
            runCatching { Log.w(TAG, "Cloud tagging returned status ${response.code}: $responseBody") }
            return@withContext emptyList()
        }

        val parsedJson = JSONObject(responseBody)
        val content = when {
            parsedJson.has("choices") -> {
                val choices = parsedJson.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    choices.getJSONObject(0).optJSONObject("message")?.optString("content").orEmpty()
                } else ""
            }
            parsedJson.has("candidates") -> {
                val candidates = parsedJson.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                    parts?.optJSONObject(0)?.optString("text").orEmpty()
                } else ""
            }
            else -> ""
        }

        return@withContext cleanTagsList(content)
    }

    /**
     * Decodes a memory-efficient downsampled Bitmap from ContentResolver stream.
     */
    fun loadSampledBitmap(context: Context, uri: Uri, maxDimension: Int): Bitmap? {
        return runCatching {
            var input: InputStream? = context.contentResolver.openInputStream(uri) ?: return null
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(input, null, options)
            input?.close()

            val origWidth = options.outWidth
            val origHeight = options.outHeight
            if (origWidth <= 0 || origHeight <= 0) return null

            var inSampleSize = 1
            while ((origWidth / inSampleSize) > maxDimension * 1.5 || (origHeight / inSampleSize) > maxDimension * 1.5) {
                inSampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            input = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(input, null, decodeOptions)
            input?.close()

            bitmap
        }.getOrNull()
    }

    /**
     * Encodes byte array to Base64 string compatible with both Android and JVM unit tests.
     */
    fun encodeBase64(bytes: ByteArray): String {
        return try {
            java.util.Base64.getEncoder().encodeToString(bytes)
        } catch (e: Throwable) {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }
    }

    /**
     * Normalizes and cleans a collection of candidate tag strings.
     */
    fun cleanTagsList(tags: Collection<String>): List<String> {
        return cleanTagsList(tags.joinToString(", "))
    }

    /**
     * Normalizes and cleans raw comma/newline-separated tags text.
     */
    fun cleanTagsList(rawText: String): List<String> {
        val sanitized = rawText
            .replace(Regex("```[a-zA-Z]*"), "")
            .replace("```", "")
            .replace("[", "")
            .replace("]", "")
            .replace("\"", "")

        val split = sanitized.split(Regex("[,;\n•]"))
        val result = mutableListOf<String>()

        for (part in split) {
            val cleaned = part.trim()
                .removePrefix("#")
                .removePrefix("-")
                .removePrefix("*")
                .trim()
                .lowercase()

            if (cleaned.length in 2..28 &&
                !cleaned.startsWith("tag") &&
                !cleaned.startsWith("here") &&
                !cleaned.startsWith("photo") &&
                !cleaned.contains("description")
            ) {
                if (!result.contains(cleaned)) {
                    result.add(cleaned)
                }
            }
        }

        return result.take(8)
    }
}
