package com.amresalehin.emregallery.geotag

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amresalehin.emregallery.data.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class GeotagSourceType {
    ALL_GALLERY_PHOTOS,
    DEVICE_ALBUM,
    DIRECTORY_PICKER
}

enum class GeotagResultFilter {
    ALL,
    MATCHED,
    SKIPPED_ALREADY_TAGGED,
    UNMATCHED,
    ERRORS
}

data class TimelineGeotagUiState(
    val timelineFileName: String? = null,
    val timelineSummary: TimelineParseSummary? = null,
    val isParsingTimeline: Boolean = false,
    val parseError: String? = null,
    val sourceType: GeotagSourceType = GeotagSourceType.ALL_GALLERY_PHOTOS,
    val selectedAlbumName: String? = null,
    val selectedDirectoryUri: Uri? = null,
    val selectedDirectoryDisplayName: String? = null,
    val config: GeotagConfig = GeotagConfig(
        maxInterpolationGapSeconds = 1800L,
        maxTimeDeltaSeconds = 900L,
        interpolateBetweenPoints = true,
        skipAlreadyGeotagged = true
    ),
    val isScanningCandidates: Boolean = false,
    val candidates: List<ImageGeotagCandidate> = emptyList(),
    val results: List<GeotagResultItem> = emptyList(),
    val progress: GeotagJobProgress = GeotagJobProgress(),
    val activeFilter: GeotagResultFilter = GeotagResultFilter.ALL,
    val writeToExif: Boolean = false
)

class TimelineGeotagViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(TimelineGeotagUiState())
    val uiState: StateFlow<TimelineGeotagUiState> = _uiState.asStateFlow()

    private var parsedTimeline: SegmentedTimeline? = null
    private var parsedPoints: List<TimelineLocationPoint> = emptyList()
    private var activeJob: Job? = null

    fun setSourceType(type: GeotagSourceType) {
        _uiState.update { it.copy(sourceType = type) }
    }

    fun setSelectedAlbum(albumName: String) {
        _uiState.update { it.copy(selectedAlbumName = albumName, sourceType = GeotagSourceType.DEVICE_ALBUM) }
    }

    fun setWriteToExif(enabled: Boolean) {
        _uiState.update { it.copy(writeToExif = enabled) }
    }

    fun updateConfig(update: (GeotagConfig) -> GeotagConfig) {
        _uiState.update { it.copy(config = update(it.config)) }
    }

    fun setFilter(filter: GeotagResultFilter) {
        _uiState.update { it.copy(activeFilter = filter) }
    }

    /**
     * Loads and parses the user-selected Google Maps Timeline JSON file in the background.
     */
    fun selectTimelineFile(uri: Uri) {
        val context = getApplication<Application>()
        val fileName = queryDisplayName(context.contentResolver, uri) ?: "Timeline.json"

        _uiState.update {
            it.copy(
                timelineFileName = fileName,
                isParsingTimeline = true,
                parseError = null
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val stream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException("Cannot open stream for selected file")

                val segmented = stream.use { input ->
                    GoogleTimelineParser.parseSegmentedTimeline(input)
                }

                parsedTimeline = segmented
                val points = segmented.allPoints()
                parsedPoints = points

                val summary = if (points.isNotEmpty()) {
                    TimelineParseSummary(
                        fileName = fileName,
                        totalPoints = points.size,
                        earliestTimestampMillis = points.firstOrNull()?.timestampMillis,
                        latestTimestampMillis = points.lastOrNull()?.timestampMillis,
                        formatDetected = when (points.firstOrNull()?.source) {
                            "records" -> "Google Takeout Records.json"
                            "place_visit", "activity_start", "waypoint" -> "Takeout Semantic History"
                            else -> "Google Maps Timeline"
                        }
                    )
                } else {
                    TimelineParseSummary(
                        fileName = fileName,
                        totalPoints = 0,
                        formatDetected = "No valid locations found"
                    )
                }

                _uiState.update {
                    it.copy(
                        isParsingTimeline = false,
                        timelineSummary = summary,
                        parseError = if (points.isEmpty()) "No GPS location records found in this JSON file. Please ensure it is a valid Google Maps Timeline or Takeout export." else null
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update {
                    it.copy(
                        isParsingTimeline = false,
                        parseError = "Failed to parse timeline JSON: ${e.localizedMessage ?: e.message}"
                    )
                }
            }
        }
    }

    /**
     * Sets the user-selected directory tree URI (from OpenDocumentTree).
     */
    fun selectDirectory(treeUri: Uri) {
        val context = getApplication<Application>()
        val dirName = queryDisplayName(context.contentResolver, treeUri)
            ?: treeUri.lastPathSegment?.substringAfterLast(':')
            ?: "Selected Directory"

        _uiState.update {
            it.copy(
                sourceType = GeotagSourceType.DIRECTORY_PICKER,
                selectedDirectoryUri = treeUri,
                selectedDirectoryDisplayName = dirName
            )
        }
    }

    /**
     * Scans candidate images from the selected source (Directory, Album, or Gallery).
     */
    fun scanCandidates(allGalleryMedia: List<MediaItem>) {
        val context = getApplication<Application>()
        val state = _uiState.value

        _uiState.update { it.copy(isScanningCandidates = true) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val candidateList = mutableListOf<ImageGeotagCandidate>()

                when (state.sourceType) {
                    GeotagSourceType.DIRECTORY_PICKER -> {
                        val treeUri = state.selectedDirectoryUri
                        if (treeUri != null) {
                            scanDirectoryTree(context, treeUri, candidateList)
                        }
                    }
                    GeotagSourceType.DEVICE_ALBUM -> {
                        val targetAlbum = state.selectedAlbumName
                        val albumItems = if (targetAlbum != null) {
                            allGalleryMedia.filter { item ->
                                !item.isVideo && item.relativePath.trim('/').equals(targetAlbum.trim('/'), ignoreCase = true)
                            }
                        } else {
                            allGalleryMedia.filter { !it.isVideo }
                        }
                        for (item in albumItems) {
                            val fallbackTaken = if (item.dateTakenMillis > 0L) item.dateTakenMillis else null
                            val fallbackModified = if (item.dateModifiedSeconds > 0L) {
                                item.dateModifiedSeconds * 1000L
                            } else if (item.dateAddedSeconds > 0L) {
                                item.dateAddedSeconds * 1000L
                            } else null

                            candidateList.add(
                                TimelineGeotagEngine.extractCandidate(
                                    context = context,
                                    uri = Uri.parse(item.uri),
                                    fallbackPath = null,
                                    displayName = item.name,
                                    isVideo = item.isVideo,
                                    fallbackTimestampMillis = fallbackTaken,
                                    fallbackLastModifiedMillis = fallbackModified
                                )
                            )
                        }
                    }
                    GeotagSourceType.ALL_GALLERY_PHOTOS -> {
                        for (item in allGalleryMedia.filter { !it.isVideo }) {
                            val fallbackTaken = if (item.dateTakenMillis > 0L) item.dateTakenMillis else null
                            val fallbackModified = if (item.dateModifiedSeconds > 0L) {
                                item.dateModifiedSeconds * 1000L
                            } else if (item.dateAddedSeconds > 0L) {
                                item.dateAddedSeconds * 1000L
                            } else null

                            candidateList.add(
                                TimelineGeotagEngine.extractCandidate(
                                    context = context,
                                    uri = Uri.parse(item.uri),
                                    fallbackPath = null,
                                    displayName = item.name,
                                    isVideo = item.isVideo,
                                    fallbackTimestampMillis = fallbackTaken,
                                    fallbackLastModifiedMillis = fallbackModified
                                )
                            )
                        }
                    }
                }

                _uiState.update {
                    it.copy(
                        isScanningCandidates = false,
                        candidates = candidateList
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update { it.copy(isScanningCandidates = false) }
            }
        }
    }

    private fun scanDirectoryTree(
        context: Context,
        treeUri: Uri,
        results: MutableList<ImageGeotagCandidate>
    ) {
        val resolver = context.contentResolver
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)

        fun queryChildren(uri: Uri) {
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val modCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                while (cursor.moveToNext()) {
                    val childId = cursor.getString(idCol)
                    val name = cursor.getString(nameCol) ?: "file"
                    val mime = cursor.getString(mimeCol) ?: ""
                    val lastMod = if (modCol >= 0 && !cursor.isNull(modCol)) cursor.getLong(modCol) else 0L

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        val subTree = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, childId)
                        queryChildren(subTree)
                    } else if (isImageMimeOrExtension(name, mime)) {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                        results.add(
                            TimelineGeotagEngine.extractCandidate(
                                context = context,
                                uri = docUri,
                                fallbackPath = null,
                                displayName = name,
                                isVideo = false,
                                fallbackLastModifiedMillis = if (lastMod > 0L) lastMod else null
                            )
                        )
                    }
                }
            }
        }

        queryChildren(childrenUri)
    }

    private fun isImageMimeOrExtension(name: String, mime: String): Boolean {
        if (mime.startsWith("image/", ignoreCase = true)) return true
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
            lower.endsWith(".cr2") || lower.endsWith(".nef") || lower.endsWith(".arw") ||
            lower.endsWith(".dng") || lower.endsWith(".heic") || lower.endsWith(".webp")
    }

    /**
     * Executes the geotagging process:
     * - Matches images with Google Maps Timeline
     * - SKIPS already geotagged images per user specification
     * - Writes GPS EXIF to eligible files
     * - Caches coordinates to PhotoMapLocationCache
     */
    fun startGeotagging(allGalleryMedia: List<MediaItem>) {
        if (activeJob?.isActive == true) return
        val context = getApplication<Application>()

        activeJob = viewModelScope.launch(Dispatchers.IO) {
            // First ensure candidates are loaded
            var currentCandidates = _uiState.value.candidates
            if (currentCandidates.isEmpty()) {
                scanCandidates(allGalleryMedia)
                // Wait briefly for scan
                currentCandidates = _uiState.value.candidates
            }

            val total = currentCandidates.size
            if (total == 0 || parsedPoints.isEmpty()) {
                return@launch
            }

            var processed = 0
            var skippedAlready = 0
            var successfullyTagged = 0
            var unmatched = 0
            var noTimestamp = 0
            var errors = 0
            val resultsList = mutableListOf<GeotagResultItem>()

            _uiState.update {
                it.copy(
                    progress = GeotagJobProgress(
                        totalImages = total,
                        processedCount = 0,
                        isRunning = true,
                        isCompleted = false
                    ),
                    results = emptyList()
                )
            }

            for (candidate in currentCandidates) {
                _uiState.update {
                    it.copy(
                        progress = it.progress.copy(
                            currentImageName = candidate.displayName
                        )
                    )
                }

                // Match with engine (preferring segmented timeline hierarchy)
                val matchResult = if (parsedTimeline != null) {
                    TimelineGeotagEngine.matchCandidateWithTimeline(
                        candidate = candidate,
                        timeline = parsedTimeline!!,
                        config = _uiState.value.config
                    )
                } else {
                    TimelineGeotagEngine.matchCandidate(
                        candidate = candidate,
                        sortedPoints = parsedPoints,
                        config = _uiState.value.config
                    )
                }

                val finalResult = when (matchResult.status) {
                    GeotagStatus.ALREADY_GEOTAGGED -> {
                        skippedAlready++
                        matchResult
                    }
                    GeotagStatus.NO_TIMESTAMP -> {
                        noTimestamp++
                        matchResult
                    }
                    GeotagStatus.UNMATCHED -> {
                        unmatched++
                        matchResult
                    }
                    GeotagStatus.MATCHED -> {
                        // Apply geotag: only writes EXIF if writeToExif is explicitly enabled!
                        val writeOutcome = TimelineGeotagEngine.applyGeotag(
                            context = context,
                            result = matchResult,
                            writeExif = _uiState.value.writeToExif
                        )
                        if (writeOutcome.isSuccess && writeOutcome.getOrNull() == true) {
                            successfullyTagged++
                            matchResult
                        } else {
                            errors++
                            matchResult.copy(
                                status = GeotagStatus.ERROR,
                                errorMessage = writeOutcome.exceptionOrNull()?.localizedMessage
                                    ?: "Failed to save geotag"
                            )
                        }
                    }
                    GeotagStatus.ERROR -> {
                        errors++
                        matchResult
                    }
                }

                resultsList.add(finalResult)
                processed++

                _uiState.update {
                    it.copy(
                        progress = GeotagJobProgress(
                            totalImages = total,
                            processedCount = processed,
                            skippedAlreadyTagged = skippedAlready,
                            successfullyTagged = successfullyTagged,
                            unmatchedCount = unmatched,
                            noTimestampCount = noTimestamp,
                            errorCount = errors,
                            currentImageName = candidate.displayName,
                            isRunning = true,
                            isCompleted = false
                        ),
                        results = ArrayList(resultsList)
                    )
                }
            }

            _uiState.update {
                it.copy(
                    progress = it.progress.copy(
                        processedCount = total,
                        isRunning = false,
                        isCompleted = true,
                        currentImageName = null
                    )
                )
            }
        }
    }

    fun stopGeotagging() {
        activeJob?.cancel()
        _uiState.update {
            it.copy(
                progress = it.progress.copy(isRunning = false)
            )
        }
    }

    fun getExportableGpxContent(): String {
        return TimelineGeotagEngine.exportToGpx(parsedPoints, "Google Timeline Track")
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        }.getOrNull()
    }
}
