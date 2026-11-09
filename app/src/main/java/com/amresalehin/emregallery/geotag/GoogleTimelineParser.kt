package com.amresalehin.emregallery.geotag

import android.util.JsonReader
import android.util.JsonToken
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.abs

/**
 * High-performance streaming parser for Google Maps Timeline / Location History JSON files.
 * Handles:
 * 1. Classic Google Takeout "Records.json" / "Location History.json" (with locations array and latitudeE7 / longitudeE7)
 * 2. Semantic Location History monthly files (with timelineObjects: placeVisit and activitySegment)
 * 3. Modern Android/iOS on-device Timeline exports (semanticSegments, rawSignals, and bare segment arrays)
 *
 * Maintains segment hierarchy (TimelineVisit, TimelineActivitySegment, TimelinePathPoint)
 * to avoid linking points across activity transitions or visit boundaries.
 */
object GoogleTimelineParser {

    /**
     * Accumulator for parsed timeline components preserving segment boundaries.
     */
    internal class ParsedTimelineData {
        val visits = mutableListOf<TimelineVisit>()
        val activitySegments = mutableListOf<TimelineActivitySegment>()
        val rawPoints = mutableListOf<TimelineLocationPoint>()

        fun toSegmentedTimeline(): SegmentedTimeline {
            visits.sortBy { it.startTimestampMillis }
            activitySegments.sortBy { it.startTimestampMillis }
            rawPoints.sortBy { it.timestampMillis }
            return SegmentedTimeline(
                visits = visits,
                activitySegments = activitySegments,
                rawPoints = rawPoints
            )
        }

        val totalPoints: Int
            get() = rawPoints.size + visits.size * 2 + activitySegments.sumOf { it.pathPoints.size }
    }

