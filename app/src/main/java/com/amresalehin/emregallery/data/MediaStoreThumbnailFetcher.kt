package com.amresalehin.emregallery.data

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.provider.MediaStore
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import coil.size.Dimension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Coil adapter for Android's MediaStore thumbnail API, backed by the app's prepared-thumbnail
 * disk cache. The first miss loads MediaStore on IO; subsequent visits decode the compact WebP
 * file and don't need to ask MediaStore to generate the same thumbnail again.
 */
class MediaStoreThumbnailFetcher(
    private val context: Context,
    private val uri: Uri,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val widthDim = options.size.width
        val heightDim = options.size.height
        if (widthDim !is Dimension.Pixels || heightDim !is Dimension.Pixels) {
            return@withContext null
        }

        val widthPx = widthDim.px
        val heightPx = heightDim.px
        if (widthPx > 1024 || heightPx > 1024) return@withContext null

        val prepared = PreparedThumbnailCache.loadOrCreate(context, uri, widthPx, heightPx)
            ?: return@withContext null

        DrawableResult(
            drawable = BitmapDrawable(context.resources, prepared.bitmap),
            isSampled = true,
            dataSource = DataSource.DISK
        )
    }

    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme == "content" &&
                (data.authority == "media" || data.authority == MediaStore.AUTHORITY)
            ) {
                val widthDim = options.size.width
                val heightDim = options.size.height
                if (widthDim is Dimension.Pixels && heightDim is Dimension.Pixels &&
                    widthDim.px <= 1024 && heightDim.px <= 1024
                ) {
                    return MediaStoreThumbnailFetcher(context, data, options)
                }
            }
            return null
        }
    }
}
