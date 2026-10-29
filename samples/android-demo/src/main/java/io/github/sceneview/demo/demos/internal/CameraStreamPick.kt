package io.github.sceneview.demo.demos.internal

/**
 * One camera stream ARCore offers, reduced to what the 3D Scan's choice reads: the CPU image
 * (the one the photos and the depth are read from), the frame-rate range, the facing, whether the
 * depth sensor is in use, and the GPU texture the preview draws.
 */
data class CameraStream(
    val cpuWidth: Int,
    val cpuHeight: Int,
    val minFps: Int,
    val maxFps: Int,
    val back: Boolean = true,
    val depthSensor: Boolean = false,
    val gpuWidth: Int = 0,
    val gpuHeight: Int = 0,
) {
    val cpuPixels: Long get() = cpuWidth.toLong() * cpuHeight
    val gpuPixels: Long get() = gpuWidth.toLong() * gpuHeight
}

/**
 * The 3D Scan's camera stream. Of the back streams ARCore offers that reach 30 fps (never one
 * that only runs faster): one whose CPU image meets [TARGET_LONG] × [TARGET_SHORT] first, then
 * one fixed at 30 fps rather than one that may drop below it, then the largest CPU image. The
 * depth sensor, then the larger GPU texture, break a tie. The target comes before the steady
 * rate so a small stream fixed at 30 fps never wins over a large one the session's default
 * config would have taken. No stream to pick keeps the session's default config.
 */
object CameraStreamPick {
    const val FPS = 30

    /** The image the scan aims for, on the long and the short side: 1280 × 720. */
    const val TARGET_LONG = 1280
    const val TARGET_SHORT = 720

    private val ORDER = compareBy<CameraStream>(
        { meetsTarget(it) },
        { it.minFps >= FPS },
        { it.cpuPixels },
        { it.depthSensor },
        { it.gpuPixels },
    )

    fun pick(streams: List<CameraStream>): CameraStream? = pickOf(streams.map { it to it })?.second

    /**
     * Of [offered], each item next to its stream, the pair whose stream [pick] chooses. The item
     * comes with its own stream rather than by looking the stream up again, so two items whose
     * streams are equal are never swapped.
     */
    fun <T> pickOf(offered: List<Pair<T, CameraStream>>): Pair<T, CameraStream>? =
        offered.filter { eligible(it.second) }.maxWithOrNull(compareBy(ORDER) { it.second })

    fun eligible(stream: CameraStream): Boolean =
        stream.back && stream.minFps <= FPS && stream.maxFps >= FPS && stream.cpuPixels > 0

    fun meetsTarget(stream: CameraStream): Boolean =
        maxOf(stream.cpuWidth, stream.cpuHeight) >= TARGET_LONG && minOf(stream.cpuWidth, stream.cpuHeight) >= TARGET_SHORT

    /**
     * The choice in one line for the bake log: the stream, what was offered, and the target. During
     * a [playback] ARCore serves the recording's own stream whatever is asked for, so the line says
     * so before what the scan asked for.
     */
    fun logLine(offered: List<CameraStream>, chosen: CameraStream?, playback: Boolean = false): String =
        line(choice(offered, chosen), playback)

    /** The line when the offered streams could not be read, so the session's default config stays. */
    fun unreadLine(error: Throwable, playback: Boolean = false): String =
        line("default config, the offered streams could not be read ($error)", playback)

    private fun line(choice: String, playback: Boolean): String =
        if (playback) "camera: playback, the recording's own stream serves; asked for $choice" else "camera: $choice"

    private fun choice(offered: List<CameraStream>, chosen: CameraStream?): String {
        val list = if (offered.isEmpty()) "" else offered.map(::label).distinct().joinToString(", ", " [", "]")
        if (chosen == null) return "default config, none of ${offered.size} offered holds $FPS fps$list"
        val extras = listOfNotNull(
            "gpu ${chosen.gpuWidth}x${chosen.gpuHeight}".takeIf { chosen.gpuPixels > 0 },
            "depth sensor".takeIf { chosen.depthSensor },
        )
        val details = if (extras.isEmpty()) "" else extras.joinToString(", ", " (", ")")
        val target = if (meetsTarget(chosen)) "meets" else "below"
        return "${chosen.cpuWidth}x${chosen.cpuHeight} at ${fps(chosen)} fps$details, " +
            "picked from ${offered.size} offered$list; $target ${TARGET_LONG}x$TARGET_SHORT"
    }

    private fun label(stream: CameraStream): String =
        "${stream.cpuWidth}x${stream.cpuHeight} ${fps(stream)}" + if (stream.back) "" else " front"

    private fun fps(stream: CameraStream): String =
        if (stream.minFps == stream.maxFps) "${stream.maxFps}" else "${stream.minFps}-${stream.maxFps}"
}
