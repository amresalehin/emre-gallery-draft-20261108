package com.amresalehin.emregallery.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import org.osmdroid.util.GeoPoint
import java.io.File

/**
 * Enriched provenance tracking how a photo location was matched and persisted.
 */
data class LocationProvenance(
    val source: String? = null,
    val matchMethod: String? = null,
    val resolvedTimestamp: Long? = null,
    val timestampSource: String? = null,
    val sampleBeforeTime: Long? = null,
    val sampleAfterTime: Long? = null,
    val sampleGapMinutes: Double? = null,
    val isEstimated: Boolean = false,
    val exifWritten: Boolean = false
)

/**
 * Persistent SQLite cache for image and video GPS metadata, including media with no GPS coordinates
 * and match provenance metadata.
 */
class PhotoMapLocationCache(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "photo_map_locations.db", null, 4) {

    data class CachedLocation(
        val point: GeoPoint?,
        val provenance: LocationProvenance? = null
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS photo_locations (
                uri TEXT PRIMARY KEY NOT NULL,
                latitude REAL,
                longitude REAL,
                source TEXT,
                match_method TEXT,
                resolved_timestamp INTEGER,
                timestamp_source TEXT,
                sample_before_time INTEGER,
                sample_after_time INTEGER,
                sample_gap_minutes REAL,
                is_estimated INTEGER DEFAULT 0,
                exif_written INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Additive migration: preserve all cached coordinates across schema upgrades
        if (oldVersion < 4) {
            runCatching { db.execSQL("ALTER TABLE photo_locations ADD COLUMN source TEXT") }
            runCatching { db.execSQL("ALTER TABLE photo_locations ADD COLUMN match_method TEXT") }
            runCatching { db.execSQL("ALTER TABLE photo_locations ADD COLUMN resolved_timestamp INTEGER") }
            runCatching { db.execSQL("ALTER TABLE photo_locations ADD COLUMN timestamp_source TEXT") }
            runCatching { db.execSQL("ALTER TABLE photo_locations ADD COLUMN sample_before_time INTEGER") }
            runCatching { db.execSQL("ALTER TABLE photo_locations ADD COLUMN sample_after_time INTEGER") }
            runCatching { db.execSQL("ALTER TABLE photo_locations ADD COLUMN sample_gap_minutes REAL") }
            runCatching { db.execSQL("ALTER TABLE photo_locations ADD COLUMN is_estimated INTEGER DEFAULT 0") }
            runCatching { db.execSQL("ALTER TABLE photo_locations ADD COLUMN exif_written INTEGER DEFAULT 0") }
        }
    }

    @Synchronized
    fun clearAll() {
        writableDatabase.delete("photo_locations", null, null)
    }

    @Synchronized
    fun getLocation(uri: String): CachedLocation? {
        readableDatabase.query(
            "photo_locations",
            ALL_COLUMNS,
            "uri = ?",
            arrayOf(uri),
            null,
            null,
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return parseCursorRow(cursor)
            }
        }
        return null
    }

    @Synchronized
    fun loadAll(): Map<String, CachedLocation> {
        val result = mutableMapOf<String, CachedLocation>()
        readableDatabase.query(
            "photo_locations",
            ALL_COLUMNS,
            null,
            null,
            null,
            null,
            null
        ).use { cursor ->
            val uriColumn = cursor.getColumnIndexOrThrow("uri")
            while (cursor.moveToNext()) {
                val uri = cursor.getString(uriColumn)
                val location = parseCursorRow(cursor)
                if (location != null) {
                    result[uri] = location
                }
            }
        }
        return result
    }

