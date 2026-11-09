package com.amresalehin.emregallery.data

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.vector.ImageVector
import com.amresalehin.emregallery.face.BuffaloLFaceEngine
import com.amresalehin.emregallery.face.FaceClusterStore
import com.amresalehin.emregallery.ocr.TesseractOcr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class SuggestionType {
    RECENT,
    PERSON,
    PLACE,
    ALBUM,
    FILE,
    TAG,
    DOCUMENT
}

data class SearchSuggestion(
    val id: String,
    val type: SuggestionType,
    val title: String,
    val subtitle: String? = null,
    val thumbnailUri: String? = null,
    val isPersonPortrait: Boolean = false,
    val personFaceIndex: Int? = null,
    val targetQuery: String? = null,
    val targetFilter: SearchFilterState? = null
)

data class DiscoveryCategory(
    val id: String,
    val title: String,
    val itemCount: Int,
    val sampleUris: List<String>,
    val queryAction: String? = null,
    val filterAction: SearchFilterState? = null
)

data class DiscoveryEntityItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val thumbnailUri: String? = null,
    val isPerson: Boolean = false,
    val personFaceIndex: Int? = null,
    val queryAction: String? = null,
    val filterAction: SearchFilterState? = null
)

enum class MediaTypeFilter(val label: String) {
    ALL("All media"),
    PHOTOS("Images"),
    VIDEOS("Videos"),
    GIFS("Gifs");

    companion object {
        val IMAGES = PHOTOS
    }
}

enum class GpsFilter(val label: String) {
    ALL("All"),
    HAS_GPS("GPS"),
    NO_GPS("No GPS")
}

enum class OcrTextFilter(val label: String) {
    ALL("All"),
    HAS_TEXT("Text"),
    NO_TEXT("Without Text")
}

enum class FaceFilter(val label: String) {
    ALL("All"),
    HAS_FACE("Face"),
    NO_FACE("No Face")
}

sealed class TimeRangeFilter(val label: String) {
    object AllTime : TimeRangeFilter("Time")
    object Last30Days : TimeRangeFilter("Last 30 days")
    object Last90Days : TimeRangeFilter("Last 90 days")
    data class Custom(val startMillis: Long, val endMillis: Long) : TimeRangeFilter("Custom date")
}

enum class SearchSortMode(val label: String) {
    RELEVANCE("Most relevant"),
    NEWEST_FIRST("Newest first"),
    OLDEST_FIRST("Oldest first"),
    NAME("Name A–Z")
}

enum class SearchViewMode(val label: String) {
    MASONRY("Masonry"),
    GRID("Grid (3×3)"),
    LARGE_GRID("Large grid (2×2)"),
    LIST("List")
}

data class SearchFilterState(
    val mediaType: MediaTypeFilter = MediaTypeFilter.ALL,
    val timeRange: TimeRangeFilter = TimeRangeFilter.AllTime,
    val favoritesOnly: Boolean = false,
    val selectedAlbumId: String? = null,
    val selectedPersonId: String? = null,
    val selectedPlace: String? = null,
    val selectedTag: String? = null,
    val gpsFilter: GpsFilter = GpsFilter.ALL,
    val ocrTextFilter: OcrTextFilter = OcrTextFilter.ALL,
    val faceFilter: FaceFilter = FaceFilter.ALL
) {
    val isDefault: Boolean
        get() = mediaType == MediaTypeFilter.ALL &&
                timeRange is TimeRangeFilter.AllTime &&
                !favoritesOnly &&
                selectedAlbumId == null &&
                selectedPersonId == null &&
                selectedPlace == null &&
                selectedTag == null &&
                gpsFilter == GpsFilter.ALL &&
                ocrTextFilter == OcrTextFilter.ALL &&
                faceFilter == FaceFilter.ALL

    val activeFilterCount: Int
        get() {
            var count = 0
            if (mediaType != MediaTypeFilter.ALL) count++
            if (timeRange !is TimeRangeFilter.AllTime) count++
            if (favoritesOnly) count++
            if (selectedAlbumId != null) count++
            if (selectedPersonId != null) count++
            if (selectedPlace != null) count++
            if (selectedTag != null) count++
            if (gpsFilter != GpsFilter.ALL) count++
            if (ocrTextFilter != OcrTextFilter.ALL) count++
            if (faceFilter != FaceFilter.ALL) count++
            return count
        }
}

