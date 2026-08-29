package io.github.sceneview.demo.demos

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import dev.romainguy.kotlin.math.Float4
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.demo.common.QaCameraBackdrop
import io.github.sceneview.demo.common.qaCameraBackdropEnabled
import io.github.sceneview.demo.common.qaCameraBackdropSurfaceType
import io.github.sceneview.demo.common.rememberQaCameraBackdropActive
import io.github.sceneview.ar.arcore.getProjectionTransform
import io.github.sceneview.ar.arcore.transform
import io.github.sceneview.ar.arcore.viewTransform
import io.github.sceneview.demo.AssetSourceState
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.demo.ARCameraInitScrim
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.common.rememberFileModelInstance
import io.github.sceneview.demo.R
import io.github.sceneview.demo.sketchfab.AssetSourceProbe
import io.github.sceneview.demo.sketchfab.SampleAssets
import io.github.sceneview.demo.sketchfab.SketchfabAssetResolver
import io.github.sceneview.demo.sketchfab.SketchfabConfig
import io.github.sceneview.demo.sketchfab.SketchfabSlug
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Transform
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * Catch-the-flyers AR demo — four winged models circle the user, and the user taps
 * them out of the air.
 *
 * On the first tracked AR frame, an [Anchor] is created at world origin (the camera's
 * pose at session-start in ARCore — i.e. wherever the user is standing). Four model
 * instances are placed as children of an [AnchorNode] in a circle of radius 1.5 m,
 * one per quadrant (360° / 4 = 90° apart). Each flyer has:
 *
 * - its own **orbital speed** (0.10–0.18 rad/s) so the ring does not look rigid,
 *   inside a band slow enough that a tap can actually land on a moving target;
 * - a **baked animation** (streamed animated creatures), oriented along the orbit
 *   tangent so it flies the orbit;
 * - a **distinct height** between -0.35 m and +0.35 m relative to eye level.
 *
 * The user stays fixed in AR and turns around to find and catch each flyer.
 *
 * Animation is driven by `withFrameNanos` so the orbit advances at the display's
 * refresh rate. Per-recompose state (`orbitSeconds`) is hoisted via `mutableLongStateOf`
 * so the `ModelNode` positions/rotations recompute every frame.
 *
 * ### The catch mechanic (issue #3341)
 *
 * A transparent tap layer sits on top of the AR viewport. Every frame the demo projects
 * each flyer to screen space ([projectTarget]); a tap picks the nearest flyer within
 * [CATCH_RADIUS_DP] ([nearestCatchTarget]). A caught flyer freezes at its capture angle,
 * loses its off-screen arrow and gets a scale bump, the device buzzes, and a ripple ring
 * confirms the hit — a missed tap draws a dim ring, so the input is always acknowledged.
 * Once all four are caught, a tap anywhere releases them and they resume from exactly
 * where they were frozen. All of the arithmetic lives in pure internal functions with
 * JVM tests, because the emulator can never run an AR session (#2754).
 *
 * The demo used to orbit **eight** models — four streamed creatures interleaved with four
 * static bundled props — with an arrow per off-screen model and no way to interact with
 * any of them. #3341 is the QA report for exactly that: too many models to follow, and a
 * "catch" that could never succeed because nothing was catchable.
 *
 * ### Streaming pipeline (Stage 2, issue #1152)
 *
 * All four flyers are streamed via [SketchfabAssetResolver] from the curated `solar`
 * category in [SampleAssets]. The resolver never fails a slot: a missing
 * [SketchfabConfig.apiKey] (App Store / first-launch) short-circuits to the bundled
 * fallback declared in the registry, and so does every *runtime* failure with a key
 * present — no network, aeroplane mode, a stale key, a 4xx, exhausted retries. So the demo
 * always renders four flyers, and a configured key is never evidence that any of them
 * streamed (which is why the asset-source pill asks the resolved files instead — #2953).
 * The four registry fallbacks are pairwise distinct (#3341 — they all pointed at the same
 * soldier before), so keyless mode still shows four different companions.
 */
private data class Planet(
    /**
     * Streamed Sketchfab slug (`solar` category) when non-null. The resolver
     * gives us either the downloaded GLB or the registered bundled fallback —
     * the demo just hands the resulting [File] to `rememberModelInstance`.
     */
    val streamedSlug: SketchfabSlug? = null,
    /**
     * Bundled asset path under `assets/`. Used when [streamedSlug] is null. No slot
     * uses it since #3341 cut the formation down to four streamed flyers, but the
     * branch stays: it is how a slot is pinned to an APK asset with no registry entry,
     * and the demo's loading code still honours it.
     */
    val bundledAssetPath: String? = null,
    val scaleToUnits: Float,
    val initialAngleRad: Float,
    val orbitSpeed: Float,   // rad/s around the user
    val spinSpeed: Float,    // rad/s local Y axis — ignored when hasBakedAnimation = true
    val height: Float,       // y offset, m
    // True when the model has its own baked animation (wing flap, walk cycle, etc.).
    // For these, we skip the local Y spin and instead orient the model along the
    // orbit tangent so it "flies/walks the orbit" naturally — the baked animation
    // does the rest of the movement.
    val hasBakedAnimation: Boolean,
) {
    init {
        require((streamedSlug == null) != (bundledAssetPath == null)) {
            "Planet must define exactly one of streamedSlug or bundledAssetPath."
        }
    }
}

// Four flying companions, one per quadrant of a 1.5 m ring — the whole formation
// (#3341). It used to be eight: four streamed creatures interleaved with four bundled
// props (helmet, lantern, toy car, walking soldier). Eight objects on eight heights at
// eight speeds is not a solar system, it is a crowd: with one edge arrow per off-screen
// object, five to seven arrows pointed in five to seven directions at once and there
// was no way to tell which one was worth turning toward. Four is the number that stays
// legible — at most three arrows on screen, each one a real invitation.
//
// Every slot now *flies*. The bundled props were the other half of the complaint: the
// designated "catch me" target was a toy car spinning in mid-air, and a walking soldier
// stepping through empty space reads as a bug, not as a companion. All four entries are
// streamed animated creatures from the `solar` category of SampleAssets, looked up by
// uid so a registry re-ordering cannot silently re-tune a slot. Offline they fall back
// to four *distinct* bundled GLBs (#3341 fixed the registry, which pointed all four at
// the same soldier): still four separate companions, just not flying ones.
//
// Speeds are 0.10–0.18 rad/s (they were 0.05–0.30). The fast end was the second half of
// "you never manage to catch one": at 0.30 rad/s on a 1.5 m ring an object crosses a
// phone-width of view in about a second, which is not a target, it is a glimpse. The
// spread is kept — a ring where everything moves at the same rate looks rigid — just
// inside a band where a tap can land.
private val ORBITAL_PLANETS: List<Planet> = run {
    val fantasyButterfly = SampleAssets.byUid["0f24b085e8654e4db09c2fe681a79e3f"]
    val flutteringButterfly = SampleAssets.byUid["80f8d9a6dadc411e89ca366cb0cfb0d9"]
    val animatedButterfly = SampleAssets.byUid["d4fbcbaab845402999f30c5aa75851e6"]
    val butterflySwarm = SampleAssets.byUid["8ca3b9aa82694e6b8bc53a69b4529539"]

    // Quadrant spacing (90° apart), heights spread over ±0.35 m around eye level so no
    // two flyers overlap from a standing viewpoint, and one distinct speed each.
    listOf(
        // Slot 0 — the swarm, biggest and slowest: the easy first catch, straight ahead
        // of wherever the user was looking when the anchor dropped.
        Planet(
            streamedSlug = butterflySwarm,
            scaleToUnits = 0.35f,
            initialAngleRad = 0f,
            orbitSpeed = 0.10f,
            spinSpeed = 0f,
            height = 0.05f,
            hasBakedAnimation = true,
        ),
        // Slot 1 — fantasy butterfly, high and to the side.
        Planet(
            streamedSlug = fantasyButterfly,
            scaleToUnits = 0.30f,
            initialAngleRad = 2f * PI.toFloat() / 4f * 1,
            orbitSpeed = 0.13f,
            spinSpeed = 0f,
            height = 0.35f,
            hasBakedAnimation = true,
        ),
        // Slot 2 — fluttering butterfly, below eye level.
        Planet(
            streamedSlug = flutteringButterfly,
            scaleToUnits = 0.22f,
            initialAngleRad = 2f * PI.toFloat() / 4f * 2,
            orbitSpeed = 0.16f,
            spinSpeed = 0f,
            height = -0.20f,
            hasBakedAnimation = true,
        ),
        // Slot 3 — smallest and fastest, the one worth chasing last.
        Planet(
            streamedSlug = animatedButterfly,
            scaleToUnits = 0.18f,
            initialAngleRad = 2f * PI.toFloat() / 4f * 3,
            orbitSpeed = 0.18f,
            spinSpeed = 0f,
            height = -0.35f,
            hasBakedAnimation = true,
        ),
    )
}

private const val ORBIT_RADIUS = 1.5f

/**
 * Radius, in dp, of the tap-to-catch hit disc around a flyer's projected screen
 * position — see [nearestCatchTarget].
 *
 * It is deliberately far larger than the models themselves. The mechanic the QA report
 * asked for ("you never manage to catch one" — #3341) is *catching a moving thing on a
 * hand-held phone*: the target drifts between the moment the finger starts moving and
 * the moment it lands, the phone drifts too, and a 0.18-unit butterfly at 1.5 m covers
 * well under 48 dp on screen. A mesh-exact ray cast would be technically correct and
 * unplayable. 72 dp is 1.5× Material 3's 48 dp minimum touch target, and that minimum
 * is the floor for a *stationary* control.
 */
private const val CATCH_RADIUS_DP = 72f

/**
 * How much a caught flyer grows, as a multiplier on its `scaleToUnits`.
 *
 * The freeze alone is ambiguous on a hand-held phone — the whole scene is drifting, so
 * "it stopped" is not something the eye can assert. A size change is unmistakable, and
 * 1.25× is enough to read without turning a butterfly into scenery.
 */
private const val CATCH_SCALE_BUMP = 1.25f

/** Lifetime of the tap-feedback ring, in seconds. */
private const val CATCH_FEEDBACK_SECONDS = 0.45f

/** Radius the tap-feedback ring expands to, as a multiple of [CATCH_RADIUS_DP]. */
private const val CATCH_FEEDBACK_MAX_RADIUS_FACTOR = 1.6f

/**
 * The last tap on the AR viewport, kept just long enough to draw a ring at it.
 *
 * Both outcomes are drawn, and that is the point: the original complaint was "you feel
 * you never manage to catch one" (#3341). A tap that does nothing at all is
 * indistinguishable from a tap the app never received, so a miss gets its own dim ring —
 * the user learns the aim was off, not that the demo is broken.
 *
 * @param x tap position in viewport pixels.
 * @param y tap position in viewport pixels.
 * @param startSeconds value of the demo's orbit clock when the tap landed; the ring's
 *   progress is `(now - startSeconds) / CATCH_FEEDBACK_SECONDS`. Reusing the orbit clock
 *   rather than an animation of its own keeps the ring on the same frame callback as
 *   everything else in the demo.
 * @param hit true when the tap caught a flyer.
 */
private data class CatchFeedback(
    val x: Float,
    val y: Float,
    val startSeconds: Float,
    val hit: Boolean,
)

/**
 * Near/far clip planes (metres) used when asking ARCore for the camera projection
 * matrix. The values are not visually critical here — they only affect the depth
 * range of the projection, and we discard depth (we keep only the clip-space sign
 * and the x/y direction). The range simply has to bracket the 1.5 m orbit radius.
 */
private const val PROJECTION_NEAR = 0.05f
private const val PROJECTION_FAR = 30f

/**
 * On-screen state for the directional indicator that points at an off-screen target
 * (issue #1482, #3269). `null` whenever the target is comfortably inside the camera
 * frustum — in that case no arrow is drawn and the user can simply see the model.
 *
 * @param angleRad direction, in radians, from the screen centre toward the target,
 *   measured in Compose screen space (0 = +X / right, π/2 = +Y / down). Used both to
 *   place the arrow on the viewport edge and to rotate the arrow glyph.
 * @param distanceMeters straight-line distance from the camera to the target, in
 *   metres. Shown next to the arrow so the user knows how far to walk/turn (#3269).
 */
internal data class OffscreenTarget(val angleRad: Float, val distanceMeters: Float)

/**
 * Pure projection of [targetWorld] into the camera described by [viewProjection] /
 * [cameraPosition]. Returns an [OffscreenTarget] with the screen-space direction and
 * distance toward it, or `null` when the target is inside the camera frustum.
 *
 * No ARCore types involved — [viewProjection] is `projection · view` and
 * [cameraPosition] is the camera's world-space translation — so this is plain JVM math,
 * unit-testable without a device or an ARCore mock (#3269, see
 * `OffscreenTargetProjectionTest`).
 *
 * The projection is the standard `clip = viewProjection · worldPoint`:
 *
 * - `clip.w <= 0` means the point is **behind** the camera. The perspective divide
 *   would mirror x/y, so we negate the clip x/y to recover the true direction and
 *   always treat the point as off-screen.
 * - otherwise `ndc = clip.xy / clip.w` is in `[-1, 1]` when on-screen. The point is
 *   considered visible only when both components are within `[-1, 1]`.
 *
 * The returned angle is in Compose screen space, where +Y points **down** — hence the
 * `-ndcY` (NDC / OpenGL Y points up).
 */
internal fun projectOffscreenTarget(
    viewProjection: Transform,
    cameraPosition: Position,
    targetWorld: Position,
): OffscreenTarget? =
    // Viewport size is irrelevant to the off-screen answer (it only scales screenX/Y),
    // so a 1×1 viewport keeps this overload free of a size parameter it never needed.
    projectTarget(viewProjection, cameraPosition, targetWorld, 1f, 1f).asOffscreenTarget()

/**
 * Full screen-space state of one orbiting flyer: where it lands on the viewport, whether
 * it is visible at all, and the edge-arrow direction/distance to use when it is not.
 *
 * This is the superset [projectOffscreenTarget] used to return half of. The catch
 * mechanic (#3341) needs the *on-screen pixel position* to hit-test a tap against, and
 * the arrow overlay needs the direction/distance — both fall out of the same single
 * projection, so they are computed together, once per flyer per AR frame.
 *
 * @param screenX horizontal position in viewport pixels. Meaningless (and unused) when
 *   [onScreen] is false — a point behind the camera has no screen position.
 * @param screenY vertical position in viewport pixels, +Y down (Compose convention).
 * @param onScreen true when the flyer is inside the camera frustum, i.e. tappable.
 * @param angleRad direction from the screen centre toward the flyer, in Compose screen
 *   space (0 = +X / right, π/2 = +Y / down).
 * @param distanceMeters straight-line camera-to-flyer distance, in metres.
 */
internal data class ProjectedTarget(
    val screenX: Float,
    val screenY: Float,
    val onScreen: Boolean,
    val angleRad: Float,
    val distanceMeters: Float,
) {
    /** The arrow-overlay view of this projection: `null` while the flyer is visible. */
    fun asOffscreenTarget(): OffscreenTarget? =
        if (onScreen) null else OffscreenTarget(angleRad, distanceMeters)
}

/**
 * Pure projection of [targetWorld] into the camera described by [viewProjection] /
 * [cameraPosition], onto a [viewportWidth] × [viewportHeight] viewport.
 *
 * No ARCore types involved — [viewProjection] is `projection · view` and
 * [cameraPosition] is the camera's world-space translation — so this is plain JVM math,
 * unit-testable without a device or an ARCore mock (#3269, #3341; see
 * `OffscreenTargetProjectionTest` and `CatchTargetTest`). That matters more here than
 * usual: the emulator can never run an AR session (#2754), so pure functions plus JVM
 * tests are the only way any of this is verifiable off a physical device.
 */
internal fun projectTarget(
    viewProjection: Transform,
    cameraPosition: Position,
    targetWorld: Position,
    viewportWidth: Float,
    viewportHeight: Float,
): ProjectedTarget {
    val clip = viewProjection * Float4(targetWorld, w = 1.0f)

    val behindCamera = clip.w <= 0f
    // Guard against a near-zero w (target almost exactly on the camera plane) which
    // would blow the divide up — treat it as "behind" and use raw clip x/y direction.
    val safeW = if (abs(clip.w) < 1e-4f) 1e-4f else clip.w
    val ndcX = if (behindCamera) -clip.x else clip.x / safeW
    val ndcY = if (behindCamera) -clip.y else clip.y / safeW

    val onScreen = !behindCamera && ndcX in -1f..1f && ndcY in -1f..1f

    val dx = targetWorld.x - cameraPosition.x
    val dy = targetWorld.y - cameraPosition.y
    val dz = targetWorld.z - cameraPosition.z

    return ProjectedTarget(
        // NDC [-1, 1] → pixels, flipping Y (NDC / OpenGL Y points up, Compose Y down).
        screenX = (ndcX + 1f) * 0.5f * viewportWidth,
        screenY = (1f - ndcY) * 0.5f * viewportHeight,
        onScreen = onScreen,
        angleRad = atan2(-ndcY, ndcX),
        distanceMeters = sqrt(dx * dx + dy * dy + dz * dz),
    )
}

/**
 * Index of the flyer a tap at ([tapX], [tapY]) catches, or `null` for a miss.
 *
 * A flyer is catchable when it is on screen, not already caught, and its projected
 * position is within [radiusPx] of the tap. When several qualify — they do overlap, the
 * ring is only 1.5 m across — the **nearest** one wins, so a tap aimed between two
 * flyers resolves the way the user expects rather than by list order.
 *
 * Distance is compared squared: no `sqrt`, and no behaviour change.
 */
internal fun nearestCatchTarget(
    projected: Map<Int, ProjectedTarget>,
    tapX: Float,
    tapY: Float,
    radiusPx: Float,
    caught: Set<Int>,
): Int? {
    val radiusSq = radiusPx * radiusPx
    var bestIndex: Int? = null
    var bestDistanceSq = Float.MAX_VALUE
    projected.forEach { (index, target) ->
        if (!target.onScreen || index in caught) return@forEach
        val dx = target.screenX - tapX
        val dy = target.screenY - tapY
        val distanceSq = dx * dx + dy * dy
        if (distanceSq <= radiusSq && distanceSq < bestDistanceSq) {
            bestDistanceSq = distanceSq
            bestIndex = index
        }
    }
    return bestIndex
}

/**
 * Orbit angle of a flyer at [seconds], in radians, wrapped to `[0, 2π)`.
 *
 * [angleOffsetRad] is the per-flyer phase shift accumulated by catch/release cycles (see
 * [releaseAngleOffset]); it is 0 for a flyer that has never been caught. The modulo
 * before the caller's `sin`/`cos` keeps Float precision over a long session (#978).
 */
internal fun orbitAngleRad(
    initialAngleRad: Float,
    orbitSpeed: Float,
    angleOffsetRad: Float,
    seconds: Float,
): Float {
    val twoPi = 2f * PI.toFloat()
    val raw = (initialAngleRad + angleOffsetRad + orbitSpeed * seconds) % twoPi
    return if (raw < 0f) raw + twoPi else raw
}

/**
 * Phase offset that makes a released flyer resume from exactly where it was frozen.
 *
 * Catching a flyer stops it in place while the clock keeps running, so releasing it
 * naively would teleport it forward by however long it was held. Solving
 * `orbitAngleRad(initial, speed, offset, seconds) == frozenAngleRad` for `offset` gives
 * the shift that makes the release continuous.
 */
internal fun releaseAngleOffset(
    initialAngleRad: Float,
    orbitSpeed: Float,
    seconds: Float,
    frozenAngleRad: Float,
): Float {
    val twoPi = 2f * PI.toFloat()
    val raw = (frozenAngleRad - initialAngleRad - orbitSpeed * seconds) % twoPi
    return if (raw < 0f) raw + twoPi else raw
}

/**
 * ARCore-facing wrapper around [projectTarget]: pulls the view/projection matrix and
 * camera position off [frame] and delegates the actual math.
 */
private fun computeProjectedTarget(
    frame: Frame,
    targetWorld: Position,
    viewportWidth: Float,
    viewportHeight: Float,
): ProjectedTarget? {
    val camera = frame.camera
    if (camera.trackingState != TrackingState.TRACKING) return null

    val viewProjection =
        camera.getProjectionTransform(PROJECTION_NEAR, PROJECTION_FAR) * camera.viewTransform
    val cameraPose = camera.pose
    val cameraPosition = Position(cameraPose.tx(), cameraPose.ty(), cameraPose.tz())
    return projectTarget(
        viewProjection, cameraPosition, targetWorld, viewportWidth, viewportHeight,
    )
}

@Composable
fun OrbitalARDemo(onBack: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo
    // with `--es ar_playback_file <path>` (#1576). `null` for every normal launch - see
    // `rememberArPlaybackDataset` - so live AR is completely unchanged for real users.
    val arPlaybackDataset = rememberArPlaybackDataset()
    val context = LocalContext.current

    // Resolve every streamed slug exactly once per composition. The resolver
    // returns the streamed GLB or the bundled fallback (we never block on the
    // network — see `SketchfabAssetResolver.resolve` Kdoc). The value flips from
    // `null` (download / fallback-copy still running on IO) to a real [File]
    // once the resolver returns. Since #3341 every slot is streamed, so no slot
    // contributes `null` here for the bundled-only reason; the branch stays because
    // [Planet.bundledAssetPath] is still a supported way to define a slot.
    //
    // ORBITAL_PLANETS is a `val` constant, so the call order of `produceState`
    // is stable across recompositions — Compose's positional memoisation stays
    // valid.
    val streamedFiles: List<File?> = ORBITAL_PLANETS.mapIndexed { index, planet ->
        val slug = planet.streamedSlug
        if (slug == null) {
            null
        } else {
            produceState<File?>(initialValue = null, key1 = slug.uid, key2 = index) {
                value = runCatching {
                    SketchfabAssetResolver.getInstance(context).resolve(slug)
                }.getOrNull()
            }.value
        }
    }

    // The user's initial-pose anchor. Created lazily on the first tracked frame, since
    // ARCore world origin is undefined until tracking begins. After that, all four flyers
    // ride this anchor — turning the phone shows them passing by in world space.
    var userAnchor by remember { mutableStateOf<Anchor?>(null) }
    var isTracking by remember { mutableStateOf(false) }
    // ARCore verdict (#3374): non-null once we know AR cannot start on this device. The
    // SDK draws the explanation over the scene; the demo's own status pill must then stop
    // claiming AR is initializing, because it never will.
    var arCoreAvailability by remember { mutableStateOf<ARCoreAvailability?>(null) }
    // Cover the jet-black ARSceneView surface until ARCore delivers its first camera
    // frame, so the ~1–3 s warm-up on entry doesn't read as a frozen screen (#2484).
    var cameraReady by remember { mutableStateOf(false) }
    // QA camera backdrop (#3308) — see TapToPlaceArSession.
    val cameraStream = rememberARCameraStream(materialLoader)
    val qaBackdrop = rememberQaCameraBackdropActive(cameraReady)

    // Elapsed seconds since the anchor was created, advanced by withFrameNanos. Drives
    // orbit + spin animation. Stored as nanos to avoid float-precision drift over long
    // sessions (a 10 min orbital run would lose ms-resolution stored as plain Float).
    var orbitNanos by remember { mutableLongStateOf(0L) }

    LaunchedEffect(userAnchor) {
        if (userAnchor == null) return@LaunchedEffect
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (lastNanos != 0L) orbitNanos += (nanos - lastNanos)
                lastNanos = nanos
            }
        }
    }
    val orbitSeconds = orbitNanos / 1_000_000_000f

    // Directional indicator state (#1482, #3269). Keyed by planet index — holds an
    // entry for **every** planet currently outside the camera frustum, not just the
    // chase target, so the overlay draws one edge arrow (with distance) per off-screen
    // object and it stays up continuously until that specific object enters the
    // viewport. Recomputed once per AR frame in onSessionUpdated.
    var offscreenTargets by remember { mutableStateOf<Map<Int, OffscreenTarget>>(emptyMap()) }
    // Viewport size in pixels, captured from the Compose layout so the indicator can
    // be clamped to the real edge of the AR surface.
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    // ---- Catch mechanic (#3341) -------------------------------------------------------
    //
    // Where each flyer landed on screen this frame, keyed by slot. `offscreenTargets`
    // below is derived from it, and a tap is resolved against it by [nearestCatchTarget].
    // Recomputed once per AR frame in onSessionUpdated — a tap can only ever be as fresh
    // as the last frame, which at 30–60 fps is well inside the tolerance a 72 dp disc buys.
    var projectedTargets by remember { mutableStateOf<Map<Int, ProjectedTarget>>(emptyMap()) }
    // Caught flyers, slot -> the orbit angle they were caught at. Presence in this map IS
    // the caught state, and the angle is what freezes them: the render and the projection
    // both read it instead of the clock, so a caught flyer holds still exactly where the
    // user grabbed it.
    var caughtAngles by remember { mutableStateOf<Map<Int, Float>>(emptyMap()) }
    // Per-slot angular offset applied after a release. Releasing must not teleport the
    // flyer back to where the free-running clock says it would be — that snap is the same
    // discontinuity the catch was supposed to remove — so each release records the offset
    // that makes the orbit resume from the frozen angle. See [releaseAngleOffset].
    var angleOffsets by remember { mutableStateOf<Map<Int, Float>>(emptyMap()) }
    // The last tap, so the overlay can draw a ring at it. See [CatchFeedback].
    var catchFeedback by remember { mutableStateOf<CatchFeedback?>(null) }

    val allCaught = caughtAngles.size == ORBITAL_PLANETS.size

    // Single source of truth for "where is slot `index` on its orbit right now", shared by
    // the projection (which decides what a tap hits) and the ModelNode (which decides what
    // the user sees). They must not drift apart: a hitbox that sits anywhere other than the
    // model on screen is precisely the "I tapped it and nothing happened" complaint.
    fun angleOf(index: Int): Float {
        caughtAngles[index]?.let { return it }
        val planet = ORBITAL_PLANETS[index]
        return orbitAngleRad(
            initialAngleRad = planet.initialAngleRad,
            orbitSpeed = planet.orbitSpeed,
            angleOffsetRad = angleOffsets[index] ?: 0f,
            seconds = orbitSeconds,
        )
    }

    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val catchRadiusPx = with(density) { CATCH_RADIUS_DP.dp.toPx() }

    // `rememberUpdatedState` because the pointerInput below is keyed on Unit — it is
    // installed once and must never be torn down mid-gesture, so it has to read the
    // *current* state through this holder rather than close over the first frame's values.
    val onViewportTap by rememberUpdatedState<(Offset) -> Unit> { offset ->
        if (allCaught) {
            // Everything is caught: the next tap releases the whole formation, so the
            // demo is replayable without leaving and re-entering it (and without a
            // "Reset" button competing with the AR view for space).
            angleOffsets = caughtAngles.mapValues { (index, frozenAngle) ->
                val planet = ORBITAL_PLANETS[index]
                releaseAngleOffset(
                    initialAngleRad = planet.initialAngleRad,
                    orbitSpeed = planet.orbitSpeed,
                    seconds = orbitSeconds,
                    frozenAngleRad = frozenAngle,
                )
            }
            caughtAngles = emptyMap()
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            catchFeedback = CatchFeedback(offset.x, offset.y, orbitSeconds, hit = true)
        } else {
            val hit = nearestCatchTarget(
                projected = projectedTargets,
                tapX = offset.x,
                tapY = offset.y,
                radiusPx = catchRadiusPx,
                caught = caughtAngles.keys,
            )
            if (hit != null) {
                caughtAngles = caughtAngles + (hit to angleOf(hit))
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            catchFeedback = CatchFeedback(offset.x, offset.y, orbitSeconds, hit = hit != null)
        }
    }

    // Onboarding-dismiss state (#2481). The "Turn around and tap to catch" **banner
    // text only** is a first-launch nudge: it teaches the user to turn and watch the
    // formation pass by, then gets out of the way once the user has clearly engaged
    // (the QA tester: "là, il met encore la flèche alors qu'on est dessus"). `
    // onboardingDismissed` flips true once the nudge has served its purpose and stays
    // true (it's a rememberSaveable so a device rotation mid-session doesn't re-show
    // it). It is set true when EITHER:
    //  - the orbit is live and at least one flyer has come on-screen (the user
    //    successfully turned toward the formation — see onSessionUpdated), or
    //  - the onboarding window below elapses (timeout fallback, so the nudge always
    //    eventually clears even in the rare frame where nothing has streamed in yet).
    //
    // IMPORTANT (#3269): this flag must NEVER gate the off-screen directional arrows
    // themselves. The arrows are not onboarding — they are the only way to find an
    // orbiting object that has drifted outside the frustum, and issue #3269 was exactly
    // that: the arrow (and its distance) went quiet after this timeout even though
    // objects were still off-screen. Arrow visibility is derived purely from
    // `offscreenTargets`, one entry per off-screen object, independent of onboarding.
    var onboardingDismissed by rememberSaveable { mutableStateOf(false) }

    // Timeout fallback for the onboarding nudge: once the world anchor is locked, give
    // the user a short window to turn around, then dismiss regardless. Keyed on
    // `userAnchor == null` so the countdown only starts once the formation exists, and
    // skipped entirely if the user already engaged (target came on-screen) first.
    LaunchedEffect(userAnchor == null, onboardingDismissed) {
        if (userAnchor != null && !onboardingDismissed) {
            delay(12.seconds)
            onboardingDismissed = true
        }
    }

    // Per-demo offline indicator (#1152 Stage 3), MEASURED from the resolved files rather
    // than inferred from `SketchfabConfig.apiKey` (#2953). The old rule read "no key ⇒
    // Offline, key ⇒ Streamed once every slot holds a File", and the second half of that
    // is the guess: a key says nothing about whether the download succeeded. Every failure
    // path in `resolve` — no network, aeroplane mode, a stale key, a 4xx, the WAF, a
    // bounds-drifted asset, exhausted retries — hands back a *bundled fallback* File, which
    // is still a non-null File, so a keyed build with four stand-ins in orbit reported
    // "Streamed (cached)". Asking the file cannot be wrong that way.
    //
    // Whole-scene and pessimistic, like Multi-Model: one fallen-back planet reads "Offline
    // model" for the formation, and the pill never says which one swapped. See
    // [AssetSourceProbe] for the full rule.
    //
    // Only streamed slots are probed. Since #3341 that is all four of them, so the filter
    // is a no-op today; it stays because a bundled slot reads straight from `assets/` and
    // would have no origin question to answer.
    val streamedSlots = streamedFiles.filterIndexed { i, _ ->
        ORBITAL_PLANETS[i].streamedSlug != null
    }
    val assetSource: AssetSourceState? = if (streamedSlots.isEmpty()) {
        null
    } else {
        AssetSourceProbe.ofAll(
            resolvedFiles = streamedSlots,
            hasApiKey = SketchfabConfig.apiKey != null,
            loaded = streamedSlots.all { it != null },
        )
    }

    // Status pill text, mirroring the ARPlacement / ARInstantPlacement style. It carries
    // the two transient setup states (tracking, anchor lock), the "Turn around"
    // onboarding nudge, and — since #3341 — the catch score.
    //
    // The score is the one line that does NOT disappear with onboarding, and that is the
    // fix for "you feel you never manage to catch one": a mechanic with no running total
    // gives the user nothing to read their own progress from. It is also the only place
    // the release gesture is taught, at the exact moment it becomes available. Between a
    // dismissed nudge and the first catch the pill is still gone entirely — the orbit is
    // self-explanatory at that point and a permanent banner only clutters the AR view.
    val statusText = when {
        // ARCore will never start here — the SDK overlay explains why, so drop the pill
        // rather than stack a second, and now false, message on top of it (#3374).
        arCoreAvailability != null -> null
        !isTracking -> "Initializing AR — look around to start tracking"
        userAnchor == null -> "Locking world anchor…"
        allCaught -> "All ${ORBITAL_PLANETS.size} caught — tap anywhere to release"
        caughtAngles.isNotEmpty() ->
            "Caught ${caughtAngles.size} / ${ORBITAL_PLANETS.size} — tap the others"
        !onboardingDismissed ->
            "Turn around and tap to catch — ${ORBITAL_PLANETS.size} models flying"
        else -> null
    }

    // No Settings FAB: this demo has nothing to configure. The orbit runs
    // automatically and the status pill already tells the user what to do
    // ("Turn around and tap to catch"), so a settings sheet carrying a
    // lone paragraph of help text added only chrome (#1620 thread 1).
    DemoScaffold(
        title = stringResource(R.string.demo_ar_orbital_title),
        onBack = onBack,
        assetSource = assetSource,
        topOverlay = {
            if (statusText != null) {
                Surface(
                    // This demo is the one in its batch that shows an asset-source
                    // chip, and the pill's longest string ("Turn around — N models
                    // orbiting") is wide enough to reach the top-end corner the chip
                    // occupies. Reserving the chip's measured width keeps the two
                    // apart; the pill re-centres in what is left, which reads as
                    // centred and, unlike a hardcoded gutter, cannot go stale when
                    // the chip's label changes.
                    modifier = Modifier.padding(end = assetSourceChipReservedSpace),
                    color = Color.Black.copy(alpha = 0.7f),
                    contentColor = Color.White,
                    tonalElevation = 4.dp,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = statusText,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewportSize = it }
        ) {
            if (qaBackdrop) QaCameraBackdrop(seed = "ar-orbital")
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                isOpaque = !qaCameraBackdropEnabled(),
                surfaceType = qaCameraBackdropSurfaceType(),
                cameraStream = if (qaBackdrop) null else cameraStream,
                playbackDataset = arPlaybackDataset,
                planeRenderer = false,
                onARCoreAvailability = { arCoreAvailability = it },
                sessionConfiguration = { _: Session, config: Config ->
                    // Plane detection off — the formation lives in world space around the
                    // user, not on a plane. Disabling planes is cheaper and gives a cleaner
                    // visual (no overlay polygons in front of the orbiting models).
                    config.planeFindingMode = Config.PlaneFindingMode.DISABLED
                    config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                },
                onSessionUpdated = { session: Session, frame: Frame ->
                    cameraReady = true
                    isTracking = frame.camera.trackingState == TrackingState.TRACKING
                    // Drop the world-origin anchor on the first tracked frame. ARCore's
                    // world origin = the camera's pose at session-start, which is exactly
                    // what we want: the formation sits where the user is standing.
                    if (isTracking && userAnchor == null) {
                        userAnchor = runCatching {
                            session.createAnchor(Pose.IDENTITY)
                        }.getOrNull()
                    }
                    // Project **every** flyer's current world position into the camera,
                    // once per frame. One pass now feeds two consumers (#1482, #3269,
                    // #3341): the edge arrows, which need the off-screen subset, and the
                    // tap-to-catch hit test, which needs the on-screen screen positions.
                    // Projecting twice would let the hitbox and the arrow disagree about
                    // where a flyer is.
                    val anchor = userAnchor
                    val viewport = viewportSize
                    projectedTargets = if (anchor != null && isTracking &&
                        viewport != IntSize.Zero
                    ) {
                        ORBITAL_PLANETS.indices.mapNotNull { index ->
                            runCatching {
                                val planet = ORBITAL_PLANETS[index]
                                val targetAngle = angleOf(index)
                                // Target position in the AnchorNode's local frame —
                                // identical to the ModelNode `position` computed in the
                                // content block, because both call `angleOf`.
                                val local = Position(
                                    x = cos(targetAngle) * ORBIT_RADIUS,
                                    y = planet.height,
                                    z = sin(targetAngle) * ORBIT_RADIUS,
                                )
                                // Lift the local point into ARCore world space through
                                // the anchor pose, then project + frustum-test it.
                                val worldPoint = anchor.pose.transform *
                                    Float4(local, w = 1.0f)
                                computeProjectedTarget(
                                    frame,
                                    Position(worldPoint.x, worldPoint.y, worldPoint.z),
                                    viewport.width.toFloat(),
                                    viewport.height.toFloat(),
                                )?.let { index to it }
                            }.getOrNull()
                        }.toMap()
                    } else {
                        emptyMap()
                    }
                    // Caught flyers are frozen in front of the user, so they never need an
                    // arrow — and once the formation is caught the screen goes quiet, which
                    // is the reward. Arrows for the ones still loose stay up continuously,
                    // per object, until that object re-enters the viewport.
                    offscreenTargets = projectedTargets
                        .filterKeys { it !in caughtAngles }
                        .mapNotNull { (index, projected) ->
                            projected.asOffscreenTarget()?.let { index to it }
                        }
                        .toMap()
                    // Onboarding nudge dismisses the "Turn around" **banner text** for
                    // good the first time the user brings any flyer on-screen (#2481).
                    // This must not affect arrow visibility (#3269) — see the
                    // `onboardingDismissed` Kdoc above.
                    if (anchor != null && isTracking &&
                        projectedTargets.isNotEmpty() &&
                        projectedTargets.size != offscreenTargets.size
                    ) {
                        onboardingDismissed = true
                    }
                }
            ) {
                val anchor = userAnchor
                if (anchor != null) {
                    AnchorNode(anchor = anchor) {
                        ORBITAL_PLANETS.forEachIndexed { index, planet ->
                            // Bundled planets read straight from `assets/` via the
                            // asset-path overload of `rememberModelInstance`. Streamed
                            // planets resolve to an on-disk `File` and must be loaded
                            // through `ModelLoader.loadModelInstance` — the two-arg
                            // `rememberModelInstance(modelLoader, String)` would bind to
                            // the asset-path overload and feed the `file://` URI to
                            // `AssetManager.open`, throwing `FileNotFoundException` (#1422).
                            // The bundled / streamed split is a stable `val` per slot, so
                            // the conditional keeps Compose's positional memoisation valid.
                            // While the streamed File is still null (download in flight)
                            // we render nothing for that slot — the orbit formation
                            // rebuilds the moment the resolver returns.
                            val instance = if (planet.bundledAssetPath != null) {
                                rememberModelInstance(modelLoader, planet.bundledAssetPath)
                            } else {
                                rememberFileModelInstance(
                                    modelLoader, streamedFiles.getOrNull(index)
                                )
                            }
                            if (instance != null) {
                                // Same `angleOf` the hit test uses, so the model and its
                                // hitbox can never disagree — and it is what holds a
                                // caught flyer still (#3341). Modulo lives inside
                                // `orbitAngleRad`, before sin/cos, so a long-running
                                // session (~290 h+) doesn't lose Float precision (#978).
                                val orbitAngle = angleOf(index)
                                val caught = index in caughtAngles
                                // Models with a baked animation (dragon, soldier) face the
                                // tangent of the orbit (= direction of motion) instead of
                                // spinning on Y — a flying dragon spinning on itself breaks
                                // the illusion. For position (R·cos θ, h, R·sin θ) on a CCW
                                // orbit, the tangent is (-sin θ, 0, cos θ); for a glTF model
                                // whose forward is -Z, that maps to a Y-rotation of θ + π.
                                val rotationY = if (planet.hasBakedAnimation) {
                                    Math.toDegrees(orbitAngle.toDouble()).toFloat() + 180f
                                } else {
                                    Math.toDegrees(
                                        (planet.spinSpeed * orbitSeconds).toDouble()
                                    ).toFloat() % 360f
                                }
                                ModelNode(
                                    modelInstance = instance,
                                    scaleToUnits = if (caught) {
                                        planet.scaleToUnits * CATCH_SCALE_BUMP
                                    } else {
                                        planet.scaleToUnits
                                    },
                                    position = Position(
                                        x = cos(orbitAngle) * ORBIT_RADIUS,
                                        y = planet.height,
                                        z = sin(orbitAngle) * ORBIT_RADIUS,
                                    ),
                                    rotation = Rotation(y = rotationY),
                                    autoAnimate = true,
                                )
                            }
                        }
                    }
                }
            }

            // Cover the still-black AR viewport until the first camera frame (#2484).
            ARCameraInitScrim(initializing = !cameraReady)

            // Off-screen target indicator (#1482, #3269) — one edge arrow per
            // off-screen planet, each labelled with the live distance to it, so the
            // user knows which way to turn and how far. A full-screen Canvas, so it
            // belongs to the viewport and stays here, under the scaffold's top slot and
            // its status pill.
            //
            // NOT gated on `onboardingDismissed` (#3269): that flag only controls the
            // "Turn around" banner text above. The arrows are the only way to locate an
            // off-screen orbiting object at any point in the session, so every entry in
            // `offscreenTargets` must keep drawing continuously — for as long as, and
            // only for as long as, that specific object stays outside the frustum.
            if (offscreenTargets.isNotEmpty() && viewportSize != IntSize.Zero) {
                OffscreenTargetArrows(targets = offscreenTargets.values.toList())
            }

            // Tap-to-catch layer (#3341). Last child of the viewport Box so it sits above
            // the AR surface and the arrows, and transparent so it changes nothing
            // visually. It is a plain full-screen gesture layer rather than per-node
            // touch handling on purpose: the hit test is a 72 dp disc around a projected
            // point, which is deliberately much larger than the mesh, and a mesh-exact
            // ray cast is what made this unplayable in the first place.
            //
            // Keyed on Unit — the handler must survive every recomposition (one per AR
            // frame) or a gesture in flight would be cancelled mid-tap. It reads current
            // state through `onViewportTap`'s rememberUpdatedState holder.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset -> onViewportTap(offset) }
                    }
            )

            // Tap-feedback ring, drawn on top of everything (#3341). Expands and fades
            // over CATCH_FEEDBACK_SECONDS, driven by the same orbit clock as the rest of
            // the demo.
            val feedback = catchFeedback
            if (feedback != null && viewportSize != IntSize.Zero) {
                val progress = (orbitSeconds - feedback.startSeconds) / CATCH_FEEDBACK_SECONDS
                if (progress in 0f..1f) {
                    CatchFeedbackRing(feedback = feedback, progress = progress)
                }
            }
        }
    }
}

