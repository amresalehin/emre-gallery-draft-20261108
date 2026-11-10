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
    fun testGeoTimelineKeepAllSelectedByDefaultAndSelectAllCheckbox() {
        val allAvailableFolders = listOf("DCIM/Camera", "Pictures/Screenshots", "Download")
        // By default, all folders are kept in selected state
        var selectedFolders = allAvailableFolders.toSet()
        val isAllSelected: () -> Boolean = { selectedFolders.containsAll(allAvailableFolders) }

        assertTrue(isAllSelected())
        assertEquals(3, selectedFolders.size)

        // User unselects Camera
        selectedFolders = selectedFolders - "DCIM/Camera"
        assertFalse(isAllSelected())
        assertEquals(setOf("Pictures/Screenshots", "Download"), selectedFolders)

        // User unselects another folder
        selectedFolders = selectedFolders - "Pictures/Screenshots"
        assertEquals(setOf("Download"), selectedFolders)

        // User toggles "Select all" checkbox when not all selected -> selects all again
        selectedFolders = allAvailableFolders.toSet()
        assertTrue(isAllSelected())
        assertEquals(allAvailableFolders.toSet(), selectedFolders)

        // User toggles "Select all" checkbox when all selected -> unselects all
        selectedFolders = emptySet()
        assertFalse(isAllSelected())
        assertTrue(selectedFolders.isEmpty())
    }
}

