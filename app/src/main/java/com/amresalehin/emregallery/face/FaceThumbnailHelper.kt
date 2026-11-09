package com.amresalehin.emregallery.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface

object FaceThumbnailHelper {
    // Dynamic cache capacities: Space-Time tradeoff trades memory for zero-lag rendering.
    // Rather than artificially capping at 100 face crops and 8 base images, allocate
    // capacity scaled to the system memory class and only trim when Android requests it.
    private val maxHeapBytes = Runtime.getRuntime().maxMemory()
    private val thumbnailCacheLimit = ((maxHeapBytes / (1024 * 1024)) * 3).toInt().coerceIn(500, 2500)
    private val baseCacheLimit = ((maxHeapBytes / (1024 * 1024)) / 8).toInt().coerceIn(24, 96)

    private val memoryCache = LruCache<String, Bitmap>(thumbnailCacheLimit)

    // Base downsampled image cache to avoid repeated expensive full-res decodes for group photos
    private data class CachedBaseImage(val bitmap: Bitmap, val originalWidth: Int, val originalHeight: Int)
    private val baseImageCache = LruCache<String, CachedBaseImage>(baseCacheLimit)

    fun clearCache() {
        synchronized(memoryCache) { memoryCache.evictAll() }
        synchronized(baseImageCache) { baseImageCache.evictAll() }
    }

    fun trimMemory(level: Int) {
        synchronized(memoryCache) {
            memoryCache.trimToSize(memoryCache.size() / 2)
        }
        synchronized(baseImageCache) {
            baseImageCache.trimToSize(baseImageCache.size() / 2)
        }
    }

    fun invalidate(uri: String) {
        synchronized(baseImageCache) { baseImageCache.remove(uri) }
        synchronized(memoryCache) {
            val snapshot = memoryCache.snapshot()
            for (key in snapshot.keys) {
                if (key.contains(uri)) {
                    memoryCache.remove(key)
                }
            }
        }
    }


    fun getFaceCrop(
        context: Context,
        uri: Uri,
        faceIndex: Int,
        engineId: String = BuffaloLFaceEngine.ENGINE_ID,
        providedBounds: RectF? = null
    ): Bitmap? {
        val cacheKey = "${engineId}_${uri}_$faceIndex"
        synchronized(memoryCache) {
            memoryCache.get(cacheKey)?.let { return it }
        }

        return runCatching {
            val store = FaceClusterStore(context.applicationContext)
            val bounds = providedBounds ?: store.getFaceBounds(uri.toString(), faceIndex, engineId)
            if (bounds == null || bounds.width() < 4f || bounds.height() < 4f) {
                return null
            }

            val cachedBase = getOrDecodeBaseImage(context, uri) ?: return null
            val baseBitmap = cachedBase.bitmap
            val origW = cachedBase.originalWidth
            val origH = cachedBase.originalHeight

            val scaleX = baseBitmap.width.toFloat() / origW.toFloat().coerceAtLeast(1f)
            val scaleY = baseBitmap.height.toFloat() / origH.toFloat().coerceAtLeast(1f)

            val scaledBounds = RectF(
                bounds.left * scaleX,
                bounds.top * scaleY,
                bounds.right * scaleX,
                bounds.bottom * scaleY
            )

            // Expand bounds slightly for better framing
            val marginX = scaledBounds.width() * 0.15f
            val marginY = scaledBounds.height() * 0.15f
            val left = (scaledBounds.left - marginX).coerceIn(0f, baseBitmap.width.toFloat())
            val top = (scaledBounds.top - marginY).coerceIn(0f, baseBitmap.height.toFloat())
            val right = (scaledBounds.right + marginX).coerceIn(0f, baseBitmap.width.toFloat())
            val bottom = (scaledBounds.bottom + marginY).coerceIn(0f, baseBitmap.height.toFloat())

            val cropWidth = (right - left).toInt().coerceIn(1, baseBitmap.width)
            val cropHeight = (bottom - top).toInt().coerceIn(1, baseBitmap.height)
            val cropX = left.toInt().coerceIn(0, (baseBitmap.width - cropWidth).coerceAtLeast(0))
            val cropY = top.toInt().coerceIn(0, (baseBitmap.height - cropHeight).coerceAtLeast(0))

            val crop = Bitmap.createBitmap(baseBitmap, cropX, cropY, cropWidth, cropHeight)
            val scaled = Bitmap.createScaledBitmap(crop, 128, 128, true)
            if (scaled !== crop) crop.recycle()
            synchronized(memoryCache) {
                memoryCache.put(cacheKey, scaled)
            }
            scaled
        }.getOrNull()
    }

    private fun getOrDecodeBaseImage(context: Context, uri: Uri): CachedBaseImage? {
        val uriKey = uri.toString()
        synchronized(baseImageCache) {
            baseImageCache.get(uriKey)?.let { return it }
        }

        val decoded = FaceImageDecoder.decodeOrientedImage(context, uri, maxDimension = 1280) ?: return null
        val result = CachedBaseImage(decoded.bitmap, decoded.originalWidth, decoded.originalHeight)
        synchronized(baseImageCache) {
            baseImageCache.put(uriKey, result)
        }
        return result
    }
}
