package com.amanrealhere.personalgallery.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.amanrealhere.personalgallery.ui.theme.GalleryTheme
import com.amanrealhere.personalgallery.ui.screens.storageaccess.StorageAccessScreen

@Composable
fun PersonalGalleryApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasAllFilesAccess by remember {
        mutableStateOf(Environment.isExternalStorageManager())
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        hasAllFilesAccess = Environment.isExternalStorageManager()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAllFilesAccess = Environment.isExternalStorageManager()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    GalleryTheme {
        if (hasAllFilesAccess) {
            val galleryViewModel: GalleryViewModel = viewModel()
            GalleryShell(viewModel = galleryViewModel)
        } else {
            StorageAccessScreen(
                onGrantAccess = {
                    val appIntent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                    ).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    try {
                        settingsLauncher.launch(appIntent)
                    } catch (_: ActivityNotFoundException) {
                        settingsLauncher.launch(
                            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        )
                    }
                }
            )
        }
    }
}
