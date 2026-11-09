package com.amresalehin.emregallery

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.amresalehin.emregallery.data.*
import com.amresalehin.emregallery.ocr.TesseractOcr
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.osmdroid.util.GeoPoint
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config
class SearchEngineTest {

    private lateinit var context: Context
    private lateinit var searchEngine: SearchEngine

    private val sampleMedia = listOf(
        MediaItem(
            id = 1L,
            uri = "content://media/external/images/media/1",
            name = "Mount_Everest.jpg",
            mimeType = "image/jpeg",
            dateAddedSeconds = 1700000000L,
            dateTakenMillis = 1700000000000L,
            sizeBytes = 2048L,
            width = 1920,
            height = 1080,
            relativePath = "DCIM/Camera",
            isVideo = false
        ),
        MediaItem(
            id = 2L,
            uri = "content://media/external/images/media/2",
            name = "Mountain_Sunset.mp4",
            mimeType = "video/mp4",
            dateAddedSeconds = 1710000000L,
            dateTakenMillis = 1710000000000L,
            sizeBytes = 10485760L,
            width = 1920,
            height = 1080,
            relativePath = "DCIM/Camera",
            isVideo = true
        ),
        MediaItem(
            id = 3L,
            uri = "content://media/external/images/media/3",
            name = "Beach_Day.jpg",
            mimeType = "image/jpeg",
            dateAddedSeconds = 1690000000L,
            dateTakenMillis = 1690000000000L,
            sizeBytes = 4096L,
            width = 1080,
            height = 1920,
            relativePath = "Pictures/Vacation",
            isVideo = false
        ),
        MediaItem(
            id = 4L,
            uri = "content://media/external/images/media/4",
            name = "Party_Animation.gif",
            mimeType = "image/gif",
            dateAddedSeconds = 1715000000L,
            dateTakenMillis = 1715000000000L,
            sizeBytes = 204800L,
            width = 500,
            height = 500,
            relativePath = "Pictures/Gifs",
            isVideo = false
        )
    )

    private val sampleAlbums = listOf(
        CustomAlbum(
            id = "album_mountains",
            name = "Mountains Trip",
            parentPath = "",
            mediaUris = setOf(
                "content://media/external/images/media/1",
                "content://media/external/images/media/2"
            )
        )
    )

    private val sampleTags = mapOf(
        "content://media/external/images/media/1" to setOf("trekking", "nature"),
        "content://media/external/images/media/3" to setOf("beach", "summer")
    )

    private val samplePeople = mapOf(
        "content://media/external/images/media/1" to setOf("John Doe")
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        searchEngine = SearchEngine(context)
    }

    @Test
    fun testLiveSuggestions_maxMatchesPerCategory() = runBlocking {
        val query = "Mount"
        val suggestions = searchEngine.getLiveSuggestions(
            query = query,
            media = sampleMedia,
            recentSearches = listOf("Mountain hike"),
            customAlbums = sampleAlbums,
            customTagsMap = sampleTags,
            customPeopleMap = samplePeople
        )

        assertTrue("Should have suggestions for 'Mount'", suggestions.isNotEmpty())

        // Verify max 2 per category rule
        val byType = suggestions.groupBy { it.type }
        for ((type, items) in byType) {
            assertTrue("Category $type must have at most 2 suggestions but had ${items.size}", items.size <= 2)
        }

        // Verify matching items only
        assertTrue("Album suggestion should match query", suggestions.any { it.type == SuggestionType.ALBUM && it.title.contains("Mount") })
        assertTrue("File suggestion should match query", suggestions.any { it.type == SuggestionType.FILE && it.title.contains("Mount") })
    }

