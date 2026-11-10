package com.amresalehin.emregallery.ai.tagging

import android.content.Context
import com.amresalehin.emregallery.face.FaceRecognitionSettings
import com.amresalehin.emregallery.ai.local.LocalModelRegistry

/**
 * Persistent preferences for AI image tagging:
 * - Active model selection (MiniCPM-V 4.6, Gemma 4, or Cloud models)
 * - Folder scoping (all folders or selective folder filtering)
 */
object AiTaggingSettings {
    private const val PREFS = "ai_tagging_settings"
    const val KEY_SELECTED_MODEL = "selected_model"
    const val KEY_ALL_FOLDERS = "all_folders"
    const val KEY_SELECTED_FOLDERS = "selected_folders"

    const val MODEL_MINI_CPM_V46 = "minicpm_v_4_6"
    const val MODEL_GEMMA_4 = "gemma_4_e2b"
    const val MODEL_LFM_2_5 = "lfm2_5_vl_450m_extract"
    const val MODEL_QWEN_3_5 = "qwen3_5_0_8b"
    const val MODEL_CLOUD = "cloud"

    fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getSelectedModel(context: Context): String {
        val saved = preferences(context).getString(KEY_SELECTED_MODEL, null)
        val previousFlorenceSelection = saved in setOf(
            "florence2", "florence-2", "florence-2-base", "florence2_base"
        )
        if (saved.isNullOrBlank() || saved == "smart_vision" || previousFlorenceSelection) {
            preferences(context).edit().putString(KEY_SELECTED_MODEL, MODEL_MINI_CPM_V46).apply()
            return MODEL_MINI_CPM_V46
        }

        val localSpec = LocalModelRegistry.findSpec(saved)
        val migrateUnsupportedLocal = (localSpec != null && !localSpec.hasRuntimeAdapter) ||
            LocalModelRegistry.isRetiredLocalModelId(saved)
        if (migrateUnsupportedLocal) {
            preferences(context).edit().putString(KEY_SELECTED_MODEL, MODEL_GEMMA_4).apply()
            return MODEL_GEMMA_4
        }
        return localSpec?.id ?: saved
    }

    fun setSelectedModel(context: Context, modelId: String) {
        preferences(context).edit().putString(KEY_SELECTED_MODEL, modelId).apply()
    }

    fun isAllFolders(context: Context): Boolean {
        return preferences(context).getBoolean(KEY_ALL_FOLDERS, true)
    }

    fun setAllFolders(context: Context, all: Boolean) {
        preferences(context).edit().putBoolean(KEY_ALL_FOLDERS, all).apply()
    }

    fun getSelectedFolders(context: Context): Set<String> {
        val set = preferences(context).getStringSet(KEY_SELECTED_FOLDERS, null)
        return if (set != null) HashSet(set) else emptySet()
    }

    fun setSelectedFolders(context: Context, folders: Set<String>) {
        preferences(context).edit().putStringSet(KEY_SELECTED_FOLDERS, HashSet(folders)).apply()
    }

    fun toggleFolder(context: Context, folderPath: String): Set<String> {
        val current = getSelectedFolders(context).toMutableSet()
        if (isAllFolders(context)) {
            setAllFolders(context, false)
            current.clear()
            current.add(folderPath)
        } else {
            if (current.contains(folderPath)) {
                current.remove(folderPath)
            } else {
                current.add(folderPath)
            }
            if (current.isEmpty()) {
                setAllFolders(context, true)
            }
        }
        setSelectedFolders(context, current)
        return current
    }

    fun selectAllFolders(context: Context) {
        setAllFolders(context, true)
        setSelectedFolders(context, emptySet())
    }

    fun isMediaInTaggingFolders(context: Context, relativePath: String): Boolean {
        if (isAllFolders(context)) return true
        val selected = getSelectedFolders(context)
        if (selected.isEmpty()) return true
        return selected.contains(FaceRecognitionSettings.normalizeFolderPath(relativePath))
    }

    fun <T> filterMediaForTagging(
        context: Context,
        media: List<T>,
        pathSelector: (T) -> String
    ): List<T> {
        if (isAllFolders(context)) return media
        val selected = getSelectedFolders(context)
        if (selected.isEmpty()) return media
        return media.filter { item ->
            val folder = FaceRecognitionSettings.normalizeFolderPath(pathSelector(item))
            selected.contains(folder)
        }
    }
}
