package com.amresalehin.emregallery.ui

import android.content.Context
import android.view.View
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.amresalehin.emregallery.R
import com.amresalehin.emregallery.data.MediaItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Placeholder shown in the selected-place bar until the place name resolves. */
private const val PLACE_NAME_PLACEHOLDER = "Even Light isn\u2019t Instant"

/** Fraction of the bar's width/height that must be dragged to dismiss it. */
private const val DISMISS_WIDTH_FRACTION = 0.35f
private const val DISMISS_HEIGHT_FRACTION = 0.6f

/** Fling speed (px/s) that dismisses the bar regardless of distance. */
private const val DISMISS_FLING_VELOCITY = 1200f

@Composable
fun PhotoMapTopBar(
    mapLayer: String,
    showMapLayerMenu: Boolean,
    onBack: () -> Unit,
    onOpenLayerMenu: () -> Unit,
    onDismissLayerMenu: () -> Unit,
    onSelectLayer: (String) -> Unit,
    onFitAllPhotos: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            onClick = onBack,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(42.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface)
            }
        }

        Spacer(Modifier.weight(1f))
        Box {
            Surface(
                onClick = onOpenLayerMenu,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (mapLayer == "Satellite") Icons.Default.SatelliteAlt else Icons.Default.Layers,
                        contentDescription = "Map layer: $mapLayer",
                        modifier = Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            DropdownMenu(expanded = showMapLayerMenu, onDismissRequest = onDismissLayerMenu) {
                DropdownMenuItem(
                    text = { Text("Standard map") },
                    leadingIcon = { Icon(Icons.Default.Map, contentDescription = null) },
                    trailingIcon = { if (mapLayer == "Standard") Icon(Icons.Default.Check, contentDescription = "Selected") },
                    onClick = { onSelectLayer("Standard") }
                )
                DropdownMenuItem(
                    text = { Text("Satellite") },
                    leadingIcon = { Icon(Icons.Default.SatelliteAlt, contentDescription = null) },
                    trailingIcon = { if (mapLayer == "Satellite") Icon(Icons.Default.Check, contentDescription = "Selected") },
                    onClick = { onSelectLayer("Satellite") }
                )
            }
        }

        Spacer(Modifier.width(8.dp))
        Surface(
            onClick = onFitAllPhotos,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(38.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.FilterCenterFocus, contentDescription = "Fit all media", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/**
 * Full-width bar showing the selected photo's place.
 *
 * - Tap anywhere on the bar to open the media.
 * - Swipe left, right, or down to dismiss. Dragging upward is blocked.
 */
@Composable
private fun SelectedPlaceBar(
    placeName: String?,
    provenance: com.amresalehin.emregallery.data.LocationProvenance? = null,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val currentOnDismiss = rememberUpdatedState(onDismiss)
    val offset = remember { mutableStateOf(Offset.Zero) }
    val size = remember { mutableStateOf(IntSize.Zero) }
    val settleJob = remember { mutableStateOf<Job?>(null) }
    val tracker = remember { VelocityTracker() }

    // Animates the bar either off-screen (dismiss) or back to rest, based on distance and fling speed.
    val settle: () -> Unit = {
        val velocity = tracker.calculateVelocity()
        val o = offset.value
        val w = size.value.width.toFloat().coerceAtLeast(1f)
        val h = size.value.height.toFloat().coerceAtLeast(1f)
        val target: Offset? = when {
            o.x > w * DISMISS_WIDTH_FRACTION || (velocity.x > DISMISS_FLING_VELOCITY && o.x > 0f) ->
                Offset(w * 1.5f, o.y)
            o.x < -w * DISMISS_WIDTH_FRACTION || (velocity.x < -DISMISS_FLING_VELOCITY && o.x < 0f) ->
                Offset(-w * 1.5f, o.y)
            o.y > h * DISMISS_HEIGHT_FRACTION || (velocity.y > DISMISS_FLING_VELOCITY && o.y > 0f) ->
                Offset(o.x, h * 3f)
            else -> null
        }
        settleJob.value?.cancel()
        settleJob.value = scope.launch {
            val from = offset.value
            val to = target ?: Offset.Zero
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = if (target != null) tween(durationMillis = 180) else spring(stiffness = 400f)
            ) { t, _ ->
                offset.value = Offset(
                    from.x + (to.x - from.x) * t,
                    from.y + (to.y - from.y) * t
                )
            }
            if (target != null) currentOnDismiss.value()
        }
    }

    Box(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)) {
        Surface(
            onClick = onOpen,
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)),
            shadowElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("map_redirect_button")
                .onSizeChanged { size.value = it }
                // pointerInput stays outside graphicsLayer so drag coordinates don't shift while the bar moves.
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            settleJob.value?.cancel()
                            tracker.resetTracking()
                        },
                        onDragEnd = { settle() },
                        onDragCancel = { settle() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            val o = offset.value
                            offset.value = Offset(
                                o.x + dragAmount.x,
                                (o.y + dragAmount.y).coerceAtLeast(0f)
                            )
                        }
                    )
                }
                .graphicsLayer {
                    val o = offset.value
                    val w = size.value.width.toFloat().coerceAtLeast(1f)
                    val h = size.value.height.toFloat().coerceAtLeast(1f)
                    translationX = o.x
                    translationY = o.y
                    val progress = max(abs(o.x) / w, o.y / h).coerceIn(0f, 1f)
                    alpha = 1f - 0.7f * progress
                }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    Icons.Default.Place,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = placeName ?: PLACE_NAME_PLACEHOLDER,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        fontStyle = if (placeName == null) FontStyle.Italic else FontStyle.Normal,
                        color = if (placeName == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (provenance != null) {
                        Spacer(Modifier.height(3.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val methodLabel = when (provenance.matchMethod?.lowercase()) {
                                "interpolated" -> "Interpolated location"
                                "staypoint" -> "Staypoint visit"
                                "nearest" -> "Nearest point"
                                "existing_exif" -> "EXIF GPS"
                                else -> provenance.matchMethod?.replaceFirstChar { it.uppercase() } ?: "Located"
                            }
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                            ) {
                                Text(
                                    text = methodLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                            if (provenance.sampleGapMinutes != null && provenance.sampleGapMinutes > 0.0) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        text = "${String.format(Locale.US, "%.1f", provenance.sampleGapMinutes)}m gap",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            val sourceLabel = when {
                                provenance.source?.contains("exif", ignoreCase = true) == true -> "EXIF"
                                else -> provenance.source?.take(12) ?: "Source"
                            }
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                            ) {
                                Text(
                                    text = sourceLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(24.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Open selected media",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Container for the interactive map and its heatmap overlay.
 *
 * [onDismissSelected] is called after the user swipes the selected-place bar away.
 * The caller must clear its selection (set [selectedMedia] to null) so the pin is removed too.
 */
@Composable
fun PhotoMapViewContainer(
    locatedMedia: List<Pair<MediaItem, GeoPoint>>,
    mapLayer: String,
    loading: Boolean,
    scanProgress: Int,
    scanTotal: Int,
    selectedMedia: Pair<MediaItem, GeoPoint>? = null,
    selectedPlaceName: String? = null,
    onOpenSelected: () -> Unit = {},
    onDismissSelected: () -> Unit = {},
    onMapReady: (MapView) -> Unit,
    onBoundsChanged: (BoundingBox?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val latestOnMapReady = rememberUpdatedState(onMapReady)
    val latestOnBoundsChanged = rememberUpdatedState(onBoundsChanged)
    val heatmapPoints = remember(locatedMedia) { locatedMedia.map { it.second } }
    val heatmapOverlay = remember { PhotoHeatmapOverlay() }
    val selectedMarker = remember { mutableStateOf<Marker?>(null) }
    val mapInstance = remember { mutableStateOf<MapView?>(null) }

    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("photo_map_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp))) {
            AndroidView(
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)),
                factory = { ctx ->
                    Configuration.getInstance().userAgentValue = ctx.packageName
                    val savedCamera = loadSavedPhotoMapCamera(ctx)
                    MapView(ctx).apply {
                        var cameraInitialized = false
                        val cameraSaveRunnable = Runnable {
                            if (cameraInitialized) persistPhotoMapCamera(ctx, this@apply)
                        }
                        fun scheduleCameraPersistence() {
                            removeCallbacks(cameraSaveRunnable)
                            postDelayed(cameraSaveRunnable, 350L)
                        }

                        clipToOutline = true
                        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                        setTileSource(if (mapLayer == "Satellite") PhotoMapTiles.satelliteTileSource() else TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        setBuiltInZoomControls(false)
                        setVerticalMapRepetitionEnabled(false)
                        setHorizontalMapRepetitionEnabled(false)
                        setScrollableAreaLimitDouble(BoundingBox(85.0511, 180.0, -85.0511, -180.0))
                        minZoomLevel = MIN_MAP_ZOOM
                        maxZoomLevel = MAX_MAP_ZOOM
                        controller.setZoom(savedCamera?.zoom ?: DEFAULT_WORLD_ZOOM)
                        controller.setCenter(savedCamera?.let { GeoPoint(it.latitude, it.longitude) } ?: WORLD_VIEW_CENTER)

                        // Initialize the camera once after measurement. Indexing never reframes it.
                        addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                            val newW = right - left
                            val newH = bottom - top
                            if (newW > 0 && newH > 0 && !cameraInitialized) {
                                minZoomLevel = MIN_MAP_ZOOM
                                maxZoomLevel = MAX_MAP_ZOOM
                                if (savedCamera != null) {
                                    controller.setCenter(GeoPoint(savedCamera.latitude, savedCamera.longitude))
                                    controller.setZoom(savedCamera.zoom.coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM))
                                } else {
                                    controller.setCenter(WORLD_VIEW_CENTER)
                                    controller.setZoom(DEFAULT_WORLD_ZOOM)
                                }
                                cameraInitialized = true
                                mapInstance.value = this
                                latestOnMapReady.value(this)
                                latestOnBoundsChanged.value(calculateMapBounds(this, ctx))
                                invalidate()
                            }
                        }

                        addMapListener(object : MapListener {
                            override fun onScroll(event: ScrollEvent?): Boolean {
                                latestOnBoundsChanged.value(calculateMapBounds(this@apply, ctx))
                                if (cameraInitialized) scheduleCameraPersistence()
                                return true
                            }
                            override fun onZoom(event: ZoomEvent?): Boolean {
                                latestOnBoundsChanged.value(calculateMapBounds(this@apply, ctx))
                                if (cameraInitialized) scheduleCameraPersistence()
                                return true
                            }
                        })

                        // Flush any pending camera save when the view leaves the screen,
                        // so the last pan/zoom is kept and no late save runs on a detached view.
                        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(v: View) = Unit
                            override fun onViewDetachedFromWindow(v: View) {
                                removeCallbacks(cameraSaveRunnable)
                                if (cameraInitialized) persistPhotoMapCamera(ctx, this@apply)
                            }
                        })
                    }
                },
                update = { map ->
                    mapInstance.value = map
                    // Heatmap stays visible at all times.
                    heatmapOverlay.updatePoints(heatmapPoints)
                    if (!map.overlays.contains(heatmapOverlay)) {
                        map.overlays.add(heatmapOverlay)
                    }

                    val selected = selectedMedia
                    if (selected == null) {
                        selectedMarker.value?.let { map.overlays.remove(it) }
                        selectedMarker.value = null
                    } else {
                        val marker = selectedMarker.value ?: Marker(map).apply {
                            icon = ContextCompat.getDrawable(map.context, R.drawable.ic_map_selection_pin)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        }.also { selectedMarker.value = it }
                        marker.position = selected.second
                        marker.title = selected.first.name
                        if (!map.overlays.contains(marker)) map.overlays.add(marker)
                    }

                    // Viewport bounds are emitted by map gestures and initial camera setup.
                    // Do not post another bounds update from every AndroidView recomposition.
                    map.invalidate()
                }
            )

            // Simple, intuitive Zoom In (+) / Zoom Out (−) floating controls
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 12.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(2.dp)
                ) {
                    IconButton(
                        onClick = { mapInstance.value?.controller?.zoomIn() },
                        modifier = Modifier.size(38.dp).testTag("map_zoom_in_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Zoom in",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.width(22.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                    )
                    IconButton(
                        onClick = { mapInstance.value?.controller?.zoomOut() },
                        modifier = Modifier.size(38.dp).testTag("map_zoom_out_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = "Zoom out",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color.Black.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 10.dp)
            ) {
                Text("© OpenStreetMap", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }

            if (selectedMedia != null) {
                // key() resets the bar's drag state whenever a different photo is selected.
                key(selectedMedia.first.uri) {
                    val provenance = remember(selectedMedia.first.uri) {
                        com.amresalehin.emregallery.data.PhotoMapLocationCache(context).getLocation(selectedMedia.first.uri)?.provenance
                    }
                    SelectedPlaceBar(
                        placeName = selectedPlaceName,
                        provenance = provenance,
                        onOpen = onOpenSelected,
                        onDismiss = onDismissSelected,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }

            if (loading && scanTotal > 0) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                    shadowElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                        Text("Indexing coordinates ($scanProgress/$scanTotal)", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

fun calculateMapBounds(map: MapView, context: Context): BoundingBox? {
    if (map.width <= 0 || map.height <= 0) return null
    val density = context.resources.displayMetrics.density
    val topPx = (8f * density).toInt().coerceIn(0, map.height - 1)
    val bottomPx = (map.height - 8f * density).toInt().coerceIn(topPx + 1, map.height)
    val northWest = map.projection.fromPixels(0, topPx)
    val southEast = map.projection.fromPixels(map.width, bottomPx)
    val latNorth = maxOf(northWest.latitude, southEast.latitude).coerceIn(-85.0511, 85.0511)
    val latSouth = minOf(northWest.latitude, southEast.latitude).coerceIn(-85.0511, 85.0511)
    val lonWest = northWest.longitude.coerceIn(-180.0, 180.0)
    val lonEast = southEast.longitude.coerceIn(-180.0, 180.0)
    return BoundingBox(latNorth, lonEast, latSouth, lonWest)
}

/**
 * Bottom card displaying photos within the visible viewport, grouped by day.
 */
@Composable
fun PhotoMapDetailsCard(
    viewportCount: Int,
    locatedTotal: Int,
    loading: Boolean,
    scanProgress: Int = 0,
    scanTotal: Int = 0,
    dayGroups: List<Pair<String, List<Pair<MediaItem, GeoPoint>>>>,
    onPhotoClick: (MediaItem, GeoPoint) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Card(
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp).testTag("photo_map_details_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when {
                        loading && locatedTotal == 0 -> "Scanning media for locations…"
                        !loading && locatedTotal == 0 -> "No geotagged media"
                        else -> "%,d items in this area".format(Locale.getDefault(), viewportCount)
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (locatedTotal > 0 && viewportCount != locatedTotal) {
                    Text("of $locatedTotal located", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            if (loading && locatedTotal == 0) {
                Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text("Scanning media locations…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (scanTotal > 0) "Reading GPS metadata ($scanProgress of $scanTotal scanned)" else "Scanning your gallery for GPS metadata…",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else if (!loading && locatedTotal == 0) {
                Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.LocationOff, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("No media with GPS data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("None of the images or videos in this gallery contain accessible GPS coordinates.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            } else if (viewportCount == 0) {
                Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.Explore, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("No media in this area", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("Pan or zoom out to see media.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    dayGroups.forEach { (day, entries) ->
                        item(key = "heading_$day", span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(day, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                                Text("${entries.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items(entries, key = { it.first.uri }) { (photo, point) ->
                            Box(
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onPhotoClick(photo, point) }
                            ) {
                                AsyncImage(
                                    model = remember(photo.uri, context) {
                                        ThumbnailUtils.buildThumbnailRequest(context, photo.uri)
                                    },
                                    contentDescription = photo.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                if (photo.isVideo) {
                                    Surface(
                                        modifier = Modifier.align(Alignment.Center).size(32.dp),
                                        shape = CircleShape,
                                        color = Color.Black.copy(alpha = 0.58f)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Default.PlayArrow,
                                                contentDescription = "Video",
                                                tint = Color.White,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
