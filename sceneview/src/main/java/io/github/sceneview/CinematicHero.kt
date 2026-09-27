package io.github.sceneview

import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import io.github.sceneview.math.Position
import io.github.sceneview.node.CameraNode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * A cinematic, scroll-driven 3D hero for the top of a scrolling screen.
 *
 * The [content] turns slowly on a cinematic orbit. As the screen scrolls, the camera swings
 * round, rises and pushes in, and the whole stage lags behind the scroll (parallax), so the 3D
 * reads as a layer *behind* the page. Its bottom edge fades into whatever is under it.
 *
 * **How to place it — two lines, and nothing else works.** The hero is drawn *under* your list,
 * never inside it: a `SceneView` inside a lazy item would eat the drags that start on it and be
 * destroyed, then reloaded, every time it scrolls off screen. So:
 * 1. Put `CinematicHero` and your list in the same `Box`, the hero **first** (underneath).
 * 2. Make the list's **first item** a transparent `Spacer` of the same [height]. The hero follows
 *    that spacer, pixel for pixel, and the list keeps every touch.
 *
 * ```kotlin
 * val listState = rememberLazyListState()
 * Box(Modifier.fillMaxSize()) {
 *     CinematicHero(listState = listState, height = 420.dp) {
 *         rememberModelInstance(
 *             modelLoader,
 *             "https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/BoomBox/glTF-Binary/BoomBox.glb"
 *         )?.let { ModelNode(modelInstance = it, scaleToUnits = 1f) }
 *     }
 *     LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
 *         item { Spacer(Modifier.height(420.dp)) } // must be the first item, same height
 *         items(cards) { card -> MyCard(card) }     // your existing content, unchanged
 *     }
 * }
 * ```
 *
 * A remote URL needs `<uses-permission android:name="android.permission.INTERNET" />`; a
 * bundled `assets/` path works too. The content is centred on the origin the camera looks at
 * (`SceneView`'s `autoCenterContent`), and `scaleToUnits = 1f` fits any model to the default
 * framing ([contentRadius] `0.75f`) — keep it, whatever the model's own size. The list must not paint an opaque background
 * over the spacer (a `LazyColumn` has none by default). Everything here runs on the main
 * thread, as Filament requires; load models with [rememberModelInstance] as above.
 *
 * Off screen, the hero stops drawing and its clock stops; it resumes on the same frame. With
 * system animations turned off (reduced motion) it does not turn by itself, and only the scroll
 * moves the camera. It is invisible to accessibility services — keep your title and text in the
 * list, over the spacer, if the hero needs them.
 *
 * @param listState     The state of the `LazyColumn` whose first item is the spacer.
 * @param height        Height of the hero — and of the spacer that opens the list.
 * @param modifier      Applied to the hero's full-size container, under the list.
 * @param backdrop      Painted behind the 3D, moving with it — e.g. a vertical gradient from
 *                      your theme. `null` (default) leaves the page's own background visible.
 * @param profile       The orbit's feel — see [CinematicCameraProfile].
 * @param contentRadius Bounding-sphere radius of the content, in world units. `0.75f` frames
 *                      any model scaled with `scaleToUnits = 1f` (its sphere is 0.5 to 0.87).
 * @param parallax      How far the 3D lags behind the scroll: `0` moves with the page, `1` stays
 *                      put. Default `0.4f`.
 * @param content       The 3D scene, in the same [SceneScope] DSL as [SceneView].
 */
@Composable
fun CinematicHero(
    listState: LazyListState,
    height: Dp,
    modifier: Modifier = Modifier,
    backdrop: Brush? = null,
    profile: CinematicCameraProfile = CinematicCameraProfile.Default,
    contentRadius: Float = 0.75f,
    parallax: Float = 0.4f,
    content: @Composable SceneScope.() -> Unit,
) {
    val anchor = remember(listState) { lazyListHeroAnchor(listState) }
    CinematicHeroStage(anchor, height, modifier, backdrop, profile, contentRadius, parallax, content)
}

/**
 * [CinematicHero] over a `LazyVerticalGrid`. The grid's first item is the spacer, and it must span
 * the whole row: `item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(420.dp)) }`.
 * Everything else is the `LazyListState` overload's — read its documentation.
 */
@Composable
fun CinematicHero(
    gridState: LazyGridState,
    height: Dp,
    modifier: Modifier = Modifier,
    backdrop: Brush? = null,
    profile: CinematicCameraProfile = CinematicCameraProfile.Default,
    contentRadius: Float = 0.75f,
    parallax: Float = 0.4f,
    content: @Composable SceneScope.() -> Unit,
) {
    val anchor = remember(gridState) { lazyGridHeroAnchor(gridState) }
    CinematicHeroStage(anchor, height, modifier, backdrop, profile, contentRadius, parallax, content)
}

/**
 * [CinematicHero] over a `Column(Modifier.verticalScroll(scrollState))`. The column's first child
 * is the spacer, `Spacer(Modifier.height(420.dp))`, with no padding above it. Everything else is
 * the `LazyListState` overload's — read its documentation.
 */
@Composable
fun CinematicHero(
    scrollState: ScrollState,
    height: Dp,
    modifier: Modifier = Modifier,
    backdrop: Brush? = null,
    profile: CinematicCameraProfile = CinematicCameraProfile.Default,
    contentRadius: Float = 0.75f,
    parallax: Float = 0.4f,
    content: @Composable SceneScope.() -> Unit,
) {
    val anchor = remember(scrollState) { scrollHeroAnchor(scrollState) }
    CinematicHeroStage(anchor, height, modifier, backdrop, profile, contentRadius, parallax, content)
}

/** How far the orbit swings round over one hero height of scroll. */
internal const val HERO_SCROLL_ORBIT_DEGREES = 70f

/** How far the camera rises over one hero height of scroll, added to the profile's elevation. */
internal const val HERO_SCROLL_RISE_DEGREES = 22f

/** Fraction of the framing distance the camera pushes in over one hero height of scroll. */
internal const val HERO_SCROLL_DOLLY = 0.3f

/** The hero's cadence while it turns: smooth, but never the panel's 120 Hz for a background. */
private const val HERO_MAX_FPS = 60

/** Where the bottom fade starts, as a fraction of the hero's height. */
private const val HERO_FADE_START = 0.78f

/**
 * Where the hero's spacer is, read at draw time so a scroll costs no recomposition.
 *
 * [top] is the spacer's top edge relative to the list's top edge, in pixels, or `NaN` while the
 * spacer is not laid out (a lazy item scrolled far away). [scrolled] is how far the list has
 * scrolled into the spacer, in pixels, `>= 0`.
 */
internal interface HeroAnchor {
    fun top(): Float
    fun scrolled(): Float
}

internal fun lazyListHeroAnchor(state: LazyListState): HeroAnchor = object : HeroAnchor {
    override fun top(): Float {
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == 0 } ?: return Float.NaN
        return (item.offset - info.viewportStartOffset).toFloat()
    }

    override fun scrolled(): Float =
        if (state.firstVisibleItemIndex == 0) state.firstVisibleItemScrollOffset.toFloat()
        else Float.MAX_VALUE
}

