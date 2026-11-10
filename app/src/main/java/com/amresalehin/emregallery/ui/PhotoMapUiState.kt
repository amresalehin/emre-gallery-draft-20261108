package com.amresalehin.emregallery.ui

import com.amresalehin.emregallery.data.MediaItem
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * State representing the Photo Map screen.
 */
data class PhotoMapUiState(
    val locatedMedia: List<Pair<MediaItem, GeoPoint>> = emptyList(),
    val loading: Boolean = true,
    val scanProgress: Int = 0,
    val scanTotal: Int = 0,
    val mapLayer: String = "Standard",
    val showMapLayerMenu: Boolean = false,
    val visibleBounds: BoundingBox? = null
) {
    /**
     * Photos currently within the active visible map viewport bounds.
     */
    val viewportMedia: List<Pair<MediaItem, GeoPoint>> by lazy {
        val bounds = visibleBounds ?: return@lazy locatedMedia
        locatedMedia.filter { (_, point) ->
            point.latitude <= bounds.latNorth && point.latitude >= bounds.latSouth &&
                if (bounds.lonWest <= bounds.lonEast) {
                    point.longitude >= bounds.lonWest && point.longitude <= bounds.lonEast
                } else {
                    point.longitude >= bounds.lonWest || point.longitude <= bounds.lonEast
                }
        }
    }

    /**
     * Viewport photos grouped by actual day and sorted chronologically descending.
     */
    val dayGroups: List<Pair<String, List<Pair<MediaItem, GeoPoint>>>> by lazy {
        if (viewportMedia.isEmpty()) return@lazy emptyList()

        val cal = Calendar.getInstance()
        val todayCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayStart = todayCal.timeInMillis
        val yesterdayStart = todayStart - (24 * 60 * 60 * 1000L)
        val currentYear = todayCal.get(Calendar.YEAR)

        val dayFormatSameYear = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
        val dayFormatOtherYear = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault())

        val groupedMap = LinkedHashMap<Long, MutableList<Pair<MediaItem, GeoPoint>>>()

        // Sort viewport media chronologically descending
        val sortedMedia = viewportMedia.sortedByDescending { (item, _) ->
            if (item.dateTakenMillis > 0) item.dateTakenMillis else (item.dateAddedSeconds * 1000L)
        }

        for (pair in sortedMedia) {
            val item = pair.first
            val timestamp = if (item.dateTakenMillis > 0) item.dateTakenMillis else (item.dateAddedSeconds * 1000L)
            val validTimestamp = if (timestamp > 0) timestamp else todayStart
            cal.timeInMillis = validTimestamp
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val dayStart = cal.timeInMillis

            groupedMap.getOrPut(dayStart) { mutableListOf() }.add(pair)
        }

        groupedMap.entries
            .sortedByDescending { it.key }
            .map { (dayStart, dayItems) ->
                val label = when (dayStart) {
                    todayStart -> "Today"
                    yesterdayStart -> "Yesterday"
                    else -> {
                        cal.timeInMillis = dayStart
                        if (cal.get(Calendar.YEAR) == currentYear) {
                            dayFormatSameYear.format(Date(dayStart))
                        } else {
                            dayFormatOtherYear.format(Date(dayStart))
                        }
                    }
                }
                label to dayItems.sortedByDescending { (item, _) ->
                    if (item.dateTakenMillis > 0) item.dateTakenMillis else (item.dateAddedSeconds * 1000L)
                }
            }
    }
}
