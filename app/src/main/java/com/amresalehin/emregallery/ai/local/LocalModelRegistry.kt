package com.amresalehin.emregallery.ai.local

/**
 * Authoritative registry of supported on-device AI models.
 * Replaces ad-hoc string URLs and approximate estimates with immutable
 * Hugging Face commit revisions, complete artifact manifests, authoritative sizes,
 * external tensor data relationships, and task classifications.
 */
object LocalModelRegistry {

    private const val HF_RESOLVE_BASE = "https://huggingface.co"

    // --------------------------------------------------------------------------
    // 1. Florence-2 Base Vision-Language Model
    // --------------------------------------------------------------------------
    const val FLORENCE_2_BASE_ID = "florence2"
    const val FLORENCE_2_CANONICAL_ID = "florence-2-base"
    const val FLORENCE_2_REPO = "onnx-community/Florence-2-base"
    const val FLORENCE_2_REVISION = "d59e079711c57174f29265539fb4cc9f0f335916"

    private fun florenceUrl(relativePath: String): String =
        "$HF_RESOLVE_BASE/$FLORENCE_2_REPO/resolve/$FLORENCE_2_REVISION/$relativePath"

    val FLORENCE_2_SPEC = LocalModelSpec(
        id = FLORENCE_2_BASE_ID,
        canonicalId = FLORENCE_2_CANONICAL_ID,
        legacyAliases = listOf("florence-2-base", "florence2_base"),
        displayName = "Florence-2 Base",
        upstreamRepo = FLORENCE_2_REPO,
        upstreamRevision = FLORENCE_2_REVISION,
        description = "Microsoft Florence-2 Base ONNX Vision-Language Model (Captioning, VQA, OCR & Extraction)",
        taskType = ModelTaskType.VISION_LANGUAGE,
        architecture = ModelArchitecture.FLORENCE2,
        license = "MIT",
        subdir = "models/florence2",
        manifestFileName = "florence_manifest.json",
        testTagPrefix = "florence",
        hasRuntimeAdapter = true,
        artifacts = listOf(
            LocalModelArtifact(
                relativePath = "config.json",
                sourceUrl = florenceUrl("config.json"),
                exactBytes = 5_447L,
                role = ArtifactRole.CONFIG
            ),
            LocalModelArtifact(
                relativePath = "generation_config.json",
                sourceUrl = florenceUrl("generation_config.json"),
                exactBytes = 297L,
                role = ArtifactRole.GENERATION_CONFIG
            ),
            LocalModelArtifact(
                relativePath = "preprocessor_config.json",
                sourceUrl = florenceUrl("preprocessor_config.json"),
                exactBytes = 2_673L,
                role = ArtifactRole.PREPROCESSOR_CONFIG
            ),
            LocalModelArtifact(
                relativePath = "processor_config.json",
                sourceUrl = florenceUrl("processor_config.json"),
                exactBytes = 130L,
                role = ArtifactRole.PROCESSOR_CONFIG
            ),
            LocalModelArtifact(
                relativePath = "tokenizer.json",
                sourceUrl = florenceUrl("tokenizer.json"),
                exactBytes = 2_297_961L,
                role = ArtifactRole.TOKENIZER
            ),
            LocalModelArtifact(
                relativePath = "tokenizer_config.json",
                sourceUrl = florenceUrl("tokenizer_config.json"),
                exactBytes = 197_658L,
                role = ArtifactRole.TOKENIZER_CONFIG
            ),
            LocalModelArtifact(
                relativePath = "special_tokens_map.json",
                sourceUrl = florenceUrl("special_tokens_map.json"),
                exactBytes = 146_627L,
                role = ArtifactRole.SPECIAL_TOKENS
            ),
            LocalModelArtifact(
                relativePath = "added_tokens.json",
                sourceUrl = florenceUrl("added_tokens.json"),
                exactBytes = 22_410L,
                role = ArtifactRole.SPECIAL_TOKENS
            ),
            LocalModelArtifact(
                relativePath = "onnx/vision_encoder_quantized.onnx",
                sourceUrl = florenceUrl("onnx/vision_encoder_quantized.onnx"),
                exactBytes = 93_788_276L,
                role = ArtifactRole.ONNX_GRAPH
            ),
            LocalModelArtifact(
                relativePath = "onnx/embed_tokens_quantized.onnx",
                sourceUrl = florenceUrl("onnx/embed_tokens_quantized.onnx"),
                exactBytes = 39_390_496L,
                role = ArtifactRole.ONNX_GRAPH
            ),
            LocalModelArtifact(
                relativePath = "onnx/encoder_model_quantized.onnx",
                sourceUrl = florenceUrl("onnx/encoder_model_quantized.onnx"),
                exactBytes = 43_651_493L,
                role = ArtifactRole.ONNX_GRAPH
            ),
            LocalModelArtifact(
                relativePath = "onnx/decoder_model_merged_quantized.onnx",
                sourceUrl = florenceUrl("onnx/decoder_model_merged_quantized.onnx"),
                exactBytes = 98_177_854L,
                role = ArtifactRole.ONNX_GRAPH
            )
        )
    )

