package com.amanrealhere.personalgallery.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GalleryDarkColorScheme = darkColorScheme(
    primary = Color(0xFFCBB8F0),
    onPrimary = Color(0xFF302447),
    primaryContainer = Color(0xFF4B3D62),
    onPrimaryContainer = Color(0xFFEBDDFF),

    secondary = Color(0xFFC9C2D5),
    onSecondary = Color(0xFF302D38),
    secondaryContainer = Color(0xFF484451),
    onSecondaryContainer = Color(0xFFE8E0F3),

    tertiary = Color(0xFFB8C9E8),
    onTertiary = Color(0xFF243247),
    tertiaryContainer = Color(0xFF3B4B63),
    onTertiaryContainer = Color(0xFFD9E4FF),

    background = Color(0xFF101116),
    onBackground = Color(0xFFE9E5EF),

    surface = Color(0xFF17181F),
    onSurface = Color(0xFFE9E5EF),

    surfaceVariant = Color(0xFF2C2933),
    onSurfaceVariant = Color(0xFFC9C2D2),
    surfaceTint = Color(0xFFCBB8F0),

    surfaceDim = Color(0xFF101116),
    surfaceBright = Color(0xFF3A3943),
    surfaceContainerLowest = Color(0xFF0C0D12),
    surfaceContainerLow = Color(0xFF191A22),
    surfaceContainer = Color(0xFF1F2028),
    surfaceContainerHigh = Color(0xFF292A33),
    surfaceContainerHighest = Color(0xFF34353F),

    inverseSurface = Color(0xFFE9E5EF),
    inverseOnSurface = Color(0xFF303039),
    inversePrimary = Color(0xFF665080),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),

    outline = Color(0xFF928C9B),
    outlineVariant = Color(0xFF494650),
    scrim = Color(0xFF000000)
)

@Composable
fun GalleryTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = GalleryDarkColorScheme,
        content = content
    )
}
