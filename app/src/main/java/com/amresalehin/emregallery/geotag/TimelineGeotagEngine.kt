package com.amresalehin.emregallery.geotag

import android.content.ContentResolver
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.amresalehin.emregallery.data.PhotoMapLocationCache
import org.osmdroid.util.GeoPoint
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Core engine matching photos with Google Maps Timeline location history and writing GPS EXIF tags.
 */
object TimelineGeotagEngine {

    private val EXIF_DATE_FORMATTERS = listOf(
        DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US),
        DateTimeFormatter.ofPattern("yyyy:MM:dd'T'HH:mm:ss", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    )

    /**
     * Inspects an image file to determine if it is already geotagged and extracts its capture timestamp.
     */
    fun extractCandidate(
        context: Context,
        uri: Uri,
        fallbackPath: String? = null,
        displayName: String? = null,
        isVideo: Boolean = false,
        fallbackTimestampMillis: Long? = null,
        fallbackLastModifiedMillis: Long? = null
    ): ImageGeotagCandidate {
        val name = displayName ?: uri.lastPathSegment?.substringAfterLast('/') ?: "image.jpg"
        var hasGps = false
        var existingLat: Double? = null
        var existingLng: Double? = null

        val exif = readExifInterface(context, uri, fallbackPath)
        if (exif != null) {
            val latLong = exif.latLong
            if (latLong != null && latLong.size >= 2 &&
                GoogleTimelineParser.isValidCoordinate(latLong[0], latLong[1])
            ) {
                hasGps = true
                existingLat = latLong[0]
                existingLng = latLong[1]
            } else {
                // Check raw GPS tags
                val latAttr = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
                val lonAttr = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)
                if (!latAttr.isNullOrBlank() && !lonAttr.isNullOrBlank()) {
                    hasGps = true
                }
            }
        }

        var fallbackMs = fallbackTimestampMillis
        var lastModifiedMs = fallbackLastModifiedMillis

        if (fallbackPath != null) {
            val f = File(fallbackPath)
            if (f.exists() && f.lastModified() > 0L) {
                if (lastModifiedMs == null) lastModifiedMs = f.lastModified()
            }
        }

        if (lastModifiedMs == null && (uri.scheme == ContentResolver.SCHEME_FILE || uri.scheme == null)) {
            val path = uri.path
            if (path != null) {
                val f = File(path)
                if (f.exists() && f.lastModified() > 0L) {
                    lastModifiedMs = f.lastModified()
                }
            }
        }

