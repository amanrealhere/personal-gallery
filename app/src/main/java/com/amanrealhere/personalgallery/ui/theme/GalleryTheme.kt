package com.amanrealhere.personalgallery.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GalleryDarkColorScheme = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF174A82),
    onPrimaryContainer = Color(0xFFD6E3FF),

    secondary = Color(0xFFBFC8D8),
    onSecondary = Color(0xFF283444),
    secondaryContainer = Color(0xFF3D4A5C),
    onSecondaryContainer = Color(0xFFDCE5F5),

    tertiary = Color(0xFFA8DADC),
    onTertiary = Color(0xFF12383A),
    tertiaryContainer = Color(0xFF285457),
    onTertiaryContainer = Color(0xFFC4F0F1),

    background = Color(0xFF101116),
    onBackground = Color(0xFFE9E5EF),

    surface = Color(0xFF17181F),
    onSurface = Color(0xFFE9E5EF),

    surfaceVariant = Color(0xFF303640),
    onSurfaceVariant = Color(0xFFC4CAD4),
    surfaceTint = Color(0xFFA8C7FA),

    surfaceDim = Color(0xFF101116),
    surfaceBright = Color(0xFF3A3943),
    surfaceContainerLowest = Color(0xFF0C0D12),
    surfaceContainerLow = Color(0xFF191A22),
    surfaceContainer = Color(0xFF1F2028),
    surfaceContainerHigh = Color(0xFF292A33),
    surfaceContainerHighest = Color(0xFF34353F),

    inverseSurface = Color(0xFFE9E5EF),
    inverseOnSurface = Color(0xFF303039),
    inversePrimary = Color(0xFF415F91),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),

    outline = Color(0xFF8E949E),
    outlineVariant = Color(0xFF454B55),
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
