package com.amresalehin.emregallery

import android.app.Application
import android.content.Context
import coil.Coil
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.amresalehin.emregallery.data.MediaStoreThumbnailFetcher
import com.amresalehin.emregallery.data.OkHttpDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class GalleryApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val isLowRam = activityManager?.isLowRamDevice ?: false

        // Unconstrained memory caching: Allocate up to 45% (30% on low-RAM) of JVM heap to Coil.
        // With largeHeap enabled, this unlocks hundreds of megabytes for raw pre-decoded bitmaps,
        // avoiding recurring decompression bottlenecks during 120Hz continuous scrolling.
        val cachePercent = if (isLowRam) 0.30 else 0.45

        // Unconstrained concurrency: Utilize full Dispatchers without artificial core-capping
        // so that multi-core processors decode thumbnails at maximum throughput.
        val imageIoDispatcher = Dispatchers.IO
        val imageComputeDispatcher = Dispatchers.Default

        val imageLoader = ImageLoader.Builder(this)
            .okHttpClient { OkHttpDownloader.client }
            .fetcherDispatcher(imageIoDispatcher)
            .decoderDispatcher(imageComputeDispatcher)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(cachePercent)
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "image_cache"))
                    .maxSizeBytes(1073741824L) // 1GB disk cache
                    .build()
            }
            .components {
                add(MediaStoreThumbnailFetcher.Factory(this@GalleryApplication))
                add(VideoFrameDecoder.Factory())
            }
            .bitmapConfig(if (isLowRam) android.graphics.Bitmap.Config.RGB_565 else android.graphics.Bitmap.Config.ARGB_8888)
            .allowRgb565(true)
            .allowHardware(true)
            .crossfade(true)
            .crossfade(150)
            .respectCacheHeaders(false)
            .build()
        Coil.setImageLoader(imageLoader)

        // Deterministic registration of both face recognition engines
        com.amresalehin.emregallery.face.FaceEngineRegistry.register(com.amresalehin.emregallery.face.BuffaloLFaceEngineFactory)
        com.amresalehin.emregallery.face.FaceEngineRegistry.register(com.amresalehin.emregallery.face.EnteFaceEngineFactory)
        com.amresalehin.emregallery.face.FaceProcessingCoordinator.init(this)

        // Initialize Chaquopy Python SDK and gallery-dl media engine asynchronously in the background.
        // Running this off the UI thread eliminates the 2-4s main thread blockage on cold start,
        // allowing activities to launch instantly.
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            com.amresalehin.emregallery.data.GalleryDlEngine.init(this@GalleryApplication)
        }

        // Initialize DownloadActivityManager in background to avoid synchronous disk I/O on UI thread
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            com.amresalehin.emregallery.data.DownloadActivityManager.initialize(this@GalleryApplication)
        }

        // Defer chromium cookie initialization so it never stalls cold start
        warmupChromium(this)
    }

    companion object {
        @Volatile
        private var isChromiumWarmedUp = false

        fun warmupChromium(context: Context) {
            if (isChromiumWarmedUp) return
            runCatching {
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                handler.post {
                    if (isChromiumWarmedUp) return@post
                    val doWarmup = {
                        if (!isChromiumWarmedUp) {
                            try {
                                val cm = android.webkit.CookieManager.getInstance()
                                cm.setAcceptCookie(true)
                                val dummy = android.webkit.WebView(context.applicationContext)
                                dummy.destroy()
                                isChromiumWarmedUp = true
                            } catch (_: Throwable) {}
                        }
                    }
                    runCatching {
                        android.os.Looper.myQueue().addIdleHandler {
                            doWarmup()
                            false
                        }
                    }
                    handler.postDelayed({
                        doWarmup()
                    }, 1200L)
                }
            }
        }
    }

    /**
     * Emre never limits itself when Android is not limiting it.
     * When Android signals genuine memory constraints via onTrimMemory,
     * memory caches are trimmed or cleared according to severity.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        runCatching {
            val coilCache = Coil.imageLoader(this).memoryCache
            when {
                level >= TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_COMPLETE -> {
                    coilCache?.clear()
                    com.amresalehin.emregallery.face.FaceThumbnailHelper.clearCache()
                }
                level >= TRIM_MEMORY_RUNNING_LOW || level >= TRIM_MEMORY_BACKGROUND -> {
                    coilCache?.trimMemory(level)
                    com.amresalehin.emregallery.face.FaceThumbnailHelper.trimMemory(level)
                }
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runCatching {
            Coil.imageLoader(this).memoryCache?.clear()
            com.amresalehin.emregallery.face.FaceThumbnailHelper.clearCache()
        }
    }
}

