package com.amresalehin.emregallery.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import coil.Coil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Dedicated, independent repository for Pocket bookmarks and saved web reading materials.
 * Decoupled completely from Downloader and MediaInbox functionality.
 * Supports automatic background fetching of link titles, descriptions, previews, and all site media.
 */
class PocketRepository(
    private val context: Context,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("pocket_bookmarks_prefs", Context.MODE_PRIVATE)

    private val _bookmarks = MutableStateFlow<List<MediaInboxStack>>(emptyList())
    val bookmarks: StateFlow<List<MediaInboxStack>> = _bookmarks.asStateFlow()

    private val _showSvg = MutableStateFlow(prefs.getBoolean(KEY_SHOW_SVG, false))
    val showSvg: StateFlow<Boolean> = _showSvg.asStateFlow()

    private val _showVideos = MutableStateFlow(prefs.getBoolean(KEY_SHOW_VIDEOS, true))
    val showVideos: StateFlow<Boolean> = _showVideos.asStateFlow()

    private val _showStreams = MutableStateFlow(prefs.getBoolean(KEY_SHOW_STREAMS, true))
    val showStreams: StateFlow<Boolean> = _showStreams.asStateFlow()

    private val _showPng = MutableStateFlow(prefs.getBoolean(KEY_SHOW_PNG, true))
    val showPng: StateFlow<Boolean> = _showPng.asStateFlow()

    private val _showGif = MutableStateFlow(prefs.getBoolean(KEY_SHOW_GIF, true))
    val showGif: StateFlow<Boolean> = _showGif.asStateFlow()

    private val _minWidth = MutableStateFlow(
        prefs.getInt(KEY_MIN_WIDTH, DEFAULT_MIN_WIDTH).let {
            if (it <= 0 && !prefs.getBoolean("pocket_dim_filter_explicitly_set_zero", false)) DEFAULT_MIN_WIDTH else it
        }
    )
    val minWidth: StateFlow<Int> = _minWidth.asStateFlow()

    private val _minHeight = MutableStateFlow(
        prefs.getInt(KEY_MIN_HEIGHT, DEFAULT_MIN_HEIGHT).let {
            if (it <= 0 && !prefs.getBoolean("pocket_dim_filter_explicitly_set_zero", false)) DEFAULT_MIN_HEIGHT else it
        }
    )
    val minHeight: StateFlow<Int> = _minHeight.asStateFlow()

    private val _minSizeKb = MutableStateFlow(prefs.getInt(KEY_MIN_SIZE_KB, DEFAULT_MIN_SIZE_KB))
    val minSizeKb: StateFlow<Int> = _minSizeKb.asStateFlow()

    fun setShowSvg(enabled: Boolean) {
        _showSvg.value = enabled
        prefs.edit().putBoolean(KEY_SHOW_SVG, enabled).apply()
    }

    fun setShowVideos(enabled: Boolean) {
        _showVideos.value = enabled
        prefs.edit().putBoolean(KEY_SHOW_VIDEOS, enabled).apply()
    }

    fun setShowStreams(enabled: Boolean) {
        _showStreams.value = enabled
        prefs.edit().putBoolean(KEY_SHOW_STREAMS, enabled).apply()
    }

    fun setShowPng(enabled: Boolean) {
        _showPng.value = enabled
        prefs.edit().putBoolean(KEY_SHOW_PNG, enabled).apply()
    }

    fun setShowGif(enabled: Boolean) {
        _showGif.value = enabled
        prefs.edit().putBoolean(KEY_SHOW_GIF, enabled).apply()
    }

    fun setMinWidth(width: Int) {
        _minWidth.value = width
        prefs.edit()
            .putInt(KEY_MIN_WIDTH, width)
            .putBoolean("pocket_dim_filter_explicitly_set_zero", width == 0)
            .apply()
    }

    fun setMinHeight(height: Int) {
        _minHeight.value = height
        prefs.edit()
            .putInt(KEY_MIN_HEIGHT, height)
            .putBoolean("pocket_dim_filter_explicitly_set_zero", height == 0)
            .apply()
    }

    private val sizeCache = ConcurrentHashMap<String, Long>()
    private val activeProbes = ConcurrentHashMap.newKeySet<String>()

    fun setMinSizeKb(sizeKb: Int) {
        _minSizeKb.value = sizeKb
        prefs.edit().putInt(KEY_MIN_SIZE_KB, sizeKb).apply()
    }

    fun parseDimensionsFromText(text: String): Pair<Int?, Int?> {
        if (text.isBlank()) return null to null
        val match = Regex("""(?i)(\d{2,5})\s*([x*×_]|by)\s*(\d{2,5})""").find(text)
        if (match != null) {
            val w = match.groupValues[1].toIntOrNull()
            val h = match.groupValues[3].toIntOrNull()
            if (w != null && h != null && w > 0 && h > 0) {
                return w to h
            }
        }
        val wMatch = Regex("""(?i)[?&](?:w|width)=(\d{2,5})""").find(text)
        val hMatch = Regex("""(?i)[?&](?:h|height)=(\d{2,5})""").find(text)
        if (wMatch != null || hMatch != null) {
            val w = wMatch?.groupValues?.get(1)?.toIntOrNull()
            val h = hMatch?.groupValues?.get(1)?.toIntOrNull()
            return w to h
        }
        return null to null
    }

    fun parseMediaItemDimensions(item: MediaInboxItem): Pair<Int?, Int?> {
        val (w, h) = parseDimensionsFromText("${item.title} ${item.sourceUrl}")
        if (w != null || h != null) {
            return w to h
        }
        if (item.localUri.isNotBlank()) {
            try {
                val path = item.localUri.removePrefix("file://")
                val file = File(path)
                if (file.exists() && file.isFile) {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, options)
                    if (options.outWidth > 0 && options.outHeight > 0) {
                        return options.outWidth to options.outHeight
                    }
                }
            } catch (_: Exception) {}
        }
        if (item.sourceUrl.isNotBlank()) {
            try {
                val diskCache = Coil.imageLoader(context).diskCache
                val snapshot = diskCache?.openSnapshot(item.sourceUrl)
                if (snapshot != null) {
                    val file = snapshot.data.toFile()
                    if (file.exists() && file.length() > 0) {
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.absolutePath, options)
                        snapshot.close()
                        if (options.outWidth > 0 && options.outHeight > 0) {
                            return options.outWidth to options.outHeight
                        }
                    } else {
                        snapshot.close()
                    }
                }
            } catch (_: Throwable) {}
        }
        return null to null
    }

    fun itemMatchesDimensions(item: MediaInboxItem, minWidth: Int, minHeight: Int): Boolean {
        if (minWidth <= 0 && minHeight <= 0) return true
        if (item.isVideo || item.isAudio || item.isStream) return true
        val (w, h) = parseMediaItemDimensions(item)
        if (w != null && minWidth > 0 && w < minWidth) return false
        if (h != null && minHeight > 0 && h < minHeight) return false
        return true
    }

    fun itemMatchesType(
        item: MediaInboxItem,
        showSvg: Boolean,
        showVideos: Boolean,
        showStreams: Boolean,
        showPng: Boolean,
        showGif: Boolean
    ): Boolean {
        if (!showSvg && MediaClassifier.isSvg(item.sourceUrl, format = item.format)) {
            return false
        }
        val isItemVideo = item.isVideo || item.format.equals("MP4", true) || item.format.equals("WEBM", true) || item.format.equals("MOV", true) || item.sourceUrl.endsWith(".mp4", true) || item.sourceUrl.endsWith(".webm", true) || item.sourceUrl.endsWith(".mov", true)
        if (!showVideos && isItemVideo) {
            return false
        }
        val isItemStream = item.isStream || item.format.equals("M3U8", true) || item.sourceUrl.contains(".m3u8", true) || item.sourceUrl.contains(".mpd", true)
        if (!showStreams && isItemStream) {
            return false
        }
        val isItemPng = item.format.equals("PNG", true) || item.sourceUrl.contains(".png", true) || item.title.contains(".png", true)
        if (!showPng && isItemPng) {
            return false
        }
        val isItemGif = item.format.equals("GIF", true) || item.sourceUrl.contains(".gif", true) || item.title.contains(".gif", true)
        if (!showGif && isItemGif) {
            return false
        }
        return true
    }

    /**
     * Accurately resolves the media item file size in bytes from local storage,
     * Coil's persistent disk cache, explicit query hints, dimension metrics, or background network probe.
     */
    fun resolveItemSizeBytes(item: MediaInboxItem): Long {
        if (item.sizeBytes > 0L) {
            return item.sizeBytes
        }
        if (item.localUri.isNotBlank()) {
            val localPath = item.localUri.removePrefix("file://")
            val f = File(localPath)
            if (f.exists() && f.isFile && f.length() > 0L) {
                val len = f.length()
                sizeCache[item.id] = len
                if (item.sourceUrl.isNotBlank()) sizeCache[item.sourceUrl] = len
                return len
            }
            if (item.localUri.startsWith("content://")) {
                runCatching {
                    val uri = Uri.parse(item.localUri)
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        val statSize = pfd.statSize
                        if (statSize > 0L) {
                            sizeCache[item.id] = statSize
                            return statSize
                        }
                    }
                }
            }
        }

        val url = item.sourceUrl
        if (url.isBlank()) return 0L

        sizeCache[url]?.let { if (it > 0L) return it }
        sizeCache[item.id]?.let { if (it > 0L) return it }

        if (url.startsWith("data:", ignoreCase = true)) {
            val payload = url.substringAfter(',', "")
            if (payload.isNotBlank()) {
                val len = if (url.contains(";base64", ignoreCase = true)) {
                    (payload.length * 3L) / 4L
                } else {
                    payload.length.toLong()
                }
                if (len > 0L) {
                    sizeCache[url] = len
                    return len
                }
            }
        }

        val sizeParamMatch = Regex("""[?&](?:size|filesize|bytes|length|len)=(\d+)""", RegexOption.IGNORE_CASE).find(url)
        if (sizeParamMatch != null) {
            val parsed = sizeParamMatch.groupValues[1].toLongOrNull()
            if (parsed != null && parsed > 0L) {
                sizeCache[url] = parsed
                return parsed
            }
        }

        try {
            val diskCache = Coil.imageLoader(context).diskCache
            val snapshot = diskCache?.openSnapshot(url)
            if (snapshot != null) {
                val len = snapshot.data.toFile().length()
                snapshot.close()
                if (len > 0L) {
                    sizeCache[url] = len
                    return len
                }
            }
        } catch (_: Throwable) {}

        val (w, h) = parseMediaItemDimensions(item)
        if (w != null && h != null && w > 0 && h > 0) {
            val isItemVideo = item.isVideo || item.isStream || item.format.equals("MP4", true) || url.endsWith(".mp4", true)
            val isItemSvg = MediaClassifier.isSvg(url, format = item.format)
            val isItemPng = item.format.equals("PNG", true) || url.endsWith(".png", true)
            val estimated = when {
                isItemSvg -> 12_000L
                isItemVideo -> ((w.toLong() * h.toLong() * 2L).coerceIn(800_000L, 25_000_000L))
                isItemPng -> ((w.toLong() * h.toLong() * 65L) / 100L).coerceAtLeast(4_000L)
                else -> ((w.toLong() * h.toLong() * 28L) / 100L).coerceAtLeast(3_000L)
            }
            if (estimated > 0L) {
                sizeCache[url] = estimated
                triggerAsyncHeadSize(url, item.id)
                return estimated
            }
        }

        triggerAsyncHeadSize(url, item.id)

        val defaultEstimate = when {
            item.isVideo || item.isStream -> 2_500_000L
            MediaClassifier.isSvg(url, format = item.format) -> 12_000L
            item.format.equals("PNG", true) || url.endsWith(".png", true) -> 350_000L
            item.format.equals("GIF", true) || url.endsWith(".gif", true) -> 750_000L
            else -> 180_000L
        }
        return defaultEstimate
    }

    private fun triggerAsyncHeadSize(url: String, itemId: String) {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return
        if (!activeProbes.add(url)) return
        coroutineScope.launch {
            try {
                val result = OkHttpDownloader.probeDirectMedia(url)
                val cl = result.contentLength
                if (cl != null && cl > 0L) {
                    sizeCache[url] = cl
                    sizeCache[itemId] = cl
                    updateItemSizeInBookmarks(itemId, url, cl)
                }
            } catch (_: Exception) {
            } finally {
                activeProbes.remove(url)
            }
        }
    }

    private fun updateItemSizeInBookmarks(itemId: String, url: String, sizeBytes: Long) {
        var changed = false
        val updatedList = _bookmarks.value.map { stack ->
            var stackChanged = false
            val updatedItems = stack.items.map { item ->
                if ((item.id == itemId || item.sourceUrl == url) && item.sizeBytes != sizeBytes) {
                    stackChanged = true
                    item.copy(sizeBytes = sizeBytes)
                } else item
            }
            if (stackChanged) {
                changed = true
                stack.copy(items = updatedItems)
            } else stack
        }
        if (changed) {
            _bookmarks.value = updatedList
            persist()
        }
    }

    /**
     * Evaluates whether an item meets the minimum size requirement (0 KB to 10 MB).
     * If minSizeKb <= 0, all sizes match.
     */
    fun itemMatchesSize(item: MediaInboxItem, minSizeKb: Int): Boolean {
        if (minSizeKb <= 0) return true
        val minSizeBytes = minSizeKb * 1024L
        val actualSize = resolveItemSizeBytes(item)
        return actualSize >= minSizeBytes
    }

    /**
     * Sanitizes a bookmark stack according to showSvg, media types (Videos, Streams, PNGs, GIFs),
     * minimum dimensions (horizontal/vertical from 0 to infinity), and media size (0KB to 10MB).
     * Any media item failing the active filters is hidden from found media.
     * The bookmark stack itself is ALWAYS preserved.
     */
    fun sanitizeStack(
        stack: MediaInboxStack,
        showSvg: Boolean = _showSvg.value,
        minWidth: Int = _minWidth.value,
        minHeight: Int = _minHeight.value,
        showVideos: Boolean = _showVideos.value,
        showStreams: Boolean = _showStreams.value,
        showPng: Boolean = _showPng.value,
        showGif: Boolean = _showGif.value,
        minSizeKb: Int = _minSizeKb.value
    ): MediaInboxStack {
        val filteredItems = stack.items.filter { item ->
            val typeOk = itemMatchesType(item, showSvg, showVideos, showStreams, showPng, showGif)
            val dimOk = itemMatchesDimensions(item, minWidth, minHeight)
            val sizeOk = itemMatchesSize(item, minSizeKb)
            typeOk && dimOk && sizeOk
        }

        val browserPreview = stack.previewImageUrl.ifBlank {
            stack.items.firstOrNull()?.sourceUrl ?: ""
        }

        val previewIsSvg = stack.previewImageUrl.isNotBlank() && MediaClassifier.isSvg(stack.previewImageUrl)
        val previewDimMatch = if (stack.previewImageUrl.isNotBlank() && (minWidth > 0 || minHeight > 0)) {
            val (pw, ph) = parseDimensionsFromText(stack.previewImageUrl)
            (pw == null || minWidth <= 0 || pw >= minWidth) && (ph == null || minHeight <= 0 || ph >= minHeight)
        } else true

        val previewTypeMatch = if (previewIsSvg) showSvg else true

        val previewSizeMatch = if (stack.previewImageUrl.isNotBlank() && minSizeKb > 0) {
            val dummyItem = MediaInboxItem(
                id = "preview_${stack.id}",
                title = stack.title,
                localUri = "",
                sourceUrl = stack.previewImageUrl,
                isVideo = false
            )
            itemMatchesSize(dummyItem, minSizeKb)
        } else true

        val previewValid = previewTypeMatch && previewDimMatch && previewSizeMatch

        // If no media is able to fulfil the criteria, show the browser preview
        val sanitizedPreview = if (filteredItems.isNotEmpty()) {
            if (previewValid && stack.previewImageUrl.isNotBlank()) {
                stack.previewImageUrl
            } else {
                filteredItems.firstOrNull()?.sourceUrl ?: ""
            }
        } else {
            // No media fulfilled the criteria -> show browser preview
            browserPreview
        }

        return stack.copy(items = filteredItems, previewImageUrl = sanitizedPreview)
    }

    /**
     * Backwards-compatible overload for sanitizeStackForSvg.
     */
    fun sanitizeStackForSvg(stack: MediaInboxStack, showSvg: Boolean): MediaInboxStack {
        return sanitizeStack(stack, showSvg = showSvg)
    }

    init {
        loadBookmarks()
    }

    private fun loadBookmarks() {
        val rawJson = prefs.getString(KEY_BOOKMARKS_JSON, null)
        if (rawJson.isNullOrBlank()) {
            migrateFromLegacyInboxIfNeeded()
            return
        }

        try {
            val jsonArray = JSONArray(rawJson)
            val list = mutableListOf<MediaInboxStack>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val itemsArr = obj.optJSONArray("items")
                val itemsList = mutableListOf<MediaInboxItem>()
                if (itemsArr != null) {
                    for (j in 0 until itemsArr.length()) {
                        val itmObj = itemsArr.getJSONObject(j)
                        itemsList.add(
                            MediaInboxItem(
                                id = itmObj.getString("id"),
                                title = itmObj.optString("title", "Media"),
                                localUri = itmObj.optString("localUri", ""),
                                sourceUrl = itmObj.optString("sourceUrl", ""),
                                isVideo = itmObj.optBoolean("isVideo", false),
                                isAudio = itmObj.optBoolean("isAudio", false),
                                isStream = itmObj.optBoolean("isStream", false),
                                format = itmObj.optString("format", ""),
                                sizeBytes = itmObj.optLong("sizeBytes", 0L),
                                dateAddedMillis = itmObj.optLong("dateAddedMillis", System.currentTimeMillis())
                            )
                        )
                    }
                }

                list.add(
                    MediaInboxStack(
                        id = obj.getString("id"),
                        title = obj.optString("title", "Untitled Bookmark"),
                        sourceDomain = obj.optString("sourceDomain", ""),
                        sourceUrl = obj.optString("sourceUrl", ""),
                        items = itemsList,
                        timestampMillis = obj.optLong("timestampMillis", System.currentTimeMillis()),
                        description = obj.optString("description", ""),
                        previewImageUrl = obj.optString("previewImageUrl", "")
                    )
                )
            }
            _bookmarks.value = list
            // Automatically fetch missing metadata (titles, descriptions, previews, media) for bookmarks
            autoFetchMissingMetadata()
        } catch (e: Exception) {
            _bookmarks.value = emptyList()
        }
    }

    private fun migrateFromLegacyInboxIfNeeded() {
        try {
            val legacyPrefs = context.getSharedPreferences("media_inbox_prefs", Context.MODE_PRIVATE)
            val legacyJson = legacyPrefs.getString("inbox_stacks_json", null)
            if (!legacyJson.isNullOrBlank()) {
                val jsonArray = JSONArray(legacyJson)
                val list = mutableListOf<MediaInboxStack>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val id = obj.getString("id")
                    val itemsArr = obj.optJSONArray("items")
                    val isBookmark = id.startsWith("bookmark_") || itemsArr == null || itemsArr.length() == 0
                    if (isBookmark) {
                        list.add(
                            MediaInboxStack(
                                id = id,
                                title = obj.optString("title", "Untitled Bookmark"),
                                sourceDomain = obj.optString("sourceDomain", ""),
                                sourceUrl = obj.optString("sourceUrl", ""),
                                items = emptyList(),
                                timestampMillis = obj.optLong("timestampMillis", System.currentTimeMillis()),
                                description = obj.optString("description", ""),
                                previewImageUrl = obj.optString("previewImageUrl", "")
                            )
                        )
                    }
                }
                if (list.isNotEmpty()) {
                    _bookmarks.value = list
                    persist()
                    autoFetchMissingMetadata()
                }
            }
        } catch (_: Exception) {}
    }

    private fun persist() {
        try {
            val jsonArray = JSONArray()
            _bookmarks.value.forEach { bookmark ->
                val obj = JSONObject().apply {
                    put("id", bookmark.id)
                    put("title", bookmark.title)
                    put("sourceDomain", bookmark.sourceDomain)
                    put("sourceUrl", bookmark.sourceUrl)
                    put("timestampMillis", bookmark.timestampMillis)
                    put("description", bookmark.description)
                    put("previewImageUrl", bookmark.previewImageUrl)

                    val itemsArr = JSONArray()
                    bookmark.items.forEach { itm ->
                        val itmObj = JSONObject().apply {
                            put("id", itm.id)
                            put("title", itm.title)
                            put("localUri", itm.localUri)
                            put("sourceUrl", itm.sourceUrl)
                            put("isVideo", itm.isVideo)
                            put("isAudio", itm.isAudio)
                            put("isStream", itm.isStream)
                            put("format", itm.format)
                            put("sizeBytes", itm.sizeBytes)
                            put("dateAddedMillis", itm.dateAddedMillis)
                        }
                        itemsArr.put(itmObj)
                    }
                    put("items", itemsArr)
                }
                jsonArray.put(obj)
            }
            prefs.edit().putString(KEY_BOOKMARKS_JSON, jsonArray.toString()).apply()
        } catch (_: Exception) {}
    }

    /**
     * Adds a bookmark to Pocket.
     * When autoFetchMetadata is true, automatically queries the web page in the background
     * for its title, description, link preview image, and site media items, then smoothly updates the bookmark.
     */
    fun addBookmark(
        title: String,
        url: String,
        domain: String = "",
        description: String = "",
        previewImageUrl: String = "",
        autoFetchMetadata: Boolean = true
    ): MediaInboxStack {
        val cleanUrl = url.trim()
        val cleanDomain = domain.ifBlank { MediaClassifier.cleanDomainFromUrl(cleanUrl) }

        val cleanLower = cleanUrl.lowercase(Locale.ROOT).substringBefore('?').substringBefore('#')
        val ext = cleanLower.substringAfterLast('.', "")
        val isDirectImage = ext in listOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp", "svg", "heic", "heif", "ico") ||
                cleanUrl.lowercase(Locale.ROOT).let { lower ->
                    lower.contains("format=jpg") || lower.contains("format=png") ||
                    lower.contains("format=webp") || lower.contains("format=avif") ||
                    lower.startsWith("data:image/")
                }

        val isDirectVideo = ext in listOf("mp4", "webm", "mov", "mkv", "m3u8", "mpd", "avi", "3gp", "ts") ||
                cleanUrl.lowercase(Locale.ROOT).let { lower ->
                    lower.contains("format=mp4") || lower.contains("format=webm") ||
                    lower.startsWith("data:video/")
                }

        val isMedia = isDirectImage || isDirectVideo

        val mediaFileName = cleanUrl.substringAfterLast('/').substringBefore('?').ifBlank {
            cleanDomain.ifBlank { "Media" }
        }

        val initialItems = if (isMedia) {
            listOf(
                MediaInboxItem(
                    id = "media_${System.currentTimeMillis()}_${cleanUrl.hashCode().toUInt().toString(16)}",
                    title = title.ifBlank { mediaFileName },
                    localUri = if (cleanUrl.startsWith("file://") || cleanUrl.startsWith("content://")) cleanUrl else "",
                    sourceUrl = cleanUrl,
                    isVideo = isDirectVideo,
                    dateAddedMillis = System.currentTimeMillis()
                )
            )
        } else {
            emptyList()
        }

        val initialPreview = previewImageUrl.ifBlank { if (isMedia) cleanUrl else "" }

        val bookmark = MediaInboxStack(
            id = "bookmark_${System.currentTimeMillis()}",
            title = title.ifBlank { if (isMedia) mediaFileName else cleanDomain.ifBlank { cleanUrl } },
            sourceDomain = cleanDomain,
            sourceUrl = cleanUrl,
            items = initialItems,
            timestampMillis = System.currentTimeMillis(),
            description = description.ifBlank { if (isMedia) (if (isDirectVideo) "Saved Video" else "Saved Image") else "" },
            previewImageUrl = initialPreview
        )
        val updated = listOf(bookmark) + _bookmarks.value.filter {
            it.id != bookmark.id && (it.sourceUrl.isBlank() || it.sourceUrl != cleanUrl)
        }
        _bookmarks.value = updated
        persist()

        if (autoFetchMetadata && cleanUrl.isNotBlank()) {
            fetchAndApplyMetadata(bookmark.id, cleanUrl)
        }

        return bookmark
    }

    /**
     * Automatically fetches metadata (title, description, preview image, and site media) for a bookmark and updates it.
     */
    fun fetchAndApplyMetadata(bookmarkId: String, url: String = "") {
        coroutineScope.launch {
            try {
                val current = _bookmarks.value.find { it.id == bookmarkId } ?: return@launch
                val targetUrl = url.ifBlank { current.sourceUrl }
                if (targetUrl.isBlank()) return@launch

                val meta = LinkMetadataFetcher.fetch(targetUrl)
                val latest = _bookmarks.value.find { it.id == bookmarkId } ?: return@launch

                val cleanDomain = latest.sourceDomain.ifBlank { MediaClassifier.cleanDomainFromUrl(targetUrl) }
                val isTitleDefault = latest.title.isBlank() ||
                        latest.title.equals(cleanDomain, ignoreCase = true) ||
                        latest.title.equals(targetUrl, ignoreCase = true) ||
                        latest.title == "Untitled Bookmark"

                val newTitle = if (isTitleDefault && meta.title.isNotBlank()) meta.title else latest.title
                val newDesc = if (latest.description.isBlank() && meta.description.isNotBlank()) meta.description else latest.description
                val newPreview = if (latest.previewImageUrl.isBlank() && meta.previewImageUrl.isNotBlank()) meta.previewImageUrl else latest.previewImageUrl
                val newDomain = if (latest.sourceDomain.isBlank() && meta.domain.isNotBlank()) meta.domain else latest.sourceDomain

                val newItems = if (latest.items.isEmpty() && meta.mediaItems.isNotEmpty()) {
                    meta.mediaItems
                } else if (latest.items.isNotEmpty()) {
                    latest.items
                } else if (newPreview.isNotBlank()) {
                    listOf(
                        MediaInboxItem(
                            id = "media_preview_${latest.id}",
                            title = newTitle,
                            localUri = "",
                            sourceUrl = newPreview,
                            isVideo = false
                        )
                    )
                } else {
                    emptyList()
                }

                if (newTitle != latest.title || newDesc != latest.description || newPreview != latest.previewImageUrl ||
                    newDomain != latest.sourceDomain || newItems.size != latest.items.size) {
                    val updated = latest.copy(
                        title = newTitle,
                        description = newDesc,
                        previewImageUrl = newPreview,
                        sourceDomain = newDomain,
                        items = newItems
                    )
                    updateBookmark(updated)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching metadata for bookmark $bookmarkId", e)
            }
        }
    }

    /**
     * Specifically extracts all media of the site for a bookmark.
     */
    fun fetchSiteMedia(bookmarkId: String, extractionContext: Context? = null) {
        coroutineScope.launch {
            try {
                val latest = _bookmarks.value.find { it.id == bookmarkId } ?: return@launch
                if (latest.sourceUrl.isBlank()) return@launch

                val meta = LinkMetadataFetcher.fetch(latest.sourceUrl)
                val current = _bookmarks.value.find { it.id == bookmarkId } ?: return@launch

                // Static HTML remains the fast fallback, but cannot see content inserted by JavaScript.
                // The activity context lets BrowserSession attach a hidden WebView and inspect the hydrated DOM.
                val scrapedItems = if (extractionContext != null &&
                    (latest.sourceUrl.startsWith("http://", ignoreCase = true) ||
                        latest.sourceUrl.startsWith("https://", ignoreCase = true))
                ) {
                    runCatching {
                        BrowserSession.extractHeadless(
                            context = extractionContext,
                            targetUrl = latest.sourceUrl,
                            onStatusUpdate = { status ->
                                Log.d(TAG, "Bookmark media scan ($bookmarkId): $status")
                            },
                            publishToDownloader = false
                        ).items
                    }.onFailure { error ->
                        Log.w(TAG, "JavaScript media scan failed for bookmark $bookmarkId", error)
                    }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }

                val dynamicItems = MediaNormalizer.deduplicate(scrapedItems).map { item ->
                    MediaInboxItem(
                        id = "web_${item.id}",
                        title = item.title.ifBlank { "Media" },
                        localUri = "",
                        sourceUrl = item.url,
                        isVideo = item.isVideo,
                        isAudio = item.isAudio,
                        isStream = item.isStream,
                        format = item.format,
                        sizeBytes = item.sizeBytes ?: 0L,
                        dateAddedMillis = item.detectedAt
                    )
                }

                // Prefer page media over OG/site-icon images so the deck opens on actual content.
                val newPreview = dynamicItems.firstOrNull { !it.isVideo && !it.isAudio }?.sourceUrl
                    ?.takeIf { it.isNotBlank() }
                    ?: meta.previewImageUrl.ifBlank { current.previewImageUrl }

                val staticCandidates = (current.items + meta.mediaItems).filterNot { item ->
                    item.sourceUrl.contains("google.com/s2/favicons", ignoreCase = true)
                }
                val candidates = if (dynamicItems.isNotEmpty()) {
                    dynamicItems + staticCandidates
                } else if (meta.mediaItems.isNotEmpty()) {
                    meta.mediaItems
                } else if (staticCandidates.isNotEmpty()) {
                    staticCandidates
                } else if (newPreview.isNotBlank()) {
                    listOf(
                        MediaInboxItem(
                            id = "site_media_${current.id}_preview",
                            title = meta.title.ifBlank { current.title },
                            localUri = "",
                            sourceUrl = newPreview,
                            isVideo = false,
                            format = "JPEG",
                            dateAddedMillis = System.currentTimeMillis()
                        )
                    )
                } else {
                    current.items
                }

                val seenKeys = mutableSetOf<String>()
                val resolvedItems = candidates
                    .filter { it.sourceUrl.isNotBlank() || it.localUri.isNotBlank() }
                    .filter { item ->
                        val keySource = item.sourceUrl.ifBlank { item.localUri }
                        val key = MediaNormalizer.canonicalKey(keySource).ifBlank { item.id }
                        seenKeys.add(key)
                    }
                    .take(60)

                val cleanDomain = current.sourceDomain.ifBlank { meta.domain }
                val isTitleDefault = current.title.isBlank() ||
                    current.title.equals(cleanDomain, ignoreCase = true) ||
                    current.title.equals(current.sourceUrl, ignoreCase = true) ||
                    current.title == "Untitled Bookmark"

                val updated = current.copy(
                    title = if (isTitleDefault && meta.title.isNotBlank()) meta.title else current.title,
                    description = current.description.ifBlank { meta.description },
                    previewImageUrl = newPreview,
                    sourceDomain = cleanDomain,
                    items = resolvedItems
                )
                updateBookmark(updated)
                Log.d(TAG, "Bookmark media scan completed: ${resolvedItems.size} items for $bookmarkId")
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching site media for bookmark $bookmarkId", e)
            }
        }
    }

    /**
     * Refreshes both static metadata and JavaScript-rendered site media.
     */
    fun refreshMetadata(bookmarkId: String, extractionContext: Context? = null) {
        fetchSiteMedia(bookmarkId, extractionContext)
    }

    /**
     * Checks all stored bookmarks and automatically fetches any missing previews or descriptions.
     */
    fun autoFetchMissingMetadata() {
        coroutineScope.launch {
            val missing = _bookmarks.value.filter {
                it.sourceUrl.isNotBlank() && (it.previewImageUrl.isBlank() || it.description.isBlank() || it.items.isEmpty())
            }
            for (bm in missing) {
                try {
                    val meta = LinkMetadataFetcher.fetch(bm.sourceUrl)
                    val current = _bookmarks.value.find { it.id == bm.id } ?: continue

                    val cleanDomain = current.sourceDomain.ifBlank { MediaClassifier.cleanDomainFromUrl(bm.sourceUrl) }
                    val isTitleDefault = current.title.isBlank() ||
                            current.title.equals(cleanDomain, ignoreCase = true) ||
                            current.title.equals(bm.sourceUrl, ignoreCase = true) ||
                            current.title == "Untitled Bookmark"

                    val newTitle = if (isTitleDefault && meta.title.isNotBlank()) meta.title else current.title
                    val newDesc = if (current.description.isBlank() && meta.description.isNotBlank()) meta.description else current.description
                    val newPreview = if (current.previewImageUrl.isBlank() && meta.previewImageUrl.isNotBlank()) meta.previewImageUrl else current.previewImageUrl
                    val newItems = if (current.items.isEmpty() && meta.mediaItems.isNotEmpty()) meta.mediaItems else current.items

                    if (newTitle != current.title || newDesc != current.description || newPreview != current.previewImageUrl || newItems.size != current.items.size) {
                        updateBookmark(
                            current.copy(
                                title = newTitle,
                                description = newDesc,
                                previewImageUrl = newPreview,
                                items = newItems
                            )
                        )
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun updateBookmark(updated: MediaInboxStack) {
        _bookmarks.value = _bookmarks.value.map { if (it.id == updated.id) updated else it }
        persist()
    }

    fun deleteBookmark(bookmarkId: String) {
        _bookmarks.value = _bookmarks.value.filter { it.id != bookmarkId }
        persist()
    }

    fun deleteBookmarks(bookmarkIds: Set<String>) {
        _bookmarks.value = _bookmarks.value.filter { it.id !in bookmarkIds }
        persist()
    }

    fun clearAll() {
        _bookmarks.value = emptyList()
        prefs.edit().remove(KEY_BOOKMARKS_JSON).apply()
    }

    companion object {
        private const val TAG = "PocketRepository"
        private const val KEY_BOOKMARKS_JSON = "pocket_bookmarks_json"
        private const val KEY_SHOW_SVG = "pocket_filter_show_svg"
        private const val KEY_SHOW_VIDEOS = "pocket_filter_show_videos"
        private const val KEY_SHOW_STREAMS = "pocket_filter_show_streams"
        private const val KEY_SHOW_PNG = "pocket_filter_show_png"
        private const val KEY_SHOW_GIF = "pocket_filter_show_gif"
        private const val KEY_MIN_WIDTH = "pocket_filter_min_width"
        private const val KEY_MIN_HEIGHT = "pocket_filter_min_height"
        private const val KEY_MIN_SIZE_KB = "pocket_filter_min_size_kb"
        const val DEFAULT_MIN_WIDTH = 480
        const val DEFAULT_MIN_HEIGHT = 540
        const val DEFAULT_MIN_SIZE_KB = 10

        @Volatile
        private var INSTANCE: PocketRepository? = null

        fun getInstance(context: Context): PocketRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PocketRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
