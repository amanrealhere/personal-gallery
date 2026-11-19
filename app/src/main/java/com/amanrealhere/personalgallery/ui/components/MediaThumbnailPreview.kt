package com.amanrealhere.personalgallery.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
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
import com.amanrealhere.personalgallery.data.local.MediaItemEntity
import com.amanrealhere.personalgallery.data.thumbnail.MediaThumbnailCache
import java.io.File

@Composable
fun MediaThumbnailPreview(
    media: MediaItemEntity,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val application = context.applicationContext as PersonalGalleryApplication
    var targetSizePx by remember(media.id) { mutableIntStateOf(0) }
    val cachedFile = produceState<File?>(null, media.id, media.sourcePath, media.fileSizeBytes, media.modifiedAtEpochMillis, targetSizePx) {
        value = if (targetSizePx > 0) {
            runCatching {
                MediaThumbnailCache.getOrCreate(
                    context,
                    media.sourcePath,
                    media.fileSizeBytes,
                    media.modifiedAtEpochMillis,
                    targetSizePx
                )
            }.getOrNull()
        } else null
    }.value

    val request = cachedFile?.let { file ->
        ImageRequest.Builder(context)
            .data(file)
            .memoryCacheKey(file.absolutePath)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .crossfade(false)
            .build()
    }

    Surface(
        modifier = modifier.onSizeChanged { size ->
            targetSizePx = ((size.width + 63) / 64 * 64).coerceIn(128, 512)
        },
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (request == null) {
                Image(
                    painter = painterResource(R.drawable.ic_gallery_folder),
                    contentDescription = null,
                    modifier = Modifier.size(36.dp)
                )
            } else {
                AsyncImage(
                    model = request,
                    imageLoader = application.imageLoader,
                    contentDescription = media.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
    }
}
