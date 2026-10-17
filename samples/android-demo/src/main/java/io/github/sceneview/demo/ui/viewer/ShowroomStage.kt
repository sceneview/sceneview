package io.github.sceneview.demo.ui.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Skybox
import com.google.android.filament.ToneMapper
import com.google.android.filament.View
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.length
import io.github.sceneview.SceneScope
import io.github.sceneview.demo.theme.ShowroomPalette
import io.github.sceneview.demo.theme.StageChrome
import io.github.sceneview.demo.theme.themedStageChrome
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.node.ContactShadowContext
import io.github.sceneview.safeDestroySkybox
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import com.google.android.filament.Box as FilamentBox

/**
 * The numbers behind the Model Viewer's studio showroom ([ShowroomStage]). Pure functions of the
 * subject's size, the camera and the design tokens, so the JVM tests pin every one of them.
 *
 * Every length is a multiple of the subject's bounding radius `r` (half its bounding-box
 * diagonal): a 4 m car and a 4 cm watch stand in the same studio, scaled to them.
 */
internal object ShowroomMath {

    /** The charcoal showroom's lighting — the dark theme's studio. Shared with the web viewer. */
    const val DARK_HDR = "environments/studio_showroom_dark_2k.hdr"

    /** The white photo studio's lighting — the light theme's studio. Shared with the web viewer. */
    const val LIGHT_HDR = "environments/studio_showroom_light_2k.hdr"

    /** `showroom_floor.mat`, compiled by `tools/GenerateFilamat.sh`. */
    const val FLOOR_MATERIAL = "materials/showroom_floor.filamat"

    /** The studio the current theme stands every model in. */
    fun hdrPath(isDark: Boolean): String = if (isDark) DARK_HDR else LIGHT_HDR

    /** Whether [path] is one of the two showroom lighting maps. */
    fun isShowroom(path: String?): Boolean = path == DARK_HDR || path == LIGHT_HDR

    /**
     * IBL intensity of the showroom maps. Their overhead softbox is 8× the cove: at the viewer's
     * historical 30 000 a white sofa clipped to a flat sheet under PBR Neutral.
     */
    const val SHOWROOM_INTENSITY = 18_000f

    /**
     * IBL intensity of the dark showroom. Its map is mostly charcoal wall, so at
     * [SHOWROOM_INTENSITY] chrome that faces the lens (a toaster's lettering, a car's grille)
     * mirrored the dark wall behind the camera and read as black, where the web viewer shows it
     * silver. The floor and the fog divide by it, so the studio's colours do not move.
     */
    const val DARK_SHOWROOM_INTENSITY = 32_000f

    /** IBL intensity the viewer has always given the Poly Haven maps. */
    const val LEGACY_INTENSITY = 30_000f

    /** The IBL intensity of the map at [path] before the user's slider. */
    fun baseIntensity(path: String?): Float = when (path) {
        DARK_HDR -> DARK_SHOWROOM_INTENSITY
        LIGHT_HDR -> SHOWROOM_INTENSITY
        else -> LEGACY_INTENSITY
    }

    /**
     * Irradiance each bundled map sends onto an upward-facing surface, divided by π (what a white
     * Lambertian floor reflects at intensity 1), per linear channel. Measured from the `.hdr` files
     * with `tools/scenes/studio`'s cosine integral; the floor's albedo is solved from it so the
     * floor reaches the screen as its token, whatever lights it.
     */
    private val UPWARD_IRRADIANCE = mapOf(
        "chinese_garden" to Float3(0.4929f, 0.5480f, 0.6459f),
        "sky_on_fire" to Float3(0.4017f, 0.3271f, 0.4753f),
        "studio_warm" to Float3(0.6514f, 0.6699f, 0.7433f),
        "studio" to Float3(0.6323f, 0.5972f, 0.5391f),
        "outdoor_cloudy" to Float3(1.1294f, 1.2135f, 1.4147f),
        "night_sky" to Float3(0.1093f, 0.1070f, 0.1021f),
        "rooftop_night" to Float3(0.5699f, 0.4566f, 0.5889f),
        "studio_showroom_dark" to Float3(1.5669f),
        "studio_showroom_light" to Float3(1.7234f),
    )

    /** A neutral guess for a map the table does not know: the bundled studios' order. */
    private val DEFAULT_IRRADIANCE = Float3(0.6f)

