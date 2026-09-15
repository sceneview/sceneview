package io.github.sceneview.demo.theme

import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import io.github.sceneview.demo.DemoSettings

/**
 * Whether the app's own chrome is allowed to animate.
 *
 * Three things can turn every duration in the app to zero, and all three mean the
 * same thing — *this frame is going to be read, not watched*:
 *
 *  - **The user asked for it.** Developer options' "Animator duration scale" set to
 *    off is the only system-wide reduce-motion switch Android exposes to an app that
 *    is not a `View` animation (a Compose `animate*AsState` does not consult it, so
 *    an app that respects the setting has to read it itself). Accessibility "Remove
 *    animations" writes the same three `Settings.Global` scales.
 *  - **QA mode.** [DemoSettings.qaMode] already pins every idle orbit and turntable
 *    in the demos so two captures of a scene agree; the chrome has to hold still for
 *    the same reason, or a screenshot lands mid-cascade.
 *  - **Inspection mode.** Android Studio `@Preview` and the Roborazzi snapshot tests
 *    compose one frame and capture it. An entrance animation that starts at alpha 0
 *    captures as a blank card.
 *
 * Read it through [LocalMotionEnabled] rather than calling this directly, so a
 * subtree can force it off without every call site knowing why.
 */
@Composable
fun rememberMotionEnabled(): Boolean {
    if (LocalInspectionMode.current) return false
    if (DemoSettings.qaMode) return false
    val context = LocalContext.current
    // The scales are a user setting, not a configuration: they change from Settings,
    // which restarts nothing here, but reading them once per composition of the app
    // root is enough — nobody toggles developer options mid-scroll, and polling them
    // every frame would cost a binder call per frame for a value that never moves.
    return remember(context) {
        val resolver = context.contentResolver
        val animator = Settings.Global.getFloat(
            resolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        val transition = Settings.Global.getFloat(
            resolver,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            1f,
        )
        animator != 0f && transition != 0f
    }
}

/**
 * Whether app chrome may animate. Defaults to `true` so a composable used outside
 * the app root (a preview, a test harness) still behaves; the real value is
 * provided once, at the top of `SceneViewDemoApp`.
 */
val LocalMotionEnabled = staticCompositionLocalOf { true }

/**
 * [durationMillis], or `0` when motion is off.
 *
 * Zeroing the duration rather than branching the composable is deliberate: the
 * animation still runs, still lands on the same end state, and still drives the
 * same recompositions — it simply arrives on the first frame. A `if (motion)`
 * around each animated modifier would fork the layout, which is how a reduced-motion
 * path drifts away from the one everybody else sees.
 */
@Composable
@ReadOnlyComposable
fun motionDuration(durationMillis: Int): Int =
    if (LocalMotionEnabled.current) durationMillis else 0

/** [SceneViewTokens.Motion.fade], or an instant snap when motion is off. */
@Composable
@ReadOnlyComposable
fun <T> motionFade(): FiniteAnimationSpec<T> =
    if (LocalMotionEnabled.current) SceneViewTokens.Motion.fade() else snap()

/** [SceneViewTokens.Motion.spring], or an instant snap when motion is off. */
@Composable
@ReadOnlyComposable
fun <T> motionSpring(): AnimationSpec<T> =
    if (LocalMotionEnabled.current) SceneViewTokens.Motion.spring() else snap()

/** A `DESIGN.md` tween, or an instant snap when motion is off. */
@Composable
@ReadOnlyComposable
fun <T> motionTween(
    durationMillis: Int,
    delayMillis: Int = 0,
    easing: Easing = SceneViewTokens.Ease.expressive,
): FiniteAnimationSpec<T> = if (LocalMotionEnabled.current) {
    tween(durationMillis = durationMillis, delayMillis = delayMillis, easing = easing)
} else {
    snap()
}

/**
 * The one press spec: `motion-spring` from `DESIGN.md`, on the scale a pressed
 * surface shrinks to. Kept here so a card, a chip and a glass pill cannot drift
 * apart — a catalogue where two tappable things answer a thumb differently reads
 * as two apps.
 */
@Composable
@ReadOnlyComposable
fun <T> pressSpring(): AnimationSpec<T> =
    if (LocalMotionEnabled.current) {
        spring(
            dampingRatio = SceneViewTokens.Spring.dampingRatio,
            stiffness = SceneViewTokens.Spring.stiffness,
        )
    } else {
        snap()
    }
