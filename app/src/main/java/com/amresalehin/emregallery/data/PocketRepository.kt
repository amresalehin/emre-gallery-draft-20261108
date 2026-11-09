package com.amresalehin.emregallery.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

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

        @Volatile
        private var INSTANCE: PocketRepository? = null

        fun getInstance(context: Context): PocketRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PocketRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