    // --------------------------------------------------------------------------
    // 2. Google Gemma 4 E2B Multimodal Model (LiteRT-LM)
    // --------------------------------------------------------------------------
    const val GEMMA_4_E2B_ID = "gemma_4_e2b"
    const val GEMMA_4_CANONICAL_ID = "gemma-4-e2b-it-litert-lm"
    const val GEMMA_4_REPO = "litert-community/gemma-4-E2B-it-litert-lm"
    const val GEMMA_4_REVISION = "3d48132a00cf76de57d926de30f5c5c5ed9b8c0d"

    private fun gemma4Url(fileName: String): String =
        "$HF_RESOLVE_BASE/$GEMMA_4_REPO/resolve/$GEMMA_4_REVISION/$fileName"

    val GEMMA_4_SPEC = LocalModelSpec(
        id = GEMMA_4_E2B_ID,
        canonicalId = GEMMA_4_CANONICAL_ID,
        legacyAliases = listOf("gemma4", "gemma_4", "gemma_3_1b_it", "gemma3_1b"),
        displayName = "Gemma 4 E2B",
        upstreamRepo = GEMMA_4_REPO,
        upstreamRevision = GEMMA_4_REVISION,
        description = "Google Gemma 4 E2B Multimodal Vision-Language Model for LiteRT-LM (CPU/GPU generic runtime, 2.59 GB)",
        taskType = ModelTaskType.VISION_LANGUAGE,
        architecture = ModelArchitecture.GEMMA4,
        license = "Gemma Terms of Use",
        subdir = "models/gemma_4_e2b",
        manifestFileName = "gemma4_manifest.json",
        testTagPrefix = "gemma",
        hasRuntimeAdapter = false, // Assets downloaded — runtime unavailable (LiteRT-LM runtime adapter not active)
        artifacts = listOf(
            LocalModelArtifact(
                relativePath = "gemma-4-E2B-it.litertlm",
                sourceUrl = gemma4Url("gemma-4-E2B-it.litertlm"),
                exactBytes = 2_781_778_740L,
                role = ArtifactRole.LITERT_MODEL
            )
        )
    )

    // Backwards-compatibility alias
    val GEMMA_3_SPEC = GEMMA_4_SPEC

    // --------------------------------------------------------------------------
    // 3. Liquid AI LFM2.5-VL-450M-Extract (llama.cpp)
    // --------------------------------------------------------------------------
    const val LFM2_5_VL_ID = "lfm2_5_vl_450m_extract"
    const val LFM2_5_CANONICAL_ID = "lfm2.5-vl-450m-extract-gguf"
    const val LFM2_5_REPO = "LiquidAI/LFM2.5-VL-450M-Extract-GGUF"
    const val LFM2_5_REVISION = "be9e242e7c37db9ddbf9f88d7df939159fdfa186"

    private fun lfmUrl(fileName: String): String =
        "$HF_RESOLVE_BASE/$LFM2_5_REPO/resolve/$LFM2_5_REVISION/$fileName"

