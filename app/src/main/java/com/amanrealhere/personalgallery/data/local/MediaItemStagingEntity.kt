package com.amanrealhere.personalgallery.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Temporary scan snapshot. Staging writes don't invalidate the live gallery PagingSource.
 */
@Entity(
    tableName = "media_item_staging",
    primaryKeys = ["scanToken", "id"],
    indices = [Index(value = ["scanToken", "albumId"])]
)
data class MediaItemStagingEntity(
    val scanToken: String,
    val id: String,
    val albumId: String,
    val sourcePath: String,
    val sourceCanonicalPath: String,
    val displayName: String,
    val fileSizeBytes: Long,
    val modifiedAtEpochMillis: Long
)