    /** [UPWARD_IRRADIANCE] of the map at [path] (`environments/foo_2k.hdr` → `foo`). */
    fun upwardIrradiance(path: String?): Float3 =
        path?.substringAfterLast('/')?.substringBeforeLast('.')?.removeSuffix("_2k")
            ?.let { UPWARD_IRRADIANCE[it] } ?: DEFAULT_IRRADIANCE

    /** Filament's exposure for a physical camera: `1 / (1.2 · N² / t · 100 / S)`. */
    fun exposure(aperture: Float, shutterSpeed: Float, sensitivity: Float): Float =
        1f / (EXPOSURE_CALIBRATION * aperture * aperture / shutterSpeed * BASE_ISO / sensitivity)

    /**
     * What the SDK's default sun and fill add to an upward-facing floor, as an irradiance over π
     * in lux — the term the floor's albedo must share with the IBL when the lights are on.
     */
    fun lightsTerm(on: Boolean): Float = if (on) DEFAULT_LIGHTS_ON_FLOOR else 0f

    /**
     * One setting of the view's height fog — the part of the showroom that turns a finite floor
     * and a flat backdrop into one seamless cyclorama. See [fog].
     */
    data class Fog(val start: Float, val density: Float, val height: Float, val falloff: Float) {
        /** Whether [other] is this fog within a relative [tolerance] — not worth a new frame. */
        fun closeTo(other: Fog?, tolerance: Float = 1e-3f): Boolean = other != null &&
            near(start, other.start, tolerance) && near(density, other.density, tolerance) &&
            near(height, other.height, tolerance) && near(falloff, other.falloff, tolerance)

        private fun near(a: Float, b: Float, tolerance: Float): Boolean =
            abs(a - b) <= tolerance * maxOf(abs(a), abs(b), 1e-3f)
    }

    /**
     * Solves Filament's exponential height fog for the showroom, from where the eye is.
     *
     * Two constraints, two unknowns (the density at the eye and the height falloff):
     * - **The floor dissolves into the horizon band.** Every floor point sees the same fog per
     *   metre, `3 / (8r)`: 95 % opaque 8 radii past the subject, so the quad's far edge — 60 radii
     *   out — is never seen and the floor has no horizon line.
     * - **The backdrop is a gradient, not a wall.** Above the eye the fog thins with height, so a
     *   direction θ above the horizontal is fogged by `1 − exp(−S / sin θ)`: half way to the
     *   horizon colour at 4°, a quarter at 10°, and the constant skybox shows the zenith colour
     *   higher up. The horizon band always sits at the eye's own height, as in a real cyclorama.
     *
     * The subject is never fogged: the fog starts just past its far side.
     */
    fun fog(eyeY: Float, eyeDistance: Float, floorY: Float, radius: Float): Fog {
        val r = radius.toDouble().takeIf { it.isFinite() && it > MIN_RADIUS } ?: MIN_RADIUS
        val reach = FOG_REACH_RADII * r
        val height = max(eyeY.toDouble() - floorY.toDouble(), FOG_MIN_EYE_HEIGHT_RADII * r)
        val heightTerm = ln(1.0 + FLOOR_OPTICAL_DEPTH * height / (SKY_OPTICAL_DEPTH * reach))
        val falloff = heightTerm / height
        return Fog(
            start = (eyeDistance + FOG_START_RADII * r).toFloat(),
            density = (SKY_OPTICAL_DEPTH * falloff * exp(heightTerm)).toFloat(),
            height = (eyeY - height).toFloat(),
            falloff = falloff.toFloat(),
        )
    }

    /**
     * Camera pitch for a subject whose largest bounding-box side is [extent] metres, in degrees
     * above the horizontal — the web viewer's three steps: a car or a sofa (3 m and up) is shot
     * low, at 9°, the configurator three-quarter; a chair or a bag at 14°; a watch, a phone or a
     * sneaker from a tabletop eye, at 22°.
     */
    fun pitchDegrees(extent: Float): Float = when {
        !extent.isFinite() || extent <= 0f -> SMALL_PITCH
        extent >= LARGE_EXTENT -> LARGE_PITCH
        extent >= MEDIUM_EXTENT -> MEDIUM_PITCH
        else -> SMALL_PITCH
    }