        if (uri.scheme == ContentResolver.SCHEME_CONTENT && (fallbackMs == null || lastModifiedMs == null)) {
            runCatching {
                val projection = arrayOf(
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.MediaColumns.DATA
                )
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val takenIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                        val modIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                        val addedIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                        val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)

                        val taken = if (takenIdx >= 0 && !cursor.isNull(takenIdx)) cursor.getLong(takenIdx) else 0L
                        val modSec = if (modIdx >= 0 && !cursor.isNull(modIdx)) cursor.getLong(modIdx) else 0L
                        val addedSec = if (addedIdx >= 0 && !cursor.isNull(addedIdx)) cursor.getLong(addedIdx) else 0L
                        val dataPath = if (dataIdx >= 0 && !cursor.isNull(dataIdx)) cursor.getString(dataIdx) else null

                        if (fallbackMs == null && taken > 0L) {
                            fallbackMs = taken
                        }
                        if (lastModifiedMs == null) {
                            if (modSec > 0L) {
                                lastModifiedMs = modSec * 1000L
                            } else if (!dataPath.isNullOrBlank()) {
                                val f = File(dataPath)
                                if (f.exists() && f.lastModified() > 0L) {
                                    lastModifiedMs = f.lastModified()
                                }
                            } else if (addedSec > 0L) {
                                lastModifiedMs = addedSec * 1000L
                            }
                        }
                    }
                }
            }

            // DocumentsContract document query fallback
            if (lastModifiedMs == null) {
                runCatching {
                    val docProjection = arrayOf(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    context.contentResolver.query(uri, docProjection, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val lmIdx = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                            if (lmIdx >= 0 && !c.isNull(lmIdx)) {
                                val lm = c.getLong(lmIdx)
                                if (lm > 0L) lastModifiedMs = lm
                            }
                        }
                    }
                }
            }

            // FileDescriptor stat fallback
            if (lastModifiedMs == null) {
                runCatching {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        val stat = android.system.Os.fstat(pfd.fileDescriptor)
                        if (stat.st_mtime > 0L) {
                            lastModifiedMs = stat.st_mtime * 1000L
                        }
                    }
                }
            }
        }

        val resolvedTimestamp = PhotoTimestampResolver.resolve(
            exif = exif,
            filePath = fallbackPath,
            displayName = name,
            fallbackMillis = fallbackMs,
            fallbackLastModifiedMillis = lastModifiedMs
        )
        val dateTimeMillis = resolvedTimestamp?.timeMs
        val offsetStr = resolvedTimestamp?.offsetString
        val timestampSource = resolvedTimestamp?.sourceTag

        return ImageGeotagCandidate(
            uriString = uri.toString(),
            displayName = name,
            filePath = fallbackPath,
            dateTimeOriginalMillis = dateTimeMillis,
            hasExistingGeotag = hasGps,
            existingLatitude = existingLat,
            existingLongitude = existingLng,
            isVideo = isVideo,
            timezoneOffsetString = offsetStr,
            timestampSource = timestampSource
        )
    }

    /**
     * Matches a candidate photo timestamp with chronological Google Timeline points.
     * Skips images that are already geotagged per user instruction.
     */
    fun matchCandidate(
        candidate: ImageGeotagCandidate,
        sortedPoints: List<TimelineLocationPoint>,
        config: GeotagConfig
    ): GeotagResultItem {
        // Requirement: Already Geotagged images are skipped!
        if (candidate.hasExistingGeotag && config.skipAlreadyGeotagged) {
            return GeotagResultItem(
                candidate = candidate,
                status = GeotagStatus.ALREADY_GEOTAGGED,
                matchedPoint = candidate.existingLatitude?.let { lat ->
                    candidate.existingLongitude?.let { lng ->
                        TimelineLocationPoint(
                            timestampMillis = candidate.dateTimeOriginalMillis ?: 0L,
                            latitude = lat,
                            longitude = lng,
                            source = "existing_exif"
                        )
                    }
                },
                matchMethod = "existing_exif"
            )
        }

        val photoBaseMillis = candidate.dateTimeOriginalMillis
            ?: return GeotagResultItem(
                candidate = candidate,
                status = GeotagStatus.NO_TIMESTAMP,
                errorMessage = "No capture timestamp found in image EXIF, filename, or metadata"
            )

        // Adjust for any camera clock drift offset configured by the user
        val adjustedPhotoTime = photoBaseMillis + (config.cameraClockOffsetSeconds * 1000L)

        if (sortedPoints.isEmpty()) {
            return GeotagResultItem(
                candidate = candidate,
                status = GeotagStatus.UNMATCHED,
                errorMessage = "Google Timeline location history is empty"
            )
        }

        // Binary search for position
        val idx = sortedPoints.binarySearch { it.timestampMillis.compareTo(adjustedPhotoTime) }
        if (idx >= 0) {
            val exact = sortedPoints[idx]
            return GeotagResultItem(
                candidate = candidate,
                status = GeotagStatus.MATCHED,
                matchedPoint = exact,
                timeDifferenceSeconds = 0L,
                isInterpolated = false,
                matchMethod = "exact",
                sampleBeforeTime = exact.timestampMillis,
                sampleAfterTime = exact.timestampMillis,
                sampleGapMinutes = 0.0,
                isEstimated = exact.isEstimated
            )
        }

        val insertionPoint = -(idx + 1)
        val before = if (insertionPoint > 0) sortedPoints[insertionPoint - 1] else null
        val after = if (insertionPoint < sortedPoints.size) sortedPoints[insertionPoint] else null

        // Try linear interpolation between surrounding points if within allowed gap
        if (config.interpolateBetweenPoints && before != null && after != null) {
            val gapMillis = after.timestampMillis - before.timestampMillis
            val maxGapMillis = config.maxInterpolationGapSeconds * 1000L
            if (gapMillis in 1..maxGapMillis) {
                val fraction = (adjustedPhotoTime - before.timestampMillis).toDouble() / gapMillis.toDouble()
                val interpLat = before.latitude + fraction * (after.latitude - before.latitude)
                val interpLng = before.longitude + fraction * (after.longitude - before.longitude)
                val interpAlt = when {
                    before.altitudeMeters != null && after.altitudeMeters != null ->
                        before.altitudeMeters + fraction * (after.altitudeMeters - before.altitudeMeters)
                    before.altitudeMeters != null -> before.altitudeMeters
                    after.altitudeMeters != null -> after.altitudeMeters
                    else -> null
                }
                val minDeltaSec = (minOf(
                    adjustedPhotoTime - before.timestampMillis,
                    after.timestampMillis - adjustedPhotoTime
                ) / 1000L)

                if (GoogleTimelineParser.isValidCoordinate(interpLat, interpLng)) {
                    val point = TimelineLocationPoint(
                        timestampMillis = adjustedPhotoTime,
                        latitude = interpLat,
                        longitude = interpLng,
                        altitudeMeters = interpAlt,
                        source = "timeline_interpolated",
                        isEstimated = before.isEstimated || after.isEstimated
                    )
                    return GeotagResultItem(
                        candidate = candidate,
                        status = GeotagStatus.MATCHED,
                        matchedPoint = point,
                        timeDifferenceSeconds = minDeltaSec,
                        isInterpolated = true,
                        matchMethod = "interpolated",
                        sampleBeforeTime = before.timestampMillis,
                        sampleAfterTime = after.timestampMillis,
                        sampleGapMinutes = gapMillis / 60000.0,
                        isEstimated = point.isEstimated
                    )
                }
            }
        }

        // Nearest neighbor check
        val nearest = when {
            before == null -> after
            after == null -> before
            else -> {
                val diffBefore = abs(adjustedPhotoTime - before.timestampMillis)
                val diffAfter = abs(adjustedPhotoTime - after.timestampMillis)
                if (diffBefore <= diffAfter) before else after
            }
        }

        if (nearest != null) {
            val diffSeconds = abs(adjustedPhotoTime - nearest.timestampMillis) / 1000L
            if (diffSeconds <= config.maxTimeDeltaSeconds) {
                return GeotagResultItem(
                    candidate = candidate,
                    status = GeotagStatus.MATCHED,
                    matchedPoint = nearest,
                    timeDifferenceSeconds = diffSeconds,
                    isInterpolated = false,
                    matchMethod = "nearest",
                    sampleBeforeTime = nearest.timestampMillis,
                    sampleAfterTime = nearest.timestampMillis,
                    sampleGapMinutes = 0.0,
                    isEstimated = nearest.isEstimated
                )
            } else {
                val diffMin = diffSeconds / 60
                val maxMin = config.maxTimeDeltaSeconds / 60
                return GeotagResultItem(
                    candidate = candidate,
                    status = GeotagStatus.UNMATCHED,
                    timeDifferenceSeconds = diffSeconds,
                    errorMessage = "Nearest timeline point is ${diffMin}m away (max allowed: ${maxMin}m)"
                )
            }
        }

        return GeotagResultItem(
            candidate = candidate,
            status = GeotagStatus.UNMATCHED,
            errorMessage = "No timeline point within range"
        )
    }

    /**
     * Matches a candidate photo timestamp against a structured [SegmentedTimeline]
     * using the [TimelinePathInterpolator] fallback ladder.
     */
    fun matchCandidateWithTimeline(
        candidate: ImageGeotagCandidate,
        timeline: SegmentedTimeline,
        config: GeotagConfig
    ): GeotagResultItem {
        if (candidate.hasExistingGeotag && config.skipAlreadyGeotagged) {
            return GeotagResultItem(
                candidate = candidate,
                status = GeotagStatus.ALREADY_GEOTAGGED,
                matchedPoint = candidate.existingLatitude?.let { lat ->
                    candidate.existingLongitude?.let { lng ->
                        TimelineLocationPoint(
                            timestampMillis = candidate.dateTimeOriginalMillis ?: 0L,
                            latitude = lat,
                            longitude = lng,
                            source = "existing_exif"
                        )
                    }
                },
                matchMethod = "existing_exif"
            )
        }

        val photoBaseMillis = candidate.dateTimeOriginalMillis
            ?: return GeotagResultItem(
                candidate = candidate,
                status = GeotagStatus.NO_TIMESTAMP,
                errorMessage = "No capture timestamp found in image EXIF, filename, or metadata"
            )

        val adjustedPhotoTime = photoBaseMillis + (config.cameraClockOffsetSeconds * 1000L)
        val match = TimelinePathInterpolator.match(adjustedPhotoTime, timeline, config)

        return GeotagResultItem(
            candidate = candidate,
            status = match.status,
            matchedPoint = match.matchedPoint,
            timeDifferenceSeconds = match.provenance?.timeDifferenceSeconds,
            isInterpolated = match.provenance?.matchMethod == "interpolated",
            errorMessage = match.errorMessage,
            matchMethod = match.provenance?.matchMethod,
            sampleBeforeTime = match.provenance?.sampleBeforeTime,
            sampleAfterTime = match.provenance?.sampleAfterTime,
            sampleGapMinutes = match.provenance?.sampleGapMinutes,
            isEstimated = match.provenance?.isEstimated ?: false
        )
    }

    /**
     * Writes the matched GPS coordinates into the image file EXIF (if [writeExif] is true)
     * and caches the result with provenance in [PhotoMapLocationCache] so it immediately
     * displays on the in-app Photo Map and Media Details drawer.
     */
    fun applyGeotag(
        context: Context,
        result: GeotagResultItem,
        writeExif: Boolean = false
    ): Result<Boolean> {
        val point = result.matchedPoint
            ?: return Result.failure(IllegalStateException("No location point to write"))
        val uri = Uri.parse(result.candidate.uriString)

        return runCatching {
            var written = false

            // Only modify original file EXIF if user explicitly requested writeExif == true
            if (writeExif) {
                // Try 1: Open direct File if accessible
                val filePath = result.candidate.filePath
                if (filePath != null) {
                    val file = File(filePath)
                    if (file.exists() && file.canWrite()) {
                        val exif = ExifInterface(file)
                        setExifGps(exif, point)
                        exif.saveAttributes()
                        written = true
                    }
                }

                // Try 2: Open FileDescriptor with "rw" via ContentResolver
                if (!written) {
                    val targetUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        uri.scheme == ContentResolver.SCHEME_CONTENT
                    ) {
                        runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
                    } else {
                        uri
                    }

                    context.contentResolver.openFileDescriptor(targetUri, "rw")?.use { pfd ->
                        val exif = ExifInterface(pfd.fileDescriptor)
                        setExifGps(exif, point)
                        exif.saveAttributes()
                        written = true
                    }
                }

                // Try 3: Direct file from uri.path
                if (!written && (uri.scheme == null || uri.scheme == ContentResolver.SCHEME_FILE)) {
                    val path = uri.path
                    if (path != null) {
                        val file = File(path)
                        if (file.exists() && file.canWrite()) {
                            val exif = ExifInterface(file)
                            setExifGps(exif, point)
                            exif.saveAttributes()
                            written = true
                        }
                    }
                }
            }

            // Always update PhotoMapLocationCache with provenance so the photo is immediately visible
            val provenance = com.amresalehin.emregallery.data.LocationProvenance(
                source = point.source,
                matchMethod = result.matchMethod ?: if (result.isInterpolated) "interpolated" else "nearest",
                resolvedTimestamp = result.candidate.dateTimeOriginalMillis,
                timestampSource = result.candidate.timestampSource,
                sampleBeforeTime = result.sampleBeforeTime,
                sampleAfterTime = result.sampleAfterTime,
                sampleGapMinutes = result.sampleGapMinutes,
                isEstimated = result.isEstimated,
                exifWritten = written
            )

            val cache = PhotoMapLocationCache(context)
            cache.save(result.candidate.uriString, GeoPoint(point.latitude, point.longitude), provenance)

            // Trigger MediaScanner notification if filePath is available and EXIF was written
            if (written && result.candidate.filePath != null) {
                runCatching {
                    MediaScannerConnection.scanFile(context, arrayOf(result.candidate.filePath), null, null)
                }
            }

            written || !writeExif
        }
    }

    private fun setExifGps(exif: ExifInterface, point: TimelineLocationPoint) {
        exif.setLatLong(point.latitude, point.longitude)
        if (point.altitudeMeters != null) {
            exif.setAltitude(point.altitudeMeters)
        }

        // Set standard GPS DateStamp and TimeStamp in UTC
        val zdt = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(point.timestampMillis),
            ZoneOffset.UTC
        )
        val dateStamp = zdt.format(DateTimeFormatter.ofPattern("yyyy:MM:dd", Locale.US))
        val timeStamp = zdt.format(DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US))
        exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, dateStamp)
        exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, timeStamp)
    }

    private fun readExifInterface(
        context: Context,
        uri: Uri,
        fallbackPath: String?
    ): ExifInterface? {
        if (fallbackPath != null) {
            val f = File(fallbackPath)
            if (f.exists() && f.canRead()) {
                val direct = runCatching { ExifInterface(f) }.getOrNull()
                if (direct != null) return direct
            }
        }

        val targetUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            uri.scheme == ContentResolver.SCHEME_CONTENT
        ) {
            runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
        } else {
            uri
        }

        val fromFd = runCatching {
            context.contentResolver.openFileDescriptor(targetUri, "r")?.use { pfd ->
                ExifInterface(pfd.fileDescriptor)
            }
        }.getOrNull()
        if (fromFd != null) return fromFd

        val fromStream = runCatching {
            context.contentResolver.openInputStream(targetUri)?.use { stream ->
                ExifInterface(stream)
            }
        }.getOrNull()
        if (fromStream != null) return fromStream

        return null
    }

    /**
     * Parses EXIF date string (e.g. "2023:05:14 15:30:22") with optional offset string (e.g. "+02:00").
     */
    fun parseExifDateTime(
        dateStr: String,
        offsetStr: String? = null,
        fallbackZoneId: ZoneId = ZoneId.systemDefault()
    ): Long? {
        val clean = dateStr.trim()
        for (formatter in EXIF_DATE_FORMATTERS) {
            try {
                val ldt = LocalDateTime.parse(clean, formatter)
                val zoneId = if (!offsetStr.isNullOrBlank()) {
                    try {
                        ZoneOffset.of(offsetStr.trim())
                    } catch (_: Exception) {
                        fallbackZoneId
                    }
                } else {
                    fallbackZoneId
                }
                return ldt.atZone(zoneId).toInstant().toEpochMilli()
            } catch (_: Exception) {
                // Try next pattern
            }
        }
        return null
    }

    /**
     * Generates a standard GPX 1.1 file content from timeline points for visualization or export.
     */
    fun exportToGpx(points: List<TimelineLocationPoint>, trackName: String = "Timeline Track"): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append("\n")
        sb.append("""<gpx version="1.1" creator="EmreGallery-GoogleTimelineGeotag" xmlns="http://www.topografix.com/GPX/1/1">""").append("\n")
        sb.append("  <trk>\n")
        sb.append("    <name>").append(trackName).append("</name>\n")
        sb.append("    <trkseg>\n")

        val dtf = DateTimeFormatter.ISO_INSTANT
        for (p in points) {
            val iso = dtf.format(java.time.Instant.ofEpochMilli(p.timestampMillis))
            sb.append("""      <trkpt lat="${p.latitude}" lon="${p.longitude}">""").append("\n")
            if (p.altitudeMeters != null) {
                sb.append("        <ele>").append(p.altitudeMeters).append("</ele>\n")
            }
            sb.append("        <time>").append(iso).append("</time>\n")
            sb.append("      </trkpt>\n")
        }

        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }
}
