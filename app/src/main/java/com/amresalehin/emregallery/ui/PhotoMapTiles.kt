package com.amresalehin.emregallery.ui

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

/**
 * Custom tile source for ESRI World Imagery satellite tiles.
 */
object PhotoMapTiles {

    fun satelliteTileSource(): OnlineTileSourceBase = object : OnlineTileSourceBase(
        "EsriWorldImagery",
        1,
        19,
        256,
        ".jpg",
        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
        "Esri World Imagery"
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String =
            baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" +
                MapTileIndex.getY(pMapTileIndex) + "/" +
                MapTileIndex.getX(pMapTileIndex) + imageFilenameEnding()
    }
}