internal fun lazyGridHeroAnchor(state: LazyGridState): HeroAnchor = object : HeroAnchor {
    override fun top(): Float {
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == 0 } ?: return Float.NaN
        return (item.offset.y - info.viewportStartOffset).toFloat()
    }

    override fun scrolled(): Float =
        if (state.firstVisibleItemIndex == 0) state.firstVisibleItemScrollOffset.toFloat()
        else Float.MAX_VALUE
}

internal fun scrollHeroAnchor(state: ScrollState): HeroAnchor = object : HeroAnchor {
    override fun top(): Float = -state.value.toFloat()
    override fun scrolled(): Float = state.value.toFloat()
}

/** `0` at rest, `1` once the list has scrolled one hero height, clamped. */
internal fun heroScrollProgress(scrolledPx: Float, heightPx: Float): Float =
    if (heightPx <= 0f) 0f else (scrolledPx / heightPx).coerceIn(0f, 1f)

/**
 * The hero's camera eye at [timeSeconds] of turntable and [scrollProgress] of scroll (`0..1`).
 *
 * At `scrollProgress = 0` it is exactly [cinematicCameraEye] at the framing distance of
 * [contentRadius]. Scrolling swings the orbit round by [HERO_SCROLL_ORBIT_DEGREES], raises it by
 * [HERO_SCROLL_RISE_DEGREES] and pushes in by [HERO_SCROLL_DOLLY] of the distance — linearly, so
 * the camera stays attached to the finger. The camera looks at the origin, where `SceneView`'s
 * `autoCenterContent` puts the content.
 */
internal fun cinematicHeroEye(
    timeSeconds: Float,
    scrollProgress: Float,
    profile: CinematicCameraProfile,
    contentRadius: Float,
): Position {
    val p = scrollProgress.coerceIn(0f, 1f)
    val azimuth = cinematicAzimuth(timeSeconds, profile) + p * HERO_SCROLL_ORBIT_DEGREES.toRadians()
    val elevation = (cinematicElevation(timeSeconds, profile) + p * HERO_SCROLL_RISE_DEGREES.toRadians())
        .coerceIn((-80f).toRadians(), 80f.toRadians())
    val distance = cinematicDistance(contentRadius, profile) * (1f - HERO_SCROLL_DOLLY * p)
    val horizontal = distance * cos(elevation)
    return Position(
        x = horizontal * sin(azimuth),
        y = distance * sin(elevation),
        z = horizontal * cos(azimuth),
    )
}

