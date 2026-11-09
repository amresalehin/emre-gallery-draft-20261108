package com.amresalehin.emregallery

import com.amresalehin.emregallery.ui.Destination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsScreenCardsTest {

    @Test
    fun testIndexingStatusDestinationRemoved() {
        val destNames = Destination.values().map { it.name }
        assertFalse("Destination enum must NOT contain INDEXING_STATUS", destNames.contains("INDEXING_STATUS"))
        assertTrue("Destination enum must contain SETTINGS", destNames.contains("SETTINGS"))
        assertTrue("Destination enum must contain POCKET", destNames.contains("POCKET"))
    }

    @Test
    fun testSettingsSearchQueryMatchingOcrAndFace() {
        fun matchesOcr(query: String): Boolean {
            return query.isBlank() ||
                "ocr".contains(query, ignoreCase = true) ||
                "text".contains(query, ignoreCase = true) ||
                "recognition".contains(query, ignoreCase = true) ||
                "language".contains(query, ignoreCase = true) ||
                "tesseract".contains(query, ignoreCase = true) ||
                "ml kit".contains(query, ignoreCase = true) ||
                "scan".contains(query, ignoreCase = true)
        }

        fun matchesFace(query: String): Boolean {
            return query.isBlank() ||
                "face".contains(query, ignoreCase = true) ||
                "people".contains(query, ignoreCase = true) ||
                "recognition".contains(query, ignoreCase = true) ||
                "clustering".contains(query, ignoreCase = true) ||
                "buffalo".contains(query, ignoreCase = true) ||
                "ente".contains(query, ignoreCase = true)
        }

        // Empty query matches both
        assertTrue(matchesOcr(""))
        assertTrue(matchesFace(""))

        // Query "ocr" matches OCR
        assertTrue(matchesOcr("ocr"))
        assertFalse(matchesFace("ocr"))

        // Query "face" matches Face
        assertFalse(matchesOcr("face"))
        assertTrue(matchesFace("face"))

        // Query "recognition" matches both
        assertTrue(matchesOcr("recognition"))
        assertTrue(matchesFace("recognition"))
    }

    @Test
    fun testCollapsibleCardsExpansionQueryLogic() {
        fun isCardExpanded(query: String, matches: Boolean): Boolean = query.isNotBlank() && matches

        // By default with blank query, cards are collapsed for clean visual hierarchy
        assertFalse(isCardExpanded("", matches = true))
        assertFalse(isCardExpanded("   ", matches = true))

        // When user specifically searches for card keyword, matching card expands automatically
        assertTrue(isCardExpanded("ocr", matches = true))
        assertFalse(isCardExpanded("ocr", matches = false))
        assertTrue(isCardExpanded("face", matches = true))
        assertTrue(isCardExpanded("tag", matches = true))
    }

    @Test
    fun testSettingsSearchQueryMatchingTagging() {
        fun matchesTagging(query: String): Boolean {
            return query.isBlank() ||
                "tag".contains(query, ignoreCase = true) ||
                "tagging".contains(query, ignoreCase = true) ||
                "ai tagging".contains(query, ignoreCase = true) ||
                "labels".contains(query, ignoreCase = true) ||
                "florence".contains(query, ignoreCase = true) ||
                "keywords".contains(query, ignoreCase = true)
        }

        assertTrue(matchesTagging(""))
        assertTrue(matchesTagging("tag"))
        assertTrue(matchesTagging("tagging"))
        assertTrue(matchesTagging("florence"))
        assertFalse(matchesTagging("theme"))
    }

    @Test
    fun testTaggingFolderScopingLogic() {
        val photos = listOf(
            "DCIM/Camera/IMG_01.jpg",
            "Pictures/Screenshots/SCR_01.png",
            "Download/MEME.jpg"
        )

        fun filterPhotos(allFolders: Boolean, selectedFolders: Set<String>): List<String> {
            if (allFolders || selectedFolders.isEmpty()) return photos
            return photos.filter { path ->
                val folder = path.substringBeforeLast('/').trim('/')
                selectedFolders.contains(folder)
            }
        }

        // All folders selected: all 3 photos
        assertEquals(3, filterPhotos(allFolders = true, emptySet()).size)

        // Only Screenshots folder selected
        val screenshotsOnly = filterPhotos(allFolders = false, setOf("Pictures/Screenshots"))
        assertEquals(1, screenshotsOnly.size)
        assertEquals("Pictures/Screenshots/SCR_01.png", screenshotsOnly.first())

        // Multiple folders selected
        val cameraAndDownload = filterPhotos(allFolders = false, setOf("DCIM/Camera", "Download"))
        assertEquals(2, cameraAndDownload.size)
    }

    @Test
    fun testGeoTimelineFolderExtractionListsAllFolders() {
        val mediaFolders = listOf(
            "DCIM/Camera",
            "Pictures/Screenshots",
            "Download",
            "Pictures/Instagram",
            "DCIM/Vacation",
            "Pictures/Wallpapers",
            "DCIM/Family",
            "Pictures/WhatsApp"
        )

        fun extractFolders(paths: List<String>): List<String> {
            val list = mutableListOf("All")
            val names = paths.asSequence()
                .filter { it.isNotBlank() }
                .map { it.trim('/').substringAfterLast('/') }
                .filter { it.isNotBlank() }
                .distinct()
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                .toList()
            list.addAll(names)
            return list
        }

        val extracted = extractFolders(mediaFolders)
        // Must contain All and all 8 distinct folders without pick pill (total 9 items)
        assertEquals(9, extracted.size)
        assertTrue(extracted.contains("All"))
        assertFalse(extracted.contains("+ Pick"))
        assertTrue(extracted.contains("Wallpapers"))
        assertTrue(extracted.contains("Instagram"))
        assertTrue(extracted.contains("Vacation"))
        assertTrue(extracted.contains("Family"))
        assertTrue(extracted.contains("Camera"))
        assertTrue(extracted.contains("Screenshots"))
    }

    @Test
    fun testGeoTimelineFolderNamesDoNotIncludeFullPaths() {
        val paths = listOf(
            "DCIM/Camera",
            "Pictures/Screenshots",
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images",
            "Download/Telegram/Subfolder"
        )
        val finalNames = paths.map { it.trim('/').substringAfterLast('/') }
        assertEquals(listOf("Camera", "Screenshots", "WhatsApp Images", "Subfolder"), finalNames)
        for (name in finalNames) {
            assertFalse("Folder display name '$name' must not contain path separators", name.contains('/'))
        }
    }

    @Test
    fun testGeoTimelineMultiSelectPillsTapToSelectAndUnselect() {
        var isAllFolders = true
        var selectedFolders = setOf<String>()
        val allAvailableFolders = listOf("DCIM/Camera", "Pictures/Screenshots", "Download")

        fun onToggleFolder(folderPath: String) {
            if (isAllFolders) {
                isAllFolders = false
                selectedFolders = setOf(folderPath)
            } else {
                val next = if (folderPath in selectedFolders) {
                    selectedFolders - folderPath
                } else {
                    selectedFolders + folderPath
                }
                if (next.isEmpty() || next.size >= allAvailableFolders.size) {
                    isAllFolders = true
                    selectedFolders = emptySet()
                } else {
                    selectedFolders = next
                }
            }
        }

        // Initially All
        assertTrue(isAllFolders)
        assertTrue(selectedFolders.isEmpty())

        // Tap Camera -> selected
        onToggleFolder("DCIM/Camera")
        assertFalse(isAllFolders)
        assertEquals(setOf("DCIM/Camera"), selectedFolders)

        // Tap Screenshots -> multiple selection allowed
        onToggleFolder("Pictures/Screenshots")
        assertFalse(isAllFolders)
        assertEquals(setOf("DCIM/Camera", "Pictures/Screenshots"), selectedFolders)

        // Tap Camera again -> unselects Camera
        onToggleFolder("DCIM/Camera")
        assertFalse(isAllFolders)
        assertEquals(setOf("Pictures/Screenshots"), selectedFolders)

        // Tap Screenshots again -> becomes empty, reverts to All Folders
        onToggleFolder("Pictures/Screenshots")
        assertTrue(isAllFolders)
        assertTrue(selectedFolders.isEmpty())
    }
}

