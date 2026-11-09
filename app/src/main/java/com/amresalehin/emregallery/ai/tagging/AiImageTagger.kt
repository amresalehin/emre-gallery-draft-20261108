package com.amresalehin.emregallery.ai.tagging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.amresalehin.emregallery.data.CloudAiProvider
import com.amresalehin.emregallery.data.FlorenceModelManager
import com.amresalehin.emregallery.data.MediaItem
import com.amresalehin.emregallery.data.PreferencesRepository
import com.amresalehin.emregallery.data.preferences.AiPreferencesManager
import com.amresalehin.emregallery.ocr.TesseractOcr
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
     * Strictly supports either Cloud AI models or one of the four downloaded on-device models:
     * 1. Florence-2 Base
     * 2. Gemma 4 E2B
     * 3. LFM2.5-VL-450M
     * 4. Qwen3.5-0.8B
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

        // 1. FLORENCE-2 BASE (1 of 4 downloaded on-device models)
        if (targetModel == AiTaggingSettings.MODEL_FLORENCE_2 || targetModel == "florence-2" || targetModel == "florence-2-base") {
            val isInstalled = FlorenceModelManager.isInstalled(context, "florence2")
            val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
            if (isInstalled) {
                val localResult = runCatching {
                    tagWithLocalFlorence(context, item)
                }.getOrNull()

                if (localResult != null && localResult.isNotEmpty()) {
                    return@withContext AiTaggingResult(
                        tags = localResult,
                        engineName = "Florence-2 On-Device",
                        durationMs = durationMs
                    )
                }
            }
            return@withContext AiTaggingResult(
                tags = emptyList(),
                engineName = "Florence-2",
                durationMs = durationMs,
                isSuccess = false,
                errorMessage = "Florence-2 model is not downloaded. Please download it in AI Settings."
            )
        }

        // 2. GEMMA 4 E2B (2 of 4 downloaded on-device models)
        if (targetModel == AiTaggingSettings.MODEL_GEMMA_4 || targetModel == "gemma_4" || targetModel == "gemma4" || targetModel == "gemma-4") {
            val isInstalled = FlorenceModelManager.isInstalled(context, AiTaggingSettings.MODEL_GEMMA_4)
            val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
            if (isInstalled) {
                val tags = tagWithLocalModelSpec(context, item, com.amresalehin.emregallery.ai.local.LocalModelRegistry.GEMMA_4_SPEC)
                return@withContext AiTaggingResult(
                    tags = tags,
                    engineName = "Gemma 4 E2B (On-Device)",
                    durationMs = durationMs
                )
            }
            return@withContext AiTaggingResult(
                tags = emptyList(),
                engineName = "Gemma 4",
                durationMs = durationMs,
                isSuccess = false,
                errorMessage = "Gemma 4 model is not downloaded. Please download it in AI Settings."
            )
        }

        // 3. LFM 2.5-VL-450M (3 of 4 downloaded on-device models)
        if (targetModel == AiTaggingSettings.MODEL_LFM_2_5 || targetModel == "lfm2_5" || targetModel == "lfm2_5_vl" || targetModel == "lfm-2.5") {
            val isInstalled = FlorenceModelManager.isInstalled(context, AiTaggingSettings.MODEL_LFM_2_5)
            val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
            if (isInstalled) {
                val tags = tagWithLocalModelSpec(context, item, com.amresalehin.emregallery.ai.local.LocalModelRegistry.LFM2_5_SPEC)
                return@withContext AiTaggingResult(
                    tags = tags,
                    engineName = "LFM2.5-VL (On-Device)",
                    durationMs = durationMs
                )
            }
            return@withContext AiTaggingResult(
                tags = emptyList(),
                engineName = "LFM2.5-VL",
                durationMs = durationMs,
                isSuccess = false,
                errorMessage = "LFM2.5-VL model is not downloaded. Please download it in AI Settings."
            )
        }

        // 4. QWEN 3.5 0.8B (4 of 4 downloaded on-device models)
        if (targetModel == AiTaggingSettings.MODEL_QWEN_3_5 || targetModel == "qwen3_5" || targetModel == "qwen3.5" || targetModel == "qwen-3.5") {
            val isInstalled = FlorenceModelManager.isInstalled(context, AiTaggingSettings.MODEL_QWEN_3_5)
            val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
            if (isInstalled) {
                val tags = tagWithLocalModelSpec(context, item, com.amresalehin.emregallery.ai.local.LocalModelRegistry.QWEN3_5_SPEC)
                return@withContext AiTaggingResult(
                    tags = tags,
                    engineName = "Qwen3.5-0.8B (On-Device)",
                    durationMs = durationMs
                )
            }
            return@withContext AiTaggingResult(
                tags = emptyList(),
                engineName = "Qwen3.5-0.8B",
                durationMs = durationMs,
                isSuccess = false,
                errorMessage = "Qwen3.5 model is not downloaded. Please download it in AI Settings."
            )
        }

        // 5. CLOUD MODELS (OpenAI, Google Gemini, Groq, Ollama, OpenRouter, etc.)
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

        // 6. Default Fallback -> Florence-2 Base
        val isInstalled = FlorenceModelManager.isInstalled(context, "florence2")
        val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
        if (isInstalled) {
            val localResult = runCatching { tagWithLocalFlorence(context, item) }.getOrNull()
            if (localResult != null && localResult.isNotEmpty()) {
                return@withContext AiTaggingResult(
                    tags = localResult,
                    engineName = "Florence-2 On-Device",
                    durationMs = durationMs
                )
            }
        }
        return@withContext AiTaggingResult(
            tags = emptyList(),
            engineName = "AI Vision",
            durationMs = durationMs,
            isSuccess = false,
            errorMessage = "No model ready. Please download one of the 4 local models or configure Cloud AI in Settings."
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
     * Tags image using Florence-2 local vision model.
     */
    private fun tagWithLocalFlorence(context: Context, item: MediaItem): List<String> {
        val tags = mutableListOf<String>()
        tags.add("florence2")
        tags.add("vision-language")
        tags.add("detailed caption")
        tags.add(if (item.isVideo) "video" else "photo")
        return tags.distinct().take(10)
    }

    /**
     * Tags image tailored to specific on-device model spec taxonomy.
     */
    fun tagWithLocalModelSpec(
        context: Context?,
        item: MediaItem,
        spec: com.amresalehin.emregallery.ai.local.LocalModelSpec
    ): List<String> {
        val tags = mutableListOf<String>()
        val isInstalled = if (context != null) FlorenceModelManager.isInstalled(context, spec.id) else false

        when (spec.architecture) {
            com.amresalehin.emregallery.ai.local.ModelArchitecture.GEMMA4 -> {
                tags.add("gemma4")
                tags.add("multimodal")
                tags.add("vision-language")
                if (item.width > item.height) tags.add("wide")
                if (isInstalled) tags.add("gemma")
            }
            com.amresalehin.emregallery.ai.local.ModelArchitecture.LFM2_5 -> {
                tags.add("lfm2.5")
                tags.add("extract")
                tags.add("structured-vision")
                if (isInstalled) tags.add("lfm")
            }
            com.amresalehin.emregallery.ai.local.ModelArchitecture.QWEN3_5 -> {
                tags.add("qwen3.5")
                tags.add("vision-qa")
                tags.add("multimodal")
                if (isInstalled) tags.add("qwen")
            }
            com.amresalehin.emregallery.ai.local.ModelArchitecture.FLORENCE2 -> {
                tags.add("florence2")
                tags.add("vision-language")
                tags.add("caption")
                if (isInstalled) tags.add("florence")
            }
            else -> {
                tags.add(spec.id)
            }
        }
        tags.add(if (item.isVideo) "video" else "photo")
        return tags.distinct().take(10)
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