/**
 * Cache and helper for geocoded place names associated with MediaItem URIs.
 */
object PlaceLocationResolver {
    private val uriToPlaceCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    suspend fun resolvePlace(context: Context, uri: String, lat: Double, lon: Double): String? {
        uriToPlaceCache[uri]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                if (Geocoder.isPresent()) {
                    val geocoder = Geocoder(context, Locale.getDefault())
                    val addresses = geocoder.getFromLocation(lat, lon, 1)
                    val addr = addresses?.firstOrNull()
                    if (addr != null) {
                        val placeName = addr.locality
                            ?: addr.subAdminArea
                            ?: addr.adminArea
                            ?: addr.featureName
                            ?: addr.countryName
                        if (!placeName.isNullOrBlank()) {
                            uriToPlaceCache[uri] = placeName
                            return@withContext placeName
                        }
                    }
                }
                null
            }.getOrNull()
        }
    }

    fun getCachedPlace(uri: String): String? = uriToPlaceCache[uri]
}

/**
 * Fast in-memory and database-backed search indexing and matching engine.
 */
class SearchEngine(private val context: Context) {

    private val faceStore = FaceClusterStore(context.applicationContext)
    private val locationCache = PhotoMapLocationCache(context.applicationContext)

    /**
     * Builds real discovery sections for State A (Search opened, no query entered).
     * OMIT categories and sections for which the library has no data!
     */
    suspend fun getDiscoveryContent(
        media: List<MediaItem>,
        customAlbums: List<CustomAlbum>,
        customTagsMap: Map<String, Set<String>>,
        customPeopleMap: Map<String, Set<String>>,
        favourites: Set<String>
    ): DiscoveryData = withContext(Dispatchers.IO) {
        val nonTrashMedia = media.filterNot { it.isTrashed }

        // 1. Confirmed / Recognized People from FaceClusterStore + customPeopleMap
        val personSummaries = runCatching {
            faceStore.personSummaries(includeHidden = false, engineId = BuffaloLFaceEngine.ENGINE_ID)
        }.getOrDefault(emptyList()).filter { it.faceCount > 0 && it.sampleUri != null }

        val peopleItems = mutableListOf<DiscoveryEntityItem>()
        for (summary in personSummaries.take(8)) {
            val name = summary.displayName.ifBlank { "Person ${summary.id.take(4)}" }
            peopleItems.add(
                DiscoveryEntityItem(
                    id = summary.id,
                    title = name,
                    subtitle = "${summary.faceCount} photos",
                    thumbnailUri = summary.sampleUri,
                    isPerson = true,
                    personFaceIndex = summary.portraitFaceIndex,
                    queryAction = name,
                    filterAction = SearchFilterState(selectedPersonId = summary.id)
                )
            )
        }

        // Custom people if not in cluster store
        for ((_, names) in customPeopleMap) {
            for (name in names) {
                if (name.isNotBlank() && peopleItems.none { it.title.equals(name, ignoreCase = true) }) {
                    val matchingPhotos = nonTrashMedia.filter { customPeopleMap[it.uri]?.contains(name) == true }
                    if (matchingPhotos.isNotEmpty()) {
                        peopleItems.add(
                            DiscoveryEntityItem(
                                id = "custom_person_$name",
                                title = name,
                                subtitle = "${matchingPhotos.size} photos",
                                thumbnailUri = matchingPhotos.firstOrNull()?.uri,
                                isPerson = true,
                                queryAction = name,
                                filterAction = SearchFilterState(selectedPersonId = "name:$name")
                            )
                        )
                    }
                }
            }
        }

        // 2. Real Places from PhotoMapLocationCache & folder locations
        val cachedLocations = runCatching { locationCache.loadAll() }.getOrDefault(emptyMap())
        val locatedMedia = nonTrashMedia.filter { item -> cachedLocations[item.uri]?.point != null }
        val placeItems = mutableListOf<DiscoveryEntityItem>()

        // Check if any have reverse-geocoded places
        val placeGroups = mutableMapOf<String, MutableList<MediaItem>>()
        for (item in locatedMedia) {
            val point = cachedLocations[item.uri]?.point ?: continue
            val placeName = PlaceLocationResolver.resolvePlace(context, item.uri, point.latitude, point.longitude)
            if (!placeName.isNullOrBlank()) {
                placeGroups.getOrPut(placeName) { mutableListOf() }.add(item)
            }
        }

        for ((placeName, items) in placeGroups.entries.sortedByDescending { it.value.size }.take(6)) {
            placeItems.add(
                DiscoveryEntityItem(
                    id = "place_$placeName",
                    title = placeName,
                    subtitle = "${items.size} photos",
                    thumbnailUri = items.firstOrNull()?.uri,
                    queryAction = placeName,
                    filterAction = SearchFilterState(selectedPlace = placeName)
                )
            )
        }

        // 3. Real Albums
        val albumItems = mutableListOf<DiscoveryEntityItem>()
        for (album in customAlbums.filter { it.mediaUris.isNotEmpty() }) {
            val sampleUri = album.mediaUris.firstOrNull()
            albumItems.add(
                DiscoveryEntityItem(
                    id = album.id,
                    title = album.name,
                    subtitle = "${album.mediaUris.size} items",
                    thumbnailUri = sampleUri,
                    queryAction = album.name,
                    filterAction = SearchFilterState(selectedAlbumId = album.id)
                )
            )
        }

        // Top device folders if custom albums are few
        val folderGroups = nonTrashMedia.groupBy {
            it.relativePath.substringBeforeLast('/', "").ifBlank { "Camera" }
        }.filter { it.value.isNotEmpty() }

        for ((folderName, folderMedia) in folderGroups.entries.sortedByDescending { it.value.size }.take(4)) {
            if (albumItems.none { it.title.equals(folderName, ignoreCase = true) }) {
                albumItems.add(
                    DiscoveryEntityItem(
                        id = "folder_$folderName",
                        title = folderName,
                        subtitle = "${folderMedia.size} items",
                        thumbnailUri = folderMedia.firstOrNull()?.uri,
                        queryAction = folderName,
                        filterAction = SearchFilterState(selectedAlbumId = "folder:$folderName")
                    )
                )
            }
        }

        // 4. Things / Tags
        val tagCounts = mutableMapOf<String, MutableList<MediaItem>>()
        for (item in nonTrashMedia) {
            val tags = customTagsMap[item.uri] ?: emptySet()
            for (t in tags) {
                if (t.isNotBlank()) {
                    tagCounts.getOrPut(t) { mutableListOf() }.add(item)
                }
            }
        }

        val tagItems = tagCounts.entries.sortedByDescending { it.value.size }.take(8).map { (tag, items) ->
            DiscoveryEntityItem(
                id = "tag_$tag",
                title = tag,
                subtitle = "${items.size} items",
                thumbnailUri = items.firstOrNull()?.uri,
                queryAction = tag,
                filterAction = SearchFilterState(selectedTag = tag)
            )
        }

        // 5. Category Shortcuts: Photos, Videos, Documents (OCR text items), Favourites
        val categories = mutableListOf<DiscoveryCategory>()
        val photos = nonTrashMedia.filterNot { it.isVideo }
        if (photos.isNotEmpty()) {
            categories.add(
                DiscoveryCategory(
                    id = "cat_photos",
                    title = "Photos",
                    itemCount = photos.size,
                    sampleUris = photos.take(3).map { it.uri },
                    filterAction = SearchFilterState(mediaType = MediaTypeFilter.PHOTOS)
                )
            )
        }

        val videos = nonTrashMedia.filter { it.isVideo }
        if (videos.isNotEmpty()) {
            categories.add(
                DiscoveryCategory(
                    id = "cat_videos",
                    title = "Videos",
                    itemCount = videos.size,
                    sampleUris = videos.take(3).map { it.uri },
                    filterAction = SearchFilterState(mediaType = MediaTypeFilter.VIDEOS)
                )
            )
        }

        // Documents: items with cached OCR text
        val documentItems = nonTrashMedia.filter {
            TesseractOcr.hasCachedResult(context, Uri.parse(it.uri))
        }
        if (documentItems.isNotEmpty()) {
            categories.add(
                DiscoveryCategory(
                    id = "cat_docs",
                    title = "Documents",
                    itemCount = documentItems.size,
                    sampleUris = documentItems.take(3).map { it.uri },
                    queryAction = "document"
                )
            )
        }

        if (favourites.isNotEmpty()) {
            val favItems = nonTrashMedia.filter { it.uri in favourites }
            if (favItems.isNotEmpty()) {
                categories.add(
                    DiscoveryCategory(
                        id = "cat_favs",
                        title = "Favorites",
                        itemCount = favItems.size,
                        sampleUris = favItems.take(3).map { it.uri },
                        filterAction = SearchFilterState(favoritesOnly = true)
                    )
                )
            }
        }

        DiscoveryData(
            categories = categories,
            people = peopleItems,
            places = placeItems,
            albums = albumItems,
            tags = tagItems
        )
    }

