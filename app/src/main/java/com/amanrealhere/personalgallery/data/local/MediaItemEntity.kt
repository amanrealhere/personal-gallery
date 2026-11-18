package com.amanrealhere.personalgallery.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "media_items",
    indices = [
        Index(value = ["albumId", "modifiedAtEpochMillis", "sourcePath"])
    ]
)
data class MediaItemEntity(
    @PrimaryKey val id: String,
    val albumId: String,
    val sourcePath: String,
    val sourceCanonicalPath: String,
    val displayName: String,
    val fileSizeBytes: Long,
    val modifiedAtEpochMillis: Long,
    val lastSeenScanToken: String
)
