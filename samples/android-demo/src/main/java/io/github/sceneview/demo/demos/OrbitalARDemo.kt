package io.github.sceneview.demo.demos

import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
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
import io.github.sceneview.ar.ARSceneView
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
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * Personal-solar-system AR demo — 8 themed planets orbit around the user.
 *
 * On the first tracked AR frame, an [Anchor] is created at world origin (the camera's
 * pose at session-start in ARCore — i.e. wherever the user is standing). Eight model
 * instances are placed as children of an [AnchorNode] in a circle of radius 1.5 m,
 * at evenly-spaced angles (360° / 8 = 45° apart). Each model has:
 *
 * - its own **orbital speed** (between 0.05 and 0.30 rad/s) — slow at the outer
 *   "planets", fast at the inner ones, so the formation looks like a solar system
 *   rather than a rigid ring;
 * - either a **baked animation** (4 streamed animated creatures — butterfly,
 *   hummingbird, bee, koi — plus the bundled `threejs_soldier`, oriented along the
 *   orbit tangent so they "fly the orbit") **or** a **local Y spin** (between 0.7
 *   and 2.0 rad/s) for static GLBs (helmet, lantern, toy car) so they feel alive
 *   without their own rig;
 * - a **distinct height** between -0.5 m and +0.5 m relative to the user's eye level.
 *
 * The user stays fixed in AR and can turn around to watch each model pass by.
 *
 * Animation is driven by `withFrameNanos` so the orbit advances at the display's
 * refresh rate. Per-recompose state (`orbitSeconds`) is hoisted via `mutableLongStateOf`
 * so the `ModelNode` positions/rotations recompute every frame.
 *
 * ### Streaming pipeline (Stage 2, issue #1152)
 *
 * Four of the eight planets are now streamed via [SketchfabAssetResolver] from the
 * curated `solar` category in [SampleAssets]. The resolver never fails a slot: a missing
 * [SketchfabConfig.apiKey] (App Store / first-launch) short-circuits to the bundled
 * fallback declared in the registry, and so does every *runtime* failure with a key
 * present — no network, aeroplane mode, a stale key, a 4xx, exhausted retries. So the demo
 * always renders eight planets, just sometimes with duplicate visuals, and a configured key
 * is never evidence that any of them streamed (which is why the asset-source pill asks the
 * resolved files instead — #2953). The four bundled GLBs (helmet, lantern, toy car,
 * soldier) are always read straight from `assets/models/` and have no network dependency.
 *
 * This replaces the previous "2 duplicate dragons + 2 duplicate soldiers" workaround
 * that the bundle-only design was forced into (the old #978 audit flagged the dups
 * as a quality issue — the formation looked like clones rather than a solar system).
 */
