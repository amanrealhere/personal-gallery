package com.amanrealhere.personalgallery.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.amanrealhere.personalgallery.ui.theme.GalleryTheme

@Composable
fun PersonalGalleryApp() {
    GalleryTheme {
        val galleryViewModel: GalleryViewModel = viewModel()

        GalleryShell(
            viewModel = galleryViewModel
        )
    }
}
