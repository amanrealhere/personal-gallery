package com.amanrealhere.personalgallery.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "albums",
    indices = [
        Index(value = ["canonicalPath"], unique = true)
    ]
)
data class AlbumEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    val name: String,

    val canonicalPath: String,

    val registeredAtEpochMillis: Long
)
