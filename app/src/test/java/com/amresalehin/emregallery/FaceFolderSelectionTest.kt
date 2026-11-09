package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.MediaItem
import com.amresalehin.emregallery.face.FaceRecognitionSettings
import com.amresalehin.emregallery.ui.FaceFolderInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceFolderSelectionTest {

    @Test
    fun testNormalizeFolderPath() {
        assertEquals("DCIM/Camera", FaceRecognitionSettings.normalizeFolderPath("DCIM/Camera/"))
        assertEquals("DCIM/Camera", FaceRecognitionSettings.normalizeFolderPath("/DCIM/Camera"))
        assertEquals("DCIM/Camera", FaceRecognitionSettings.normalizeFolderPath("   DCIM/Camera/  "))
        assertEquals("Pictures/Family", FaceRecognitionSettings.normalizeFolderPath("Pictures/Family"))
        assertEquals("Internal storage", FaceRecognitionSettings.normalizeFolderPath(""))
        assertEquals("Internal storage", FaceRecognitionSettings.normalizeFolderPath("   "))
        assertEquals("Internal storage", FaceRecognitionSettings.normalizeFolderPath("/"))
        assertEquals("Internal storage", FaceRecognitionSettings.normalizeFolderPath("///"))
    }

    @Test
    fun testFolderInfoCreationAndGrouping() {
        val items = listOf(
            MediaItem(1, "uri1", "img1.jpg", null, 0, 0, 0, 0, 0, "DCIM/Camera/", false),
            MediaItem(2, "uri2", "img2.jpg", null, 0, 0, 0, 0, 0, "DCIM/Camera", false),
            MediaItem(3, "uri3", "img3.jpg", null, 0, 0, 0, 0, 0, "Pictures/Screenshots", false),
            MediaItem(4, "uri4", "video1.mp4", null, 0, 0, 0, 0, 0, "DCIM/Camera", true),
            MediaItem(5, "uri5", "img4.jpg", null, 0, 0, 0, 0, 0, "", false)
        )

        val photos = items.filterNot { it.isVideo }
        val folders = photos
            .groupBy { FaceRecognitionSettings.normalizeFolderPath(it.relativePath) }
            .map { (path, folderPhotos) ->
                FaceFolderInfo(
                    path = path,
                    name = path.substringAfterLast('/').ifBlank { "Internal storage" },
                    photoCount = folderPhotos.size,
                    coverUri = folderPhotos.firstOrNull()?.uri
                )
            }
            .sortedByDescending { it.photoCount }

        assertEquals(3, folders.size)
        val cameraFolder = folders.find { it.path == "DCIM/Camera" }
        val screenshotFolder = folders.find { it.path == "Pictures/Screenshots" }
        val rootFolder = folders.find { it.path == "Internal storage" }

        assertEquals(2, cameraFolder?.photoCount)
        assertEquals("Camera", cameraFolder?.name)
        assertEquals(1, screenshotFolder?.photoCount)
        assertEquals("Screenshots", screenshotFolder?.name)
        assertEquals(1, rootFolder?.photoCount)
        assertEquals("Internal storage", rootFolder?.name)
    }

    @Test
    fun testSelectiveFolderFiltering() {
        val items = listOf(
            MediaItem(1, "uri1", "img1.jpg", null, 0, 0, 0, 0, 0, "DCIM/Camera", false),
            MediaItem(2, "uri2", "img2.jpg", null, 0, 0, 0, 0, 0, "Pictures/Family", false),
            MediaItem(3, "uri3", "img3.jpg", null, 0, 0, 0, 0, 0, "Download", false)
        )

        val selectedFolders = setOf("DCIM/Camera", "Download")

        val filtered = items.filter { item ->
            FaceRecognitionSettings.normalizeFolderPath(item.relativePath) in selectedFolders
        }

        assertEquals(2, filtered.size)
        assertTrue(filtered.any { it.uri == "uri1" })
        assertFalse(filtered.any { it.uri == "uri2" })
        assertTrue(filtered.any { it.uri == "uri3" })
    }
}
