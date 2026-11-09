package com.amresalehin.emregallery.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import kotlin.math.max

/**
 * Common thumbnail and image caching utilities for Emre Gallery.
 * Unifies cache keys across all screens (Home, Albums, Favourites, Bin, Archive, Search)
 * so thumbnails are cached once, shared instantly across screens, and used as instantaneous
 * zero-latency placeholders when expanding the fullscreen media viewer.
 */
object ThumbnailUtils {
    const val THUMBNAIL_DIMENSION = 420

    fun mediaThumbnailKey(uri: String): String = "${uri}_thumb"

    fun buildThumbnailRequest(
        context: Context,
        uri: String,
        widthPx: Int = THUMBNAIL_DIMENSION,
        heightPx: Int = THUMBNAIL_DIMENSION,
        useRgb565: Boolean = false
    ): ImageRequest {
        val builder = ImageRequest.Builder(context)
            .data(Uri.parse(uri))
            .size(widthPx, heightPx)
            .precision(Precision.INEXACT)
            // Grid thumbnails should appear on the first available frame; the outline is already
            // visible, so a crossfade only adds perceived latency while rapidly scrolling.
            .crossfade(false)
            .diskCacheKey(mediaThumbnailKey(uri))
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCacheKey(mediaThumbnailKey(uri))
            .memoryCachePolicy(CachePolicy.ENABLED)
            .allowHardware(true)
            .allowRgb565(true)

        if (useRgb565) {
            builder.bitmapConfig(Bitmap.Config.RGB_565)
        }
        return builder.build()
    }

    fun buildViewerRequest(
        context: Context,
        uri: String,
        isVideo: Boolean
    ): ImageRequest {
        val builder = ImageRequest.Builder(context)
            .data(Uri.parse(uri))
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)

        if (isVideo) {
            builder
                .size(THUMBNAIL_DIMENSION, THUMBNAIL_DIMENSION)
                .precision(Precision.INEXACT)
                .crossfade(true)
                .crossfade(150)
                .diskCacheKey(mediaThumbnailKey(uri))
                .memoryCacheKey(mediaThumbnailKey(uri))
        } else {
            val dm = context.resources.displayMetrics
            val maxDim = max(dm.widthPixels, dm.heightPixels).coerceIn(1600, 2560)
            builder
                .size(maxDim, maxDim)
                .precision(Precision.INEXACT)
                .placeholderMemoryCacheKey(mediaThumbnailKey(uri))
                .diskCacheKey("${uri}:viewer-scaled")
                .memoryCacheKey("${uri}:viewer-scaled")
                .crossfade(true)
                .crossfade(150)
        }
        return builder.build()
    }
}
