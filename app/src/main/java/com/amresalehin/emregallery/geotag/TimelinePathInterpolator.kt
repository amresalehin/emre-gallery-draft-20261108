package com.amresalehin.emregallery.geotag

import kotlin.math.abs

/**
 * Metadata provenance record detailing how a photo was matched to a location.
 */
data class MatchProvenance(
    val matchMethod: String, // "interpolated", "exact", "nearest_segment", "nearest_raw", "staypoint", "nearest_staypoint"
    val source: String,
    val sampleBeforeTime: Long? = null,
    val sampleAfterTime: Long? = null,
    val sampleGapMinutes: Double? = null,
    val isEstimated: Boolean = false,
    val timeDifferenceSeconds: Long = 0L
)

/**
 * Result of interpolation matching against a segmented timeline.
 */
data class InterpolationMatchResult(
    val status: GeotagStatus,
    val matchedPoint: TimelineLocationPoint? = null,
    val provenance: MatchProvenance? = null,
    val errorMessage: String? = null
)

/**
 * Service providing path interpolation and structured fallback matching.
 *
 * Fallback ladder:
 * 1. Segment Path Interpolation: Strictly bounded within identical activity segments.
 *    Enforces the threshold condition: (T2 - T1) <= T_max_gap. Prevents point linkage
 *    across activity transitions or visit boundaries.
 * 2. Raw signals position nearest search (within nearest point tolerance).
 * 3. Visit staypoint match (stationary duration containment or nearest staypoint).
 */
object TimelinePathInterpolator {

