package io.github.sceneview.demo.demos.internal

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * What the replay draws round a tapped object ([ScanPick.Found]), as triangles, and where its
 * label goes. Pure, like [ArDebugGeometry]: the JVM tests pin exactly what the GPU receives.
 */
object ScanObjectMeshes {
    /**
     * [box]'s twelve edges as square tubes of [radius], each run [radius] past its two corners so
     * the three that meet there close into one solid corner.
     */
    fun edges(box: ScanBox, radius: Float, into: DebugMesh = DebugMesh(EDGE_VERTICES)): DebugMesh {
        into.clear()
        val corners = box.corners()
        for ((from, to) in ScanBox.EDGES) {
            val a = corners[from]
            val b = corners[to]
            val along = (b - a).normalized() * radius
            ArDebugGeometry.addSegment(into, a - along, b + along, radius)
        }
        return into
    }

    /**
     * The quads of [surfels] cut out of [cloud], the dense layer's own mesh ([DenseSurfels.mesh]:
     * four vertices a surfel, in the cloud's order) — so a tint drawn with them lies exactly on
     * the points it lights, whatever shape the layer gave them. A surfel past the mesh is left out.
     */
    fun surfels(cloud: DebugMesh, surfels: IntArray): DebugMesh {
        val mesh = DebugMesh(maxOf(surfels.size * VERTICES_PER_SURFEL, 1))
        for (surfel in surfels) {
            val first = surfel * VERTICES_PER_SURFEL
            if (surfel < 0 || first + VERTICES_PER_SURFEL > cloud.vertexCount) continue
            val base = mesh.vertexCount
            for (k in 0 until VERTICES_PER_SURFEL) {
                val at = (first + k) * 3
                mesh.vertex(cloud.positions[at], cloud.positions[at + 1], cloud.positions[at + 2])
            }
            mesh.quad(base, base + 1, base + 2, base + 3)
        }
        return mesh
    }

    /**
     * Where a label [height] pixels tall puts its top edge, in the rows [band] the chrome over the
     * scene leaves free: [gap] over [above], the highest point of what it names, while that is in
     * the band; else [gap] under [below], its lowest point — brought back into the band when the
     * object runs out of it, so the label lies across the object's foot, never under the chrome.
     */
    fun labelTop(above: Float, below: Float, height: Int, gap: Int, band: IntRange): Int {
        val lowest = max(band.first, band.last - height)
        val over = (above - gap - height).roundToInt()
        val under = (below + gap).roundToInt()
        return if (over >= band.first) min(over, lowest) else under.coerceIn(band.first, lowest)
    }

    /**
     * Where [camera] draws [box]: the column of the middle of its top, then the highest and the
     * lowest row any of it reaches — what [labelTop] is given; `null` with that middle behind the
     * lens.
     */
    fun drawnSpan(box: ScanBox, camera: ScanCamera): Triple<Float, Float, Float>? {
        val (x, middle) = camera.project(box.top) ?: return null
        var high = middle
        var low = middle
        for (corner in box.corners()) {
            val y = camera.project(corner)?.second ?: continue
            high = min(high, y)
            low = max(low, y)
        }
        return Triple(x, high, low)
    }

    const val VERTICES_PER_SURFEL = 4

    /** Twelve tubes of eight vertices. */
    private const val EDGE_VERTICES = 96
}

/**
 * When the replay's camera is slid to bring a picked box, and its label, out from under the
 * chrome ([ArDebugFraming.reveal]), asked once a rendered frame ([due]) (#4505):
 *
 * - after the tap, as soon as the label has a height — or [WAIT_FRAMES] frames later. Until
 *   then the pick is [pending] and nothing is written over it: the label is placed for where the
 *   slide ends, so it is born where it stays instead of jumping there when the slide is over;
 * - and each time a finger that moved the camera has let go and the spin it left has died: an
 *   orbit can turn the box back under the chrome, and a box being read stays whole in view.
 */
class ScanReveal {
    private var waiting = 0
    private var moved = false

    /** `true` from [picked] until the tap's slide has been decided. */
    val pending: Boolean get() = waiting > 0

    /** A tap has just picked an object. */
    fun picked() {
        waiting = WAIT_FRAMES
        moved = false
    }

    /** Nothing to bring out: nothing picked, or a pick found again where the camera had left it. */
    fun settled() {
        waiting = 0
        moved = false
    }

