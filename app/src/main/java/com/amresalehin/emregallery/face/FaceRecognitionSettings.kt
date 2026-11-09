package com.amresalehin.emregallery.face

import android.content.Context

/** Persistent tuning controls used by the Index Status screen and face indexer. */
object FaceRecognitionSettings {
    private const val PREFS = "face_recognition_settings"
    const val KEY_DETECTION_CONFIDENCE = "detection_confidence"
    const val KEY_RECOGNITION_DISTANCE = "recognition_distance"
    // Kept for one-time migration from older app versions.
    const val KEY_SIMILARITY_THRESHOLD = "similarity_threshold"
    const val KEY_MIN_GROUP_FACES = "min_group_faces"

    const val DEFAULT_DETECTION_CONFIDENCE = 0.70f
    const val DEFAULT_RECOGNITION_DISTANCE = 0.60f
    const val DEFAULT_MIN_GROUP_FACES = 2

    const val COMPARISON_IOU_THRESHOLD = 0.40f
    const val LOW_DETECTION_CONFIDENCE_THRESHOLD = 0.35f

    const val KEY_ACTIVE_ENGINE = "active_face_engine"
    const val KEY_ENTE_DETECTION_CONFIDENCE = "ente_detection_confidence"
    const val KEY_ENTE_RECOGNITION_DISTANCE = "ente_recognition_distance"
    const val KEY_ENTE_MIN_GROUP_FACES = "ente_min_group_faces"

    fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getActiveEngine(context: Context): String {
        return preferences(context).getString(KEY_ACTIVE_ENGINE, BuffaloLFaceEngine.ENGINE_ID)
            ?: BuffaloLFaceEngine.ENGINE_ID
    }

    fun setActiveEngine(context: Context, engineId: String): Boolean {
        return FaceProcessingCoordinator.trySwitchEngine(context, engineId)
    }

    fun detectionConfidence(context: Context, engineId: String = BuffaloLFaceEngine.ENGINE_ID): Float {
        val prefs = preferences(context)
        return if (engineId == EnteFaceConstants.ENGINE_ID) {
            prefs.getFloat(KEY_ENTE_DETECTION_CONFIDENCE, EnteFaceConstants.DEFAULT_DETECTION_CONFIDENCE)
                .coerceIn(0.30f, 0.90f)
        } else {
            prefs.getFloat(KEY_DETECTION_CONFIDENCE, DEFAULT_DETECTION_CONFIDENCE).coerceIn(0.30f, 0.90f)
        }
    }

    fun setDetectionConfidence(context: Context, engineId: String, value: Float) {
        val rounded = (value.coerceIn(0.30f, 0.90f) * 100f).let { kotlin.math.round(it) / 100f }
        val key = if (engineId == EnteFaceConstants.ENGINE_ID) KEY_ENTE_DETECTION_CONFIDENCE else KEY_DETECTION_CONFIDENCE
        preferences(context).edit().putFloat(key, rounded).apply()
    }

    fun recognitionDistance(context: Context, engineId: String = BuffaloLFaceEngine.ENGINE_ID): Float {
        val prefs = preferences(context)
        if (engineId == EnteFaceConstants.ENGINE_ID) {
            return prefs.getFloat(KEY_ENTE_RECOGNITION_DISTANCE, EnteFaceConstants.DEFAULT_RECOGNITION_DISTANCE)
                .coerceIn(0.25f, 0.75f)
        }
        if (prefs.contains(KEY_RECOGNITION_DISTANCE)) {
            return prefs.getFloat(KEY_RECOGNITION_DISTANCE, DEFAULT_RECOGNITION_DISTANCE).coerceIn(0.25f, 0.75f)
        }
        // Older versions stored a minimum cosine similarity. Convert it to cosine distance.
        val migrated = if (prefs.contains(KEY_SIMILARITY_THRESHOLD)) {
            (1f - prefs.getFloat(KEY_SIMILARITY_THRESHOLD, 0.40f)).coerceIn(0.25f, 0.75f)
        } else DEFAULT_RECOGNITION_DISTANCE
        prefs.edit().putFloat(KEY_RECOGNITION_DISTANCE, migrated).apply()
        return migrated
    }

