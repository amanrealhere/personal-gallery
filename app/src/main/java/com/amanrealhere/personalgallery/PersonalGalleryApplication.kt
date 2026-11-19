package com.amanrealhere.personalgallery

import android.app.Application
import java.io.File
import com.amanrealhere.personalgallery.data.local.GalleryDatabase
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool
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
                .setMemoryCache(LruResourceCache(calculator.memoryCacheSize.toLong()))
                .setBitmapPool(LruBitmapPool(calculator.bitmapPoolSize.toLong()))
                .setDiskCache(
                    DiskLruCacheFactory(
                        { File(cacheDir, "glide-media-cache") },
                        250L * 1024L * 1024L
                    )
                )
        )
    }
}
