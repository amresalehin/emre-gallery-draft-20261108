package com.amresalehin.emregallery.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class DownloadActivityStatus {
    DOWNLOADING,
    RETRYING,
    PAUSED,
    FAILED,
    FINISHED
}

data class DownloadActivityItem(
    val id: String,
    val workId: String? = null,
    val title: String,
    val mediaUrl: String,
    val thumbnailUrl: String? = null,
    val localUri: String? = null,
    val isVideo: Boolean = false,
    val isAudio: Boolean = false,
    val isStream: Boolean = false,
    val format: String = "MP4",
    val status: DownloadActivityStatus = DownloadActivityStatus.DOWNLOADING,
    val progress: Float = 0f,
    val bytesRead: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val errorMessage: String? = null,
    val timestampMillis: Long = System.currentTimeMillis(),
    val durationHint: String? = null,
    val retryCount: Int = 0,
    val maxRetries: Int = 5,
    val nextRetryTimestampMillis: Long? = null,
    val retryDelaySeconds: Long? = null,
    val stackId: String? = null,
    val stackTitle: String? = null,
    val sourceDomain: String? = null,
    val sourceUrl: String? = null
) {
    val isFailed: Boolean get() = status == DownloadActivityStatus.FAILED
    val isDownloading: Boolean get() = status == DownloadActivityStatus.DOWNLOADING
    val isFinished: Boolean get() = status == DownloadActivityStatus.FINISHED
    val isPaused: Boolean get() = status == DownloadActivityStatus.PAUSED
    val isRetrying: Boolean get() = status == DownloadActivityStatus.RETRYING
}

object DownloadActivityManager {
    private const val TAG = "DownloadActivityManager"
    private const val PREFS_NAME = "download_activity_tracker_prefs"
    private const val KEY_ACTIVITIES_JSON = "saved_activities_json"

    private val _activities = MutableStateFlow<List<DownloadActivityItem>>(emptyList())
    val activities: StateFlow<List<DownloadActivityItem>> = _activities.asStateFlow()

    private var appContext: Context? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val activeRetryJobs = ConcurrentHashMap<String, Job>()

