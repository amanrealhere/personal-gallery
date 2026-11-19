package com.amanrealhere.personalgallery.ui.components

import android.widget.ImageView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.amanrealhere.personalgallery.R
import com.amanrealhere.personalgallery.data.local.MediaItemEntity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import java.io.File

/**
 * Grid media thumbnails are loaded directly from the original file by Glide.
 *
 * Glide owns decoding, memory caching, transformed-resource disk caching and
 * request lifecycle. This intentionally avoids generating and validating a
 * separate app-managed thumbnail JPEG every time a LazyGrid cell re-enters
 * composition.
 */
@Composable
fun MediaThumbnailPreview(
    media: MediaItemEntity,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val source = remember(media.sourcePath) { File(media.sourcePath) }
    val signature = remember(
        media.sourceCanonicalPath,
        media.fileSizeBytes,
        media.modifiedAtEpochMillis
    ) {
        ObjectKey(
            "${media.sourceCanonicalPath}|${media.fileSizeBytes}|" +
                "${media.modifiedAtEpochMillis}|grid-center-crop-v1"
        )
    }
    val requestKey = remember(source, signature) {
        "${source.absolutePath}|${signature.hashCode()}"
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                ImageView(viewContext).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            },
            update = { imageView ->
                if (imageView.tag != requestKey) {
                    imageView.tag = requestKey
                    Glide.with(imageView)
                        .load(source)
                        .signature(signature)
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .centerCrop()
                        .placeholder(R.drawable.ic_gallery_folder)
                        .error(R.drawable.ic_gallery_folder)
                        .dontAnimate()
                        .into(imageView)
                }
            },
            onRelease = { imageView ->
                Glide.with(imageView).clear(imageView)
                imageView.setImageDrawable(null)
                imageView.tag = null
            }
        )
    }
}
