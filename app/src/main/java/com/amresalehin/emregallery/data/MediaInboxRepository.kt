package com.amresalehin.emregallery.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class MediaInboxItem(
    val id: String,
    val title: String,
    val localUri: String,
    val sourceUrl: String,
    val isVideo: Boolean,
    val isAudio: Boolean = false,
    val isStream: Boolean = false,
    val format: String = "",
    val sizeBytes: Long = 0L,
    val dateAddedMillis: Long = System.currentTimeMillis()
)

data class MediaInboxStack(
    val id: String,
    val title: String,
    val sourceDomain: String,
    val sourceUrl: String,
    val items: List<MediaInboxItem>,
    val timestampMillis: Long = System.currentTimeMillis(),
    val description: String = "",
    val previewImageUrl: String = ""
)

class MediaInboxRepository(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("media_inbox_prefs", Context.MODE_PRIVATE)

    private val _stacks = MutableStateFlow<List<MediaInboxStack>>(emptyList())
    val stacks: StateFlow<List<MediaInboxStack>> = _stacks.asStateFlow()

    init {
        loadStacks()
    }

    private fun loadStacks() {
        val rawJson = prefs.getString(KEY_STACKS_JSON, null)
        if (rawJson.isNullOrBlank()) {
            _stacks.value = emptyList()
            return
        }

        try {
            val jsonArray = JSONArray(rawJson)
            val list = mutableListOf<MediaInboxStack>()
            for (i in 0 until jsonArray.length()) {
                val stackObj = jsonArray.getJSONObject(i)
                val id = stackObj.getString("id")
                val title = stackObj.optString("title", "Untitled Stack")
                val sourceDomain = stackObj.optString("sourceDomain", "")
                val sourceUrl = stackObj.optString("sourceUrl", "")
                val timestamp = stackObj.optLong("timestampMillis", System.currentTimeMillis())
                val description = stackObj.optString("description", "")
                val previewImageUrl = stackObj.optString("previewImageUrl", "")

                val itemsArray = stackObj.optJSONArray("items") ?: JSONArray()
                val itemsList = mutableListOf<MediaInboxItem>()
                for (j in 0 until itemsArray.length()) {
                    val itmObj = itemsArray.getJSONObject(j)
                    itemsList.add(
                        MediaInboxItem(
                            id = itmObj.getString("id"),
                            title = itmObj.optString("title", "Media"),
                            localUri = itmObj.getString("localUri"),
                            sourceUrl = itmObj.optString("sourceUrl", ""),
                            isVideo = itmObj.optBoolean("isVideo", false),
                            isAudio = itmObj.optBoolean("isAudio", false),
                            isStream = itmObj.optBoolean("isStream", false),
                            format = itmObj.optString("format", ""),
                            sizeBytes = itmObj.optLong("sizeBytes", 0L),
                            dateAddedMillis = itmObj.optLong("dateAddedMillis", timestamp)
                        )
                    )
                }

                list.add(
                    MediaInboxStack(
                        id = id,
                        title = title,
                        sourceDomain = sourceDomain,
                        sourceUrl = sourceUrl,
                        items = itemsList,
                        timestampMillis = timestamp,
                        description = description,
                        previewImageUrl = previewImageUrl
                    )
                )
            }
            val consolidated = consolidateStacks(list)
            _stacks.value = consolidated
            if (consolidated.size != list.size) {
                persist()
            }
        } catch (e: Exception) {
            _stacks.value = emptyList()
        }
    }

    private fun consolidateStacks(rawList: List<MediaInboxStack>): List<MediaInboxStack> {
        if (rawList.size <= 1) return rawList
        val merged = mutableListOf<MediaInboxStack>()
        for (stack in rawList) {
            val existingIndex = merged.indexOfFirst { existing ->
                when {
                    existing.id == stack.id -> true
                    existing.sourceUrl.isNotBlank() && stack.sourceUrl.isNotBlank() && existing.sourceUrl == stack.sourceUrl -> true
                    else -> false
                }
            }
            if (existingIndex >= 0) {
                val existing = merged[existingIndex]
                val existingKeys = existing.items.map { it.id to it.localUri }.toSet()
                val newItems = stack.items.filter { (it.id to it.localUri) !in existingKeys }
                merged[existingIndex] = existing.copy(
                    items = existing.items + newItems,
                    timestampMillis = maxOf(existing.timestampMillis, stack.timestampMillis),
                    sourceUrl = if (existing.sourceUrl.isNotBlank()) existing.sourceUrl else stack.sourceUrl,
                    sourceDomain = if (existing.sourceDomain.isNotBlank()) existing.sourceDomain else stack.sourceDomain
                )
            } else {
                merged.add(stack)
            }
        }
        return merged
    }

    private fun persist() {
        try {
            val jsonArray = JSONArray()
            _stacks.value.forEach { stack ->
                val stackObj = JSONObject().apply {
                    put("id", stack.id)
                    put("title", stack.title)
                    put("sourceDomain", stack.sourceDomain)
                    put("sourceUrl", stack.sourceUrl)
                    put("timestampMillis", stack.timestampMillis)
                    put("description", stack.description)
                    put("previewImageUrl", stack.previewImageUrl)

                    val itemsArr = JSONArray()
                    stack.items.forEach { itm ->
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
                jsonArray.put(stackObj)
            }
            prefs.edit().putString(KEY_STACKS_JSON, jsonArray.toString()).apply()
        } catch (e: Exception) {
            // Log or ignore
        }
    }

    fun addStack(stack: MediaInboxStack) {
        val updated = listOf(stack) + _stacks.value.filter { it.id != stack.id }
        _stacks.value = consolidateStacks(updated)
        persist()
    }

    fun addBookmark(
        title: String,
        url: String,
        domain: String = "",
        description: String = ""
    ): MediaInboxStack {
        val cleanUrl = url.trim()
        val cleanDomain = domain.ifBlank { MediaClassifier.cleanDomainFromUrl(cleanUrl) }
        val bookmarkStack = MediaInboxStack(
            id = "bookmark_${System.currentTimeMillis()}",
            title = title.ifBlank { cleanDomain.ifBlank { cleanUrl } },
            sourceDomain = cleanDomain,
            sourceUrl = cleanUrl,
            items = emptyList(),
            timestampMillis = System.currentTimeMillis(),
            description = description
        )
        addStack(bookmarkStack)
        return bookmarkStack
    }

    fun updateStack(updated: MediaInboxStack) {
        _stacks.value = _stacks.value.map { if (it.id == updated.id) updated else it }
        persist()
    }

    @Synchronized
    fun addItemToStack(
        stackId: String,
        item: MediaInboxItem,
        stackTitle: String,
        sourceDomain: String,
        sourceUrl: String
    ) {
        val current = _stacks.value.toMutableList()
        // 1. Try to find existing stack by stackId
        var existingIndex = current.indexOfFirst { it.id == stackId }
        // 2. If not found by stackId, match strictly by sourceUrl if non-blank
        if (existingIndex < 0 && sourceUrl.isNotBlank()) {
            existingIndex = current.indexOfFirst { it.sourceUrl == sourceUrl }
        }

        if (existingIndex >= 0) {
            val existing = current[existingIndex]
            // When replacing an item with the same sourceUrl or id, delete old file on disk if path changed
            val replacedItems = existing.items.filter {
                (it.id == item.id || (it.sourceUrl.isNotBlank() && it.sourceUrl == item.sourceUrl)) && it.localUri != item.localUri
            }
            for (oldItem in replacedItems) {
                runCatching {
                    val path = oldItem.localUri.removePrefix("file://")
                    val file = File(path)
                    if (file.exists()) {
                        file.delete()
                    }
                }
            }

            val updatedItems = existing.items.filter {
                it.id != item.id && (it.sourceUrl.isBlank() || it.sourceUrl != item.sourceUrl) && it.localUri != item.localUri
            } + item
            current[existingIndex] = existing.copy(
                items = updatedItems,
                timestampMillis = System.currentTimeMillis()
            )
        } else {
            val newStack = MediaInboxStack(
                id = stackId.ifBlank { "stack_${System.currentTimeMillis()}" },
                title = stackTitle.ifBlank { sourceDomain.ifBlank { "Discovered Media" } },
                sourceDomain = sourceDomain,
                sourceUrl = sourceUrl,
                items = listOf(item),
                timestampMillis = System.currentTimeMillis()
            )
            current.add(0, newStack)
        }
        _stacks.value = consolidateStacks(current)
        persist()
    }

    fun deleteStack(stackId: String) {
        // Also cleanup local files
        val stack = _stacks.value.firstOrNull { it.id == stackId }
        stack?.items?.forEach { item ->
            try {
                if (item.localUri.startsWith("file://") || item.localUri.startsWith("/")) {
                    val path = item.localUri.removePrefix("file://")
                    val file = File(path)
                    if (file.exists()) file.delete()
                }
            } catch (_: Exception) {}
        }
        _stacks.value = _stacks.value.filter { it.id != stackId }
        persist()
    }

    fun clearAll() {
        _stacks.value.forEach { stack ->
            stack.items.forEach { item ->
                try {
                    val path = item.localUri.removePrefix("file://")
                    val file = File(path)
                    if (file.exists()) file.delete()
                } catch (_: Exception) {}
            }
        }
        _stacks.value = emptyList()
        prefs.edit().remove(KEY_STACKS_JSON).apply()
    }

    companion object {
        private const val KEY_STACKS_JSON = "inbox_stacks_json"

        @Volatile
        private var INSTANCE: MediaInboxRepository? = null

        fun getInstance(context: Context): MediaInboxRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MediaInboxRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
