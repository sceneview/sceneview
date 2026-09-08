package io.github.sceneview.node

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import com.google.android.filament.Engine
import com.google.android.filament.LightManager
import io.github.sceneview.NULL_ENTITY
import io.github.sceneview.SceneScope
import io.github.sceneview.math.Direction
import io.github.sceneview.math.colorOf
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A composable node that drives the scene's sun light based on time-of-day.
 *
 * [DynamicSkyNode] places a [LightManager.Type.SUN] light in the scene and updates its
 * colour, intensity and direction every recomposition as [timeOfDay] changes. The sun rises
 * in the east (–X), arcs overhead at noon, and sets in the west (+X).
 *
 * ### Colour model
 * | Time range        | Colour                                   |
 * |-------------------|------------------------------------------|
 * | Night (< 6, > 18) | near-black (0.02, 0.02, 0.05)           |
 * | Sunrise / sunset  | warm orange–red (lerp from noon colour)  |
 * | Midday            | white (1.0, 0.98, 0.95)                 |
 *
 * The scene's sun intensity is scaled proportionally to the sun's elevation so the scene
 * darkens naturally at night.
 *
 * @param timeOfDay    Hour of day in [0.0, 24.0). 0 = midnight, 6 = sunrise, 12 = noon,
 *                     18 = sunset.
 * @param turbidity    Atmospheric turbidity / haze factor [1.0, 10.0]. Higher values
 *                     produce a more orange-red tint near the horizon. Default 2.0.
 * @param sunIntensity Maximum sun illuminance in lux at solar noon. Default 110 000 lux.
 */
@Composable
fun SceneScope.DynamicSkyNode(
    timeOfDay: Float = 12f,
    turbidity: Float = 2f,
    sunIntensity: Float = 110_000f
) {
    val node = remember(engine) {
        DynamicSkyNodeImpl(engine = engine)
    }

    SideEffect {
        node.update(timeOfDay = timeOfDay, turbidity = turbidity, sunIntensity = sunIntensity)
    }

    NodeLifecycle(node = node, content = null)
}

/**
 * Implementation backing [DynamicSkyNode].
 *
 * Extends [LightNode] so it participates in the normal SceneScope lifecycle (auto-attached and
 * auto-destroyed). The [LightComponent] interface properties ([lightDirection], [color],
 * [intensity]) are written on every call to [update].
 */
internal class DynamicSkyNodeImpl(engine: Engine) : LightNode(
    engine = engine,
    entity = NULL_ENTITY,
    builder = LightManager.Builder(LightManager.Type.SUN).apply {
        intensity(110_000f)
        color(1f, 1f, 1f)
        castShadows(true)
        sunAngularRadius(1.9f)
        sunHaloSize(10f)
        sunHaloFalloff(80f)
    }
) {

    /**
     * Apply time-of-day parameters.
     *
     * Called from a [androidx.compose.runtime.SideEffect] on every recomposition so that
     * Compose state changes are reflected immediately on the next frame.
     */
    fun update(timeOfDay: Float, turbidity: Float, sunIntensity: Float) {
        // ── Sun elevation angle ──────────────────────────────────────────────────────────────────
        // Map timeOfDay (0..24) to a sine-based elevation in [–1, 1].
        // At 6 h the sun is on the eastern horizon (elevation = 0),
        // at 12 h it is overhead (elevation = 1),
        // at 18 h it is on the western horizon (elevation = 0),
        // outside [6, 18] it is below the horizon (elevation < 0).
        val hourAngle = ((timeOfDay - 6f) / 12f) * PI.toFloat()  // 0 at sunrise, π at sunset
        val elevation = sin(hourAngle).coerceIn(-1f, 1f)
        val azimuthCos = -cos(hourAngle)                          // east→west sweep on X axis

        // Vector pointing *toward* the sun: up at noon, on the horizon at 6 h and 18 h.
        val toSunX = azimuthCos * 0.6f
        val toSunY = elevation
        val toSunZ = -0.5f                                         // slight southward tilt
        val len = sqrt(toSunX * toSunX + toSunY * toSunY + toSunZ * toSunZ)
            .coerceAtLeast(1e-6f)
        // Filament's light direction is the direction the light *travels*, i.e. from the sun
        // down onto the scene — the opposite of the vector above. Before #3496 this was set to
        // the toward-the-sun vector, so a noon sun lit the scene from underneath the floor: no
        // cast shadow, and the time-of-day slider only changed the colour.
        lightDirection = Direction(x = -toSunX / len, y = -toSunY / len, z = -toSunZ / len)

        // ── Colour ───────────────────────────────────────────────────────────────────────────────
        color = computeSunColor(elevation, turbidity)

        // ── Intensity ────────────────────────────────────────────────────────────────────────────
        // Scale intensity by elevation: no direct light below the horizon.
        intensity = sunIntensity * max(0f, elevation)
    }

    /**
     * Compute the sun colour for a given normalised [elevation] and [turbidity].
     *
     * - elevation ≤ 0 → night (very dark, slight blue cast)
     * - elevation ≈ 0 → horizon (warm orange, boosted by turbidity)
     * - elevation = 1 → noon (near-white)
     */
    private fun computeSunColor(elevation: Float, turbidity: Float): io.github.sceneview.math.Color {
        if (elevation <= 0f) {
            // Night — faint blue ambient tint keeps the sun disc off
            return colorOf(r = 0.02f, g = 0.02f, b = 0.06f)
        }

        // How much "horizon warmth" to apply: strongest near the horizon, zero at noon.
        val horizonFactor = (1f - elevation).let { it * it }  // quadratic so warmth lingers
        val turbidityBoost = ((turbidity - 1f) / 9f).coerceIn(0f, 1f)

        // Warm sunset / sunrise colour (orange-red)
        val warmR = 1.0f
        val warmG = 0.45f + 0.05f * turbidityBoost
        val warmB = 0.20f - 0.10f * turbidityBoost

        // Noon colour (near-white, slightly warm)
        val noonR = 1.00f
        val noonG = 0.98f
        val noonB = 0.95f

        return colorOf(
            r = lerp(noonR, warmR, horizonFactor),
            g = lerp(noonG, warmG, horizonFactor),
            b = lerp(noonB, warmB, horizonFactor)
        )
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