    /** SSAO radius: the crease under a bumper and the seam of a watch strap, both. */
    fun aoRadius(radius: Float): Float = (AO_RADIUS_RADII * radius).clampFinite(0.005f, 0.5f)

    /** SSAO bilateral threshold, scaled with the subject so a watch's AO is not smeared. */
    fun aoBilateralThreshold(radius: Float): Float =
        (AO_BILATERAL_RADII * radius).clampFinite(0.0002f, 0.05f)

    /** The linear colour to draw so that [color] reaches the screen through the tone mapper. */
    fun linearBefore(color: Color, neutral: Boolean): FloatArray =
        if (neutral) ViewerBackdrop.linearBeforeNeutral(color) else ViewerBackdrop.linearBeforeToneMap(color)

    /**
     * The floor's diffuse albedo for [token] to reach the screen as itself: the colour before the
     * tone mapper, divided by what lights the floor (the IBL's upward [irradiance] at
     * [baseIntensity], plus the lights when they are on) times [exposure]. Clamped to the range a
     * real floor can have — a token brighter than the light allows comes out as white paint.
     */
    @Suppress("LongParameterList")
    fun floorAlbedo(
        token: Color,
        neutral: Boolean,
        irradiance: Float3,
        baseIntensity: Float,
        exposure: Float,
        lights: Boolean,
    ): Float3 {
        val target = linearBefore(token, neutral)
        val extra = lightsTerm(lights)
        fun channel(index: Int, upward: Float): Float {
            val albedo = target[index] / ((upward * baseIntensity + extra) * exposure)
            return if (albedo.isFinite()) albedo.coerceIn(MIN_ALBEDO, MAX_ALBEDO) else MIN_ALBEDO
        }
        return Float3(channel(0, irradiance.x), channel(1, irradiance.y), channel(2, irradiance.z))
    }

    /**
     * The fog colour to give Filament so the horizon band reaches the screen as [token]. Filament
     * multiplies it by the IBL luminance (intensity × exposure), so it is divided by both here; the
     * user's lighting slider then dims the fog with everything else.
     */
    fun fogColor(token: Color, neutral: Boolean, baseIntensity: Float, exposure: Float): FloatArray {
        val scale = baseIntensity * exposure
        return linearBefore(token, neutral).map { if (scale > 0f) it / scale else 0f }.toFloatArray()
    }

    /** A floor finish: how glossy, and how far the polished turntable reaches. */
    class Gloss(
        val roughness: Float,
        val reflectance: Float,
        /** Radius of the polished zone, in subject radii. */
        val radiusRadii: Float,
        /** What is left of the reflection away from the subject, `0..1`. */
        val rimSpecular: Float,
    )

    /**
     * Dark showroom: a polished charcoal floor. Its sheen is the studio's lights; the subject's
     * own mirror image is [ShowroomReflection]'s, laid over it.
     */
    val DARK_GLOSS = Gloss(roughness = 0.3f, reflectance = 0.35f, radiusRadii = 1.6f, rimSpecular = 0.25f)

    /** White studio: a satin floor — a reflection there reads as a puddle, so it stays faint. */
    val LIGHT_GLOSS = Gloss(roughness = 0.35f, reflectance = 0.3f, radiusRadii = 1.4f, rimSpecular = 0.3f)

    fun gloss(isDark: Boolean): Gloss = if (isDark) DARK_GLOSS else LIGHT_GLOSS

    /**
     * How much of the floor's sheen survives for an eye looking down at the subject from
     * [eyeY], [eyeDistance] away from its centre at [centerY]: `1` up to [SHEEN_FULL_DEGREES]
     * of elevation, `0` from [SHEEN_GONE_DEGREES]. Seen from above, a glossy floor mirrors the
     * overhead softbox and washes out to grey; the web viewer's floor is unlit and keeps its
     * colour there, so this one hands its sheen back as the eye climbs.
     */
    fun sheen(eyeY: Float, centerY: Float, eyeDistance: Float): Float {
        if (!eyeDistance.isFinite() || eyeDistance <= 1e-6f) return 1f
        val elevation = Math.toDegrees(
            kotlin.math.asin(((eyeY - centerY) / eyeDistance).coerceIn(-1f, 1f).toDouble()),
        ).toFloat()
        val t = ((elevation - SHEEN_FULL_DEGREES) / (SHEEN_GONE_DEGREES - SHEEN_FULL_DEGREES)).coerceIn(0f, 1f)
        return 1f - t * t * (3f - 2f * t)
    }

