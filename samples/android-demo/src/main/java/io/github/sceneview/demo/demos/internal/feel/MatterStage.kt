package io.github.sceneview.demo.demos.internal.feel

/** Three portrait rows in one camera. Labels and geometry share the same measured row bounds. */
internal class MatterStage {
    var widthPx = 1
    var heightPx = 1
    val captionPx = IntArray(3)
    val y = FloatArray(3)
    val z = FloatArray(3)
    val scale = FloatArray(3) { 1f }
    val halfWidth = FeelSimulation.PAD_HALF_WIDTH / 0.9f
    var halfHeight = halfWidth
        private set
    private var framedWidth = -1
    private var framedHeight = -1
    private val framedCaptions = IntArray(3) { -1 }

    fun frame(): Boolean {
        if (framedWidth == widthPx && framedHeight == heightPx && framedCaptions.contentEquals(captionPx)) return false
        framedWidth = widthPx
        framedHeight = heightPx
        captionPx.copyInto(framedCaptions)
        halfHeight = halfWidth * heightPx / widthPx.coerceAtLeast(1)
        val row = halfHeight * 2f / 3f
        val unitsPerPx = halfWidth * 2f / widthPx.coerceAtLeast(1)
        val blockHeight = 2f * FeelSimulation.PAD_HALF_LENGTH * SIN + FeelSimulation.PAD_THICKNESS * COS
        for (i in 0..2) {
            val caption = captionPx[i] * unitsPerPx
            scale[i] = ((row - caption).coerceAtLeast(0.1f) * 0.92f / blockHeight).coerceAtMost(1f)
            val up = halfHeight - row * (i + 0.5f) + caption / 2f + FeelSimulation.PAD_THICKNESS * COS * scale[i] / 2f
            y[i] = up * COS
            z[i] = -up * SIN
        }
        return true
    }

    companion object {
        const val ELEVATION = 42f
        const val SIN = 0.6691306f
        const val COS = 0.7431448f
    }
}
