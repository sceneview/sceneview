package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Rotation
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Pure-Kotlin math helpers extracted from the Compose demos so they can be exercised
 * by JVM unit tests without firing up Filament / ARCore / Compose.
 *
 * Each function is the **single source of truth** for the calculation it implements —
 * the demo composables just call into here. Adding a JVM test for any of these
 * functions is the cheapest way to pin the demo's visible behaviour against
 * regressions, since the math drives the screen pixels (rotation, animation curve,
 * slider derivation) and the math is what tends to break when someone refactors.
 *
 * Closes part of [#880](https://github.com/sceneview/sceneview/issues/880) — the
 * non-AR demo regression-detection roadmap.
 */
internal object DemoMath {

    /**
     * Y-axis spin used by [io.github.sceneview.demo.demos.GeometryDemo]. Returns the
     * next rotation angle in degrees, advancing [previousDegrees] by `ratePerSecond *
     * deltaNanos` and wrapping at 360°.
     *
     * The wrap is `((next % 360) + 360) % 360` so callers passing a deliberately
     * negative rate (reverse spin) still land in `[0, 360)`. Without the second
     * modulo, Kotlin's `%` returns a negative remainder for negative operands.
     *
     * @param previousDegrees Last spin angle (must be in `[0, 360)`).
     * @param deltaNanos      Elapsed time since the previous frame, in nanoseconds. The
     *                        Choreographer typically delivers ~16.67 ms (≈16 666 666 ns)
     *                        per frame on a 60 Hz display. Negative values clamp to 0.
     * @param ratePerSecond   Rotation speed in degrees per second. Default 36°/s gives
     *                        one full revolution every 10 s — slow enough to see, fast
     *                        enough to read as motion.
     * @return Next rotation angle, always in `[0, 360)`.
     */
    fun nextSpinDegrees(
        previousDegrees: Float,
        deltaNanos: Long,
        ratePerSecond: Float = 36f,
    ): Float {
        if (deltaNanos <= 0L) return previousDegrees.wrapTo360()
        val deltaSec = deltaNanos / 1_000_000_000f
        val raw = previousDegrees + deltaSec * ratePerSecond
        return raw.wrapTo360()
    }

    private fun Float.wrapTo360(): Float = ((this % 360f) + 360f) % 360f

    /**
     * Asset path of the bundled Khronos *DamagedHelmet* GLB. The helmet is the hero
     * model for the AR placement demos (Cloud Anchor, Tap to Place, Depth Occlusion).
     */
    const val HELMET_ASSET = "models/khronos_damaged_helmet.glb"

    /**
     * Default placement rotation to apply to a bundled model the moment it is dropped
     * onto an AR plane. See [#1477](https://github.com/sceneview/sceneview/issues/1477).
     *
     * The Khronos *DamagedHelmet* GLB ships with a root-node quaternion of
     * `(0.7071, 0, 0, 0.7071)` — a +90° rotation about X — left over from its
     * Blender Z-up export. When the model is placed under an ARCore plane
     * `AnchorNode` (whose local frame is already Y-up), that residual pitch lands
     * the helmet **face-down**, nose into the floor, with the gold exhaust nozzle
     * pointing at the ceiling.
     *
     * Rather than re-author the shared bundled asset — several non-AR demos also
     * load it and frame it correctly in their own way — every AR placement demo
     * applies this single correcting rotation at placement time so the helmet
     * stands upright, visor forward. All other bundled cycle models (fox, lantern,
     * toy car, shiba) are authored upright and get the identity rotation.
     *
     * @param assetPath The bundled asset path passed to `rememberModelInstance`.
     * @return The local Euler rotation (degrees) to pass to `ModelNode(rotation = …)`.
     */
    fun placementRotationFor(assetPath: String): Rotation = when (assetPath) {
        HELMET_ASSET -> Rotation(x = -90f)
        else -> Rotation(x = 0f)
    }

    /**
     * Tabletop-display rotation used by [io.github.sceneview.demo.demos.ModelViewerDemo]'s
     * Multi-Model section (formerly `MultiModelDemo`, #2239 Batch 5).
     * Each model has a fixed `(dx, dz)` offset relative to the formation centre at
     * `(0, _, centerZ)`. As `sceneYaw` advances, the formation rotates around the
     * centre. Returns the new world-space `(x, z)` for the model.
     *
     * The rotation is **clockwise in (x, z)** when looking down +Y — combined with
     * each model's per-frame `Rotation(y = -sceneYaw)` (counter-clockwise in
     * Filament's Y-up right-handed convention), every model keeps facing the camera
     * even as the formation orbits. This is the visual "turntable display" effect.
     *
     * @param dx       Model's offset on the X axis (relative to formation centre).
     * @param dz       Model's offset on the Z axis (relative to formation centre,
     *                 i.e. `worldZ - centerZ`).
     * @param sceneYaw Current scene yaw in degrees.
     * @return The new local `(x, z)` after rotation. Caller adds the centre back to
     *         get world-space coordinates.
     */
    fun rotateAroundCentre(dx: Float, dz: Float, sceneYaw: Float): Pair<Float, Float> {
        val rad = Math.toRadians(sceneYaw.toDouble())
        val cosY = cos(rad).toFloat()
        val sinY = sin(rad).toFloat()
        // Clockwise in (x, z) when viewed from +Y down — see KDoc.
        val rx = dx * cosY + dz * sinY
        val rz = -dx * sinY + dz * cosY
        return rx to rz
    }

    /**
     * Camera distance at which a content box of [contentWidth] × [contentHeight] **covers** a
     * viewport of the given [aspect] — it fills the frame on both axes and the excess is cropped,
     * as opposed to `fitDistanceForBounds`'s *fit*, which shrinks the content until it sits
     * entirely inside the frame with empty margins on the wider axis.
     *
     * Used by [io.github.sceneview.demo.demos.ModelViewerDemo]'s Multi-Model section, whose
     * subject is a grove — a display that is much wider than it is tall. *Fitting* a 2.2 m-wide
     * grove into a portrait phone frame parks the camera ~5.5 m back and reduces the trees to a
     * strip across the middle; *covering* keeps them full-height and lets the frame crop through
     * the neighbouring trees, which is what a park scene should look like.
     *
     * Filament derives the camera's **vertical** FOV from the focal length against a 24 mm sensor
     * height, so the vertical framing is aspect-invariant and only the horizontal extent grows
     * with [aspect] (`io.github.sceneview.verticalFovDegreesForFocalLength`). That is why cover
     * framing is what makes the shot survive a wider viewport: at a fixed distance, a wider frame
     * shows *more world* to the left and right, and the fix is to be far enough back that the
     * extra width lands on more content instead of the backdrop (#2913).
     *
     * `d = min(dVertical, dHorizontal)` where
     * - `dVertical   = (contentHeight / 2) / tan(vfov / 2)`
     * - `dHorizontal = (contentWidth  / 2) / (tan(vfov / 2) · aspect)`
     *
     * Taking the **min** is what makes it *cover* rather than *fit* (which takes the max). On a
     * portrait viewport the vertical term wins and the distance is the same on a phone (~0.47 w/h)
     * and a tablet (~0.64) — both frame the grove full-height. On a landscape / foldable viewport
     * the horizontal term takes over and pulls the camera in so the grove still spans the width.
     *
     * @param contentWidth   Width of the content box, in metres. A non-finite / non-positive
     *                       value means "this axis does not constrain the framing" and lets
     *                       [contentHeight] decide alone.
     * @param contentHeight  Height of the content box, in metres. Same degenerate-axis rule as
     *                       [contentWidth].
     * @param verticalFovDegrees Camera vertical field-of-view, `(0, 180)`. For SceneView's default
     *                       28 mm lens this is ≈46.4° — pass
     *                       `io.github.sceneview.verticalFovDegreesForFocalLength(focalLength)`.
     *                       Values outside the range clamp into it; a non-finite value falls back
     *                       to that 28 mm default rather than propagating a `NaN`, since
     *                       `Double.coerceIn` returns `NaN` unchanged.
     * @param aspect         Viewport aspect ratio `width / height`. Non-finite / non-positive
     *                       values fall back to `1`, so a viewport measured before layout can
     *                       never produce a `NaN` camera position.
     * @param fill           Fraction of the frame the content should span. `1` = exactly edge to
     *                       edge; `< 1` leaves breathing room (`0.9` ⇒ the content spans 90% of
     *                       the frame); `> 1` crops into it. Non-finite / non-positive values fall
     *                       back to `1`.
     * @return The camera distance in metres, clamped to `[0.2, 50]`. Every degenerate input is
     *         sanitised above rather than propagated, so the result is always finite — a fully
     *         degenerate content box lands on the far bound instead of placing the camera inside
     *         the subject.
     */
    fun coverDistance(
        contentWidth: Float,
        contentHeight: Float,
        verticalFovDegrees: Double,
        aspect: Float,
        fill: Float = 1f,
    ): Float {
        val safeAspect = if (aspect.isFinite() && aspect > 0f) aspect else 1f
        val safeFill = if (fill.isFinite() && fill > 0f) fill else 1f
        // `coerceIn` returns NaN unchanged, so the finite check has to come first — otherwise a NaN
        // FOV survives every guard below and reaches the camera as a NaN position.
        val safeFovDegrees = verticalFovDegrees.takeIf { it.isFinite() }?.coerceIn(1.0, 179.0)
            ?: DEFAULT_VERTICAL_FOV_DEGREES
        val halfFovTan = tan(Math.toRadians(safeFovDegrees) / 2.0).toFloat()
        // A degenerate axis does not constrain the framing — treat it as "infinitely far" so the
        // other axis wins the `min` instead of collapsing the camera onto the subject.
        val distanceVertical = if (contentHeight.isFinite() && contentHeight > 0f) {
            (contentHeight / 2f) / (halfFovTan * safeFill)
        } else {
            Float.POSITIVE_INFINITY
        }
        val distanceHorizontal = if (contentWidth.isFinite() && contentWidth > 0f) {
            (contentWidth / 2f) / (halfFovTan * safeAspect * safeFill)
        } else {
            Float.POSITIVE_INFINITY
        }
        return min(distanceVertical, distanceHorizontal).coerceIn(0.2f, 50f)
    }

    /**
     * SceneView's default 28 mm lens against a 24 mm sensor height ⇒ ≈46.4° vertical FOV. Used only
     * as the fallback when [coverDistance] is handed a non-finite FOV.
     */
    private const val DEFAULT_VERTICAL_FOV_DEGREES = 46.4

    // ── AnimationDemo cinematic-camera choreography ──────────────────────────

    /**
     * The five scripted camera shots offered by
     * [io.github.sceneview.demo.demos.AnimationPhysicsDemo] (Animation tab).
     *
     * Mirrors the demo's private `CameraMode` enum — kept as a separate public
     * type in [DemoMath] so the choreography ([cameraModeScript]) can be
     * JVM-unit-tested without touching the Compose / Filament demo body.
     */
    enum class CameraShot { HERO, REVEAL, VERTIGO, TRACKING, FREE }

    /**
     * One keyframe in a cinematic camera shot. Each step animates the spherical
     * camera coordinates (and, for VERTIGO, the lens FOV) to [target values][to]
     * over [durationMillis]; a step with `durationMillis == 0` is an instant
     * `snapTo`, and a step with all-null targets is a pure hold ([holdMillis]).
     *
     * This is a *specification* of the choreography in `AnimationDemo`'s
     * `LifecyclePausingLaunchedEffect` — it pins the keyframe values (radii,
     * yaw sweep, FOV range, hold beats) so a refactor that drifts the cinematic
     * timing fails a unit test instead of silently shipping. The demo keeps
     * driving the real `Animatable`s imperatively; this model is the
     * golden reference those values are checked against.
     */
    data class CameraStep(
        /** Human-readable label for the beat (test diagnostics only). */
        val label: String,
        /** Target yaw in degrees, or `null` to leave yaw unchanged. */
        val yaw: Float? = null,
        /** Target orbit radius in metres, or `null` to leave radius unchanged. */
        val radius: Float? = null,
        /** Target camera Y-height in metres, or `null` to leave height unchanged. */
        val yHeight: Float? = null,
        /** Target vertical FOV in degrees (VERTIGO only), or `null` to leave FOV unchanged. */
        val fov: Float? = null,
        /** Tween duration in ms. `0` means an instant snap (no interpolation). */
        val durationMillis: Int = 0,
        /** Extra dwell after the tween completes, in ms (the cinematic "beat"). */
        val holdMillis: Int = 0,
    )

    /** Default vertical FOV (degrees) — a 50 mm-equivalent natural cinema lens. */
    const val DEFAULT_FOV_DEGREES = 45f

    /** Base orbit radius (metres) used as the framing reference for all shots. */
    const val BASE_RADIUS = 3.5f

    /** Base camera Y-height (metres) — level with the soldier's chest target. */
    const val BASE_Y_HEIGHT = 0.5f

    /** Inclusive playback-speed range of the AnimationDemo speed slider. */
    val ANIMATION_SPEED_RANGE: ClosedFloatingPointRange<Float> = 0.25f..3f

    /** Inclusive IBL-intensity range (lux) of the AnimationDemo IBL slider. */
    val IBL_INTENSITY_RANGE: ClosedFloatingPointRange<Float> = 0f..10_000f

    // ── ContactShadowPreviewDemo bounce choreography ─────────────────────────

    /**
     * One full ground-to-ground hop of the contact-shadow preview's comparison boxes,
     * in nanoseconds (2.6 s). Slow enough to read the shadow's response at the landing,
     * fast enough that the loop never feels idle.
     */
    const val CONTACT_BOUNCE_PERIOD_NANOS = 2_600_000_000L

    /** Peak height (metres) reached at the middle of each hop. */
    const val CONTACT_BOUNCE_MAX_HEIGHT_METERS = 0.34f

    /**
     * Height above the floor of the contact-shadow preview's bouncing boxes at
     * [elapsedNanos] into the loop: `maxHeight * |sin(π · t / period)|`.
     *
     * The rectified sine is deliberate — unlike a smooth hover (`(1-cos)/2`), `|sin|`
     * has a **non-zero slope at the zero crossings**, so the box visibly *strikes* the
     * floor once per period instead of easing into it. The landing instant is the
     * event the contact shadow exists to sell (the pool snaps dark and tight exactly
     * when the box touches), so the motion is shaped to emphasise it.
     *
     * The phase is reduced with integer math (`elapsedNanos % periodNanos`) **before**
     * the float conversion, so precision never degrades no matter how long the demo
     * runs. `t = 0` starts at ground contact — which is also the deterministic pose QA
     * mode freezes at (a zeroed clock).
     *
     * @param elapsedNanos Accumulated animation time. Values `<= 0` return `0` (contact).
     * @param periodNanos  Loop period. Values `<= 0` return `0` rather than dividing by zero.
     * @param maxHeight    Peak hop height in metres.
     * @return Height in `[0, maxHeight]`.
     */
    fun bounceHeight(
        elapsedNanos: Long,
        periodNanos: Long = CONTACT_BOUNCE_PERIOD_NANOS,
        maxHeight: Float = CONTACT_BOUNCE_MAX_HEIGHT_METERS,
    ): Float {
        if (elapsedNanos <= 0L || periodNanos <= 0L || maxHeight <= 0f) return 0f
        val phase = (elapsedNanos % periodNanos).toFloat() / periodNanos.toFloat()
        return maxHeight * abs(sin(PI.toFloat() * phase))
    }

    /**
     * How much of its base opacity a contact shadow keeps when its object hovers
     * [height] above the surface: `1` at contact, fading linearly to [liftedFactor]
     * at [maxHeight].
     *
     * This is the perceptual core of the preview. A contact shadow is an
     * ambient-occlusion pool: the closer the object, the more sky it blocks from the
     * patch beneath it, so the pool is densest at contact and washes out as the object
     * lifts. Coupling the pool's opacity to the hop height is what makes the grounded
     * box read as *landing on* the floor while its shadowless twin reads as floating —
     * the object/shadow motion-coupling cue (the classic "ball-in-a-box" illusion).
     *
     * Never returns less than [liftedFactor]: the pool must stay faintly visible at
     * the top of the hop so the eye keeps attributing it to the box. The default is
     * 0.45, not the physically purer 0.28 it shipped with: device QA measured the
     * 0.28 pool at ~0.15 alpha at the peak — near-invisible on the demo's light floor,
     * so the grounded-vs-floating contrast blinked out at the top of every hop. 0.45
     * keeps the pool legible at every phase while preserving a 2.2× contact/peak fade.
     * That measurement, its method (matched box-height crops under an identical
     * fixed-range contrast stretch) and the before/after evidence are recorded in the
     * QA report on PR #2851 — this default is empirical, not a taste call.
     *
     * @param height       Current hover height, metres. Coerced into `[0, maxHeight]`.
     * @param maxHeight    Height at which the factor bottoms out. `<= 0` returns `1`.
     * @param liftedFactor Factor kept at [maxHeight], in `0..1`.
     * @return Multiplier in `[liftedFactor, 1]`.
     */
    fun groundingIntensityFactor(
        height: Float,
        maxHeight: Float = CONTACT_BOUNCE_MAX_HEIGHT_METERS,
        liftedFactor: Float = 0.45f,
    ): Float {
        if (maxHeight <= 0f) return 1f
        val lift = (height / maxHeight).coerceIn(0f, 1f)
        return 1f - (1f - liftedFactor) * lift
    }

    /**
     * Uniform scale of the contact-shadow quad when its object hovers [height] above
     * the surface: `1` at contact, growing linearly to [liftedSpread] at [maxHeight].
     *
     * Physically, lifting an occluder widens its penumbra: the pool spreads and
     * softens as the object rises, then snaps back tight at the landing. Paired with
     * [groundingIntensityFactor] (dimming), this scale (spreading) completes the
     * ambient-occlusion response — dark-and-tight on contact, faint-and-wide in the air.
     *
     * @param height       Current hover height, metres. Coerced into `[0, maxHeight]`.
     * @param maxHeight    Height at which the spread tops out. `<= 0` returns `1`.
     * @param liftedSpread Scale reached at [maxHeight]. `>= 1`.
     * @return Scale in `[1, liftedSpread]`.
     */
    fun groundingSpread(
        height: Float,
        maxHeight: Float = CONTACT_BOUNCE_MAX_HEIGHT_METERS,
        liftedSpread: Float = 1.5f,
    ): Float {
        if (maxHeight <= 0f) return 1f
        val lift = (height / maxHeight).coerceIn(0f, 1f)
        return 1f + (liftedSpread - 1f) * lift
    }

    /**
     * Horizontal ground offset `(dx, dz)` of a contact shadow when its object hovers [height]
     * above the floor: the pool *slides out from under* the object as it lifts and slides back
     * beneath it on the way down.
     *
     * This is the perceptual payload the preview was missing. Dimming
     * ([groundingIntensityFactor]) and spreading ([groundingSpread]) alone leave the pool pinned
     * under the box — a lifted object still reads as merely "a fainter shadow", not as "higher".
     * The shadow's **path** is the strongest grounding cue the visual system has (the classic
     * "ball-in-a-box" illusion: the same vertical trajectory reads as bouncing or floating
     * depending only on where the shadow goes). Moving the pool is what makes the grounded box
     * read as *landing on* the floor while its shadowless twin, animated identically, floats.
     *
     * The offset is the exact geometric projection of the object's base along the key light: a
     * directional light travelling `(lightDirX, lightDirY, lightDirZ)` throws a point at height
     * `h` onto the ground `h / |lightDirY|` along the light's horizontal component. A light with
     * no vertical component (`|lightDirY| ~ 0`) grazes the floor and would project to infinity,
     * so it returns no offset rather than a `NaN`/∞ position.
     *
     * @param height    Current hover height, metres. Values `<= 0` (contact) return `(0, 0)`.
     * @param lightDirX Key-light travel direction, X component.
     * @param lightDirY Key-light travel direction, Y component (negative for a ceiling light).
     * @param lightDirZ Key-light travel direction, Z component.
     * @return The `(dx, dz)` in metres to add to the pool's ground position.
     */
    fun groundingShadowOffset(
        height: Float,
        lightDirX: Float,
        lightDirY: Float,
        lightDirZ: Float,
    ): Pair<Float, Float> {
        if (height <= 0f) return 0f to 0f
        val vertical = abs(lightDirY)
        if (vertical <= 1e-4f) return 0f to 0f
        val k = height / vertical
        return (lightDirX * k) to (lightDirZ * k)
    }

    /**
     * Bob period (nanoseconds, 3.4 s) of the contact-shadow preview's FLOATING box.
     *
     * Deliberately *longer* and non-harmonic with [CONTACT_BOUNCE_PERIOD_NANOS] (2.6 s): the
     * two boxes drift out of phase, which reads as "two independent objects doing two different
     * things" rather than a single synchronised animation.
     */
    const val CONTACT_FLOAT_PERIOD_NANOS = 3_400_000_000L

    /**
     * Rest height (metres, box **centre**) of the floating box. Chosen so the box hovers well
     * clear of the floor at all times — its lowest point (`centre − bob − half-edge`) never
     * reaches the grounded box's highest point, and its highest point stays below the wall TV.
     * See the `floatHoverY … stays clear of the floor and the grounded box` test.
     */
    const val CONTACT_FLOAT_CENTER_Y_METERS = 0.62f

    /** Peak bob deviation of the floating box from [CONTACT_FLOAT_CENTER_Y_METERS], metres. */
    const val CONTACT_FLOAT_BOB_METERS = 0.05f

    /**
     * Vertical position (box **centre**, metres) of the contact-shadow preview's FLOATING box
     * at [elapsedNanos]: a slow, smooth sine bob of ±[bobAmplitude] around [centerY].
     *
     * This is the *positive* half of the grounded-vs-floating comparison. Its twin
     * ([bounceHeight]) is a **rectified** sine that STRIKES the floor once per period; this is a
     * **plain** sine — smooth at every phase, no zero-crossing landing, and centred high above
     * the floor so the box never touches it. One box hammers the ground (its contact pool snaps
     * dark at each landing); its twin serenely levitates. The floating box's *motion* — not the
     * mere absence of a shadow — is what makes it read as airborne (#2740): a shadow that is
     * simply missing reads as a rendering bug, but a box visibly hovering high and bobbing reads
     * as floating, so of course it casts no contact shadow.
     *
     * `t = 0` returns exactly [centerY] (`sin 0 = 0`), the deterministic pose QA mode freezes at
     * — the same zeroed clock that lands [bounceHeight]'s box on the floor.
     *
     * @param elapsedNanos Accumulated animation time. Values `<= 0` are treated as `0` (rest).
     * @param periodNanos  Bob period. Values `<= 0` return [centerY] rather than dividing by zero.
     * @param centerY      Rest height of the box centre, metres.
     * @param bobAmplitude Peak deviation from [centerY], metres.
     * @return Box-centre height in `[centerY - bobAmplitude, centerY + bobAmplitude]`.
     */
    fun floatHoverY(
        elapsedNanos: Long,
        periodNanos: Long = CONTACT_FLOAT_PERIOD_NANOS,
        centerY: Float = CONTACT_FLOAT_CENTER_Y_METERS,
        bobAmplitude: Float = CONTACT_FLOAT_BOB_METERS,
    ): Float {
        if (periodNanos <= 0L) return centerY
        val safeElapsed = if (elapsedNanos <= 0L) 0L else elapsedNanos
        val phase = (safeElapsed % periodNanos).toFloat() / periodNanos.toFloat()
        return centerY + bobAmplitude * sin(2f * PI.toFloat() * phase)
    }

    /**
     * The keyframe choreography for one cinematic [shot] of
     * [io.github.sceneview.demo.demos.AnimationPhysicsDemo] (Animation tab).
     *
     * Returns the ordered list of [CameraStep]s that make up **one loop** of the
     * shot (the demo replays the list forever). [CameraShot.FREE] returns an
     * empty list — there is no scripted motion, the user drives the camera.
     *
     * The returned values are the single source of truth for the shot's framing:
     * a unit test pins them, and any drift in the demo's hand-written
     * `animateTo` calls is caught by comparing against this model.
     */
    fun cameraModeScript(shot: CameraShot): List<CameraStep> = when (shot) {
        CameraShot.HERO -> listOf(
            // Eyes-level heroic orbit broken into 4 segments with a 2 s hold at
            // the front-3/4 angle for a cinematic beat.
            CameraStep("enter", radius = BASE_RADIUS + 0.2f, yHeight = 0.55f, durationMillis = 0),
            CameraStep("reset-yaw", yaw = 0f, durationMillis = 0),
            CameraStep("q1 0→45", yaw = 45f, durationMillis = 5_000, holdMillis = 2_000),
            CameraStep("q2 45→180", yaw = 180f, durationMillis = 8_000),
            CameraStep("half 180→360", yaw = 360f, durationMillis = 10_000),
        )
        CameraShot.REVEAL -> listOf(
            // Close-up → wide high-angle dolly-out. Slight 15° off-axis hold.
            CameraStep("off-axis", yaw = 15f, durationMillis = 0),
            CameraStep("close-up", radius = 1.5f, yHeight = 0.9f, durationMillis = 0),
            CameraStep("pull-back", radius = 5.0f, yHeight = 1.2f, durationMillis = 6_000, holdMillis = 2_000),
        )
        CameraShot.VERTIGO -> listOf(
            // Hitchcock dolly-zoom — radius and FOV move in opposition.
            CameraStep("enter", yaw = 20f, yHeight = BASE_Y_HEIGHT, durationMillis = 0),
            CameraStep("vertigo-start", radius = 2.0f, fov = 60f, durationMillis = 0),
            CameraStep("vertigo-in", radius = 5.0f, fov = 25f, durationMillis = 10_000, holdMillis = 1_000),
            CameraStep("vertigo-out", radius = 2.0f, fov = 60f, durationMillis = 8_000, holdMillis = 1_000),
        )
        CameraShot.TRACKING -> listOf(
            // Lateral straight-line pass — described as the yaw-free sweep beat.
            // The demo drives an absolute eye position; the spherical fields are
            // left null because TRACKING bypasses the (yaw, radius, yHeight) path.
            CameraStep("sweep -4→+4", durationMillis = 8_000, holdMillis = 1_000),
        )
        CameraShot.FREE -> emptyList()
    }
}
