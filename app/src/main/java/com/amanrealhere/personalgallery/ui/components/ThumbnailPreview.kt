package com.amanrealhere.personalgallery.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import com.amanrealhere.personalgallery.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ThumbnailPreview(
    path: String?,
    modifier: Modifier = Modifier,
    showFolderPlaceholder: Boolean = true
) {
    val bitmapState = produceState<Bitmap?>(initialValue = null, path) {
        value = if (path.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            val file = File(path)
            if (file.isFile) BitmapFactory.decodeFile(file.absolutePath) else null
        }
    }

    DisposableEffect(bitmapState.value) {
        val current = bitmapState.value
        onDispose { current?.recycle() }
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = bitmapState.value
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else if (showFolderPlaceholder) {
                Image(
                    painter = painterResource(R.drawable.ic_gallery_folder),
                    contentDescription = null,
                    modifier = Modifier.size(44.dp)
                )
            }
        }
    }
}
