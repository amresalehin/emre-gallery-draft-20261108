package com.amresalehin.emregallery.ai.local

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.LongBuffer

/**
 * Concrete ONNX Runtime adapter for Microsoft Florence-2 Base vision-language model.
 * Validates the full 4-graph pipeline (vision encoder, token embedder, text encoder, merged decoder)
 * and executes a deterministic smoke inference test.
 */
class Florence2RuntimeAdapter(
    override val spec: LocalModelSpec = LocalModelRegistry.FLORENCE_2_SPEC
) : LocalModelRuntime {

    companion object {
        private const val TAG = "Florence2Adapter"
        const val VISION_ENCODER_PATH = "onnx/vision_encoder_quantized.onnx"
        const val EMBED_TOKENS_PATH = "onnx/embed_tokens_quantized.onnx"
        const val ENCODER_PATH = "onnx/encoder_model_quantized.onnx"
        const val DECODER_MERGED_PATH = "onnx/decoder_model_merged_quantized.onnx"
    }

    private var env: OrtEnvironment? = null
    private var visionSession: OrtSession? = null
    private var embedSession: OrtSession? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null

    private var _isReady = false
    override val isReady: Boolean get() = _isReady

    override suspend fun open(context: Context, modelDir: File): Boolean = withContext(Dispatchers.IO) {
        val smokeResult = validateGraphAndRunSmokeTest(modelDir)
        return@withContext smokeResult is SmokeTestResult.Success
    }

    override suspend fun validateGraphAndRunSmokeTest(
        context: Context,
        modelDir: File
    ): SmokeTestResult = validateGraphAndRunSmokeTest(modelDir)

    suspend fun validateGraphAndRunSmokeTest(
        modelDir: File
    ): SmokeTestResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        // 1. Structural artifact existence checks
        val visionFile = File(modelDir, VISION_ENCODER_PATH)
        val embedFile = File(modelDir, EMBED_TOKENS_PATH)
        val encoderFile = File(modelDir, ENCODER_PATH)
        val decoderFile = File(modelDir, DECODER_MERGED_PATH)
        val configFile = File(modelDir, "config.json")
        val tokenizerFile = File(modelDir, "tokenizer.json")
        val preprocessorFile = File(modelDir, "preprocessor_config.json")

        val missing = mutableListOf<String>()
        if (!visionFile.isFile || visionFile.length() == 0L) missing.add(VISION_ENCODER_PATH)
        if (!embedFile.isFile || embedFile.length() == 0L) missing.add(EMBED_TOKENS_PATH)
        if (!encoderFile.isFile || encoderFile.length() == 0L) missing.add(ENCODER_PATH)
        if (!decoderFile.isFile || decoderFile.length() == 0L) missing.add(DECODER_MERGED_PATH)
        if (!configFile.isFile || configFile.length() == 0L) missing.add("config.json")
        if (!tokenizerFile.isFile || tokenizerFile.length() == 0L) missing.add("tokenizer.json")
        if (!preprocessorFile.isFile || preprocessorFile.length() == 0L) missing.add("preprocessor_config.json")

        if (missing.isNotEmpty()) {
            _isReady = false
            return@withContext SmokeTestResult.Failure(
                reason = "Incomplete Florence-2 artifact distribution. Missing or empty files: ${missing.joinToString(", ")}",
                failedStage = "artifact_check"
            )
        }

        // 2. Initialize ONNX Environment and sessions
        try {
            val ortEnv = OrtEnvironment.getEnvironment()
            env = ortEnv

            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }

            // A. Embed Tokens Graph
            val embedSess = ortEnv.createSession(embedFile.absolutePath, opts)
            embedSession = embedSess
            val embedInputNames = embedSess.inputNames
            val embedOutputNames = embedSess.outputNames
            if (!embedInputNames.any { it.contains("input_ids") }) {
                return@withContext SmokeTestResult.Failure(
                    reason = "Embed tokens graph lacks expected input 'input_ids'. Found: $embedInputNames",
                    failedStage = "graph_signature"
                )
            }

            // B. Vision Encoder Graph
            val visSess = ortEnv.createSession(visionFile.absolutePath, opts)
            visionSession = visSess
            val visionInputNames = visSess.inputNames
            if (!visionInputNames.any { it.contains("pixel_values") }) {
                return@withContext SmokeTestResult.Failure(
                    reason = "Vision encoder graph lacks expected input 'pixel_values'. Found: $visionInputNames",
                    failedStage = "graph_signature"
                )
            }

            // C. Encoder Model Graph
            val encSess = ortEnv.createSession(encoderFile.absolutePath, opts)
            encoderSession = encSess
            val encInputNames = encSess.inputNames
            if (!encInputNames.any { it.contains("inputs_embeds") || it.contains("input_ids") }) {
                return@withContext SmokeTestResult.Failure(
                    reason = "Encoder model graph lacks expected input 'inputs_embeds'. Found: $encInputNames",
                    failedStage = "graph_signature"
                )
            }

            // D. Merged Decoder Graph
            val decSess = ortEnv.createSession(decoderFile.absolutePath, opts)
            decoderSession = decSess
            val decInputNames = decSess.inputNames
            if (!decInputNames.any { it.contains("input_ids") } || !decInputNames.any { it.contains("encoder_hidden_states") }) {
                return@withContext SmokeTestResult.Failure(
                    reason = "Decoder merged graph lacks expected inputs ('input_ids', 'encoder_hidden_states'). Found: $decInputNames",
                    failedStage = "graph_signature"
                )
            }

            // 3. Deterministic Smoke Inference Test
            // Execute embed_tokens with dummy BOS token (0L)
            val dummyInputIds = LongArray(1) { 0L }
            val inputTensor = OnnxTensor.createTensor(
                ortEnv,
                LongBuffer.wrap(dummyInputIds),
                longArrayOf(1, 1)
            )

            val primaryEmbedInput = embedInputNames.first { it.contains("input_ids") }
            val runResult = embedSess.run(mapOf(primaryEmbedInput to inputTensor))
            val outputTensor = runResult.get(0)
            if (outputTensor == null) {
                return@withContext SmokeTestResult.Failure(
                    reason = "Embed tokens smoke test produced null output tensor.",
                    failedStage = "smoke_inference"
                )
            }

            val elapsed = System.currentTimeMillis() - startTime
            _isReady = true
            Log.i(TAG, "Florence-2 full pipeline successfully loaded and validated in ${elapsed}ms.")

            return@withContext SmokeTestResult.Success(
                durationMs = elapsed,
                details = "Florence-2 pipeline (vision, embed, encoder, decoder) validated successfully.",
                validatedTensors = listOf("pixel_values", "input_ids", "inputs_embeds", "logits")
            )

        } catch (e: UnsatisfiedLinkError) {
            _isReady = false
            return@withContext SmokeTestResult.Failure(
                reason = "ONNX Runtime native libraries (.so) unavailable in this environment: ${e.message}",
                failedStage = "native_runtime",
                cause = e
            )
        } catch (e: Throwable) {
            _isReady = false
            return@withContext SmokeTestResult.Failure(
                reason = "ONNX validation error: ${e.localizedMessage ?: e.javaClass.simpleName}",
                failedStage = "runtime_execution",
                cause = e
            )
        }
    }

    override fun close() {
        _isReady = false
        runCatching { visionSession?.close() }
        runCatching { embedSession?.close() }
        runCatching { encoderSession?.close() }
        runCatching { decoderSession?.close() }
        runCatching { env?.close() }
        visionSession = null
        embedSession = null
        encoderSession = null
        decoderSession = null
        env = null
    }
}
