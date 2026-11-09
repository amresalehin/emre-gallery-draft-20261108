package com.amresalehin.emregallery.data

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MediaRepository(private val resolver: ContentResolver) {

    fun loadMedia(): List<MediaItem> {
        val rows = ArrayList<MediaItem>()
        queryCollectionInternal(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false) { rows.add(it) }
        queryCollectionInternal(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true) { rows.add(it) }
        return rows
    }

    suspend fun loadMediaProgressive(
        onBatch: suspend (items: List<MediaItem>, isComplete: Boolean) -> Unit
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        val collected = ArrayList<MediaItem>()
        var lastEmitTime = System.currentTimeMillis()
        val emitMutex = Mutex()

        coroutineScope {
            val imageJob = launch {
                queryCollectionSuspend(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, video = false) { item ->
                    val snapshot = synchronized(collected) {
                        collected.add(item)
                        val count = collected.size
                        val now = System.currentTimeMillis()
                        if (count == 40 || count == 120 || count == 250 || (count > 250 && count % 350 == 0) || (now - lastEmitTime >= 150L && count >= 30)) {
                            lastEmitTime = now
                            ArrayList(collected)
                        } else null
                    }
                    if (snapshot != null) {
                        emitMutex.withLock {
                            onBatch(snapshot, false)
                        }
                    }
                }
            }

            val videoJob = launch {
                queryCollectionSuspend(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, video = true) { item ->
                    val snapshot = synchronized(collected) {
                        collected.add(item)
                        val count = collected.size
                        val now = System.currentTimeMillis()
                        if (count == 40 || count == 120 || count == 250 || (count > 250 && count % 350 == 0) || (now - lastEmitTime >= 150L && count >= 30)) {
                            lastEmitTime = now
                            ArrayList(collected)
                        } else null
                    }
                    if (snapshot != null) {
                        emitMutex.withLock {
                            onBatch(snapshot, false)
                        }
                    }
                }
            }

            imageJob.join()
            videoJob.join()
        }

        val finalResult = synchronized(collected) { ArrayList(collected) }
        emitMutex.withLock {
            onBatch(finalResult, true)
        }
        finalResult
    }

    private fun queryCollectionInternal(collection: Uri, video: Boolean, onItem: (MediaItem) -> Unit) {
        val cursor = buildCursor(collection, video)
        cursor?.use { c ->
            readItemsFromCursor(c, collection, video, onItem)
        }
    }

    private suspend fun queryCollectionSuspend(collection: Uri, video: Boolean, onItem: suspend (MediaItem) -> Unit) {
        val cursor = buildCursor(collection, video)
        cursor?.use { c ->
            val idCol = c.getColumnIndex(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeCol = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val addedCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
            val sizeCol = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val widthCol = c.getColumnIndex(MediaStore.MediaColumns.WIDTH)
            val heightCol = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
            val pathCol = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            val takenCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
            val modifiedCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val durationCol = if (video) c.getColumnIndex(MediaStore.Video.VideoColumns.DURATION) else -1
            val trashedCol = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                c.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
            } else -1

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val itemUri = ContentUris.withAppendedId(collection, id)
                val name = c.getString(nameCol) ?: "Untitled"
                val mimeType = if (mimeCol >= 0) c.getString(mimeCol) else null
                val added = if (addedCol >= 0) c.getLong(addedCol) else 0L
                val taken = if (takenCol >= 0 && !c.isNull(takenCol)) c.getLong(takenCol) else 0L
                val modified = if (modifiedCol >= 0 && !c.isNull(modifiedCol)) c.getLong(modifiedCol) else 0L
                val size = if (sizeCol >= 0) c.getLong(sizeCol) else 0L
                val width = if (widthCol >= 0) c.getInt(widthCol) else 0
                val height = if (heightCol >= 0) c.getInt(heightCol) else 0
                val path = if (pathCol >= 0) c.getString(pathCol) ?: "" else ""
                val duration = if (durationCol >= 0 && !c.isNull(durationCol)) c.getLong(durationCol) else 0L
                val isTrashed = if (trashedCol >= 0 && !c.isNull(trashedCol)) c.getInt(trashedCol) != 0 else false

                if (!isSupportedMedia(name, mimeType, video)) {
                    continue
                }

                onItem(
                    MediaItem(
                        id = id,
                        uri = itemUri.toString(),
                        name = name,
                        mimeType = mimeType,
                        dateAddedSeconds = added,
                        dateTakenMillis = taken,
                        sizeBytes = size,
                        width = width,
                        height = height,
                        relativePath = path,
                        isVideo = video,
                        durationMillis = duration,
                        isTrashed = isTrashed,
                        dateModifiedSeconds = modified
                    )
                )
            }
        }
    }

    private fun buildCursor(collection: Uri, video: Boolean): android.database.Cursor? {
        val projectionList = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            add(MediaStore.MediaColumns.RELATIVE_PATH)
            add(MediaStore.MediaColumns.DATE_TAKEN)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            if (video) {
                add(MediaStore.Video.VideoColumns.DURATION)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                add(MediaStore.MediaColumns.IS_TRASHED)
            }
        }
        val projection = projectionList.toTypedArray()
        val sort = "${MediaStore.MediaColumns.DATE_ADDED} DESC"

        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val queryArgs = android.os.Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
            }
            runCatching { resolver.query(collection, projection, queryArgs, null) }.getOrNull()
                ?: resolver.query(collection, projection, null, null, sort)
        } else {
            resolver.query(collection, projection, null, null, sort)
        }
    }

    private fun readItemsFromCursor(
        c: android.database.Cursor,
        collection: Uri,
        video: Boolean,
        onItem: (MediaItem) -> Unit
    ) {
        val idCol = c.getColumnIndex(MediaStore.MediaColumns._ID)
        val nameCol = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
        val mimeCol = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
        val addedCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
        val sizeCol = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
        val widthCol = c.getColumnIndex(MediaStore.MediaColumns.WIDTH)
        val heightCol = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
        val pathCol = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
        val takenCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
        val modifiedCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
        val durationCol = if (video) c.getColumnIndex(MediaStore.Video.VideoColumns.DURATION) else -1
        val trashedCol = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            c.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
        } else -1

        while (c.moveToNext()) {
            val id = c.getLong(idCol)
            val itemUri = ContentUris.withAppendedId(collection, id)
            val name = c.getString(nameCol) ?: "Untitled"
            val mimeType = if (mimeCol >= 0) c.getString(mimeCol) else null
            val added = if (addedCol >= 0) c.getLong(addedCol) else 0L
            val taken = if (takenCol >= 0 && !c.isNull(takenCol)) c.getLong(takenCol) else 0L
            val modified = if (modifiedCol >= 0 && !c.isNull(modifiedCol)) c.getLong(modifiedCol) else 0L
            val size = if (sizeCol >= 0) c.getLong(sizeCol) else 0L
            val width = if (widthCol >= 0) c.getInt(widthCol) else 0
            val height = if (heightCol >= 0) c.getInt(heightCol) else 0
            val path = if (pathCol >= 0) c.getString(pathCol) ?: "" else ""
            val duration = if (durationCol >= 0 && !c.isNull(durationCol)) c.getLong(durationCol) else 0L
            val isTrashed = if (trashedCol >= 0 && !c.isNull(trashedCol)) c.getInt(trashedCol) != 0 else false

            if (!isSupportedMedia(name, mimeType, video)) {
                continue
            }

            onItem(
                MediaItem(
                    id = id,
                    uri = itemUri.toString(),
                    name = name,
                    mimeType = mimeType,
                    dateAddedSeconds = added,
                    dateTakenMillis = taken,
                    sizeBytes = size,
                    width = width,
                    height = height,
                    relativePath = path,
                    isVideo = video,
                    durationMillis = duration,
                    isTrashed = isTrashed,
                    dateModifiedSeconds = modified
                )
            )
        }
    }

    companion object {
        private val NON_MEDIA_EXTENSIONS = setOf(
            "xml", "txt", "json", "html", "htm", "css", "js", "bin", "pdf",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip", "rar", "7z", "tar", "gz",
            "apk", "dat", "db", "log", "sqlite", "properties", "yaml", "yml", "ini", "cfg",
            "sh", "py", "java", "kt", "class", "dex", "tmp", "temp", "bak", "nomedia",
            "srt", "vtt", "sub", "lrc", "meta", "downloading", "part", "crdownload"
        )

        private val VALID_IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif",
            "dng", "cr2", "nef", "arw", "raw", "ico", "svg"
        )

        private val VALID_VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "avi", "mov", "3gp", "flv", "m4v", "ts", "wmv", "mpg", "mpeg"
        )

        fun isSupportedMedia(name: String, mimeType: String?, isVideo: Boolean): Boolean {
            val cleanName = name.trim().lowercase(java.util.Locale.ROOT)
            val ext = cleanName.substringAfterLast('.', "")
            if (ext in NON_MEDIA_EXTENSIONS) return false

            val mime = mimeType?.trim()?.lowercase(java.util.Locale.ROOT)
            if (mime != null) {
                if (mime == "text/xml" || mime == "application/xml" ||
                    (mime.contains("xml") && !mime.contains("svg")) ||
                    mime == "text/plain" || mime == "application/json" || mime == "text/html" ||
                    mime == "application/zip" || mime == "application/pdf" || mime.startsWith("text/")) {
                    return false
                }
            }

            return if (isVideo) {
                ext in VALID_VIDEO_EXTENSIONS || (mime != null && mime.startsWith("video/"))
            } else {
                ext in VALID_IMAGE_EXTENSIONS || (mime != null && mime.startsWith("image/"))
            }
        }
    }
}