    /** Elevation up to which the floor keeps its whole sheen — every framing preset is below. */
    const val SHEEN_FULL_DEGREES = 30f

    /** Elevation from which the floor is matte: a plan view reads the tokens. */
    const val SHEEN_GONE_DEGREES = 65f

    /**
     * The shallowest the orbit may look down at the subject, in degrees. The web viewer's floor
     * and dome are unlit and meet at one colour, so its eye can graze the floor; this floor is
     * lit and fogged, and under this angle the two read as a line across the frame.
     */
    const val MIN_EYE_ELEVATION_DEGREES = 8f

    /** The floor quad's side, in subject radii — its edge is far inside the fog. */
    const val FLOOR_SIZE_RADII = 120f

    /** Height of the virtual lamp the pool of light falls off from, in subject radii. */
    const val POOL_RADII = 1.5f

    /** How far the floor sits under the subject's lowest point, so a tyre never z-fights it. */
    const val FLOOR_DROP_RADII = 0.0005f

    /** The broad, soft contact shadow: its fully dark core spans the subject's footprint. */
    const val FLOOR_SHADOW_SPREAD = 3.33f
    const val FLOOR_SHADOW_INTENSITY = 0.42f

    /** The tight, dark contact core right under the subject. */
    const val CONTACT_SHADOW_SPREAD = 2.88f
    const val CONTACT_SHADOW_INTENSITY = 0.55f

    /** Shadow quad Y scale in radii: lifts it `5 mm × this` off the floor, so tiny subjects fit. */
    const val SHADOW_LIFT_RADII = 0.4f

    /** Bloom under the dark showroom: a glint on chrome. The white studio has none — it hazes. */
    const val BLOOM_STRENGTH = 0.12f

    /** The SDK's default bloom, which every other lighting keeps. */
    const val LEGACY_BLOOM_STRENGTH = 0.1f

    /**
     * The studio vignette: the corners are multiplied by a grey (`stage-studio-vignette`), never
     * by black, so the floor in front of the subject keeps its tone and the eye goes to the
     * subject. Same curve as the web viewer.
     */
    fun vignetteGrey(isDark: Boolean): Float = if (isDark) DARK_VIGNETTE_GREY else LIGHT_VIGNETTE_GREY

    /** Where the vignette starts, from the centre: later on the white studio, earlier on charcoal. */
    fun vignetteMidPoint(isDark: Boolean): Float = if (isDark) DARK_VIGNETTE_MID_POINT else LIGHT_VIGNETTE_MID_POINT

    const val VIGNETTE_ROUNDNESS = 0.6f
    const val VIGNETTE_FEATHER = 0.8f

    /**
     * How strongly the floor mirrors the subject ([ShowroomReflection]) — the web viewer's two
     * values: a clear image on the polished charcoal, a faint one on the white satin, where a
     * strong reflection reads as a puddle.
     */
    fun reflectionStrength(isDark: Boolean): Float = if (isDark) DARK_REFLECTION else LIGHT_REFLECTION

    /** The height up a subject of [height] metres at which its reflection has faded out. */
    fun reflectionFadeHeight(height: Float): Float =
        (REFLECTION_FADE_HEIGHT * height).takeIf { it.isFinite() && it > 0f } ?: MIN_FADE_HEIGHT

    /** How far inside the footprint the reflection gives way to the contact shadow, in metres. */
    fun reflectionInset(halfWidth: Float, halfDepth: Float): Float =
        REFLECTION_INSET * 2f * maxOf(halfWidth, halfDepth, 0f)

    /**
     * The lowest the orbit may take the eye, above the floor, in multiples of the subject's longest
     * side — the web viewer's limit. Lower, the floor reads as a line and the backdrop's seam shows.
     */
    const val MIN_EYE_HEIGHT_EXTENTS = 0.02f

    /** The mirror image's size, as a fraction of the view's. */
    const val REFLECTION_SCALE = 0.25f

