package io.github.sceneview.demo.ui.home

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.normalize
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * The home hero's camera, as a pure function of how far the page has been scrolled.
 *
 * The whole point of this file is that it is the screen's **only** writer of the hero camera,
 * and that it has **no memory**: `pose = f(p, u, psi, width, height)`. A camera that is
 * re-derived from scratch every frame cannot drift, cannot fight a second animator, and cannot
 * jump when a gesture is interrupted — the four "camera jump" cases this hero replaces all came
 * from two writers sharing one transform.
 *
 * It is also deliberately free of Compose, Filament and Android: the numbers below are checked
 * against a table that the iOS port shares (`HomeHeroPoseTest`), and a table is only worth
 * something if both platforms can run it.
 *
 * Coordinates: `cx` / `cy` are where the subject's centre should land **on screen**, in dp from
 * the stage's top-left. The stage is pinned — it never moves and never resizes — so the whole
 * choreography is the camera's, and the viewport's, and nothing in the layout depends on it.
 */
internal object HomeHeroPose {

    /** Stage height at rest, in dp. The viewport is this tall for the life of the screen. */
    const val STAGE_HEIGHT_DP = 420f

    /** The band the hero never shrinks below — Thomas' decision: it is permanent. */
    const val DOCK_HEIGHT_DP = 80f

    /** Scroll distance, in dp, over which `p` runs 0 -> 1. */
    const val DOLLY_DISTANCE_DP = STAGE_HEIGHT_DP - DOCK_HEIGHT_DP

    /**
     * Vertical field of view, degrees. The same number on all four surfaces, which is what lets
     * one table of poses be the acceptance test for Android and iOS both.
     */
    const val VERTICAL_FOV_DEGREES = 46.4

    /** Subject size at the dock, in dp. */
    private const val DOCK_MODEL_DP = 48f

    /** Left inset of the docked subject, in dp. */
    private const val DOCK_PADDING_DP = 16f

    /** How many samples the monotone size table holds. */
    private const val SIZE_SAMPLES = 65

    /**
     * Height, in dp, that the text block claims out of the stage at a given `p`.
     *
     * The subject is sized against what is *left*, never against the stage: that is the
     * difference between "the model is big" and "the model is big and the title is legible".
     */
    private fun textBlockDp(p: Float): Float = lerp(152f, 80f, smoothStep(0f, 0.62f, p))

    /**
     * Subject size in dp, tabulated and made monotone.
     *
     * The law is a blend of two caps — one that protects the text, one that aims at the dock —
     * and a blend of two monotone functions is not itself monotone. A subject that grew back
     * mid-scroll would read as the camera stuttering, so the table carries a running minimum and
     * the curve can only ever come down. Sampling (rather than solving) is what makes the same
     * table expressible in Swift without porting a solver.
     */
    private val sizeTable: FloatArray = FloatArray(SIZE_SAMPLES).also { table ->
        var running = Float.MAX_VALUE
        for (i in 0 until SIZE_SAMPLES) {
            val p = i.toFloat() / (SIZE_SAMPLES - 1)
            val visible = STAGE_HEIGHT_DP - p * DOLLY_DISTANCE_DP
            val free = visible - textBlockDp(p)
            val capText = 0.84f * free
            val capDock = visible * lerp(0.66f, DOCK_MODEL_DP / DOCK_HEIGHT_DP, smoothStep(0.75f, 1f, p))
            running = minOf(running, lerp(capText, capDock, smoothStep(0.45f, 0.70f, p)))
            table[i] = running
        }
    }

    private fun sizeDp(p: Float): Float {
        val x = p.coerceIn(0f, 1f) * (SIZE_SAMPLES - 1)
        val i = x.toInt()
        if (i >= SIZE_SAMPLES - 1) return sizeTable[SIZE_SAMPLES - 1]
        return lerp(sizeTable[i], sizeTable[i + 1], x - i)
    }

