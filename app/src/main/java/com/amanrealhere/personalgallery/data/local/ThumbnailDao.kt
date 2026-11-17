package com.amanrealhere.personalgallery.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ThumbnailDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGeneration(generation: ThumbnailGenerationEntity)

    @Query("SELECT * FROM thumbnail_generations WHERE id = :id LIMIT 1")
    suspend fun generation(id: String): ThumbnailGenerationEntity?

    @Query("SELECT * FROM thumbnail_generations ORDER BY createdAtEpochMillis DESC")
    fun observeGenerations(): Flow<List<ThumbnailGenerationEntity>>

    @Query("UPDATE thumbnail_generations SET totalCount = :total WHERE id = :id")
    suspend fun setTotal(id: String, total: Int)

    @Query("""
        UPDATE thumbnail_generations
        SET processedCount = :processed, successCount = :success, failureCount = :failed
        WHERE id = :id
    """)
    suspend fun updateProgress(id: String, processed: Int, success: Int, failed: Int)

    @Query("""
        UPDATE thumbnail_generations
        SET status = :status, completedAtEpochMillis = :completedAt
        WHERE id = :id
    """)
    suspend fun finishGeneration(id: String, status: String, completedAt: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecords(records: List<ThumbnailRecordEntity>)

    @Query("""
        SELECT * FROM thumbnail_records
        WHERE generationId = :generationId AND albumId = :albumId
    """)
    suspend fun records(generationId: String, albumId: String): List<ThumbnailRecordEntity>

    @Query("""
        SELECT * FROM thumbnail_records
        WHERE generationId = :generationId AND albumId = :albumId
          AND sourcePath = :sourcePath AND kind = :kind
        LIMIT 1
    """)
    suspend fun findRecord(
        generationId: String,
        albumId: String,
        sourcePath: String,
        kind: String
    ): ThumbnailRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setAlbumState(state: AlbumThumbnailStateEntity)

    @Query("SELECT * FROM album_thumbnail_state WHERE albumId = :albumId LIMIT 1")
    suspend fun albumState(albumId: String): AlbumThumbnailStateEntity?

    @Query("SELECT * FROM album_thumbnail_state")
    fun observeAlbumStates(): Flow<List<AlbumThumbnailStateEntity>>

    @Query("SELECT * FROM album_thumbnail_state WHERE albumId = :albumId LIMIT 1")
    fun observeAlbumState(albumId: String): Flow<AlbumThumbnailStateEntity?>

    @Query("DELETE FROM thumbnail_records WHERE generationId = :generationId")
    suspend fun deleteRecordsForGeneration(generationId: String)
}
