@file:Suppress("MatchingDeclarationName") // File intentionally contains both Colors + SceneviewTheme
package io.github.sceneview.sample

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

object Colors {
    // SceneView brand — seed #005BC1
    val Seed = Color(0xFF005BC1)

    // Light scheme
    val LightPrimary = Color(0xFF005BC1)
    val LightOnPrimary = Color(0xFFFFFFFF)
    val LightPrimaryContainer = Color(0xFFD4E3FF)
    val LightOnPrimaryContainer = Color(0xFF001B3F)
    val LightSecondary = Color(0xFF555F71)
    val LightOnSecondary = Color(0xFFFFFFFF)
    val LightSecondaryContainer = Color(0xFFD9E3F8)
    val LightOnSecondaryContainer = Color(0xFF121C2B)
    val LightTertiary = Color(0xFF6446CD)
    val LightOnTertiary = Color(0xFFFFFFFF)
    val LightTertiaryContainer = Color(0xFFE9DDFF)
    val LightOnTertiaryContainer = Color(0xFF1F0060)
    val LightError = Color(0xFFBA1A1A)
    val LightErrorContainer = Color(0xFFFFDAD6)
    val LightOnError = Color(0xFFFFFFFF)
    val LightOnErrorContainer = Color(0xFF410002)
    val LightBackground = Color(0xFFF9F9FF)
    val LightOnBackground = Color(0xFF1A1C1E)
    val LightOutline = Color(0xFF73777F)
    val LightInverseOnSurface = Color(0xFFF0F0F7)
    val LightInverseSurface = Color(0xFF2F3033)
    val LightInversePrimary = Color(0xFFA4C1FF)
    val LightSurfaceTint = Color(0xFF005BC1)
    val LightOutlineVariant = Color(0xFFDFE2EB)
    val LightScrim = Color(0xFF000000)
    val LightSurface = Color(0xFFF9F9FF)
    val LightOnSurface = Color(0xFF1A1C1E)
    val LightSurfaceVariant = Color(0xFFDFE2EB)
    val LightOnSurfaceVariant = Color(0xFF434750)

    // Dark scheme
    val DarkPrimary = Color(0xFFA4C1FF)
    val DarkOnPrimary = Color(0xFF002F65)
    val DarkPrimaryContainer = Color(0xFF00458F)
    val DarkOnPrimaryContainer = Color(0xFFD4E3FF)
    val DarkSecondary = Color(0xFFBDC7DC)
    val DarkOnSecondary = Color(0xFF273141)
    val DarkSecondaryContainer = Color(0xFF3D4758)
    val DarkOnSecondaryContainer = Color(0xFFD9E3F8)
    val DarkTertiary = Color(0xFFD2A8FF)
    val DarkOnTertiary = Color(0xFF37009B)
    val DarkTertiaryContainer = Color(0xFF4E2AA6)
    val DarkOnTertiaryContainer = Color(0xFFE9DDFF)
    val DarkError = Color(0xFFFFB4AB)
    val DarkErrorContainer = Color(0xFF93000A)
    val DarkOnError = Color(0xFF690005)
    val DarkOnErrorContainer = Color(0xFFFFDAD6)
    val DarkBackground = Color(0xFF111318)
    val DarkOnBackground = Color(0xFFE2E2E9)
    val DarkOutline = Color(0xFF8D9199)
    val DarkInverseOnSurface = Color(0xFF2F3033)
    val DarkInverseSurface = Color(0xFFE2E2E9)
    val DarkInversePrimary = Color(0xFF005BC1)
    val DarkSurfaceTint = Color(0xFFA4C1FF)
    val DarkOutlineVariant = Color(0xFF434750)
    val DarkScrim = Color(0xFF000000)
    val DarkSurface = Color(0xFF111318)
    val DarkOnSurface = Color(0xFFE2E2E9)
    val DarkSurfaceVariant = Color(0xFF434750)
    val DarkOnSurfaceVariant = Color(0xFFC3C6CF)
}

