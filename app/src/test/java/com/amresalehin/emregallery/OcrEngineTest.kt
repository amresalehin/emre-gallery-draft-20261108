package com.amresalehin.emregallery

import com.amresalehin.emregallery.ocr.MlKitLanguage
import com.amresalehin.emregallery.ocr.MlKitOcr
import com.amresalehin.emregallery.ocr.OcrEngine
import com.amresalehin.emregallery.ocr.TesseractOcr
import com.amresalehin.emregallery.ui.OcrState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrEngineTest {

    @Test
    fun testOcrEngineDefaultsAndFromId() {
        assertEquals(OcrEngine.TESSERACT, OcrEngine.fromId("tesseract"))
        assertEquals(OcrEngine.TESSERACT, OcrEngine.fromId("TESSERACT"))
        assertEquals(OcrEngine.GOOGLE_ML_KIT, OcrEngine.fromId("google_mlkit"))
        assertEquals(OcrEngine.GOOGLE_ML_KIT, OcrEngine.fromId("GOOGLE_MLKIT"))
        // Unknown defaults to Tesseract
        assertEquals(OcrEngine.TESSERACT, OcrEngine.fromId("unknown_engine"))
        assertEquals(OcrEngine.TESSERACT, OcrEngine.fromId(null))
    }

    @Test
    fun testOcrStateEngineField() {
        val defaultState = OcrState()
        assertEquals("tesseract", defaultState.engine)
        assertEquals(setOf("latin"), defaultState.mlKitLanguages)

        val mlKitState = defaultState.copy(
            engine = "google_mlkit",
            mlKitLanguages = setOf("latin", "japanese", "chinese")
        )
        assertEquals("google_mlkit", mlKitState.engine)
        assertTrue(mlKitState.engine.equals("google_mlkit", ignoreCase = true))
        assertEquals(3, mlKitState.mlKitLanguages.size)
        assertTrue(mlKitState.mlKitLanguages.contains("japanese"))
    }

    @Test
    fun testEngineProperties() {
        assertEquals("Google ML Kit", OcrEngine.GOOGLE_ML_KIT.title)
        assertEquals("ML Kit", OcrEngine.GOOGLE_ML_KIT.shortName)
        assertTrue(OcrEngine.GOOGLE_ML_KIT.description.contains("on-device", ignoreCase = true))
        assertTrue(OcrEngine.GOOGLE_ML_KIT.description.contains("multi-language", ignoreCase = true))

        assertEquals("Tesseract", OcrEngine.TESSERACT.title)
        assertEquals("Tesseract", OcrEngine.TESSERACT.shortName)
    }

    @Test
    fun testMlKitLanguageScripts() {
        assertEquals(5, MlKitLanguage.entries.size)
        assertTrue(MlKitLanguage.ALL_CODES.containsAll(listOf("latin", "chinese", "devanagari", "japanese", "korean")))

        assertEquals(MlKitLanguage.LATIN, MlKitLanguage.fromCode("latin"))
        assertEquals(MlKitLanguage.CHINESE, MlKitLanguage.fromCode("CHINESE"))
        assertEquals(MlKitLanguage.DEVANAGARI, MlKitLanguage.fromCode("devanagari"))
        assertEquals(MlKitLanguage.JAPANESE, MlKitLanguage.fromCode("Japanese"))
        assertEquals(MlKitLanguage.KOREAN, MlKitLanguage.fromCode("korean"))
        assertEquals(MlKitLanguage.LATIN, MlKitLanguage.fromCode("invalid_code"))
    }

    @Test
    fun testMlKitLanguageSelectionAndLabels() {
        val defaultResolved = MlKitLanguage.resolveSelection(null)
        assertEquals(1, defaultResolved.size)
        assertEquals(MlKitLanguage.LATIN, defaultResolved.first())

        val multiResolved = MlKitLanguage.resolveSelection(listOf("japanese", "chinese"))
        assertEquals(2, multiResolved.size)
        assertTrue(multiResolved.contains(MlKitLanguage.JAPANESE))
        assertTrue(multiResolved.contains(MlKitLanguage.CHINESE))

        assertEquals("Latin", MlKitLanguage.getDisplayLabel(listOf("latin")))
        assertEquals("Japanese (+1)", MlKitLanguage.getDisplayLabel(listOf("japanese", "korean")))
        assertEquals("All Scripts (5)", MlKitLanguage.getDisplayLabel(MlKitLanguage.ALL_CODES))
    }

    @Test
    fun testMlKitMergeRecognizedTexts() {
        assertEquals("", MlKitOcr.mergeRecognizedTexts(emptyList()))
        assertEquals("Single Text Line", MlKitOcr.mergeRecognizedTexts(listOf("Single Text Line")))

        val latinText = "Coffee Shop\nOpen 08:00 - 20:00"
        val japaneseText = "コーヒーショップ\nOpen 08:00 - 20:00\n営業時間"

        val merged = MlKitOcr.mergeRecognizedTexts(listOf(latinText, japaneseText))
        val lines = merged.split('\n')

        assertTrue(lines.contains("Coffee Shop"))
        assertTrue(lines.contains("Open 08:00 - 20:00"))
        assertTrue(lines.contains("コーヒーショップ"))
        assertTrue(lines.contains("営業時間"))
        // Deduplicated: "Open 08:00 - 20:00" appears once
        assertEquals(1, lines.count { it == "Open 08:00 - 20:00" })
    }

    @Test
    fun testTesseractSupportedLanguages() {
        val supported = TesseractOcr.supportedLanguages
        assertTrue(supported.size >= 12)
        assertTrue(supported.containsKey("English"))
        assertTrue(supported.containsKey("Spanish"))
        assertTrue(supported.containsKey("French"))
        assertTrue(supported.containsKey("German"))
        assertTrue(supported.containsKey("Hindi"))
        assertTrue(supported.containsKey("Bengali"))
        assertTrue(supported.containsKey("Arabic"))
        assertTrue(supported.containsKey("Urdu"))
        assertTrue(supported.containsKey("Portuguese"))
        assertTrue(supported.containsKey("Russian"))
        assertTrue(supported.containsKey("Chinese (Simplified)"))
        assertTrue(supported.containsKey("Japanese"))

        // Ensure language codes map properly
        assertEquals("eng", supported["English"])
        assertEquals("spa", supported["Spanish"])
        assertEquals("fra", supported["French"])
        assertEquals("deu", supported["German"])
    }
}
