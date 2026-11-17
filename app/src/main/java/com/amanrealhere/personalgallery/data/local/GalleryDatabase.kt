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
        AlbumThumbnailStateEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class GalleryDatabase : RoomDatabase() {

    abstract fun albumDao(): AlbumDao
    abstract fun thumbnailDao(): ThumbnailDao

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
            ).addMigrations(MIGRATION_1_2).build()
        }

        private fun migratePrivateDatabaseIfNeeded(context: Context, destination: File) {
            val privateDatabase = context.getDatabasePath("personal-gallery.db")
            if (destination.exists() || !privateDatabase.exists()) return

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
                if (temporary.exists()) temporary.delete()

                val migrated = Room.databaseBuilder(
                    context,
                    GalleryDatabase::class.java,
                    temporary.absolutePath
                ).addMigrations(MIGRATION_1_2).build()

                try {
                    runBlocking {
                        withContext(Dispatchers.IO) {
                            migrated.albumDao().insertAllIgnoringConflicts(albums)
                        }
                    }
                } finally {
                    migrated.close()
                }

                check(temporary.renameTo(destination)) {
                    "Could not move migrated database into Pictures/.personal-gallery/database."
                }
            } finally {
                legacy.close()
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
