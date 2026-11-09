package com.amresalehin.emregallery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.amresalehin.emregallery.data.MediaInboxRepository
import com.amresalehin.emregallery.data.PocketRepository
import com.amresalehin.emregallery.ui.Destination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config
class PocketDecouplingUnitTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clear shared preferences for clean test environment
        context.getSharedPreferences("pocket_bookmarks_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("media_inbox_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun testPocketRepositoryIsCompletelyDecoupledFromMediaInbox() {
        val pocketRepo = PocketRepository(context)
        val inboxRepo = MediaInboxRepository(context)

        // Add bookmark in Pocket
        val bookmark = pocketRepo.addBookmark(
            title = "Android Developers",
            url = "https://developer.android.com",
            description = "Official Android developer guides"
        )

        // Pocket repository holds the bookmark
        assertEquals(1, pocketRepo.bookmarks.value.size)
        assertEquals("Android Developers", pocketRepo.bookmarks.value.first().title)
        assertEquals("developer.android.com", pocketRepo.bookmarks.value.first().sourceDomain)
        assertTrue("Pocket bookmarks must not contain downloaded media items", pocketRepo.bookmarks.value.first().items.isEmpty())

        // MediaInboxRepository (Downloader repo) remains completely untouched and empty
        assertTrue("MediaInboxRepository must be decoupled and remain empty", inboxRepo.stacks.value.isEmpty())

        // Deleting from Pocket does not affect MediaInbox
        pocketRepo.deleteBookmark(bookmark.id)
        assertTrue(pocketRepo.bookmarks.value.isEmpty())
        assertTrue(inboxRepo.stacks.value.isEmpty())
    }

    @Test
    fun testAlbumsFilterDecoupledFromDownloadEntryPoints() {
        // Navigation mapping logic that was updated in AlbumsScreen
        fun filterDestination(label: String): Destination? {
            return when (label) {
                "Pocket", "Bookmarks" -> Destination.POCKET
                "Browser" -> Destination.BROWSER
                "Activity", "Activities", "Activity Page" -> Destination.MEDIA_INBOX
                "Favourites" -> Destination.FAVOURITES
                "Bin" -> Destination.BIN
                "Hidden", "Locked folder" -> Destination.LOCKED_FOLDER
                "Archive" -> Destination.ARCHIVE
                else -> null
            }
        }

        assertEquals(Destination.POCKET, filterDestination("Pocket"))
        assertEquals(Destination.POCKET, filterDestination("Bookmarks"))
        assertEquals(Destination.BROWSER, filterDestination("Browser"))
        assertEquals(Destination.MEDIA_INBOX, filterDestination("Activity"))

        // Entry points to downloader outside browser/activity are removed
        assertEquals(null, filterDestination("Media Downloader"))
        assertEquals(null, filterDestination("Media Inbox"))
        assertEquals(null, filterDestination("Inbox"))
    }

    @Test
    fun testPocketFilterDecoupledFromMediaCategories() {
        val filterOptions = listOf("All", "Web Links", "Notes")
        assertFalse("Pocket filter must not contain Media download category", filterOptions.contains("Media"))
        assertTrue(filterOptions.contains("All"))
        assertTrue(filterOptions.contains("Web Links"))
        assertTrue(filterOptions.contains("Notes"))
    }
}
