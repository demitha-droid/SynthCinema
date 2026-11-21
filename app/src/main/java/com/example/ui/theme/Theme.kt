package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val SynthCinemaColorScheme = darkColorScheme(
    primary = ProjectorAmber,
    onPrimary = ObsidianCanvas,
    primaryContainer = ProjectorAmberContainer,
    onPrimaryContainer = ProjectorAmberLight,
    secondary = SynthCyan,
    onSecondary = SynthCyanDark,
    secondaryContainer = SynthCyanContainer,
    onSecondaryContainer = SynthCyan,
    tertiary = YouTubeCrimson,
    onTertiary = BoneWhite,
    tertiaryContainer = YouTubeCrimsonBg,
    onTertiaryContainer = BoneWhite,
    background = ObsidianCanvas,
    onBackground = BoneWhite,
    surface = ScreeningSurface,
    onSurface = BoneWhite,
    surfaceVariant = ElevatedSurface,
    onSurfaceVariant = WarmSilver,
    surfaceContainerLowest = ObsidianCanvas,
    surfaceContainerLow = ScreeningSurface,
    surfaceContainer = ElevatedSurface,
    surfaceContainerHigh = CardSurfaceHigh,
    outline = SubtleBorder,
    outlineVariant = SubtleBorder.copy(alpha = 0.6f),
    error = CinemaError,
    onError = ObsidianCanvas,
    errorContainer = CinemaErrorContainer,
    onErrorContainer = BoneWhite
)

val SynthCinemaShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun SynthCinemaTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = SynthCinemaColorScheme,
        typography = SynthCinemaTypography,
        shapes = SynthCinemaShapes,
        content = content
    )
}