    /**
     * The pose to draw.
     *
     * @param progress   `p`, 0 at rest, 1 docked. Clamped.
     * @param pagerOffset `u`, the featured pager's fractional page offset: the one thing a
     *                   horizontal swipe is allowed to move, and it moves yaw only.
     * @param idleYaw    `psi`, the bounded idle turntable's contribution. Faded out as `(1 - p)^2`
     *                   so the docked pose is exact whatever the turntable was doing.
     * @param widthDp    stage width.
     * @param subjectUnits the subject's largest dimension in world units, after normalisation.
     */
    fun pose(
        progress: Float,
        pagerOffset: Float = 0f,
        idleYaw: Float = 0f,
        widthDp: Float,
        subjectUnits: Float,
    ): HeroPose {
        val p = progress.coerceIn(0f, 1f)
        val visible = STAGE_HEIGHT_DP - p * DOLLY_DISTANCE_DP
        val size = sizeDp(p)
        val free = visible - textBlockDp(p)

        val cx = lerp(widthDp / 2f, DOCK_PADDING_DP + size / 2f, smoothStep(0.38f, 0.62f, p))
        val cy = lerp(free / 2f + 4f, visible / 2f, smoothStep(0.50f, 0.75f, p))
        val azimuth = -28f + 60f * p + 100f * pagerOffset + idleYaw * (1f - p) * (1f - p)
        val elevation = lerp(4f, 18f, easeOutCubic(p))

        // Unit conversion: `k` world units per dp at the subject's plane, and the distance that
        // makes a subject of `subjectUnits` cover `size` dp of a `STAGE_HEIGHT_DP` viewport.
        // STAGE_HEIGHT_DP, not `visible`: the viewport is pinned, so the projection never changes
        // — only what the clip shows of it. That is what removes the resize jump entirely.
        val k = subjectUnits / size
        val distance = subjectUnits * STAGE_HEIGHT_DP /
            (2f * tan(Math.toRadians(VERTICAL_FOV_DEGREES / 2.0)).toFloat() * size)

        val azimuthRad = Math.toRadians(azimuth.toDouble())
        val elevationRad = Math.toRadians(elevation.toDouble())
        val offset = Float3(
            (sin(azimuthRad) * cos(elevationRad)).toFloat(),
            sin(elevationRad).toFloat(),
            (cos(azimuthRad) * cos(elevationRad)).toFloat(),
        )
        // The camera's own basis. `offset` points from target to eye, so forward is its negation;
        // both depend on the angles only, never on the target, so there is no circularity here.
        val forward = -offset
        val right = normalize(cross(forward, WORLD_UP))
        val up = cross(right, forward)

        // Framing is done by moving the *target*, not the subject: the subject stays at the
        // origin and stays the thing the viewer is looking at. Pushing the target up by
        // `(cy - H/2)` lands the origin that much lower on screen, which is the sign below.
        val target = right * (-(cx - widthDp / 2f) * k) + up * ((cy - STAGE_HEIGHT_DP / 2f) * k)

        return HeroPose(
            progress = p,
            visibleHeightDp = visible,
            sizeDp = size,
            centerXDp = cx,
            centerYDp = cy,
            azimuthDegrees = azimuth,
            elevationDegrees = elevation,
            exposure = lerp(1f, 0.9f, p),
            distance = distance,
            target = target,
            eye = target + offset * distance,
        )
    }

    private val WORLD_UP = Float3(0f, 1f, 0f)

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun easeOutCubic(t: Float): Float {
        val x = 1f - t.coerceIn(0f, 1f)
        return 1f - x * x * x
    }

    private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge0 == edge1) return if (x < edge0) 0f else 1f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

/**
 * One frame of the hero's choreography. Every field is derived, none is state.
 *
 * @param visibleHeightDp what the clip shows of the pinned stage — the *only* height anything on
 *   screen is allowed to read, and never a layout height.
 * @param sizeDp the subject's on-screen size; [HomeHeroPoseTest] asserts it never grows and never
 *   falls below 45 % of [visibleHeightDp], which is the legibility floor Thomas set.
 */
internal data class HeroPose(
    val progress: Float,
    val visibleHeightDp: Float,
    val sizeDp: Float,
    val centerXDp: Float,
    val centerYDp: Float,
    val azimuthDegrees: Float,
    val elevationDegrees: Float,
    val exposure: Float,
    val distance: Float,
    val target: Float3,
    val eye: Float3,
)
