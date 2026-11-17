package com.amanrealhere.personalgallery.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "thumbnail_generations")
data class ThumbnailGenerationEntity(
    @PrimaryKey val id: String,
    val albumId: String?,
    val createdAtEpochMillis: Long,
    val completedAtEpochMillis: Long?,
    val status: String,
    val totalCount: Int,
    val processedCount: Int,
    val successCount: Int,
    val failureCount: Int
)

@Entity(
    tableName = "thumbnail_records",
    indices = [
        Index(value = ["generationId", "sourcePath", "kind"], unique = true),
        Index(value = ["albumId", "generationId"])
    ]
)
data class ThumbnailRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val generationId: String,
    val albumId: String,
    val sourcePath: String,
    val sourceName: String,
    val sourceSizeBytes: Long,
    val sourceModifiedAtEpochMillis: Long,
    val sourceCanonicalPath: String,
    val kind: String,
    val outputPath: String,
    val status: String,
    val errorMessage: String? = null
)

@Entity(tableName = "album_thumbnail_state")
data class AlbumThumbnailStateEntity(
    @PrimaryKey val albumId: String,
    val activeGenerationId: String?,
    val coverThumbnailPath: String?,
    val imageCount: Int,
    val latestImageModifiedAtEpochMillis: Long?
)