    fun match(
        adjustedPhotoTime: Long,
        timeline: SegmentedTimeline,
        config: GeotagConfig
    ): InterpolationMatchResult {
        val maxGapMillis = config.maxInterpolationGapSeconds * 1000L
        val maxDeltaMillis = config.maxTimeDeltaSeconds * 1000L

        // LADDER STEP 1: Segment Path Interpolation bounded within identical activity segments
        for (segment in timeline.activitySegments) {
            val points = segment.pathPoints
            if (points.isEmpty()) continue

            val segStart = segment.startTimestampMillis
            val segEnd = segment.endTimestampMillis

            // Check if photo falls within the activity segment temporal boundary (with a buffer)
            if (adjustedPhotoTime in (segStart - maxDeltaMillis)..(segEnd + maxDeltaMillis)) {
                // If photo is between first and last path points of this segment
                val firstPt = points.first()
                val lastPt = points.last()

                if (adjustedPhotoTime in firstPt.timestampMillis..lastPt.timestampMillis) {
                    val idx = points.binarySearch { it.timestampMillis.compareTo(adjustedPhotoTime) }
                    if (idx >= 0) {
                        // Exact point match on segment
                        val pt = points[idx]
                        return InterpolationMatchResult(
                            status = GeotagStatus.MATCHED,
                            matchedPoint = TimelineLocationPoint(
                                timestampMillis = pt.timestampMillis,
                                latitude = pt.latitude,
                                longitude = pt.longitude,
                                altitudeMeters = pt.altitudeMeters,
                                accuracyMeters = pt.accuracyMeters,
                                source = pt.source,
                                isEstimated = pt.isEstimated,
                                segmentId = segment.segmentId
                            ),
                            provenance = MatchProvenance(
                                matchMethod = "exact",
                                source = pt.source,
                                sampleBeforeTime = pt.timestampMillis,
                                sampleAfterTime = pt.timestampMillis,
                                sampleGapMinutes = 0.0,
                                isEstimated = pt.isEstimated,
                                timeDifferenceSeconds = 0L
                            )
                        )
                    }

                    val insertionPoint = -(idx + 1)
                    val before = if (insertionPoint > 0) points[insertionPoint - 1] else null
                    val after = if (insertionPoint < points.size) points[insertionPoint] else null

                    if (before != null && after != null) {
                        val gapMillis = after.timestampMillis - before.timestampMillis
                        val gapMinutes = gapMillis / 60000.0

                        // Strict threshold check: gap must be <= maxInterpolationGap
                        if (config.interpolateBetweenPoints && gapMillis in 1..maxGapMillis) {
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
                            val isEstimated = before.isEstimated || after.isEstimated
                            val minDeltaSec = minOf(
                                adjustedPhotoTime - before.timestampMillis,
                                after.timestampMillis - adjustedPhotoTime
                            ) / 1000L

                            if (GoogleTimelineParser.isValidCoordinate(interpLat, interpLng)) {
                                val point = TimelineLocationPoint(
                                    timestampMillis = adjustedPhotoTime,
                                    latitude = interpLat,
                                    longitude = interpLng,
                                    altitudeMeters = interpAlt,
                                    source = "timeline_interpolated",
                                    isEstimated = isEstimated,
                                    segmentId = segment.segmentId
                                )
                                return InterpolationMatchResult(
                                    status = GeotagStatus.MATCHED,
                                    matchedPoint = point,
                                    provenance = MatchProvenance(
                                        matchMethod = "interpolated",
                                        source = "timeline_interpolated",
                                        sampleBeforeTime = before.timestampMillis,
                                        sampleAfterTime = after.timestampMillis,
                                        sampleGapMinutes = gapMinutes,
                                        isEstimated = isEstimated,
                                        timeDifferenceSeconds = minDeltaSec
                                    )
                                )
                            }
                        } else {
                            // Gap exceeded maxInterpolationGapMinutes!
                            // Fallback to nearest neighbor strictly if within maxTimeDeltaSeconds
                            val nearest = if (abs(adjustedPhotoTime - before.timestampMillis) <= abs(adjustedPhotoTime - after.timestampMillis)) before else after
                            val diffSec = abs(adjustedPhotoTime - nearest.timestampMillis) / 1000L
                            if (diffSec <= config.maxTimeDeltaSeconds) {
                                return InterpolationMatchResult(
                                    status = GeotagStatus.MATCHED,
                                    matchedPoint = TimelineLocationPoint(
                                        timestampMillis = nearest.timestampMillis,
                                        latitude = nearest.latitude,
                                        longitude = nearest.longitude,
                                        altitudeMeters = nearest.altitudeMeters,
                                        accuracyMeters = nearest.accuracyMeters,
                                        source = nearest.source,
                                        isEstimated = nearest.isEstimated,
                                        segmentId = segment.segmentId
                                    ),
                                    provenance = MatchProvenance(
                                        matchMethod = "nearest_segment",
                                        source = nearest.source,
                                        sampleBeforeTime = nearest.timestampMillis,
                                        sampleAfterTime = nearest.timestampMillis,
                                        sampleGapMinutes = gapMinutes,
                                        isEstimated = nearest.isEstimated,
                                        timeDifferenceSeconds = diffSec
                                    )
                                )
                            }
                        }
                    }
                } else {
                    // Check segment boundary points (first or last)
                    val boundaryPoint = if (adjustedPhotoTime < firstPt.timestampMillis) firstPt else lastPt
                    val diffSec = abs(adjustedPhotoTime - boundaryPoint.timestampMillis) / 1000L
                    if (diffSec <= config.maxTimeDeltaSeconds) {
                        return InterpolationMatchResult(
                            status = GeotagStatus.MATCHED,
                            matchedPoint = TimelineLocationPoint(
                                timestampMillis = boundaryPoint.timestampMillis,
                                latitude = boundaryPoint.latitude,
                                longitude = boundaryPoint.longitude,
                                altitudeMeters = boundaryPoint.altitudeMeters,
                                accuracyMeters = boundaryPoint.accuracyMeters,
                                source = boundaryPoint.source,
                                isEstimated = boundaryPoint.isEstimated,
                                segmentId = segment.segmentId
                            ),
                            provenance = MatchProvenance(
                                matchMethod = "nearest_segment",
                                source = boundaryPoint.source,
                                sampleBeforeTime = boundaryPoint.timestampMillis,
                                sampleAfterTime = boundaryPoint.timestampMillis,
                                sampleGapMinutes = 0.0,
                                isEstimated = boundaryPoint.isEstimated,
                                timeDifferenceSeconds = diffSec
                            )
                        )
                    }
                }
            }
        }

        // LADDER STEP 2: rawSignals.position / records nearest search
        val rawPoints = timeline.rawPoints
        if (rawPoints.isNotEmpty()) {
            val idx = rawPoints.binarySearch { it.timestampMillis.compareTo(adjustedPhotoTime) }
            val nearestRaw = if (idx >= 0) {
                rawPoints[idx]
            } else {
                val ins = -(idx + 1)
                val b = if (ins > 0) rawPoints[ins - 1] else null
                val a = if (ins < rawPoints.size) rawPoints[ins] else null
                when {
                    b == null -> a
                    a == null -> b
                    abs(adjustedPhotoTime - b.timestampMillis) <= abs(adjustedPhotoTime - a.timestampMillis) -> b
                    else -> a
                }
            }

            if (nearestRaw != null) {
                val diffSec = abs(adjustedPhotoTime - nearestRaw.timestampMillis) / 1000L
                if (diffSec <= config.maxTimeDeltaSeconds) {
                    return InterpolationMatchResult(
                        status = GeotagStatus.MATCHED,
                        matchedPoint = nearestRaw,
                        provenance = MatchProvenance(
                            matchMethod = "nearest_raw",
                            source = nearestRaw.source,
                            sampleBeforeTime = nearestRaw.timestampMillis,
                            sampleAfterTime = nearestRaw.timestampMillis,
                            sampleGapMinutes = 0.0,
                            isEstimated = nearestRaw.isEstimated,
                            timeDifferenceSeconds = diffSec
                        )
                    )
                }
            }
        }

        // LADDER STEP 3: visit staypoint match
        for (visit in timeline.visits) {
            // Check staypoint duration containment
            if (adjustedPhotoTime in visit.startTimestampMillis..visit.endTimestampMillis) {
                val durationMinutes = (visit.endTimestampMillis - visit.startTimestampMillis) / 60000.0
                return InterpolationMatchResult(
                    status = GeotagStatus.MATCHED,
                    matchedPoint = TimelineLocationPoint(
                        timestampMillis = adjustedPhotoTime,
                        latitude = visit.latitude,
                        longitude = visit.longitude,
                        source = visit.source,
                        isEstimated = false
                    ),
                    provenance = MatchProvenance(
                        matchMethod = "staypoint",
                        source = visit.source,
                        sampleBeforeTime = visit.startTimestampMillis,
                        sampleAfterTime = visit.endTimestampMillis,
                        sampleGapMinutes = durationMinutes,
                        isEstimated = false,
                        timeDifferenceSeconds = 0L
                    )
                )
            }

            // Check nearest visit boundary
            val boundaryDiffSec = minOf(
                abs(adjustedPhotoTime - visit.startTimestampMillis),
                abs(adjustedPhotoTime - visit.endTimestampMillis)
            ) / 1000L
            if (boundaryDiffSec <= config.maxTimeDeltaSeconds) {
                return InterpolationMatchResult(
                    status = GeotagStatus.MATCHED,
                    matchedPoint = TimelineLocationPoint(
                        timestampMillis = if (abs(adjustedPhotoTime - visit.startTimestampMillis) <= abs(adjustedPhotoTime - visit.endTimestampMillis)) {
                            visit.startTimestampMillis
                        } else {
                            visit.endTimestampMillis
                        },
                        latitude = visit.latitude,
                        longitude = visit.longitude,
                        source = visit.source,
                        isEstimated = false
                    ),
                    provenance = MatchProvenance(
                        matchMethod = "nearest_staypoint",
                        source = visit.source,
                        sampleBeforeTime = visit.startTimestampMillis,
                        sampleAfterTime = visit.endTimestampMillis,
                        sampleGapMinutes = (visit.endTimestampMillis - visit.startTimestampMillis) / 60000.0,
                        isEstimated = false,
                        timeDifferenceSeconds = boundaryDiffSec
                    )
                )
            }
        }

        return InterpolationMatchResult(
            status = GeotagStatus.UNMATCHED,
            errorMessage = "No matching timeline segment, raw signal, or visit staypoint within range"
        )
    }
}
