package io.github.sceneview.demo.demos.internal

/**
 * A live scan's replay journal. At [maxBytes], discard superseded plane snapshots first,
 * then thin camera/point samples and depth counts across the timeline. Keep the latest plane
 * snapshot per id, every tombstone, photo pose, Image, FloorCells and Anchor event.
 * If that irreducible metadata alone exceeds the allowance, retain it and report the cap:
 * replay correctness takes priority over a hard byte ceiling. [onCap] fires only once.
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
        if (bytes + cost > maxBytes && !capped) {
            capped = true
            onCap()
        }
        // A single oversized point array cannot be thinned. Replay-critical kinds always enter.
        if (event is ArDebugEvent.Points && cost > maxBytes) return
        events.add(event)
        bytes += cost
        if (bytes <= maxBytes) return
        thinPlanes()
        while (bytes > maxBytes) {
            val before = bytes
            thinSamples()
            if (bytes == before) thinDepthCounts()
            if (bytes == before) removeLastOptionalSample()
            if (bytes == before) break // Only irreducible replay metadata remains.
        }
    }

    private fun thinPlanes() {
        val latest = HashMap<Int, Int>()
        events.forEachIndexed { i, event -> if (event is ArDebugEvent.Plane && event.polygon.isNotEmpty()) latest[event.id] = i }
        removeWhere { i, event ->
            event is ArDebugEvent.Plane && event.polygon.isNotEmpty() && latest[event.id] != i
        }
    }

    private fun thinSamples() {
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
        removeWhere { i, event ->
            when (event) {
                is ArDebugEvent.CameraPose -> poses++ % 2 == 1 && i != lastPose && i !in photoPoses
                is ArDebugEvent.Points -> points++ % 2 == 1 && i != lastPoints
                else -> false
            }
        }
    }

    private fun thinDepthCounts() {
        val last = events.indexOfLast { it is ArDebugEvent.DepthStats }
        var counts = 0
        removeWhere { i, event -> event is ArDebugEvent.DepthStats && counts++ % 2 == 1 && i != last }
    }

    private fun removeLastOptionalSample() {
        // Half-thinning bottoms out at the endpoints. They too are optional if space is tight.
        val photoPoses = HashSet<Int>()
        var pose = -1
        events.forEachIndexed { i, event ->
            if (event is ArDebugEvent.CameraPose) pose = i
            if (event is ArDebugEvent.Image && pose >= 0) photoPoses.add(pose)
        }
        val index = events.indexOfFirst { it is ArDebugEvent.Points || it is ArDebugEvent.DepthStats }
            .takeIf { it >= 0 } ?: events.indices.firstOrNull {
                events[it] is ArDebugEvent.CameraPose && it !in photoPoses
            } ?: return
        bytes -= bytesOf(events.removeAt(index))
    }

    private inline fun removeWhere(remove: (Int, ArDebugEvent) -> Boolean) {
        var out = 0
        for (i in events.indices) {
            val event = events[i]
            if (remove(i, event)) bytes -= bytesOf(event) else events[out++] = event
        }
        events.subList(out, events.size).clear()
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
