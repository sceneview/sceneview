package io.github.sceneview.demo.demos.internal

import androidx.compose.ui.graphics.Color
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * The one stage both lighting demos light — geometry, rig math and the environment catalogue,
 * as pure functions so they can be read, unit-tested and reused without a Filament engine.
 *
 * ## Why the two demos share a stage
 *
 * `lighting` and `lighting-lab` used to be seven modes across two cards, each mode building its
 * own engine around the same helmet and each answering a different question badly. They are now
 * two **roles** over one subject:
 *
 * - **`lighting`** — the showcase: *where does the light come from?* Three rigs (image-based,
 *   studio, sun), few controls each.
 * - **`lighting-lab`** — the workbench: *what can I turn?* One rig, every knob live on the same
 *   frame.
 *
 * Sharing the stage is what makes that split readable: the subject is identical on both screens,
 * so the only thing that changes between them is the job. Everything in here is therefore
 * expressed once — the floor, the hero, the two photographic probe balls, and the three-point rig
 * — and both screens compose it.
 *
 * ## The probe balls
 *
 * A chrome ball and a matte grey ball flanking the subject are what a set photographer actually
 * puts in frame to capture lighting: the chrome one shows *what the environment looks like* (it
 * mirrors the IBL), the grey one shows *where the light comes from and how hard it is* (its
 * terminator is the light direction, the softness of that terminator is the source size). They
 * make an IBL rotation or a key-light move legible on a scene that would otherwise only show a
 * highlight sliding across a helmet visor.
 */
object LightingStage {

    // ── Geometry ─────────────────────────────────────────────────────────────────────────────

    /** The hero. Already bundled and already the model both demos' card art was generated from. */
    const val HERO_MODEL: String = "models/khronos_damaged_helmet.glb"

    /** Hero size, world units on its longest axis — so the helmet spans 0.5 m. */
    const val HERO_UNITS: Float = 0.5f

    /**
     * Top surface of the floor. The hero is centred on the origin and normalised to
     * [HERO_UNITS], so its underside sits at `-HERO_UNITS / 2`; the floor is 1 cm below that,
     * close enough to read as contact under ambient occlusion without the mesh intersecting it.
     */
    const val FLOOR_TOP: Float = -(HERO_UNITS / 2f) - 0.01f

    /** Floor slab thickness. Thin, but a solid so it casts and receives like anything else. */
    const val FLOOR_THICKNESS: Float = 0.04f

    /**
     * Floor side length.
     *
     * 4 m, not "very large": the floor has to run past the frame so it reads as ground rather
     * than as a plinth the subject floats on, but every extra metre is also extra ground the
     * directional shadow map has to cover, and a cascade stretched over a 20 m slab turns a
     * 0.5 m helmet's shadow into stair-steps. 4 m clears the widest hero-orbit framing with the
     * shadow still crisp.
     */
    const val FLOOR_SIZE: Float = 4f

    /** Centre of the floor slab, derived so its top lands exactly on [FLOOR_TOP]. */
    val floorCenter: Position get() = Position(0f, FLOOR_TOP - FLOOR_THICKNESS / 2f, 0f)

    /**
     * Floor material — a dark, faintly polished studio sweep.
     *
     * `DESIGN.md`'s `outline` (dark) rather than `surface-dim`: on-device QA on 2026-09-08 showed
     * `#161B22` under a bright HDR as an unbroken black rectangle over half the frame, which took
     * no light and grounded nothing. `#2A3346` is bright enough to carry the pool a key throws
     * and the gradient an environment lays down.
     *
     * The roughness is the other half of that fix. A near-matte floor gives an object nothing to
     * stand on; at 0.45 the sweep returns a soft, blurred reflection of the hero and the two
     * probes, which is what actually reads as contact under an image-based rig — an IBL casts no
     * shadow, so the reflection is the only grounding cue available to it.
     */
    val FLOOR_COLOR: Color = Color(0xFF2A3346)
    const val FLOOR_ROUGHNESS: Float = 0.45f
    const val FLOOR_REFLECTANCE: Float = 0.55f

