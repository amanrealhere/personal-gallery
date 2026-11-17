package com.amanrealhere.personalgallery.ui.screens.addalbum

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
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
import java.io.File
import java.io.IOException

@Composable
fun AddAlbumScreen(
    viewModel: GalleryViewModel,
    onAlbumSelected: (Album) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var folderPath by remember { mutableStateOf("") }
    var browseMessage by remember { mutableStateOf<String?>(null) }
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

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }

        val resolvedPath = filesystemPathFromTreeUri(uri)

        if (resolvedPath != null) {
            folderPath = resolvedPath
            browseMessage = null
            viewModel.clearRegistrationResult()
        } else {
            browseMessage =
                "This folder provider cannot be converted into a filesystem path. " +
                    "Please enter the absolute path manually."
        }
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier.padding(28.dp)
                ) {
                    Text(
                        text = "Add an album",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(Modifier.height(10.dp))

                    Text(
                        text = "Register an existing folder to keep its images " +
                            "organized in Personal Gallery.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(28.dp))

                    Text(
                        text = "Storage access",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = if (hasAllFilesAccess) {
                            "All-files access is enabled. Personal Gallery can " +
                                "read folders on shared storage."
                        } else {
                            "All-files access is required to read the folders " +
                                "you register."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (!hasAllFilesAccess) {
                        Spacer(Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = ::openAllFilesSettings
                        ) {
                            Text("Grant storage access")
                        }
                    }

                    Spacer(Modifier.height(28.dp))

                    Text(
                        text = "Folder location",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = "Enter an absolute path or browse for a folder.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(14.dp))

                    OutlinedTextField(
                        value = folderPath,
                        onValueChange = {
                            folderPath = it
                            browseMessage = null
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

                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = {
                                folderPickerLauncher.launch(null)
                            },
                            enabled = !isRegistering
                        ) {
                            Text("Browse folders")
                        }
                    }

                    if (browseMessage != null) {
                        Spacer(Modifier.height(10.dp))

                        Text(
                            text = browseMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    Text(
                        text = "Choose the folder itself, not an individual image. " +
                            "Subfolders will not be registered separately. " +
                            "Original files will not be moved or modified.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
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
                                Text("Register album")
                            }
                        }
                    }

                    when (val result = registrationResult) {
                        is AlbumRegistrationResult.InvalidFolder -> {
                            Spacer(Modifier.height(16.dp))

                            Text(
                                text = validationMessage(result.reason),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        AlbumRegistrationResult.RegistrationFailed -> {
                            Spacer(Modifier.height(16.dp))

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
}

private fun filesystemPathFromTreeUri(uri: Uri): String? {
    if (uri.authority != "com.android.externalstorage.documents") {
        return null
    }

    return try {
        if (!DocumentsContract.isTreeUri(uri)) {
            return null
        }

        val documentId = DocumentsContract.getTreeDocumentId(uri)
        val separator = documentId.indexOf(':')

        if (separator <= 0) {
            return null
        }

        val volume = documentId.substring(0, separator)
        val relativePath = documentId.substring(separator + 1)

        val storageRoot = when (volume.lowercase()) {
            "primary" -> Environment.getExternalStorageDirectory()
            else -> File("/storage", volume)
        }.canonicalFile

        val selectedFolder = File(storageRoot, relativePath).canonicalFile
        val rootPrefix = storageRoot.path.trimEnd(File.separator) + File.separator

        if (selectedFolder.path != storageRoot.path &&
            !selectedFolder.path.startsWith(rootPrefix)
        ) {
            return null
        }

        selectedFolder.path
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
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