    /**
     * The model matrix of the eye mirrored in the floor plane `y = floorY`: `R · eye`, with
     * `R = T(0, floorY, 0) · S(1, −1, 1) · T(0, −floorY, 0)`. Column-major, written into [out].
     */
    fun mirrorModelMatrix(eye: DoubleArray, floorY: Float, out: DoubleArray): DoubleArray {
        val twiceFloor = 2.0 * floorY
        for (column in 0 until 4) {
            val base = column * 4
            out[base] = eye[base]
            out[base + 1] = twiceFloor * eye[base + 3] - eye[base + 1]
            out[base + 2] = eye[base + 2]
            out[base + 3] = eye[base + 3]
        }
        return out
    }

    /**
     * The floor material's `reflectionMatrix`: floor model space (the unit quad, centred under
     * [center] on [floorY] and scaled to [FLOOR_SIZE_RADII] radii) → the mirror render's clip
     * space, `projection · view · floor`. Column-major, written into [out].
     */
    @Suppress("LongParameterList")
    fun reflectionMatrix(
        projection: DoubleArray,
        view: DoubleArray,
        center: Position,
        floorY: Float,
        radius: Float,
        out: FloatArray,
    ): FloatArray {
        val side = (FLOOR_SIZE_RADII * radius).toDouble()
        val floor = DoubleArray(16).also {
            it[0] = side
            it[5] = radius.toDouble()
            it[10] = side
            it[12] = center.x.toDouble()
            it[13] = floorY.toDouble()
            it[14] = center.z.toDouble()
            it[15] = 1.0
        }
        val clip = multiply(projection, multiply(view, floor))
        for (i in 0 until 16) out[i] = clip[i].toFloat()
        return out
    }

    /** `a · b`, both column-major 4×4. */
    fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(16).also { out ->
        for (column in 0 until 4) for (row in 0 until 4) {
            var sum = 0.0
            for (k in 0 until 4) sum += a[k * 4 + row] * b[column * 4 + k]
            out[column * 4 + row] = sum
        }
    }

    private fun Float.clampFinite(min: Float, max: Float): Float = if (isNaN()) min else coerceIn(min, max)

    private const val DARK_REFLECTION = 0.26f
    private const val LIGHT_REFLECTION = 0.14f
    private const val REFLECTION_FADE_HEIGHT = 0.4f
    private const val REFLECTION_INSET = 0.04f
    private const val MIN_FADE_HEIGHT = 1e-3f

    private const val EXPOSURE_CALIBRATION = 1.2f
    private const val BASE_ISO = 100f
    private const val DEFAULT_LIGHTS_ON_FLOOR = 3734f
    private const val MIN_RADIUS = 1e-3
    private const val FOG_REACH_RADII = 8.0
    private const val FLOOR_OPTICAL_DEPTH = 3.0

    /** ln 2 · sin 4°: the sky is half way to the horizon colour 4° above the eye. */
    private const val SKY_OPTICAL_DEPTH = 0.048352
    private const val FOG_MIN_EYE_HEIGHT_RADII = 0.05
    private const val FOG_START_RADII = 1.05
    private const val LARGE_EXTENT = 3f
    private const val MEDIUM_EXTENT = 0.6f
    private const val LARGE_PITCH = 9f
    private const val MEDIUM_PITCH = 14f
    private const val SMALL_PITCH = 22f
    private const val AO_RADIUS_RADII = 0.12f
    private const val AO_BILATERAL_RADII = 0.02f
    private const val MIN_ALBEDO = 0.02f
    private const val MAX_ALBEDO = 0.9f
    private const val DARK_VIGNETTE_GREY = 0.6f
    private const val LIGHT_VIGNETTE_GREY = 0.8f
    private const val DARK_VIGNETTE_MID_POINT = 0.6f
    private const val LIGHT_VIGNETTE_MID_POINT = 0.75f
}

/**
 * The Model Viewer's studio showroom: a seamless cyclorama the subject stands in — the floor's
 * pool of light, its satin sheen, the floor dissolving into the horizon band, the backdrop
 * darkening towards the zenith. Three Filament objects carry it:
 * - [skybox], a constant colour: the zenith;
 * - [floorMaterial], `showroom_floor.mat` on a quad under the subject ([ShowroomFloor]);
 * - the [view]'s height fog, which carries both into the horizon colour ([ShowroomMath.fog]).
 *
 * Every colour is a `stage-studio-*` token pre-compensated for the tone mapper and the light, so
 * a capture reads the token back. [dress] applies a [Look] (composition), [onFrame] follows the
 * eye (render loop); both say whether they changed anything, and the caller asks for a frame.
 * The subject's mirror image in the floor is [ShowroomReflection]'s: it feeds the material's
 * `reflection` input.
 */
