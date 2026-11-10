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
    // 1. OpenBMB MiniCPM-V 4.6 (1.3B) multimodal model (llama.cpp)
    // --------------------------------------------------------------------------
    const val MINI_CPM_V46_ID = "minicpm_v_4_6"
    const val MINI_CPM_V46_CANONICAL_ID = "minicpm-v-4.6-instruct"
    const val MINI_CPM_V46_REPO = "openbmb/MiniCPM-V-4.6-gguf"
    const val MINI_CPM_V46_REVISION = "78e02f066e9819a60573b78a4275df8a0c27f698"

    private fun miniCpmV46Url(fileName: String): String =
        "$HF_RESOLVE_BASE/$MINI_CPM_V46_REPO/resolve/$MINI_CPM_V46_REVISION/$fileName"

    val MINI_CPM_V46_SPEC = LocalModelSpec(
        id = MINI_CPM_V46_ID,
        canonicalId = MINI_CPM_V46_CANONICAL_ID,
        legacyAliases = listOf("minicpm-v-4.6", "minicpm_v46"),
        displayName = "MiniCPM-V 4.6 (1.3B)",
        upstreamRepo = MINI_CPM_V46_REPO,
        upstreamRevision = MINI_CPM_V46_REVISION,
        description = "OpenBMB MiniCPM-V 4.6 1.3B vision-language model using Q4_K_M weights and its F16 vision projector (about 1.6 GB; 6 GB RAM-class device recommended).",
        taskType = ModelTaskType.VISION_LANGUAGE,
        architecture = ModelArchitecture.MINICPM_V46,
        license = "Apache-2.0",
        subdir = "models/minicpm_v_4_6",
        manifestFileName = "minicpmv46_manifest.json",
        testTagPrefix = "minicpmv46",
        hasRuntimeAdapter = true,
        artifacts = listOf(
            LocalModelArtifact(
                relativePath = "MiniCPM-V-4_6-Q4_K_M.gguf",
                sourceUrl = miniCpmV46Url("MiniCPM-V-4_6-Q4_K_M.gguf"),
                expectedMinBytes = 500_000_000L,
                md5Hex = "fd778481dd56b6036dd8f9cf7c1519cf",
                role = ArtifactRole.GGUF_MODEL
            ),
            LocalModelArtifact(
                relativePath = "mmproj-model-f16.gguf",
                sourceUrl = miniCpmV46Url("mmproj-model-f16.gguf"),
                expectedMinBytes = 1_100_000_000L,
                md5Hex = "54aea6e04d752f47309a48f12795a1a3",
                role = ArtifactRole.GGUF_MMPROJ
            )
        )
    )

    // --------------------------------------------------------------------------
    // 2. Google Gemma 4 E2B Multimodal Model (LiteRT-LM)
    // --------------------------------------------------------------------------
    const val GEMMA_4_E2B_ID = "gemma_4_e2b"
    const val GEMMA_4_CANONICAL_ID = "gemma-4-e2b-it-litert-lm"
    const val GEMMA_4_REPO = "litert-community/gemma-4-E2B-it-litert-lm"
    const val GEMMA_4_REVISION = "6e5c4f1e395deb959c494953478fa5cec4b8008f"

    private fun gemma4Url(fileName: String): String =
        "$HF_RESOLVE_BASE/$GEMMA_4_REPO/resolve/$GEMMA_4_REVISION/$fileName"

    val GEMMA_4_SPEC = LocalModelSpec(
        id = GEMMA_4_E2B_ID,
        canonicalId = GEMMA_4_CANONICAL_ID,
        legacyAliases = listOf("gemma4", "gemma_4"),
        displayName = "Gemma 4 E2B",
        upstreamRepo = GEMMA_4_REPO,
        upstreamRevision = GEMMA_4_REVISION,
        description = "Google Gemma 4 E2B multimodal vision-language model for LiteRT-LM image understanding (2.59 GB; 8 GB RAM-class device minimum).",
        taskType = ModelTaskType.VISION_LANGUAGE,
        architecture = ModelArchitecture.GEMMA4,
        license = "Apache-2.0",
        subdir = "models/gemma_4_e2b",
        manifestFileName = "gemma4_manifest.json",
        testTagPrefix = "gemma",
        hasRuntimeAdapter = true, // Gemma4RuntimeAdapter performs real LiteRT-LM image inference.
        artifacts = listOf(
            LocalModelArtifact(
                relativePath = "gemma-4-E2B-it.litertlm",
                sourceUrl = gemma4Url("gemma-4-E2B-it.litertlm"),
                exactBytes = 2_588_147_712L,
                role = ArtifactRole.LITERT_MODEL
            )
        )
    )


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
        legacyAliases = listOf("lfm2_5", "lfm2_5_vl"),
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
        legacyAliases = listOf("qwen3_5", "qwen3.5-0.8b"),
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


    val ALL_MODELS: List<LocalModelSpec> = listOf(
        GEMMA_4_SPEC,
        LFM2_5_SPEC,
        QWEN3_5_SPEC,
        MINI_CPM_V46_SPEC
    )

    fun findSpec(idOrAlias: String): LocalModelSpec? {
        val normalized = idOrAlias.trim().lowercase()
        return ALL_MODELS.firstOrNull { spec ->
            spec.id.equals(normalized, ignoreCase = true) ||
                    spec.canonicalId.equals(normalized, ignoreCase = true) ||
                    spec.legacyAliases.any { it.equals(normalized, ignoreCase = true) }
        }
    }

    private val retiredLocalModelIds = setOf(
        "gemma_3_1b_it", "gemma3_1b",
        "lfm2_350m", "lfm2_350m_extract",
        "qwen2_5_0_5b", "qwen2_5_05b"
    )

    fun isRetiredLocalModelId(id: String): Boolean =
        id.trim().lowercase() in retiredLocalModelIds

    fun getSpec(idOrAlias: String): LocalModelSpec {
        return findSpec(idOrAlias)
            ?: throw IllegalArgumentException("Unsupported local model id or alias: '" + idOrAlias + "'.")
    }
}