    fun initialize(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            loadPersistedActivities()
            // 1. Sanitize any stale false 4K labels from persisted items
            // 2. Ensure any items marked failed prematurely from temporary 429 errors resume as RETRYING under downloading filter
            val cleaned = _activities.value.map { item ->
                var updated = item
                if (!item.isVideo && item.format.contains("4K", ignoreCase = true)) {
                    val clean = if (item.mediaUrl.lowercase().contains(".png") || item.title.lowercase().endsWith(".png")) "PNG" else "JPG"
                    updated = updated.copy(format = clean)
                }
                if (updated.status == DownloadActivityStatus.FAILED && isTemporaryFailure(-1, updated.errorMessage) && updated.retryCount < updated.maxRetries) {
                    val nextAttempt = updated.retryCount + 1
                    val delaySec = calculateRetryDelaySeconds(nextAttempt, null)
                    updated = updated.copy(
                        status = DownloadActivityStatus.RETRYING,
                        retryCount = nextAttempt,
                        maxRetries = 5,
                        nextRetryTimestampMillis = System.currentTimeMillis() + (delaySec * 1000L),
                        retryDelaySeconds = delaySec,
                        errorMessage = null
                    )
                }
                updated
            }
            _activities.value = cleaned
            syncWithStorageDir(context)
            persistActivities()
            syncWithInboxRepository()
            resumePersistedRetries(context.applicationContext)
        }
    }

    private fun resumePersistedRetries(context: Context) {
        val now = System.currentTimeMillis()
        val retryingItems = _activities.value.filter { it.status == DownloadActivityStatus.RETRYING }
        for (item in retryingItems) {
            val nextTime = item.nextRetryTimestampMillis
            if (nextTime != null) {
                val remainingMillis = nextTime - now
                val remainingSec = if (remainingMillis > 0) (remainingMillis + 999L) / 1000L else 0L
                scheduleRetryExecution(context, item.id, remainingSec)
            } else {
                scheduleRetryExecution(context, item.id, 0L)
            }
        }
    }

    private fun getPrefs(): SharedPreferences? {
        return appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun persistActivities() {
        val prefs = getPrefs() ?: return
        try {
            val jsonArray = JSONArray()
            // Persist all activity items including finished downloads up to 500
            val toSave = _activities.value.take(500)
            for (item in toSave) {
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("workId", item.workId ?: "")
                    put("title", item.title)
                    put("mediaUrl", item.mediaUrl)
                    put("thumbnailUrl", item.thumbnailUrl ?: "")
                    put("localUri", item.localUri ?: "")
                    put("isVideo", item.isVideo)
                    put("isAudio", item.isAudio)
                    put("isStream", item.isStream)
                    put("format", item.format)
                    put("status", item.status.name)
                    put("progress", item.progress.toDouble())
                    put("bytesRead", item.bytesRead)
                    put("totalBytes", item.totalBytes)
                    put("speedBytesPerSec", item.speedBytesPerSec)
                    put("errorMessage", item.errorMessage ?: "")
                    put("timestampMillis", item.timestampMillis)
                    put("durationHint", item.durationHint ?: "")
                    put("retryCount", item.retryCount)
                    put("maxRetries", item.maxRetries)
                    put("nextRetryTimestampMillis", item.nextRetryTimestampMillis ?: -1L)
                    put("retryDelaySeconds", item.retryDelaySeconds ?: -1L)
                    put("stackId", item.stackId ?: "")
                    put("stackTitle", item.stackTitle ?: "")
                    put("sourceDomain", item.sourceDomain ?: "")
                    put("sourceUrl", item.sourceUrl ?: "")
                }
                jsonArray.put(obj)
            }
            prefs.edit().putString(KEY_ACTIVITIES_JSON, jsonArray.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error persisting download activities", e)
        }
    }

    private fun loadPersistedActivities() {
        val prefs = getPrefs() ?: return
        val rawJson = prefs.getString(KEY_ACTIVITIES_JSON, null) ?: return
        try {
            val jsonArray = JSONArray(rawJson)
            val list = mutableListOf<DownloadActivityItem>()
            for (i in 0 until jsonArray.length()) {
                try {
                    val obj = jsonArray.getJSONObject(i)
                    val statusStr = obj.optString("status", DownloadActivityStatus.FAILED.name)
                    val status = runCatching { DownloadActivityStatus.valueOf(statusStr) }.getOrDefault(DownloadActivityStatus.FAILED)
                    val isVid = obj.optBoolean("isVideo", false)
                    val isAud = obj.optBoolean("isAudio", false)
                    val rawFmt = obj.optString("format", "").trim()
                    val cleanFormat = if (!isVid && rawFmt.contains("4K", ignoreCase = true)) {
                        if (isAud) "MP3" else "JPG"
                    } else if (rawFmt.isNotBlank()) {
                        rawFmt
                    } else {
                        when {
                            isVid -> "MP4"
                            isAud -> "MP3"
                            else -> "JPG"
                        }
                    }
                    val rawProgress = obj.optDouble("progress", 0.0).toFloat()
                    val safeProgress = if (rawProgress.isNaN() || rawProgress.isInfinite() || rawProgress < 0f) 0f else rawProgress.coerceIn(0f, 1f)

                    list.add(
                        DownloadActivityItem(
                            id = obj.optString("id").takeIf { it.isNotBlank() } ?: "persisted_${System.currentTimeMillis()}_$i",
                            workId = obj.optString("workId").takeIf { it.isNotBlank() },
                            title = obj.optString("title", "Media"),
                            mediaUrl = obj.optString("mediaUrl", ""),
                            thumbnailUrl = obj.optString("thumbnailUrl").takeIf { it.isNotBlank() },
                            localUri = obj.optString("localUri").takeIf { it.isNotBlank() },
                            isVideo = isVid,
                            isAudio = isAud,
                            isStream = obj.optBoolean("isStream", false),
                            format = cleanFormat,
                            status = status,
                            progress = safeProgress,
                            bytesRead = obj.optLong("bytesRead", 0L),
                            totalBytes = obj.optLong("totalBytes", 0L),
                            speedBytesPerSec = obj.optLong("speedBytesPerSec", 0L),
                            errorMessage = obj.optString("errorMessage").takeIf { it.isNotBlank() },
                            timestampMillis = obj.optLong("timestampMillis", System.currentTimeMillis()),
                            durationHint = obj.optString("durationHint").takeIf { it.isNotBlank() },
                            retryCount = obj.optInt("retryCount", 0),
                            maxRetries = obj.optInt("maxRetries", 5),
                            nextRetryTimestampMillis = obj.optLong("nextRetryTimestampMillis", -1L).takeIf { it > 0L },
                            retryDelaySeconds = obj.optLong("retryDelaySeconds", -1L).takeIf { it > 0L },
                            stackId = obj.optString("stackId").takeIf { it.isNotBlank() },
                            stackTitle = obj.optString("stackTitle").takeIf { it.isNotBlank() },
                            sourceDomain = obj.optString("sourceDomain").takeIf { it.isNotBlank() },
                            sourceUrl = obj.optString("sourceUrl").takeIf { it.isNotBlank() }
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing persisted item index $i", e)
                }
            }
            _activities.value = list.distinctBy { it.id }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading persisted download activities", e)
        }
    }

    /**
     * Synchronizes and merges finished items from the real MediaInboxRepository.
     */
    fun syncWithInboxRepository() {
        val ctx = appContext ?: return
        val repo = MediaInboxRepository.getInstance(ctx)
        val stacks = repo.stacks.value
        val finishedItems = mutableListOf<DownloadActivityItem>()
        val seenIds = mutableSetOf<String>()

        for (stack in stacks) {
            for (item in stack.items) {
                val candidateId = item.id.ifBlank { "${stack.id}_${item.localUri.hashCode()}" }
                val uniqueId = if (seenIds.add(candidateId)) {
                    candidateId
                } else {
                    val fallback = "${stack.id}_${candidateId}_${item.localUri.hashCode()}"
                    seenIds.add(fallback)
                    fallback
                }

                finishedItems.add(
                    DownloadActivityItem(
                        id = uniqueId,
                        title = item.title,
                        mediaUrl = item.sourceUrl,
                        thumbnailUrl = item.localUri,
                        localUri = item.localUri,
                        isVideo = item.isVideo,
                        isAudio = item.isAudio,
                        isStream = item.isStream,
                        format = item.format.ifBlank { if (item.isVideo) "MP4" else "JPEG" },
                        status = DownloadActivityStatus.FINISHED,
                        progress = 1.0f,
                        bytesRead = item.sizeBytes,
                        totalBytes = item.sizeBytes,
                        timestampMillis = item.dateAddedMillis,
                        durationHint = if (item.isVideo) "HD" else null,
                        stackId = stack.id,
                        stackTitle = stack.title
                    )
                )
            }
        }

        val currentActivities = _activities.value
        val existingFinished = currentActivities.filter { it.status == DownloadActivityStatus.FINISHED }
        val activeOrFailed = currentActivities.filter { it.status != DownloadActivityStatus.FINISHED }
        val activeIds = activeOrFailed.map { it.id }.toSet()
        val allFinished = (existingFinished + finishedItems).filter { it.id !in activeIds }.distinctBy { it.id }

        // Combined ordered: downloading first, retrying second, paused third, failed fourth, then finished newest first
        _activities.value = (activeOrFailed + allFinished).distinctBy { it.id }.sortedWith(
            compareBy<DownloadActivityItem> {
                when (it.status) {
                    DownloadActivityStatus.DOWNLOADING -> 0
                    DownloadActivityStatus.RETRYING -> 1
                    DownloadActivityStatus.PAUSED -> 2
                    DownloadActivityStatus.FAILED -> 3
                    DownloadActivityStatus.FINISHED -> 4
                }
            }.thenByDescending { it.timestampMillis }
        )
    }

    /**
     * Scans the download storage directory and synthesizes finished activity items
     * for any completed files saved on disk, ensuring past downloads always display.
     */
    fun syncWithStorageDir(context: Context? = appContext) {
        val ctx = context ?: return
        try {
            val storageDir = OkHttpDownloader.getTargetStorageDir(ctx)
            if (!storageDir.exists() || !storageDir.isDirectory) return
            val files = storageDir.listFiles() ?: return

            val current = _activities.value.toMutableList()
            val existingLocalUris = current.mapNotNull { it.localUri }.toSet()
            val existingTitles = current.map { it.title.lowercase(Locale.ROOT) }.toSet()

            var modified = false
            for (file in files) {
                if (file.isFile && file.length() > 0 && !file.name.endsWith(".download") && !file.name.endsWith(".tmp")) {
                    val fileUri = Uri.fromFile(file).toString()
                    val fileName = file.name
                    if (fileUri !in existingLocalUris && fileName.lowercase(Locale.ROOT) !in existingTitles) {
                        val isVideo = fileName.endsWith(".mp4", true) || fileName.endsWith(".webm", true) || fileName.endsWith(".mkv", true)
                        val isAudio = fileName.endsWith(".mp3", true) || fileName.endsWith(".m4a", true) || fileName.endsWith(".wav", true) || fileName.endsWith(".aac", true)
                        val ext = file.extension.uppercase(Locale.ROOT).ifBlank { if (isVideo) "MP4" else if (isAudio) "MP3" else "JPG" }

                        current.add(
                            DownloadActivityItem(
                                id = "storage_${file.name.hashCode()}_${file.lastModified()}",
                                title = fileName,
                                mediaUrl = fileUri,
                                thumbnailUrl = fileUri,
                                localUri = fileUri,
                                isVideo = isVideo,
                                isAudio = isAudio,
                                isStream = false,
                                format = ext,
                                status = DownloadActivityStatus.FINISHED,
                                progress = 1.0f,
                                bytesRead = file.length(),
                                totalBytes = file.length(),
                                timestampMillis = file.lastModified()
                            )
                        )
                        modified = true
                    }
                }
            }

            if (modified) {
                _activities.value = current.distinctBy { it.id }.sortedWith(
                    compareBy<DownloadActivityItem> {
                        when (it.status) {
                            DownloadActivityStatus.DOWNLOADING -> 0
                            DownloadActivityStatus.RETRYING -> 1
                            DownloadActivityStatus.PAUSED -> 2
                            DownloadActivityStatus.FAILED -> 3
                            DownloadActivityStatus.FINISHED -> 4
                        }
                    }.thenByDescending { it.timestampMillis }
                )
                persistActivities()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing with storage dir", e)
        }
    }

    fun recordQueued(
        id: String,
        title: String,
        mediaUrl: String,
        thumbnailUrl: String? = null,
        isVideo: Boolean = false,
        isAudio: Boolean = false,
        isStream: Boolean = false,
        format: String = "JPG",
        workId: String? = null,
        isRetry: Boolean = false,
        stackId: String? = null,
        stackTitle: String? = null,
        sourceDomain: String? = null,
        sourceUrl: String? = null
    ) {
        val current = _activities.value.toMutableList()
        val existing = current.firstOrNull { it.id == id }
        current.removeAll { it.id == id }
        current.add(
            0,
            DownloadActivityItem(
                id = id,
                workId = workId,
                title = title,
                mediaUrl = mediaUrl,
                thumbnailUrl = thumbnailUrl ?: mediaUrl,
                isVideo = isVideo,
                isAudio = isAudio,
                isStream = isStream,
                format = format.ifBlank { if (isVideo) "MP4" else "JPG" },
                status = DownloadActivityStatus.DOWNLOADING,
                progress = existing?.progress ?: 0.05f,
                bytesRead = existing?.bytesRead ?: 0L,
                totalBytes = existing?.totalBytes ?: 0L,
                retryCount = if (isRetry) (existing?.retryCount ?: 0) else 0,
                maxRetries = existing?.maxRetries ?: 5,
                timestampMillis = System.currentTimeMillis(),
                stackId = stackId ?: existing?.stackId,
                stackTitle = stackTitle ?: existing?.stackTitle,
                sourceDomain = sourceDomain ?: existing?.sourceDomain,
                sourceUrl = sourceUrl ?: existing?.sourceUrl
            )
        )
        _activities.value = current
        persistActivities()
    }

    fun recordBatchQueued(
        items: List<ScrapedMediaItem>,
        workIds: List<UUID>? = null,
        stackId: String? = null,
        stackTitle: String? = null,
        sourceDomain: String? = null,
        sourceUrl: String? = null
    ) {
        val current = _activities.value.toMutableList()
        val newItems = items.mapIndexed { index, item ->
            val wId = workIds?.getOrNull(index)?.toString()
            DownloadActivityItem(
                id = item.id,
                workId = wId,
                title = item.title,
                mediaUrl = item.url,
                thumbnailUrl = item.thumbnailUrl ?: item.url,
                isVideo = item.isVideo,
                isAudio = item.isAudio,
                isStream = item.isStream,
                format = item.format.ifBlank { if (item.isVideo) "MP4" else "JPG" },
                status = DownloadActivityStatus.DOWNLOADING,
                progress = 0.05f,
                timestampMillis = System.currentTimeMillis(),
                stackId = stackId,
                stackTitle = stackTitle,
                sourceDomain = sourceDomain,
                sourceUrl = sourceUrl
            )
        }
        val newIds = newItems.map { it.id }.toSet()
        current.removeAll { it.id in newIds }
        current.addAll(0, newItems)
        _activities.value = current
        persistActivities()
    }

    fun updateProgress(
        itemId: String,
        progress: Float,
        bytesRead: Long,
        totalBytes: Long,
        speedBytesPerSec: Long = 0L
    ) {
        val current = _activities.value
        val index = current.indexOfFirst { it.id == itemId }
        if (index >= 0) {
            val updated = current.toMutableList()
            val existing = updated[index]
            updated[index] = existing.copy(
                status = DownloadActivityStatus.DOWNLOADING,
                progress = progress.coerceIn(0f, 1f),
                bytesRead = bytesRead,
                totalBytes = if (totalBytes > 0) totalBytes else existing.totalBytes,
                speedBytesPerSec = speedBytesPerSec
            )
            _activities.value = updated
        }
    }

    fun markFinished(
        itemId: String,
        localUri: String,
        sizeBytes: Long
    ) {
        val current = _activities.value.toMutableList()
        val index = current.indexOfFirst { it.id == itemId }
        if (index >= 0) {
            val existing = current[index]
            current[index] = existing.copy(
                status = DownloadActivityStatus.FINISHED,
                progress = 1.0f,
                localUri = localUri,
                bytesRead = sizeBytes,
                totalBytes = sizeBytes,
                timestampMillis = System.currentTimeMillis()
            )
        }
        _activities.value = current
        persistActivities()
        syncWithInboxRepository()
    }

    /**
     * Determines whether an error is a transient failure eligible for automatic retry.
     * Handles HTTP 429 (Too Many Requests), HTTP 5xx server errors, and network/socket timeouts.
     */
    fun isTemporaryFailure(statusCode: Int, errorMessage: String?): Boolean {
        if (statusCode == 429) return true
        if (statusCode in listOf(500, 502, 503, 504)) return true
        if (statusCode == -1) {
            val msg = errorMessage?.lowercase(Locale.ROOT).orEmpty()
            if (msg.contains("timeout") ||
                msg.contains("reset") ||
                msg.contains("connection") ||
                msg.contains("failed to connect") ||
                msg.contains("network") ||
                msg.contains("socket") ||
                msg.contains("broken pipe") ||
                msg.contains("429")
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Calculates retry delay respecting server Retry-After header if present;
     * otherwise applies exponential backoff with jitter on the schedule:
     * Attempt 1: 2s
     * Attempt 2: 4s
     * Attempt 3: 8s
     * Attempt 4: 16s
     * Attempt 5: 32s
     * Jitter adds random duration (up to 50% of base), preventing server thundering-herd.
     */
    fun calculateRetryDelaySeconds(attempt: Int, retryAfterSeconds: Long?): Long {
        if (retryAfterSeconds != null && retryAfterSeconds > 0L) {
            return retryAfterSeconds.coerceIn(1L, 300L)
        }
        val baseDelays = listOf(2L, 4L, 8L, 16L, 32L)
        val index = (attempt - 1).coerceIn(0, baseDelays.size - 1)
        val base = baseDelays[index]
        val maxJitter = (base * 0.5).toLong().coerceAtLeast(1L)
        val jitter = (0..maxJitter).random()
        return base + jitter
    }

    /**
     * Handles download failure. Returns true if an automatic retry has been scheduled.
     * Stops automatic retries once max retries (5) are exhausted and permits manual retry.
     */
    fun handleDownloadFailure(
        context: Context,
        itemId: String,
        statusCode: Int,
        errorMessage: String?,
        retryAfterSeconds: Long?
    ): Boolean {
        val current = _activities.value.toMutableList()
        val index = current.indexOfFirst { it.id == itemId }
        if (index < 0) return false

        val item = current[index]
        if (item.status == DownloadActivityStatus.PAUSED ||
            item.status == DownloadActivityStatus.FINISHED ||
            item.errorMessage == "Cancelled by user"
        ) {
            Log.d(TAG, "Ignoring failure callback for item $itemId with status ${item.status}")
            return false
        }

        val isTransient = isTemporaryFailure(statusCode, errorMessage)
        val nextAttempt = item.retryCount + 1

        if (isTransient && nextAttempt <= item.maxRetries) {
            val delaySec = calculateRetryDelaySeconds(nextAttempt, retryAfterSeconds)
            val nextRetryEpoch = System.currentTimeMillis() + (delaySec * 1000L)
            current[index] = item.copy(
                status = DownloadActivityStatus.RETRYING,
                retryCount = nextAttempt,
                maxRetries = 5,
                nextRetryTimestampMillis = nextRetryEpoch,
                retryDelaySeconds = delaySec,
                errorMessage = null // Never mark as failed until all 5 automatic retries conclude
            )
            _activities.value = current
            persistActivities()
            scheduleRetryExecution(context, itemId, delaySec)
            return true
        } else {
            // Retries exhausted or non-transient error: stop automatic attempts, allow manual retry
            activeRetryJobs[itemId]?.cancel()
            activeRetryJobs.remove(itemId)
            current[index] = item.copy(
                status = DownloadActivityStatus.FAILED,
                nextRetryTimestampMillis = null,
                retryDelaySeconds = null,
                errorMessage = errorMessage ?: "Download failed"
            )
            _activities.value = current
            persistActivities()
            return false
        }
    }

    /**
     * Schedules retry execution with deduplication, preventing duplicate retry jobs.
     */
    fun scheduleRetryExecution(context: Context, itemId: String, delaySeconds: Long) {
        // Prevent duplicate retry requests
        activeRetryJobs[itemId]?.cancel()

        val job = scope.launch {
            if (delaySeconds > 0L) {
                delay(delaySeconds * 1000L)
            }
            val current = _activities.value.firstOrNull { it.id == itemId }
            if (current != null && current.status == DownloadActivityStatus.RETRYING) {
                executeScheduledRetry(context, current)
            }
            activeRetryJobs.remove(itemId)
        }
        activeRetryJobs[itemId] = job
    }

    private fun executeScheduledRetry(context: Context, item: DownloadActivityItem) {
        val current = _activities.value.toMutableList()
        val index = current.indexOfFirst { it.id == item.id }
        if (index < 0) return

        val existing = current[index]
        if (existing.status == DownloadActivityStatus.DOWNLOADING) return

        current[index] = existing.copy(
            status = DownloadActivityStatus.DOWNLOADING,
            nextRetryTimestampMillis = null,
            retryDelaySeconds = null
        )
        _activities.value = current
        persistActivities()

        val scraped = ScrapedMediaItem(
            id = item.id,
            url = item.mediaUrl,
            title = item.title,
            thumbnailUrl = item.thumbnailUrl,
            isVideo = item.isVideo,
            isAudio = item.isAudio,
            isStream = item.isStream,
            format = item.format
        )
        val workId = DownloadQueueManager.enqueueDownload(
            context = context,
            item = scraped,
            referer = item.sourceUrl,
            stackId = item.stackId,
            stackTitle = item.stackTitle,
            sourceDomain = item.sourceDomain,
            sourceUrl = item.sourceUrl,
            isRetry = true
        )
        val reloaded = _activities.value.toMutableList()
        val idx = reloaded.indexOfFirst { it.id == item.id }
        if (idx >= 0) {
            reloaded[idx] = reloaded[idx].copy(workId = workId.toString())
            _activities.value = reloaded
            persistActivities()
        }
    }

    fun markFailed(
        itemId: String,
        errorMessage: String
    ) {
        val current = _activities.value.toMutableList()
        val index = current.indexOfFirst { it.id == itemId }
        if (index >= 0) {
            val existing = current[index]
            current[index] = existing.copy(
                status = DownloadActivityStatus.FAILED,
                errorMessage = errorMessage
            )
        }
        _activities.value = current
        persistActivities()
    }

    fun pauseDownload(context: Context, itemId: String) {
        activeRetryJobs[itemId]?.cancel()
        activeRetryJobs.remove(itemId)
        val current = _activities.value.toMutableList()
        val index = current.indexOfFirst { it.id == itemId }
        if (index >= 0) {
            val existing = current[index]
            existing.workId?.let { wId ->
                runCatching {
                    DownloadQueueManager.cancelDownload(context, UUID.fromString(wId))
                }
            }
            current[index] = existing.copy(
                status = DownloadActivityStatus.PAUSED,
                nextRetryTimestampMillis = null,
                retryDelaySeconds = null
            )
            _activities.value = current
            persistActivities()
        }
    }

    fun resumeDownload(context: Context, itemId: String) {
        retryDownload(context, itemId)
    }

    fun retryDownload(context: Context, itemId: String) {
        val current = _activities.value.toMutableList()
        val index = current.indexOfFirst { it.id == itemId }
        if (index >= 0) {
            val item = current[index]
            // Cancel any pending automatic retry to prevent duplicate requests
            activeRetryJobs[itemId]?.cancel()
            activeRetryJobs.remove(itemId)

            val scraped = ScrapedMediaItem(
                id = item.id,
                url = item.mediaUrl,
                title = item.title,
                thumbnailUrl = item.thumbnailUrl,
                isVideo = item.isVideo,
                isAudio = item.isAudio,
                isStream = item.isStream,
                format = item.format
            )
            val newWorkId = DownloadQueueManager.enqueueDownload(
                context = context,
                item = scraped,
                referer = item.sourceUrl,
                stackId = item.stackId,
                stackTitle = item.stackTitle,
                sourceDomain = item.sourceDomain,
                sourceUrl = item.sourceUrl,
                isRetry = true
            )
            current[index] = item.copy(
                status = DownloadActivityStatus.DOWNLOADING,
                workId = newWorkId.toString(),
                progress = if (item.bytesRead > 0 && item.totalBytes > 0) item.bytesRead.toFloat() / item.totalBytes.toFloat() else 0.05f,
                errorMessage = null,
                retryCount = 0, // Reset attempt count on manual retry
                nextRetryTimestampMillis = null,
                retryDelaySeconds = null,
                timestampMillis = System.currentTimeMillis()
            )
            _activities.value = current
            persistActivities()
        }
    }

    fun retryAllFailed(context: Context) {
        val failed = _activities.value.filter { it.isFailed }
        for (f in failed) {
            retryDownload(context, f.id)
        }
    }

    fun deleteActivity(itemId: String) {
        activeRetryJobs[itemId]?.cancel()
        activeRetryJobs.remove(itemId)
        val current = _activities.value.toMutableList()
        current.removeAll { it.id == itemId }
        _activities.value = current
        persistActivities()
    }

    fun cancelAllActive(context: Context) {
        for ((_, job) in activeRetryJobs) {
            job.cancel()
        }
        activeRetryJobs.clear()
        DownloadQueueManager.cancelAll(context)
        val current = _activities.value.toMutableList()
        for (i in current.indices) {
            if (current[i].status == DownloadActivityStatus.DOWNLOADING ||
                current[i].status == DownloadActivityStatus.RETRYING ||
                current[i].status == DownloadActivityStatus.PAUSED
            ) {
                current[i] = current[i].copy(
                    status = DownloadActivityStatus.FAILED,
                    errorMessage = "Cancelled by user",
                    nextRetryTimestampMillis = null,
                    retryDelaySeconds = null
                )
            }
        }
        _activities.value = current
        persistActivities()
    }

    fun clearFinished() {
        val current = _activities.value.toMutableList()
        current.removeAll { it.status == DownloadActivityStatus.FINISHED }
        _activities.value = current
        persistActivities()
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2fGB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.2fMB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.1fKB", kb)
            else -> "$bytes B"
        }
    }

    fun formatSpeed(speedBytesPerSec: Long): String {
        if (speedBytesPerSec <= 0) return "0.00MB/s"
        val mb = speedBytesPerSec / (1024.0 * 1024.0)
        return if (mb >= 0.05) {
            String.format(Locale.US, "%.2fMB/s", mb)
        } else {
            val kb = speedBytesPerSec / 1024.0
            String.format(Locale.US, "%.1fKB/s", kb)
        }
    }

    fun formatDate(timestampMillis: Long): String {
        if (timestampMillis <= 0L) return "Recent"
        val now = System.currentTimeMillis()
        val diff = now - timestampMillis
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val timeStr = runCatching { timeFormat.format(Date(timestampMillis)) }.getOrDefault("")

        return when {
            diff in 0..(24 * 3600 * 1000L) -> if (timeStr.isNotBlank()) "Today, $timeStr" else "Today"
            diff in (24 * 3600 * 1000L)..(48 * 3600 * 1000L) -> if (timeStr.isNotBlank()) "Yesterday, $timeStr" else "Yesterday"
            else -> {
                runCatching {
                    val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                    dateFormat.format(Date(timestampMillis))
                }.getOrDefault("Recent")
            }
        }
    }
}
