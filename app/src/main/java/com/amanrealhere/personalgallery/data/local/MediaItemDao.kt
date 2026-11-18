package com.amanrealhere.personalgallery.data.local

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MediaItemDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(items: List<MediaItemEntity>)

    @Query("""
        SELECT * FROM media_items
        WHERE albumId = :albumId
        ORDER BY modifiedAtEpochMillis DESC, sourcePath ASC
    """)
    fun pagingSource(albumId: String): PagingSource<Int, MediaItemEntity>

    @Query("""
        DELETE FROM media_items
        WHERE albumId = :albumId AND lastSeenScanToken != :scanToken
    """)
    suspend fun deleteNotSeenInScan(albumId: String, scanToken: String)

    @Query("DELETE FROM media_items WHERE albumId = :albumId")
    suspend fun deleteAlbumItems(albumId: String)

    @Query("""
        SELECT * FROM media_items
        WHERE albumId = :albumId
        ORDER BY modifiedAtEpochMillis DESC, sourcePath ASC
    """)
    suspend fun itemsForAlbum(albumId: String): List<MediaItemEntity>

    @Query("SELECT COUNT(*) FROM media_items WHERE albumId = :albumId")
    suspend fun countForAlbum(albumId: String): Int
}
