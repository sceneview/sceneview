@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.sceneview.demo.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * SceneView Demo Theme — Material 3 Expressive
 *
 * Color system from the SceneView design system (source: #005bc1).
 * Typography: M3 Expressive scale with Inter-weight equivalents.
 * Shapes: M3 Expressive with DESIGN.md radius tokens (8/12/16/28/32dp).
 * Motion: Expressive spring animations.
 *
 * ## Dynamic colour is OFF by default, on purpose
 *
 * `dynamicColor` used to default to `true`. On any Android 12+ device that means
 * [dynamicLightColorScheme] / [dynamicDarkColorScheme] win, the wallpaper palette
 * paints the whole app, and the brand ramp below — every `md_theme_*` token,
 * derived from the SceneView source colour `#005bc1` — never executes. In practice
 * the Play Store app shipped in whatever lavender or peach the user's wallpaper
 * happened to produce, which `DESIGN.md` explicitly rules out, and screenshots of
 * it showed a different product on every device.
 *
 * This app is the showcase for an SDK. Showing the brand is part of the job, so
 * the default is now `false` and the schemes below are what actually run. The
 * parameter is kept so a host that *wants* Material You can opt in — that is a
 * legitimate choice for a consumer app, just not for this one.
 */

private val LightColors = lightColorScheme(
    primary = md_theme_light_primary,
    onPrimary = md_theme_light_onPrimary,
    primaryContainer = md_theme_light_primaryContainer,
    onPrimaryContainer = md_theme_light_onPrimaryContainer,
    inversePrimary = md_theme_light_inversePrimary,
    secondary = md_theme_light_secondary,
    onSecondary = md_theme_light_onSecondary,
    secondaryContainer = md_theme_light_secondaryContainer,
    onSecondaryContainer = md_theme_light_onSecondaryContainer,
    tertiary = md_theme_light_tertiary,
    onTertiary = md_theme_light_onTertiary,
    tertiaryContainer = md_theme_light_tertiaryContainer,
    onTertiaryContainer = md_theme_light_onTertiaryContainer,
    error = md_theme_light_error,
    onError = md_theme_light_onError,
    errorContainer = md_theme_light_errorContainer,
    onErrorContainer = md_theme_light_onErrorContainer,
    // `background` is the role that paints Scaffold's container, and therefore the
    // strip behind the status bar on every screen in this app. It was the one
    // surface role this scheme never named, so it fell through to the Material3
    // BASELINE #FEF7FF while every other surface here is #F9F9FF — five levels
    // apart in the red channel, which is exactly enough to read as a hard line
    // across the top of the display. Measured on a Pixel 7a at #3237: rows 0-130
    // #fef7ff, rows 131+ #f9f9ff.
    //
    // It looked like Material You and it is not (it survived `dynamicColor =
    // false`); it looked like the XML theme's missing `colorSurface` and it is not
    // (binding that changed nothing on screen — the strip is Compose-drawn). It was
    // a defaulted parameter, in a 30-line argument list where every neighbour is
    // spelled out, which is the least visible place a wrong colour can hide.
    //
    // M3 treats background and surface as the same tone; keeping them equal here is
    // both correct and the only way this stays true when a token is re-generated.
    background = md_theme_light_surface,
    onBackground = md_theme_light_onSurface,
    surface = md_theme_light_surface,
    onSurface = md_theme_light_onSurface,
    surfaceVariant = md_theme_light_surfaceVariant,
    onSurfaceVariant = md_theme_light_onSurfaceVariant,
    surfaceDim = md_theme_light_surfaceDim,
    surfaceBright = md_theme_light_surfaceBright,
    surfaceContainerLowest = md_theme_light_surfaceContainerLowest,
    surfaceContainerLow = md_theme_light_surfaceContainerLow,
    surfaceContainer = md_theme_light_surfaceContainer,
    surfaceContainerHigh = md_theme_light_surfaceContainerHigh,
    surfaceContainerHighest = md_theme_light_surfaceContainerHighest,
    outline = md_theme_light_outline,
    outlineVariant = md_theme_light_outlineVariant,
    inverseSurface = md_theme_light_inverseSurface,
    inverseOnSurface = md_theme_light_inverseOnSurface,
)

private val DarkColors = darkColorScheme(
    primary = md_theme_dark_primary,
    onPrimary = md_theme_dark_onPrimary,
    primaryContainer = md_theme_dark_primaryContainer,
    onPrimaryContainer = md_theme_dark_onPrimaryContainer,
    inversePrimary = md_theme_dark_inversePrimary,
    secondary = md_theme_dark_secondary,
    onSecondary = md_theme_dark_onSecondary,
    secondaryContainer = md_theme_dark_secondaryContainer,
    onSecondaryContainer = md_theme_dark_onSecondaryContainer,
    tertiary = md_theme_dark_tertiary,
    onTertiary = md_theme_dark_onTertiary,
    tertiaryContainer = md_theme_dark_tertiaryContainer,
    onTertiaryContainer = md_theme_dark_onTertiaryContainer,
    error = md_theme_dark_error,
    onError = md_theme_dark_onError,
    errorContainer = md_theme_dark_errorContainer,
    onErrorContainer = md_theme_dark_onErrorContainer,
    // Same omission, same consequence in the dark scheme: baseline #141218 against
    // this app's #111318. See the note on LightColors above.
    background = md_theme_dark_surface,
    onBackground = md_theme_dark_onSurface,
    surface = md_theme_dark_surface,
    onSurface = md_theme_dark_onSurface,
    surfaceVariant = md_theme_dark_surfaceVariant,
    onSurfaceVariant = md_theme_dark_onSurfaceVariant,
    surfaceDim = md_theme_dark_surfaceDim,
    surfaceBright = md_theme_dark_surfaceBright,
    surfaceContainerLowest = md_theme_dark_surfaceContainerLowest,
    surfaceContainerLow = md_theme_dark_surfaceContainerLow,
    surfaceContainer = md_theme_dark_surfaceContainer,
    surfaceContainerHigh = md_theme_dark_surfaceContainerHigh,
    surfaceContainerHighest = md_theme_dark_surfaceContainerHighest,
    outline = md_theme_dark_outline,
    outlineVariant = md_theme_dark_outlineVariant,
    inverseSurface = md_theme_dark_inverseSurface,
    inverseOnSurface = md_theme_dark_inverseOnSurface,
)

@Composable
fun SceneViewDemoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography = SceneViewTypography,
        shapes = SceneViewShapes,
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}
