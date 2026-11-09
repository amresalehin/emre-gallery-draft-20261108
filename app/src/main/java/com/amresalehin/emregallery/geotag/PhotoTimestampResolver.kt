package com.amresalehin.emregallery.geotag

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
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
 * with strict tag priority and paired timezone offset handling to avoid hour shifts.
 */
object PhotoTimestampResolver {

    private val EXIF_DATE_FORMATTERS = listOf(
        DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US),
        DateTimeFormatter.ofPattern("yyyy:MM:dd'T'HH:mm:ss", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.US),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.US)
    )

    /**
     * Resolves the capture timestamp from an [ExifInterface] instance, falling back
     * to the file's last modified timestamp if no valid EXIF date is present.
     *
     * Priority chain:
     * 1. DateTimeOriginal paired with OffsetTimeOriginal (or OffsetTime)
     * 2. Earliest of (DateTimeDigitized paired with OffsetTimeDigitized/OffsetTime, DateTime paired with OffsetTime)
     * 3. File system last modified timestamp (if file exists)
     * 4. Optional fallback timestamp provided by caller
     */
    fun resolve(
        exif: ExifInterface?,
        filePath: String? = null,
        fallbackMillis: Long? = null,
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
        }

        // 3. Fallback to file's last modified timestamp
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

        // 4. Fallback provided by caller (e.g. MediaStore date_added / date_taken)
        if (fallbackMillis != null && fallbackMillis > 0L) {
            return ResolvedTimestamp(
                timeMs = fallbackMillis,
                sourceTag = "MediaStoreDate",
                offsetString = null
            )
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
