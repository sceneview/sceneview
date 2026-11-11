package io.github.sceneview.demo.demos.internal.feel

/** One visit, one load. The mark rides the foam's actual indentation while it is resting. */
internal class MatterOpening(enabled: Boolean) {
    var finished = !enabled
        private set
    var visible = false
        private set
    var heightAbovePad = 0f
        private set
    var loadKg = 0f
        private set
    private var startedAt: Long? = null

    fun advance(now: Long) {
        if (finished) return
        val start = startedAt ?: now.also { startedAt = it }
        val age = (now - start).coerceAtLeast(0L)
        if (age >= 2000L) { cancel(); return }
        visible = age < 1700L
        loadKg = if (age in 350L until 1250L) MARK_MASS_KG else 0f
        heightAbovePad = when {
            age < 350L -> TRAVEL * (1f - smooth(age / 350f))
            age < 1250L -> 0f
            // Retrace the descent within the foam row's perspective headroom.
            else -> TRAVEL * smooth((age - 1250L) / 450f)
        }
    }

    fun cancel() {
        finished = true
        visible = false
        loadKg = 0f
    }

    private fun smooth(t: Float): Float = t.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

    companion object {
        const val MARK_MASS_KG = 0.75f
        // A fifth-width cube and short drop keep the full frame inside the top row.
        // MatterStageTest projects the outer bracket corners at maximum travel.
        const val MARK_WIDTH = FeelSimulation.PAD_HALF_WIDTH * 2f / 5f
        const val TRAVEL = 0.2f
        const val SHOW_BRACKETS = true
    }
}
