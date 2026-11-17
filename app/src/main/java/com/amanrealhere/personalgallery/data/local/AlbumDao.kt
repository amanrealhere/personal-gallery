package com.amanrealhere.personalgallery.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {
    @Query("""
        SELECT * FROM albums
        ORDER BY name COLLATE NOCASE ASC, registeredAtEpochMillis ASC
    """)
    fun observeAll(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE canonicalPath = :canonicalPath LIMIT 1")
    suspend fun findByCanonicalPath(canonicalPath: String): AlbumEntity?

    @Query("SELECT * FROM albums WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): AlbumEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(album: AlbumEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoringConflicts(albums: List<AlbumEntity>)
}