    /** Radius of each photographic probe ball. */
    const val PROBE_RADIUS: Float = 0.065f

    /** How far to either side of the hero the probe balls stand. */
    const val PROBE_OFFSET_X: Float = 0.34f

    /** Probes are pulled forward of the hero so the camera never hides one behind it. */
    const val PROBE_OFFSET_Z: Float = 0.14f

    /** The chrome ball — mirrors the environment. Stage left. */
    val chromeProbePosition: Position
        get() = Position(-PROBE_OFFSET_X, FLOOR_TOP + PROBE_RADIUS, PROBE_OFFSET_Z)

    /** The matte grey ball — reads the key light's direction and softness. Stage right. */
    val matteProbePosition: Position
        get() = Position(PROBE_OFFSET_X, FLOOR_TOP + PROBE_RADIUS, PROBE_OFFSET_Z)

    /** Marker sphere drawn at each analytic light so the user can see where a light *is*. */
    const val MARKER_RADIUS: Float = 0.045f

    // ── Framing ──────────────────────────────────────────────────────────────────────────────

    /**
     * World extents of the *subject* — hero plus both probes — for
     * [io.github.sceneview.demo.rememberFitOrbitRadius]. Deliberately not the floor: framing on
     * a 4 m ground would push the camera so far back the helmet became a speck.
     */
    const val SUBJECT_EXTENT_X: Float = (PROBE_OFFSET_X + PROBE_RADIUS) * 2f
    const val SUBJECT_EXTENT_Y: Float = HERO_UNITS
    const val SUBJECT_EXTENT_Z: Float = HERO_UNITS

    /**
     * Camera elevation above the stage centre.
     *
     * Higher than the library's stock 8.3°: the floor, the contact shadow and the pool the key
     * light throws are all *on the ground plane*, and at a near-horizontal eye the ground is a
     * line. 16° opens it enough to see the shadow without tipping into a top-down look that
     * would flatten the helmet.
     */
    const val ORBIT_ELEVATION_DEGREES: Float = 20f

    /**
     * Camera height above the stage centre for a given orbit radius.
     *
     * `rememberHeroOrbitCameraManipulator` takes a **horizontal** radius and a height, so the
     * elevation it actually flies at is `atan(height / radius)` — a fixed height therefore means
     * a different angle on every viewport. The stage frames on its widest axis (hero plus both
     * probes, 1 m across), which on a portrait phone puts the camera around 3.5 m out; the 0.42 m
     * height this used to hardcode was 7° there, not the 20° declared above, and on-device QA on
     * 2026-09-08 caught the result: a 4 m floor seen edge-on, filling the lower half of the frame
     * as a black wall with the helmet cut in two by its far edge.
     *
     * Deriving the height from the radius keeps the declared elevation true on every device.
     */
    fun orbitHeight(radius: Float): Float = radius * tan(ORBIT_ELEVATION_DEGREES.toRadians())

    /** One idle orbit lap. Slow — the subject of these screens is the light, not the turntable. */
    const val ORBIT_DURATION_MILLIS: Int = 26_000

    /** Yaw the idle orbit freezes at in QA mode, so captures are comparable run to run. */
    const val STATIC_YAW: Float = 32f

    // ── Three-point rig ──────────────────────────────────────────────────────────────────────

    /** Distance from the stage centre to every analytic light in the rig. */
    const val RIG_RADIUS: Float = 1.35f

    /** Key light elevation — the classic 35–45° portrait key, at the low end so the visor lights. */
    const val KEY_ELEVATION_DEGREES: Float = 38f

    /** Fill sits low and opposite the key: it opens the shadow side, it does not model. */
    const val FILL_ELEVATION_DEGREES: Float = 12f