    @Test
    fun testSearchMedia_filtersAndMatching() = runBlocking {
        // Query "Mount"
        val results = searchEngine.searchMedia(
            query = "Mount",
            media = sampleMedia,
            filter = SearchFilterState(),
            sortMode = SearchSortMode.RELEVANCE,
            customAlbums = sampleAlbums,
            customTagsMap = sampleTags,
            customPeopleMap = samplePeople,
            favourites = emptySet()
        )

        assertEquals("Should match the 2 mountain items", 2, results.items.size)
        assertTrue(results.items.any { it.name == "Mount_Everest.jpg" })
        assertTrue(results.items.any { it.name == "Mountain_Sunset.mp4" })

        // Filter by Videos Only
        val videoResults = searchEngine.searchMedia(
            query = "Mount",
            media = sampleMedia,
            filter = SearchFilterState(mediaType = MediaTypeFilter.VIDEOS),
            sortMode = SearchSortMode.RELEVANCE,
            customAlbums = sampleAlbums,
            customTagsMap = sampleTags,
            customPeopleMap = samplePeople,
            favourites = emptySet()
        )
        assertEquals(1, videoResults.items.size)
        assertTrue(videoResults.items.first().isVideo)
        assertEquals("Mountain_Sunset.mp4", videoResults.items.first().name)

        // Dynamic chips should include matching album
        assertTrue(results.dynamicChips.any { it.kind == ChipKind.ALBUM && it.label == "Mountains Trip" })
    }

    @Test
    fun testSearchMedia_tagMatching() = runBlocking {
        val results = searchEngine.searchMedia(
            query = "trekking",
            media = sampleMedia,
            filter = SearchFilterState(),
            sortMode = SearchSortMode.RELEVANCE,
            customAlbums = sampleAlbums,
            customTagsMap = sampleTags,
            customPeopleMap = samplePeople,
            favourites = emptySet()
        )

        assertEquals(1, results.items.size)
        assertEquals("Mount_Everest.jpg", results.items.first().name)
    }

    @Test
    fun testDiscoveryContent_realEntitiesOnly() = runBlocking {
        val discovery = searchEngine.getDiscoveryContent(
            media = sampleMedia,
            customAlbums = sampleAlbums,
            customTagsMap = sampleTags,
            customPeopleMap = samplePeople,
            favourites = setOf("content://media/external/images/media/1")
        )

        // Non-empty sections exist
        assertTrue(discovery.categories.isNotEmpty())
        assertTrue(discovery.albums.any { it.title == "Mountains Trip" })
        assertTrue(discovery.tags.any { it.title == "trekking" })
        assertTrue(discovery.people.any { it.title == "John Doe" })
    }

    @Test
    fun testSearchMedia_mediaTypeFilters_imagesVideosGifs() = runBlocking {
        // 1. Photos / Images filter
        val imageResults = searchEngine.searchMedia(
            query = "",
            media = sampleMedia,
            filter = SearchFilterState(mediaType = MediaTypeFilter.PHOTOS),
            sortMode = SearchSortMode.RELEVANCE,
            customAlbums = emptyList(),
            customTagsMap = emptyMap(),
            customPeopleMap = emptyMap(),
            favourites = emptySet()
        )
        // Mount_Everest.jpg and Beach_Day.jpg (Party_Animation.gif and Mountain_Sunset.mp4 excluded)
        assertEquals(2, imageResults.items.size)
        assertTrue(imageResults.items.all { !it.isVideo && it.name.endsWith(".jpg") })

        // 2. Videos filter
        val videoResults = searchEngine.searchMedia(
            query = "",
            media = sampleMedia,
            filter = SearchFilterState(mediaType = MediaTypeFilter.VIDEOS),
            sortMode = SearchSortMode.RELEVANCE,
            customAlbums = emptyList(),
            customTagsMap = emptyMap(),
            customPeopleMap = emptyMap(),
            favourites = emptySet()
        )
        assertEquals(1, videoResults.items.size)
        assertEquals("Mountain_Sunset.mp4", videoResults.items.first().name)

        // 3. Gifs filter
        val gifResults = searchEngine.searchMedia(
            query = "",
            media = sampleMedia,
            filter = SearchFilterState(mediaType = MediaTypeFilter.GIFS),
            sortMode = SearchSortMode.RELEVANCE,
            customAlbums = emptyList(),
            customTagsMap = emptyMap(),
            customPeopleMap = emptyMap(),
            favourites = emptySet()
        )
        assertEquals(1, gifResults.items.size)
        assertEquals("Party_Animation.gif", gifResults.items.first().name)
    }