internal class ShowroomStage(
    private val view: View,
    /** The backdrop — hand it to the scene as the environment's skybox. */
    val skybox: Skybox,
    /** The floor's material instance — owned by the [MaterialLoader] that created it. */
    val floorMaterial: MaterialInstance,
) {
    /** Everything the stage's colours and fog depend on, but the eye. */
    data class Look(
        val palette: ShowroomPalette,
        val isDark: Boolean,
        /** Whether the view tone-maps with PBR Neutral ([ShowroomToneMapping]), else Filmic. */
        val neutral: Boolean,
        /** [ShowroomMath.upwardIrradiance] of the lighting on screen. */
        val irradiance: Float3,
        /** [ShowroomMath.baseIntensity] of the lighting on screen. */
        val baseIntensity: Float,
        /** The user's lighting slider, which dims the backdrop with the light. */
        val slider: Float,
        val exposure: Float,
        /** Whether the SDK's sun and fill are in the scene. */
        val lights: Boolean,
        /** Whether the showroom is on screen at all (false while the user shows the HDR itself). */
        val staged: Boolean,
        /** The subject's bounding-box centre, at rest. */
        val center: Position,
        val floorY: Float,
        val radius: Float,
    )

    private var look: Look? = null
    private var fog: ShowroomMath.Fog? = null
    private var fogOn = false
    private var sheen = Float.NaN

    /** Applies [look]; `true` when anything changed. Main thread (Filament). */
    fun dress(look: Look, eye: Position): Boolean {
        if (look == this.look) return false
        this.look = look
        val top = ShowroomMath.linearBefore(look.palette.top, look.neutral)
        skybox.setColor(top[0] * look.slider, top[1] * look.slider, top[2] * look.slider, 1f)
        val center = albedo(look.palette.pool, look)
        val rim = albedo(look.palette.floor, look)
        val gloss = ShowroomMath.gloss(look.isDark)
        floorMaterial.apply {
            setParameter(PARAM_CENTER_COLOR, center.x, center.y, center.z)
            setParameter(PARAM_RIM_COLOR, rim.x, rim.y, rim.z)
            // The floor is a unit quad scaled by FLOOR_SIZE_RADII · r, and the material measures
            // in its model space: every radius is handed over in floor widths.
            setParameter(PARAM_POOL_RADIUS, ShowroomMath.POOL_RADII / ShowroomMath.FLOOR_SIZE_RADII)
            setParameter(PARAM_ROUGHNESS, gloss.roughness)
            setParameter(PARAM_GLOSS_RADIUS, gloss.radiusRadii / ShowroomMath.FLOOR_SIZE_RADII)
            setParameter(PARAM_RIM_SPECULAR, gloss.rimSpecular)
        }
        // The fog colour follows the look: write it whole now, so the first frame is fogged.
        fog = null
        sheen = Float.NaN
        applyFog(eye)
        applySheen(eye)
        return true
    }

    /**
     * Follows the eye; `true` when the fog moved and the frame on screen is stale. Called after
     * each presented frame, so it settles within one more frame and then stays quiet — the loop
     * parks again.
     */
    fun onFrame(eye: Position): Boolean {
        val fogMoved = applyFog(eye)
        return applySheen(eye) || fogMoved
    }

    /** Fades the floor's specular with the eye's elevation ([ShowroomMath.sheen]). */
    private fun applySheen(eye: Position): Boolean {
        val look = look ?: return false
        val next = ShowroomMath.sheen(eye.y, look.center.y, length(eye - look.center))
        if (abs(next - sheen) < SHEEN_STEP) return false
        sheen = next
        floorMaterial.setParameter(PARAM_REFLECTANCE, ShowroomMath.gloss(look.isDark).reflectance * next)
        return true
    }

    private fun albedo(token: Color, look: Look): Float3 = ShowroomMath.floorAlbedo(
        token = token,
        neutral = look.neutral,
        irradiance = look.irradiance,
        baseIntensity = look.baseIntensity,
        exposure = look.exposure,
        lights = look.lights,
    )

    private fun applyFog(eye: Position): Boolean {
        val look = look ?: return false
        if (!look.staged) {
            if (!fogOn) return false
            view.fogOptions = view.fogOptions.also { it.enabled = false }
            fogOn = false
            fog = null
            return true
        }
        val next = ShowroomMath.fog(eye.y, length(eye - look.center), look.floorY, look.radius)
        if (fogOn && next.closeTo(fog)) return false
        view.fogOptions = view.fogOptions.also {
            it.enabled = true
            it.distance = next.start
            it.cutOffDistance = Float.POSITIVE_INFINITY
            it.maximumOpacity = 1f
            it.height = next.height
            it.heightFalloff = next.falloff
            it.density = next.density
            it.color = ShowroomMath.fogColor(look.palette.horizon, look.neutral, look.baseIntensity, look.exposure)
            it.fogColorFromIbl = false
            it.inScatteringSize = -1f
        }
        fog = next
        fogOn = true
        return true
    }

    /** Turns the fog off — the view outlives the stage when the viewer leaves composition. */
    fun clear() {
        if (fogOn) view.fogOptions = view.fogOptions.also { it.enabled = false }
        fogOn = false
        fog = null
        look = null
    }

    private companion object {
        const val PARAM_CENTER_COLOR = "centerColor"
        const val PARAM_RIM_COLOR = "rimColor"
        const val PARAM_POOL_RADIUS = "poolRadius"
        const val PARAM_ROUGHNESS = "roughness"
        const val PARAM_REFLECTANCE = "reflectance"
        const val PARAM_GLOSS_RADIUS = "glossRadius"
        const val PARAM_RIM_SPECULAR = "rimSpecular"

        /** The sheen change under which the floor is left alone, so the render loop parks. */
        const val SHEEN_STEP = 0.01f
    }
}

