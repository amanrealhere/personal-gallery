package com.amanrealhere.personalgallery

import android.app.Application
import com.amanrealhere.personalgallery.data.local.GalleryDatabase
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.load.engine.cache.LruBitmapPool
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator
import com.bumptech.glide.load.engine.cache.DiskLruCacheFactory

class PersonalGalleryApplication : Application() {

    val database: GalleryDatabase by lazy {
        GalleryDatabase.getInstance(this)
    }

    override fun onCreate() {
        super.onCreate()
        configureGlide()
    }

    private fun configureGlide() {
        val calculator = MemorySizeCalculator.Builder(this).build()
        Glide.init(
            this,
            GlideBuilder()
                .setMemoryCache(LruResourceCache(calculator.memoryCacheSize))
                .setBitmapPool(LruBitmapPool(calculator.bitmapPoolSize))
                .setDiskCache(
                    DiskLruCacheFactory(
                        { cacheDir.absolutePath + "/glide-media-cache" },
                        250L * 1024L * 1024L
                    )
                )
        )
    }
}
