package com.amresalehin.emregallery.geotag

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Structured result of photo capture timestamp resolution.
 */
data class ResolvedTimestamp(
    val timeMs: Long,
    val sourceTag: String,
    val offsetString: String? = null
)

/**
 * Utility responsible for extracting and resolving image capture timestamps
 * with strict tag priority, timezone offset handling, GPS tags, filename parsing,
 * and MediaStore/filesystem fallbacks so geotagging is never limited to only DateTimeOriginal.
 */
object PhotoTimestampResolver {

    private val EXIF_DATE_FORMATTERS = listOf(
        DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US),
        DateTimeFormatter.ofPattern("yyyy:MM:dd'T'HH:mm:ss", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.US),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.US)
    )

    private val DATE_TIME_FILENAME_REGEX = Regex(
        """(?:^|[^0-9])(19\d\d|20\d\d)[-_.]?(0[1-9]|1[0-2])[-_.]?(0[1-9]|[12]\d|3[01])(?:\s*(?:at|@)\s*|[T\s_]+|[-_.]+)(0\d|1\d|2[0-3])[-_.:]?(0\d|[1-5]\d)[-_.:]?(0\d|[1-5]\d)""",
        RegexOption.IGNORE_CASE
    )

    private val DATE_ONLY_FILENAME_REGEX = Regex(
        """(?:^|[^0-9])(19\d\d|20\d\d)[-_.]?(0[1-9]|1[0-2])[-_.]?(0[1-9]|[12]\d|3[01])(?=$|[^0-9])"""
    )

    private val EPOCH_MILLIS_REGEX = Regex("""\b(1[5-8]\d{11})\b""")
    private val EPOCH_SECONDS_REGEX = Regex("""\b(1[5-8]\d{8})\b""")

    /**
     * Resolves the capture timestamp across all viable metadata and filesystem sources.
     *
     * Priority chain:
     * 1. DateTimeOriginal paired with OffsetTimeOriginal (or OffsetTime)
     * 2. Earliest of (DateTimeDigitized paired with OffsetTimeDigitized/OffsetTime, DateTime paired with OffsetTime)
     * 3. GPS Date & Time in EXIF (GPS DateStamp & TimeStamp parsed in UTC)
     * 4. Filename timestamp (e.g. IMG_YYYYMMDD_HHMMSS, WhatsApp, Screenshots, epoch)
     * 5. MediaStore date taken / date added (if provided by caller or queried)
     * 6. File system last modified timestamp (if file exists)
     */
    fun resolve(
        exif: ExifInterface?,
        filePath: String? = null,
        displayName: String? = null,
        fallbackMillis: Long? = null,
        fallbackLastModifiedMillis: Long? = null,
        systemZoneId: ZoneId = ZoneId.systemDefault()
    ): ResolvedTimestamp? {
        if (exif != null) {
            // 1. DateTimeOriginal
            val originalStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            if (!originalStr.isNullOrBlank()) {
                val originalOffset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_OFFSET_TIME)
                val ms = parseDateTime(originalStr, originalOffset, systemZoneId)
                if (ms != null) {
                    return ResolvedTimestamp(
                        timeMs = ms,
                        sourceTag = "DateTimeOriginal",
                        offsetString = originalOffset
                    )
                }
            }

            // 2. Earliest of DateTimeDigitized and DateTime
            val digitizedStr = exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
            val digitizedOffset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED)
                ?: exif.getAttribute(ExifInterface.TAG_OFFSET_TIME)
            val digitizedMs = if (!digitizedStr.isNullOrBlank()) {
                parseDateTime(digitizedStr, digitizedOffset, systemZoneId)
            } else null

            val modifiedStr = exif.getAttribute(ExifInterface.TAG_DATETIME)
            val modifiedOffset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME)
            val modifiedMs = if (!modifiedStr.isNullOrBlank()) {
                parseDateTime(modifiedStr, modifiedOffset, systemZoneId)
            } else null

            when {
                digitizedMs != null && modifiedMs != null -> {
                    return if (digitizedMs <= modifiedMs) {
                        ResolvedTimestamp(digitizedMs, "DateTimeDigitized", digitizedOffset)
                    } else {
                        ResolvedTimestamp(modifiedMs, "DateTime", modifiedOffset)
                    }
                }
                digitizedMs != null -> {
                    return ResolvedTimestamp(digitizedMs, "DateTimeDigitized", digitizedOffset)
                }
                modifiedMs != null -> {
                    return ResolvedTimestamp(modifiedMs, "DateTime", modifiedOffset)
                }
            }

            // 3. EXIF GPS DateStamp and TimeStamp
            val gpsMs = parseGpsDateTime(exif)
            if (gpsMs != null && gpsMs > 0L) {
                return ResolvedTimestamp(
                    timeMs = gpsMs,
                    sourceTag = "GPS_DateTime",
                    offsetString = "+00:00"
                )
            }
        }

        // 4. Filename-embedded timestamp (e.g. IMG_20230514_153022.jpg, WhatsApp, PXL, Screenshots)
        val nameToParse = displayName ?: filePath?.let { File(it).name }
        if (!nameToParse.isNullOrBlank()) {
            val fnMs = parseFilenameTimestamp(nameToParse, systemZoneId)
            if (fnMs != null && fnMs > 0L) {
                return ResolvedTimestamp(
                    timeMs = fnMs,
                    sourceTag = "FilenameTimestamp",
                    offsetString = null
                )
            }
        }

        // 5. Fallback provided by caller (e.g. MediaStore date_taken)
        if (fallbackMillis != null && fallbackMillis > 0L) {
            return ResolvedTimestamp(
                timeMs = fallbackMillis,
                sourceTag = "MediaStoreDate",
                offsetString = null
            )
        }

        // 6. Fallback to file system or metadata last modified timestamp
        if (filePath != null) {
            val f = File(filePath)
            if (f.exists() && f.lastModified() > 0L) {
                return ResolvedTimestamp(
                    timeMs = f.lastModified(),
                    sourceTag = "FileLastModified",
                    offsetString = null
                )
            }
        }

        if (fallbackLastModifiedMillis != null && fallbackLastModifiedMillis > 0L) {
            return ResolvedTimestamp(
                timeMs = fallbackLastModifiedMillis,
                sourceTag = "FileLastModified",
                offsetString = null
            )
        }

        return null
    }

    /**
     * Parses GPS DateStamp and TimeStamp from EXIF in UTC.
     */
    fun parseGpsDateTime(exif: ExifInterface): Long? {
        val directGpsMs = runCatching { exif.gpsDateTime }.getOrNull()
        if (directGpsMs != null && directGpsMs > 0L) {
            return directGpsMs
        }

        val dateStamp = exif.getAttribute(ExifInterface.TAG_GPS_DATESTAMP)
        val timeStamp = exif.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP)
        if (!dateStamp.isNullOrBlank() && !timeStamp.isNullOrBlank()) {
            try {
                val cleanDate = dateStamp.trim().replace('-', ':')
                val dParts = cleanDate.split(":")
                val tParts = timeStamp.trim().split(":", ",")
                if (dParts.size == 3 && tParts.size >= 3) {
                    val year = dParts[0].toInt()
                    val month = dParts[1].toInt()
                    val day = dParts[2].toInt()
                    val hour = parseGpsTimeComponent(tParts[0])
                    val minute = parseGpsTimeComponent(tParts[1])
                    val second = parseGpsTimeComponent(tParts[2])
                    val zdt = ZonedDateTime.of(year, month, day, hour, minute, second, 0, ZoneOffset.UTC)
                    return zdt.toInstant().toEpochMilli()
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun parseGpsTimeComponent(part: String): Int {
        val clean = part.trim()
        if (clean.contains('/')) {
            val fraction = clean.split('/')
            if (fraction.size == 2) {
                val num = fraction[0].toDoubleOrNull() ?: 0.0
                val den = fraction[1].toDoubleOrNull() ?: 1.0
                return (num / den).toInt().coerceIn(0, 59)
            }
        }
        return (clean.toDoubleOrNull() ?: 0.0).toInt().coerceIn(0, 59)
    }

    /**
     * Extracts capture timestamp embedded in filename strings.
     * Supports formats such as:
     * - IMG_20230514_153022.jpg
     * - PXL_20240102_123456789.jpg
     * - Screenshot_2023-08-15-14-20-33.png
     * - WhatsApp Image 2023-05-14 at 15.30.22.jpeg
     * - 2023-05-14 15.30.22.jpg / 20230514_153022.jpg
     * - IMG-20230514-WA0001.jpg (date only, midday default)
     * - Unix epoch milliseconds (13 digits) or seconds (10 digits)
     */
    fun parseFilenameTimestamp(
        filename: String,
        fallbackZoneId: ZoneId = ZoneId.systemDefault()
    ): Long? {
        val base = filename.substringBeforeLast('.')

        // 1. Date + Time pattern
        val dtMatch = DATE_TIME_FILENAME_REGEX.find(base)
        if (dtMatch != null) {
            val (y, m, d, hh, mm, ss) = dtMatch.destructured
            try {
                val ldt = LocalDateTime.of(y.toInt(), m.toInt(), d.toInt(), hh.toInt(), mm.toInt(), ss.toInt())
                return ldt.atZone(fallbackZoneId).toInstant().toEpochMilli()
            } catch (_: Exception) {}
        }

        // 2. Date only pattern
        val dMatch = DATE_ONLY_FILENAME_REGEX.find(base)
        if (dMatch != null) {
            val (y, m, d) = dMatch.destructured
            try {
                val ldt = LocalDateTime.of(y.toInt(), m.toInt(), d.toInt(), 12, 0, 0)
                return ldt.atZone(fallbackZoneId).toInstant().toEpochMilli()
            } catch (_: Exception) {}
        }

        // 3. Epoch millis (13 digits)
        val emMatch = EPOCH_MILLIS_REGEX.find(base)
        if (emMatch != null) {
            val ms = emMatch.groupValues[1].toLongOrNull()
            if (ms != null && ms > 0L) return ms
        }

        // 4. Epoch seconds (10 digits)
        val esMatch = EPOCH_SECONDS_REGEX.find(base)
        if (esMatch != null) {
            val sec = esMatch.groupValues[1].toLongOrNull()
            if (sec != null && sec > 0L) return sec * 1000L
        }

        return null
    }

    /**
     * Parses an EXIF date string with optional timezone offset (e.g. "+02:00" or "-07:00").
     */
    fun parseDateTime(
        dateStr: String,
        offsetStr: String? = null,
        fallbackZoneId: ZoneId = ZoneId.systemDefault()
    ): Long? {
        val clean = dateStr.trim()
        if (clean.isBlank()) return null

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
}