    val LFM2_5_SPEC = LocalModelSpec(
        id = LFM2_5_VL_ID,
        canonicalId = LFM2_5_CANONICAL_ID,
        legacyAliases = listOf("lfm2_5", "lfm2_5_vl", "lfm2_350m", "lfm2_350m_extract"),
        displayName = "LFM2.5-VL-450M-Extract",
        upstreamRepo = LFM2_5_REPO,
        upstreamRevision = LFM2_5_REVISION,
        description = "Liquid AI LFM2.5-VL-450M-Extract Vision-Language Model for structured tags and JSON (llama.cpp Q4_K_M + Q8_0 projector)",
        taskType = ModelTaskType.VISION_LANGUAGE,
        architecture = ModelArchitecture.LFM2_5,
        license = "lfm1.0",
        subdir = "models/lfm2_5_vl_450m_extract",
        manifestFileName = "lfm2_5_manifest.json",
        testTagPrefix = "lfm",
        hasRuntimeAdapter = false, // Assets downloaded — runtime unavailable (llama.cpp runtime adapter not active)
        artifacts = listOf(
            LocalModelArtifact(
                relativePath = "LFM2.5-VL-450M-Extract-Q4_K_M.gguf",
                sourceUrl = lfmUrl("LFM2.5-VL-450M-Extract-Q4_K_M.gguf"),
                exactBytes = 240_668_768L,
                role = ArtifactRole.GGUF_MODEL
            ),
            LocalModelArtifact(
                relativePath = "mmproj-LFM2.5-VL-450M-Extract-Q8_0.gguf",
                sourceUrl = lfmUrl("mmproj-LFM2.5-VL-450M-Extract-Q8_0.gguf"),
                exactBytes = 108_136_832L,
                role = ArtifactRole.GGUF_MMPROJ
            )
        )
    )

    // Backwards-compatibility alias
    val LFM2_SPEC = LFM2_5_SPEC

    // --------------------------------------------------------------------------
    // 4. Alibaba Qwen 3.5 0.8B (llama.cpp / Unsloth)
    // --------------------------------------------------------------------------
    const val QWEN3_5_ID = "qwen3_5_0_8b"
    const val QWEN3_5_CANONICAL_ID = "qwen3.5-0.8b-gguf"
    const val QWEN3_5_REPO = "unsloth/Qwen3.5-0.8B-GGUF"
    const val QWEN3_5_REVISION = "6ab461498e2023f6e3c1baea90a8f0fe38ab64d0"

    private fun qwenUrl(fileName: String): String =
        "$HF_RESOLVE_BASE/$QWEN3_5_REPO/resolve/$QWEN3_5_REVISION/$fileName"

    val QWEN3_5_SPEC = LocalModelSpec(
        id = QWEN3_5_ID,
        canonicalId = QWEN3_5_CANONICAL_ID,
        legacyAliases = listOf("qwen3_5", "qwen3.5-0.8b", "qwen2_5_0_5b", "qwen2_5_05b"),
        displayName = "Qwen3.5-0.8B",
        upstreamRepo = QWEN3_5_REPO,
        upstreamRevision = QWEN3_5_REVISION,
        description = "Alibaba Qwen 3.5 0.8B Quantized Vision-Language Model with Projector (llama.cpp / Unsloth Q4_K_XL + F16 projector)",
        taskType = ModelTaskType.VISION_LANGUAGE,
        architecture = ModelArchitecture.QWEN3_5,
        license = "Apache-2.0",
        subdir = "models/qwen3_5_0_8b",
        manifestFileName = "qwen3_5_manifest.json",
        testTagPrefix = "qwen",
        hasRuntimeAdapter = false, // Assets downloaded — runtime unavailable (llama.cpp runtime adapter not active)
        artifacts = listOf(
            LocalModelArtifact(
                relativePath = "Qwen3.5-0.8B-UD-Q4_K_XL.gguf",
                sourceUrl = qwenUrl("Qwen3.5-0.8B-UD-Q4_K_XL.gguf"),
                exactBytes = 586_303_872L,
                role = ArtifactRole.GGUF_MODEL
            ),
            LocalModelArtifact(
                relativePath = "mmproj-F16.gguf",
                sourceUrl = qwenUrl("mmproj-F16.gguf"),
                exactBytes = 215_286_400L,
                role = ArtifactRole.GGUF_MMPROJ
            )
        )
    )

    // Backwards-compatibility alias
    val QWEN_2_5_SPEC = QWEN3_5_SPEC

    val ALL_MODELS: List<LocalModelSpec> = listOf(
        GEMMA_4_SPEC,
        LFM2_5_SPEC,
        QWEN3_5_SPEC,
        FLORENCE_2_SPEC
    )

    fun findSpec(idOrAlias: String): LocalModelSpec? {
        val normalized = idOrAlias.trim().lowercase()
        return ALL_MODELS.firstOrNull { spec ->
            spec.id.equals(normalized, ignoreCase = true) ||
                    spec.canonicalId.equals(normalized, ignoreCase = true) ||
                    spec.legacyAliases.any { it.equals(normalized, ignoreCase = true) }
        }
    }

    fun getSpec(idOrAlias: String): LocalModelSpec {
        return findSpec(idOrAlias) ?: FLORENCE_2_SPEC
    }
}
