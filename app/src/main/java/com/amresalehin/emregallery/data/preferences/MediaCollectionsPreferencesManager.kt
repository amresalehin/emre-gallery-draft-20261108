package com.amresalehin.emregallery.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.amresalehin.emregallery.data.CustomAlbum
import com.amresalehin.emregallery.data.galleryDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Manages media tags, people assignments, albums, favorites, bin, archive, and recent search history.
 */
class MediaCollectionsPreferencesManager(private val context: Context) {

    private val favouritesKey = stringSetPreferencesKey("favourites")
    private val hiddenKey = stringSetPreferencesKey("hidden")
    private val binKey = stringSetPreferencesKey("bin")
    private val archiveKey = stringSetPreferencesKey("archive")
    private val legacySearchesKey = stringSetPreferencesKey("recent_searches")
    private val searchesKey = stringPreferencesKey("recent_searches_v2")
    private val customAlbumsKey = stringPreferencesKey("custom_albums")
    private val mediaTagsKey = stringPreferencesKey("media_custom_tags")
    private val mediaPeopleKey = stringPreferencesKey("media_custom_people")

    val favourites: Flow<Set<String>> = context.galleryDataStore.data.map {
        it[favouritesKey] ?: emptySet()
    }.distinctUntilChanged()

    val hidden: Flow<Set<String>> = context.galleryDataStore.data.map {
        it[hiddenKey] ?: emptySet()
    }.distinctUntilChanged()

    val bin: Flow<Set<String>> = context.galleryDataStore.data.map {
        it[binKey] ?: emptySet()
    }.distinctUntilChanged()

    val archive: Flow<Set<String>> = context.galleryDataStore.data.map {
        it[archiveKey] ?: emptySet()
    }.distinctUntilChanged()

