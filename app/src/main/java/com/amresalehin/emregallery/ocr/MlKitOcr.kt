package com.amresalehin.emregallery.ocr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.amresalehin.emregallery.face.FaceImageDecoder
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * On-device Optical Character Recognition powered by Google ML Kit Text Recognition v2.
 * Fully offline, runs on-device neural text recognition supporting multiple scripts:
 * - Latin (English, Spanish, French, German, Italian, Portuguese, Turkish, Polish, Dutch, etc.)
 * - Chinese (Simplified & Traditional)
 * - Devanagari (Hindi, Marathi, Nepali, Sanskrit, etc.)
 * - Japanese (Kanji, Hiragana, Katakana)
 * - Korean (Hangul & Hanja)
 */
object MlKitOcr {

    private val recognizerCache = ConcurrentHashMap<MlKitLanguage, TextRecognizer>()

    /**
     * Obtains or lazily creates a cached [TextRecognizer] client for the specified [language] script.
     */
    fun getRecognizer(language: MlKitLanguage): TextRecognizer {
        return recognizerCache.computeIfAbsent(language) { lang ->
            when (lang) {
                MlKitLanguage.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                MlKitLanguage.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                MlKitLanguage.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
                MlKitLanguage.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                MlKitLanguage.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            }
        }
    }

    /**
     * Recognizes text from a media item at [uri] across one or more [languageCodes].
     * Tries direct URI stream via ML Kit InputImage, and falls back to oriented bitmap decoder
     * if direct stream access fails.
     */
    fun recognize(
        context: Context,
        uri: Uri,
        languageCodes: Collection<String> = listOf("latin")
    ): String {
        val selectedLanguages = MlKitLanguage.resolveSelection(languageCodes)
        var tempBitmap: Bitmap? = null
        try {
            val inputImage = try {
                InputImage.fromFilePath(context, uri)
            } catch (_: Exception) {
                val decoded = FaceImageDecoder.decodeOrientedImage(context, uri, maxDimension = 2200)
                    ?: error("Could not decode image at URI: $uri")
                tempBitmap = decoded.bitmap
                InputImage.fromBitmap(decoded.bitmap, 0)
            }

            return recognizeImage(inputImage, selectedLanguages)
        } finally {
            tempBitmap?.recycle()
        }
    }

    /**
     * Recognizes text from an in-memory [bitmap] across one or more [languageCodes].
     */
    fun recognize(
        bitmap: Bitmap,
        rotationDegrees: Int = 0,
        languageCodes: Collection<String> = listOf("latin")
    ): String {
        val selectedLanguages = MlKitLanguage.resolveSelection(languageCodes)
        val inputImage = InputImage.fromBitmap(bitmap, rotationDegrees)
        return recognizeImage(inputImage, selectedLanguages)
    }

    /**
     * Processes [inputImage] through all [languages] and cleanly merges recognized text blocks.
     */
    private fun recognizeImage(
        inputImage: InputImage,
        languages: List<MlKitLanguage>
    ): String {
        if (languages.isEmpty()) return ""

        // Fast path for single language selection
        if (languages.size == 1) {
            val recognizer = getRecognizer(languages.first())
            val task = recognizer.process(inputImage)
            val result = Tasks.await(task, 45, TimeUnit.SECONDS)
            return result.text.trim()
        }

        // Multi-language recognition across selected scripts
        val extractedTexts = mutableListOf<String>()
        for (lang in languages) {
            try {
                val recognizer = getRecognizer(lang)
                val task = recognizer.process(inputImage)
                val result = Tasks.await(task, 45, TimeUnit.SECONDS)
                val recognized = result.text.trim()
                if (recognized.isNotEmpty()) {
                    extractedTexts.add(recognized)
                }
            } catch (_: Exception) {
                // If a single script fails or times out, continue with the remaining scripts
            }
        }

        return mergeRecognizedTexts(extractedTexts)
    }

    /**
     * Intelligently merges lines from multiple script models, avoiding identical duplicates
     * and redundant sub-lines while preserving character order and formatting.
     */
    fun mergeRecognizedTexts(texts: List<String>): String {
        if (texts.isEmpty()) return ""
        if (texts.size == 1) return texts.first()

        val uniqueLines = mutableListOf<String>()
        val seenNormalized = mutableSetOf<String>()

        for (text in texts) {
            val lines = text.split('\n')
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue

                // Normalize for deduplication (strip extra spacing)
                val normalized = line.replace("\\s+".toRegex(), " ").lowercase()
                if (normalized !in seenNormalized) {
                    // Check if already covered by an existing line or vice versa
                    val existingIndex = uniqueLines.indexOfFirst {
                        it.contains(line, ignoreCase = true)
                    }
                    if (existingIndex == -1) {
                        seenNormalized.add(normalized)
                        uniqueLines.add(line)
                    }
                }
            }
        }

        return uniqueLines.joinToString("\n")
    }

    /**
     * Releases cached recognizer resources.
     */
    fun close() {
        recognizerCache.values.forEach { recognizer ->
            runCatching { recognizer.close() }
        }
        recognizerCache.clear()
    }
}
