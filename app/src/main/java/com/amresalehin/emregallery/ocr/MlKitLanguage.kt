package com.amresalehin.emregallery.ocr

/**
 * Supported language scripts for Google ML Kit Text Recognition v2.
 * Google ML Kit uses dedicated on-device neural models for Latin, Chinese,
 * Devanagari, Japanese, and Korean scripts. Non-Latin models also support
 * embedded Latin characters.
 */
enum class MlKitLanguage(
    val code: String,
    val title: String,
    val nativeScript: String,
    val sampleText: String,
    val description: String,
    val coveredLanguages: List<String>
) {
    LATIN(
        code = "latin",
        title = "Latin",
        nativeScript = "English, Español, Français...",
        sampleText = "Aa Bb 123",
        description = "Latin-script languages including English, Spanish, French, German, Italian, Portuguese, Turkish, Polish, Dutch, Vietnamese, Indonesian, etc.",
        coveredLanguages = listOf("English", "Spanish", "French", "German", "Italian", "Portuguese", "Turkish", "Polish", "Dutch", "Vietnamese", "Indonesian")
    ),
    CHINESE(
        code = "chinese",
        title = "Chinese",
        nativeScript = "中文 (简体 / 繁體)",
        sampleText = "文字识别",
        description = "Simplified and Traditional Chinese script models with automatic Latin character recognition.",
        coveredLanguages = listOf("Simplified Chinese (简体中文)", "Traditional Chinese (繁體中文)")
    ),
    DEVANAGARI(
        code = "devanagari",
        title = "Devanagari",
        nativeScript = "देवनागरी (हिन्दी / मराठी)",
        sampleText = "पाठ पहचान",
        description = "Major South Asian languages written in Devanagari script with Latin numeral and letter recognition.",
        coveredLanguages = listOf("Hindi (हिन्दी)", "Marathi (मराठी)", "Nepali (नेपाली)", "Sanskrit (संस्कृत)", "Bhojpuri", "Konkani")
    ),
    JAPANESE(
        code = "japanese",
        title = "Japanese",
        nativeScript = "日本語 (漢字・平仮名・片仮名)",
        sampleText = "文字認識",
        description = "Japanese Kanji, Hiragana, and Katakana scripts with embedded Latin and numeral recognition.",
        coveredLanguages = listOf("Japanese (日本語)")
    ),
    KOREAN(
        code = "korean",
        title = "Korean",
        nativeScript = "한국어 (한글 / 漢字)",
        sampleText = "텍스트 인식",
        description = "Korean Hangul and Hanja scripts with embedded Latin and numeral recognition.",
        coveredLanguages = listOf("Korean (한국어)")
    );

    companion object {
        val DEFAULT_SELECTION: Set<String> = setOf(LATIN.code)
        val ALL_CODES: Set<String> = entries.map { it.code }.toSet()

        fun fromCode(code: String?): MlKitLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: LATIN

        fun resolveSelection(codes: Collection<String>?): List<MlKitLanguage> {
            if (codes.isNullOrEmpty()) return listOf(LATIN)
            val matched = codes.mapNotNull { code -> entries.firstOrNull { it.code.equals(code, ignoreCase = true) } }
            return matched.ifEmpty { listOf(LATIN) }
        }

        fun getDisplayLabel(codes: Collection<String>?): String {
            val resolved = resolveSelection(codes)
            return when {
                resolved.size == entries.size -> "All Scripts (${entries.size})"
                resolved.size == 1 -> resolved.first().title
                else -> "${resolved.first().title} (+${resolved.size - 1})"
            }
        }
    }
}