    /** Fill is 180° across from the key, offset so the two never stack into one source. */
    const val FILL_AZIMUTH_OFFSET_DEGREES: Float = 155f

    /** Rim comes from behind and above to draw the silhouette's edge. */
    const val RIM_ELEVATION_DEGREES: Float = 46f
    const val RIM_AZIMUTH_OFFSET_DEGREES: Float = -125f

    /**
     * Position of a light on the rig sphere.
     *
     * Azimuth is measured from the camera's home direction (+Z) turning towards +X, so azimuth 0
     * puts the light between the viewer and the subject and 90° puts it stage right — the way a
     * lighting diagram is read, rather than the way a maths library defaults.
     */
    fun rigPosition(
        azimuthDegrees: Float,
        elevationDegrees: Float,
        radius: Float = RIG_RADIUS,
    ): Position {
        val azimuth = azimuthDegrees.toRadians()
        val elevation = elevationDegrees.toRadians()
        val horizontal = radius * cos(elevation)
        return Position(
            x = horizontal * sin(azimuth),
            y = radius * sin(elevation),
            z = horizontal * cos(azimuth),
        )
    }

    /**
     * Direction a light at [from] must point to aim at the stage centre.
     *
     * Not normalised on purpose: Filament normalises the light direction itself, and keeping the
     * raw vector means a caller can read it as "towards the origin" without an epsilon guard for
     * the degenerate `from == origin` case, which the rig never produces.
     */
    fun aimAtStage(from: Position): Direction = Direction(-from.x, -from.y, -from.z)

    /**
     * Row-major 3×3 rotation about the world Y axis, in the layout
     * `IndirectLight.setRotation` expects.
     *
     * This is the whole of "rotate the environment": Filament turns the *lighting* — the
     * irradiance and the reflection probe — and leaves the skybox where it is, which is why the
     * demo disables the slider while the sky is drawn rather than letting the two drift apart on
     * screen.
     */
    fun iblRotation(degrees: Float): FloatArray {
        val radians = degrees.toRadians()
        val c = cos(radians)
        val s = sin(radians)
        return floatArrayOf(
            c, 0f, -s,
            0f, 1f, 0f,
            s, 0f, c,
        )
    }

    // ── Environments ─────────────────────────────────────────────────────────────────────────

    /**
     * One bundled HDR, with the two colours its swatch is drawn from.
     *
     * The swatch is a hand-picked sky/ground pair rather than a decoded thumbnail: decoding
     * seven 1.7 MB HDRs to paint seven 36 dp circles would cost more than the environment the
     * user actually picks, and the pair is what a photographer recognises an environment by
     * anyway — warm top over dark floor is the sunset, cold top over pale floor is the overcast.
     */
    data class EnvironmentOption(
        val id: String,
        val label: String,
        val file: String,
        val swatchTop: Color,
        val swatchBottom: Color,
    )

    /** Every HDR in `assets/environments/`, ordered bright to dark. */
    val environments: List<EnvironmentOption> = listOf(
        EnvironmentOption(
            id = "studio",
            label = "Studio",
            file = "environments/studio_2k.hdr",
            swatchTop = Color(0xFFF2EFE8),
            swatchBottom = Color(0xFF6E6A63),
        ),
        EnvironmentOption(
            id = "studio-warm",
            label = "Warm studio",
            file = "environments/studio_warm_2k.hdr",
            swatchTop = Color(0xFFFFE7C2),
            swatchBottom = Color(0xFF4A3B2C),
        ),
        EnvironmentOption(
            id = "cloudy",
            label = "Overcast",
            file = "environments/outdoor_cloudy_2k.hdr",
            swatchTop = Color(0xFFD6DEE8),
            swatchBottom = Color(0xFF7C8794),
        ),
        EnvironmentOption(
            id = "garden",
            label = "Garden",
            file = "environments/chinese_garden_2k.hdr",
            swatchTop = Color(0xFFBFD4C4),
            swatchBottom = Color(0xFF4A5A46),
        ),
        EnvironmentOption(
            id = "sunset",
            label = "Sunset",
            file = "environments/sunset_2k.hdr",
            swatchTop = Color(0xFFFFB874),
            swatchBottom = Color(0xFF3A2A34),
        ),
        EnvironmentOption(
            id = "rooftop-night",
            label = "Rooftop",
            file = "environments/rooftop_night_2k.hdr",
            swatchTop = Color(0xFF3C4A6B),
            swatchBottom = Color(0xFF14171F),
        ),
        EnvironmentOption(
            id = "night-sky",
            label = "Night sky",
            file = "environments/night_sky_2k.hdr",
            swatchTop = Color(0xFF1B2340),
            swatchBottom = Color(0xFF0B0F16),
        ),
    )

