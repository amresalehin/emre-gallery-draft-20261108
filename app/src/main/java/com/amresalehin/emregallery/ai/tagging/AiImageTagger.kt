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
import com.amresalehin.emregallery.ai.local.MiniCpmV46RuntimeAdapter
import com.amresalehin.emregallery.ai.local.Lfm25VlRuntimeAdapter
import com.amresalehin.emregallery.ai.local.Qwen35RuntimeAdapter
import com.amresalehin.emregallery.ai.local.LocalModelInstaller
import com.amresalehin.emregallery.ai.local.LocalModelRegistry
import com.amresalehin.emregallery.ai.local.LocalModelRuntimeRegistry
import com.amresalehin.emregallery.ai.local.ModelArchitecture
import com.amresalehin.emregallery.data.CloudAiProvider
import com.amresalehin.emregallery.data.LocalModelStatus
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
 * 2. On-device MiniCPM-V 4.6 and Gemma 4 image inference
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
     * Supports configured Cloud Vision providers, Gemma 4 E2B, MiniCPM-V 4.6, LFM2.5-VL-450M-Extract, and Qwen3.5-0.8B local inference.
     * All four configured local model runtimes are available when their model artifacts are installed and validated.
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
                return@withContext failure(
                    "Local model unavailable",
                    "This local model generation is no longer supported. Select an installed local model or configure a Cloud AI provider."
                )
            }
            if (!localSpec.hasRuntimeAdapter) {
                return@withContext failure(
                    localSpec.displayName,
                    "${localSpec.displayName} can be downloaded, but its image-inference runtime is not available in this build. No tags were generated."
                )
            }
            if (item.isVideo) {
                return@withContext failure(localSpec.displayName, "${localSpec.displayName} image tagging currently accepts photos, not video files.")
            }

            val memoryMessage = when (localSpec.architecture) {
                ModelArchitecture.GEMMA4 -> Gemma4RuntimeAdapter.deviceClassRequirementMessage(context)
                ModelArchitecture.MINICPM_V46 -> MiniCpmV46RuntimeAdapter.deviceClassRequirementMessage(context)
                ModelArchitecture.LFM2_5 -> Lfm25VlRuntimeAdapter.memoryRequirementMessage(context)
                ModelArchitecture.QWEN3_5 -> Qwen35RuntimeAdapter.memoryRequirementMessage(context)
                else -> null
            }
            memoryMessage?.let { return@withContext failure(localSpec.displayName, it) }

            val installedState = LocalModelInstaller.checkInstallationStatus(context, localSpec)
            if (installedState.status != LocalModelStatus.READY || !installedState.runtimeReady) {
                val message = installedState.errorMessage ?: installedState.statusDetail
                    ?: "${localSpec.displayName} is not installed and runtime-validated. Download it in AI Settings first."
                return@withContext failure(localSpec.displayName, message)
            }

            val modelDir = LocalModelInstaller.getModelDir(context, localSpec)
            val runtime = LocalModelRuntimeRegistry.getRuntime(localSpec)
                ?: return@withContext failure(localSpec.displayName, "No runtime adapter is registered for this local model.")
            if (!runtime.isReady && !runtime.open(context, modelDir)) {
                val message = when (runtime) {
                    is Gemma4RuntimeAdapter -> runtime.lastErrorMessage
                    is MiniCpmV46RuntimeAdapter -> runtime.lastErrorMessage
                    is Lfm25VlRuntimeAdapter -> runtime.lastErrorMessage
                    is Qwen35RuntimeAdapter -> runtime.lastErrorMessage
                    else -> null
                } ?: "The selected local model could not initialize on this device."
                LocalModelRuntimeRegistry.closeRuntime(localSpec.id)
                return@withContext failure(localSpec.displayName, message)
            }
            val imageRuntime = runtime as? LocalImageTaggingRuntime
                ?: return@withContext failure(localSpec.displayName, "The selected runtime does not expose image-to-text inference.")
            val maxDimension = if (localSpec.architecture == ModelArchitecture.MINICPM_V46 || localSpec.architecture == ModelArchitecture.QWEN3_5) 768 else 448
            val bitmap = loadSampledBitmap(context, Uri.parse(item.uri), maxDimension)
                ?: return@withContext failure(localSpec.displayName, "The image could not be decoded from the media URI.")
            try {
                val imageBytes = ByteArrayOutputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
                    output.toByteArray()
                }
                try {
                    val tags = cleanTagsList(imageRuntime.generateImageTags(imageBytes))
                    if (tags.isEmpty()) {
                        return@withContext failure(
                            localSpec.displayName,
                            "${localSpec.displayName} returned no usable image tags. No placeholder tags were saved."
                        )
                    }
                    return@withContext AiTaggingResult(
                        tags = tags,
                        engineName = "${localSpec.displayName} (On-device)",
                        durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
                    )
                } finally {
                    imageBytes.fill(0)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                return@withContext failure(
                    localSpec.displayName,
                    "Local image inference failed: ${error.message ?: error.javaClass.simpleName}"
                )
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
            "No usable local image model or active Cloud AI provider is configured. Select MiniCPM-V 4.6 or Gemma 4, or configure a Cloud AI provider in Settings."
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
        if (imageBytes.isEmpty()) throw IOException("The image payload is empty.")

        val rawBase = provider.baseUrl.trim().trimEnd('/')
        if (rawBase.isBlank()) throw IOException("Cloud provider Base URL cannot be blank.")
        val normalizedBase = when {
            rawBase.startsWith("https://", ignoreCase = true) || rawBase.startsWith("http://", ignoreCase = true) -> rawBase
            else -> "https://$rawBase"
        }

        val modelToUse = modelOverride?.takeIf {
            it.isNotBlank() && it != "cloud" && !it.startsWith("cloud_") && it != provider.id
        } ?: provider.modelId.ifBlank {
            if (normalizedBase.contains("generativelanguage.googleapis.com", ignoreCase = true)) "gemini-2.5-flash"
            else "gpt-6.1-sol"
        }
        val base64Image = encodeBase64(imageBytes)
        val prompt = "Inspect this image and return 4 to 8 concise, lowercase search tags. " +
            "Describe only clearly visible subjects, objects, setting, activity, and visual style. " +
            "Do not infer identities or hidden context. Return only comma-separated tags, with no explanation."

        val isNativeGemini = normalizedBase.contains("generativelanguage.googleapis.com", ignoreCase = true) &&
            !normalizedBase.contains("/openai", ignoreCase = true)

        val request = if (isNativeGemini) {
            if (provider.apiKey.isBlank()) {
                throw IOException("Google Gemini requires an API key. Add a Gemini API key and save the provider.")
            }
            val geminiModel = modelToUse.removePrefix("models/").substringAfterLast('/')
            val endpoint = when {
                normalizedBase.endsWith(":generateContent", ignoreCase = true) -> normalizedBase
                Regex("/models/[^/]+:generateContent$", RegexOption.IGNORE_CASE).containsMatchIn(normalizedBase) -> normalizedBase
                normalizedBase.endsWith("/v1beta", ignoreCase = true) || normalizedBase.endsWith("/v1", ignoreCase = true) ->
                    "$normalizedBase/models/$geminiModel:generateContent"
                else -> "$normalizedBase/v1beta/models/$geminiModel:generateContent"
            }
            val payload = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", JSONArray()
                        .put(JSONObject().put("text", prompt))
                        .put(JSONObject().put("inline_data", JSONObject().apply {
                            put("mime_type", "image/jpeg")
                            put("data", base64Image)
                        }))
                    )
                }))
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.2)
                    put("maxOutputTokens", 160)
                })
            }
            Request.Builder()
                .url(endpoint)
                .header("x-goog-api-key", provider.apiKey.trim())
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
        } else {
            val endpoint = when {
                normalizedBase.endsWith("/chat/completions", ignoreCase = true) -> normalizedBase
                listOf("/v1", "/v1beta/openai", "/openai/v1", "/openai", "/api/v1", "/api/openai/v1")
                    .any { normalizedBase.endsWith(it, ignoreCase = true) } -> "$normalizedBase/chat/completions"
                else -> "$normalizedBase/v1/chat/completions"
            }
            val payload = JSONObject().apply {
                put("model", modelToUse)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray()
                        .put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                        .put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64Image"))
                        })
                    )
                }))
                put("max_tokens", 160)
                put("temperature", 0.2)
            }
            val builder = Request.Builder()
                .url(endpoint)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
            if (provider.apiKey.isNotBlank()) builder.header("Authorization", "Bearer ${provider.apiKey.trim()}")
            builder.build()
        }

        val response = httpClient.newCall(request).execute()
        val responseBody = response.use { it.body?.string().orEmpty() }
        if (!response.isSuccessful) {
            val errorPayload = runCatching { JSONObject(responseBody) }.getOrNull()
            val message = errorPayload?.optJSONObject("error")?.optString("message").orEmpty()
            val suffix = message.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
            val detail = when (response.code) {
                400 -> "rejected the image request"
                401 -> "rejected the API key"
                403 -> "denied access; check model permissions and billing"
                404 -> "could not find the endpoint or model"
                413 -> "rejected the image because the request is too large"
                429 -> "rate limit or quota reached"
                in 500..599 -> "is temporarily unavailable"
                else -> "request failed"
            }
            throw IOException("${provider.name} $detail (HTTP ${response.code})$suffix")
        }
        if (responseBody.isBlank()) throw IOException("Cloud provider returned an empty response.")

        val parsed = try {
            JSONObject(responseBody)
        } catch (error: Exception) {
            throw IOException("Cloud provider returned invalid JSON: ${error.message ?: "unparseable response"}")
        }
        val responseText = when {
            parsed.optString("output_text").isNotBlank() -> parsed.optString("output_text")
            parsed.optJSONArray("choices")?.optJSONObject(0) != null -> {
                val content = parsed.optJSONArray("choices")!!.optJSONObject(0)!!
                    .optJSONObject("message")?.opt("content")
                when (content) {
                    is String -> content
                    is JSONArray -> (0 until content.length()).mapNotNull { index ->
                        content.optJSONObject(index)?.optString("text")?.takeIf { it.isNotBlank() }
                    }.joinToString("\n")
                    else -> ""
                }
            }
            parsed.optJSONArray("candidates")?.optJSONObject(0) != null -> {
                val parts = parsed.optJSONArray("candidates")!!.optJSONObject(0)!!
                    .optJSONObject("content")?.optJSONArray("parts")
                (0 until (parts?.length() ?: 0)).mapNotNull { index ->
                    parts?.optJSONObject(index)?.optString("text")?.takeIf { it.isNotBlank() }
                }.joinToString("\n")
            }
            else -> ""
        }
        val tags = cleanTagsList(responseText)
        if (tags.isEmpty()) {
            val message = parsed.optJSONObject("error")?.optString("message").orEmpty()
            throw IOException(message.ifBlank {
                "The selected model returned no usable tags. Check the model ID and confirm that it supports image input."
            })
        }
        tags
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