/**
 * The expanding ring drawn at the user's last tap (issue #3341).
 *
 * Two states, and the miss state is the important one: a tap that hits nothing still
 * draws — dimmer, thinner, and without the filled centre — because "nothing happened at
 * all" is exactly what the original report described. A visible miss tells the user the
 * app saw the tap and the aim was off, which is a thing they can correct.
 *
 * @param progress 0 at the tap, 1 when the ring has finished; the radius eases outward
 *   and the alpha fades linearly to nothing.
 */
@Composable
private fun CatchFeedbackRing(feedback: CatchFeedback, progress: Float) {
    val maxRadius = with(LocalDensity.current) {
        (CATCH_RADIUS_DP * CATCH_FEEDBACK_MAX_RADIUS_FACTOR).dp.toPx()
    }
    val strokeWidth = with(LocalDensity.current) { (if (feedback.hit) 4f else 2f).dp.toPx() }
    // Ease-out: fast at the start, so the ring reads as a response to the finger rather
    // than as an animation that happens to begin near it.
    val eased = 1f - (1f - progress) * (1f - progress)
    val radius = maxRadius * (0.35f + 0.65f * eased)
    val alpha = (1f - progress) * (if (feedback.hit) 0.9f else 0.45f)
    val color = if (feedback.hit) {
        SceneViewTokens.ArOverlay.accentGuidance
    } else {
        SceneViewTokens.ArOverlay.onScrim
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawCircle(
            color = color.copy(alpha = alpha),
            radius = radius,
            center = Offset(feedback.x, feedback.y),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
    }
}

/**
 * Full-screen [Canvas] overlay that draws one directional arrow per entry in [targets]
 * on the viewport edge, each pointing toward an off-screen orbiting object and labelled
 * with the live distance to it (issue #1482, #3269, #3304).
 *
 * Each arrow is placed by casting a ray from the screen centre in its target's
 * direction (Compose screen space: 0 = right, π/2 = down) and clamping the hit point to
 * a rounded-rectangle inset from the viewport edge, then rotating the glyph to that same
 * direction. The distance label sits behind the glyph, in its own scrim pill, formatted
 * with the device locale's decimal separator.
 *
 * **The glyph's silhouette is the whole point** (#3304). It is a shaft + head arrow,
 * ~2:1 long, so head and tail are told apart at a glance; every layer of it is
 * shape-following, because the previous version drew the triangle inside a
 * rotationally-symmetric translucent disc *wider than the triangle itself* — the disc
 * won the silhouette and the indicator read as a dot, with no readable direction. Never
 * put a symmetric shape behind a directional one.
 *
 * Colours come from `DESIGN.md`'s AR coaching overlay tokens, not from the Material
 * scheme, and deliberately do **not** flip with the app theme: what this is read against
 * is an arbitrary camera frame, never `surface`. `accentGuidance` is the token for
 * "waiting on the user to move the phone", which is exactly what this arrow says.
 *
 * @param targets one entry per currently off-screen object — arbitrary order, drawn
 *   independently so overlapping targets never hide one another's arrow.
 */
@Composable
internal fun OffscreenTargetArrows(targets: List<OffscreenTarget>) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas

        val centerX = width / 2f
        val centerY = height / 2f

        // Keep every arrow fully inside the viewport: inset the clamp rectangle by
        // enough to fit the glyph + a small margin, and never let the inset collapse
        // past the centre on a very small surface.
        val margin = SceneViewTokens.Space.x2l.toPx()
        val halfW = max(1f, centerX - margin)
        val halfH = max(1f, centerY - margin)

        val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            // `this.` is load-bearing: bare `color = ...` here resolves against a
            // Compose `Color` in scope rather than this `Paint` receiver's `color: Int`.
            this.color = SceneViewTokens.ArOverlay.onScrim.toArgb()
            textSize = 14.sp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val placements = declutterOffscreenArrowPlacements(
            targets = targets,
            centerX = centerX,
            centerY = centerY,
            halfW = halfW,
            halfH = halfH,
            minSpacingPx = MIN_ARROW_SPACING_DP.dp.toPx(),
        )

        for (placement in placements) {
            drawOffscreenTargetArrow(
                target = placement.target,
                arrowX = placement.x,
                arrowY = placement.y,
                labelPaint = labelPaint,
            )
        }
    }
}