    /** The environment both demos open on — neutral, bright, and flattering to a metal helmet. */
    val defaultEnvironment: EnvironmentOption get() = environments.first()

    /** The environment the lab's local reflection probe overrides with — deliberately unmissable. */
    const val PROBE_ENVIRONMENT_FILE: String = "environments/sunset_2k.hdr"

    /**
     * HDR whose sky matches the hour, so the Sun rig's skybox agrees with its sun.
     *
     * `DynamicSkyNode` drives a directional sun from the clock but paints no sky of its own; left
     * on the default neutral environment the scene reads as "noon" at every hour. Three buckets
     * is coarse and deliberately so — it covers the three states a viewer distinguishes at a
     * glance (night, golden hour, day) without pretending to a continuum the assets cannot serve.
     */
    fun skyEnvironmentFor(hour: Float): EnvironmentOption = when {
        hour < NIGHT_END_HOUR || hour >= NIGHT_START_HOUR ->
            environments.first { it.id == "rooftop-night" }
        hour < GOLDEN_MORNING_END_HOUR || hour >= GOLDEN_EVENING_START_HOUR ->
            environments.first { it.id == "sunset" }
        else -> environments.first { it.id == "cloudy" }
    }

    /** Short name of the period an hour falls in — the Sun rig's live readout. */
    fun periodLabel(hour: Float): String = when {
        hour < NIGHT_END_HOUR -> "Night"
        hour < GOLDEN_MORNING_END_HOUR -> "Sunrise"
        hour < 11f -> "Morning"
        hour < 14f -> "Midday"
        hour < GOLDEN_EVENING_START_HOUR -> "Afternoon"
        hour < NIGHT_START_HOUR -> "Golden hour"
        else -> "Night"
    }

    /**
     * Where the day's three states begin and end.
     *
     * Named rather than inlined because the readout and the sky have to agree: on-device QA on
     * 2026-09-08 caught the pill saying "Golden hour" over a flat blue overcast, because the label
     * turned at 17 h and the sky at a different threshold. They now read the same three numbers.
     *
     * The evening boundary is 16 h, not 18 h, because it is set by `DynamicSkyNode`'s own colour
     * ramp: warmth there is quadratic in `1 − sin(elevation)`, which is already 0.38 at 16 h — the
     * frame is visibly golden well before the sun reaches the horizon.
     */
    const val NIGHT_END_HOUR: Float = 5.5f
    const val GOLDEN_MORNING_END_HOUR: Float = 8f
    const val GOLDEN_EVENING_START_HOUR: Float = 16f
    const val NIGHT_START_HOUR: Float = 19.5f

    // ── Key light colour presets ─────────────────────────────────────────────────────────────

    /** A key-light colour, as both the UI swatch and the linear RGB Filament is given. */
    data class LightColor(
        val label: String,
        val swatch: Color,
        val r: Float,
        val g: Float,
        val b: Float,
    )

