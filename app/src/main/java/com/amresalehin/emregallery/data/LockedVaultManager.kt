package com.amresalehin.emregallery.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class LockedVaultManager(private val context: Context) {

    private val vaultDir: File = File(context.filesDir, "locked_vault").apply {
        if (!exists()) mkdirs()
    }

    private val metaFile: File = File(vaultDir, "vault_metadata.json")

    fun getVaultItems(): List<MediaItem> {
        if (!metaFile.exists()) return emptyList()
        return try {
            val jsonStr = metaFile.readText()
            val array = JSONArray(jsonStr)
            val list = mutableListOf<MediaItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val uriStr = obj.getString("uri")
                val file = File(Uri.parse(uriStr).path ?: "")
                if (file.exists()) {
                    list.add(
                        MediaItem(
                            id = obj.getLong("id"),
                            uri = uriStr,
                            name = obj.getString("name"),
                            mimeType = if (obj.has("mimeType") && !obj.isNull("mimeType")) obj.getString("mimeType") else null,
                            dateAddedSeconds = obj.optLong("dateAddedSeconds", System.currentTimeMillis() / 1000L),
                            dateTakenMillis = obj.optLong("dateTakenMillis", 0L),
                            sizeBytes = file.length(),
                            width = obj.optInt("width", 0),
                            height = obj.optInt("height", 0),
                            relativePath = "Locked",
                            isVideo = obj.optBoolean("isVideo", false),
                            durationMillis = obj.optLong("durationMillis", 0L),
                            isTrashed = false
                        )
                    )
                }
            }
            list.sortedByDescending { if (it.dateTakenMillis > 0) it.dateTakenMillis else it.dateAddedSeconds * 1000L }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveVaultItems(items: List<MediaItem>) {
        val array = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("uri", item.uri)
                put("name", item.name)
                put("mimeType", item.mimeType)
                put("dateAddedSeconds", item.dateAddedSeconds)
                put("dateTakenMillis", item.dateTakenMillis)
                put("width", item.width)
                put("height", item.height)
                put("isVideo", item.isVideo)
                put("durationMillis", item.durationMillis)
            }
            array.put(obj)
        }
        metaFile.writeText(array.toString())
    }

    /**
     * Copies media from mainline storage into app-private locked vault
     * and deletes the original file from mainline storage (MediaStore).
     */
    suspend fun lockItems(items: List<MediaItem>): List<MediaItem> = withContext(Dispatchers.IO) {
        val currentVaultItems = getVaultItems().toMutableList()
        val lockedList = mutableListOf<MediaItem>()

        for (item in items) {
            try {
                val safeName = item.name.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
                val destFile = File(vaultDir, "vault_${item.id}_$safeName")
                val srcUri = Uri.parse(item.uri)

                // Copy stream to isolated vault file
                context.contentResolver.openInputStream(srcUri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                if (destFile.exists() && destFile.length() > 0) {
                    val lockedItem = item.copy(
                        uri = Uri.fromFile(destFile).toString(),
                        relativePath = "Locked",
                        sizeBytes = destFile.length()
                    )
                    currentVaultItems.removeAll { it.id == item.id || it.uri == lockedItem.uri }
                    currentVaultItems.add(lockedItem)
                    lockedList.add(lockedItem)

                    // Remove original from device mainline storage
                    runCatching {
                        context.contentResolver.delete(srcUri, null, null)
                    }
                }
            } catch (_: Exception) {
            }
        }

        saveVaultItems(currentVaultItems)
        lockedList
    }

    /**
     * Restores media items back from locked vault to mainline storage
     */
    suspend fun unlockItems(items: List<MediaItem>): List<String> = withContext(Dispatchers.IO) {
        val currentVaultItems = getVaultItems().toMutableList()
        val restoredUris = mutableListOf<String>()

        for (item in items) {
            try {
                val srcFile = File(Uri.parse(item.uri).path ?: "")
                if (!srcFile.exists()) continue

                val collection = if (item.isVideo) {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }

                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType ?: if (item.isVideo) "video/mp4" else "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Restored")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }

                val insertedUri = context.contentResolver.insert(collection, cv)
                if (insertedUri != null) {
                    srcFile.inputStream().use { input ->
                        context.contentResolver.openOutputStream(insertedUri)?.use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cv.clear()
                        cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        context.contentResolver.update(insertedUri, cv, null, null)
                    }
                    srcFile.delete()
                    currentVaultItems.removeAll { it.uri == item.uri || it.id == item.id }
                    restoredUris.add(insertedUri.toString())
                }
            } catch (_: Exception) {
            }
        }

        saveVaultItems(currentVaultItems)
        restoredUris
    }

    /**
     * Permanently deletes items from the locked vault
     */
    suspend fun deletePermanently(items: List<MediaItem>) = withContext(Dispatchers.IO) {
        val currentVaultItems = getVaultItems().toMutableList()
        for (item in items) {
            try {
                val file = File(Uri.parse(item.uri).path ?: "")
                if (file.exists()) file.delete()
                currentVaultItems.removeAll { it.uri == item.uri || it.id == item.id }
            } catch (_: Exception) {
            }
        }
        saveVaultItems(currentVaultItems)
    }
}