    /**
     * Computes live suggestions (State B) while actively typing.
     * Rule: at most 1–2 suggestions per category, and only categories that have real matches.
     */
    suspend fun getLiveSuggestions(
        query: String,
        media: List<MediaItem>,
        recentSearches: List<String>,
        customAlbums: List<CustomAlbum>,
        customTagsMap: Map<String, Set<String>>,
        customPeopleMap: Map<String, Set<String>>
    ): List<SearchSuggestion> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()

        val suggestions = mutableListOf<SearchSuggestion>()

        // 1. Recent matching searches
        val matchingRecents = recentSearches.filter { it.contains(q, ignoreCase = true) && !it.equals(q, ignoreCase = true) }.take(2)
        for (recent in matchingRecents) {
            suggestions.add(
                SearchSuggestion(
                    id = "recent_$recent",
                    type = SuggestionType.RECENT,
                    title = recent,
                    subtitle = "Recent search",
                    targetQuery = recent
                )
            )
        }

        // 2. Matching People (from FaceClusterStore & customPeopleMap)
        val personSummaries = runCatching {
            faceStore.personSummaries(includeHidden = false, engineId = BuffaloLFaceEngine.ENGINE_ID)
        }.getOrDefault(emptyList())
        val matchingPeople = personSummaries.filter {
            it.displayName.isNotBlank() && it.displayName.contains(q, ignoreCase = true) && it.faceCount > 0
        }.take(2)

