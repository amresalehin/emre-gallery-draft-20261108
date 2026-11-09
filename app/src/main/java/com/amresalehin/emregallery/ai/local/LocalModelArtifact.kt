package com.amresalehin.emregallery.ai.local

/**
 * Task capabilities of local on-device models.
 */
enum class ModelTaskType(val label: String) {
    VISION_LANGUAGE("Vision-Language"),
    TEXT_GENERATION("Text Generation"),
    EMBEDDING("Text / Image Embedding"),
    IMAGE_CLASSIFICATION("Image Classification")
}

/**
 * Supported underlying model architecture families.
 */
enum class ModelArchitecture(val family: String) {
    FLORENCE2("Florence-2 Encoder-Decoder"),
    GEMMA4("Gemma 4 LiteRT-LM"),
    LFM2_5("LFM2.5-VL llama.cpp"),
    QWEN3_5("Qwen 3.5 llama.cpp"),
    GEMMA3("Gemma-3 Autoregressive Decoder"),
    LFM2("Liquid Foundation Model 2"),
    QWEN2("Qwen 2.5 Decoder")
}

/**
 * Role of each artifact file within a complete model distribution.
 */
enum class ArtifactRole {
    CONFIG,
    GENERATION_CONFIG,
    PREPROCESSOR_CONFIG,
    PROCESSOR_CONFIG,
    TOKENIZER,
    TOKENIZER_CONFIG,
    VOCAB_OR_MERGES,
    SPECIAL_TOKENS,
    ONNX_GRAPH,
    ONNX_EXTERNAL_DATA,
    LITERT_MODEL,
    GGUF_MODEL,
    GGUF_MMPROJ
}

/**
 * Declares a single required or optional artifact file within a model distribution.
 */
data class LocalModelArtifact(
    val relativePath: String,
    val sourceUrl: String,
    val exactBytes: Long? = null,
    val expectedMinBytes: Long = 10L,
    val sha256Hex: String? = null,
    val isRequired: Boolean = true,
    val role: ArtifactRole = ArtifactRole.ONNX_GRAPH,
    val externalDataFor: String? = null
)

/**
 * Typed, immutable specification for a local model in the registry.
 */
data class LocalModelSpec(
    val id: String,
    val canonicalId: String = id,
    val legacyAliases: List<String> = emptyList(),
    val displayName: String,
    val upstreamRepo: String,
    val upstreamRevision: String,
    val description: String,
    val taskType: ModelTaskType,
    val architecture: ModelArchitecture,
    val license: String,
    val subdir: String,
    val manifestFileName: String,
    val testTagPrefix: String,
    val hasRuntimeAdapter: Boolean,
    val artifacts: List<LocalModelArtifact>
) {
    val totalExpectedBytes: Long
        get() = artifacts.sumOf { it.exactBytes ?: it.expectedMinBytes }
}
