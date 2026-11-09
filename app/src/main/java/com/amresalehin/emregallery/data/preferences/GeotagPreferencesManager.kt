package com.amresalehin.emregallery.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
 */
class GeotagPreferencesManager(private val context: Context) {

    private val writeToExifKey = booleanPreferencesKey("geotag_write_to_exif")
    private val maxInterpolationGapKey = intPreferencesKey("geotag_max_interpolation_gap_minutes")
    private val nearestPointToleranceKey = intPreferencesKey("geotag_nearest_point_tolerance_minutes")
    private val skipExistingGpsKey = booleanPreferencesKey("geotag_skip_existing_gps")

    private val sharedPrefs = context.getSharedPreferences("geotag_prefs", Context.MODE_PRIVATE)

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
}