    val recentSearches: Flow<List<String>> = context.galleryDataStore.data.map { prefs ->
        val rawV2 = prefs[searchesKey]
        if (rawV2 != null) {
            try {
                val array = JSONArray(rawV2)
                val list = mutableListOf<String>()
                for (i in 0 until array.length()) {
                    list.add(array.getString(i))
                }
                list
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            prefs[legacySearchesKey]?.toList()?.sorted() ?: emptyList()
        }
    }.distinctUntilChanged()

    val customAlbums: Flow<List<CustomAlbum>> = context.galleryDataStore.data.map { prefs ->
        val raw = prefs[customAlbumsKey] ?: "[]"
        try {
            val jsonArray = JSONArray(raw)
            val list = mutableListOf<CustomAlbum>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.optString("id", "")
                val name = obj.optString("name", "")
                val parentPath = obj.optString("parentPath", "")
                val urisArray = obj.optJSONArray("mediaUris")
                val uris = mutableSetOf<String>()
                if (urisArray != null) {
                    for (j in 0 until urisArray.length()) {
                        uris.add(urisArray.getString(j))
                    }
                }
                if (id.isNotEmpty() && name.isNotEmpty()) {
                    list.add(CustomAlbum(id, name, parentPath, uris))
                }
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }.distinctUntilChanged()

    val customTagsMap: Flow<Map<String, Set<String>>> = context.galleryDataStore.data.map { prefs ->
        val raw = prefs[mediaTagsKey] ?: "{}"
        try {
            val obj = JSONObject(raw)
            val map = mutableMapOf<String, Set<String>>()
            for (key in obj.keys()) {
                val arr = obj.getJSONArray(key)
                val set = mutableSetOf<String>()
                for (i in 0 until arr.length()) {
                    set.add(arr.getString(i))
                }
                map[key] = set
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }.distinctUntilChanged()

    val customPeopleMap: Flow<Map<String, Set<String>>> = context.galleryDataStore.data.map { prefs ->
        val raw = prefs[mediaPeopleKey] ?: "{}"
        try {
            val obj = JSONObject(raw)
            val map = mutableMapOf<String, Set<String>>()
            for (key in obj.keys()) {
                val arr = obj.getJSONArray(key)
                val set = mutableSetOf<String>()
                for (i in 0 until arr.length()) {
                    set.add(arr.getString(i))
                }
                map[key] = set
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }.distinctUntilChanged()

    suspend fun saveCustomAlbums(albums: List<CustomAlbum>) {
        val array = JSONArray()
        for (album in albums) {
            val obj = JSONObject()
            obj.put("id", album.id)
            obj.put("name", album.name)
            obj.put("parentPath", album.parentPath)
            val urisArr = JSONArray()
            for (uri in album.mediaUris) {
                urisArr.put(uri)
            }
            obj.put("mediaUris", urisArr)
            array.put(obj)
        }
        context.galleryDataStore.edit { prefs ->
            prefs[customAlbumsKey] = array.toString()
        }
    }

    suspend fun createCustomAlbum(name: String, parentPath: String = ""): CustomAlbum {
        val current = customAlbums.first()
        val newAlbum = CustomAlbum(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            parentPath = parentPath.trim(),
            mediaUris = emptySet()
        )
        saveCustomAlbums(current + newAlbum)
        return newAlbum
    }

    suspend fun deleteCustomAlbum(albumId: String) {
        val current = customAlbums.first()
        saveCustomAlbums(current.filterNot { it.id == albumId })
    }

    suspend fun renameCustomAlbum(albumId: String, newName: String) {
        val current = customAlbums.first()
        saveCustomAlbums(current.map {
            if (it.id == albumId) it.copy(name = newName.trim()) else it
        })
    }

    suspend fun addMediaToCustomAlbum(albumId: String, uri: String) {
        val current = customAlbums.first()
        saveCustomAlbums(current.map {
            if (it.id == albumId) it.copy(mediaUris = it.mediaUris + uri) else it
        })
    }

    suspend fun removeMediaFromCustomAlbum(albumId: String, uri: String) {
        val current = customAlbums.first()
        saveCustomAlbums(current.map {
            if (it.id == albumId) it.copy(mediaUris = it.mediaUris - uri) else it
        })
    }

    suspend fun toggleFavourite(uri: String) {
        context.galleryDataStore.edit { prefs ->
            val set = (prefs[favouritesKey] ?: emptySet()).toMutableSet()
            if (!set.add(uri)) {
                set.remove(uri)
            }
            prefs[favouritesKey] = set
        }
    }

    suspend fun setHidden(uri: String, hiddenVal: Boolean) {
        context.galleryDataStore.edit { prefs ->
            val set = (prefs[hiddenKey] ?: emptySet()).toMutableSet()
            if (hiddenVal) set.add(uri) else set.remove(uri)
            prefs[hiddenKey] = set
        }
    }

    suspend fun setHiddenBatch(uris: Collection<String>, hiddenVal: Boolean) {
        if (uris.isEmpty()) return
        context.galleryDataStore.edit { prefs ->
            val set = (prefs[hiddenKey] ?: emptySet()).toMutableSet()
            if (hiddenVal) set.addAll(uris) else set.removeAll(uris.toSet())
            prefs[hiddenKey] = set
        }
    }

    suspend fun setInBin(uri: String, inBin: Boolean) {
        context.galleryDataStore.edit { prefs ->
            val set = (prefs[binKey] ?: emptySet()).toMutableSet()
            if (inBin) set.add(uri) else set.remove(uri)
            prefs[binKey] = set
        }
    }

    suspend fun setInBinBatch(uris: Collection<String>, inBin: Boolean) {
        if (uris.isEmpty()) return
        context.galleryDataStore.edit { prefs ->
            val set = (prefs[binKey] ?: emptySet()).toMutableSet()
            if (inBin) set.addAll(uris) else set.removeAll(uris.toSet())
            prefs[binKey] = set
        }
    }

    suspend fun removePermanently(uris: Collection<String>) {
        if (uris.isEmpty()) return
        val uriSet = uris.toSet()
        context.galleryDataStore.edit { prefs ->
            val binSet = (prefs[binKey] ?: emptySet()).toMutableSet()
            binSet.removeAll(uriSet)
            prefs[binKey] = binSet

            val hiddenSet = (prefs[hiddenKey] ?: emptySet()).toMutableSet()
            hiddenSet.removeAll(uriSet)
            prefs[hiddenKey] = hiddenSet

            val favSet = (prefs[favouritesKey] ?: emptySet()).toMutableSet()
            favSet.removeAll(uriSet)
            prefs[favouritesKey] = favSet

            val archSet = (prefs[archiveKey] ?: emptySet()).toMutableSet()
            archSet.removeAll(uriSet)
            prefs[archiveKey] = archSet
        }
        val currentAlbums = customAlbums.first()
        val hasChanges = currentAlbums.any { it.mediaUris.any { uri -> uri in uriSet } }
        if (hasChanges) {
            saveCustomAlbums(currentAlbums.map { album ->
                album.copy(mediaUris = album.mediaUris - uriSet)
            })
        }
    }

    suspend fun setArchived(uri: String, archivedVal: Boolean) {
        context.galleryDataStore.edit { prefs ->
            val set = (prefs[archiveKey] ?: emptySet()).toMutableSet()
            if (archivedVal) set.add(uri) else set.remove(uri)
            prefs[archiveKey] = set
        }
    }

    suspend fun setArchivedBatch(uris: Collection<String>, archivedVal: Boolean) {
        if (uris.isEmpty()) return
        context.galleryDataStore.edit { prefs ->
            val set = (prefs[archiveKey] ?: emptySet()).toMutableSet()
            if (archivedVal) set.addAll(uris) else set.removeAll(uris.toSet())
            prefs[archiveKey] = set
        }
    }

    suspend fun addRecentSearch(query: String) {
        val normalized = query.trim()
        if (normalized.isEmpty()) return
        context.galleryDataStore.edit { prefs ->
            val list = try {
                val raw = prefs[searchesKey]
                if (raw != null) {
                    val arr = JSONArray(raw)
                    val result = mutableListOf<String>()
                    for (i in 0 until arr.length()) result.add(arr.getString(i))
                    result
                } else {
                    prefs[legacySearchesKey]?.toMutableList() ?: mutableListOf()
                }
            } catch (e: Exception) {
                mutableListOf()
            }.toMutableList()

            list.remove(normalized)
            list.add(0, normalized)
            val trimmed = list.take(20)
            prefs[searchesKey] = JSONArray(trimmed).toString()
        }
    }

    suspend fun clearRecentSearches() {
        context.galleryDataStore.edit { prefs ->
            prefs.remove(searchesKey)
            prefs.remove(legacySearchesKey)
        }
    }

    suspend fun addTagForMedia(uri: String, tag: String) {
        addTagsForMedia(uri, listOf(tag))
    }

    suspend fun addTagsForMedia(uri: String, tags: Collection<String>) {
        val validTags = tags.map { it.trim().removePrefix("#").lowercase() }.filter { it.isNotEmpty() }.toSet()
        if (validTags.isEmpty()) return
        context.galleryDataStore.edit { prefs ->
            val raw = prefs[mediaTagsKey] ?: "{}"
            val obj = try { JSONObject(raw) } catch (e: Exception) { JSONObject() }
            val arr = obj.optJSONArray(uri) ?: JSONArray()
            val existing = mutableSetOf<String>()
            for (i in 0 until arr.length()) existing.add(arr.getString(i))
            if (existing.addAll(validTags)) {
                val newArr = JSONArray(existing)
                obj.put(uri, newArr)
                prefs[mediaTagsKey] = obj.toString()
            }
        }
    }

    suspend fun removeTagForMedia(uri: String, tag: String) {
        val trimmed = tag.trim().removePrefix("#").lowercase()
        if (trimmed.isEmpty()) return
        context.galleryDataStore.edit { prefs ->
            val raw = prefs[mediaTagsKey] ?: "{}"
            val obj = try { JSONObject(raw) } catch (e: Exception) { JSONObject() }
            val arr = obj.optJSONArray(uri) ?: return@edit
            val existing = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                val t = arr.getString(i)
                if (!t.equals(trimmed, ignoreCase = true)) {
                    existing.add(t)
                }
            }
            obj.put(uri, JSONArray(existing))
            prefs[mediaTagsKey] = obj.toString()
        }
    }

    suspend fun addPersonForMedia(uri: String, person: String) {
        val trimmed = person.trim()
        if (trimmed.isEmpty()) return
        context.galleryDataStore.edit { prefs ->
            val raw = prefs[mediaPeopleKey] ?: "{}"
            val obj = try { JSONObject(raw) } catch (e: Exception) { JSONObject() }
            val arr = obj.optJSONArray(uri) ?: JSONArray()
            val existing = mutableSetOf<String>()
            for (i in 0 until arr.length()) existing.add(arr.getString(i))
            if (existing.add(trimmed)) {
                val newArr = JSONArray(existing)
                obj.put(uri, newArr)
                prefs[mediaPeopleKey] = obj.toString()
            }
        }
    }
}
