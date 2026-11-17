package com.amanrealhere.personalgallery.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [AlbumEntity::class],
    version = 1,
    exportSchema = true
)
abstract class GalleryDatabase : RoomDatabase() {

    abstract fun albumDao(): AlbumDao

    companion object {

        @Volatile
        private var INSTANCE: GalleryDatabase? = null

        fun getInstance(context: Context): GalleryDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    GalleryDatabase::class.java,
                    "personal-gallery.db"
                ).build().also {
                    INSTANCE = it
                }
            }
        }
    }
}