/**
 * Minimum on-screen gap, in dp, kept between two off-screen arrows landing on the same
 * viewport edge. Below this the triangle glyphs overlap into one illegible blob and the
 * distance labels print on top of each other. Two orbiting objects can transiently align
 * behind one another from the camera's viewpoint — their true bearings (`angleRad`) end
 * up a fraction of a degree apart, which used to place both arrows within a few px of
 * each other on the same edge (device QA on a Pixel 4a hit this live — #3296 follow-up,
 * see [declutterOffscreenArrowPlacements]).
 */
private const val MIN_ARROW_SPACING_DP = 64

/** Which viewport edge an off-screen arrow lands on. */
private enum class ArrowEdge { LEFT, RIGHT, TOP, BOTTOM }

/** Final screen-space position for one [OffscreenTarget]'s arrow + label. */
private data class ArrowPlacement(val target: OffscreenTarget, val x: Float, val y: Float)

/** A target's raw (pre-declutter) hit point on the clamp rectangle, decomposed by edge. */
private data class RawArrowHit(
    val target: OffscreenTarget,
    val edge: ArrowEdge,
    /** Position along the edge — the Y clamp for LEFT/RIGHT, the X clamp for TOP/BOTTOM. */
    val along: Float,
    /** The edge's fixed coordinate — X for LEFT/RIGHT, Y for TOP/BOTTOM. */
    val fixed: Float,
)