    /**
     * Whether the camera is to be slid now. [labelMeasured] is whether the label's height is
     * known, [moving] is [ArDebugOrbitCamera.moving].
     */
    fun due(labelMeasured: Boolean, moving: Boolean): Boolean {
        if (waiting > 0) {
            if (!labelMeasured && --waiting > 0) return false
            settled()
            return true
        }
        if (moving) {
            moved = true
            return false
        }
        return moved.also { moved = false }
    }

    companion object {
        /** The label is laid out a frame or two after the box: no longer than this is waited. */
        const val WAIT_FRAMES = 4
    }
}

/**
 * A card hanging in a top corner of the replay's view — the 3D view's Camera card: [width] pixels
 * in from the view's edge and the last [height] rows of the cards above; in the end corner, the
 * left one with [atLeft].
 */
data class ScanCard(val width: Int, val height: Int, val atLeft: Boolean = false)

/**
 * What the replay draws over its view, in pixels of it: cards across the first [top] rows, the
 * filmstrip and the dock across the last [bottom], and a [card] in a top corner. A picked object
 * and its label keep [margin] from all of it, and the label [gap] from its box (#4505).
 */
data class ScanChrome(
    val top: Int,
    val bottom: Int,
    val card: ScanCard? = null,
    val margin: Int = 0,
    val gap: Int = 0,
) {

    /**
     * What is left of a view [width] × [height] for a box whose label, [label] pixels tall, goes
     * over it: beside the corner card, or under it — one [FramingInsets] each, for
     * [ArDebugFraming.reveal] to take the nearer of.
     */
    fun free(width: Int, height: Int, label: Int): List<FramingInsets> {
        if (width <= 0 || height <= 0) return emptyList()
        val side = margin.toFloat() / width
        val over = margin + label + gap
        val under = FramingInsets(side, (top + over).toFloat() / height, side, (bottom + margin).toFloat() / height)
        val card = card ?: return listOf(under)
        val column = (card.width + margin).toFloat() / width
        val beside = under.copy(
            top = (top - card.height + over).toFloat() / height,
            left = if (card.atLeft) column else side,
            right = if (card.atLeft) side else column,
        )
        return listOf(beside, under)
    }

    /**
     * Where a label [labelWidth] × [labelHeight] puts its top left corner in a view [width] ×
     * [height], for a box drawn from [above] down to [below] in column [x]: centred over it
     * ([ScanObjectMeshes.labelTop]) — moved aside, level with the corner card, where it would lie
     * under it, and under the box when the view is too narrow for that.
     */
    @Suppress("LongParameterList") // one box, one label, one view: two numbers each
    fun labelAt(
        x: Float,
        above: Float,
        below: Float,
        labelWidth: Int,
        labelHeight: Int,
        width: Int,
        height: Int,
    ): Pair<Int, Int> {
        val centred = (x - labelWidth / 2f).roundToInt().coerceIn(margin, max(margin, width - labelWidth - margin))
        val clear = top + margin..height - bottom - margin
        val card = card ?: return centred to ScanObjectMeshes.labelTop(above, below, labelHeight, gap, clear)
        val y = ScanObjectMeshes.labelTop(above, below, labelHeight, gap, top - card.height + margin..clear.last)
        val column = card.width + margin
        val beside = if (card.atLeft) column..width - margin - labelWidth else margin..width - column - labelWidth
        return when {
            y >= clear.first || centred in beside -> centred to y
            !beside.isEmpty() -> centred.coerceIn(beside.first, beside.last) to y
            else -> centred to ScanObjectMeshes.labelTop(above, below, labelHeight, gap, clear)
        }
    }
}

/**
 * The pinhole this orbit is drawing through right now, [ArDebugOrbitCamera.getTransform]'s own
 * eye and pedestal drop: where a tap's ray starts, and where a point of the room lands on screen.
 * With another [pose], the pinhole it would draw through from there — [ArDebugOrbitCamera.heading],
 * for what must already be where a slide ends.
 */
fun ArDebugOrbitCamera.scanCamera(pose: OrbitPose = this.pose): ScanCamera {
    val at = CameraRig.eye(pose)
    val eye = Vec3(at.x, at.y, at.z)
    val forward = (Vec3(pose.target.x, pose.target.y, pose.target.z) - eye).normalized()
    val up = forward.cross(Vec3.Up).normalized().cross(forward)
    val metresPerPixel = CameraRig.worldPerPixel(pose.distance, verticalFovDegrees, viewportHeight)
    val drop = up * (lift * viewportHeight * metresPerPixel)
    return ScanCamera(
        eye = eye - drop,
        forward = forward,
        tanHalfFov = tan(Math.toRadians(verticalFovDegrees / 2.0)).toFloat(),
        width = viewportWidth,
        height = viewportHeight,
    )
}