private data class Planet(
    /**
     * Streamed Sketchfab slug (`solar` category) when non-null. The resolver
     * gives us either the downloaded GLB or the registered bundled fallback —
     * the demo just hands the resulting [File] to `rememberModelInstance`.
     */
    val streamedSlug: SketchfabSlug? = null,
    /**
     * Bundled asset path under `assets/`. Used when [streamedSlug] is null —
     * the three pure-bundled planets (helmet, lantern, toy car, animated dragon).
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

// 8 themed planets — 4 streamed via the resolver (animated creatures from the
// `solar` category of SampleAssets), 4 bundled in the APK as offline fallback +
// to keep variety when the Sketchfab key is missing. The streamed entries fall
// back to their registered bundled GLB when offline, so the demo always renders
// eight orbiting models — no broken/black slots, no clones (the old 7-slot
// design duplicated the dragon and soldier just to fill the ring).
private val ORBITAL_PLANETS: List<Planet> = run {
    // The four streamed entries — order matches the SampleAssets `solar` category
    // (four animated butterflies). We look them up by uid so a registry
    // re-ordering doesn't silently break the per-slot orbit tuning below.
    val butterfly = SampleAssets.byUid["0f24b085e8654e4db09c2fe681a79e3f"]
    val hummingbird = SampleAssets.byUid["80f8d9a6dadc411e89ca366cb0cfb0d9"]
    val bee = SampleAssets.byUid["d4fbcbaab845402999f30c5aa75851e6"]
    val koi = SampleAssets.byUid["8ca3b9aa82694e6b8bc53a69b4529539"]

    // Per-slot tuning kept compatible with the previous bundle-only design — same
    // orbit radius (1.5 m), same height spread (±0.5 m), same speed range
    // (0.05–0.30 rad/s) so the visual rhythm doesn't change.
    listOf(
        // Slot 0 — bundled helmet, static spinning (hero anchor at angle 0).
        Planet(
            bundledAssetPath = "models/khronos_damaged_helmet.glb",
            scaleToUnits = 0.20f,
            initialAngleRad = 0f,
            orbitSpeed = 0.08f,
            spinSpeed = 0.7f,
            height = 0.0f,
            hasBakedAnimation = false,
        ),
        // Slot 1 — streamed butterfly, baked anim, flies the orbit tangent.
        Planet(
            streamedSlug = butterfly,
            scaleToUnits = 0.30f,
            initialAngleRad = 2f * PI.toFloat() / 8f * 1,
            orbitSpeed = 0.20f,
            spinSpeed = 0f,
            height = -0.2f,
            hasBakedAnimation = true,
        ),
        // Slot 2 — bundled lantern, static spinning.
        Planet(
            bundledAssetPath = "models/khronos_lantern.glb",
            scaleToUnits = 0.20f,
            initialAngleRad = 2f * PI.toFloat() / 8f * 2,
            orbitSpeed = 0.06f,
            spinSpeed = 0.9f,
            height = 0.4f,
            hasBakedAnimation = false,
        ),
        // Slot 3 — streamed hummingbird, baked anim.
        Planet(
            streamedSlug = hummingbird,
            scaleToUnits = 0.18f,
            initialAngleRad = 2f * PI.toFloat() / 8f * 3,
            orbitSpeed = 0.15f,
            spinSpeed = 0f,
            height = -0.4f,
            hasBakedAnimation = true,
        ),
        // Slot 4 — bundled toy car, static spinning (kid-friendly anchor).
        Planet(
            bundledAssetPath = "models/khronos_toy_car.glb",
            scaleToUnits = 0.20f,
            initialAngleRad = 2f * PI.toFloat() / 8f * 4,
            orbitSpeed = 0.10f,
            spinSpeed = 2.0f,
            height = 0.2f,
            hasBakedAnimation = false,
        ),
        // Slot 5 — streamed bee, baked anim.
        Planet(
            streamedSlug = bee,
            scaleToUnits = 0.12f,
            initialAngleRad = 2f * PI.toFloat() / 8f * 5,
            orbitSpeed = 0.25f,
            spinSpeed = 0f,
            height = -0.5f,
            hasBakedAnimation = true,
        ),
        // Slot 6 — bundled animated soldier (baked walk cycle).
        // Replaced animated_dragon.glb (8.0 MB) with threejs_soldier.glb
        // (2.1 MB, same baked-animation property) as part of the Stage 3
        // APK slim-down — see #1152 Stage 3 PR.
        Planet(
            bundledAssetPath = "models/threejs_soldier.glb",
            scaleToUnits = 0.30f,
            initialAngleRad = 2f * PI.toFloat() / 8f * 6,
            orbitSpeed = 0.05f,
            spinSpeed = 0f,
            height = 0.5f,
            hasBakedAnimation = true,
        ),
        // Slot 7 — streamed koi fish, baked anim (closes the ring).
        Planet(
            streamedSlug = koi,
            scaleToUnits = 0.35f,
            initialAngleRad = 2f * PI.toFloat() / 8f * 7,
            orbitSpeed = 0.30f,
            spinSpeed = 0f,
            height = 0.3f,
            hasBakedAnimation = true,
        ),
    )
}

private const val ORBIT_RADIUS = 1.5f

/**
 * Index into [ORBITAL_PLANETS] of the "target to chase" — the model the off-screen
 * directional indicator points at (issue #1482). Slot 4 is the bundled toy car, the
 * orbiting object the QA tester referred to as "the flying car". It is a deliberately
 * slow-orbiting (0.10 rad/s), kid-friendly anchor model, so it stays a stable, easy
 * target for the user to turn toward and "catch".
 */
private const val TARGET_PLANET_INDEX = 4

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
): OffscreenTarget? {
    val clip = viewProjection * Float4(targetWorld, w = 1.0f)

    val behindCamera = clip.w <= 0f
    // Guard against a near-zero w (target almost exactly on the camera plane) which
    // would blow the divide up — treat it as "behind" and use raw clip x/y direction.
    val safeW = if (abs(clip.w) < 1e-4f) 1e-4f else clip.w
    val ndcX = if (behindCamera) -clip.x else clip.x / safeW
    val ndcY = if (behindCamera) -clip.y else clip.y / safeW

    val onScreen = !behindCamera && ndcX in -1f..1f && ndcY in -1f..1f
    if (onScreen) return null

    val dx = targetWorld.x - cameraPosition.x
    val dy = targetWorld.y - cameraPosition.y
    val dz = targetWorld.z - cameraPosition.z
    val distanceMeters = sqrt(dx * dx + dy * dy + dz * dz)

    // Compose screen Y points down, NDC Y points up — flip Y for the screen-space angle.
    return OffscreenTarget(angleRad = atan2(-ndcY, ndcX), distanceMeters = distanceMeters)
}

