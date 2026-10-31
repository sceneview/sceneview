package io.github.sceneview.demo.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.theme.LocalMotionEnabled
import io.github.sceneview.demo.theme.SceneViewTokens

/**
 * The `SharedTransitionLayout` wrapped around the app's `NavHost`, so a destination can
 * share an element with the one it replaces. `null` outside the NavHost — a preview, a
 * test — where every shared-element modifier below is a no-op. A `ModalBottomSheet`
 * inherits it, so a row inside a sheet sees the scope too; it stays inert there because
 * only the card that was tapped is ever armed.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The NavHost destination's own `AnimatedContentScope`, provided by each destination. */
val LocalNavAnimatedScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** The two ends of a container transform: the Home card, and the demo screen it becomes. */
enum class ContainerRole { Card, Screen }

/**
 * Which demo is mid container transform (`motion-container` in `DESIGN.md`).
 *
 * Armed by a tap on a Home card, read by the NavHost to pick the morph over the shared
 * axis, and disarmed once Home has settled again. Only the card that was tapped carries
 * the shared bounds: a demo opened from anywhere else — the What's new sheet, the AR View
 * tab, a deep link — arrives on the plain shared axis, even when its card happens to be
 * composed under the sheet. Held in memory only: the activity handles rotation itself
 * (`configChanges`), so Back still shrinks into the card after one; after process death
 * nothing is armed and Back slides home.
 */
object DemoContainerTransform {
    var armedId: String? by mutableStateOf(null)

    fun key(demoId: String): String = "demo-container-$demoId"
}

/**
 * One end of the Home card → demo screen container transform (#4883).
 *
 * Both ends render through `sharedBounds` in the overlay while the bounds travel on
 * `motion-container`. The card is the container: it re-measures to the growing bounds,
 * its tint filling them, while the screen fades in above it after `motion-fade-through`.
 * The screen is laid out once at full size and scaled to the bounds (width-filled,
 * top-aligned), so while it is still card-sized the window shows its top — its title
 * row — where the card's title was. The corner radius runs from the card's
 * `home-row-radius` to the screen's square corners. Back plays it in reverse, and the
 * predictive-back gesture seeks it under the finger.
 *
 * A no-op when motion is off, outside the NavHost, or when [demoId] is not the armed one.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.demoContainerTransform(demoId: String, role: ContainerRole): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val scope = LocalNavAnimatedScope.current ?: return this
    // Read per card through derivedStateOf, so arming one card recomposes only that card
    // instead of every card on screen.
    val armed by remember(demoId) { derivedStateOf { DemoContainerTransform.armedId == demoId } }
    if (!LocalMotionEnabled.current || !armed) return this
    val restRadius = SceneViewTokens.Home.rowRadius
    val radius by scope.transition.animateDp(
        transitionSpec = { SceneViewTokens.Motion.container() },
        label = "container-radius",
    ) { state ->
        if (role == ContainerRole.Screen && state == EnterExitState.Visible) 0.dp else restRadius
    }
    val handover = SceneViewTokens.Motion.fadeThroughOutMillis
    val face = SceneViewTokens.Duration.shortMillis
    val ease = SceneViewTokens.Ease.expressive
    val screen = role == ContainerRole.Screen
    return with(shared) {
        this@demoContainerTransform.sharedBounds(
            sharedContentState = rememberSharedContentState(DemoContainerTransform.key(demoId)),
            animatedVisibilityScope = scope,
            // The screen is drawn above the card and owns the cross-fade; the card stays
            // opaque under it as the container, so no frame shows Home through the bounds.
            enter = if (screen) {
                fadeIn(tween(durationMillis = face, delayMillis = handover, easing = ease))
            } else {
                fadeIn(tween(durationMillis = handover, easing = ease))
            },
            exit = if (screen) {
                fadeOut(tween(durationMillis = face, delayMillis = handover, easing = ease))
            } else {
                fadeOut(tween(durationMillis = SceneViewTokens.Motion.containerMillis, easing = ease))
            },
            boundsTransform = { _, _ -> SceneViewTokens.Motion.container() },
            // The screen is measured once, at full size, and scaled to the travelling
            // bounds, so its 3D viewport is never resized per frame; the card re-measures
            // to them instead, so its tint fills the growing container.
            resizeMode = if (screen) {
                SharedTransitionScope.ResizeMode.scaleToBounds(
                    contentScale = ContentScale.FillWidth,
                    alignment = Alignment.TopCenter,
                )
            } else {
                SharedTransitionScope.ResizeMode.RemeasureToBounds
            },
            zIndexInOverlay = if (screen) 1f else 0f,
            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(radius)),
        )
    }
}
