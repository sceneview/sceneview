package io.github.sceneview.demo.common

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Who wants light status-bar icons right now (#3984).
 *
 * The icons are one window-wide flag, but several screens have an opinion about it — the
 * Showcase hero over its night sky, every demo's scrim over the dark stage — and during a
 * navigation both the screen leaving and the screen arriving are composed. Each used to
 * capture the flag, force it, and put the captured value back on dispose. Those
 * save-and-restore pairs raced: the Showcase, disposed last on its way out, restored the
 * dark icons it had captured *after* the demo had already forced light ones, so the clock
 * and battery vanished on the dark stage of Model Viewer, Materials, Lighting and the rest.
 *
 * Now screens only *request* light icons ([RequestLightStatusBarIcons]); the count is the
 * single source of truth, and [ProvideStatusBarIcons] applies it: light icons while at
 * least one request is live, the theme's own set otherwise. Order no longer matters.
 */
@Stable
class StatusBarIconsState internal constructor() {
    internal var lightIconRequests by mutableIntStateOf(0)
}

/** `null` outside [ProvideStatusBarIcons] — previews and screenshot tests — where requests are no-ops. */
val LocalStatusBarIcons = staticCompositionLocalOf<StatusBarIconsState?> { null }

/**
 * Asks for light (white) status-bar icons for as long as this call is composed with
 * [active] `true` — for a screen whose top edge is the dark stage or a dark scrim, in both
 * themes.
 */
@Composable
fun RequestLightStatusBarIcons(active: Boolean = true) {
    val state = LocalStatusBarIcons.current ?: return
    if (!active) return
    DisposableEffect(state) {
        state.lightIconRequests++
        onDispose { state.lightIconRequests-- }
    }
}

/**
 * The one place that writes the activity window's status-bar appearance. Place it inside
 * the theme, at the root of the activity's content.
 */
@Composable
fun ProvideStatusBarIcons(content: @Composable () -> Unit) {
    val state = remember { StatusBarIconsState() }
    val view = LocalView.current
    val themeIsLight = MaterialTheme.colorScheme.surface.luminance() >= 0.5f
    // `isAppearanceLightStatusBars` means "light *bars*", i.e. dark icons.
    val darkIcons = themeIsLight && state.lightIconRequests == 0
    SideEffect {
        val window = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>()
            .firstOrNull()
            ?.window
            ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        if (controller.isAppearanceLightStatusBars != darkIcons) {
            controller.isAppearanceLightStatusBars = darkIcons
        }
    }
    CompositionLocalProvider(LocalStatusBarIcons provides state, content = content)
}