/**
 * ARCore-facing wrapper around [projectOffscreenTarget]: pulls the view/projection
 * matrix and camera position off [frame] and delegates the actual math.
 */
private fun computeOffscreenTarget(frame: Frame, targetWorld: Position): OffscreenTarget? {
    val camera = frame.camera
    if (camera.trackingState != TrackingState.TRACKING) return null

    val viewProjection =
        camera.getProjectionTransform(PROJECTION_NEAR, PROJECTION_FAR) * camera.viewTransform
    val cameraPose = camera.pose
    val cameraPosition = Position(cameraPose.tx(), cameraPose.ty(), cameraPose.tz())
    return projectOffscreenTarget(viewProjection, cameraPosition, targetWorld)
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
    // once the resolver returns. Bundled-only planets contribute `null` here
    // and go straight through `rememberModelInstance(assetPath)` below.
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
    // ARCore world origin is undefined until tracking begins. After that, all 8 planets
    // ride this anchor — turning the phone shows them passing by in world space.
    var userAnchor by remember { mutableStateOf<Anchor?>(null) }
    var isTracking by remember { mutableStateOf(false) }
    // Cover the jet-black ARSceneView surface until ARCore delivers its first camera
    // frame, so the ~1–3 s warm-up on entry doesn't read as a frozen screen (#2484).
    var cameraReady by remember { mutableStateOf(false) }

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

    // Onboarding-dismiss state (#2481). The "Turn around — N models orbiting" **banner
    // text only** is a first-launch nudge: it teaches the user to turn and watch the
    // formation pass by, then gets out of the way once the user has clearly engaged
    // (the QA tester: "là, il met encore la flèche alors qu'on est dessus"). `
    // onboardingDismissed` flips true once the nudge has served its purpose and stays
    // true (it's a rememberSaveable so a device rotation mid-session doesn't re-show
    // it). It is set true when EITHER:
    //  - the orbit is live and the chase target has come on-screen at least once
    //    (the user successfully turned toward a model — see onSessionUpdated), or
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
    // Only the streamed slots are probed — the four bundled planets (helmet, lantern, toy
    // car, soldier) read straight from `assets/` and have no origin question to answer.
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
    // the two transient setup states (tracking, anchor lock) and the "Turn around"
    // onboarding nudge. Once tracking + anchor are established *and* the onboarding nudge
    // has been dismissed (#2481), the pill is gone entirely — the orbit is
    // self-explanatory at that point and a permanent banner only clutters the AR view.
    val statusText = when {
        !isTracking -> "Initializing AR — look around to start tracking"
        userAnchor == null -> "Locking world anchor…"
        !onboardingDismissed -> "Turn around — ${ORBITAL_PLANETS.size} models orbiting"
        else -> null
    }

    // No Settings FAB: this demo has nothing to configure. The orbit runs
    // automatically and the status pill already tells the user what to do
    // ("Turn around — N models orbiting"), so a settings sheet carrying a
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
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                playbackDataset = arPlaybackDataset,
                planeRenderer = false,
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
                    // Off-screen target indicator (#1482, #3269). Project **every**
                    // planet's current world position into the camera and keep an entry
                    // for each one that falls outside the frustum, so the overlay can
                    // draw one edge arrow (with distance) per off-screen object — not
                    // just the chase target — and it stays up continuously, per object,
                    // until that object re-enters the viewport.
                    val anchor = userAnchor
                    offscreenTargets = if (anchor != null && isTracking) {
                        ORBITAL_PLANETS.indices.mapNotNull { index ->
                            runCatching {
                                val planet = ORBITAL_PLANETS[index]
                                val targetAngle =
                                    (planet.initialAngleRad + planet.orbitSpeed * orbitSeconds) %
                                        (2f * PI.toFloat())
                                // Target position in the AnchorNode's local frame —
                                // identical to the ModelNode `position` computed in the
                                // content block.
                                val local = Position(
                                    x = cos(targetAngle) * ORBIT_RADIUS,
                                    y = planet.height,
                                    z = sin(targetAngle) * ORBIT_RADIUS,
                                )
                                // Lift the local point into ARCore world space through
                                // the anchor pose, then project + frustum-test it.
                                val worldPoint = anchor.pose.transform *
                                    Float4(local, w = 1.0f)
                                computeOffscreenTarget(
                                    frame,
                                    Position(worldPoint.x, worldPoint.y, worldPoint.z),
                                )?.let { index to it }
                            }.getOrNull()
                        }.toMap()
                    } else {
                        emptyMap()
                    }
                    // Onboarding nudge dismisses the "Turn around" **banner text** for
                    // good the first time the user brings the chase target on-screen
                    // (#2481). This must not affect arrow visibility (#3269) — see the
                    // `onboardingDismissed` Kdoc above.
                    if (anchor != null && isTracking &&
                        !offscreenTargets.containsKey(TARGET_PLANET_INDEX)
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
                                // Modulo before sin/cos so a long-running session
                                // (~290 h+) doesn't lose Float precision (#978).
                                val orbitAngle =
                                    (planet.initialAngleRad + planet.orbitSpeed * orbitSeconds) %
                                            (2f * PI.toFloat())
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
                                    scaleToUnits = planet.scaleToUnits,
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
                OffscreenTargetArrows(
                    targets = offscreenTargets.values.toList(),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Full-screen [Canvas] overlay that draws one directional arrow per entry in [targets]
 * on the viewport edge, each pointing toward an off-screen orbiting object and labelled
 * with the live distance to it (issue #1482, #3269).
 *
 * Each arrow is placed by casting a ray from the screen centre in its target's
 * direction (Compose screen space: 0 = right, π/2 = down) and clamping the hit point to
 * a rounded-rectangle inset from the viewport edge. The glyph is a filled triangle plus
 * a short stalk, rotated so it visually points along the same direction; the distance
 * label sits just behind the tip, formatted with the device locale's decimal separator.
 *
 * @param targets one entry per currently off-screen object — arbitrary order, drawn
 *   independently so overlapping targets never hide one another's arrow.
 * @param color arrow fill colour — the demo passes the Material primary colour.
 */
@Composable
private fun OffscreenTargetArrows(
    targets: List<OffscreenTarget>,
    color: Color,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas

        val centerX = width / 2f
        val centerY = height / 2f

        // Keep every arrow fully inside the viewport: inset the clamp rectangle by
        // enough to fit the glyph + a small margin, and never let the inset collapse
        // past the centre on a very small surface.
        val margin = 48.dp.toPx()
        val halfW = max(1f, centerX - margin)
        val halfH = max(1f, centerY - margin)

        val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            // `this.` is load-bearing: bare `color = ...` here resolves against the
            // Composable's own `color: Color` parameter (Compose's `Color`, not an
            // `Int`) rather than this `Paint` receiver's `color: Int` property.
            this.color = android.graphics.Color.WHITE
            textSize = 13.sp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
        }

        for (target in targets) {
            drawOffscreenTargetArrow(
                target = target,
                centerX = centerX,
                centerY = centerY,
                halfW = halfW,
                halfH = halfH,
                color = color,
                labelPaint = labelPaint,
            )
        }
    }
}

/** Draws a single off-screen arrow + distance label. See [OffscreenTargetArrows]. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawOffscreenTargetArrow(
    target: OffscreenTarget,
    centerX: Float,
    centerY: Float,
    halfW: Float,
    halfH: Float,
    color: Color,
    labelPaint: android.graphics.Paint,
) {
    val angleRad = target.angleRad
    val dirX = cos(angleRad)
    val dirY = sin(angleRad)

    // Distance along (dirX, dirY) until the ray first crosses the inset rectangle.
    // Guard the divide for axis-aligned directions (dirX or dirY == 0).
    val tX = if (dirX != 0f) halfW / kotlin.math.abs(dirX) else Float.MAX_VALUE
    val tY = if (dirY != 0f) halfH / kotlin.math.abs(dirY) else Float.MAX_VALUE
    val t = min(tX, tY)

    val arrowX = centerX + dirX * t
    val arrowY = centerY + dirY * t

    // Triangle pointing along +X before rotation; rotateRad spins it to angleRad.
    val tip = 22.dp.toPx()
    val halfBase = 15.dp.toPx()
    rotateRad(radians = angleRad, pivot = Offset(arrowX, arrowY)) {
        // Soft drop shadow for contrast against bright camera frames.
        val arrowPath = Path().apply {
            moveTo(arrowX + tip, arrowY)
            lineTo(arrowX - tip * 0.4f, arrowY - halfBase)
            lineTo(arrowX - tip * 0.4f, arrowY + halfBase)
            close()
        }
        drawCircle(
            color = Color.Black.copy(alpha = 0.35f),
            radius = tip * 1.15f,
            center = Offset(arrowX, arrowY),
        )
        drawPath(path = arrowPath, color = color)
    }

    // Distance label (#3269), locale-aware with one decimal — e.g. "3.2 m" or,
    // under a comma-decimal locale, "3,2 m". Drawn upright (outside the rotateRad
    // block) just behind the arrow tip, pulled toward the screen centre along the
    // same direction so it stays clear of the glyph and inside the viewport.
    val labelOffset = tip * 2.4f
    val labelX = arrowX - dirX * labelOffset
    val labelY = arrowY - dirY * labelOffset
    val distanceText = String.format(Locale.getDefault(), "%.1f m", target.distanceMeters)
    drawContext.canvas.nativeCanvas.drawText(distanceText, labelX, labelY, labelPaint)
}
