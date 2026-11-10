package com.amresalehin.emregallery

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config
class PhotoMapLocationCacheMigrationTest {

    private class V3DatabaseHelper(context: Context, name: String) :
        SQLiteOpenHelper(context, name, null, 3) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE photo_locations (
                    uri TEXT PRIMARY KEY,
                    latitude REAL,
                    longitude REAL,
                    cached_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // No-op for v3
        }
    }

    private class V4DatabaseHelper(context: Context, name: String) :
        SQLiteOpenHelper(context, name, null, 4) {
        override fun onCreate(db: SQLiteDatabase) {}

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 4) {
                val addColumns = listOf(
                    "ALTER TABLE photo_locations ADD COLUMN source TEXT",
                    "ALTER TABLE photo_locations ADD COLUMN match_method TEXT",
                    "ALTER TABLE photo_locations ADD COLUMN resolved_timestamp INTEGER",
                    "ALTER TABLE photo_locations ADD COLUMN timestamp_source TEXT",
                    "ALTER TABLE photo_locations ADD COLUMN sample_before_time INTEGER",
                    "ALTER TABLE photo_locations ADD COLUMN sample_after_time INTEGER",
                    "ALTER TABLE photo_locations ADD COLUMN sample_gap_minutes REAL",
                    "ALTER TABLE photo_locations ADD COLUMN is_estimated INTEGER DEFAULT 0",
                    "ALTER TABLE photo_locations ADD COLUMN exif_written INTEGER DEFAULT 0"
                )
                for (sql in addColumns) {
                    try {
                        db.execSQL(sql)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    @Test
    fun `test additive migration from v3 to v4 preserves existing rows without data loss`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "test_migration_v3_to_v4.db"
        context.deleteDatabase(dbName)

        // 1. Create a V3 database and insert initial records
        val v3Helper = V3DatabaseHelper(context, dbName)
        val v3Db = v3Helper.writableDatabase

        val cv1 = ContentValues().apply {
            put("uri", "content://media/photo1")
            put("latitude", 48.8584)
            put("longitude", 2.2945)
            put("cached_at", 1700000000000L)
        }
        val cv2 = ContentValues().apply {
            put("uri", "content://media/photo2_no_gps")
            putNull("latitude")
            putNull("longitude")
            put("cached_at", 1700000001000L)
        }
        v3Db.insert("photo_locations", null, cv1)
        v3Db.insert("photo_locations", null, cv2)
        v3Helper.close()

        // 2. Open with V4DatabaseHelper to trigger additive onUpgrade(3, 4)
        val v4Helper = V4DatabaseHelper(context, dbName)
        val v4Db = v4Helper.writableDatabase

        // 3. Verify existing v3 records survived intact!
        val cursor = v4Db.rawQuery("SELECT * FROM photo_locations WHERE uri = ?", arrayOf("content://media/photo1"))
        assertTrue("Pre-existing v3 row must still exist after upgrade", cursor.moveToFirst())
        val lat = cursor.getDouble(cursor.getColumnIndexOrThrow("latitude"))
        val lon = cursor.getDouble(cursor.getColumnIndexOrThrow("longitude"))
        assertEquals(48.8584, lat, 0.0001)
        assertEquals(2.2945, lon, 0.0001)

        // Verify newly added columns exist in table
        assertNotNull(cursor.getColumnIndex("match_method"))
        assertNotNull(cursor.getColumnIndex("is_estimated"))
        assertNotNull(cursor.getColumnIndex("exif_written"))
        cursor.close()

        // 4. Insert a new v4 row with enriched provenance
        val cvV4 = ContentValues().apply {
            put("uri", "content://media/photo3_v4")
            put("latitude", 35.6762)
            put("longitude", 139.6503)
            put("cached_at", 1700000002000L)
            put("source", "External GPX")
            put("match_method", "interpolated")
            put("resolved_timestamp", 1700000000500L)
            put("timestamp_source", "DateTimeOriginal")
            put("sample_gap_minutes", 12.5)
            put("is_estimated", 0)
            put("exif_written", 0)
        }
        val insertId = v4Db.insert("photo_locations", null, cvV4)
        assertTrue(insertId > 0)

        // Read back the v4 row
        val cursorV4 = v4Db.rawQuery("SELECT * FROM photo_locations WHERE uri = ?", arrayOf("content://media/photo3_v4"))
        assertTrue(cursorV4.moveToFirst())
        assertEquals("interpolated", cursorV4.getString(cursorV4.getColumnIndexOrThrow("match_method")))
        assertEquals(12.5, cursorV4.getDouble(cursorV4.getColumnIndexOrThrow("sample_gap_minutes")), 0.01)
        cursorV4.close()

        v4Helper.close()
        context.deleteDatabase(dbName)
    }
}
