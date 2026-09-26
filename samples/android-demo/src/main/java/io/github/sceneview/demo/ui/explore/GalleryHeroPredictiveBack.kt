package io.github.sceneview.demo.ui.explore

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.lerp
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.explore.components.AsyncNetworkImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The two ends of the gallery hero's predictive-back gesture. */
internal enum class HeroBackPose { Open, Thumbnail }

/**
 * Seek state of the gallery hero while the user drags a predictive back gesture.
 *
 * [progress] runs 0 → 1 as the finger travels. The hero reads it through [heroBackLayer]
 * (shrinks, rounds its corners, drifts away from the swiped edge) and cross-fades the live
 * scene into the model's thumbnail with it — so the gesture previews the way back to the
 * card the model was opened from, and a cancel springs it back to full size.
 */
@Stable
internal class HeroPredictiveBack(
    private val progressState: State<Float>,
    private val swipeEdgeState: State<Int>,
) {
    /** 0 while the viewer is open, 1 when the hero has shrunk to its thumbnail pose. */
    val progress: Float get() = progressState.value

    /** [BackEventCompat.EDGE_LEFT] or [BackEventCompat.EDGE_RIGHT] of the gesture in flight. */
    val swipeEdge: Int get() = swipeEdgeState.value
}

/**
 * Drives [HeroPredictiveBack] from [PredictiveBackHandler] through a [SeekableTransitionState]:
 * every back event seeks the transition to the finger's progress, a release plays the rest of
 * it with `motion-spring` and then calls [onBack], a cancel animates back to [HeroBackPose.Open].
 *
 * @param enabled Only while the live scene is on screen; other stages keep the plain back.
 */
@Composable
internal fun rememberHeroPredictiveBack(
    enabled: Boolean,
    onBack: () -> Unit,
): HeroPredictiveBack {
    val seekState = remember { SeekableTransitionState(HeroBackPose.Open) }
    val swipeEdge = remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    // The handler's own coroutine is cancelled with the gesture, so the settle-back
    // animation of a cancelled gesture runs in a scope that outlives it.
    val settleScope = rememberCoroutineScope()

    PredictiveBackHandler(enabled = enabled) { events ->
        try {
            events.collect { event ->
                swipeEdge.intValue = event.swipeEdge
                seekState.seekTo(fraction = event.progress, targetState = HeroBackPose.Thumbnail)
            }
            seekState.animateTo(HeroBackPose.Thumbnail, SceneViewTokens.Motion.spring())
            onBack()
        } catch (cancelled: CancellationException) {
            settleScope.launch {
                seekState.animateTo(HeroBackPose.Open, SceneViewTokens.Motion.spring())
            }
            throw cancelled
        }
    }

    val transition = rememberTransition(seekState, label = "gallery-hero-back")
    val progress = transition.animateFloat(label = "gallery-hero-back-progress") { pose ->
        if (pose == HeroBackPose.Open) 0f else 1f
    }
    return remember(progress) { HeroPredictiveBack(progress, swipeEdge) }
}

/** Scale the hero reaches at the end of the gesture: the size of a gallery card, not a dot. */
private const val HERO_BACK_MIN_SCALE = 0.72f

/**
 * The hero's shape and position for [back]'s progress, applied in the draw phase only — the
 * layout (and so the SceneView's TextureView and its camera aspect) never changes size while
 * the finger moves, which is what keeps the gesture off the Filament render path.
 */
internal fun Modifier.heroBackLayer(back: HeroPredictiveBack, cornerRadius: Dp): Modifier =
    graphicsLayer {
        val p = back.progress
        val scale = 1f - (1f - HERO_BACK_MIN_SCALE) * p
        scaleX = scale
        scaleY = scale
        // Material predictive back: the surface drifts away from the edge being swiped.
        val drift = SceneViewTokens.Space.lg.toPx() * p
        translationX = if (back.swipeEdge == BackEventCompat.EDGE_LEFT) drift else -drift
        shape = RoundedCornerShape(lerp(cornerRadius, SceneViewTokens.Radius.xl, p))
        clip = true
    }

/**
 * Gesture progress at which the thumbnail starts to cover the live scene. The thumbnail is
 * framed differently from the orbit camera, so a crossfade over the whole gesture showed a
 * large ghost of the model over the small live one (emulator QA). The first stretch of the
 * gesture is a clean shrink; the thumbnail only takes over on the way to the release.
 */
private const val THUMBNAIL_FADE_START = 0.6f

/** Thumbnail opacity for a gesture [progress]: 0 until [THUMBNAIL_FADE_START], then linear to 1. */
internal fun heroBackThumbnailAlpha(progress: Float): Float =
    ((progress - THUMBNAIL_FADE_START) / (1f - THUMBNAIL_FADE_START)).coerceIn(0f, 1f)

/**
 * The model's gallery thumbnail, faded in over the live scene at the end of the gesture, so the
 * hero lands on the card it came from. Only composed once it would be visible: an idle viewer
 * pays nothing for it. The URL is the one the Downloading stage already showed, so it is served
 * from [AsyncNetworkImage]'s memory cache.
 */
@Composable
internal fun HeroBackThumbnail(back: HeroPredictiveBack, url: String?, contentDescription: String?) {
    val visible by remember(back) { derivedStateOf { heroBackThumbnailAlpha(back.progress) > 0f } }
    if (!visible) return
    AsyncNetworkImage(
        url = url,
        contentDescription = contentDescription,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = heroBackThumbnailAlpha(back.progress) },
        contentScale = ContentScale.Crop,
    )
}
