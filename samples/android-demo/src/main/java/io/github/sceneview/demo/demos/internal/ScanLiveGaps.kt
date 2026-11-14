package io.github.sceneview.demo.demos.internal

/**
 * The empty areas of the live model's floor worth a line of guidance: a piece of missing floor
 * (cells side by side) [MIN_M2] at least that the scan already surrounds, [ENCLOSED] of its edge
 * bordering floor it has seen.
 *
 * What merely notches the model's outline is not one. The floor counted as missing is what the
 * model's hull spans, and a ragged outline leaves slivers and bays all along it: the scan fills
 * them by going on, or they lie behind a wall. Naming them told nothing (2026-11-14, the first
 * hand-held scan of a room: the line named one for 55 s on end and changed twelve times).
 */
internal object ScanLiveGaps {

    /** One such area: its [bounds] `[minX, minZ, maxX, maxZ]`, the point to [aim] at, its [area] in m². */
    class Gap(val bounds: FloatArray, val aim: Pair<Float, Float>, val area: Float)

    /** The least floor worth going back for: a step's worth, eight cells of 25 cm. */
    const val MIN_M2 = 0.5f

    /** The share of an area's edge that borders scanned floor, at least: a bay deeper than wide. */
    const val ENCLOSED = 0.7f

    /**
     * [ground]'s gaps. Each aims at the centre of its missing cell nearest their mean, so always
     * at the gap itself: the middle of an L-shaped gap's bounds lies on floor already scanned.
     */
    @Suppress("NestedBlockDepth")
    fun of(ground: ScanGround): List<Gap> {
        val columns = ground.columns
        val rows = ground.rows
        val seen = BooleanArray(columns * rows)
        val gaps = ArrayList<Gap>()
        val piece = ArrayList<Int>()
        val stack = ArrayDeque<Int>()
        for (start in 0 until columns * rows) {
            if (seen[start] || ground.at(start % columns, start / columns) != ScanGround.MISSING) continue
            seen[start] = true
            stack.addLast(start)
            piece.clear()
            var scanned = 0
            var open = 0
            while (stack.isNotEmpty()) {
                val at = stack.removeLast()
                piece += at
                for ((dc, dr) in NEIGHBOURS) {
                    val nc = at % columns + dc
                    val nr = at / columns + dr
                    when (ground.at(nc, nr)) {
                        ScanGround.MISSING -> {
                            val next = nr * columns + nc
                            if (!seen[next]) {
                                seen[next] = true
                                stack.addLast(next)
                            }
                        }
                        ScanGround.OUTSIDE -> open++
                        else -> scanned++
                    }
                }
            }
            val area = piece.size * ground.cellM * ground.cellM
            if (area >= MIN_M2 && scanned >= ENCLOSED * (scanned + open)) gaps += gap(ground, piece, area)
        }
        return gaps
    }

    private fun gap(ground: ScanGround, piece: List<Int>, area: Float): Gap {
        val m = ground.cellM
        var c0 = Int.MAX_VALUE
        var r0 = Int.MAX_VALUE
        var c1 = Int.MIN_VALUE
        var r1 = Int.MIN_VALUE
        var sumC = 0f
        var sumR = 0f
        for (at in piece) {
            val c = at % ground.columns
            val r = at / ground.columns
            c0 = minOf(c0, c)
            c1 = maxOf(c1, c)
            r0 = minOf(r0, r)
            r1 = maxOf(r1, r)
            sumC += c
            sumR += r
        }
        val meanC = sumC / piece.size
        val meanR = sumR / piece.size
        val nearest = piece.minBy { at ->
            val dc = at % ground.columns - meanC
            val dr = at / ground.columns - meanR
            dc * dc + dr * dr
        }
        return Gap(
            bounds = floatArrayOf(
                ground.originX + c0 * m, ground.originZ + r0 * m,
                ground.originX + (c1 + 1) * m, ground.originZ + (r1 + 1) * m,
            ),
            aim = (ground.originX + (nearest % ground.columns + HALF) * m) to
                (ground.originZ + (nearest / ground.columns + HALF) * m),
            area = area,
        )
    }

    private const val HALF = 0.5f
    private val NEIGHBOURS = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
}