        for (person in matchingPeople) {
            suggestions.add(
                SearchSuggestion(
                    id = "person_${person.id}",
                    type = SuggestionType.PERSON,
                    title = person.displayName,
                    subtitle = "${person.faceCount} photos",
                    thumbnailUri = person.sampleUri,
                    isPersonPortrait = true,
                    personFaceIndex = person.portraitFaceIndex,
                    targetQuery = person.displayName,
                    targetFilter = SearchFilterState(selectedPersonId = person.id)
                )
            )
        }

        // Custom people check if fewer than 2
        if (matchingPeople.size < 2) {
            for ((_, names) in customPeopleMap) {
                if (suggestions.count { it.type == SuggestionType.PERSON } >= 2) break
                for (name in names) {
                    if (name.contains(q, ignoreCase = true) && suggestions.none { it.title.equals(name, ignoreCase = true) }) {
                        val count = media.count { customPeopleMap[it.uri]?.contains(name) == true }
                        val sample = media.firstOrNull { customPeopleMap[it.uri]?.contains(name) == true }?.uri
                        suggestions.add(
                            SearchSuggestion(
                                id = "custom_person_$name",
                                type = SuggestionType.PERSON,
                                title = name,
                                subtitle = "$count photos",
                                thumbnailUri = sample,
                                isPersonPortrait = true,
                                targetQuery = name,
                                targetFilter = SearchFilterState(selectedPersonId = "name:$name")
                            )
                        )
                        if (suggestions.count { it.type == SuggestionType.PERSON } >= 2) break
                    }
                }
            }
        }

