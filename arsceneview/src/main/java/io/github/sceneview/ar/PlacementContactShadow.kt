package io.github.sceneview.ar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.sceneview.NodeScope
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.node.ContactShadowContext
import io.github.sceneview.node.ContactShadowNode
import io.github.sceneview.node.ModelNode

/**
 * The soft dark pool under an [AutoPlacementModel] standing on a floor or a table — what makes it
 * look *on* the surface rather than floating a few millimetres above it, as Scene Viewer and AR
 * Quick Look both draw under a placed object.
 *
 * It complements the real shadow the scene's [ShadowReceiverPlane][io.github.sceneview.ar.light.ShadowReceiverPlane]
 * catches: that one follows the estimated light and can fall away from the object, or vanish when
 * the light is straight overhead or no light is estimated; this one stays where the object touches
 * the surface, whatever the light. Sized from the object's own bounds, so it scales with the pinch
 * and turns with the twist like the selection ring. Never touchable, never casts nor receives.
 *
 * Off with [AutoPlacementState.showsContactShadow]. Not drawn for a wall placement: a framed
 * print or a TV hangs off the wall and does not press into it.
 */
@Composable
internal fun NodeScope.PlacementContactShadowNode(
    state: AutoPlacementState,
    model: ModelNode,
) {
    if (!state.showsContactShadow) return
    val quad = remember(model) { PlacementContactShadow.quad(model.groundedCorners()) } ?: return
    val shadow = remember(engine, materialLoader, quad) {
        ContactShadowNode(
            engine = engine,
            materialLoader = materialLoader,
            size = Size(quad.sizeX, 0f, quad.sizeZ),
            context = ContactShadowContext.Floor,
        ).apply {
            position = Position(quad.centerX, 0f, quad.centerZ)
            isShadowCaster = false
            isShadowReceiver = false
        }
    }
    NodeLifecycle(shadow, null)
}

/** The model's eight bounds corners in its parent's frame: rotated, scaled and grounded. */
private fun ModelNode.groundedCorners(): List<Position> = buildList {
    for (x in listOf(-1f, 1f)) for (y in listOf(-1f, 1f)) for (z in listOf(-1f, 1f)) {
        val corner = center + Position(x * halfExtent.x, y * halfExtent.y, z * halfExtent.z)
        add(quaternion * (corner * scale) + position)
    }
}

/** Geometry of the contact shadow quad — pure, pinned in `PlacementContactShadowTest`. */
internal object PlacementContactShadow {

    /**
     * The dark core reaches this fraction of the footprint's half-extent on each axis: the pool
     * is darkest under the object and starts fading just inside its outline, so a slim chair leg
     * or a sofa's skirt is never ringed by a hard dark band.
     */
    const val CORE_FRACTION = 0.8f

    /**
     * Smallest half-extent of either axis, as a fraction of the larger one. Keeps a pencil-thin
     * object from getting a pool so narrow it reads as a line.
     */
    const val MIN_ASPECT = 0.25f

    /** Smallest half-extent, metres — a few millimetres of pool under a key or a ring. */
    const val MIN_HALF_EXTENT = 0.01f

    /** Quad centre on the surface (parent X/Z) and its size along X and Z, metres. */
    data class Quad(val centerX: Float, val centerZ: Float, val sizeX: Float, val sizeZ: Float)

    /**
     * The quad under the object whose bounds corners (in the surface frame, +Y the normal) are
     * [corners], or `null` when there is nothing finite to ground.
     *
     * [ContactShadowContext.Floor] keeps its pool fully dark out to [ContactShadowContext.radiusU]
     * of the quad's width from the centre, then fades out across
     * `radiusU × softness` more: sizing the quad so that the core is [CORE_FRACTION] of the
     * footprint puts the end of the fade about a quarter of the footprint beyond its edge.
     */
    fun quad(corners: List<Position>, context: ContactShadowContext = ContactShadowContext.Floor): Quad? {
        if (corners.isEmpty()) return null
        val minX = corners.minOf { it.x }
        val maxX = corners.maxOf { it.x }
        val minZ = corners.minOf { it.z }
        val maxZ = corners.maxOf { it.z }
        if (!(minX.isFinite() && maxX.isFinite() && minZ.isFinite() && maxZ.isFinite())) return null
        val rawX = (maxX - minX) / 2f
        val rawZ = (maxZ - minZ) / 2f
        val floor = maxOf(MIN_HALF_EXTENT, maxOf(rawX, rawZ) * MIN_ASPECT)
        val halfX = maxOf(rawX, floor)
        val halfZ = maxOf(rawZ, floor)
        return Quad(
            centerX = (minX + maxX) / 2f,
            centerZ = (minZ + maxZ) / 2f,
            sizeX = halfX * CORE_FRACTION / context.radiusU,
            sizeZ = halfZ * CORE_FRACTION / context.radiusV,
        )
    }
}