/**
 * Casts every target's ray to the clamp rectangle (same math [drawOffscreenTargetArrow]
 * used to do inline), then spreads out any that land within [minSpacingPx] of another on
 * the *same* edge — the only case that can actually overlap, since the four edges never
 * touch except at corners. Arrow **rotation** stays exactly the target's true bearing;
 * only the drawn position is nudged, the standard trade-off radar-style HUDs make to keep
 * clustered indicators legible (issue #3296 follow-up).
 */
private fun declutterOffscreenArrowPlacements(
    targets: List<OffscreenTarget>,
    centerX: Float,
    centerY: Float,
    halfW: Float,
    halfH: Float,
    minSpacingPx: Float,
): List<ArrowPlacement> {
    val rawHits = targets.map { target ->
        val dirX = cos(target.angleRad)
        val dirY = sin(target.angleRad)
        val tX = if (dirX != 0f) halfW / abs(dirX) else Float.MAX_VALUE
        val tY = if (dirY != 0f) halfH / abs(dirY) else Float.MAX_VALUE
        if (tX <= tY) {
            val edge = if (dirX >= 0f) ArrowEdge.RIGHT else ArrowEdge.LEFT
            RawArrowHit(
                target = target,
                edge = edge,
                along = centerY + dirY * tX,
                fixed = centerX + if (dirX >= 0f) halfW else -halfW,
            )
        } else {
            val edge = if (dirY >= 0f) ArrowEdge.BOTTOM else ArrowEdge.TOP
            RawArrowHit(
                target = target,
                edge = edge,
                along = centerX + dirX * tY,
                fixed = centerY + if (dirY >= 0f) halfH else -halfH,
            )
        }
    }

    val placements = mutableListOf<ArrowPlacement>()
    for (edge in ArrowEdge.entries) {
        val bucket = rawHits.filter { it.edge == edge }.sortedBy { it.along }
        if (bucket.isEmpty()) continue
        val (lo, hi) = if (edge == ArrowEdge.LEFT || edge == ArrowEdge.RIGHT) {
            (centerY - halfH) to (centerY + halfH)
        } else {
            (centerX - halfW) to (centerX + halfW)
        }
        val declutteredAlong = declutter1D(bucket.map { it.along }, minSpacingPx, lo, hi)
        bucket.forEachIndexed { index, hit ->
            val along = declutteredAlong[index]
            placements += if (edge == ArrowEdge.LEFT || edge == ArrowEdge.RIGHT) {
                ArrowPlacement(hit.target, x = hit.fixed, y = along)
            } else {
                ArrowPlacement(hit.target, x = along, y = hit.fixed)
            }
        }
    }
    return placements
}

