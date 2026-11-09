package com.amresalehin.emregallery.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Smoothed geographic density overlay for geotagged photos.
 *
 * Rendering uses a cached, filtered density bitmap plus interpolated isopleth lines.
 * The density colour scale is tied to a fixed local-photo-count reference, not the
 * strongest pixel in the current viewport, so one extreme hotspot cannot hide weaker
 * clusters. Expensive grid work runs on a single background worker after the viewport
 * has settled; repeated redraws reuse the cached result.
 */
class PhotoHeatmapOverlay(
    initialPoints: List<GeoPoint> = emptyList()
) : Overlay() {

    @Volatile
    private var points: List<GeoPoint> = initialPoints
    @Volatile
    private var pointsVersion: Int = 0
    @Volatile
    private var renderGeneration: Int = 0
    @Volatile
    private var cachedRender: RenderedHeatmap? = null
    @Volatile
    private var activeKey: RenderKey? = null
    private val renderLock = Any()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingKey: RenderKey? = null
    private var pendingRender: Runnable? = null

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }

    private data class ScreenPoint(val x: Float, val y: Float)
    private data class RenderKey(
        val width: Int,
        val height: Int,
        val zoomBucket: Int,
        val cameraXBucket: Long,
        val cameraYBucket: Long,
        val pointsVersion: Int
    )
    private data class RenderedHeatmap(val key: RenderKey, val bitmap: Bitmap)
    private data class BucketKey(val x: Int, val y: Int)

    /**
     * Change photo coordinates without replacing this overlay instance. The caller
     * should pass a stable list (for example, one remembered against locatedMedia).
     */
    fun updatePoints(newPoints: List<GeoPoint>) {
        if (newPoints === points) return
        points = newPoints
        pointsVersion += 1
        renderGeneration += 1
        pendingRender?.let(mainHandler::removeCallbacks)
        pendingRender = null
        pendingKey = null
        activeKey = null
        synchronized(renderLock) {
            cachedRender?.bitmap?.recycle()
            cachedRender = null
        }
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        super.draw(canvas, mapView, shadow)
        if (shadow || points.isEmpty() || mapView.width <= 0 || mapView.height <= 0) return

        val key = createRenderKey(mapView) ?: return
        // Hold the render lock through drawing. updatePoints() recycles the previous bitmap;
        // releasing this lock before Canvas.drawBitmap() allowed a concurrent update to recycle
        // the bitmap after lookup, causing a runtime crash while indexing was updating points.
        val drewCachedBitmap = synchronized(renderLock) {
            val renderedBitmap = cachedRender
                ?.takeIf { it.key == key && !it.bitmap.isRecycled }
                ?.bitmap
            if (renderedBitmap != null) {
                canvas.drawBitmap(
                    renderedBitmap,
                    null,
                    RectF(0f, 0f, mapView.width.toFloat(), mapView.height.toFloat()),
                    bitmapPaint
                )
                true
            } else {
                false
            }
        }
        if (drewCachedBitmap) return

        requestRender(mapView, key)
    }

    /**
     * Key camera positions in world-pixel space. Small sub-pixel movements reuse the
     * cached image; a larger pan, zoom change, size change, or coordinate update rerenders.
     */
    private fun createRenderKey(mapView: MapView): RenderKey? {
        val width = mapView.width
        val height = mapView.height
        if (width <= 0 || height <= 0) return null

        val zoom = mapView.zoomLevelDouble.coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM)
        val zoomBucket = (zoom * ZOOM_KEY_STEPS).roundToInt()
        val keyZoom = zoomBucket.toDouble() / ZOOM_KEY_STEPS
        val center = mapView.projection.fromPixels(width / 2, height / 2)
        val worldSize = TILE_SIZE * 2.0.pow(keyZoom)
        val latitude = center.latitude.coerceIn(-MAX_MERCATOR_LATITUDE, MAX_MERCATOR_LATITUDE)
        val sinLatitude = sin(latitude * PI / 180.0)
        val worldX = (center.longitude + 180.0) / 360.0 * worldSize
        val worldY = (0.5 - ln((1.0 + sinLatitude) / (1.0 - sinLatitude)) / (4.0 * PI)) * worldSize

        return RenderKey(
            width = width,
            height = height,
            zoomBucket = zoomBucket,
            cameraXBucket = (worldX / CAMERA_BUCKET_PIXELS).roundToLong(),
            cameraYBucket = (worldY / CAMERA_BUCKET_PIXELS).roundToLong(),
            pointsVersion = pointsVersion
        )
    }

    /**
     * Debounce camera motion before snapshotting projected points. A single worker
     * generates the field, so rapid pan/zoom gestures don't queue expensive stale work.
     */
    private fun requestRender(mapView: MapView, key: RenderKey) {
        if (pendingKey == key || activeKey == key) return

        pendingRender?.let(mainHandler::removeCallbacks)
        pendingKey = key
        renderGeneration += 1
        val generation = renderGeneration
        val mapReference = WeakReference(mapView)
        val task = Runnable {
            val map = mapReference.get() ?: return@Runnable
            val currentKey = createRenderKey(map)
            if (currentKey != key || pointsVersion != key.pointsVersion) {
                if (pendingKey == key) pendingKey = null
                return@Runnable
            }

            pendingKey = null
            activeKey = key
            val zoom = (key.zoomBucket.toDouble() / ZOOM_KEY_STEPS)
                .coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM)
            val kernel = kernelForZoom(zoom)
            val projectedPoints = projectPoints(map, kernel.radius * KERNEL_SUPPORT_MULTIPLIER)
            val snapshotVersion = pointsVersion

            RENDER_EXECUTOR.execute {
                val result = buildRender(
                    key = key,
                    width = key.width,
                    height = key.height,
                    gridCell = if (zoom >= FINE_GRID_ZOOM) FINE_GRID_CELL else GRID_CELL,
                    kernelRadius = kernel.radius,
                    kernelSigma = kernel.sigma,
                    projectedPoints = projectedPoints,
                    generation = generation,
                    expectedVersion = snapshotVersion
                )
                if (result != null &&
                    renderGeneration == generation &&
                    pointsVersion == snapshotVersion
                ) {
                    synchronized(renderLock) {
                        if (renderGeneration == generation && pointsVersion == snapshotVersion) {
                            val previous = cachedRender
                            cachedRender = result
                            if (previous?.bitmap !== result.bitmap) previous?.bitmap?.recycle()
                        } else {
                            result.bitmap.recycle()
                        }
                    }
                    activeKey = null
                    mapReference.get()?.postInvalidate()
                } else {
                    result?.bitmap?.recycle()
                    if (activeKey == key) {
                        activeKey = null
                        mapReference.get()?.postInvalidate()
                    }
                }
            }
        }
        pendingRender = task
        mainHandler.postDelayed(task, RENDER_DEBOUNCE_MS)
    }

    /** Projection is performed on the UI thread; density accumulation happens off-thread. */
    private fun projectPoints(mapView: MapView, margin: Float): List<ScreenPoint> {
        val snapshot = points
        val width = mapView.width
        val height = mapView.height
        val projection = mapView.projection
        val projected = Point()
        val result = ArrayList<ScreenPoint>(min(snapshot.size, MAX_PROJECTED_POINTS_HINT))
        for (point in snapshot) {
            projection.toPixels(point, projected)
            val x = projected.x.toFloat()
            val y = projected.y.toFloat()
            if (x >= -margin && x <= width + margin &&
                y >= -margin && y <= height + margin
            ) {
                result.add(ScreenPoint(x, y))
            }
        }
        return result
    }

    private fun buildRender(
        key: RenderKey,
        width: Int,
        height: Int,
        gridCell: Int,
        kernelRadius: Float,
        kernelSigma: Float,
        projectedPoints: List<ScreenPoint>,
        generation: Int,
        expectedVersion: Int
    ): RenderedHeatmap? {
        fun isCancelled(): Boolean =
            Thread.currentThread().isInterrupted ||
                renderGeneration != generation ||
                pointsVersion != expectedVersion

        val gridWidth = (width + gridCell - 1) / gridCell
        val gridHeight = (height + gridCell - 1) / gridCell
        if (gridWidth <= 0 || gridHeight <= 0) return null

        val density = FloatArray(gridWidth * gridHeight)
        if (projectedPoints.isNotEmpty()) {
            // Bucket points only for bookkeeping while retaining each point's original
            // projected offset. Do not average nearby points into one artificial center.
            val buckets = HashMap<BucketKey, MutableList<ScreenPoint>>()
            for (point in projectedPoints) {
                val cellX = floor(point.x / gridCell).toInt()
                val cellY = floor(point.y / gridCell).toInt()
                buckets.getOrPut(BucketKey(cellX, cellY)) { ArrayList() }.add(point)
            }

            val supportRadius = kernelRadius * KERNEL_SUPPORT_MULTIPLIER
            val supportSquared = supportRadius * supportRadius
            var visited = 0
            for (bucketPoints in buckets.values) {
                if (isCancelled()) return null
                for (point in bucketPoints) {
                    val minX = max(0, floor((point.x - supportRadius) / gridCell).toInt())
                    val maxX = min(gridWidth - 1, floor((point.x + supportRadius) / gridCell).toInt())
                    val minY = max(0, floor((point.y - supportRadius) / gridCell).toInt())
                    val maxY = min(gridHeight - 1, floor((point.y + supportRadius) / gridCell).toInt())
                    for (y in minY..maxY) {
                        val sampleY = y * gridCell + gridCell / 2f
                        val dy = sampleY - point.y
                        for (x in minX..maxX) {
                            val sampleX = x * gridCell + gridCell / 2f
                            val dx = sampleX - point.x
                            val distanceSquared = dx * dx + dy * dy
                            if (distanceSquared <= supportSquared) {
                                density[y * gridWidth + x] += exp(
                                    -distanceSquared / (2f * kernelSigma * kernelSigma)
                                )
                            }
                        }
                    }
                    visited++
                    if (visited % CANCEL_CHECK_BUCKETS == 0 && isCancelled()) return null
                }
            }
        }

        if (isCancelled()) return null
        val zoom = key.zoomBucket.toDouble() / ZOOM_KEY_STEPS
        val blurSigma = if (zoom >= FINE_GRID_ZOOM) FINE_BLUR_SIGMA else BLUR_SIGMA
        val smoothed = gaussianBlur(
            density,
            gridWidth,
            gridHeight,
            radius = BLUR_RADIUS,
            sigma = blurSigma,
            shouldCancel = ::isCancelled
        ) ?: return null

        val pixels = IntArray(smoothed.size)
        for (index in smoothed.indices) {
            if (index % PIXEL_CANCEL_CHECK_INTERVAL == 0 && isCancelled()) return null
            pixels[index] = densityColor(smoothed[index])
        }
        val bitmap = Bitmap.createBitmap(gridWidth, gridHeight, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, gridWidth, 0, 0, gridWidth, gridHeight)

        if (isCancelled()) {
            bitmap.recycle()
            return null
        }
        return RenderedHeatmap(key, bitmap)
    }

    /**
     * Nonlinear but fixed density transfer: the reference is a local sum of five
     * photo-kernel contributions, not the strongest sample in the visible viewport.
     */
    private fun densityColor(density: Float): Int {
        if (density <= MIN_VISIBLE_DENSITY) return Color.TRANSPARENT
        val intensity = (ln(1f + density) / LN_REFERENCE_DENSITY).coerceIn(0f, 1f)
        val upperIndex = COLOR_STOPS.indexOfFirst { it.first >= intensity }
        val lowerIndex = when {
            upperIndex < 0 -> COLOR_STOPS.lastIndex - 1
            upperIndex <= 0 -> 0
            else -> upperIndex - 1
        }
        val fraction = when {
            upperIndex < 0 -> 1f
            upperIndex <= 0 -> 0f
            else -> {
                val lowerStop = COLOR_STOPS[lowerIndex]
                val upperStop = COLOR_STOPS[upperIndex]
                ((intensity - lowerStop.first) / (upperStop.first - lowerStop.first))
                    .coerceIn(0f, 1f)
            }
        }

        val colorA = COLOR_STOPS[lowerIndex].second
        val colorB = COLOR_STOPS[min(lowerIndex + 1, COLOR_STOPS.lastIndex)].second
        fun channel(color: Int, shift: Int) = (color shr shift) and 0xff
        fun lerpChannel(shift: Int): Int =
            (channel(colorA, shift) +
                (channel(colorB, shift) - channel(colorA, shift)) * fraction)
                .roundToInt().coerceIn(0, 255)

        val alphaFraction = ((intensity - ALPHA_START) / (ALPHA_FULL - ALPHA_START)).coerceIn(0f, 1f)
        val alpha = (alphaFraction * MAX_HEAT_ALPHA).roundToInt()
        if (alpha <= 0) return Color.TRANSPARENT
        return Color.argb(alpha, lerpChannel(16), lerpChannel(8), lerpChannel(0))
    }

    private fun gaussianBlur(
        source: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        sigma: Float,
        shouldCancel: () -> Boolean
    ): FloatArray? {
        val weights = FloatArray(radius * 2 + 1)
        var total = 0f
        for (offset in -radius..radius) {
            val weight = exp(-(offset * offset).toFloat() / (2f * sigma * sigma))
            weights[offset + radius] = weight
            total += weight
        }
        for (index in weights.indices) weights[index] /= total

        val horizontal = FloatArray(source.size)
        val output = FloatArray(source.size)
        for (y in 0 until height) {
            if (y % BLUR_CANCEL_CHECK_ROWS == 0 && shouldCancel()) return null
            val row = y * width
            for (x in 0 until width) {
                var value = 0f
                for (offset in -radius..radius) {
                    val sx = (x + offset).coerceIn(0, width - 1)
                    value += source[row + sx] * weights[offset + radius]
                }
                horizontal[row + x] = value
            }
        }
        for (y in 0 until height) {
            if (y % BLUR_CANCEL_CHECK_ROWS == 0 && shouldCancel()) return null
            for (x in 0 until width) {
                var value = 0f
                for (offset in -radius..radius) {
                    val sy = (y + offset).coerceIn(0, height - 1)
                    value += horizontal[sy * width + x] * weights[offset + radius]
                }
                output[y * width + x] = value
            }
        }
        return output
    }

    private data class Kernel(val radius: Float, val sigma: Float)

    private fun kernelForZoom(zoom: Double): Kernel {
        val fraction = ((zoom - MIN_MAP_ZOOM) / (MAX_MAP_ZOOM - MIN_MAP_ZOOM))
            .toFloat().coerceIn(0f, 1f)
        val eased = fraction * fraction * (3f - 2f * fraction)
        return Kernel(
            lerp(MAX_KERNEL_RADIUS, MIN_KERNEL_RADIUS, eased),
            lerp(MAX_KERNEL_SIGMA, MIN_KERNEL_SIGMA, eased)
        )
    }

    private fun lerp(from: Float, to: Float, fraction: Float): Float =
        from + (to - from) * fraction

    private companion object {
        const val MIN_MAP_ZOOM = 1.0
        const val MAX_MAP_ZOOM = 19.0
        const val FINE_GRID_ZOOM = 14.0
        const val TILE_SIZE = 256.0
        const val MAX_MERCATOR_LATITUDE = 85.05112878
        const val ZOOM_KEY_STEPS = 10
        const val CAMERA_BUCKET_PIXELS = 4.0
        const val GRID_CELL = 8
        const val FINE_GRID_CELL = 4
        const val MAX_KERNEL_RADIUS = 34f
        const val MIN_KERNEL_RADIUS = 12f
        const val MAX_KERNEL_SIGMA = 12f
        const val MIN_KERNEL_SIGMA = 5.5f
        const val KERNEL_SUPPORT_MULTIPLIER = 2.2f
        const val BLUR_RADIUS = 2
        const val BLUR_SIGMA = 0.8f
        const val FINE_BLUR_SIGMA = 0.65f
        const val RENDER_DEBOUNCE_MS = 80L
        const val MAX_PROJECTED_POINTS_HINT = 4096
        const val CANCEL_CHECK_BUCKETS = 32
        const val PIXEL_CANCEL_CHECK_INTERVAL = 1024
        const val BLUR_CANCEL_CHECK_ROWS = 8
        const val FLOAT_EPSILON = 0.00001f
        const val REFERENCE_DENSITY = 5f
        val LN_REFERENCE_DENSITY = ln(1f + REFERENCE_DENSITY)
        const val MIN_VISIBLE_DENSITY = 0.012f
        const val ALPHA_START = 0.025f
        const val ALPHA_FULL = 0.9f
        const val MAX_HEAT_ALPHA = 154f
        val COLOR_STOPS = listOf(
            0.0f to Color.rgb(116, 87, 217), // violet
            0.18f to Color.rgb(149, 96, 233), // purple
            0.40f to Color.rgb(214, 107, 234), // magenta-pink
            0.62f to Color.rgb(245, 141, 203), // pink
            0.80f to Color.rgb(250, 155, 169), // warm pink
            0.92f to Color.rgb(249, 199, 106), // warm yellow
            1.0f to Color.rgb(255, 231, 123) // pale yellow core
        )
        val RENDER_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "Emre-PhotoHeatmap").apply { isDaemon = true }
        }
    }
}