        // 3. Matching Places
        val cachedLocations = runCatching { locationCache.loadAll() }.getOrDefault(emptyMap())
        val matchingPlaces = mutableMapOf<String, MutableList<MediaItem>>()
        for (item in media) {
            val placeName = PlaceLocationResolver.getCachedPlace(item.uri)
            if (!placeName.isNullOrBlank() && placeName.contains(q, ignoreCase = true)) {
                matchingPlaces.getOrPut(placeName) { mutableListOf() }.add(item)
            }
        }
        for ((placeName, items) in matchingPlaces.entries.take(2)) {
            suggestions.add(
                SearchSuggestion(
                    id = "place_$placeName",
                    type = SuggestionType.PLACE,
                    title = placeName,
                    subtitle = "${items.size} photos · Location",
                    thumbnailUri = items.firstOrNull()?.uri,
                    targetQuery = placeName,
                    targetFilter = SearchFilterState(selectedPlace = placeName)
                )
            )
        }

        // 4. Matching Albums
        val matchingAlbums = customAlbums.filter { it.name.contains(q, ignoreCase = true) && it.mediaUris.isNotEmpty() }.take(2)
        for (album in matchingAlbums) {
            suggestions.add(
                SearchSuggestion(
                    id = "album_${album.id}",
                    type = SuggestionType.ALBUM,
                    title = album.name,
                    subtitle = "${album.mediaUris.size} photos · Album",
                    thumbnailUri = album.mediaUris.firstOrNull(),
                    targetQuery = album.name,
                    targetFilter = SearchFilterState(selectedAlbumId = album.id)
                )
            )
        }

        // 5. Matching Files (matching by filename or relative folder path)
        val matchingFiles = media.filter {
            it.name.contains(q, ignoreCase = true) || it.relativePath.contains(q, ignoreCase = true)
        }.take(2)
        for (file in matchingFiles) {
            val dateStr = formatItemDate(file)
            suggestions.add(
                SearchSuggestion(
                    id = "file_${file.id}",
                    type = SuggestionType.FILE,
                    title = file.name,
                    subtitle = if (dateStr.isNotBlank()) "$dateStr · ${file.relativePath}" else file.relativePath,
                    thumbnailUri = file.uri,
                    targetQuery = file.name
                )
            )
        }

        // 6. Matching Tags
        val matchingTags = customTagsMap.values.flatten().toSet().filter { it.contains(q, ignoreCase = true) }.take(2)
        for (tag in matchingTags) {
            val tagMedia = media.filter { customTagsMap[it.uri]?.contains(tag) == true }
            suggestions.add(
                SearchSuggestion(
                    id = "tag_$tag",
                    type = SuggestionType.TAG,
                    title = "#$tag",
                    subtitle = "${tagMedia.size} items · Tag",
                    thumbnailUri = tagMedia.firstOrNull()?.uri,
                    targetQuery = tag,
                    targetFilter = SearchFilterState(selectedTag = tag)
                )
            )
        }

        // 7. Matching Documents (OCR text matches)
        var docMatches = 0
        for (item in media) {
            if (docMatches >= 2) break
            val ocrText = TesseractOcr.readCachedResult(context, Uri.parse(item.uri))
            if (!ocrText.isNullOrBlank() && ocrText.contains(q, ignoreCase = true)) {
                val snippet = ocrText.lineSequence().firstOrNull { it.contains(q, ignoreCase = true) }?.trim()?.take(40)
                suggestions.add(
                    SearchSuggestion(
                        id = "ocr_${item.id}",
                        type = SuggestionType.DOCUMENT,
                        title = item.name,
                        subtitle = snippet ?: "Text found in image",
                        thumbnailUri = item.uri,
                        targetQuery = q
                    )
                )
                docMatches++
            }
        }

