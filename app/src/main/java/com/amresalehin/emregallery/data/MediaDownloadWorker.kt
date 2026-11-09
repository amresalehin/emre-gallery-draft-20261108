package com.amresalehin.emregallery.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * Persistent background downloader worker powered by Android Jetpack WorkManager.
 * Runs as a foreground service with a unique notification ID to ensure uninterrupted downloading.
 * Incorporates byte-level progress reporting, HTTP Range resumable downloading, headers/cookies propagation,
 * and social video remuxing with yt-dlp.
 */
class MediaDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private fun getWorkerNotificationId(key: String): Int {
        return 4001 + (abs(key.hashCode()) % 50000)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val itemId = inputData.getString(KEY_ITEM_ID) ?: id.toString()
        val title = inputData.getString(KEY_TITLE) ?: inputData.getString(KEY_FILE_NAME) ?: "Media"
        return createSilentForegroundInfo(title, getWorkerNotificationId(itemId))
    }

    private fun createSilentForegroundInfo(title: String, notificationId: Int): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (notificationManager.getNotificationChannel(SILENT_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    SILENT_CHANNEL_ID,
                    "Media Downloads",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Silent background media downloads"
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
                notificationManager.createNotificationChannel(channel)
            }
        }

        val notification = NotificationCompat.Builder(applicationContext, SILENT_CHANNEL_ID)
            .setContentTitle("Downloading media")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val mediaUrl = inputData.getString(KEY_MEDIA_URL) ?: return@withContext Result.failure()
        val fileName = inputData.getString(KEY_FILE_NAME) ?: "media_${System.currentTimeMillis()}"
        val referer = inputData.getString(KEY_REFERER)
        val itemId = inputData.getString(KEY_ITEM_ID) ?: "item_${System.currentTimeMillis()}"
        val title = inputData.getString(KEY_TITLE) ?: fileName
        val isVideo = inputData.getBoolean(KEY_IS_VIDEO, false)
        val isAudio = inputData.getBoolean(KEY_IS_AUDIO, false)
        val isStream = inputData.getBoolean(KEY_IS_STREAM, false)
        val format = inputData.getString(KEY_FORMAT) ?: ""
        val stackId = inputData.getString(KEY_STACK_ID) ?: "stack_${System.currentTimeMillis()}"
        val stackTitle = inputData.getString(KEY_STACK_TITLE) ?: "Discovered Media"
        val sourceDomain = inputData.getString(KEY_SOURCE_DOMAIN) ?: ""
        val sourceUrl = inputData.getString(KEY_SOURCE_URL) ?: mediaUrl
        val sourceEngine = inputData.getString(KEY_SOURCE_ENGINE) ?: ""
        val headersJson = inputData.getString(KEY_HEADERS_JSON)

        val downloadHeaders: Map<String, String> = runCatching {
            if (!headersJson.isNullOrBlank()) {
                val obj = JSONObject(headersJson)
                val map = mutableMapOf<String, String>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = obj.getString(k)
                }
                map
            } else emptyMap()
        }.getOrDefault(emptyMap())

        val cookieString = OkHttpDownloader.sharedCookieJar.getCookieString(mediaUrl).takeIf { it.isNotBlank() }
            ?: downloadHeaders["Cookie"]

        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            Log.w(TAG, "Could not set silent foreground service notification: ${e.message}")
        }

        setProgress(workDataOf(KEY_PROGRESS to 0.05f, KEY_STATUS to "Initializing download for $title..."))

        try {
            var downloadedFile: File? = null
            var errorDiagnostic: String? = null

            setProgress(workDataOf(KEY_PROGRESS to 0.15f, KEY_STATUS to "Downloading $title..."))
            DownloadActivityManager.updateProgress(itemId, 0.15f, 0L, 0L, 0L)

            val isDirectHttpAsset = isVideo.not() && isStream.not() && (
                    mediaUrl.startsWith("http://", ignoreCase = true) ||
                    mediaUrl.startsWith("https://", ignoreCase = true) ||
                    mediaUrl.startsWith("data:image/", ignoreCase = true)
            ) && (
                sourceEngine.contains("HTML", ignoreCase = true) ||
                sourceEngine.contains("DOM", ignoreCase = true) ||
                sourceEngine.contains("Network", ignoreCase = true) ||
                sourceEngine.contains("Srcset", ignoreCase = true) ||
                sourceEngine.contains("Direct", ignoreCase = true) ||
                mediaUrl.contains(".jpg", ignoreCase = true) ||
                mediaUrl.contains(".png", ignoreCase = true) ||
                mediaUrl.contains(".webp", ignoreCase = true) ||
                mediaUrl.contains(".gif", ignoreCase = true) ||
                mediaUrl.contains(".svg", ignoreCase = true) ||
                mediaUrl.contains(".mp3", ignoreCase = true) ||
                mediaUrl.contains(".m4a", ignoreCase = true) ||
                mediaUrl.contains(".wav", ignoreCase = true) ||
                mediaUrl.contains(".flac", ignoreCase = true) ||
                (mediaUrl.contains(".mp4", ignoreCase = true) && !sourceUrl.contains("youtube.com", ignoreCase = true) && !sourceUrl.contains("youtu.be", ignoreCase = true))
            )

            val targetUrl = if (sourceUrl.contains("youtube.com", ignoreCase = true) || sourceUrl.contains("youtu.be", ignoreCase = true)) {
                sourceUrl
            } else {
                mediaUrl
            }

            // Strategy 1: Direct HTTP media via OkHttp (fast path, byte-level ranges, resumable)
            if (isDirectHttpAsset) {
                setProgress(workDataOf(KEY_PROGRESS to 0.25f, KEY_STATUS to "Downloading via OkHttp: $title..."))
                val okResult = OkHttpDownloader.downloadMediaFileDetailed(
                    context = applicationContext,
                    mediaUrl = mediaUrl,
                    fileName = fileName,
                    referer = referer ?: sourceUrl,
                    downloadId = itemId,
                    headers = downloadHeaders,
                    onProgress = { bytesRead, totalBytes ->
                        val ratio = if (totalBytes > 0L) (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0.1f, 0.95f) else 0.5f
                        DownloadActivityManager.updateProgress(itemId, ratio, bytesRead, totalBytes, 0L)
                    }
                )
                if (okResult.isSuccessful && okResult.file != null && okResult.file.exists() && okResult.file.length() > 0L) {
                    downloadedFile = okResult.file
                }
            }

            // Strategy 2: Originating extractor (yt-dlp or gallery-dl) when required or as fallback
            if ((downloadedFile == null || !downloadedFile.exists() || downloadedFile.length() == 0L) && GalleryDlEngine.isAvailable()) {
                setProgress(workDataOf(KEY_PROGRESS to 0.35f, KEY_STATUS to "Downloading via extractor engine: $title..."))
                downloadedFile = GalleryDlEngine.downloadMedia(
                    context = applicationContext,
                    mediaUrl = targetUrl,
                    fileName = fileName,
                    referer = referer ?: sourceUrl,
                    headers = downloadHeaders,
                    cookies = cookieString
                )
                // If targetUrl != mediaUrl and extractor returned null, retry with mediaUrl directly
                if ((downloadedFile == null || !downloadedFile.exists() || downloadedFile.length() == 0L) && targetUrl != mediaUrl) {
                    downloadedFile = GalleryDlEngine.downloadMedia(
                        context = applicationContext,
                        mediaUrl = mediaUrl,
                        fileName = fileName,
                        referer = referer ?: sourceUrl,
                        headers = downloadHeaders,
                        cookies = cookieString
                    )
                }
            }

            // Strategy 3: Final fallback to OkHttp for any URL that specialized extractors couldn't capture
            if ((downloadedFile == null || !downloadedFile.exists() || downloadedFile.length() == 0L) && !isDirectHttpAsset) {
                setProgress(workDataOf(KEY_PROGRESS to 0.5f, KEY_STATUS to "Trying direct download fallback: $title..."))
                val fallbackResult = OkHttpDownloader.downloadMediaFileDetailed(
                    context = applicationContext,
                    mediaUrl = mediaUrl,
                    fileName = fileName,
                    referer = referer ?: sourceUrl,
                    downloadId = itemId,
                    headers = downloadHeaders
                )
                if (fallbackResult.isSuccessful && fallbackResult.file != null && fallbackResult.file.exists() && fallbackResult.file.length() > 0L) {
                    downloadedFile = fallbackResult.file
                } else if (errorDiagnostic == null) {
                    errorDiagnostic = fallbackResult.errorMessage
                }
            }

            if (isStopped) {
                Log.d(TAG, "MediaDownloadWorker was stopped for $mediaUrl")
                return@withContext Result.failure()
            }

            if (downloadedFile != null && downloadedFile.exists() && downloadedFile.length() > 0L) {
                setProgress(workDataOf(KEY_PROGRESS to 1.0f, KEY_STATUS to "Saved successfully"))

                DownloadActivityManager.markFinished(
                    itemId = itemId,
                    localUri = downloadedFile.absolutePath,
                    sizeBytes = downloadedFile.length()
                )

                Result.success(
                    workDataOf(
                        KEY_LOCAL_URI to downloadedFile.absolutePath,
                        KEY_SIZE_BYTES to downloadedFile.length(),
                        KEY_ITEM_ID to itemId
                    )
                )
            } else {
                val finalError = errorDiagnostic ?: "Download failed via yt-dlp and gallery-dl for $mediaUrl"
                val currentItem = DownloadActivityManager.activities.value.firstOrNull { it.id == itemId }
                val isPausedOrCancelled = isStopped ||
                    currentItem?.status == DownloadActivityStatus.PAUSED ||
                    currentItem?.errorMessage == "Cancelled by user"

                if (!isPausedOrCancelled) {
                    DownloadActivityManager.handleDownloadFailure(
                        context = applicationContext,
                        itemId = itemId,
                        statusCode = -1,
                        errorMessage = finalError,
                        retryAfterSeconds = null
                    )
                }
                Result.failure(workDataOf(KEY_ERROR to finalError))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in MediaDownloadWorker for $mediaUrl", e)
            val err = e.message ?: "Unknown error"
            val currentItem = DownloadActivityManager.activities.value.firstOrNull { it.id == itemId }
            val isPausedOrCancelled = isStopped ||
                e is kotlinx.coroutines.CancellationException ||
                currentItem?.status == DownloadActivityStatus.PAUSED ||
                currentItem?.errorMessage == "Cancelled by user"

            if (!isPausedOrCancelled) {
                DownloadActivityManager.handleDownloadFailure(
                    context = applicationContext,
                    itemId = itemId,
                    statusCode = -1,
                    errorMessage = err,
                    retryAfterSeconds = null
                )
            }
            Result.failure(workDataOf(KEY_ERROR to err))
        } finally {
            DownloadQueueManager.markDownloadCompleted(itemId)
        }
    }

    companion object {
        const val TAG = "MediaDownloadWorker"
        const val WORK_TAG_DOWNLOAD = "media_download"

        const val KEY_MEDIA_URL = "media_url"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_REFERER = "referer"
        const val KEY_ITEM_ID = "item_id"
        const val KEY_TITLE = "title"
        const val KEY_IS_VIDEO = "is_video"
        const val KEY_IS_AUDIO = "is_audio"
        const val KEY_IS_STREAM = "is_stream"
        const val KEY_FORMAT = "format"
        const val KEY_STACK_ID = "stack_id"
        const val KEY_STACK_TITLE = "stack_title"
        const val KEY_SOURCE_DOMAIN = "source_domain"
        const val KEY_SOURCE_URL = "source_url"
        const val KEY_SOURCE_ENGINE = "source_engine"
        const val KEY_HEADERS_JSON = "headers_json"

        const val KEY_PROGRESS = "progress"
        const val KEY_BYTES_READ = "bytes_read"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_STATUS = "status"
        const val KEY_LOCAL_URI = "local_uri"
        const val KEY_SIZE_BYTES = "size_bytes"
        const val KEY_ERROR = "error"

        const val SILENT_CHANNEL_ID = "media_download_silent_channel"
        const val NOTIFICATION_ID = 4001
    }
}
