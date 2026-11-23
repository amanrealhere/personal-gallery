package com.amanrealhere.personalgallery.data.local

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface MediaItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStaging(items: List<MediaItemStagingEntity>)

    @Query("DELETE FROM media_item_staging WHERE scanToken = :scanToken")
    suspend fun clearStaging(scanToken: String)

    @Query("""
        INSERT OR REPLACE INTO media_items (
            id, albumId, sourcePath, sourceCanonicalPath, displayName, fileSizeBytes, modifiedAtEpochMillis
        )
        SELECT s.id, s.albumId, s.sourcePath, s.sourceCanonicalPath, s.displayName, s.fileSizeBytes, s.modifiedAtEpochMillis
        FROM media_item_staging s
        WHERE s.albumId = :albumId AND s.scanToken = :scanToken
          AND NOT EXISTS (
              SELECT 1 FROM media_items m WHERE m.id = s.id
                AND m.sourcePath = s.sourcePath AND m.sourceCanonicalPath = s.sourceCanonicalPath
                AND m.displayName = s.displayName AND m.fileSizeBytes = s.fileSizeBytes
                AND m.modifiedAtEpochMillis = s.modifiedAtEpochMillis
          )
    """)
    suspend fun applyChangedStagedItems(albumId: String, scanToken: String)

    @Query("""
        DELETE FROM media_items
        WHERE albumId = :albumId
          AND id NOT IN (
              SELECT id FROM media_item_staging
              WHERE albumId = :albumId AND scanToken = :scanToken
          )
    """)
    suspend fun deleteMissingFromStagedScan(albumId: String, scanToken: String)

    @Transaction
    suspend fun finishStagedScan(albumId: String, scanToken: String, complete: Boolean) {
        applyChangedStagedItems(albumId, scanToken)
        if (complete) deleteMissingFromStagedScan(albumId, scanToken)
        clearStaging(scanToken)
    }

    @Query("""
        SELECT * FROM media_items WHERE albumId = :albumId
        ORDER BY modifiedAtEpochMillis DESC, sourcePath ASC
    """)
    fun pagingSource(albumId: String): PagingSource<Int, MediaItemEntity>

    @Query("""
        SELECT * FROM media_items WHERE albumId = :albumId
        ORDER BY modifiedAtEpochMillis DESC, sourcePath ASC
    """)
    suspend fun itemsForAlbum(albumId: String): List<MediaItemEntity>

    @Query("SELECT COUNT(*) FROM media_items WHERE albumId = :albumId")
    suspend fun countForAlbum(albumId: String): Int

    @Query("""
        SELECT * FROM media_items WHERE albumId = :albumId
        ORDER BY modifiedAtEpochMillis DESC, sourcePath ASC LIMIT 1
    """)
    suspend fun latestItemForAlbum(albumId: String): MediaItemEntity?

    @Query("SELECT COUNT(*) FROM media_items WHERE albumId = :albumId")
    fun observeCountForAlbum(albumId: String): kotlinx.coroutines.flow.Flow<Int>
}