    @Test
    fun testSearchMedia_gpsFilters() = runBlocking {
        val locationCache = PhotoMapLocationCache(context)
        locationCache.save("content://media/external/images/media/1", GeoPoint(27.9881, 86.9250))
        locationCache.save("content://media/external/images/media/3", null)

        val gpsResults = searchEngine.searchMedia(
            query = "",
            media = sampleMedia,
            filter = SearchFilterState(gpsFilter = GpsFilter.HAS_GPS),
            sortMode = SearchSortMode.RELEVANCE,
            customAlbums = emptyList(),
            customTagsMap = emptyMap(),
            customPeopleMap = emptyMap(),
            favourites = emptySet()
        )
        assertTrue(gpsResults.items.any { it.uri == "content://media/external/images/media/1" })
        assertFalse(gpsResults.items.any { it.uri == "content://media/external/images/media/3" })

        val noGpsResults = searchEngine.searchMedia(
            query = "",
            media = sampleMedia,
            filter = SearchFilterState(gpsFilter = GpsFilter.NO_GPS),
            sortMode = SearchSortMode.RELEVANCE,
            customAlbums = emptyList(),
            customTagsMap = emptyMap(),
            customPeopleMap = emptyMap(),
            favourites = emptySet()
        )
        assertTrue(noGpsResults.items.any { it.uri == "content://media/external/images/media/3" })
        assertFalse(noGpsResults.items.any { it.uri == "content://media/external/images/media/1" })
    }

    @Test
    fun testSearchMedia_ocrTextFilters() = runBlocking {
        TesseractOcr.saveCachedResult(context, Uri.parse("content://media/external/images/media/1"), "Welcome to Everest Base Camp")

        val textResults = searchEngine.searchMedia(
            query = "",
            media = sampleMedia,
            filter = SearchFilterState(ocrTextFilter = OcrTextFilter.HAS_TEXT),
            sortMode = SearchSortMode.RELEVANCE,
            customAlbums = emptyList(),
            customTagsMap = emptyMap(),
            customPeopleMap = emptyMap(),
            favourites = emptySet()
        )
        assertTrue(textResults.items.any { it.uri == "content://media/external/images/media/1" })
        assertFalse(textResults.items.any { it.uri == "content://media/external/images/media/3" })

        val withoutTextResults = searchEngine.searchMedia(
            query = "",
            media = sampleMedia,
            filter = SearchFilterState(ocrTextFilter = OcrTextFilter.NO_TEXT),
            sortMode = SearchSortMode.RELEVANCE,
            customAlbums = emptyList(),
            customTagsMap = emptyMap(),
            customPeopleMap = emptyMap(),
            favourites = emptySet()
        )
        assertTrue(withoutTextResults.items.any { it.uri == "content://media/external/images/media/3" })
        assertFalse(withoutTextResults.items.any { it.uri == "content://media/external/images/media/1" })
    }

    @Test
    fun testSearchMedia_faceFilters() = runBlocking {
        // media/1 has John Doe in samplePeople
        val faceResults = searchEngine.searchMedia(
            query = "",
            media = sampleMedia,
            filter = SearchFilterState(faceFilter = FaceFilter.HAS_FACE),
            sortMode = SearchSortMode.RELEVANCE,
            customAlbums = emptyList(),
            customTagsMap = emptyMap(),
            customPeopleMap = samplePeople,
            favourites = emptySet()
        )
        assertTrue(faceResults.items.any { it.uri == "content://media/external/images/media/1" })
        assertFalse(faceResults.items.any { it.uri == "content://media/external/images/media/3" })

        val noFaceResults = searchEngine.searchMedia(
            query = "",
            media = sampleMedia,
            filter = SearchFilterState(faceFilter = FaceFilter.NO_FACE),
            sortMode = SearchSortMode.RELEVANCE,
            customAlbums = emptyList(),
            customTagsMap = emptyMap(),
            customPeopleMap = samplePeople,
            favourites = emptySet()
        )
        assertTrue(noFaceResults.items.any { it.uri == "content://media/external/images/media/3" })
        assertFalse(noFaceResults.items.any { it.uri == "content://media/external/images/media/1" })
    }
}
