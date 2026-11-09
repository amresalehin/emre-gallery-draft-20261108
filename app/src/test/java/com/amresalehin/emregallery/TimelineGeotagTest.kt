package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.LocationProvenance
import com.amresalehin.emregallery.data.PhotoMapLocationCache
import com.amresalehin.emregallery.geotag.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config
class TimelineGeotagTest {

    @Test
    fun `test Google Takeout Records json parsing`() {
        val json = """
            {
              "locations": [
                {
                  "timestamp": "2023-05-14T10:00:00.000Z",
                  "latitudeE7": 377749000,
                  "longitudeE7": -1224194000,
                  "accuracy": 15,
                  "altitude": 25.5
                },
                {
                  "timestampMs": "1684059000000",
                  "latitudeE7": 377755000,
                  "longitudeE7": -1224180000,
                  "accuracy": 8
                }
              ]
            }
        """.trimIndent()

        val points = GoogleTimelineParser.parseTimeline(ByteArrayInputStream(json.toByteArray()))
        assertEquals(2, points.size)

        val first = points[0]
        assertEquals(37.7749, first.latitude, 0.0001)
        assertEquals(-122.4194, first.longitude, 0.0001)
        assertEquals(25.5, first.altitudeMeters ?: 0.0, 0.1)
        assertEquals(15f, first.accuracyMeters ?: 0f, 0.1f)
        assertEquals("records", first.source)

        val second = points[1]
        assertEquals(37.7755, second.latitude, 0.0001)
        assertEquals(-122.4180, second.longitude, 0.0001)
    }

    @Test
    fun `test Semantic Location History timelineObjects parsing`() {
        val json = """
            {
              "timelineObjects": [
                {
                  "placeVisit": {
                    "location": {
                      "latitudeE7": 407128000,
                      "longitudeE7": -740060000
                    },
                    "duration": {
                      "startTimestamp": "2023-06-10T14:00:00Z",
                      "endTimestamp": "2023-06-10T16:00:00Z"
                    }
                  }
                },
                {
                  "activitySegment": {
                    "duration": {
                      "startTimestamp": "2023-06-10T16:00:00Z",
                      "endTimestamp": "2023-06-10T17:00:00Z"
                    },
                    "startLocation": {
                      "latitudeE7": 407128000,
                      "longitudeE7": -740060000
                    },
                    "endLocation": {
                      "latitudeE7": 407580000,
                      "longitudeE7": -739855000
                    }
                  }
                }
              ]
            }
        """.trimIndent()

        val points = GoogleTimelineParser.parseTimeline(ByteArrayInputStream(json.toByteArray()))
        assertTrue("Should parse place visit and activity segment endpoints", points.size >= 2)

        val placePoint = points.first { it.source.startsWith("place_visit") }
        assertEquals(40.7128, placePoint.latitude, 0.0001)
        assertEquals(-74.0060, placePoint.longitude, 0.0001)
    }

    @Test
    fun `test modern timeline export with geo uri string`() {
        val json = """
            [
              {
                "time": "2023-07-20T08:30:00Z",
                "point": "geo:51.5074,-0.1278"
              },
              {
                "timestamp": "2023-07-20T08:45:00Z",
                "latitude": 51.5080,
                "longitude": -0.1285
              }
            ]
        """.trimIndent()

        val points = GoogleTimelineParser.parseTimeline(ByteArrayInputStream(json.toByteArray()))
        assertEquals(2, points.size)
        assertEquals(51.5074, points[0].latitude, 0.0001)
        assertEquals(-0.1278, points[0].longitude, 0.0001)
        assertEquals(51.5080, points[1].latitude, 0.0001)
        assertEquals(-0.1285, points[1].longitude, 0.0001)
    }

