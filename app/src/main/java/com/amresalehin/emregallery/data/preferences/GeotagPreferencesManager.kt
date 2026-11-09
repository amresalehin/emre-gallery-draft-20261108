package com.amresalehin.emregallery.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.amresalehin.emregallery.data.galleryDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Centralized preferences manager for Timeline Geotagging and GPS safety defaults.
 *
 * Safety defaults per specification:
 * - writeToExif: false (never write to original files by default)
 * - maxInterpolationGapMinutes: 30 min
 * - nearestPointToleranceMinutes: 15 min
 * - skipExistingGps: true
 * - isAllFolders: true (by default keep Geotimeline selected to All Folders)
 * - selectedFolders: emptySet()
 */
class GeotagPreferencesManager(private val context: Context) {

    private val writeToExifKey = booleanPreferencesKey("geotag_write_to_exif")
    private val maxInterpolationGapKey = intPreferencesKey("geotag_max_interpolation_gap_minutes")
    private val maxInterpolationDistanceKmKey = androidx.datastore.preferences.core.floatPreferencesKey("geotag_max_interpolation_distance_km")
    private val nearestPointToleranceKey = intPreferencesKey("geotag_nearest_point_tolerance_minutes")
    private val skipExistingGpsKey = booleanPreferencesKey("geotag_skip_existing_gps")
    private val isAllFoldersKey = booleanPreferencesKey("geotag_is_all_folders")
    private val selectedFoldersKey = stringSetPreferencesKey("geotag_selected_folders")

    private val sharedPrefs = context.getSharedPreferences("geotag_prefs", Context.MODE_PRIVATE)

    fun getMaxInterpolationDistanceKmSync(): Float =
        sharedPrefs.getFloat("max_distance_km", 2.0f)

    val maxInterpolationDistanceKm: Flow<Float> = context.galleryDataStore.data.map {
        it[maxInterpolationDistanceKmKey] ?: getMaxInterpolationDistanceKmSync()
    }.distinctUntilChanged()

    suspend fun setMaxInterpolationDistanceKm(distanceKm: Float) {
        val clamped = distanceKm.coerceIn(0.5f, 5.0f)
        sharedPrefs.edit().putFloat("max_distance_km", clamped).apply()
        context.galleryDataStore.edit { prefs ->
            prefs[maxInterpolationDistanceKmKey] = clamped
        }
    }

    fun isWriteToExifSync(): Boolean =
        sharedPrefs.getBoolean("write_to_exif", false)

    val writeToExif: Flow<Boolean> = context.galleryDataStore.data.map {
        it[writeToExifKey] ?: isWriteToExifSync()
    }.distinctUntilChanged()

    suspend fun setWriteToExif(value: Boolean) {
        sharedPrefs.edit().putBoolean("write_to_exif", value).apply()
        context.galleryDataStore.edit { prefs ->
            prefs[writeToExifKey] = value
        }
    }

    fun getMaxInterpolationGapMinutesSync(): Int =
        sharedPrefs.getInt("max_gap_minutes", 30)

    val maxInterpolationGapMinutes: Flow<Int> = context.galleryDataStore.data.map {
        it[maxInterpolationGapKey] ?: getMaxInterpolationGapMinutesSync()
    }.distinctUntilChanged()

    suspend fun setMaxInterpolationGapMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(1, 60)
        sharedPrefs.edit().putInt("max_gap_minutes", clamped).apply()
        context.galleryDataStore.edit { prefs ->
            prefs[maxInterpolationGapKey] = clamped
        }
    }

    fun getNearestPointToleranceMinutesSync(): Int =
        sharedPrefs.getInt("tolerance_minutes", 15)

    val nearestPointToleranceMinutes: Flow<Int> = context.galleryDataStore.data.map {
        it[nearestPointToleranceKey] ?: getNearestPointToleranceMinutesSync()
    }.distinctUntilChanged()

    suspend fun setNearestPointToleranceMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(1, 120)
        sharedPrefs.edit().putInt("tolerance_minutes", clamped).apply()
        context.galleryDataStore.edit { prefs ->
            prefs[nearestPointToleranceKey] = clamped
        }
    }

    fun isSkipExistingGpsSync(): Boolean =
        sharedPrefs.getBoolean("skip_existing_gps", true)

    val skipExistingGps: Flow<Boolean> = context.galleryDataStore.data.map {
        it[skipExistingGpsKey] ?: isSkipExistingGpsSync()
    }.distinctUntilChanged()

    suspend fun setSkipExistingGps(value: Boolean) {
        sharedPrefs.edit().putBoolean("skip_existing_gps", value).apply()
        context.galleryDataStore.edit { prefs ->
            prefs[skipExistingGpsKey] = value
        }
    }

    fun isAllFoldersSync(): Boolean =
        sharedPrefs.getBoolean("is_all_folders", true)

    val isAllFolders: Flow<Boolean> = context.galleryDataStore.data.map {
        it[isAllFoldersKey] ?: isAllFoldersSync()
    }.distinctUntilChanged()

    suspend fun setAllFolders(value: Boolean) {
        sharedPrefs.edit().putBoolean("is_all_folders", value).apply()
        context.galleryDataStore.edit { prefs ->
            prefs[isAllFoldersKey] = value
        }
    }

    fun getSelectedFoldersSync(): Set<String> =
        sharedPrefs.getStringSet("selected_folders", null) ?: emptySet()

    val selectedFolders: Flow<Set<String>> = context.galleryDataStore.data.map {
        it[selectedFoldersKey] ?: getSelectedFoldersSync()
    }.distinctUntilChanged()

    suspend fun setSelectedFolders(folders: Set<String>) {
        sharedPrefs.edit().putStringSet("selected_folders", HashSet(folders)).apply()
        context.galleryDataStore.edit { prefs ->
            prefs[selectedFoldersKey] = folders
        }
    }

    fun getSavedTimelineFileName(): String? =
        sharedPrefs.getString("saved_timeline_file_name", null)

    fun setSavedTimelineFileName(name: String?) {
        if (name == null) {
            sharedPrefs.edit().remove("saved_timeline_file_name").apply()
        } else {
            sharedPrefs.edit().putString("saved_timeline_file_name", name).apply()
        }
    }
}