/** Lens focal length, in millimetres, giving [verticalFovDegrees] on Filament's 24 mm sensor. */
internal fun focalLengthForVerticalFov(verticalFovDegrees: Float): Double =
    12.0 / tan(verticalFovDegrees / 2.0 * PI / 180.0)

private fun Float.toRadians(): Float = (this * PI / 180.0).toFloat()

/**
 * The turntable's clock: advances only on presented frames while the hero is moving, and never by
 * more than [MAX_FRAME_SECONDS] at once, so a hero that was off screen resumes where it stopped
 * instead of jumping.
 */
internal class CinematicHeroClock {
    var seconds: Float = 0f
        private set
    private var previousNanos: Long = NO_FRAME

    fun pause() {
        previousNanos = NO_FRAME
    }

    fun frame(frameTimeNanos: Long): Float {
        if (previousNanos != NO_FRAME) {
            val delta = (frameTimeNanos - previousNanos) / 1_000_000_000f
            seconds += delta.coerceIn(0f, MAX_FRAME_SECONDS)
        }
        previousNanos = frameTimeNanos
        return seconds
    }

    companion object {
        const val MAX_FRAME_SECONDS = 0.1f
        private const val NO_FRAME = Long.MIN_VALUE
    }
}

@Composable
private fun CinematicHeroStage(
    anchor: HeroAnchor,
    height: Dp,
    modifier: Modifier,
    backdrop: Brush?,
    profile: CinematicCameraProfile,
    contentRadius: Float,
    parallax: Float,
    content: @Composable SceneScope.() -> Unit,
) {
    val heightPx = with(LocalDensity.current) { height.toPx() }
    val onScreen by remember(anchor, heightPx) {
        derivedStateOf { !anchor.top().isNaN() && anchor.scrolled() < heightPx }
    }
    val motionEnabled = remember {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()
    }
    val moving = onScreen && motionEnabled

    val engine = rememberEngine()
    val cameraNode = rememberCameraNode(engine) {
        focalLength = focalLengthForVerticalFov(profile.fovDegrees)
    }
    val clock = remember { CinematicHeroClock() }
    // Last pose written, so an unchanged pose is never re-written: every camera write asks for a
    // frame, and re-writing the same pose from each frame would keep a parked scene drawing.
    val lastPose = remember { floatArrayOf(Float.NaN, Float.NaN) }
    val applyPose = remember(anchor, cameraNode, profile, contentRadius, heightPx) {
        { seconds: Float ->
            val progress = heroScrollProgress(anchor.scrolled(), heightPx)
            if (seconds != lastPose[0] || progress != lastPose[1]) {
                lastPose[0] = seconds
                lastPose[1] = progress
                cameraNode.applyHeroEye(cinematicHeroEye(seconds, progress, profile, contentRadius))
            }
        }
    }
    LaunchedEffect(cameraNode, profile) {
        cameraNode.focalLength = focalLengthForVerticalFov(profile.fovDegrees)
    }
    // Scroll moves the camera even when the turntable is still (reduced motion, parked loop).
    LaunchedEffect(applyPose) {
        snapshotFlow { heroScrollProgress(anchor.scrolled(), heightPx) }
            .collect { applyPose(clock.seconds) }
    }
    LaunchedEffect(moving) {
        clock.pause()
    }

    Box(modifier.fillMaxSize().clipToBounds().clearAndSetSemantics { }) {
        // Follows the spacer one-for-one, clips to it, and fades its bottom edge out.
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .graphicsLayer {
                    val top = anchor.top()
                    alpha = if (top.isNaN()) 0f else 1f
                    translationY = if (top.isNaN()) 0f else top
                    clip = true
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            HERO_FADE_START to Color.Black,
                            1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            // Lags behind the scroll: the parallax.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val scrolled = anchor.scrolled()
                        translationY = if (scrolled < heightPx) scrolled * parallax else 0f
                    }
                    .then(if (backdrop != null) Modifier.background(backdrop) else Modifier),
            ) {
                SceneView(
                    modifier = Modifier.fillMaxSize(),
                    surfaceType = SurfaceType.TextureSurface,
                    isOpaque = false,
                    engine = engine,
                    cameraNode = cameraNode,
                    cameraManipulator = null,
                    onGestureListener = null,
                    frameRatePolicy = if (moving) {
                        FrameRatePolicy.Continuous(maxFps = HERO_MAX_FPS)
                    } else {
                        FrameRatePolicy.OnDemand()
                    },
                    onFrame = { frameTimeNanos ->
                        applyPose(if (moving) clock.frame(frameTimeNanos) else clock.seconds)
                    },
                    content = content,
                )
            }
        }
    }
}

private fun CameraNode.applyHeroEye(eye: Position) {
    position = eye
    lookAt(Position(0f, 0f, 0f), smooth = false)
}
