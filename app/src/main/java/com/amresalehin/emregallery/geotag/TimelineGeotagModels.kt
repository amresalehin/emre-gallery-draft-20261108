package com.amresalehin.emregallery.geotag

/**
 * Single spatial-temporal location point parsed from Google Maps Timeline / Location History.
 */
data class TimelineLocationPoint(
    val timestampMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val accuracyMeters: Float? = null,
    val source: String = "timeline",
    val isEstimated: Boolean = false,
    val segmentId: String? = null
)

/**
 * Individual point along a tracked activity segment path.
 */
data class TimelinePathPoint(
    val timestampMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val accuracyMeters: Float? = null,
    val isEstimated: Boolean = false,
    val source: String = "timeline_path"
)

/**
 * Stationary visit staypoint duration.
 */
data class TimelineVisit(
    val startTimestampMillis: Long,
    val endTimestampMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val placeId: String? = null,
    val name: String? = null,
    val source: String = "place_visit"
)

/**
 * Discrete activity journey with bounding path points.
 */
data class TimelineActivitySegment(
    val segmentId: String,
    val startTimestampMillis: Long,
    val endTimestampMillis: Long,
    val startLocation: Pair<Double, Double>? = null,
    val endLocation: Pair<Double, Double>? = null,
    val activityType: String? = null,
    val pathPoints: List<TimelinePathPoint> = emptyList()
)

/**
 * Parsed timeline data preserving segment hierarchy and boundaries.
 */
data class SegmentedTimeline(
    val visits: List<TimelineVisit> = emptyList(),
    val activitySegments: List<TimelineActivitySegment> = emptyList(),
    val rawPoints: List<TimelineLocationPoint> = emptyList()
) {
    /**
     * Chronologically flattened points for legacy queries.
     */
    fun allPoints(): List<TimelineLocationPoint> {
        val list = mutableListOf<TimelineLocationPoint>()
        list.addAll(rawPoints)
        for (v in visits) {
            list.add(TimelineLocationPoint(v.startTimestampMillis, v.latitude, v.longitude, source = v.source))
        }
        for (a in activitySegments) {
            for (p in a.pathPoints) {
                list.add(
                    TimelineLocationPoint(
                        timestampMillis = p.timestampMillis,
                        latitude = p.latitude,
                        longitude = p.longitude,
                        altitudeMeters = p.altitudeMeters,
                        accuracyMeters = p.accuracyMeters,
                        source = p.source,
                        isEstimated = p.isEstimated,
                        segmentId = a.segmentId
                    )
                )
            }
        }
        list.sortBy { it.timestampMillis }
        return list
    }
}

/**
 * Candidate image to be evaluated for geotagging.
 */
data class ImageGeotagCandidate(
    val uriString: String,
    val displayName: String,
    val filePath: String? = null,
    val dateTimeOriginalMillis: Long? = null,
    val hasExistingGeotag: Boolean = false,
    val existingLatitude: Double? = null,
    val existingLongitude: Double? = null,
    val isVideo: Boolean = false,
    val timezoneOffsetString: String? = null,
    val timestampSource: String? = null
)

/**
 * Status of an individual image after geotag processing.
 */
enum class GeotagStatus {
    /** The image already contains valid GPS metadata and was skipped. */
    ALREADY_GEOTAGGED,
    /** Successfully matched to a Google Timeline coordinate and geotagged. */
    MATCHED,
    /** No timeline location point found within the allowed time delta window. */
    UNMATCHED,
    /** No valid capture timestamp could be found in EXIF or metadata. */
    NO_TIMESTAMP,
    /** Error occurred while reading or writing image EXIF. */
    ERROR
}

/**
 * Result record for a processed image.
 */
data class GeotagResultItem(
    val candidate: ImageGeotagCandidate,
    val status: GeotagStatus,
    val matchedPoint: TimelineLocationPoint? = null,
    val timeDifferenceSeconds: Long? = null,
    val isInterpolated: Boolean = false,
    val errorMessage: String? = null,
    val matchMethod: String? = null,
    val sampleBeforeTime: Long? = null,
    val sampleAfterTime: Long? = null,
    val sampleGapMinutes: Double? = null,
    val isEstimated: Boolean = false
)

/**
 * Summary of a parsed Google Maps Timeline JSON file.
 */
data class TimelineParseSummary(
    val fileName: String,
    val totalPoints: Int,
    val earliestTimestampMillis: Long? = null,
    val latestTimestampMillis: Long? = null,
    val formatDetected: String = "Google Takeout"
)

/**
 * Progress of an active geotagging operation.
 */
data class GeotagJobProgress(
    val totalImages: Int = 0,
    val processedCount: Int = 0,
    val skippedAlreadyTagged: Int = 0,
    val successfullyTagged: Int = 0,
    val unmatchedCount: Int = 0,
    val noTimestampCount: Int = 0,
    val errorCount: Int = 0,
    val currentImageName: String? = null,
    val isCompleted: Boolean = false,
    val isRunning: Boolean = false
)

/**
 * User configuration for matching images against Google Timeline.
 */
data class GeotagConfig(
    /** Manual camera clock adjustment in seconds (if camera clock was fast or slow). */
    val cameraClockOffsetSeconds: Long = 0L,
    /** Maximum allowed time difference in seconds between photo and nearest timeline point. Default 900s (15 min). */
    val maxTimeDeltaSeconds: Long = 900L,
    /** Whether to linearly interpolate coordinates if photo time falls between two nearby track points. */
    val interpolateBetweenPoints: Boolean = true,
    /** Maximum allowed gap between two track points to perform interpolation. Default 1800s (30 min). */
    val maxInterpolationGapSeconds: Long = 1800L,
    /** Always skip images that already have GPS coordinates (per user specification). */
    val skipAlreadyGeotagged: Boolean = true
)