/**
 * The [ShowroomStage] for [view]: its backdrop skybox and its floor material. The skybox is freed
 * here; the floor material and its instance by [materialLoader].
 */
@Composable
internal fun rememberShowroomStage(engine: Engine, view: View, materialLoader: MaterialLoader): ShowroomStage {
    val stage = remember(engine, view, materialLoader) {
        ShowroomStage(
            view = view,
            skybox = Skybox.Builder().color(0f, 0f, 0f, 1f).build(engine),
            floorMaterial = materialLoader.createInstance(
                materialLoader.createMaterial(ShowroomMath.FLOOR_MATERIAL),
            ),
        )
    }
    DisposableEffect(engine, stage) {
        onDispose {
            stage.clear()
            engine.safeDestroySkybox(stage.skybox)
        }
    }
    return stage
}

/**
 * Tone-maps [view] with Khronos PBR Neutral while [neutral], else with the view's own grading
 * (the SDK's Filmic). Neutral keeps a product's authored colour — a white sofa white, a red car
 * the red it was painted — and the showroom's tokens are inverted through it
 * ([ViewerBackdrop.linearBeforeNeutral]). The original grading is put back on leaving.
 */
@Composable
internal fun ShowroomToneMapping(engine: Engine, view: View, neutral: Boolean, requestRender: () -> Unit) {
    val neutralGrading = remember(engine) {
        ColorGrading.Builder().toneMapper(ToneMapper.PBRNeutralToneMapper()).build(engine)
    }
    DisposableEffect(engine, neutralGrading) {
        onDispose { runCatching { engine.destroyColorGrading(neutralGrading) } }
    }
    val original = remember(view) { view.colorGrading }
    DisposableEffect(view, original) {
        onDispose { view.colorGrading = original }
    }
    DisposableEffect(view, neutral, neutralGrading) {
        view.colorGrading = if (neutral) neutralGrading else original
        requestRender()
        onDispose { }
    }
}

/**
 * The showroom's post-processing on [view], applied after the SDK's render-quality preset — so
 * compose it after the `SceneView` call. Ambient occlusion scales with the subject's [radius];
 * bloom stays off on the white studio, where it only hazes; a grey vignette
 * ([ShowroomMath.vignetteGrey]) lets the studio's corners fall away in both themes. No
 * screen-space reflections: the floor's mirror image is a planar one ([ShowroomReflection]).
 */
