package io.github.sceneview.demo.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.theme.LocalMotionEnabled
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.pressSpring
import kotlin.math.min
import kotlinx.coroutines.delay

/**
 * Shrinks the element to `motion-spring`'s press scale while [interactionSource]
 * reports a press, and lets it back with the spring's overshoot.
 *
 * Drawn in a `graphicsLayer`, so the press never re-measures the row it sits in:
 * a chip that changed its own size under the thumb would push its neighbours
 * sideways, which is exactly the feedback a press must not give.
 */
@Composable
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = SceneViewTokens.Spring.pressScale,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = pressSpring(),
        label = "press-scale",
    )
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

/** How far a card rises as it cascades in — `space-lg`, the `DESIGN.md` scroll-reveal distance. */
private val CascadeRise = 24.dp

/** Per-card offset in the cascade. */
private const val CASCADE_STEP_MILLIS = 55

/**
 * Cards past this rank all start together. A stagger that keeps counting makes the
 * bottom of a 53-demo catalogue arrive a minute after the top; the point is a wave
 * across the first screenful, not a queue.
 */
private const val CASCADE_MAX_STEPS = 8

/**
 * The window during which the catalogue is still cascading. After it, a card that
 * scrolls into view appears instantly — which is the whole contract: the reveal is
 * an *entrance*, played once when the screen arrives, never again under the thumb.
 */
private const val CASCADE_WINDOW_MILLIS =
    CASCADE_MAX_STEPS * CASCADE_STEP_MILLIS + SceneViewTokens.Duration.mediumMillis

/**
 * The one-shot entrance the catalogue plays when it first appears.
 *
 * [delayFor] returns `null` — meaning "draw this at rest, do not animate" — for
 * every card composed after the window closed, so scrolling never replays the
 * reveal, and for every card at all when motion is off.
 */
@Immutable
class CascadeState internal constructor(private val running: Boolean) {
    fun delayFor(index: Int): Int? =
        if (running) min(index, CASCADE_MAX_STEPS) * CASCADE_STEP_MILLIS else null
}

/**
 * A cascade that plays once per arrival on the screen and is remembered across
 * configuration changes — a rotation is not a new arrival, and replaying a
 * 53-card wave because the user turned the phone is a tic, not a flourish.
 */
@Composable
fun rememberCascade(): CascadeState {
    val motionEnabled = LocalMotionEnabled.current
    var played by rememberSaveable { mutableStateOf(false) }
    var running by remember { mutableStateOf(motionEnabled && !played) }
    LaunchedEffect(Unit) {
        if (!running) return@LaunchedEffect
        delay(CASCADE_WINDOW_MILLIS.toLong())
        running = false
        played = true
    }
    return remember(running) { CascadeState(running) }
}

/**
 * Fades and lifts the element into place after [delayMillis], or leaves it
 * untouched when [delayMillis] is `null` (see [CascadeState.delayFor]).
 *
 * A modifier rather than a wrapper composable on purpose: in a `LazyVerticalGrid`
 * item, `Modifier.animateItem()` has to stay on the item's own root, and an extra
 * `Box` between them would silently take the placement animation away from it.
 */
@Composable
fun Modifier.cascadeIn(delayMillis: Int?): Modifier {
    if (delayMillis == null) return this
    val rise = with(LocalDensity.current) { CascadeRise.toPx() }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = SceneViewTokens.Duration.mediumMillis,
                delayMillis = delayMillis,
                easing = SceneViewTokens.Ease.expressive,
            ),
        )
    }
    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * rise
    }
}