    @Synchronized
    fun save(uri: String, point: GeoPoint?, provenance: LocationProvenance? = null) {
        val values = ContentValues().apply {
            put("uri", uri)
            if (point == null) {
                putNull("latitude")
                putNull("longitude")
            } else {
                put("latitude", point.latitude)
                put("longitude", point.longitude)
            }
            if (provenance != null) {
                put("source", provenance.source)
                put("match_method", provenance.matchMethod)
                if (provenance.resolvedTimestamp != null) {
                    put("resolved_timestamp", provenance.resolvedTimestamp)
                } else {
                    putNull("resolved_timestamp")
                }
                put("timestamp_source", provenance.timestampSource)
                if (provenance.sampleBeforeTime != null) {
                    put("sample_before_time", provenance.sampleBeforeTime)
                } else {
                    putNull("sample_before_time")
                }
                if (provenance.sampleAfterTime != null) {
                    put("sample_after_time", provenance.sampleAfterTime)
                } else {
                    putNull("sample_after_time")
                }
                if (provenance.sampleGapMinutes != null) {
                    put("sample_gap_minutes", provenance.sampleGapMinutes)
                } else {
                    putNull("sample_gap_minutes")
                }
                put("is_estimated", if (provenance.isEstimated) 1 else 0)
                put("exif_written", if (provenance.exifWritten) 1 else 0)
            }
        }
        writableDatabase.insertWithOnConflict(
            "photo_locations", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun parseCursorRow(cursor: android.database.Cursor): CachedLocation? {
        val latitudeColumn = cursor.getColumnIndex("latitude")
        val longitudeColumn = cursor.getColumnIndex("longitude")
        val hasCoordinates = latitudeColumn >= 0 && longitudeColumn >= 0 &&
            !cursor.isNull(latitudeColumn) && !cursor.isNull(longitudeColumn)

        val point = if (!hasCoordinates) {
            null
        } else {
            val lat = cursor.getDouble(latitudeColumn)
            val lon = cursor.getDouble(longitudeColumn)
            if (
                lat.isFinite() && lon.isFinite() &&
                lat in -90.0..90.0 && lon in -180.0..180.0 &&
                !(lat == 0.0 && lon == 0.0)
            ) {
                GeoPoint(lat, lon)
            } else {
                null
            }
        }

        // Old builds could cache (0, 0) as a real GPS coordinate. Do not turn
        // an invalid coordinate into a permanent "no GPS" cache entry: omit it
        if (hasCoordinates && point == null) return null

        val sourceIdx = cursor.getColumnIndex("source")
        val methodIdx = cursor.getColumnIndex("match_method")
        val resolvedTsIdx = cursor.getColumnIndex("resolved_timestamp")
        val tsSourceIdx = cursor.getColumnIndex("timestamp_source")
        val beforeIdx = cursor.getColumnIndex("sample_before_time")
        val afterIdx = cursor.getColumnIndex("sample_after_time")
        val gapIdx = cursor.getColumnIndex("sample_gap_minutes")
        val estimatedIdx = cursor.getColumnIndex("is_estimated")
        val writtenIdx = cursor.getColumnIndex("exif_written")

        val provenance = if (sourceIdx >= 0 || methodIdx >= 0) {
            LocationProvenance(
                source = if (sourceIdx >= 0 && !cursor.isNull(sourceIdx)) cursor.getString(sourceIdx) else null,
                matchMethod = if (methodIdx >= 0 && !cursor.isNull(methodIdx)) cursor.getString(methodIdx) else null,
                resolvedTimestamp = if (resolvedTsIdx >= 0 && !cursor.isNull(resolvedTsIdx)) cursor.getLong(resolvedTsIdx) else null,
                timestampSource = if (tsSourceIdx >= 0 && !cursor.isNull(tsSourceIdx)) cursor.getString(tsSourceIdx) else null,
                sampleBeforeTime = if (beforeIdx >= 0 && !cursor.isNull(beforeIdx)) cursor.getLong(beforeIdx) else null,
                sampleAfterTime = if (afterIdx >= 0 && !cursor.isNull(afterIdx)) cursor.getLong(afterIdx) else null,
                sampleGapMinutes = if (gapIdx >= 0 && !cursor.isNull(gapIdx)) cursor.getDouble(gapIdx) else null,
                isEstimated = estimatedIdx >= 0 && cursor.getInt(estimatedIdx) == 1,
                exifWritten = writtenIdx >= 0 && cursor.getInt(writtenIdx) == 1
            )
        } else null

        return CachedLocation(point, provenance)
    }

    /**
     * Scans a batch of media items, caching GPS coordinates to the database.
     * Can be safely called from background services or foreground screens.
     */
    fun scanMediaBatch(
        context: Context,
        media: List<MediaItem>,
        forceRescan: Boolean = false,
        onProgress: ((scanned: Int, total: Int, located: Int) -> Unit)? = null
    ): Int {
        // Enforce background priority so this scanner never contends with the UI thread or RenderThread
        runCatching {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        }
        val items = media
        if (forceRescan) {
            clearAll()
        }
        val cached = loadAll()
        val pending = if (forceRescan) items else items.filterNot { cached.containsKey(it.uri) }
        if (pending.isEmpty()) return 0

        var newlyLocated = 0
        var totalLocated = cached.values.count { it.point != null }

        // Process in throttled batches with batched transactions to minimize disk sync and CPU usage
        val batchSize = 25
        for (batch in pending.chunked(batchSize)) {
            val extracted = batch.map { item ->
                item to extractLocation(context, item.uri, isVideo = item.isVideo)
            }

            val db = writableDatabase
            db.beginTransaction()
            try {
                for ((item, point) in extracted) {
                    val values = ContentValues().apply {
                        put("uri", item.uri)
                        if (point == null) {
                            putNull("latitude")
                            putNull("longitude")
                        } else {
                            put("latitude", point.latitude)
                            put("longitude", point.longitude)
                            put("source", "exif")
                            put("match_method", "direct_exif")
                            put("exif_written", 1)
                        }
                    }
                    db.insertWithOnConflict("photo_locations", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                    if (point != null) {
                        newlyLocated++
                        totalLocated++
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            onProgress?.invoke(newlyLocated, pending.size, totalLocated)
        }
        return newlyLocated
    }

    companion object {
        private val ALL_COLUMNS = arrayOf(
            "uri", "latitude", "longitude", "source", "match_method",
            "resolved_timestamp", "timestamp_source", "sample_before_time",
            "sample_after_time", "sample_gap_minutes", "is_estimated", "exif_written"
        )

        /**
         * Safely extracts latitude and longitude from an image URI's EXIF data or video metadata.
         */
        fun extractLocation(
            context: Context,
            uriString: String,
            isVideo: Boolean = false
        ): GeoPoint? {
            val uri = Uri.parse(uriString)

            // Direct local file support
            if (uri.scheme == null || uri.scheme == ContentResolver.SCHEME_FILE) {
                val path = uri.path ?: uriString
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    val directPoint = runCatching {
                        val exif = ExifInterface(file)
                        getGeoPointFromExif(exif)
                    }.getOrNull()
                    if (directPoint != null) return directPoint
                }
            }

            // On Android 10+ (API 29+), content URIs require MediaStore.setRequireOriginal(uri)
            val targetUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == ContentResolver.SCHEME_CONTENT) {
                runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
            } else {
                uri
            }

            // 1. Try ExifInterface with FileDescriptor
            val fromFd = runCatching {
                context.contentResolver.openFileDescriptor(targetUri, "r")?.use { pfd ->
                    val exif = ExifInterface(pfd.fileDescriptor)
                    getGeoPointFromExif(exif)
                }
            }.getOrNull()
            if (fromFd != null) return fromFd

            // 2. Try ExifInterface with InputStream on targetUri
            val fromStream = runCatching {
                context.contentResolver.openInputStream(targetUri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    getGeoPointFromExif(exif)
                }
            }.getOrNull()
            if (fromStream != null) return fromStream

            // 3. Fallback to original uri
            if (targetUri != uri) {
                val fromOrigFd = runCatching {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        val exif = ExifInterface(pfd.fileDescriptor)
                        getGeoPointFromExif(exif)
                    }
                }.getOrNull()
                if (fromOrigFd != null) return fromOrigFd

                val fromOrigStream = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val exif = ExifInterface(stream)
                        getGeoPointFromExif(exif)
                    }
                }.getOrNull()
                if (fromOrigStream != null) return fromOrigStream
            }

            if (isVideo) {
                val fromMetadata = runCatching {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, uri)
                        val locStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
                        parseIso6709(locStr)
                    } finally {
                        retriever.release()
                    }
                }.getOrNull()
                if (fromMetadata != null) return fromMetadata
            }

            return null
        }

