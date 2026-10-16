package io.github.sceneview.demo.demos.internal

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What a room scan has really seen, in cells of [cellM]: how many good looks each patch of the
 * room got, from how close, how square-on and how steadily. The paint over the camera draws it
 * (`ScanCoverageScene.kt`) and the line's "Room 60% covered" counts it ([summary]). It is fed
 * only what ARCore measures — raw-depth surfels and feature points — never a guess.
 *
 * A *look* is every sample that falls in a cell within [LOOK_GAP_NS] of the look's first: it
 * is worth its best [quality], once. A cell's evidence is the sum of its looks, so a sweep gives
 * a patch one or two weak looks and a camera held on it piles them up: the paint densifies where
 * the user lingers, which is where the scan really gets better.
 *
 * Depth arrives from the fusion's worker, points and every read from the main thread: each
 * method holds the instance's lock.
 */
internal class ScanCoverage(
    val cellM: Float = CELL_M,
    private val maxCells: Int = MAX_CELLS,
    private val clock: () -> Long = System::nanoTime,
) {
    /** One cell of the room, as [forEachCell] hands it out. */
    class Cell internal constructor(
        /** Its rank in the order the cells were first seen: the paint lights the new ones up. */
        val seq: Int,
    ) {
        /** The sum of its looks' quality: what [tier] grades. */
        var evidence = 0f
            private set

        /** Where its samples fell, averaged: the paint's centre. */
        var x = 0f
            private set
        var y = 0f
            private set
        var z = 0f
            private set

        // Sum of the sample normals: depth's own, or a feature point's guess at a quarter weight.
        private var nx = 0f
        private var ny = 0f
        private var nz = 0f
        private var samples = 0
        private var lookStart = 0L
        private var lookBest = 0f
        private var looks = 0

        /** 0 unseen · 1 seen once · 2 seen again · 3 dense · 4 full: the paint's four looks. */
        val tier: Int get() = tierOf(evidence)

        /** The cell's unit normal (`[x, y, z]`), up when nothing hinted at one. */
        fun normal(): FloatArray {
            val length = sqrt(nx * nx + ny * ny + nz * nz)
            if (length < 1e-6f) return floatArrayOf(0f, 1f, 0f)
            return floatArrayOf(nx / length, ny / length, nz / length)
        }

        /** Floor, wall or ceiling, from the way the cell faces. */
        val kind: Kind
            get() {
                val n = normal()
                return when {
                    n[1] >= HORIZONTAL_NORMAL_Y -> Kind.Floor
                    n[1] <= -HORIZONTAL_NORMAL_Y -> Kind.Ceiling
                    else -> Kind.Wall
                }
            }

        internal fun absorb(acc: Acc, nanos: Long) {
            val total = samples + acc.count
            val w = acc.count.toFloat() / total
            x += (acc.x / acc.count - x) * w
            y += (acc.y / acc.count - y) * w
            z += (acc.z / acc.count - z) * w
            nx += acc.nx
            ny += acc.ny
            nz += acc.nz
            samples = total.coerceAtMost(MAX_SAMPLES_WEIGHT)
            // One look per gap: what falls in the same moment counts once, worth its best.
            if (looks == 0 || abs(nanos - lookStart) >= LOOK_GAP_NS) {
                lookStart = nanos
                lookBest = acc.best
                evidence += acc.best
                looks++
            } else if (acc.best > lookBest) {
                evidence += acc.best - lookBest
                lookBest = acc.best
            }
        }
    }

    enum class Kind { Floor, Wall, Ceiling }

    /**
     * How much of the room the scan holds, for the line and the ring: [percent] of it, each
     * [walls] sector's share (`[SECTORS]` around where the user walked, the first one ahead of
     * the scan's start along +Z), and the [floor] and [ceiling] shares, all 0–1.
     */
    class Summary(
        val percent: Int,
        val walls: FloatArray,
        val floor: Float,
        val ceiling: Float,
        val cells: Int,
    ) {
        /** Enough of the room to make a good scan: the line invites the user to finish. */
        val isEnough: Boolean get() = percent >= ENOUGH_PERCENT

        companion object {
            val EMPTY = Summary(0, FloatArray(SECTORS), 0f, 0f, 0)
        }
    }

    /** What the latest look saw: what the coach reads for "Hold here" and the distance. */
    class Look(
        /** Cells the look touched. */
        val cells: Int,
        /** Of them, those still short of dense: where holding the phone still helps. */
        val poor: Int,
        /** The median distance to what it saw, metres; `NaN` when it saw nothing. */
        val distanceM: Float,
    ) {
        val poorShare: Float get() = if (cells == 0) 0f else poor.toFloat() / cells
    }

    internal class Acc {
        var count = 0
        var best = 0f
        var x = 0f
        var y = 0f
        var z = 0f
        var nx = 0f
        var ny = 0f
        var nz = 0f

        fun reset() {
            count = 0
            best = 0f
            x = 0f
            y = 0f
            z = 0f
            nx = 0f
            ny = 0f
            nz = 0f
        }
    }

    private val lock = Any()
    private val cells = HashMap<Long, Cell>()
    private val batch = HashMap<Long, Acc>()
    private val pool = ArrayList<Acc>()
    private val distances = FloatArray(DISTANCE_SAMPLES)
    private var nextSeq = 0
    private var eyeSumX = 0.0
    private var eyeSumZ = 0.0
    private var eyeCount = 0
    private var eyeMinY = Float.POSITIVE_INFINITY
    private var eyeMaxY = Float.NEGATIVE_INFINITY

    /** The floor's height once enough of it is seen, else `NaN`: what a feature point is judged by. */
    @Volatile
    private var floorY = Float.NaN

    /** The camera moves too fast to trust what it sees: looks are dropped, the paint stops growing. */
    @Volatile
    var paused = false

    /** The scan keeps no more of the room (its budget is spent): no cell is born any more. */
    @Volatile
    var frozen = false

    /** How much a look is worth at the camera's current speed, 0–1 ([ScanMotion.lookFactor]). */
    @Volatile
    var motionFactor = 1f

    /** Bumped by every look that changed a cell: the paint re-uploads on a change only. */
    @Volatile
    var version = 0
        private set

    /** The latest look, or `null` before the first. */
    @Volatile
    var lastLook: Look? = null
        private set

    /** Cells seen so far, at any tier. */
    val size: Int get() = synchronized(lock) { cells.size }

    /**
     * Raw-depth surfels seen from [camera] (the depth's sensor pose). Every [stride]th one is
     * read, and a cell is born only where two of them fall, so a stray pixel paints nothing.
     */
    fun observeDepth(camera: DebugPose, samples: DenseSamples, stride: Int = DEPTH_STRIDE): Look? =
        observe(
            camera, samples.count, samples.positions, samples.normals, stride, DEPTH_MIN_SAMPLES,
        ) { i -> depthConfidence(samples.confidences[i].toInt() and 0xFF) }

    /** ARCore's feature points (`[x, y, z, …]`, [confidence] 0–1 each) seen from [camera]. */
    fun observePoints(camera: DebugPose, xyz: FloatArray, confidence: FloatArray): Look? =
        observe(camera, confidence.size, xyz, null, 1, 1) { i -> 0.4f + 0.6f * confidence[i].coerceIn(0f, 1f) }

    /** Hands every cell to [block] under the lock: the paint's pass. Never call back into this. */
    fun forEachCell(block: (Cell) -> Unit) = synchronized(lock) { cells.values.forEach(block) }

    private fun observe(
        camera: DebugPose,
        count: Int,
        positions: FloatArray,
        normals: FloatArray?,
        stride: Int,
        minSamples: Int,
        confidenceAt: (Int) -> Float,
    ): Look? {
        if (paused) return null
        val nanos = clock()
        val forward = camera.forward
        synchronized(lock) {
            noteEye(camera)
            var seen = 0
            var i = 0
            while (i < count) {
                if (accumulate(camera, forward, positions, normals, i, confidenceAt(i), seen)) seen++
                i += stride.coerceAtLeast(1)
            }
            return apply(nanos, minSamples, median(seen.coerceAtMost(DISTANCE_SAMPLES)))
        }
    }

    /** Adds sample [i] to this look's [batch]; `false` when it is too close to be a surface. */
    private fun accumulate(
        camera: DebugPose,
        forward: Vec3,
        positions: FloatArray,
        normals: FloatArray?,
        i: Int,
        confidence: Float,
        seen: Int,
    ): Boolean {
        val px = positions[i * 3]
        val py = positions[i * 3 + 1]
        val pz = positions[i * 3 + 2]
        val dx = px - camera.x
        val dy = py - camera.y
        val dz = pz - camera.z
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        if (!distance.isFinite() || distance < NEAR_M) return false
        val ux = dx / distance
        val uy = dy / distance
        val uz = dz / distance
        // Every few samples feed the median distance: a ring of the latest, enough for a median.
        distances[seen % DISTANCE_SAMPLES] = distance

        var nx: Float
        var ny: Float
        var nz: Float
        val weight: Float
        if (normals != null) {
            nx = normals[i * 3]
            ny = normals[i * 3 + 1]
            nz = normals[i * 3 + 2]
            weight = 1f
        } else {
            val guess = guessNormal(ux, uy, uz, py)
            nx = guess[0]
            ny = guess[1]
            nz = guess[2]
            weight = GUESSED_NORMAL_WEIGHT
        }
        val facing = abs(nx * ux + ny * uy + nz * uz)
        val centred = ux * forward.x + uy * forward.y + uz * forward.z
        val q = quality(distance, facing, confidence, centred) * motionFactor

        val key = key(cellIndex(px), cellIndex(py), cellIndex(pz))
        val acc = batch.getOrPut(key) { take() }
        acc.count++
        if (q > acc.best) acc.best = q
        acc.x += px
        acc.y += py
        acc.z += pz
        nx *= weight
        ny *= weight
        nz *= weight
        acc.nx += nx
        acc.ny += ny
        acc.nz += nz
        return true
    }

    /** Folds this look's [batch] into the cells. */
    private fun apply(nanos: Long, minSamples: Int, distanceM: Float): Look {
        var touched = 0
        var poor = 0
        var changed = false
        for ((key, acc) in batch) {
            var cell = cells[key]
            if (cell == null) {
                if (frozen || acc.count < minSamples || cells.size >= maxCells) continue
                cell = Cell(nextSeq++)
                cells[key] = cell
            }
            val before = cell.evidence
            cell.absorb(acc, nanos)
            if (cell.evidence != before) changed = true
            touched++
            if (cell.tier < DENSE_TIER) poor++
        }
        for (acc in batch.values) {
            acc.reset()
            pool += acc
        }
        batch.clear()
        if (changed) version++
        return Look(touched, poor, distanceM).also { lastLook = it }
    }

    /**
     * How much of the room the scan holds. Walls weigh most: they are what a plan and a model of
     * the room are made of. Each of [SECTORS] around the walk is split in [BAND_M] bands from just
     * above the floor to head height, and a band is covered once [WALL_CELLS_PER_BAND] full cells
     * fill it (a cell counts by its [tier]: seen once a quarter, full once).
     */
    fun summary(): Summary = synchronized(lock) {
        if (cells.isEmpty() || eyeCount == 0) return Summary.EMPTY
        val floor = estimateFloor()
        floorY = floor
        val ceiling = estimateCeiling(floor)
        val bottom = floor + WALL_FROM_FLOOR_M
        val bands = ((minOf(ceiling - BAND_M / 4, floor + WALL_TO_M) - bottom) / BAND_M)
            .roundToInt().coerceIn(1, MAX_BANDS)
        val filled = fill(bottom, bands)

        val walls = FloatArray(SECTORS) { s ->
            var sum = 0f
            for (b in 0 until bands) sum += (filled.walls[s * MAX_BANDS + b] / WALL_CELLS_PER_BAND).coerceAtMost(1f)
            sum / bands
        }
        val floorShare = share(filled.floor, FLOOR_CELLS_PER_SECTOR)
        val ceilingShare = share(filled.ceiling, CEILING_CELLS_PER_SECTOR)
        val room = WALL_WEIGHT * walls.average().toFloat() +
            FLOOR_WEIGHT * floorShare +
            CEILING_WEIGHT * ceilingShare
        Summary((room * 100f).roundToInt().coerceIn(0, 100), walls, floorShare, ceilingShare, cells.size)
    }

    private class Fill(val walls: FloatArray, val floor: FloatArray, val ceiling: FloatArray)

    /** Each cell's [TIER_VALUE] summed in its sector — and, for a wall, its band above [bottom]. */
    private fun fill(bottom: Float, bands: Int): Fill {
        val cx = (eyeSumX / eyeCount).toFloat()
        val cz = (eyeSumZ / eyeCount).toFloat()
        val out = Fill(FloatArray(SECTORS * MAX_BANDS), FloatArray(SECTORS), FloatArray(SECTORS))
        for (cell in cells.values) {
            val value = TIER_VALUE[cell.tier]
            val sector = sectorOf(cell.x - cx, cell.z - cz)
            val band = floor((cell.y - bottom) / BAND_M).toInt()
            when (cell.kind) {
                Kind.Floor -> out.floor[sector] += value
                Kind.Ceiling -> out.ceiling[sector] += value
                Kind.Wall -> if (band in 0 until bands) out.walls[sector * MAX_BANDS + band] += value
            }
        }
        return out
    }

    /** The mean over the sectors of how full each is, [full] being what fills one. */
    private fun share(sectors: FloatArray, full: Float): Float =
        sectors.sumOf { (it / full).coerceAtMost(1f).toDouble() }.toFloat() / sectors.size

    private fun noteEye(camera: DebugPose) {
        eyeSumX += camera.x
        eyeSumZ += camera.z
        eyeCount++
        if (camera.y < eyeMinY) eyeMinY = camera.y
        if (camera.y > eyeMaxY) eyeMaxY = camera.y
    }

    /** The floor: the median of the upward cells well below the phone, else a phone held at the chest. */
    private fun estimateFloor(): Float {
        val below = cells.values.filter { it.kind == Kind.Floor && it.y < eyeMinY - FLOOR_BELOW_EYE_M }.map { it.y }
        return if (below.size >= MIN_SURFACE_CELLS) below.sorted()[below.size / 2] else eyeMinY - EYE_HEIGHT_M
    }

    /** The ceiling: the median of the downward cells above the phone, else a standard room's. */
    private fun estimateCeiling(floor: Float): Float {
        val above = cells.values.filter { it.kind == Kind.Ceiling && it.y > eyeMaxY }.map { it.y }
        return if (above.size >= MIN_SURFACE_CELLS) above.sorted()[above.size / 2] else floor + ROOM_HEIGHT_M
    }

    /**
     * A feature point has no normal: it faces the camera, upright, unless it lies on the floor
     * or the camera looks steeply down (a floor, a table) or up (the ceiling) at it.
     */
    private fun guessNormal(ux: Float, uy: Float, uz: Float, py: Float): FloatArray {
        val floor = floorY
        return when {
            (floor.isFinite() && py < floor + ON_FLOOR_M) || uy < -STEEP_VIEW -> floatArrayOf(0f, 1f, 0f)
            uy > STEEP_VIEW -> floatArrayOf(0f, -1f, 0f)
            else -> {
                val h = sqrt(ux * ux + uz * uz).coerceAtLeast(1e-6f)
                floatArrayOf(-ux / h, 0f, -uz / h)
            }
        }
    }

    private fun take(): Acc = if (pool.isEmpty()) Acc() else pool.removeAt(pool.size - 1)

    private fun median(n: Int): Float {
        if (n == 0) return Float.NaN
        val sorted = distances.copyOf(n).also { it.sort() }
        return sorted[n / 2]
    }

    private fun cellIndex(v: Float): Int = floor(v / cellM).toInt()

    companion object {
        /** A cell's side: a hand's width, fine enough to show a gap, coarse enough to fill. */
        const val CELL_M = 0.2f
        const val MAX_CELLS = 20_000
        const val SECTORS = 12
        const val ENOUGH_PERCENT = 80
        const val DENSE_TIER = 3

        /** Samples that land in one cell within this window are one look. */
        const val LOOK_GAP_NS = 250_000_000L

        // Evidence past which a cell takes each tier: a sweep gives about 2, holding 2 s gives 6.
        const val SEEN_AGAIN = 1.2f
        const val DENSE = 3f
        const val FULL = 6f

        const val NEAR_M = 0.15f
        private const val DEPTH_STRIDE = 8
        private const val DEPTH_MIN_SAMPLES = 2
        private const val DISTANCE_SAMPLES = 64
        private const val MAX_SAMPLES_WEIGHT = 10_000
        private const val GUESSED_NORMAL_WEIGHT = 0.25f
        private const val HORIZONTAL_NORMAL_Y = 0.75f
        private const val STEEP_VIEW = 0.7f
        private const val ON_FLOOR_M = 0.12f
        private const val FLOOR_BELOW_EYE_M = 0.5f
        private const val EYE_HEIGHT_M = 1.3f
        private const val ROOM_HEIGHT_M = 2.5f
        private const val MIN_SURFACE_CELLS = 8
        private const val WALL_FROM_FLOOR_M = 0.2f
        private const val WALL_TO_M = 2.2f
        private const val BAND_M = 0.4f
        private const val MAX_BANDS = 5
        private const val WALL_CELLS_PER_BAND = 4f
        private const val FLOOR_CELLS_PER_SECTOR = 6f
        private const val CEILING_CELLS_PER_SECTOR = 4f
        private const val WALL_WEIGHT = 0.7f
        private const val FLOOR_WEIGHT = 0.2f
        private const val CEILING_WEIGHT = 0.1f
        private val TIER_VALUE = floatArrayOf(0f, 0.25f, 0.5f, 0.8f, 1f)

        fun tierOf(evidence: Float): Int = when {
            evidence >= FULL -> 4
            evidence >= DENSE -> 3
            evidence >= SEEN_AGAIN -> 2
            evidence > 0f -> 1
            else -> 0
        }

        /**
         * One sample's look, 0–1: full from 0.5 to 2.5 m and fading past it, better square-on
         * ([facing], the cosine to the surface's normal) and at the centre of the view
         * ([centred], the cosine to the camera's axis), scaled by the sensor's [confidence].
         */
        fun quality(distanceM: Float, facing: Float, confidence: Float, centred: Float): Float {
            val near = ((distanceM - NEAR_M) / (BEST_FROM_M - NEAR_M)).coerceIn(0f, 1f)
            val fade = (distanceM - BEST_TO_M) / (FADE_TO_M - BEST_TO_M)
            val far = (1f - fade * (1f - FAR_FLOOR)).coerceIn(FAR_FLOOR, 1f)
            val square = SQUARE_FLOOR + (1f - SQUARE_FLOOR) * facing.coerceIn(0f, 1f)
            val centre = (EDGE_FLOOR + (1f - EDGE_FLOOR) * ((centred - EDGE_COS) / (CENTRE_COS - EDGE_COS)))
                .coerceIn(EDGE_FLOOR, 1f)
            return near * far * square * centre * confidence.coerceIn(0f, 1f)
        }

        /** ARCore's raw-depth confidence (128–255 once kept) as 0.4–1. */
        fun depthConfidence(raw: Int): Float =
            (0.4f + 0.6f * ((raw - DepthBackProjection.MIN_CONFIDENCE) / 127f)).coerceIn(0.4f, 1f)

        /** The sector of a point [dx], [dz] off the walk's centre: 0 along +Z, counter-clockwise seen from above. */
        fun sectorOf(dx: Float, dz: Float): Int {
            val angle = atan2(dx, dz).let { if (it < 0f) it + 2f * PI.toFloat() else it }
            return (angle / (2f * PI.toFloat()) * SECTORS).toInt().coerceIn(0, SECTORS - 1)
        }

        private const val BEST_FROM_M = 0.5f
        private const val BEST_TO_M = 2.5f
        private const val FADE_TO_M = 5f
        private const val FAR_FLOOR = 0.25f
        private const val SQUARE_FLOOR = 0.35f
        private const val EDGE_FLOOR = 0.35f
        private const val CENTRE_COS = 0.951f // 18° off the axis
        private const val EDGE_COS = 0.819f // 35° off the axis

        private fun key(ix: Int, iy: Int, iz: Int): Long =
            ((ix.toLong() and MASK) shl 42) or ((iy.toLong() and MASK) shl 21) or (iz.toLong() and MASK)

        private const val MASK = 0x1F_FFFFL
    }
}
