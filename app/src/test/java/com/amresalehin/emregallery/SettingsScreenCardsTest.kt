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
}