private val LightColorScheme = lightColorScheme(
    primary = Colors.LightPrimary,
    onPrimary = Colors.LightOnPrimary,
    primaryContainer = Colors.LightPrimaryContainer,
    onPrimaryContainer = Colors.LightOnPrimaryContainer,
    secondary = Colors.LightSecondary,
    onSecondary = Colors.LightOnSecondary,
    secondaryContainer = Colors.LightSecondaryContainer,
    onSecondaryContainer = Colors.LightOnSecondaryContainer,
    tertiary = Colors.LightTertiary,
    onTertiary = Colors.LightOnTertiary,
    tertiaryContainer = Colors.LightTertiaryContainer,
    onTertiaryContainer = Colors.LightOnTertiaryContainer,
    error = Colors.LightError,
    errorContainer = Colors.LightErrorContainer,
    onError = Colors.LightOnError,
    onErrorContainer = Colors.LightOnErrorContainer,
    background = Colors.LightBackground,
    onBackground = Colors.LightOnBackground,
    outline = Colors.LightOutline,
    inverseOnSurface = Colors.LightInverseOnSurface,
    inverseSurface = Colors.LightInverseSurface,
    inversePrimary = Colors.LightInversePrimary,
    surfaceTint = Colors.LightSurfaceTint,
    outlineVariant = Colors.LightOutlineVariant,
    scrim = Colors.LightScrim,
    surface = Colors.LightSurface,
    onSurface = Colors.LightOnSurface,
    surfaceVariant = Colors.LightSurfaceVariant,
    onSurfaceVariant = Colors.LightOnSurfaceVariant,
)


private val DarkColorScheme = darkColorScheme(
    primary = Colors.DarkPrimary,
    onPrimary = Colors.DarkOnPrimary,
    primaryContainer = Colors.DarkPrimaryContainer,
    onPrimaryContainer = Colors.DarkOnPrimaryContainer,
    secondary = Colors.DarkSecondary,
    onSecondary = Colors.DarkOnSecondary,
    secondaryContainer = Colors.DarkSecondaryContainer,
    onSecondaryContainer = Colors.DarkOnSecondaryContainer,
    tertiary = Colors.DarkTertiary,
    onTertiary = Colors.DarkOnTertiary,
    tertiaryContainer = Colors.DarkTertiaryContainer,
    onTertiaryContainer = Colors.DarkOnTertiaryContainer,
    error = Colors.DarkError,
    errorContainer = Colors.DarkErrorContainer,
    onError = Colors.DarkOnError,
    onErrorContainer = Colors.DarkOnErrorContainer,
    background = Colors.DarkBackground,
    onBackground = Colors.DarkOnBackground,
    outline = Colors.DarkOutline,
    inverseOnSurface = Colors.DarkInverseOnSurface,
    inverseSurface = Colors.DarkInverseSurface,
    inversePrimary = Colors.DarkInversePrimary,
    surfaceTint = Colors.DarkSurfaceTint,
    outlineVariant = Colors.DarkOutlineVariant,
    scrim = Colors.DarkScrim,
    surface = Colors.DarkSurface,
    onSurface = Colors.DarkOnSurface,
    surfaceVariant = Colors.DarkSurfaceVariant,
    onSurfaceVariant = Colors.DarkOnSurfaceVariant,
)

val Typography = Typography()

/**
 * Material 3 theme shared by all SceneView sample apps.
 *
 * Uses dynamic colour (Material You) on Android 12+ and falls back to the
 * SceneView brand colour scheme (seed #005BC1) on older devices.
 *
 * @param darkTheme Whether to use the dark colour scheme. Defaults to the system setting.
 * @param dynamicColor Whether to use Material You dynamic colours on Android 12+.
 * @param content The composable content to theme.
 */
@Composable
fun SceneviewTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable() () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
