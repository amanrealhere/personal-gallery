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
     * One process-wide loader keeps decoded cache thumbnails reusable while grid
     * items enter and leave the Compose composition. The app-managed JPEG cache
     * is the durable thumbnail layer; Coil's second disk cache is disabled.
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