    @Test
    fun `test modern Android Timeline semantic segments`() {
        val json = """
            {
              "semanticSegments": [
                {
                  "startTime": "2025-03-14T09:02:00.000+01:00",
                  "endTime": "2025-03-14T11:40:00.000+01:00",
                  "visit": {
                    "topCandidate": {
                      "placeLocation": {
                        "latLng": "48.8566°, 2.3522°"
                      }
                    }
                  }
                },
                {
                  "startTime": "2025-03-14T11:40:00.000+01:00",
                  "endTime": "2025-03-14T12:10:00.000+01:00",
                  "activity": {
                    "start": { "latLng": "48.8566°, 2.3522°" },
                    "end": { "latLng": "48.8600°, 2.3500°" }
                  },
                  "timelinePath": [
                    {
                      "point": "48.8570°, 2.3520°",
                      "time": "2025-03-14T11:45:00.000+01:00"
                    },
                    {
                      "point": "48.8580°, 2.3510°",
                      "time": "2025-03-14T11:55:00.000+01:00"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val points = GoogleTimelineParser.parseTimeline(ByteArrayInputStream(json.toByteArray()))
        assertEquals(5, points.size)
        assertTrue(points.any { it.source == "visit" && it.latitude == 48.8566 })
        assertTrue(points.any { it.source == "activity_start" && it.longitude == 2.3522 })
        assertTrue(points.any { it.source == "activity_end" && it.longitude == 2.3500 })
        assertTrue(points.any { it.source == "timeline_path" && it.latitude == 48.8570 })
        assertTrue(points.zipWithNext().all { (a, b) -> a.timestampMillis <= b.timestampMillis })
    }

    @Test
    fun `test modern Timeline raw signal reads nested position timestamp`() {
        val json = """
            {
              "rawSignals": [
                {
                  "position": {
                    "LatLng": "22.5726°, 88.3639°",
                    "accuracyMeters": 12.5,
                    "altitudeMeters": 9.0,
                    "timestamp": "2025-04-01T10:30:00+05:30"
                  }
                }
              ]
            }
        """.trimIndent()

        val points = GoogleTimelineParser.parseTimeline(ByteArrayInputStream(json.toByteArray()))
        assertEquals(1, points.size)
        assertEquals(22.5726, points[0].latitude, 0.0001)
        assertEquals(88.3639, points[0].longitude, 0.0001)
        assertEquals(12.5f, points[0].accuracyMeters ?: 0f, 0.01f)
        assertEquals(9.0, points[0].altitudeMeters ?: 0.0, 0.01)
        assertEquals("raw_signal", points[0].source)
    }

    @Test
    fun `test iOS bare array Timeline visits and route offsets`() {
        val json = """
            [
              {
                "startTime": "2025-05-01T08:00:00Z",
                "endTime": "2025-05-01T09:00:00Z",
                "visit": {
                  "topCandidate": {
                    "placeLocation": "geo:51.5074,-0.1278"
                  }
                }
              },
              {
                "startTime": "2025-05-01T09:00:00Z",
                "endTime": "2025-05-01T10:00:00Z",
                "timelinePath": [
                  {
                    "point": "geo:51.5080,-0.1280",
                    "durationMinutesOffsetFromStartTime": "15"
                  }
                ]
              }
            ]
        """.trimIndent()

        val points = GoogleTimelineParser.parseTimeline(ByteArrayInputStream(json.toByteArray()))
        assertEquals(2, points.size)
        assertEquals(51.5074, points[0].latitude, 0.0001)
        assertEquals(-0.1278, points[0].longitude, 0.0001)
        assertEquals(51.5080, points[1].latitude, 0.0001)
        assertEquals(
            GoogleTimelineParser.parseTimestampToMillis("2025-05-01T09:15:00Z"),
            points[1].timestampMillis
        )
    }

    @Test
    fun `test numeric Unix seconds are normalized to milliseconds`() {
        val millis = GoogleTimelineParser.parseTimestampToMillis("1718460089")
        assertEquals(1718460089000L, millis)
    }

    @Test
    fun `test already geotagged images are skipped`() {
        val existingLat = 48.8566
        val existingLng = 2.3522
        val candidate = ImageGeotagCandidate(
            uriString = "content://media/1",
            displayName = "Paris_Eiffel.jpg",
            dateTimeOriginalMillis = 1684060000000L,
            hasExistingGeotag = true,
            existingLatitude = existingLat,
            existingLongitude = existingLng
        )

        val timelinePoints = listOf(
            TimelineLocationPoint(
                timestampMillis = 1684060000000L,
                latitude = 40.7128,
                longitude = -74.0060
            )
        )

        val config = GeotagConfig(skipAlreadyGeotagged = true)
        val result = TimelineGeotagEngine.matchCandidate(candidate, timelinePoints, config)

        // Must be skipped and retain its existing GPS
        assertEquals(GeotagStatus.ALREADY_GEOTAGGED, result.status)
        assertNotNull(result.matchedPoint)
        assertEquals(existingLat, result.matchedPoint?.latitude ?: 0.0, 0.0001)
        assertEquals(existingLng, result.matchedPoint?.longitude ?: 0.0, 0.0001)
    }

    @Test
    fun `test matching candidate within time window`() {
        val baseTime = 1684060000000L
        val timelinePoints = listOf(
            TimelineLocationPoint(
                timestampMillis = baseTime - 120_000L, // 2 minutes before
                latitude = 35.6762,
                longitude = 139.6503
            ),
            TimelineLocationPoint(
                timestampMillis = baseTime + 300_000L, // 5 minutes after
                latitude = 35.6770,
                longitude = 139.6520
            )
        )

        val unGeotaggedCandidate = ImageGeotagCandidate(
            uriString = "content://media/2",
            displayName = "Tokyo_Tower.jpg",
            dateTimeOriginalMillis = baseTime,
            hasExistingGeotag = false
        )

        val config = GeotagConfig(
            maxTimeDeltaSeconds = 900L,
            interpolateBetweenPoints = false
        )
        val result = TimelineGeotagEngine.matchCandidate(unGeotaggedCandidate, timelinePoints, config)

        assertEquals(GeotagStatus.MATCHED, result.status)
        assertNotNull(result.matchedPoint)
        // Should pick the closest (2 minutes before)
        assertEquals(35.6762, result.matchedPoint?.latitude ?: 0.0, 0.0001)
        assertEquals(120L, result.timeDifferenceSeconds)
    }

    @Test
    fun `test linear interpolation between surrounding timeline points`() {
        val t1 = 1684060000000L
        val t2 = 1684060600000L // 10 minutes later (600,000 ms)
        val p1 = TimelineLocationPoint(t1, latitude = 10.0, longitude = 20.0, altitudeMeters = 100.0)
        val p2 = TimelineLocationPoint(t2, latitude = 20.0, longitude = 40.0, altitudeMeters = 200.0)

        val photoTime = t1 + 300_000L // Exactly halfway (50%)
        val candidate = ImageGeotagCandidate(
            uriString = "content://media/3",
            displayName = "Moving_Train.jpg",
            dateTimeOriginalMillis = photoTime,
            hasExistingGeotag = false
        )

        val config = GeotagConfig(
            interpolateBetweenPoints = true,
            maxInterpolationGapSeconds = 1800L
        )

        val result = TimelineGeotagEngine.matchCandidate(candidate, listOf(p1, p2), config)

        assertEquals(GeotagStatus.MATCHED, result.status)
        assertTrue(result.isInterpolated)
        assertNotNull(result.matchedPoint)
        // 50% between (10, 20) and (20, 40) is (15, 30)
        assertEquals(15.0, result.matchedPoint?.latitude ?: 0.0, 0.0001)
        assertEquals(30.0, result.matchedPoint?.longitude ?: 0.0, 0.0001)
        assertEquals(150.0, result.matchedPoint?.altitudeMeters ?: 0.0, 0.0001)
    }

    @Test
    fun `test unmatched when time delta exceeds maximum allowed window`() {
        val baseTime = 1684060000000L
        val timelinePoints = listOf(
            TimelineLocationPoint(
                timestampMillis = baseTime - 7200_000L, // 2 hours before
                latitude = 35.6762,
                longitude = 139.6503
            )
        )

        val candidate = ImageGeotagCandidate(
            uriString = "content://media/4",
            displayName = "Late_Photo.jpg",
            dateTimeOriginalMillis = baseTime,
            hasExistingGeotag = false
        )

        val config = GeotagConfig(maxTimeDeltaSeconds = 900L) // 15 mins
        val result = TimelineGeotagEngine.matchCandidate(candidate, timelinePoints, config)

        assertEquals(GeotagStatus.UNMATCHED, result.status)
    }

    @Test
    fun `test camera clock drift adjustment`() {
        val timelinePointTime = 1684060000000L
        val timelinePoints = listOf(
            TimelineLocationPoint(
                timestampMillis = timelinePointTime,
                latitude = 52.5200,
                longitude = 13.4050
            )
        )

        // Photo camera clock was 60 seconds slow
        val photoCameraTime = timelinePointTime - 60_000L
        val candidate = ImageGeotagCandidate(
            uriString = "content://media/5",
            displayName = "Drift_Photo.jpg",
            dateTimeOriginalMillis = photoCameraTime,
            hasExistingGeotag = false
        )

        // Set camera clock offset to +60 seconds to correct for the slow camera clock
        val config = GeotagConfig(cameraClockOffsetSeconds = 60L)
        val result = TimelineGeotagEngine.matchCandidate(candidate, timelinePoints, config)

        assertEquals(GeotagStatus.MATCHED, result.status)
        assertEquals(0L, result.timeDifferenceSeconds)
        assertEquals(52.5200, result.matchedPoint?.latitude ?: 0.0, 0.0001)
    }

    @Test
    fun `test EXIF date parsing with timezone offset`() {
        val exifDate = "2023:08:15 14:30:00"
        val offset = "+02:00"

        val millisWithOffset = TimelineGeotagEngine.parseExifDateTime(exifDate, offset)
        assertNotNull(millisWithOffset)

        val millisUtc = TimelineGeotagEngine.parseExifDateTime(exifDate, "+00:00")
        assertNotNull(millisUtc)

        // +02:00 should be 2 hours earlier in UTC epoch millis than +00:00
        val diffHours = (millisUtc!! - millisWithOffset!!) / (1000L * 3600L)
        assertEquals(2L, diffHours)
    }

    @Test
    fun `test GPX export generation`() {
        val points = listOf(
            TimelineLocationPoint(
                timestampMillis = 1684060000000L,
                latitude = 37.7749,
                longitude = -122.4194,
                altitudeMeters = 10.5
            )
        )
        val gpx = TimelineGeotagEngine.exportToGpx(points, "Test Track")
        assertTrue(gpx.contains("<gpx version=\"1.1\""))
        assertTrue(gpx.contains("lat=\"37.7749\""))
        assertTrue(gpx.contains("lon=\"-122.4194\""))
        assertTrue(gpx.contains("<ele>10.5</ele>"))
        assertTrue(gpx.contains("<name>Test Track</name>"))
    }

    // --- Phase 1: PhotoTimestampResolver Tests ---

    @Test
    fun `test PhotoTimestampResolver tag priority and offset resolution`() {
        val baseDateStr = "2024:06:15 12:00:00"
        val offset = "+02:00"
        val msWithOffset = PhotoTimestampResolver.parseDateTime(
            dateStr = baseDateStr,
            offsetStr = offset
        )
        assertNotNull(msWithOffset)

        val msUtc = PhotoTimestampResolver.parseDateTime(
            dateStr = baseDateStr,
            offsetStr = "+00:00"
        )
        assertNotNull(msUtc)
        // +02:00 should be 2 hours earlier in epoch ms than +00:00
        assertEquals(2 * 3600 * 1000L, msUtc!! - msWithOffset!!)
    }

    @Test
    fun `test PhotoTimestampResolver file modified fallback`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val tempFile = File(context.cacheDir, "timestamp_test.tmp")
        tempFile.writeText("sample data")
        tempFile.setLastModified(1700000000000L)

        val resolved = PhotoTimestampResolver.resolve(
            exif = null,
            filePath = tempFile.absolutePath,
            displayName = null,
            fallbackMillis = null
        )
        assertNotNull(resolved)
        assertEquals("FileLastModified", resolved?.sourceTag)
        assertEquals(1700000000000L, resolved?.timeMs)
        tempFile.delete()
    }

    @Test
    fun `test PhotoTimestampResolver resolves filename timestamps`() {
        val zone = ZoneOffset.UTC

        // Standard camera naming: IMG_YYYYMMDD_HHMMSS.jpg
        val imgTime = PhotoTimestampResolver.parseFilenameTimestamp("IMG_20230514_153022.jpg", zone)
        assertNotNull(imgTime)
        val expectedImg = java.time.LocalDateTime.of(2023, 5, 14, 15, 30, 22).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(expectedImg, imgTime)

        // Pixel naming: PXL_YYYYMMDD_HHMMSSNNN.jpg
        val pxlTime = PhotoTimestampResolver.parseFilenameTimestamp("PXL_20240102_123456789.jpg", zone)
        assertNotNull(pxlTime)
        val expectedPxl = java.time.LocalDateTime.of(2024, 1, 2, 12, 34, 56).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(expectedPxl, pxlTime)

        // Screenshot naming: Screenshot_YYYY-MM-DD-HH-mm-ss.png
        val screenTime = PhotoTimestampResolver.parseFilenameTimestamp("Screenshot_2023-08-15-14-20-33.png", zone)
        assertNotNull(screenTime)
        val expectedScreen = java.time.LocalDateTime.of(2023, 8, 15, 14, 20, 33).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(expectedScreen, screenTime)

        // WhatsApp naming: WhatsApp Image 2023-05-14 at 15.30.22.jpeg
        val waTime = PhotoTimestampResolver.parseFilenameTimestamp("WhatsApp Image 2023-05-14 at 15.30.22.jpeg", zone)
        assertNotNull(waTime)
        assertEquals(expectedImg, waTime)

        // Unix epoch milliseconds: 1684078222000.jpg
        val epochTime = PhotoTimestampResolver.parseFilenameTimestamp("1684078222000.jpg", zone)
        assertEquals(1684078222000L, epochTime)

        // Full resolve chain returning FilenameTimestamp
        val resolved = PhotoTimestampResolver.resolve(
            exif = null,
            filePath = null,
            displayName = "IMG_20230514_153022.jpg",
            fallbackMillis = null,
            systemZoneId = zone
        )
        assertNotNull(resolved)
        assertEquals("FilenameTimestamp", resolved?.sourceTag)
        assertEquals(expectedImg, resolved?.timeMs)
    }

    @Test
    fun `test PhotoTimestampResolver MediaStore date fallback when EXIF and filename lack timestamp`() {
        val resolved = PhotoTimestampResolver.resolve(
            exif = null,
            filePath = null,
            displayName = "generic_download.jpg",
            fallbackMillis = 1715000000000L
        )
        assertNotNull(resolved)
        assertEquals("MediaStoreDate", resolved?.sourceTag)
        assertEquals(1715000000000L, resolved?.timeMs)
    }

    @Test
    fun `test extractCandidate succeeds without DateTimeOriginal via filename or MediaStore`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val uri = android.net.Uri.parse("content://media/external/images/media/999")

        val candidate = TimelineGeotagEngine.extractCandidate(
            context = context,
            uri = uri,
            fallbackPath = null,
            displayName = "IMG_20230514_153022.jpg",
            isVideo = false,
            fallbackTimestampMillis = 1715000000000L
        )

        assertNotNull(candidate.dateTimeOriginalMillis)
        assertEquals("FilenameTimestamp", candidate.timestampSource)
    }

    @Test
    fun `test PhotoTimestampResolver and extractCandidate fall back to lastmodified`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val expectedLastModified = 1705000000000L

        // Direct resolver check with generic filename and no EXIF
        val resolved = PhotoTimestampResolver.resolve(
            exif = null,
            filePath = null,
            displayName = "download.jpg",
            fallbackMillis = null,
            fallbackLastModifiedMillis = expectedLastModified
        )
        assertNotNull(resolved)
        assertEquals("FileLastModified", resolved?.sourceTag)
        assertEquals(expectedLastModified, resolved?.timeMs)

        // Engine candidate extraction check falling back to lastModified
        val candidate = TimelineGeotagEngine.extractCandidate(
            context = context,
            uri = android.net.Uri.parse("content://media/external/images/media/1001"),
            fallbackPath = null,
            displayName = "unnamed_photo.png",
            isVideo = false,
            fallbackTimestampMillis = null,
            fallbackLastModifiedMillis = expectedLastModified
        )

        assertNotNull(candidate.dateTimeOriginalMillis)
        assertEquals(expectedLastModified, candidate.dateTimeOriginalMillis)
        assertEquals("FileLastModified", candidate.timestampSource)
    }

    // --- Phase 2: GoogleTimelineParser Segmented Timeline Tests ---

    @Test
    fun `test GoogleTimelineParser parses SegmentedTimeline maintaining segment hierarchy`() {
        val json = """
            {
              "semanticSegments": [
                {
                  "startTime": "2025-06-01T10:00:00.000Z",
                  "endTime": "2025-06-01T11:00:00.000Z",
                  "visit": {
                    "topCandidate": {
                      "placeLocation": {
                        "latLng": "37.7749°, -122.4194°"
                      }
                    }
                  }
                },
                {
                  "startTime": "2025-06-01T11:00:00.000Z",
                  "endTime": "2025-06-01T11:30:00.000Z",
                  "activity": {
                    "start": { "latLng": "37.7749°, -122.4194°" },
                    "end": { "latLng": "37.7800°, -122.4100°" }
                  },
                  "timelinePath": [
                    {
                      "point": "37.7770°, -122.4150°",
                      "time": "2025-06-01T11:15:00.000Z"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val segmented = GoogleTimelineParser.parseSegmentedTimeline(ByteArrayInputStream(json.toByteArray()))
        assertEquals(1, segmented.visits.size)
        assertEquals(1, segmented.activitySegments.size)

        val visit = segmented.visits[0]
        assertEquals(37.7749, visit.latitude, 0.0001)
        assertEquals(-122.4194, visit.longitude, 0.0001)

        val activity = segmented.activitySegments[0]
        assertEquals(3, activity.pathPoints.size) // start, path, end
        assertEquals("activity_start", activity.pathPoints[0].source)
        assertEquals("activity_end", activity.pathPoints[2].source)
    }

    // --- Phase 5: TimelinePathInterpolator Tests ---

    @Test
    fun `test TimelinePathInterpolator interpolates linearly within single activity segment`() {
        val tStart = 1700000000000L
        val tEnd = tStart + 1800_000L // 30 minutes later

        val pStart = TimelinePathPoint(tStart, 30.0, 50.0, source = "activity_start", isEstimated = false)
        val pEnd = TimelinePathPoint(tEnd, 40.0, 70.0, source = "activity_end", isEstimated = false)

        val segment = TimelineActivitySegment(
            segmentId = "seg1",
            startTimestampMillis = tStart,
            endTimestampMillis = tEnd,
            pathPoints = listOf(pStart, pEnd)
        )
        val timeline = SegmentedTimeline(activitySegments = listOf(segment))

        val photoTime = tStart + 900_000L // exactly 50%
        val config = GeotagConfig(maxInterpolationGapSeconds = 2400L) // 40 min

        val match = TimelinePathInterpolator.match(photoTime, timeline, config)
        assertEquals(GeotagStatus.MATCHED, match.status)
        assertNotNull(match.matchedPoint)
        assertEquals(35.0, match.matchedPoint?.latitude ?: 0.0, 0.0001)
        assertEquals(60.0, match.matchedPoint?.longitude ?: 0.0, 0.0001)
        assertEquals("interpolated", match.provenance?.matchMethod)
        assertEquals(30.0, match.provenance?.sampleGapMinutes ?: 0.0, 0.1)
    }

    @Test
    fun `test TimelinePathInterpolator rejects oversized gap greater than max allowed gap`() {
        val tStart = 1700000000000L
        val tEnd = tStart + 3600_000L // 60 minutes later

        val pStart = TimelinePathPoint(tStart, 30.0, 50.0, source = "activity_start", isEstimated = false)
        val pEnd = TimelinePathPoint(tEnd, 40.0, 70.0, source = "activity_end", isEstimated = false)

        val segment = TimelineActivitySegment(
            segmentId = "seg2",
            startTimestampMillis = tStart,
            endTimestampMillis = tEnd,
            pathPoints = listOf(pStart, pEnd)
        )
        val timeline = SegmentedTimeline(activitySegments = listOf(segment))

        val photoTime = tStart + 1800_000L // 30 minutes in
        // Allowed gap is only 15 minutes (900 seconds)
        val config = GeotagConfig(
            maxInterpolationGapSeconds = 900L,
            maxTimeDeltaSeconds = 300L // 5 min tolerance
        )

        val match = TimelinePathInterpolator.match(photoTime, timeline, config)
        // Since gap is 60 min > 15 min max allowed, and photo is 30 min from both points (> 5 min tolerance), it must be UNMATCHED
        assertEquals(GeotagStatus.UNMATCHED, match.status)
    }

    @Test
    fun `test TimelinePathInterpolator never interpolates across separate activity segments`() {
        val trip1Start = 1700000000000L
        val trip1End = trip1Start + 600_000L // 10 min

        val trip2Start = trip1End + 3600_000L // 1 hour later
        val trip2End = trip2Start + 600_000L

        val segment1 = TimelineActivitySegment(
            segmentId = "trip1",
            startTimestampMillis = trip1Start,
            endTimestampMillis = trip1End,
            pathPoints = listOf(
                TimelinePathPoint(trip1Start, 10.0, 10.0, source = "activity_start", isEstimated = false),
                TimelinePathPoint(trip1End, 12.0, 12.0, source = "activity_end", isEstimated = false)
            )
        )
        val segment2 = TimelineActivitySegment(
            segmentId = "trip2",
            startTimestampMillis = trip2Start,
            endTimestampMillis = trip2End,
            pathPoints = listOf(
                TimelinePathPoint(trip2Start, 50.0, 50.0, source = "activity_start", isEstimated = false),
                TimelinePathPoint(trip2End, 52.0, 52.0, source = "activity_end", isEstimated = false)
            )
        )
        val timeline = SegmentedTimeline(activitySegments = listOf(segment1, segment2))

        // Photo taken in the middle between trip 1 and trip 2
        val photoTime = trip1End + 1800_000L // 30 min after trip 1 ended
        val config = GeotagConfig(maxInterpolationGapSeconds = 7200L, maxTimeDeltaSeconds = 300L)

        val match = TimelinePathInterpolator.match(photoTime, timeline, config)
        // Must NOT interpolate between trip 1 end and trip 2 start!
        assertNotEquals("interpolated", match.provenance?.matchMethod)
        assertEquals(GeotagStatus.UNMATCHED, match.status)
    }

    // --- Phase 4: SQLite Database Additive Migration Tests ---

    @Test
    fun `test PhotoMapLocationCache saves and retrieves enriched provenance`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val cache = PhotoMapLocationCache(context)

        val uri = "content://media/test_photo_prov"
        val geoPoint = org.osmdroid.util.GeoPoint(34.0522, -118.2437)
        val provenance = LocationProvenance(
            source = "Google Timeline",
            matchMethod = "interpolated",
            resolvedTimestamp = 1718000000000L,
            timestampSource = "DateTimeOriginal",
            sampleBeforeTime = 1717999000000L,
            sampleAfterTime = 1718001000000L,
            sampleGapMinutes = 33.3,
            isEstimated = false,
            exifWritten = false
        )

        cache.save(uri, geoPoint, provenance)

        val loaded = cache.getLocation(uri)
        assertNotNull(loaded)
        assertNotNull(loaded!!.point)
        assertEquals(34.0522, loaded.point!!.latitude, 0.0001)
        assertEquals(-118.2437, loaded.point!!.longitude, 0.0001)
        assertNotNull(loaded.provenance)
        assertEquals("interpolated", loaded.provenance?.matchMethod)
        assertEquals("DateTimeOriginal", loaded.provenance?.timestampSource)
        assertEquals(33.3, loaded.provenance?.sampleGapMinutes ?: 0.0, 0.1)
        assertFalse(loaded.provenance?.exifWritten ?: true)
    }

    // --- Phase 7: Bit-for-bit file safety verification when writeExif is false ---

    @Test
    fun `test original media file remains bit-for-bit unchanged when writeExif is false`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val testFile = File(context.cacheDir, "bit_for_bit_safe.jpg")
        val originalBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01)
        testFile.writeBytes(originalBytes)

        val candidate = ImageGeotagCandidate(
            uriString = android.net.Uri.fromFile(testFile).toString(),
            displayName = testFile.name,
            filePath = testFile.absolutePath,
            dateTimeOriginalMillis = 1700000000000L,
            hasExistingGeotag = false
        )

        val resultItem = GeotagResultItem(
            candidate = candidate,
            status = GeotagStatus.MATCHED,
            matchedPoint = TimelineLocationPoint(1700000000000L, latitude = 51.5074, longitude = -0.1278, source = "timeline"),
            matchMethod = "exact"
        )

        // Apply with writeExif = false (EXIF safety default)
        val success = TimelineGeotagEngine.applyGeotag(context, resultItem, writeExif = false).getOrDefault(false)
        assertTrue(success)

        val afterBytes = testFile.readBytes()
        assertArrayEquals("Original file bytes must remain bit-for-bit unchanged when writeExif is false", originalBytes, afterBytes)

        // But location must be present in PhotoMapLocationCache
        val cached = PhotoMapLocationCache(context).getLocation(candidate.uriString)
        assertNotNull(cached)
        assertNotNull(cached!!.point)
        assertEquals(51.5074, cached.point!!.latitude, 0.0001)
        assertFalse(cached.provenance?.exifWritten ?: true)

        testFile.delete()
    }
}