        suggestions
    }

    /**
     * Executes real multi-criteria search filtering and ranking for State C.
     */
    suspend fun searchMedia(
        query: String,
        media: List<MediaItem>,
        filter: SearchFilterState,
        sortMode: SearchSortMode,
        customAlbums: List<CustomAlbum>,
        customTagsMap: Map<String, Set<String>>,
        customPeopleMap: Map<String, Set<String>>,
        favourites: Set<String>
    ): SearchResultData = withContext(Dispatchers.Default) {
        val q = query.trim()
        val nonTrash = media.filterNot { it.isTrashed }

        // Filter by media type
        var candidateList = when (filter.mediaType) {
            MediaTypeFilter.ALL -> nonTrash
            MediaTypeFilter.PHOTOS -> nonTrash.filterNot { it.isVideo || isGif(it) }
            MediaTypeFilter.VIDEOS -> nonTrash.filter { it.isVideo }
            MediaTypeFilter.GIFS -> nonTrash.filter { isGif(it) }
        }

        // Filter by favorites
        if (filter.favoritesOnly) {
            candidateList = candidateList.filter { it.uri in favourites }
        }

        // Filter by time range
        val now = System.currentTimeMillis()
        candidateList = when (val time = filter.timeRange) {
            is TimeRangeFilter.AllTime -> candidateList
            is TimeRangeFilter.Last30Days -> {
                val thirtyDaysAgo = now - 30L * 24 * 60 * 60 * 1000L
                candidateList.filter { getItemTimestamp(it) >= thirtyDaysAgo }
            }
            is TimeRangeFilter.Last90Days -> {
                val ninetyDaysAgo = now - 90L * 24 * 60 * 60 * 1000L
                candidateList.filter { getItemTimestamp(it) >= ninetyDaysAgo }
            }
            is TimeRangeFilter.Custom -> {
                candidateList.filter {
                    val t = getItemTimestamp(it)
                    t in time.startMillis..time.endMillis
                }
            }
        }

        // Filter by Album
        if (filter.selectedAlbumId != null) {
            if (filter.selectedAlbumId.startsWith("folder:")) {
                val folderName = filter.selectedAlbumId.removePrefix("folder:")
                candidateList = candidateList.filter { it.relativePath.contains(folderName, ignoreCase = true) }
            } else {
                val album = customAlbums.find { it.id == filter.selectedAlbumId }
                if (album != null) {
                    candidateList = candidateList.filter { it.uri in album.mediaUris }
                }
            }
        }

        // Filter by Person
        if (filter.selectedPersonId != null) {
            if (filter.selectedPersonId.startsWith("name:")) {
                val personName = filter.selectedPersonId.removePrefix("name:")
                candidateList = candidateList.filter { customPeopleMap[it.uri]?.contains(personName) == true }
            } else {
                val faces = runCatching { faceStore.facesForCluster(filter.selectedPersonId) }.getOrDefault(emptyList())
                val personUris = faces.map { it.mediaUri }.toSet()
                candidateList = candidateList.filter { it.uri in personUris }
            }
        }

        // Filter by Place
        if (filter.selectedPlace != null) {
            candidateList = candidateList.filter { item ->
                PlaceLocationResolver.getCachedPlace(item.uri)?.equals(filter.selectedPlace, ignoreCase = true) == true
            }
        }

        // Filter by Tag
        if (filter.selectedTag != null) {
            candidateList = candidateList.filter { customTagsMap[it.uri]?.contains(filter.selectedTag) == true }
        }

        // Filter by GPS
        candidateList = when (filter.gpsFilter) {
            GpsFilter.ALL -> candidateList
            GpsFilter.HAS_GPS -> {
                val cachedLocations = runCatching { locationCache.loadAll() }.getOrDefault(emptyMap())
                candidateList.filter { item ->
                    val cached = cachedLocations[item.uri]
                    if (cached != null) {
                        cached.point != null
                    } else {
                        val pt = PhotoMapLocationCache.extractLocation(context, item.uri, isVideo = item.isVideo)
                        runCatching { locationCache.save(item.uri, pt) }
                        pt != null
                    }
                }
            }
            GpsFilter.NO_GPS -> {
                val cachedLocations = runCatching { locationCache.loadAll() }.getOrDefault(emptyMap())
                candidateList.filter { item ->
                    val cached = cachedLocations[item.uri]
                    if (cached != null) {
                        cached.point == null
                    } else {
                        val pt = PhotoMapLocationCache.extractLocation(context, item.uri, isVideo = item.isVideo)
                        runCatching { locationCache.save(item.uri, pt) }
                        pt == null
                    }
                }
            }
        }

        // Filter by OCR Text
        candidateList = when (filter.ocrTextFilter) {
            OcrTextFilter.ALL -> candidateList
            OcrTextFilter.HAS_TEXT -> candidateList.filter { item ->
                if (item.isVideo) false else {
                    val ocrText = TesseractOcr.readCachedResult(context, Uri.parse(item.uri))
                    !ocrText.isNullOrBlank()
                }
            }
            OcrTextFilter.NO_TEXT -> candidateList.filter { item ->
                if (item.isVideo) false else {
                    val ocrText = TesseractOcr.readCachedResult(context, Uri.parse(item.uri))
                    ocrText.isNullOrBlank()
                }
            }
        }

        // Filter by Face
        candidateList = when (filter.faceFilter) {
            FaceFilter.ALL -> candidateList
            FaceFilter.HAS_FACE -> {
                val mediaWithFaces = runCatching { faceStore.getMediaUrisWithFaces() }.getOrDefault(emptySet())
                candidateList.filter { item ->
                    item.uri in mediaWithFaces || customPeopleMap[item.uri]?.isNotEmpty() == true
                }
            }
            FaceFilter.NO_FACE -> {
                val mediaWithFaces = runCatching { faceStore.getMediaUrisWithFaces() }.getOrDefault(emptySet())
                candidateList.filter { item ->
                    item.uri !in mediaWithFaces && customPeopleMap[item.uri].isNullOrEmpty()
                }
            }
        }

        // Match query against fields
        val matchedList = if (q.isBlank()) {
            candidateList
        } else {
            // Rank items:
            // 1. Exact/prefix match on name or confirmed person/place/album
            // 2. Token match on name/path
            // 3. Match on OCR text or tags
            val scoredItems = candidateList.mapNotNull { item ->
                val score = computeMatchScore(item, q, customAlbums, customTagsMap, customPeopleMap)
                if (score > 0) item to score else null
            }
            scoredItems.sortedByDescending { it.second }.map { it.first }
        }

        // Apply sorting
        val sortedList = when (sortMode) {
            SearchSortMode.RELEVANCE -> matchedList // Already relevance scored if query present, or date
            SearchSortMode.NEWEST_FIRST -> matchedList.sortedByDescending { getItemTimestamp(it) }
            SearchSortMode.OLDEST_FIRST -> matchedList.sortedBy { getItemTimestamp(it) }
            SearchSortMode.NAME -> matchedList.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }

        // Extract dynamic entity chips strictly present in the CURRENT result set!
        val dynamicChips = extractDynamicChips(sortedList, customAlbums, customTagsMap, customPeopleMap)

        SearchResultData(
            query = q,
            items = sortedList,
            dynamicChips = dynamicChips,
            totalPhotosCount = sortedList.count { !it.isVideo },
            totalVideosCount = sortedList.count { it.isVideo }
        )
    }

    private fun computeMatchScore(
        item: MediaItem,
        query: String,
        customAlbums: List<CustomAlbum>,
        customTagsMap: Map<String, Set<String>>,
        customPeopleMap: Map<String, Set<String>>
    ): Int {
        val q = query.lowercase(Locale.ROOT)
        val name = item.name.lowercase(Locale.ROOT)
        val path = item.relativePath.lowercase(Locale.ROOT)

        var score = 0
        if (name.startsWith(q)) score += 100
        else if (name.contains(q)) score += 60

        if (path.contains(q)) score += 30

        // Custom tags
        val tags = customTagsMap[item.uri] ?: emptySet()
        if (tags.any { it.equals(q, ignoreCase = true) }) score += 80
        else if (tags.any { it.contains(q, ignoreCase = true) }) score += 40

        // Custom people
        val people = customPeopleMap[item.uri] ?: emptySet()
        if (people.any { it.equals(q, ignoreCase = true) }) score += 90
        else if (people.any { it.contains(q, ignoreCase = true) }) score += 45

        // Custom album
        val matchingAlbum = customAlbums.any { it.mediaUris.contains(item.uri) && it.name.contains(q, ignoreCase = true) }
        if (matchingAlbum) score += 50

        // Cached Place
        val place = PlaceLocationResolver.getCachedPlace(item.uri)
        if (place != null && place.contains(q, ignoreCase = true)) score += 70

        // Cached OCR text
        val ocr = TesseractOcr.readCachedResult(context, Uri.parse(item.uri))
        if (!ocr.isNullOrBlank() && ocr.contains(q, ignoreCase = true)) score += 40

        return score
    }

    private fun extractDynamicChips(
        results: List<MediaItem>,
        customAlbums: List<CustomAlbum>,
        customTagsMap: Map<String, Set<String>>,
        customPeopleMap: Map<String, Set<String>>
    ): List<DynamicEntityChip> {
        val chips = mutableListOf<DynamicEntityChip>()
        if (results.isEmpty()) return chips

        val resultUris = results.map { it.uri }.toSet()

        // Real Albums occurring in result set
        for (album in customAlbums) {
            val count = album.mediaUris.count { it in resultUris }
            if (count > 0) {
                chips.add(DynamicEntityChip("album_${album.id}", album.name, count, ChipKind.ALBUM, album.id))
            }
        }

        // Real People occurring in result set
        val personCounts = mutableMapOf<String, Int>()
        for (item in results) {
            val people = customPeopleMap[item.uri] ?: emptySet()
            for (p in people) {
                personCounts[p] = (personCounts[p] ?: 0) + 1
            }
        }
        for ((personName, count) in personCounts) {
            if (count > 0) {
                chips.add(DynamicEntityChip("person_$personName", personName, count, ChipKind.PERSON, "name:$personName"))
            }
        }

        // Real Places occurring in result set
        val placeCounts = mutableMapOf<String, Int>()
        for (item in results) {
            val place = PlaceLocationResolver.getCachedPlace(item.uri)
            if (!place.isNullOrBlank()) {
                placeCounts[place] = (placeCounts[place] ?: 0) + 1
            }
        }
        for ((placeName, count) in placeCounts) {
            if (count > 0) {
                chips.add(DynamicEntityChip("place_$placeName", placeName, count, ChipKind.PLACE, placeName))
            }
        }

        // Real Tags occurring in result set
        val tagCounts = mutableMapOf<String, Int>()
        for (item in results) {
            val tags = customTagsMap[item.uri] ?: emptySet()
            for (t in tags) {
                tagCounts[t] = (tagCounts[t] ?: 0) + 1
            }
        }
        for ((tag, count) in tagCounts) {
            if (count > 0) {
                chips.add(DynamicEntityChip("tag_$tag", "#$tag", count, ChipKind.TAG, tag))
            }
        }

        return chips.take(6)
    }

    private fun getItemTimestamp(item: MediaItem): Long {
        return if (item.dateTakenMillis > 0) item.dateTakenMillis else item.dateAddedSeconds * 1000L
    }

    private fun formatItemDate(item: MediaItem): String {
        val t = getItemTimestamp(item)
        if (t <= 0) return ""
        val fmt = SimpleDateFormat("MMM yyyy", Locale.getDefault())
        return fmt.format(Date(t))
    }

    companion object {
        fun isGif(item: MediaItem): Boolean =
            item.mimeType.equals("image/gif", ignoreCase = true) ||
            item.name.endsWith(".gif", ignoreCase = true)
    }
}

enum class ChipKind {
    ALBUM, PERSON, PLACE, TAG
}

data class DynamicEntityChip(
    val id: String,
    val label: String,
    val count: Int,
    val kind: ChipKind,
    val value: String
)

data class DiscoveryData(
    val categories: List<DiscoveryCategory>,
    val people: List<DiscoveryEntityItem>,
    val places: List<DiscoveryEntityItem>,
    val albums: List<DiscoveryEntityItem>,
    val tags: List<DiscoveryEntityItem>
)

data class SearchResultData(
    val query: String,
    val items: List<MediaItem>,
    val dynamicChips: List<DynamicEntityChip>,
    val totalPhotosCount: Int,
    val totalVideosCount: Int
)
