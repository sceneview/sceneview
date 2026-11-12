package io.github.sceneview.demo.demos.internal.feel

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Product-shot travel in model space. The caller supplies the 450 ms motion-token clock. */
internal object MatterTransition {
    private fun smooth(t: Float) = t.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    fun outgoingTravel(progress: Float): Float = (progress / .40f).coerceIn(0f, 1f).let { it * it }
    fun incomingTravel(progress: Float): Float = 1f - smooth((progress - .40f) / .42f)
    fun height(kind: FeelMaterial, progress: Float, outgoing: Boolean): Float {
        if (outgoing) return -.14f * outgoingTravel(progress)
        val enter = incomingTravel(progress)
        // A stage lift reveals the incoming solid through the floor, with very little lateral travel.
        val arrive = ((progress - .82f) / .18f).coerceIn(0f, 1f)
        val amplitude = when (kind) {
            FeelMaterial.Gel -> .045f
            FeelMaterial.MemoryFoam -> .018f
            FeelMaterial.Velvet, FeelMaterial.Leather -> .012f
            else -> .008f
        }
        val settle = amplitude * sin(arrive * PI.toFloat()) * (1f - arrive)
        return -(FeelSimulation.PAD_THICKNESS + .10f) * enter + settle
    }
    fun shadow(progress: Float): Float = if (progress < .40f) {
        1f - smooth(progress / .40f)
    } else smooth((progress - .52f) / .48f)
    fun settleDepth(kind: FeelMaterial, ageSeconds: Float): Float {
        if (ageSeconds < 0f) return 0f
        return when (kind) {
            FeelMaterial.Gel -> .055f * exp(-ageSeconds * 12f) * sin(ageSeconds * 38f)
            FeelMaterial.MemoryFoam -> .035f * exp(-ageSeconds * 8f)
            else -> 0f
        }
    }
}

/** No idle timer jobs: one phase, evaluated by the existing main-thread frame callback. */
internal class MatterIdle {
    private var idleSince: Long? = null
    private var held = false
    private var frozenYaw = 0f
    var yawDegrees = 0f
        private set
    fun touch(now: Long, down: Boolean) {
        idleSince = now
        held = down
        frozenYaw = yawDegrees
        // Freeze the CURRENT pose immediately. Returning to the base pose here would jump
        // the sample under the finger before screenToRay picking.
    }
    fun advance(now: Long, enabled: Boolean): Boolean {
        val previous = yawDegrees
        val start = idleSince ?: now.also { idleSince = it }
        if (!enabled) {
            yawDegrees = 0f
            idleSince = now
        } else if (!held && now - start > DELAY_MILLIS) {
            val seconds = (now - start - DELAY_MILLIS) / 1000f
            // Start with zero velocity from the frozen pose, then reach the full ±6° orbit.
            val ramp = (seconds / 2f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
            yawDegrees = frozenYaw * (1f - ramp) + AMPLITUDE * sin(seconds * (2f * PI.toFloat() / PERIOD_SECONDS)) * ramp
        }
        return previous != yawDegrees
    }
    companion object {
        const val DELAY_MILLIS = 4000L
        const val PERIOD_SECONDS = 12f
        const val AMPLITUDE = 6f
    }
}
