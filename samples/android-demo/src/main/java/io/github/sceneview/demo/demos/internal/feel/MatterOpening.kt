package io.github.sceneview.demo.demos.internal.feel

import kotlin.math.exp
import kotlin.math.sin

/** One visit, one heavy load. The mark rides the same wide well the shader draws. */
internal class MatterOpening(enabled: Boolean) {
    var finished = !enabled
        private set
    var visible = false
        private set
    var heightAbovePad = 0f
        private set
    var loadKg = 0f
        private set
    var impactDepthMm = 0f
        private set
    var squash = 0f
        private set
    var travel = TRAVEL
    private var startedAt: Long? = null

    /** True exactly once, on the first frame crossing contact (also with a missed frame). */
    fun advance(now: Long): Boolean {
        if (finished) return false
        val start = startedAt ?: now.also { startedAt = it }
        val age = (now - start).coerceAtLeast(0L)
        val impact = loadKg == 0f && age in FALL_MILLIS until LIFT_MILLIS
        if (age >= TOTAL_MILLIS) { cancel(); return false }
        visible = age < LIFT_MILLIS + LIFT_DURATION
        loadKg = if (age in FALL_MILLIS until LIFT_MILLIS) MARK_MASS_KG else 0f
        impactDepthMm = if (loadKg > 0f) 12f * smooth((age - FALL_MILLIS) / 120f) else 0f
        val impactAge = (age - FALL_MILLIS) / 1000f
        squash = if (impactAge in 0f.. .32f) {
            .045f * exp(-impactAge * 10f) * sin(impactAge * 18f).coerceAtLeast(0f)
        } else 0f
        heightAbovePad = when {
            age < FALL_MILLIS -> travel * (1f - (age.toFloat() / FALL_MILLIS).let { it * it })
            age < LIFT_MILLIS -> 0f
            else -> travel * smooth((age - LIFT_MILLIS).toFloat() / LIFT_DURATION)
        }
        return impact
    }

    fun cancel() {
        finished = true
        visible = false
        loadKg = 0f
        impactDepthMm = 0f
        squash = 0f
    }

    private fun smooth(t: Float): Float = t.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

    companion object {
        fun isAllowed(systemAnimations: Boolean, qaMode: Boolean): Boolean = systemAnimations && !qaMode
        const val FALL_MILLIS = 400L
        const val LIFT_MILLIS = 920L // 120 ms compression + 400 ms rest.
        const val LIFT_DURATION = 900L
        const val TOTAL_MILLIS = 2220L
        const val MARK_MASS_KG = 0.75f
        const val MARK_WIDTH = FeelSimulation.PAD_HALF_WIDTH * 2f / 5f
        const val WELL_RADIUS = 1.65f
        const val TRAVEL = 1.1f
        const val SHOW_BRACKETS = false
    }
}