        private fun getGeoPointFromExif(exif: ExifInterface): GeoPoint? {
            val latLong = exif.latLong
            if (latLong != null && latLong.size >= 2) {
                val lat = latLong[0]
                val lon = latLong[1]
                if (lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)) {
                    return GeoPoint(lat, lon)
                }
            }

            val latStr = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
            val latRef = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF)
            val lonStr = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)
            val lonRef = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF)
            if (!latStr.isNullOrBlank() && !lonStr.isNullOrBlank()) {
                val lat = parseDms(latStr, latRef)
                val lon = parseDms(lonStr, lonRef)
                if (lat != null && lon != null && lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)) {
                    return GeoPoint(lat, lon)
                }
            }

            return null
        }

        private fun parseDms(dms: String, ref: String?): Double? {
            return runCatching {
                val parts = dms.split(',', ' ').filter { it.isNotBlank() }
                val degrees: Double
                val minutes: Double
                val seconds: Double
                if (parts.size >= 3) {
                    degrees = parseRational(parts[0])
                    minutes = parseRational(parts[1])
                    seconds = parseRational(parts[2])
                } else if (parts.size == 1) {
                    degrees = parseRational(parts[0])
                    minutes = 0.0
                    seconds = 0.0
                } else {
                    return null
                }
                var decimal = degrees + (minutes / 60.0) + (seconds / 3600.0)
                val r = ref?.trim()?.uppercase()
                if (r == "S" || r == "W") {
                    decimal = -decimal
                }
                decimal
            }.getOrNull()
        }

        private fun parseRational(part: String): Double {
            val sub = part.split('/')
            return if (sub.size == 2) {
                val denom = sub[1].toDoubleOrNull() ?: 1.0
                if (denom != 0.0) (sub[0].toDoubleOrNull() ?: 0.0) / denom else 0.0
            } else {
                part.toDoubleOrNull() ?: 0.0
            }
        }

        private fun parseIso6709(raw: String?): GeoPoint? {
            if (raw.isNullOrBlank()) return null
            val match = Regex("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)").find(raw.trim()) ?: return null
            val lat = match.groupValues[1].toDoubleOrNull() ?: return null
            val lon = match.groupValues[2].toDoubleOrNull() ?: return null
            return if (lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)) {
                GeoPoint(lat, lon)
            } else null
        }
    }
}
