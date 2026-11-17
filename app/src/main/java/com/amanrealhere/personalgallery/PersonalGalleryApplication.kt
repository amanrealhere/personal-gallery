package com.amanrealhere.personalgallery

import android.app.Application
import com.amanrealhere.personalgallery.data.local.GalleryDatabase

class PersonalGalleryApplication : Application() {

    val database: GalleryDatabase by lazy {
        GalleryDatabase.getInstance(this)
    }
}
