package io.github.sceneview.demo.demos.internal

/**
 * A scan's replay journal, capped at [maxBytes] (at most 4 MiB in live capture), including
 * conservative object/array overhead. [ScanMemoryBudget] accounts for it beside both maps.
 * A coloured observation of 1,000 points costs about 16 KiB (xyz floats + ARGB ints); at
 * the recorder's 5 Hz a 406-second scan would otherwise retain about 31 MiB, beside a
 * TSDF and the dense map. A pose costs about 112 bytes including its seven-float
 * [DebugPose]: another 1.3 MiB at 30 Hz. The journal gets only 4 MiB on a 256 MiB heap.
 *
 * At the cap, halve camera/point observations across the timeline, keeping the first/last of
 * each and the camera poses used by photos. Photos, planes, depth counts and floor cells
 * survive this first pass. If metadata alone
 * fills the budget, halve all events too. Repeat until there is room; reject a single event
 * larger than the budget. This preserves a replay spanning the scan (with less temporal
 * detail), rather than stopping recording or keeping only its last seconds. [onCap] fires
 * once per journal. No Android dependencies: live capture supplies the log callback.
 */
class ArDebugJournal(
    val maxBytes: Long = MAX_BYTES,
    private val onCap: () -> Unit = {},
) : AbstractList<ArDebugEvent>() {
    init {
        require(maxBytes > 0) { "maxBytes $maxBytes" }
    }
    private val events = ArrayList<ArDebugEvent>()
    var bytes: Long = 0L
        private set
    var capped: Boolean = false
        private set

    override val size: Int get() = events.size
    override fun get(index: Int): ArDebugEvent = events[index]

    fun append(event: ArDebugEvent) {
        val cost = bytesOf(event)
        if (bytes + cost > maxBytes) {
            if (!capped) {
                capped = true
                onCap()
            }
            if (cost > maxBytes) return
            while (bytes + cost > maxBytes && events.isNotEmpty()) {
                val before = bytes
                thin(observationsOnly = true)
                if (bytes == before) thin(observationsOnly = false)
            }
        }
        events.add(event)
        bytes += cost
    }

    private fun thin(observationsOnly: Boolean) {
        val photoPoses = HashSet<Int>()
        var pose = -1
        for (i in events.indices) {
            if (events[i] is ArDebugEvent.CameraPose) pose = i
            if (events[i] is ArDebugEvent.Image && pose >= 0) photoPoses.add(pose)
        }
        val lastPose = events.indexOfLast { it is ArDebugEvent.CameraPose }
        val lastPoints = events.indexOfLast { it is ArDebugEvent.Points }
        var poses = 0
        var points = 0
        var out = 0
        for (i in events.indices) {
            val event = events[i]
            val remove = if (observationsOnly) {
                when (event) {
                    is ArDebugEvent.CameraPose -> poses++ % 2 == 1 && i != lastPose && i !in photoPoses
                    is ArDebugEvent.Points -> points++ % 2 == 1 && i != lastPoints
                    else -> false
                }
            } else {
                i != 0 && i % 2 == 1
            }
            if (remove) bytes -= bytesOf(event) else events[out++] = event
        }
        // One entry must also make progress when even the first event no longer fits.
        if (out == events.size && !observationsOnly) {
            bytes -= bytesOf(events.removeAt(events.lastIndex))
        } else {
            events.subList(out, events.size).clear()
        }
    }

    companion object {
        const val MAX_BYTES = 4L * 1024 * 1024

        /** Payload plus rounded-up headers, references and list slots; not a heap measurement. */
        fun bytesOf(event: ArDebugEvent): Long = 64L + when (event) {
            is ArDebugEvent.CameraPose, is ArDebugEvent.Anchor -> 48L
            is ArDebugEvent.Points -> 24L + event.positions.size * 4L +
                (event.confidences?.let { 24L + it.size * 4L } ?: 0L) +
                (event.colors?.let { 24L + it.size * 4L } ?: 0L)
            is ArDebugEvent.Plane -> 24L + event.polygon.size * 4L
            is ArDebugEvent.Image -> 48L + event.path.length * 2L
            is ArDebugEvent.DepthStats -> 0L
            is ArDebugEvent.FloorCells -> 24L + event.keys.size * 8L
        }
    }
}
