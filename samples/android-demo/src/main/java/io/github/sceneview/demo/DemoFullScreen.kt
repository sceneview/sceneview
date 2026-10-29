package io.github.sceneview.demo

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.GlassIconButton

// Full screen for every demo (#4558): the pieces DemoScaffold composes when the identity
// row's full-screen button is tapped.

/**
 * Whether the demo is in full screen (#4558) — provided in the scaffold's `scene` slot.
 *
 * The bands of [LocalDemoSceneCover] and [LocalDemoSceneRestingCover] already ease to zero on
 * `motion-focus` as full screen opens, and back on the way out: a scene framed by them follows on
 * its own. This is for a demo that frames its subject from what it measures of the chrome itself
 * (3D Scan's replay) or from a fixed reserve (the Model Viewer's dock): it gives that frame up for
 * the whole screen while this is `true`, and keeps it to go back to. `false` outside a scaffold.
 */
val LocalDemoFullScreen = androidx.compose.runtime.compositionLocalOf { false }

/**
 * Fades a band out while the demo is in full screen (#4558) and, once it has gone, stops
 * placing it — an unplaced node draws nothing and takes no touch, so the scene under it keeps
 * every gesture. The band stays composed: a demo's overlay comes back with its state.
 */
internal fun Modifier.hiddenInFullScreen(alpha: () -> Float): Modifier =
    graphicsLayer { this.alpha = alpha() }
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                if (alpha() > 0f) placeable.place(0, 0)
            }
        }

/**
 * The way back from full screen (#4558): the same glass button, in the corner the full-screen
 * button left, alone on the scene. With no band under it, it carries its own `chrome-scrim`
 * ground ([GlassIconButton]'s `ground`), like the glass on a phone held sideways.
 */
@Composable
internal fun BoxScope.DemoFullScreenExit(visible: Boolean, onExit: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(SceneViewTokens.Motion.fade()),
        exit = fadeOut(SceneViewTokens.Motion.fade()),
        modifier = Modifier
            .align(Alignment.TopEnd)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
            )
            .padding(SceneViewTokens.Space.md),
    ) {
        GlassIconButton(
            icon = Icons.Outlined.FullscreenExit,
            contentDescription = stringResource(R.string.demo_full_screen_exit_cd),
            onClick = onExit,
            ground = LocalStageChrome.current.scrim,
            modifier = Modifier.testTag(DemoScaffoldTestTags.FULL_SCREEN_EXIT),
        )
    }
}

/**
 * Hides the status and navigation bars while [focused] (#4558); a swipe from the edge shows
 * them for a moment. Leaving restores what was there before — a live AR session in the AR
 * tab already hides them, and must not get them back from a demo leaving full screen.
 */
@Composable
internal fun DemoFullScreenSystemBars(focused: Boolean) {
    if (!focused) return
    val view = LocalView.current
    DisposableEffect(view) {
        val activity = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>()
            .firstOrNull()
        val window = activity?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        val barsWereShown = ViewCompat.getRootWindowInsets(view)
            ?.isVisible(WindowInsetsCompat.Type.statusBars()) != false
        val behavior = controller.systemBarsBehavior
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller.systemBarsBehavior = behavior
            if (barsWereShown) controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
