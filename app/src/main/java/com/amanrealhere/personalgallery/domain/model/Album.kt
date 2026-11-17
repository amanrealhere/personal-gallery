package com.amanrealhere.personalgallery.domain.model

data class Album(
    val id: String,
    val name: String,
    val canonicalPath: String,
    val registeredAtEpochMillis: Long
)
