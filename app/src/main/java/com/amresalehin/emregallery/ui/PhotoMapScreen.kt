package com.amresalehin.emregallery.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.amresalehin.emregallery.data.MediaItem
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

const val WORLD_VIEW_LAT_NORTH = 85.0511
const val WORLD_VIEW_LAT_SOUTH = -85.0511
const val WORLD_VIEW_LON_WEST = -180.0
const val WORLD_VIEW_LON_EAST = 180.0

const val MIN_MAP_ZOOM = 2.0
const val MAX_MAP_ZOOM = 19.0
const val DEFAULT_WORLD_ZOOM = 2.5
const val SINGLE_PHOTO_ZOOM = 15.0

/** Center of the full-world view used when there is no saved camera. */
val WORLD_VIEW_CENTER = GeoPoint(0.0, 0.0)
val WORLD_VIEW_BOUNDS = BoundingBox(
    WORLD_VIEW_LAT_NORTH,
    WORLD_VIEW_LON_EAST,
    WORLD_VIEW_LAT_SOUTH,
    WORLD_VIEW_LON_WEST
)

private const val PHOTO_MAP_CAMERA_PREFERENCES = "photo_map_camera"
private const val CAMERA_LATITUDE_KEY = "latitude"
private const val CAMERA_LONGITUDE_KEY = "longitude"
private const val CAMERA_ZOOM_KEY = "zoom"

/** A camera snapshot saved independently of the photo-indexing lifecycle. */
internal data class SavedPhotoMapCamera(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double
)

internal fun loadSavedPhotoMapCamera(context: Context): SavedPhotoMapCamera? {
    val preferences = context.getSharedPreferences(
        PHOTO_MAP_CAMERA_PREFERENCES,
        Context.MODE_PRIVATE
    )
    val latitude = preferences.getString(CAMERA_LATITUDE_KEY, null)?.toDoubleOrNull()
        ?: return null
    val longitude = preferences.getString(CAMERA_LONGITUDE_KEY, null)?.toDoubleOrNull()
        ?: return null
    val zoom = preferences.getString(CAMERA_ZOOM_KEY, null)?.toDoubleOrNull()
        ?: return null

    if (!latitude.isFinite() || latitude !in -90.0..90.0) return null
    if (!longitude.isFinite() || longitude !in -180.0..180.0) return null
    if (!zoom.isFinite() || zoom <= 0.0 || zoom > 30.0) return null

    return SavedPhotoMapCamera(latitude, longitude, zoom)
}