    fun setRecognitionDistance(context: Context, engineId: String, value: Float) {
        val rounded = (value.coerceIn(0.25f, 0.75f) * 100f).let { kotlin.math.round(it) / 100f }
        val key = if (engineId == EnteFaceConstants.ENGINE_ID) KEY_ENTE_RECOGNITION_DISTANCE else KEY_RECOGNITION_DISTANCE
        preferences(context).edit().putFloat(key, rounded).apply()
    }

    fun minGroupFaces(context: Context, engineId: String = BuffaloLFaceEngine.ENGINE_ID): Int {
        val prefs = preferences(context)
        return if (engineId == EnteFaceConstants.ENGINE_ID) {
            prefs.getInt(KEY_ENTE_MIN_GROUP_FACES, DEFAULT_MIN_GROUP_FACES).coerceIn(1, 30)
        } else {
            prefs.getInt(KEY_MIN_GROUP_FACES, DEFAULT_MIN_GROUP_FACES).coerceIn(1, 30)
        }
    }

    fun setMinGroupFaces(context: Context, engineId: String, value: Int) {
        val coerced = value.coerceIn(1, 30)
        val key = if (engineId == EnteFaceConstants.ENGINE_ID) KEY_ENTE_MIN_GROUP_FACES else KEY_MIN_GROUP_FACES
        preferences(context).edit().putInt(key, coerced).apply()
    }

    const val KEY_CPU_THROTTLE_MODE = "cpu_throttle_mode"
    const val THROTTLE_UNCONSTRAINED = "unconstrained"
    const val THROTTLE_TURBO = "turbo"
    const val THROTTLE_BALANCED = "balanced"
    const val THROTTLE_ECO = "eco"

    const val KEY_INDEX_ALL_FOLDERS = "face_index_all_folders"
    const val KEY_SELECTED_FOLDERS = "face_index_selected_folders"

    fun isIndexAllFolders(context: Context): Boolean {
        return preferences(context).getBoolean(KEY_INDEX_ALL_FOLDERS, true)
    }

    fun setIndexAllFolders(context: Context, all: Boolean) {
        preferences(context).edit().putBoolean(KEY_INDEX_ALL_FOLDERS, all).apply()
    }

    fun getSelectedFolders(context: Context): Set<String> {
        val set = preferences(context).getStringSet(KEY_SELECTED_FOLDERS, null)
        return if (set != null) HashSet(set) else emptySet()
    }

    fun setSelectedFolders(context: Context, folders: Set<String>) {
        preferences(context).edit().putStringSet(KEY_SELECTED_FOLDERS, HashSet(folders)).apply()
    }

    fun normalizeFolderPath(relativePath: String): String {
        return relativePath.trim().trim('/').ifBlank { "Internal storage" }
    }

    fun isMediaInFaceIndexingFolders(context: Context, relativePath: String): Boolean {
        if (isIndexAllFolders(context)) return true
        val selected = getSelectedFolders(context)
        if (selected.isEmpty()) return true
        return selected.contains(normalizeFolderPath(relativePath))
    }

    fun <T> filterMediaForFaceIndexing(
        context: Context,
        media: List<T>,
        pathSelector: (T) -> String
    ): List<T> {
        if (isIndexAllFolders(context)) return media
        val selected = getSelectedFolders(context)
        if (selected.isEmpty()) return media
        return media.filter { item ->
            val folder = normalizeFolderPath(pathSelector(item))
            selected.contains(folder)
        }
    }

    fun cpuThrottleMode(context: Context): String {
        return preferences(context).getString(KEY_CPU_THROTTLE_MODE, THROTTLE_UNCONSTRAINED) ?: THROTTLE_UNCONSTRAINED
    }

    fun setCpuThrottleMode(context: Context, mode: String) {
        preferences(context).edit().putString(KEY_CPU_THROTTLE_MODE, mode).apply()
    }

    /**
     * Emre never limits itself when Android is not limiting it.
     * Default unconstrained mode operates with 0ms artificial cooldown for maximum continuous throughput.
     */
    fun cpuThrottleCooldownMillis(context: Context): Long {
        return when (cpuThrottleMode(context)) {
            THROTTLE_ECO -> 40L
            THROTTLE_BALANCED -> 10L
            THROTTLE_TURBO -> 0L
            THROTTLE_UNCONSTRAINED -> 0L
            else -> 0L
        }
    }
}
