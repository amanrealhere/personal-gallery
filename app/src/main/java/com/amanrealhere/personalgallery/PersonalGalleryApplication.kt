package com.amanrealhere.personalgallery

import android.app.Application
import coil3.ImageLoader
import coil3.memory.MemoryCache
import com.amanrealhere.personalgallery.data.local.GalleryDatabase

class PersonalGalleryApplication : Application() {

    val database: GalleryDatabase by lazy {
        GalleryDatabase.getInstance(this)
    }

    /**
     * One process-wide loader keeps decoded thumbnails reusable while grid
     * items enter and leave the Compose composition.
     *
     * The thumbnail JPEGs already form our persistent disk cache, so Coil's
     * second disk cache is deliberately disabled.
     */
    val imageLoader: ImageLoader by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(this@PersonalGalleryApplication, 0.15)
                    .build()
            }
            .diskCache(null)
            .build()
    }
}
