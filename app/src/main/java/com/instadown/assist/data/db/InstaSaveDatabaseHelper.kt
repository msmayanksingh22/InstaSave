package com.instadown.assist.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

class InstaSaveDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "instasave.db"
        const val DATABASE_VERSION = 2
        private const val TAG = "InstaSaveDB"

        // Downloads Table
        const val TABLE_DOWNLOADS = "downloads"
        const val COL_DL_ID = "id"
        const val COL_DL_TX_ID = "transaction_id"
        const val COL_DL_SHORTCODE = "shortcode"
        const val COL_DL_POST_TYPE = "post_type"
        const val COL_DL_SOURCE_URL = "source_url"
        const val COL_DL_CREATED_AT = "created_at"
        const val COL_DL_CAROUSEL_COUNT = "carousel_count"
        const val COL_DL_STATUS = "status"
        const val COL_DL_TOTAL_BYTES = "total_bytes"
        const val COL_DL_AUTHOR = "author_username"
        const val COL_DL_CAPTION = "caption"
        const val COL_DL_THUMBNAIL_PATH = "thumbnail_path"

        // Download Items Table
        const val TABLE_DOWNLOAD_ITEMS = "download_items"
        const val COL_ITEM_ID = "id"
        const val COL_ITEM_DOWNLOAD_ID = "download_id"
        const val COL_ITEM_INDEX = "item_index"
        const val COL_ITEM_MEDIA_TYPE = "media_type"
        const val COL_ITEM_MEDIA_URI = "media_uri"
        const val COL_ITEM_FILE_NAME = "file_name"
        const val COL_ITEM_WIDTH = "width"
        const val COL_ITEM_HEIGHT = "height"
        const val COL_ITEM_SIZE_BYTES = "size_bytes"
        const val COL_ITEM_STATUS = "status"
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // Explicitly enable foreign key cascade enforcement
        db.setForeignKeyConstraintsEnabled(true)
        Log.i(TAG, "onConfigure: SQLite foreign key enforcement enabled")
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        try {
            // Safely purge legacy AUTO_FAIL_* non-download rows without affecting legitimate records
            val rows = db.delete(TABLE_DOWNLOADS, "$COL_DL_TX_ID LIKE ?", arrayOf("AUTO_FAIL_%"))
            if (rows > 0) {
                Log.i(TAG, "onOpen: Safely cleaned up $rows legacy AUTO_FAIL technical records")
            }
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_downloads_status_created ON $TABLE_DOWNLOADS($COL_DL_STATUS, $COL_DL_CREATED_AT DESC);")
            try { db.execSQL("ALTER TABLE $TABLE_DOWNLOADS ADD COLUMN $COL_DL_AUTHOR TEXT;") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE $TABLE_DOWNLOADS ADD COLUMN $COL_DL_CAPTION TEXT;") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE $TABLE_DOWNLOADS ADD COLUMN $COL_DL_THUMBNAIL_PATH TEXT;") } catch (_: Exception) {}

            // Deduplicate downloads table by shortcode, keeping the latest / SUCCESS record
            val dupCursor = db.rawQuery(
                "SELECT $COL_DL_SHORTCODE, COUNT(*) as cnt FROM $TABLE_DOWNLOADS WHERE $COL_DL_SHORTCODE != '' GROUP BY $COL_DL_SHORTCODE HAVING cnt > 1",
                null
            )
            val duplicateShortcodes = mutableListOf<String>()
            dupCursor.use { c ->
                while (c.moveToNext()) {
                    duplicateShortcodes.add(c.getString(0))
                }
            }
            for (sc in duplicateShortcodes) {
                val rowsCursor = db.query(
                    TABLE_DOWNLOADS,
                    arrayOf(COL_DL_ID, COL_DL_STATUS),
                    "$COL_DL_SHORTCODE = ?",
                    arrayOf(sc),
                    null, null,
                    "CASE WHEN $COL_DL_STATUS = 'SUCCESS' THEN 1 ELSE 2 END, $COL_DL_ID DESC"
                )
                rowsCursor.use { rc ->
                    if (rc.moveToFirst()) {
                        // Skip the first (best/latest) record, delete all subsequent duplicate records
                        while (rc.moveToNext()) {
                            val deleteId = rc.getLong(0)
                            db.delete(TABLE_DOWNLOADS, "$COL_DL_ID = ?", arrayOf(deleteId.toString()))
                            Log.i(TAG, "onOpen: Cleaned up duplicate record $deleteId for shortcode $sc")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "onOpen: Migration/cleanup notice: ${e.message}")
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        Log.i(TAG, "onCreate: Creating InstaSave schema version $DATABASE_VERSION")

        val createDownloads = """
            CREATE TABLE $TABLE_DOWNLOADS (
                $COL_DL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_DL_TX_ID TEXT NOT NULL UNIQUE,
                $COL_DL_SHORTCODE TEXT NOT NULL,
                $COL_DL_POST_TYPE TEXT NOT NULL,
                $COL_DL_SOURCE_URL TEXT NOT NULL,
                $COL_DL_CREATED_AT INTEGER NOT NULL,
                $COL_DL_CAROUSEL_COUNT INTEGER NOT NULL DEFAULT 1,
                $COL_DL_STATUS TEXT NOT NULL,
                $COL_DL_TOTAL_BYTES INTEGER NOT NULL DEFAULT 0,
                $COL_DL_AUTHOR TEXT,
                $COL_DL_CAPTION TEXT,
                $COL_DL_THUMBNAIL_PATH TEXT
            );
        """.trimIndent()

        val createItems = """
            CREATE TABLE $TABLE_DOWNLOAD_ITEMS (
                $COL_ITEM_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_ITEM_DOWNLOAD_ID INTEGER NOT NULL REFERENCES $TABLE_DOWNLOADS($COL_DL_ID) ON DELETE CASCADE,
                $COL_ITEM_INDEX INTEGER NOT NULL,
                $COL_ITEM_MEDIA_TYPE TEXT NOT NULL,
                $COL_ITEM_MEDIA_URI TEXT,
                $COL_ITEM_FILE_NAME TEXT NOT NULL,
                $COL_ITEM_WIDTH INTEGER NOT NULL DEFAULT 0,
                $COL_ITEM_HEIGHT INTEGER NOT NULL DEFAULT 0,
                $COL_ITEM_SIZE_BYTES INTEGER NOT NULL DEFAULT 0,
                $COL_ITEM_STATUS TEXT NOT NULL,
                UNIQUE($COL_ITEM_DOWNLOAD_ID, $COL_ITEM_INDEX)
            );
        """.trimIndent()

        val indexCreated = "CREATE INDEX idx_downloads_created ON $TABLE_DOWNLOADS($COL_DL_CREATED_AT DESC);"
        val indexShortcode = "CREATE INDEX idx_downloads_shortcode_created ON $TABLE_DOWNLOADS($COL_DL_SHORTCODE, $COL_DL_CREATED_AT DESC);"
        val indexItems = "CREATE INDEX idx_items_download ON $TABLE_DOWNLOAD_ITEMS($COL_ITEM_DOWNLOAD_ID);"

        db.execSQL(createDownloads)
        db.execSQL(createItems)
        db.execSQL(indexCreated)
        db.execSQL(indexShortcode)
        db.execSQL(indexItems)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.i(TAG, "onUpgrade: from $oldVersion to $newVersion")
        if (oldVersion < 2) {
            try { db.execSQL("ALTER TABLE $TABLE_DOWNLOADS ADD COLUMN $COL_DL_AUTHOR TEXT;") } catch (e: Exception) { Log.w(TAG, "onUpgrade author: ${e.message}") }
            try { db.execSQL("ALTER TABLE $TABLE_DOWNLOADS ADD COLUMN $COL_DL_CAPTION TEXT;") } catch (e: Exception) { Log.w(TAG, "onUpgrade caption: ${e.message}") }
            try { db.execSQL("ALTER TABLE $TABLE_DOWNLOADS ADD COLUMN $COL_DL_THUMBNAIL_PATH TEXT;") } catch (e: Exception) { Log.w(TAG, "onUpgrade thumbnail: ${e.message}") }
        }
    }
}