/**
 * Spreads already-ascending [values] apart so consecutive entries are at least
 * [minSpacing] px apart, then keeps the whole run inside `[lo, hi]`: first pushing later
 * entries forward, then — only if that pushed the run past `hi` — sliding everything back
 * so it ends exactly on `hi` (and, if the run is wider than `[lo, hi]`, back further so
 * the first entry lands on `lo`, spacing still preserved even if a few entries spill past
 * the nominal edge — better than any two of them stacking exactly on top of each other).
 */
private fun declutter1D(values: List<Float>, minSpacing: Float, lo: Float, hi: Float): List<Float> {
    if (values.size <= 1) return values
    val result = values.toMutableList()
    for (i in 1 until result.size) {
        val minAllowed = result[i - 1] + minSpacing
        if (result[i] < minAllowed) result[i] = minAllowed
    }
    val overflow = result.last() - hi
    if (overflow > 0f) {
        for (i in result.indices) result[i] -= overflow
        val deficit = lo - result.first()
        if (deficit > 0f) {
            for (i in result.indices) result[i] += deficit
        }
    }
    return result
}

/** Draws a single off-screen arrow + distance label. See [OffscreenTargetArrows]. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawOffscreenTargetArrow(
    target: OffscreenTarget,
    arrowX: Float,
    arrowY: Float,
    labelPaint: android.graphics.Paint,
) {
    val angleRad = target.angleRad
    val dirX = cos(angleRad)
    val dirY = sin(angleRad)

    // Glyph outline, drawn pointing along +X and spun to `angleRad` by `rotateRad`. The
    // position (`arrowX`, `arrowY`) is the already-decluttered placement computed by
    // [declutterOffscreenArrowPlacements] — the ray-cast to the clamp rectangle happens
    // once there, not per draw call. Head is 22 dp long for 28 dp of base — a ~60° apex,
    // pointed enough to read as a tip — and the shaft adds another 20 dp of unmistakable
    // tail behind it.
    val tipX = ARROW_TIP_DP.dp.toPx()
    val headBackX = ARROW_HEAD_BACK_DP.dp.toPx()
    val headHalfBase = ARROW_HEAD_HALF_BASE_DP.dp.toPx()
    val tailX = ARROW_TAIL_DP.dp.toPx()
    val shaftHalfWidth = ARROW_SHAFT_HALF_WIDTH_DP.dp.toPx()

    val arrowPath = Path().apply {
        moveTo(arrowX + tipX, arrowY)
        lineTo(arrowX + headBackX, arrowY - headHalfBase)
        lineTo(arrowX + headBackX, arrowY - shaftHalfWidth)
        lineTo(arrowX + tailX, arrowY - shaftHalfWidth)
        lineTo(arrowX + tailX, arrowY + shaftHalfWidth)
        lineTo(arrowX + headBackX, arrowY + shaftHalfWidth)
        lineTo(arrowX + headBackX, arrowY + headHalfBase)
        close()
    }

    rotateRad(radians = angleRad, pivot = Offset(arrowX, arrowY)) {
        // Three shape-following layers, widest first — a halo and a keyline that hug
        // the arrow instead of a disc that hides it (#3304). The halo lifts the glyph
        // off a busy frame, the keyline keeps its edge crisp on a light one, and the
        // bright fill carries it on a dark one. Rounded joins so the barbs read as
        // barbs rather than as aliased spikes at this size.
        drawPath(
            path = arrowPath,
            color = SceneViewTokens.ArOverlay.scrimDark,
            style = Stroke(
                width = ARROW_HALO_WIDTH_DP.dp.toPx(),
                join = StrokeJoin.Round,
                cap = StrokeCap.Round,
            ),
        )
        drawPath(
            path = arrowPath,
            color = SceneViewTokens.ArOverlay.scrimLight,
            style = Stroke(
                width = ARROW_KEYLINE_WIDTH_DP.dp.toPx(),
                join = StrokeJoin.Round,
                cap = StrokeCap.Round,
            ),
        )
        drawPath(path = arrowPath, color = SceneViewTokens.ArOverlay.accentGuidance)
    }

    // Distance label (#3269), locale-aware with one decimal — e.g. "3.2 m" or, under a
    // comma-decimal locale, "3,2 m". Drawn upright (outside the `rotateRad` block, so it
    // stays readable whichever way the arrow points) on its own AR-scrim pill, pulled
    // toward the screen centre far enough to clear the tail.
    val labelOffset = SceneViewTokens.Space.x3l.toPx()
    val labelX = arrowX - dirX * labelOffset
    val labelY = arrowY - dirY * labelOffset
    val distanceText = String.format(Locale.getDefault(), "%.1f m", target.distanceMeters)

    val metrics = labelPaint.fontMetrics
    val pillHalfWidth =
        labelPaint.measureText(distanceText) / 2f + SceneViewTokens.Space.sm.toPx()
    val pillHalfHeight =
        (metrics.descent - metrics.ascent) / 2f + SceneViewTokens.Space.xs.toPx()
    drawRoundRect(
        color = SceneViewTokens.ArOverlay.scrimLight,
        topLeft = Offset(labelX - pillHalfWidth, labelY - pillHalfHeight),
        size = Size(pillHalfWidth * 2f, pillHalfHeight * 2f),
        cornerRadius = CornerRadius(pillHalfHeight),
    )
    // `drawText` takes a baseline, not a centre: shift by the mean of the font's
    // ascent/descent to sit the glyphs' optical middle on `labelY`.
    val baselineY = labelY - (metrics.ascent + metrics.descent) / 2f
    drawContext.canvas.nativeCanvas.drawText(distanceText, labelX, baselineY, labelPaint)
}

// Off-screen arrow glyph, in dp along its pointing axis, origin at the anchor point on
// the inset viewport rectangle. Kept together so the proportions that make the head
// distinguishable from the tail are readable in one place (#3304).
private const val ARROW_TIP_DP = 24f
private const val ARROW_HEAD_BACK_DP = 2f
private const val ARROW_HEAD_HALF_BASE_DP = 14f
private const val ARROW_TAIL_DP = -20f
private const val ARROW_SHAFT_HALF_WIDTH_DP = 6f
private const val ARROW_HALO_WIDTH_DP = 10f
private const val ARROW_KEYLINE_WIDTH_DP = 4f
