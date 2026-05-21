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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.sketchfab.SampleAssets
import io.github.sceneview.demo.sketchfab.SketchfabAssetResolver
import io.github.sceneview.demo.sketchfab.SketchfabConfig
import io.github.sceneview.demo.sketchfab.SketchfabSlug
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import java.io.File
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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
 * curated `solar` category in [SampleAssets]. When [SketchfabConfig.apiKey] is
 * missing (App Store / first-launch / no-network) the resolver returns the bundled
 * fallback declared in the registry — so the demo always renders eight planets,
 * just sometimes with duplicate visuals. The four bundled GLBs (helmet, lantern,
 * toy car, soldier) are always read straight from `assets/models/` and have no
 * network dependency.
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
    // (butterfly, hummingbird, bee, fish). We look them up by uid so a registry
    // re-ordering doesn't silently break the per-slot orbit tuning below.
    val butterfly = SampleAssets.byUid["78d8345fffe54a55ae62fadcf9eaece6"]
    val hummingbird = SampleAssets.byUid["9c54b62d3c2f4f0db8e7a3a8a78a4d92"]
    val bee = SampleAssets.byUid["6cb9f9a4c6e94f9da5b7c8a85e8a5c2d"]
    val koi = SampleAssets.byUid["d1ca3a3ddf3845abb98f4e5d62ae34c6"]

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
 * On-screen state for the directional indicator that points at the off-screen target
 * (issue #1482). `null` whenever the target is comfortably inside the camera frustum —
 * in that case no arrow is drawn and the user can simply see the model.
 *
 * @param angleRad direction, in radians, from the screen centre toward the target,
 *   measured in Compose screen space (0 = +X / right, π/2 = +Y / down). Used both to
 *   place the arrow on the viewport edge and to rotate the arrow glyph.
 */
private data class OffscreenTarget(val angleRad: Float)

/**
 * Projects [targetWorld] (an ARCore world-space position) to the camera and decides
 * whether it is off-screen. Returns an [OffscreenTarget] with the screen-space
 * direction toward it, or `null` when the target is inside the camera frustum.
 *
 * The projection is the standard `clip = projection · view · worldPoint`:
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
private fun computeOffscreenTarget(frame: Frame, targetWorld: Position): OffscreenTarget? {
    val camera = frame.camera
    if (camera.trackingState != TrackingState.TRACKING) return null

    val viewProjection =
        camera.getProjectionTransform(PROJECTION_NEAR, PROJECTION_FAR) * camera.viewTransform
    val clip = viewProjection * Float4(targetWorld, w = 1.0f)

    val behindCamera = clip.w <= 0f
    // Guard against a near-zero w (target almost exactly on the camera plane) which
    // would blow the divide up — treat it as "behind" and use raw clip x/y direction.
    val safeW = if (kotlin.math.abs(clip.w) < 1e-4f) 1e-4f else clip.w
    val ndcX = if (behindCamera) -clip.x else clip.x / safeW
    val ndcY = if (behindCamera) -clip.y else clip.y / safeW

    val onScreen = !behindCamera && ndcX in -1f..1f && ndcY in -1f..1f
    if (onScreen) return null

    // Compose screen Y points down, NDC Y points up — flip Y for the screen-space angle.
    return OffscreenTarget(angleRad = atan2(-ndcY, ndcX))
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

    // Directional indicator state (#1482). Non-null whenever the chase target
    // (the toy car, slot TARGET_PLANET_INDEX) is outside the camera frustum — the
    // overlay then draws an edge arrow pointing the user toward it. Recomputed once
    // per AR frame in onSessionUpdated.
    var offscreenTarget by remember { mutableStateOf<OffscreenTarget?>(null) }
    // Viewport size in pixels, captured from the Compose layout so the indicator can
    // be clamped to the real edge of the AR surface.
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    // Per-demo offline indicator (#1152 Stage 3): if every streamed slot has
    // resolved to a real `File` (Sketchfab CDN), surface "Streamed". If some
    // are still null, we're "Streaming…". If `SketchfabConfig.apiKey` is
    // absent up-front, the resolver short-circuits to the bundled fallback
    // and every slot resolves quickly to its fallback file — chip says
    // "Bundled fallback" instead.
    val streamedSlugs = ORBITAL_PLANETS.mapNotNull { it.streamedSlug }
    val assetSource: AssetSourceState? = if (streamedSlugs.isEmpty()) {
        null
    } else if (SketchfabConfig.apiKey == null) {
        AssetSourceState.Bundled
    } else if (streamedFiles.filterIndexed { i, _ -> ORBITAL_PLANETS[i].streamedSlug != null }
            .all { it != null }) {
        AssetSourceState.Streamed
    } else {
        AssetSourceState.Streaming
    }

    DemoScaffold(
        title = stringResource(R.string.demo_ar_orbital_title),
        onBack = onBack,
        assetSource = assetSource,
        controls = {
            Text(
                text = "8 models orbit around you in a personal solar system. " +
                    "Stand still and turn the phone — each model passes by at " +
                    "a different speed and height.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
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
                    isTracking = frame.camera.trackingState == TrackingState.TRACKING
                    // Drop the world-origin anchor on the first tracked frame. ARCore's
                    // world origin = the camera's pose at session-start, which is exactly
                    // what we want: the formation sits where the user is standing.
                    if (isTracking && userAnchor == null) {
                        userAnchor = runCatching {
                            session.createAnchor(Pose.IDENTITY)
                        }.getOrNull()
                    }
                    // Off-screen target indicator (#1482). Project the chase target's
                    // current world position into the camera and, when it falls outside
                    // the frustum, surface the screen-space direction toward it so the
                    // edge arrow can guide the user to turn and catch it.
                    val anchor = userAnchor
                    offscreenTarget = if (anchor != null && isTracking) {
                        runCatching {
                            val target = ORBITAL_PLANETS[TARGET_PLANET_INDEX]
                            val targetAngle =
                                (target.initialAngleRad + target.orbitSpeed * orbitSeconds) %
                                    (2f * PI.toFloat())
                            // Target position in the AnchorNode's local frame — identical
                            // to the ModelNode `position` computed in the content block.
                            val local = Position(
                                x = cos(targetAngle) * ORBIT_RADIUS,
                                y = target.height,
                                z = sin(targetAngle) * ORBIT_RADIUS,
                            )
                            // Lift the local point into ARCore world space through the
                            // anchor pose, then project + frustum-test it.
                            val worldPoint = anchor.pose.transform *
                                Float4(local, w = 1.0f)
                            computeOffscreenTarget(
                                frame,
                                Position(worldPoint.x, worldPoint.y, worldPoint.z),
                            )
                        }.getOrNull()
                    } else {
                        null
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
                                    centerOrigin = Position(0f, 0f, 0f),
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

            // Off-screen target indicator (#1482) — an edge arrow that points toward
            // the chase target whenever it is outside the camera frustum, so the user
            // knows which way to turn. Drawn below the status pill so the pill text
            // always stays readable.
            val target = offscreenTarget
            if (target != null && viewportSize != IntSize.Zero) {
                OffscreenTargetArrow(
                    angleRad = target.angleRad,
                    viewportSize = viewportSize,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // Status pill — top-center, mirrors the ARPlacement / ARInstantPlacement style.
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
                color = Color.Black.copy(alpha = 0.7f),
                contentColor = Color.White,
                tonalElevation = 4.dp,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = when {
                        !isTracking -> "Initializing AR — look around to start tracking"
                        userAnchor == null -> "Locking world anchor…"
                        else -> "Turn around — ${ORBITAL_PLANETS.size} models orbiting"
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

/**
 * Load a [io.github.sceneview.model.ModelInstance] from a nullable streamed [File],
 * returning `null` until the file is ready.
 *
 * The resolver always hands back a real on-disk [File] (streamed GLB or staged
 * bundled fallback), so the model must be loaded through
 * [io.github.sceneview.loaders.ModelLoader.loadModelInstance], which understands
 * `file://` URIs. The two-argument `rememberModelInstance(modelLoader, String)`
 * call is **not** usable here: Kotlin overload resolution binds it to the
 * asset-path overload (the one without a defaulted `resourceResolver`), which
 * feeds the `file://` string straight to `AssetManager.open` — that throws
 * `FileNotFoundException`, the instance stays `null`, and the streamed planet
 * never renders (#1422). Loading via `produceState` + `loadModelInstance` keeps
 * the Filament JNI work on the loader's own Main-thread hop.
 */
@Composable
private fun rememberFileModelInstance(
    modelLoader: io.github.sceneview.loaders.ModelLoader,
    file: File?,
): io.github.sceneview.model.ModelInstance? {
    if (file == null) return null
    return produceState<io.github.sceneview.model.ModelInstance?>(
        initialValue = null,
        key1 = modelLoader,
        key2 = file.absolutePath,
    ) {
        value = runCatching {
            modelLoader.loadModelInstance("file://${file.absolutePath}")
        }.getOrNull()
    }.value
}

/**
 * Full-screen [Canvas] overlay that draws a single directional arrow on the viewport
 * edge, pointing toward the off-screen chase target (issue #1482).
 *
 * The arrow is placed by casting a ray from the screen centre in direction [angleRad]
 * (Compose screen space: 0 = right, π/2 = down) and clamping the hit point to a
 * rounded-rectangle inset from the viewport edge. The glyph is a filled triangle plus
 * a short stalk, rotated so it visually points along the same direction.
 *
 * @param angleRad direction from screen centre toward the target, in radians.
 * @param viewportSize current AR surface size in pixels.
 * @param color arrow fill colour — the demo passes the Material primary colour.
 */
@Composable
private fun OffscreenTargetArrow(
    angleRad: Float,
    viewportSize: IntSize,
    color: Color,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas

        val centerX = width / 2f
        val centerY = height / 2f
        val dirX = cos(angleRad)
        val dirY = sin(angleRad)

        // Keep the arrow fully inside the viewport: inset the clamp rectangle by enough
        // to fit the glyph + a small margin, and never let the inset collapse past the
        // centre on a very small surface.
        val margin = 48.dp.toPx()
        val halfW = max(1f, centerX - margin)
        val halfH = max(1f, centerY - margin)

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
    }
}
