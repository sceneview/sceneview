package io.github.sceneview.demo.demos.internal

/**
 * Whether the phone has walked away from the space it films ([ScanScope.Space]). A cupboard or
 * a shelf is filmed from before its opening, an arm moving, the feet not; a room is walked. The
 * first real use of the choice was a kitchen walked round for two and a half minutes
 * (2026-11-14), measured as one cupboard and given figures that meant nothing: the capture now
 * says so as it happens.
 *
 * Where the phone was first seen is where the capture stands. Further than [REACH_M] from there
 * across the ground it has strayed, until it is back within [BACK_M]; its height does not count.
 */
class SpaceStay {
    private var home: DebugPose? = null
    private var away = false

    /** Whether the phone at [camera] has walked off; `false` while it is not known. */
    fun strayed(camera: DebugPose?): Boolean {
        val at = camera ?: return away
        val from = home ?: at.also { home = it }
        val dx = at.x - from.x
        val dz = at.z - from.z
        val reach = if (away) BACK_M else REACH_M
        away = dx * dx + dz * dz > reach * reach
        return away
    }

    companion object {
        /** A wardrobe's width or a car boot's is swept within this of where one stands. */
        const val REACH_M = 1.2f

        /** Back this close, the phone is before the opening again. */
        const val BACK_M = 0.9f
    }
}
