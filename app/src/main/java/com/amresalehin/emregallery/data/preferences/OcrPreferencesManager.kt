package com.amresalehin.emregallery.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.amresalehin.emregallery.data.galleryDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Manages OCR language selections, OCR engine toggles, and model IDs.
 */
class OcrPreferencesManager(private val context: Context) {

    private val ocrLanguagesKey = stringSetPreferencesKey("ocr_languages")
    private val mlKitLanguagesKey = stringSetPreferencesKey("mlkit_languages")
    private val ocrModelKey = stringPreferencesKey("ocr_model")
    private val ocrEngineKey = stringPreferencesKey("ocr_engine")

    val ocrLanguages: Flow<Set<String>> = context.galleryDataStore.data.map {
        it[ocrLanguagesKey] ?: setOf("eng")
    }.distinctUntilChanged()

    val mlKitLanguages: Flow<Set<String>> = context.galleryDataStore.data.map {
        it[mlKitLanguagesKey] ?: setOf("latin")
    }.distinctUntilChanged()

    val ocrModel: Flow<String> = context.galleryDataStore.data.map {
        it[ocrModelKey] ?: "fast"
    }.distinctUntilChanged()

    val ocrEngine: Flow<String> = context.galleryDataStore.data.map {
        it[ocrEngineKey] ?: "tesseract"
    }.distinctUntilChanged()

    suspend fun setOcrLanguages(languages: Set<String>) {
        context.galleryDataStore.edit { it[ocrLanguagesKey] = languages }
    }

    suspend fun setMlKitLanguages(languages: Set<String>) {
        context.galleryDataStore.edit { it[mlKitLanguagesKey] = languages }
    }

    suspend fun setOcrModel(modelId: String) {
        context.galleryDataStore.edit { it[ocrModelKey] = modelId }
    }

    suspend fun setOcrEngine(engine: String) {
        context.galleryDataStore.edit { it[ocrEngineKey] = engine }
    }
}