    /**
     * Four key colours spanning the range a set actually uses — tungsten to daylight to a gelled
     * accent — rather than a rainbow. Values are the linear multipliers, not sRGB: Filament takes
     * a linear colour, and the swatch beside it is the sRGB the user sees.
     */
    val keyColors: List<LightColor> = listOf(
        LightColor("Neutral", Color(0xFFFFF6E8), 1f, 0.97f, 0.92f),
        LightColor("Tungsten", Color(0xFFFFC169), 1f, 0.78f, 0.45f),
        LightColor("Daylight", Color(0xFFCFE0FF), 0.72f, 0.83f, 1f),
        LightColor("Magenta", Color(0xFFFF8FD0), 1f, 0.48f, 0.78f),
    )

    // ── Fog presets ──────────────────────────────────────────────────────────────────────────

    /** A fog colour preset for the lab. */
    data class FogColor(val label: String, val color: Color)

    val fogColors: List<FogColor> = listOf(
        FogColor("Mist", Color(0xFFCCDDFF)),
        FogColor("Warm haze", Color(0xFFFFDDAA)),
        FogColor("Smoke", Color(0xFF8A8F98)),
    )

    // ── Intensities ──────────────────────────────────────────────────────────────────────────

    /** Key light default, candela. Reads as a firm key on the helmet at [RIG_RADIUS]. */
    const val KEY_INTENSITY_DEFAULT: Float = 90_000f
    const val KEY_INTENSITY_MIN: Float = 10_000f
    const val KEY_INTENSITY_MAX: Float = 220_000f

    /** Fill and rim are expressed as a fraction of the key, so one slider keeps the rig in ratio. */
    const val FILL_RATIO: Float = 0.28f
    const val RIM_RATIO: Float = 0.75f

    /** Sun intensity, lux. Above Filament's 110 klx default so it reads against a bright sky. */
    const val SUN_INTENSITY: Float = 160_000f

    /** IBL intensity range for the lab, in lux. The library's balanced default is 10 klx. */
    const val IBL_INTENSITY_DEFAULT: Float = 10_000f
    const val IBL_INTENSITY_MIN: Float = 500f
    const val IBL_INTENSITY_MAX: Float = 60_000f

    // ── Camera exposure ──────────────────────────────────────────────────────────────────────

    /**
     * The exposure slider is a **multiplier on the library's own camera**, not an absolute value.
     *
     * `CameraComponent.setExposure(Float)` — the one-argument overload — is the "match a
     * unit-less engine" form: it pins aperture to f/1.0 and shutter to 1.2 s, which is roughly
     * seventeen thousand times more exposure than the `DefaultCameraNode` the SDK ships
     * (f/12, 1/200 s, ISO 200). Fed a 10 klx indirect light, on-device QA on 2026-09-08 came
     * back a flat white rectangle: no helmet, no floor, no probes.
     *
     * So both screens keep the shipped aperture and shutter and move **sensitivity** only, which
     * is what a photographer changes when the light is fixed: 1.0 is exactly the SDK default,
     * 0.25 is two stops down and 3.0 is a little over one and a half stops up.
     */
    const val CAMERA_APERTURE: Float = 12f
    const val CAMERA_SHUTTER_SPEED: Float = 1f / 200f
    const val CAMERA_BASE_ISO: Float = 200f

    const val EXPOSURE_DEFAULT: Float = 1f
    const val EXPOSURE_MIN: Float = 0.25f
    const val EXPOSURE_MAX: Float = 3f

    /** ISO for an exposure multiplier, clamped into the range Filament accepts (10…204 800). */
    fun sensitivityFor(exposure: Float): Float =
        (CAMERA_BASE_ISO * exposure).coerceIn(10f, 204_800f)

    /** Local reflection probe radius range, metres. */
    const val PROBE_ZONE_DEFAULT: Float = 2.5f
    const val PROBE_ZONE_MIN: Float = 0.5f
    const val PROBE_ZONE_MAX: Float = 6f

    private fun Float.toRadians(): Float = (this * PI_F / 180f)
}

private const val PI_F = 3.1415927f
