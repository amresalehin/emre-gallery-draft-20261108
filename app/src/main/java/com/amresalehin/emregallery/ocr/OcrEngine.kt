package com.amresalehin.emregallery.ocr

enum class OcrEngine(
    val id: String,
    val title: String,
    val shortName: String,
    val description: String
) {
    TESSERACT(
        id = "tesseract",
        title = "Tesseract",
        shortName = "Tesseract",
        description = "Open-source LSTM OCR engine with custom models (Fast/Best) and downloadable language packs."
    ),
    GOOGLE_ML_KIT(
        id = "google_mlkit",
        title = "Google ML Kit",
        shortName = "ML Kit",
        description = "On-device neural text recognition by Google. Fast, offline multi-language support (Latin, Chinese, Devanagari, Japanese, Korean)."
    );

    companion object {
        fun fromId(id: String?): OcrEngine =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: TESSERACT
    }
}
