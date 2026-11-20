package com.amanrealhere.personalgallery.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Query
import com.amanrealhere.personalgallery.data.storage.GalleryStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

@Database(
    entities = [
        AlbumEntity::class,
        ThumbnailGenerationEntity::class,
        ThumbnailRecordEntity::class,
        AlbumThumbnailStateEntity::class,
        MediaItemEntity::class,
        MediaItemStagingEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class GalleryDatabase : RoomDatabase() {

    abstract fun albumDao(): AlbumDao
    abstract fun thumbnailDao(): ThumbnailDao
    abstract fun mediaItemDao(): MediaItemDao

    companion object {
        @Volatile private var INSTANCE: GalleryDatabase? = null

        fun getInstance(context: Context): GalleryDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: openSharedDatabase(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }

        private fun openSharedDatabase(context: Context): GalleryDatabase {
            val storage = GalleryStorage.getInstance(context)
            val databaseFile = storage.databaseFile()
            migratePrivateDatabaseIfNeeded(context, databaseFile)

            return Room.databaseBuilder(
                context,
                GalleryDatabase::class.java,
                databaseFile.absolutePath
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
        }

        private fun migratePrivateDatabaseIfNeeded(context: Context, destination: File) {
            val privateDatabase = context.getDatabasePath("personal-gallery.db")
            if (destination.exists() && destination.length() > 0L) return
            if (destination.exists()) deleteSqliteArtifacts(destination)
            if (!privateDatabase.exists()) return

            val legacy = Room.databaseBuilder(
                context,
                LegacyGalleryDatabase::class.java,
                "personal-gallery.db"
            ).build()

            try {
                val albums = runBlocking {
                    withContext(Dispatchers.IO) {
                        legacy.albumDao().getAllOnce()
                    }
                }

                val temporary = File(destination.parentFile, "personal-gallery.db.migrating")
                deleteSqliteArtifacts(temporary)

                val migrated = Room.databaseBuilder(
                    context,
                    GalleryDatabase::class.java,
                    temporary.absolutePath
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()

                try {
                    runBlocking {
                        withContext(Dispatchers.IO) {
                            migrated.albumDao().insertAllIgnoringConflicts(albums)
                        }
                    }
                } finally {
                    migrated.close()
                }

                check(temporary.isFile && temporary.length() > 0L) {
                    "The migrated database file was not created."
                }
                check(temporary.renameTo(destination)) {
                    "Could not move migrated database into Pictures/.personal-gallery/database."
                }
            } finally {
                legacy.close()
            }
        }

        private fun deleteSqliteArtifacts(databaseFile: File) {
            listOf(
                databaseFile,
                File(databaseFile.absolutePath + "-wal"),
                File(databaseFile.absolutePath + "-shm"),
                File(databaseFile.absolutePath + "-journal")
            ).forEach { artifact ->
                if (artifact.exists()) {
                    check(artifact.delete()) {
                        "Could not remove an incomplete database migration file: $artifact"
                    }
                }
            }
        }
    }
}

@Database(entities = [AlbumEntity::class], version = 1, exportSchema = false)
abstract class LegacyGalleryDatabase : RoomDatabase() {
    abstract fun albumDao(): LegacyAlbumDao
}

@androidx.room.Dao
interface LegacyAlbumDao {
    @Query("SELECT * FROM albums")
    suspend fun getAllOnce(): List<AlbumEntity>
}

private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS thumbnail_generations (
                id TEXT NOT NULL PRIMARY KEY,
                albumId TEXT,
                createdAtEpochMillis INTEGER NOT NULL,
                completedAtEpochMillis INTEGER,
                status TEXT NOT NULL,
                totalCount INTEGER NOT NULL,
                processedCount INTEGER NOT NULL,
                successCount INTEGER NOT NULL,
                failureCount INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS thumbnail_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                generationId TEXT NOT NULL,
                albumId TEXT NOT NULL,
                sourcePath TEXT NOT NULL,
                sourceName TEXT NOT NULL,
                sourceSizeBytes INTEGER NOT NULL,
                sourceModifiedAtEpochMillis INTEGER NOT NULL,
                sourceCanonicalPath TEXT NOT NULL,
                kind TEXT NOT NULL,
                outputPath TEXT NOT NULL,
                status TEXT NOT NULL,
                errorMessage TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_thumbnail_records_generationId_sourcePath_kind ON thumbnail_records(generationId, sourcePath, kind)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_thumbnail_records_albumId_generationId ON thumbnail_records(albumId, generationId)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS album_thumbnail_state (
                albumId TEXT NOT NULL PRIMARY KEY,
                activeGenerationId TEXT,
                coverThumbnailPath TEXT,
                imageCount INTEGER NOT NULL,
                latestImageModifiedAtEpochMillis INTEGER
            )
        """.trimIndent())
    }
}

private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS media_items (
                id TEXT NOT NULL PRIMARY KEY,
                albumId TEXT NOT NULL,
                sourcePath TEXT NOT NULL,
                sourceCanonicalPath TEXT NOT NULL,
                displayName TEXT NOT NULL,
                fileSizeBytes INTEGER NOT NULL,
                modifiedAtEpochMillis INTEGER NOT NULL,
                lastSeenScanToken TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE INDEX IF NOT EXISTS index_media_items_albumId_modifiedAtEpochMillis_sourcePath
            ON media_items(albumId, modifiedAtEpochMillis, sourcePath)
        """.trimIndent())
    }
}
private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS media_items_new (
                id TEXT NOT NULL PRIMARY KEY, albumId TEXT NOT NULL, sourcePath TEXT NOT NULL,
                sourceCanonicalPath TEXT NOT NULL, displayName TEXT NOT NULL,
                fileSizeBytes INTEGER NOT NULL, modifiedAtEpochMillis INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO media_items_new (id, albumId, sourcePath, sourceCanonicalPath, displayName, fileSizeBytes, modifiedAtEpochMillis)
            SELECT id, albumId, sourcePath, sourceCanonicalPath, displayName, fileSizeBytes, modifiedAtEpochMillis
            FROM media_items
        """.trimIndent())
        db.execSQL("DROP TABLE media_items")
        db.execSQL("ALTER TABLE media_items_new RENAME TO media_items")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_albumId_modifiedAtEpochMillis_sourcePath ON media_items(albumId, modifiedAtEpochMillis, sourcePath)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS media_item_staging (
                scanToken TEXT NOT NULL, id TEXT NOT NULL, albumId TEXT NOT NULL, sourcePath TEXT NOT NULL,
                sourceCanonicalPath TEXT NOT NULL, displayName TEXT NOT NULL,
                fileSizeBytes INTEGER NOT NULL, modifiedAtEpochMillis INTEGER NOT NULL,
                PRIMARY KEY(scanToken, id)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_media_item_staging_scanToken_albumId ON media_item_staging(scanToken, albumId)")
    }
}