internal fun persistPhotoMapCamera(context: Context, map: MapView) {
    runCatching {
        if (map.width <= 0 || map.height <= 0) return

        val center = map.mapCenter
        val zoom = map.zoomLevelDouble
        if (!center.latitude.isFinite() || !center.longitude.isFinite() ||
            !zoom.isFinite() || zoom <= 0.0
        ) return

        context.getSharedPreferences(PHOTO_MAP_CAMERA_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(CAMERA_LATITUDE_KEY, center.latitude.toString())
            .putString(CAMERA_LONGITUDE_KEY, center.longitude.toString())
            .putString(CAMERA_ZOOM_KEY, zoom.toString())
            .apply()
    }
}

/**
 * Returns the default world overview zoom level.
 */
fun calculateWorldFitZoom(map: MapView): Double = DEFAULT_WORLD_ZOOM

/**
 * Frames the entire world, centered, so any geotagged media is visible at a glance.
 */
fun showWorldView(map: MapView, animated: Boolean = false) {
    map.minZoomLevel = MIN_MAP_ZOOM
    map.maxZoomLevel = MAX_MAP_ZOOM
    if (animated) {
        map.controller.animateTo(WORLD_VIEW_CENTER)
        map.controller.setZoom(DEFAULT_WORLD_ZOOM)
    } else {
        map.controller.setCenter(WORLD_VIEW_CENTER)
        map.controller.setZoom(DEFAULT_WORLD_ZOOM)
    }
    map.invalidate()
}

fun focusSinglePhoto(map: MapView, point: GeoPoint, animated: Boolean = true) {
    if (map.width <= 0 || map.height <= 0) {
        map.post { focusSinglePhoto(map, point, animated) }
        return
    }
    map.minZoomLevel = MIN_MAP_ZOOM
    map.maxZoomLevel = MAX_MAP_ZOOM
    if (animated) map.controller.animateTo(point) else map.controller.setCenter(point)
    map.controller.setZoom(SINGLE_PHOTO_ZOOM)
    map.invalidate()
}

/**
 * Computes the geographic bounding box of a list of coordinates with safety margins,
 * clamped strictly to valid Web Mercator world bounds.
 */
fun computeMediaBoundingBox(points: List<GeoPoint>): BoundingBox? {
    if (points.isEmpty()) return null
    if (points.size == 1) {
        val pt = points.first()
        return BoundingBox(
            (pt.latitude + 0.005).coerceAtMost(WORLD_VIEW_LAT_NORTH),
            (pt.longitude + 0.005).coerceAtMost(WORLD_VIEW_LON_EAST),
            (pt.latitude - 0.005).coerceAtLeast(WORLD_VIEW_LAT_SOUTH),
            (pt.longitude - 0.005).coerceAtLeast(WORLD_VIEW_LON_WEST)
        )
    }
    val north = points.maxOf { it.latitude }
    val south = points.minOf { it.latitude }
    val east = points.maxOf { it.longitude }
    val west = points.minOf { it.longitude }
    val latSpan = north - south
    val lonSpan = kotlin.math.abs(east - west)

    val latMargin = (latSpan * 0.02).coerceAtLeast(0.0001)
    val lonMargin = (lonSpan * 0.02).coerceAtLeast(0.0001)
    return BoundingBox(
        (north + latMargin).coerceAtMost(WORLD_VIEW_LAT_NORTH),
        (east + lonMargin).coerceAtMost(WORLD_VIEW_LON_EAST),
        (south - latMargin).coerceAtLeast(WORLD_VIEW_LAT_SOUTH),
        (west - lonMargin).coerceAtLeast(WORLD_VIEW_LON_WEST)
    )
}

/**
 * Fits the given bounding box on the map with standard edge padding and predictable zoom limits.
 */
fun fitBounds(
    map: MapView,
    bounds: BoundingBox,
    animated: Boolean = true,
    paddingDp: Int = 48
) {
    if (map.width <= 0 || map.height <= 0) {
        map.post { fitBounds(map, bounds, animated, paddingDp) }
        return
    }
    val density = map.context.resources.displayMetrics.density
    val standardPaddingPx = (paddingDp * density).toInt()
    val maxPaddingX = (map.width * 0.2f).toInt()
    val maxPaddingY = (map.height * 0.2f).toInt()
    val paddingPx = minOf(standardPaddingPx, maxPaddingX, maxPaddingY).coerceAtLeast(16)

    runCatching {
        map.minZoomLevel = MIN_MAP_ZOOM
        map.maxZoomLevel = MAX_MAP_ZOOM
        map.zoomToBoundingBox(bounds, animated, paddingPx)
        val clampedZoom = map.zoomLevelDouble.coerceIn(MIN_MAP_ZOOM, SINGLE_PHOTO_ZOOM)
        if (map.zoomLevelDouble != clampedZoom) {
            map.controller.setZoom(clampedZoom)
        }
        map.invalidate()
    }
}

/**
 * Fits a list of coordinates on the map.
 * When empty, falls back to the full world view.
 */
fun fitPointsToMap(
    map: MapView,
    points: List<GeoPoint>,
    animated: Boolean = true,
    paddingDp: Int = 48
) {
    if (points.isEmpty()) {
        showWorldView(map, animated)
        return
    }
    if (points.size == 1) {
        focusSinglePhoto(map, points.first(), animated)
        return
    }
    val bounds = computeMediaBoundingBox(points)
    if (bounds != null) {
        fitBounds(map, bounds, animated, paddingDp)
    } else {
        showWorldView(map, animated)
    }
}

@Composable
fun PhotoMapScreen(
    media: List<MediaItem>,
    focusItem: MediaItem? = null,
    onBack: () -> Unit,
    onOpen: (MediaItem) -> Unit,
    viewModel: PhotoMapViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val locationPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) viewModel.rescanMedia(media)
    }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
            !hasMediaLocationPermission(context)
        ) {
            locationPermLauncher.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
    }

    var mapRef by remember { mutableStateOf<MapView?>(null) }
    var selectedMapMedia by remember { mutableStateOf<Pair<MediaItem, GeoPoint>?>(null) }
    // null means "still resolving"; the bar shows its placeholder text until this is set.
    var selectedPlaceName by remember { mutableStateOf<String?>(null) }
    var lastFocusedUri by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(media) {
        viewModel.loadAndScanMedia(media)
    }

    LaunchedEffect(selectedMapMedia) {
        val selected = selectedMapMedia
        selectedPlaceName = null
        if (selected != null) {
            selectedPlaceName = viewModel.getPlaceName(selected.second) ?: "Place name unavailable"
        }
    }

    // A requested photo is an explicit navigation action, so it may move the camera.
    LaunchedEffect(focusItem?.uri, mapRef) {
        val requestedItem = focusItem ?: return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect
        if (lastFocusedUri == requestedItem.uri) return@LaunchedEffect

        val point = viewModel.getLocationForMedia(context, requestedItem)
        if (point != null) {
            selectedMapMedia = requestedItem to point
            focusSinglePhoto(map, point, animated = true)
            lastFocusedUri = requestedItem.uri
        } else {
            lastFocusedUri = requestedItem.uri
            snackbarHostState.showSnackbar(
                message = "Cannot focus on \"${requestedItem.name}\": no accessible GPS coordinates found.",
                duration = SnackbarDuration.Short
            )
        }
    }

    // Indexing updates data only. Camera state is saved and restored independently.
    DisposableEffect(Unit) {
        onDispose {
            mapRef?.let { map ->
                persistPhotoMapCamera(context, map)
                map.onPause()
                map.onDetach()
            }
            mapRef = null
        }
    }

    val onFitAllPhotos: () -> Unit = {
        selectedMapMedia = null
        mapRef?.let { map ->
            fitPointsToMap(map, uiState.locatedMedia.map { it.second }, animated = true)
        }
    }

    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            PhotoMapTopBar(
                mapLayer = uiState.mapLayer,
                showMapLayerMenu = uiState.showMapLayerMenu,
                onBack = onBack,
                onOpenLayerMenu = { viewModel.setLayerMenuExpanded(true) },
                onDismissLayerMenu = { viewModel.setLayerMenuExpanded(false) },
                onSelectLayer = { layer ->
                    viewModel.setMapLayer(layer)
                    mapRef?.let { map ->
                        map.setTileSource(
                            if (layer == "Satellite") PhotoMapTiles.satelliteTileSource()
                            else TileSourceFactory.MAPNIK
                        )
                        map.invalidate()
                    }
                },
                onFitAllPhotos = onFitAllPhotos
            )

            PhotoMapViewContainer(
                locatedMedia = uiState.locatedMedia,
                mapLayer = uiState.mapLayer,
                loading = uiState.loading,
                scanProgress = uiState.scanProgress,
                scanTotal = uiState.scanTotal,
                selectedMedia = selectedMapMedia,
                selectedPlaceName = selectedPlaceName,
                onOpenSelected = { selectedMapMedia?.first?.let(onOpen) },
                // Swiping the bar away clears the selection, which also removes the pin.
                onDismissSelected = { selectedMapMedia = null },
                onMapReady = { map -> mapRef = map },
                onBoundsChanged = { bounds -> viewModel.updateVisibleMapBounds(bounds) },
                modifier = Modifier.weight(1f)
            )

            PhotoMapDetailsCard(
                viewportCount = uiState.viewportMedia.size,
                locatedTotal = uiState.locatedMedia.size,
                loading = uiState.loading,
                scanProgress = uiState.scanProgress,
                scanTotal = uiState.scanTotal,
                dayGroups = uiState.dayGroups,
                onPhotoClick = { photo, point ->
                    // Selecting a thumbnail only selects its media and marker.
                    // It must not move or zoom the map camera.
                    selectedMapMedia = photo to point
                },
                modifier = Modifier.weight(1f)
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
        )
    }
}
