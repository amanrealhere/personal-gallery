package com.amanrealhere.personalgallery.ui.screens.addalbum

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amanrealhere.personalgallery.data.repository.AlbumRegistrationResult
import com.amanrealhere.personalgallery.data.storage.FolderValidationResult
import com.amanrealhere.personalgallery.domain.model.Album
import com.amanrealhere.personalgallery.ui.GalleryViewModel

@Composable
fun AddAlbumScreen(
    viewModel: GalleryViewModel,
    onAlbumSelected: (Album) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var folderPath by remember { mutableStateOf("") }
    var hasAllFilesAccess by remember {
        mutableStateOf(Environment.isExternalStorageManager())
    }

    val registrationResult by
        viewModel.registrationResult.collectAsStateWithLifecycle()

    val isRegistering by
        viewModel.isRegistering.collectAsStateWithLifecycle()

    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
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

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(registrationResult) {
        when (val result = registrationResult) {
            is AlbumRegistrationResult.Registered -> {
                viewModel.clearRegistrationResult()
                onAlbumSelected(result.album)
            }

            is AlbumRegistrationResult.AlreadyRegistered -> {
                viewModel.clearRegistrationResult()
                onAlbumSelected(result.album)
            }

            else -> Unit
        }
    }

    fun openAllFilesSettings() {
        val appSpecificIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
        ).apply {
            data = Uri.parse("package:${context.packageName}")
        }

        try {
            settingsLauncher.launch(appSpecificIntent)
        } catch (_: ActivityNotFoundException) {
            settingsLauncher.launch(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            )
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = "Add an album",
                    style = MaterialTheme.typography.headlineMedium
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Register an existing folder containing your screenshots. " +
                        "The original files will not be moved or modified.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(28.dp))

                Text(
                    text = "Storage permission",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (hasAllFilesAccess) {
                        "All-files access is granted."
                    } else {
                        "Personal Gallery needs all-files access to read folders " +
                            "you register, including folders on shared storage."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!hasAllFilesAccess) {
                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = ::openAllFilesSettings
                    ) {
                        Text("Grant storage access")
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                Text(
                    text = "Folder path",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = folderPath,
                    onValueChange = {
                        folderPath = it
                        viewModel.clearRegistrationResult()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isRegistering,
                    singleLine = true,
                    label = {
                        Text("Absolute folder path")
                    },
                    placeholder = {
                        Text("/storage/emulated/0/Pictures/Screenshots")
                    },
                    keyboardOptions = KeyboardOptions.Default
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Enter the complete path to an existing folder. " +
                        "Subfolders will not be registered separately.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        if (!hasAllFilesAccess) {
                            openAllFilesSettings()
                        } else {
                            viewModel.registerAlbum(folderPath)
                        }
                    },
                    enabled = folderPath.isNotBlank() && !isRegistering
                ) {
                    if (isRegistering) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Register folder")
                    }
                }

                when (val result = registrationResult) {
                    is AlbumRegistrationResult.InvalidFolder -> {
                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = validationMessage(result.reason),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    AlbumRegistrationResult.RegistrationFailed -> {
                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "The folder could not be registered. " +
                                "Please try again.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    else -> Unit
                }
            }
        }
    }
}

private fun validationMessage(
    reason: FolderValidationResult
): String {
    return when (reason) {
        FolderValidationResult.EmptyPath ->
            "Enter a folder path."

        FolderValidationResult.NotAbsolutePath ->
            "Enter an absolute path, starting from the storage root."

        FolderValidationResult.PermissionRequired ->
            "Storage access is required for this folder. Grant access in Android Settings."

        FolderValidationResult.DoesNotExist ->
            "That folder does not exist."

        FolderValidationResult.NotDirectory ->
            "The specified path is not a directory."

        FolderValidationResult.NotReadable ->
            "The folder cannot be read. Check its storage permissions."

        FolderValidationResult.InvalidPath ->
            "The folder path could not be resolved."

        is FolderValidationResult.Valid ->
            ""
    }
}
