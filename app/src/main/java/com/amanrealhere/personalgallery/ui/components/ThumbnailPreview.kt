package com.amanrealhere.personalgallery.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.amanrealhere.personalgallery.PersonalGalleryApplication
import com.amanrealhere.personalgallery.R
import java.io.File

@Composable
fun ThumbnailPreview(
    path: String?,
    modifier: Modifier = Modifier,
    showFolderPlaceholder: Boolean = true
) {
    val context = LocalContext.current
    val application = context.applicationContext as PersonalGalleryApplication

    val thumbnailFile = remember(path) {
        path?.takeIf(String::isNotBlank)?.let(::File)
    }
    // Thumbnail paths are generation-specific and immutable, so the path is
    // a stable cache key without synchronous filesystem metadata calls.
    val cacheKey = remember(thumbnailFile) {
        thumbnailFile?.absolutePath
    }
    val request = remember(thumbnailFile, cacheKey) {
        thumbnailFile?.let { file ->
            ImageRequest.Builder(context)
                .data(file)
                .memoryCacheKey(cacheKey)
                .diskCachePolicy(CachePolicy.DISABLED)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .crossfade(false)
                .build()
        }
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (showFolderPlaceholder) {
                Image(
                    painter = painterResource(R.drawable.ic_gallery_folder),
                    contentDescription = null,
                    modifier = Modifier.size(44.dp)
                )
            }

            if (request != null) {
                AsyncImage(
                    model = request,
                    imageLoader = application.imageLoader,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
    }
}