@Composable
@Suppress("LongParameterList")
internal fun ShowroomViewOptions(
    view: View,
    isDark: Boolean,
    staged: Boolean,
    showroomLit: Boolean,
    radius: Float,
    requestRender: () -> Unit,
) {
    LaunchedEffect(view, isDark, staged, showroomLit, radius) {
        view.ambientOcclusionOptions = view.ambientOcclusionOptions.also {
            it.enabled = true
            it.radius = ShowroomMath.aoRadius(radius)
            it.bilateralThreshold = ShowroomMath.aoBilateralThreshold(radius)
        }
        view.bloomOptions = view.bloomOptions.also {
            it.enabled = isDark || !showroomLit
            it.strength = if (showroomLit) ShowroomMath.BLOOM_STRENGTH else ShowroomMath.LEGACY_BLOOM_STRENGTH
        }
        view.vignetteOptions = view.vignetteOptions.also {
            val grey = ShowroomMath.vignetteGrey(isDark)
            it.enabled = staged && showroomLit
            it.midPoint = ShowroomMath.vignetteMidPoint(isDark)
            it.roundness = ShowroomMath.VIGNETTE_ROUNDNESS
            it.feather = ShowroomMath.VIGNETTE_FEATHER
            it.color = floatArrayOf(grey, grey, grey, 1f)
        }
        requestRender()
    }
}

/** Whether the showroom is the dark one: the themed stage's chrome is the dark media chrome. */
@Composable
internal fun showroomIsDark(): Boolean = themedStageChrome() === StageChrome.Media

/**
 * The showroom's floor and contact shadows under a subject whose bounding box is centred on
 * [center], rests on [floorY] and has bounding radius [radius]. The shadows follow the subject's
 * footprint ([halfWidth] × [halfDepth], turned by [yawDegrees]) and fade in with [shadowAlpha];
 * the floor is drawn only while [showFloor].
 */
@Composable
@Suppress("LongParameterList")
internal fun SceneScope.ShowroomFloor(
    stage: ShowroomStage,
    center: Position,
    floorY: Float,
    radius: Float,
    halfWidth: Float,
    halfDepth: Float,
    yawDegrees: Float,
    showFloor: Boolean,
    shadowAlpha: Float,
) {
    val r = radius.takeIf { it.isFinite() && it > 0f } ?: return
    if (showFloor) {
        val side = ShowroomMath.FLOOR_SIZE_RADII * r
        PlaneNode(
            // A unit quad, scaled: its geometry and bounding box never change, so the recipe below
            // is never undone by a geometry update. Y is scaled by r for the bounding box only.
            size = Size(1f, 0f, 1f),
            materialInstance = stage.floorMaterial,
            position = Position(center.x, floorY - ShowroomMath.FLOOR_DROP_RADII * r, center.z),
            scale = Scale(side, r, side),
            apply = {
                // The device-proven flat receiver recipe (#2620, see ShadowReceiverPlaneNode):
                // a zero-thickness quad that receives the sun's shadow crashes the cascaded
                // shadow build on real GPUs unless it never casts, is never culled, and carries
                // a box with some height — here from the floor to 2r above it.
                isShadowCaster = false
                isShadowReceiver = true
                isTouchable = false
                setCulling(false)
                axisAlignedBoundingBox = FilamentBox(0f, 1f, 0f, 0.5f, 1f, 0.5f)
            },
        )
    }
    val alpha = shadowAlpha.coerceIn(0f, 1f)
    val shadowPosition = Position(center.x, floorY, center.z)
    val shadowRotation = Rotation(y = yawDegrees)
    ContactShadow(
        size = Size(1f, 0f, 1f),
        context = ContactShadowContext.Floor,
        intensity = ShowroomMath.FLOOR_SHADOW_INTENSITY * alpha,
        position = shadowPosition,
        rotation = shadowRotation,
        scale = Scale(
            ShowroomMath.FLOOR_SHADOW_SPREAD * halfWidth,
            ShowroomMath.SHADOW_LIFT_RADII * r,
            ShowroomMath.FLOOR_SHADOW_SPREAD * halfDepth,
        ),
    )
    ContactShadow(
        size = Size(1f, 0f, 1f),
        context = ContactShadowContext.TableTop,
        intensity = ShowroomMath.CONTACT_SHADOW_INTENSITY * alpha,
        position = shadowPosition,
        rotation = shadowRotation,
        scale = Scale(
            ShowroomMath.CONTACT_SHADOW_SPREAD * halfWidth,
            ShowroomMath.SHADOW_LIFT_RADII * r,
            ShowroomMath.CONTACT_SHADOW_SPREAD * halfDepth,
        ),
    )
}
