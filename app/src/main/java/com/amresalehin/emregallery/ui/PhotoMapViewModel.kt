package com.amresalehin.emregallery.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amresalehin.emregallery.data.MediaItem
import com.amresalehin.emregallery.data.PhotoMapLocationCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import android.content.Context
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * ViewModel managing the photo GPS indexing, location cache, and UI state for the Photo Map screen.
 */
class PhotoMapViewModel(application: Application) : AndroidViewModel(application) {

    private val locationCache = PhotoMapLocationCache(application.applicationContext)
    private val placeNameCache = ConcurrentHashMap<String, String>()
    private var indexingJob: Job? = null

    private val _uiState = MutableStateFlow(PhotoMapUiState())
    val uiState: StateFlow<PhotoMapUiState> = _uiState.asStateFlow()

    suspend fun getLocationForMedia(context: Context, item: MediaItem): GeoPoint? {
        return withContext(Dispatchers.IO) {
            val cached = locationCache.loadAll()[item.uri]?.point
            if (cached != null) return@withContext cached
            val extracted = PhotoMapLocationCache.extractLocation(context, item.uri, isVideo = item.isVideo)
            if (extracted != null) {
                locationCache.save(item.uri, extracted)
            }
            extracted
        }
    }

    /**
     * Resolves a human-readable place name for a selected map coordinate.
     *
     * The platform Geocoder call is synchronous on older Android APIs, so keep it
     * off the main thread. Use the application context because this operation does
     * not depend on a screen or Activity lifecycle.
     */
    suspend fun getPlaceName(point: GeoPoint): String? = withContext(Dispatchers.IO) {
        val cacheKey = String.format(Locale.US, "%.4f,%.4f", point.latitude, point.longitude)
        placeNameCache[cacheKey]?.let { return@withContext it }

        val resolved = getPlaceDisplayName(getApplication(), point.latitude, point.longitude)
            ?: reverseGeocodeLocation(getApplication(), point.latitude, point.longitude)
            ?: String.format(Locale.US, "%.3f°, %.3f°", point.latitude, point.longitude)
        placeNameCache[cacheKey] = resolved
        resolved
    }

    fun loadAndScanMedia(media: List<MediaItem>, forceRescan: Boolean = false) {
        indexingJob?.cancel()
        indexingJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    loading = true,
                    scanProgress = 0,
                    scanTotal = 0
                )
            }

            if (forceRescan) {
                withContext(Dispatchers.IO) { locationCache.clearAll() }
            }

            val items = media
            val cached = withContext(Dispatchers.IO) { locationCache.loadAll() }
            val cachedLocated = items.mapNotNull { item ->
                cached[item.uri]?.point?.let { item to it }
            }

            val uncachedItems = if (forceRescan) items else items.filterNot { cached.containsKey(it.uri) }
            _uiState.update {
                it.copy(
                    locatedMedia = cachedLocated,
                    scanTotal = uncachedItems.size,
                    loading = uncachedItems.isNotEmpty()
                )
            }

            if (uncachedItems.isEmpty()) {
                _uiState.update { it.copy(loading = false) }
                return@launch
            }

            val context = getApplication<Application>()
            val result = cachedLocated.toMutableList()
            var scanned = 0

            for (item in uncachedItems) {
                if (!isActive) break

                val point = withContext(Dispatchers.IO) {
                    PhotoMapLocationCache.extractLocation(context, item.uri, isVideo = item.isVideo)
                }

                withContext(Dispatchers.IO) {
                    locationCache.save(item.uri, point)
                }

                if (point != null) {
                    result.add(item to point)
                }
                scanned++

                // Avoid republishing the growing location list too frequently: each update
                // invalidates the heatmap cache and recomposes the viewport grid.
                if (scanned % 128 == 0 || scanned == uncachedItems.size) {
                    val currentList = result.toList()
                    val currentScanned = scanned
                    _uiState.update {
                        it.copy(
                            locatedMedia = currentList,
                            scanProgress = currentScanned
                        )
                    }
                    yield()
                }
            }

            _uiState.update {
                it.copy(
                    locatedMedia = result.toList(),
                    scanProgress = uncachedItems.size,
                    loading = false
                )
            }
        }
    }

    fun rescanMedia(media: List<MediaItem>) {
        loadAndScanMedia(media, forceRescan = true)
    }

    fun updateVisibleMapBounds(bounds: BoundingBox?) {
        _uiState.update { it.copy(visibleBounds = bounds) }
    }

    fun setMapLayer(layer: String) {
        _uiState.update { it.copy(mapLayer = layer, showMapLayerMenu = false) }
    }

    fun setLayerMenuExpanded(expanded: Boolean) {
        _uiState.update { it.copy(showMapLayerMenu = expanded) }
    }
}