    /**
     * Parses an input stream of a Google Timeline JSON export into a structured [SegmentedTimeline].
     *
     * @param inputStream The source stream from file or content URI
     * @param minTimestampMillis Optional minimum timestamp to filter points (with safety buffer)
     * @param maxTimestampMillis Optional maximum timestamp to filter points (with safety buffer)
     * @param onProgress Optional callback reporting number of points parsed so far
     * @return [SegmentedTimeline] preserving activity journeys and visit boundaries
     */
    fun parseSegmentedTimeline(
        inputStream: InputStream,
        minTimestampMillis: Long? = null,
        maxTimestampMillis: Long? = null,
        onProgress: ((pointsCount: Int) -> Unit)? = null
    ): SegmentedTimeline {
        val data = ParsedTimelineData()
        val reader = JsonReader(InputStreamReader(inputStream, Charsets.UTF_8))
        reader.isLenient = true

        try {
            when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    parseRootObject(reader, data, minTimestampMillis, maxTimestampMillis, onProgress)
                }
                JsonToken.BEGIN_ARRAY -> {
                    parseArrayOfObjects(reader, data, minTimestampMillis, maxTimestampMillis, onProgress)
                }
                else -> {
                    // Unexpected token
                }
            }
        } finally {
            runCatching { reader.close() }
        }

        return data.toSegmentedTimeline()
    }

    /**
     * Parses an input stream of a Google Timeline JSON export and returns all points chronologically.
     */
    fun parseTimeline(
        inputStream: InputStream,
        minTimestampMillis: Long? = null,
        maxTimestampMillis: Long? = null,
        onProgress: ((pointsCount: Int) -> Unit)? = null
    ): List<TimelineLocationPoint> {
        return parseSegmentedTimeline(inputStream, minTimestampMillis, maxTimestampMillis, onProgress).allPoints()
    }

    private fun parseRootObject(
        reader: JsonReader,
        data: ParsedTimelineData,
        minTimestampMillis: Long?,
        maxTimestampMillis: Long?,
        onProgress: ((Int) -> Unit)?
    ) {
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            when (name) {
                "locations" -> {
                    parseLocationsArray(reader, data, minTimestampMillis, maxTimestampMillis, onProgress)
                }
                "timelineObjects" -> {
                    parseTimelineObjectsArray(reader, data, minTimestampMillis, maxTimestampMillis, onProgress)
                }
                "semanticSegments" -> {
                    parseArrayOfObjects(reader, data, minTimestampMillis, maxTimestampMillis, onProgress)
                }
                "rawSignals" -> {
                    parseRawSignalsArray(reader, data, minTimestampMillis, maxTimestampMillis, onProgress)
                }
                else -> {
                    reader.skipValue()
                }
            }
        }
        reader.endObject()
    }

    private fun parseArrayOfObjects(
        reader: JsonReader,
        data: ParsedTimelineData,
        minTimestampMillis: Long?,
        maxTimestampMillis: Long?,
        onProgress: ((Int) -> Unit)?
    ) {
        reader.beginArray()
        var count = 0
        while (reader.hasNext()) {
            if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                parseGenericTimelineItem(reader, data, minTimestampMillis, maxTimestampMillis)
                count++
                if (count % 2000 == 0) {
                    onProgress?.invoke(data.totalPoints)
                }
            } else {
                reader.skipValue()
            }
        }
        reader.endArray()
        onProgress?.invoke(data.totalPoints)
    }

    /**
     * Parses standard Takeout "locations": [ { "timestamp": "...", "latitudeE7": ..., "longitudeE7": ... } ]
     */
    private fun parseLocationsArray(
        reader: JsonReader,
        data: ParsedTimelineData,
        minTimestampMillis: Long?,
        maxTimestampMillis: Long?,
        onProgress: ((Int) -> Unit)?
    ) {
        reader.beginArray()
        var scanned = 0
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }
            reader.beginObject()
            var tsMillis: Long? = null
            var latE7: Long? = null
            var lngE7: Long? = null
            var latDirect: Double? = null
            var lngDirect: Double? = null
            var accuracy: Float? = null
            var altitude: Double? = null

            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "timestamp" -> {
                        tsMillis = parseTimestampToMillis(reader.nextString())
                    }
                    "timestampMs" -> {
                        tsMillis = reader.nextString().toLongOrNull()
                    }
                    "latitudeE7" -> {
                        latE7 = reader.nextLong()
                    }
                    "longitudeE7" -> {
                        lngE7 = reader.nextLong()
                    }
                    "latitude" -> {
                        latDirect = reader.nextDouble()
                    }
                    "longitude" -> {
                        lngDirect = reader.nextDouble()
                    }
                    "accuracy" -> {
                        accuracy = reader.nextDouble().toFloat()
                    }
                    "altitude" -> {
                        altitude = reader.nextDouble()
                    }
                    else -> {
                        reader.skipValue()
                    }
                }
            }
            reader.endObject()

            val finalLat = when {
                latE7 != null -> e7ToDegrees(latE7, 900_000_000L)
                latDirect != null -> latDirect
                else -> null
            }
            val finalLng = when {
                lngE7 != null -> e7ToDegrees(lngE7, 1_800_000_000L)
                lngDirect != null -> lngDirect
                else -> null
            }

            if (tsMillis != null && finalLat != null && finalLng != null && isValidCoordinate(finalLat, finalLng)) {
                if (isWithinRange(tsMillis, minTimestampMillis, maxTimestampMillis)) {
                    data.rawPoints.add(
                        TimelineLocationPoint(
                            timestampMillis = tsMillis,
                            latitude = finalLat,
                            longitude = finalLng,
                            altitudeMeters = altitude,
                            accuracyMeters = accuracy,
                            source = "records",
                            isEstimated = false
                        )
                    )
                }
            }

            scanned++
            if (scanned % 5000 == 0) {
                onProgress?.invoke(data.totalPoints)
            }
        }
        reader.endArray()
        onProgress?.invoke(data.totalPoints)
    }

    /**
     * Parses Semantic Location History "timelineObjects": [ { "activitySegment": ... }, { "placeVisit": ... } ]
     */
    private fun parseTimelineObjectsArray(
        reader: JsonReader,
        data: ParsedTimelineData,
        minTimestampMillis: Long?,
        maxTimestampMillis: Long?,
        onProgress: ((Int) -> Unit)?
    ) {
        reader.beginArray()
        var scanned = 0
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "placeVisit" -> parsePlaceVisit(reader, data, minTimestampMillis, maxTimestampMillis)
                    "activitySegment" -> parseActivitySegment(reader, data, minTimestampMillis, maxTimestampMillis)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            scanned++
            if (scanned % 500 == 0) {
                onProgress?.invoke(data.totalPoints)
            }
        }
        reader.endArray()
        onProgress?.invoke(data.totalPoints)
    }

    private fun parsePlaceVisit(
        reader: JsonReader,
        data: ParsedTimelineData,
        minTimestampMillis: Long?,
        maxTimestampMillis: Long?
    ) {
        reader.beginObject()
        var startTs: Long? = null
        var endTs: Long? = null
        var lat: Double? = null
        var lng: Double? = null
        var placeId: String? = null
        var name: String? = null

        while (reader.hasNext()) {
            when (reader.nextName()) {
                "location" -> {
                    if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "latitudeE7" -> lat = e7ToDegrees(reader.nextLong(), 900_000_000L)
                                "longitudeE7" -> lng = e7ToDegrees(reader.nextLong(), 1_800_000_000L)
                                "latitude" -> lat = reader.nextDouble()
                                "longitude" -> lng = reader.nextDouble()
                                "placeId" -> placeId = reader.nextString()
                                "name" -> name = reader.nextString()
                                "point", "latLng" -> {
                                    val parsed = parseGeoUriString(reader.nextString())
                                    if (parsed != null) {
                                        lat = parsed.first
                                        lng = parsed.second
                                    }
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    } else {
                        val pair = parseLocationSubObject(reader)
                        lat = pair?.first
                        lng = pair?.second
                    }
                }
                "duration" -> {
                    val dur = parseDurationSubObject(reader)
                    startTs = dur.first
                    endTs = dur.second
                }
                "centerLatE7" -> {
                    lat = e7ToDegrees(reader.nextLong(), 900_000_000L)
                }
                "centerLngE7" -> {
                    lng = e7ToDegrees(reader.nextLong(), 1_800_000_000L)
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (lat != null && lng != null && isValidCoordinate(lat, lng)) {
            val sTs = startTs ?: endTs
            val eTs = endTs ?: sTs
            if (sTs != null && eTs != null) {
                if (isWithinRange(sTs, minTimestampMillis, maxTimestampMillis) ||
                    isWithinRange(eTs, minTimestampMillis, maxTimestampMillis)
                ) {
                    data.visits.add(
                        TimelineVisit(
                            startTimestampMillis = sTs,
                            endTimestampMillis = maxOf(sTs, eTs),
                            latitude = lat,
                            longitude = lng,
                            placeId = placeId,
                            name = name,
                            source = "place_visit"
                        )
                    )
                }
            }
        }
    }

    private fun parseActivitySegment(
        reader: JsonReader,
        data: ParsedTimelineData,
        minTimestampMillis: Long?,
        maxTimestampMillis: Long?
    ) {
        reader.beginObject()
        var startTs: Long? = null
        var endTs: Long? = null
        var startCoord: Pair<Double, Double>? = null
        var endCoord: Pair<Double, Double>? = null
        var activityType: String? = null
        val segmentPathPoints = mutableListOf<TimelinePathPoint>()

        while (reader.hasNext()) {
            when (reader.nextName()) {
                "activityType" -> activityType = reader.nextString()
                "duration" -> {
                    val dur = parseDurationSubObject(reader)
                    startTs = dur.first
                    endTs = dur.second
                }
                "startLocation" -> {
                    startCoord = parseLocationSubObject(reader)
                }
                "endLocation" -> {
                    endCoord = parseLocationSubObject(reader)
                }
                "waypointPath" -> {
                    parseWaypointPath(reader, segmentPathPoints, startTs, endTs, minTimestampMillis, maxTimestampMillis)
                }
                "simplifiedRawPath" -> {
                    parseSimplifiedRawPath(reader, segmentPathPoints, minTimestampMillis, maxTimestampMillis)
                }
                "timelinePath" -> {
                    parseTimelinePath(reader, segmentPathPoints, startTs, endTs, minTimestampMillis, maxTimestampMillis)
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        // If waypoints didn't populate, at least capture start and end locations
        if (startTs != null && startCoord != null && isValidCoordinate(startCoord.first, startCoord.second)) {
            if (segmentPathPoints.none { it.timestampMillis == startTs }) {
                segmentPathPoints.add(
                    TimelinePathPoint(
                        timestampMillis = startTs,
                        latitude = startCoord.first,
                        longitude = startCoord.second,
                        isEstimated = false,
                        source = "activity_start"
                    )
                )
            }
        }
        if (endTs != null && endCoord != null && isValidCoordinate(endCoord.first, endCoord.second)) {
            if (segmentPathPoints.none { it.timestampMillis == endTs }) {
                segmentPathPoints.add(
                    TimelinePathPoint(
                        timestampMillis = endTs,
                        latitude = endCoord.first,
                        longitude = endCoord.second,
                        isEstimated = false,
                        source = "activity_end"
                    )
                )
            }
        }

        segmentPathPoints.sortBy { it.timestampMillis }

        val sTs = startTs ?: segmentPathPoints.firstOrNull()?.timestampMillis ?: 0L
        val eTs = endTs ?: segmentPathPoints.lastOrNull()?.timestampMillis ?: sTs

        if (segmentPathPoints.isNotEmpty()) {
            data.activitySegments.add(
                TimelineActivitySegment(
                    segmentId = "act_${sTs}",
                    startTimestampMillis = sTs,
                    endTimestampMillis = eTs,
                    startLocation = startCoord,
                    endLocation = endCoord,
                    activityType = activityType,
                    pathPoints = segmentPathPoints
                )
            )
        }
    }

    private fun parseWaypointPath(
        reader: JsonReader,
        pathPoints: MutableList<TimelinePathPoint>,
        startTs: Long?,
        endTs: Long?,
        minTimestampMillis: Long?,
        maxTimestampMillis: Long?
    ) {
        reader.beginObject()
        val coords = mutableListOf<Pair<Double, Double>>()
        while (reader.hasNext()) {
            if (reader.nextName() == "waypoints") {
                reader.beginArray()
                while (reader.hasNext()) {
                    if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                        reader.beginObject()
                        var lat: Double? = null
                        var lng: Double? = null
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "latE7" -> lat = e7ToDegrees(reader.nextLong(), 900_000_000L)
                                "lngE7" -> lng = e7ToDegrees(reader.nextLong(), 1_800_000_000L)
                                "latitude" -> lat = reader.nextDouble()
                                "longitude" -> lng = reader.nextDouble()
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        if (lat != null && lng != null && isValidCoordinate(lat, lng)) {
                            coords.add(Pair(lat, lng))
                        }
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endArray()
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()

        // Distribute timestamps linearly between startTs and endTs if known
        // These points are estimated because they lack explicit recorded timestamps
        if (startTs != null && endTs != null && coords.isNotEmpty() && endTs > startTs) {
            val totalDuration = endTs - startTs
            coords.forEachIndexed { index, pair ->
                val fraction = (index + 1).toDouble() / (coords.size + 1).toDouble()
                val ts = (startTs + (totalDuration * fraction)).toLong()
                if (isWithinRange(ts, minTimestampMillis, maxTimestampMillis)) {
                    pathPoints.add(
                        TimelinePathPoint(
                            timestampMillis = ts,
                            latitude = pair.first,
                            longitude = pair.second,
                            isEstimated = true,
                            source = "waypoint"
                        )
                    )
                }
            }
        }
    }

    private fun parseSimplifiedRawPath(
        reader: JsonReader,
        pathPoints: MutableList<TimelinePathPoint>,
        minTimestampMillis: Long?,
        maxTimestampMillis: Long?
    ) {
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() == "points") {
                reader.beginArray()
                while (reader.hasNext()) {
                    if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                        reader.beginObject()
                        var lat: Double? = null
                        var lng: Double? = null
                        var ts: Long? = null
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "latE7" -> lat = e7ToDegrees(reader.nextLong(), 900_000_000L)
                                "lngE7" -> lng = e7ToDegrees(reader.nextLong(), 1_800_000_000L)
                                "latitude" -> lat = reader.nextDouble()
                                "longitude" -> lng = reader.nextDouble()
                                "timestamp" -> ts = parseTimestampToMillis(reader.nextString())
                                "timestampMs" -> ts = reader.nextString().toLongOrNull()
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        if (lat != null && lng != null && ts != null && isValidCoordinate(lat, lng)) {
                            if (isWithinRange(ts, minTimestampMillis, maxTimestampMillis)) {
                                pathPoints.add(
                                    TimelinePathPoint(
                                        timestampMillis = ts,
                                        latitude = lat,
                                        longitude = lng,
                                        isEstimated = false,
                                        source = "raw_path"
                                    )
                                )
                            }
                        }
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endArray()
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
    }

    private fun parseTimelinePath(
        reader: JsonReader,
        pathPoints: MutableList<TimelinePathPoint>,
        startTs: Long?,
        endTs: Long?,
        minTimestampMillis: Long?,
        maxTimestampMillis: Long?
    ) {
        reader.beginArray()
        val pathCoords = mutableListOf<Pair<Double, Double>>()
        while (reader.hasNext()) {
            if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "point") {
                        val pointStr = reader.nextString()
                        val parsed = parseGeoUriString(pointStr)
                        if (parsed != null && isValidCoordinate(parsed.first, parsed.second)) {
                            pathCoords.add(parsed)
                        }
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
            } else {
                reader.skipValue()
            }
        }
        reader.endArray()

        if (startTs != null && endTs != null && pathCoords.isNotEmpty() && endTs > startTs) {
            val totalDuration = endTs - startTs
            pathCoords.forEachIndexed { index, pair ->
                val fraction = (index + 1).toDouble() / (pathCoords.size + 1).toDouble()
                val ts = (startTs + (totalDuration * fraction)).toLong()
                if (isWithinRange(ts, minTimestampMillis, maxTimestampMillis)) {
                    pathPoints.add(
                        TimelinePathPoint(
                            timestampMillis = ts,
                            latitude = pair.first,
                            longitude = pair.second,
                            isEstimated = true,
                            source = "timeline_path"
                        )
                    )
                }
            }
        }
    }

    private fun parseRawSignalsArray(
        reader: JsonReader,
        data: ParsedTimelineData,
        minTimestampMillis: Long?,
        maxTimestampMillis: Long?,
        onProgress: ((Int) -> Unit)?
    ) {
        reader.beginArray()
        var scanned = 0
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }
            reader.beginObject()
            var timestamp: Long? = null
            var coordinate: Pair<Double, Double>? = null
            var accuracy: Float? = null
            var altitude: Double? = null

            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "position" -> {
                        if (reader.peek() == JsonToken.STRING) {
                            coordinate = parseGeoUriString(reader.nextString())
                        } else if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                            reader.beginObject()
                            var lat: Double? = null
                            var lng: Double? = null
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "LatLng", "latLng", "point" -> {
                                        val parsed = parseGeoUriString(reader.nextString())
                                        lat = parsed?.first
                                        lng = parsed?.second
                                    }
                                    "latitudeE7", "latE7" -> lat = e7ToDegrees(reader.nextLong(), 900_000_000L)
                                    "longitudeE7", "lngE7" -> lng = e7ToDegrees(reader.nextLong(), 1_800_000_000L)
                                    "latitude", "lat" -> lat = reader.nextDouble()
                                    "longitude", "lng" -> lng = reader.nextDouble()
                                    "timestamp", "time" -> timestamp = parseTimestampToMillis(reader.nextString())
                                    "accuracyMeters", "accuracy" -> accuracy = reader.nextDouble().toFloat()
                                    "altitudeMeters", "altitude" -> altitude = reader.nextDouble()
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                            if (lat != null && lng != null && isValidCoordinate(lat, lng)) {
                                coordinate = Pair(lat, lng)
                            }
                        } else {
                            reader.skipValue()
                        }
                    }
                    "timestamp", "time" -> timestamp = parseTimestampToMillis(reader.nextString())
                    "timestampMs" -> timestamp = reader.nextString().toLongOrNull()
                    "accuracyMeters", "accuracy" -> accuracy = reader.nextDouble().toFloat()
                    "altitudeMeters", "altitude" -> altitude = reader.nextDouble()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            if (timestamp != null && coordinate != null &&
                isValidCoordinate(coordinate.first, coordinate.second) &&
                isWithinRange(timestamp, minTimestampMillis, maxTimestampMillis)
            ) {
                data.rawPoints.add(
                    TimelineLocationPoint(
                        timestampMillis = timestamp,
                        latitude = coordinate.first,
                        longitude = coordinate.second,
                        accuracyMeters = accuracy,
                        altitudeMeters = altitude,
                        source = "raw_signal",
                        isEstimated = false
                    )
                )
            }
            scanned++
            if (scanned % 2000 == 0) onProgress?.invoke(data.totalPoints)
        }
        reader.endArray()
        onProgress?.invoke(data.totalPoints)
    }

    private fun parseGenericTimelineItem(
        reader: JsonReader,
        data: ParsedTimelineData,
        minTimestampMillis: Long?,
        maxTimestampMillis: Long?
    ) {
        reader.beginObject()
        var timestamp: Long? = null
        var startTimestamp: Long? = null
        var endTimestamp: Long? = null
        var latitude: Double? = null
        var longitude: Double? = null
        var visitCoordinate: Pair<Double, Double>? = null
        var activityStart: Pair<Double, Double>? = null
        var activityEnd: Pair<Double, Double>? = null
        val pathEntries = mutableListOf<ParsedTimelinePathPoint>()

        while (reader.hasNext()) {
            when (reader.nextName()) {
                "timestamp", "time" -> timestamp = parseTimestampToMillis(reader.nextString())
                "timestampMs" -> timestamp = reader.nextString().toLongOrNull()
                "startTime", "startTimestamp" -> startTimestamp = parseTimestampToMillis(reader.nextString())
                "endTime", "endTimestamp" -> endTimestamp = parseTimestampToMillis(reader.nextString())
                "latitudeE7", "latE7" -> latitude = e7ToDegrees(reader.nextLong(), 900_000_000L)
                "longitudeE7", "lngE7" -> longitude = e7ToDegrees(reader.nextLong(), 1_800_000_000L)
                "latitude", "lat" -> latitude = reader.nextDouble()
                "longitude", "lng" -> longitude = reader.nextDouble()
                "point" -> {
                    val parsed = parseGeoUriString(reader.nextString())
                    if (parsed != null) {
                        latitude = parsed.first
                        longitude = parsed.second
                    }
                }
                "placeLocation", "location", "position" -> {
                    val parsed = parseLocationSubObject(reader)
                    if (parsed != null) {
                        latitude = parsed.first
                        longitude = parsed.second
                    }
                }
                "visit" -> visitCoordinate = parseModernVisit(reader)
                "activity" -> {
                    val endpoints = parseModernActivity(reader)
                    activityStart = endpoints.first
                    activityEnd = endpoints.second
                }
                "timelinePath" -> pathEntries.addAll(parseTimelinePathEntries(reader))
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        // 1. Visit staypoint
        if (startTimestamp != null && visitCoordinate != null &&
            isValidCoordinate(visitCoordinate.first, visitCoordinate.second)
        ) {
            val endTs = endTimestamp ?: startTimestamp
            data.visits.add(
                TimelineVisit(
                    startTimestampMillis = startTimestamp,
                    endTimestampMillis = maxOf(startTimestamp, endTs),
                    latitude = visitCoordinate.first,
                    longitude = visitCoordinate.second,
                    source = "visit"
                )
            )
        }

        // 2. Activity Journey
        val activityPoints = mutableListOf<TimelinePathPoint>()
        if (startTimestamp != null && activityStart != null && isValidCoordinate(activityStart.first, activityStart.second)) {
            activityPoints.add(
                TimelinePathPoint(
                    timestampMillis = startTimestamp,
                    latitude = activityStart.first,
                    longitude = activityStart.second,
                    isEstimated = false,
                    source = "activity_start"
                )
            )
        }

        val segmentStartTimestamp = startTimestamp
        val segmentEndTimestamp = endTimestamp
        pathEntries.forEachIndexed { index, entry ->
            val isEstimated = (entry.timestampMillis == null && entry.offsetMinutes == null)
            val pathTimestamp = entry.timestampMillis
                ?: entry.offsetMinutes?.let { offset ->
                    segmentStartTimestamp?.plus((offset * 60_000.0).toLong())
                }
                ?: if (segmentStartTimestamp != null && segmentEndTimestamp != null &&
                    segmentEndTimestamp > segmentStartTimestamp && pathEntries.size > 1
                ) {
                    val fraction = index.toDouble() / (pathEntries.size - 1).toDouble()
                    (segmentStartTimestamp + ((segmentEndTimestamp - segmentStartTimestamp) * fraction)).toLong()
                } else {
                    segmentStartTimestamp ?: timestamp
                }

            if (pathTimestamp != null && isWithinRange(pathTimestamp, minTimestampMillis, maxTimestampMillis)) {
                activityPoints.add(
                    TimelinePathPoint(
                        timestampMillis = pathTimestamp,
                        latitude = entry.latitude,
                        longitude = entry.longitude,
                        isEstimated = isEstimated,
                        source = "timeline_path"
                    )
                )
            }
        }

        if (endTimestamp != null && activityEnd != null && isValidCoordinate(activityEnd.first, activityEnd.second)) {
            if (activityPoints.none { it.timestampMillis == endTimestamp }) {
                activityPoints.add(
                    TimelinePathPoint(
                        timestampMillis = endTimestamp,
                        latitude = activityEnd.first,
                        longitude = activityEnd.second,
                        isEstimated = false,
                        source = "activity_end"
                    )
                )
            }
        }

        if (activityPoints.isNotEmpty()) {
            activityPoints.sortBy { it.timestampMillis }
            val sTs = startTimestamp ?: activityPoints.first().timestampMillis
            val eTs = endTimestamp ?: activityPoints.last().timestampMillis
            data.activitySegments.add(
                TimelineActivitySegment(
                    segmentId = "act_${sTs}",
                    startTimestampMillis = sTs,
                    endTimestampMillis = maxOf(sTs, eTs),
                    startLocation = activityStart,
                    endLocation = activityEnd,
                    pathPoints = activityPoints
                )
            )
        }

        // 3. Generic standalone point if neither visit nor activity
        val pointTimestamp = timestamp ?: startTimestamp
        if (pointTimestamp != null && latitude != null && longitude != null &&
            isValidCoordinate(latitude, longitude) && visitCoordinate == null && activityStart == null && activityPoints.isEmpty()
        ) {
            if (isWithinRange(pointTimestamp, minTimestampMillis, maxTimestampMillis)) {
                data.rawPoints.add(
                    TimelineLocationPoint(
                        timestampMillis = pointTimestamp,
                        latitude = latitude,
                        longitude = longitude,
                        source = "generic",
                        isEstimated = false
                    )
                )
            }
        }
    }

    private data class ParsedTimelinePathPoint(
        val latitude: Double,
        val longitude: Double,
        val timestampMillis: Long? = null,
        val offsetMinutes: Double? = null
    )

    private fun parseModernVisit(reader: JsonReader): Pair<Double, Double>? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        reader.beginObject()
        var coordinate: Pair<Double, Double>? = null
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "topCandidate" -> {
                    if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "placeLocation", "location" -> coordinate = parseLocationSubObject(reader)
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    } else reader.skipValue()
                }
                "placeLocation", "location" -> coordinate = parseLocationSubObject(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return coordinate
    }

    private fun parseModernActivity(reader: JsonReader): Pair<Pair<Double, Double>?, Pair<Double, Double>?> {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return Pair(null, null)
        }
        reader.beginObject()
        var start: Pair<Double, Double>? = null
        var end: Pair<Double, Double>? = null
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "start", "startLocation" -> start = parseLocationSubObject(reader)
                "end", "endLocation" -> end = parseLocationSubObject(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return Pair(start, end)
    }

    private fun parseTimelinePathEntries(reader: JsonReader): List<ParsedTimelinePathPoint> {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return emptyList()
        }
        val entries = mutableListOf<ParsedTimelinePathPoint>()
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }
            reader.beginObject()
            var coordinate: Pair<Double, Double>? = null
            var timestamp: Long? = null
            var offsetMinutes: Double? = null
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "point" -> {
                        coordinate = if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                            parseLocationSubObject(reader)
                        } else {
                            parseGeoUriString(reader.nextString())
                        }
                    }
                    "time", "timestamp" -> timestamp = parseTimestampToMillis(reader.nextString())
                    "durationMinutesOffsetFromStartTime" -> {
                        offsetMinutes = reader.nextString().toDoubleOrNull()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (coordinate != null && isValidCoordinate(coordinate.first, coordinate.second)) {
                entries.add(
                    ParsedTimelinePathPoint(
                        latitude = coordinate.first,
                        longitude = coordinate.second,
                        timestampMillis = timestamp,
                        offsetMinutes = offsetMinutes
                    )
                )
            }
        }
        reader.endArray()
        return entries
    }

    private fun parseLocationSubObject(reader: JsonReader): Pair<Double, Double>? {
        if (reader.peek() == JsonToken.STRING) {
            val str = reader.nextString()
            return parseGeoUriString(str)
        }
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        reader.beginObject()
        var lat: Double? = null
        var lng: Double? = null
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "latitudeE7", "latE7" -> lat = e7ToDegrees(reader.nextLong(), 900_000_000L)
                "longitudeE7", "lngE7" -> lng = e7ToDegrees(reader.nextLong(), 1_800_000_000L)
                "latitude", "lat" -> lat = reader.nextDouble()
                "longitude", "lng" -> lng = reader.nextDouble()
                "latLng", "LatLng", "point" -> {
                    val s = reader.nextString()
                    val p = parseGeoUriString(s)
                    if (p != null) {
                        lat = p.first
                        lng = p.second
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return if (lat != null && lng != null && isValidCoordinate(lat, lng)) Pair(lat, lng) else null
    }

    private fun parseDurationSubObject(reader: JsonReader): Pair<Long?, Long?> {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return Pair(null, null)
        }
        reader.beginObject()
        var start: Long? = null
        var end: Long? = null
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "startTimestamp" -> start = parseTimestampToMillis(reader.nextString())
                "endTimestamp" -> end = parseTimestampToMillis(reader.nextString())
                "startTimestampMs" -> start = reader.nextString().toLongOrNull()
                "endTimestampMs" -> end = reader.nextString().toLongOrNull()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return Pair(start, end)
    }

    /**
     * Parses geo:37.4219999,-122.0840575 or 37.4219999,-122.0840575
     */
    fun parseGeoUriString(raw: String): Pair<Double, Double>? {
        val clean = raw.trim()
            .removePrefix("geo:")
            .removePrefix("GEO:")
            .replace("°", "")
            .trim()
        val parts = clean.split(',', ';')
        if (parts.size >= 2) {
            val lat = parts[0].trim().toDoubleOrNull()
            val lng = parts[1].trim().toDoubleOrNull()
            if (lat != null && lng != null && isValidCoordinate(lat, lng)) {
                return Pair(lat, lng)
            }
        }
        return null
    }

    /**
     * Parses standard ISO-8601 strings into epoch milliseconds.
     */
    fun parseTimestampToMillis(raw: String): Long? {
        val value = raw.trim()
        if (value.isEmpty()) return null

        value.toLongOrNull()?.let { numeric ->
            val magnitude = if (numeric == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(numeric)
            return when {
                magnitude < 100_000_000_000L -> numeric * 1000L // Unix seconds
                magnitude >= 100_000_000_000_000L -> numeric / 1000L // Microseconds
                else -> numeric // Unix milliseconds
            }
        }

        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                java.time.OffsetDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME)
                    .toInstant().toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun e7ToDegrees(value: Long, maxAxisValue: Long): Double {
        // Some Takeout exports encode signed E7 coordinates as unsigned 32-bit integers.
        val signedValue = if (value > maxAxisValue) value - 4_294_967_296L else value
        return signedValue / 10_000_000.0
    }

    private fun isWithinRange(ts: Long, min: Long?, max: Long?): Boolean {
        if (min != null && ts < min) return false
        if (max != null && ts > max) return false
        return true
    }

    fun isValidCoordinate(lat: Double, lng: Double): Boolean {
        return lat.isFinite() && lng.isFinite() &&
            lat in -90.0..90.0 && lng in -180.0..180.0 &&
            !(abs(lat) < 1e-6 && abs(lng) < 1e-6)
    }
}
