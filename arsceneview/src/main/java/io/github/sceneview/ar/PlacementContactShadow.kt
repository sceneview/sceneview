package io.github.sceneview.ar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.length
import io.github.sceneview.NodeScope
import io.github.sceneview.ar.depth.GroundContact
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.math.Transform
import io.github.sceneview.node.ContactShadowContext
import io.github.sceneview.node.ContactShadowFootprint
import io.github.sceneview.node.ContactShadowNode
import io.github.sceneview.node.ModelNode
import kotlin.math.hypot

/**
 * The soft dark pool under an [AutoPlacementModel], or a sized [AutoPlacementNode], standing on a
 * floor or a table — what makes it
 * look *on* the surface rather than floating a few millimetres above it, as Scene Viewer and AR
 * Quick Look both draw under a placed object.
 *
 * In AR the object hides its own footprint from almost any viewpoint, so the shadow a user
 * actually sees is the band just outside it. The pool is therefore a rounded rectangle on the
 * footprint, [PlacementContactShadow.EDGE_ALPHA] dark at the object's edge and fading out over
 * [PlacementContactShadow.FADE_METERS] of real floor on every side, at any pinch — not an
 * ellipse whose dark core sat inside the footprint and whose pale fade scaled with each axis.
 * Only an object smaller than about half a metre gets a shorter fade, half its larger
 * half-extent, so a mug does not sit in a pool wider than itself.
 *
 * It complements the real shadow the scene's [ShadowReceiverPlane][io.github.sceneview.ar.light.ShadowReceiverPlane]
 * catches: that one follows the estimated light and can fall away from the object, or vanish when
 * the light is straight overhead or no light is estimated; this one stays where the object touches
 * the surface, whatever the light. Sized from the object's own bounds, so the rectangle scales with
 * the pinch and turns with the twist like the selection ring, while the fade keeps its width on
 * the floor. Never touchable, never casts nor receives.
 *
 * Off with [AutoPlacementState.showsContactShadow]. Not drawn for a wall placement: a framed
 * print or a TV hangs off the wall and does not press into it.
 */
@Composable
internal fun NodeScope.PlacementContactShadowNode(
    state: AutoPlacementState,
    corners: List<Position>,
) {
    if (!state.showsContactShadow) return
    // The node sits under the pivot that carries the pinch, so the quad is rebuilt in the
    // pivot's units for each scale: the rectangle follows the object, the fade does not.
    val pinch = state.scaleFactor
    val quad = remember(corners, pinch) { PlacementContactShadow.quad(corners, pinch) } ?: return
    val shadow = remember(engine, materialLoader) {
        ContactShadowNode(
            engine = engine,
            materialLoader = materialLoader,
            size = Size(quad.sizeX, 0f, quad.sizeZ),
            context = ContactShadowContext.Floor,
            intensity = PlacementContactShadow.EDGE_ALPHA,
        ).apply {
            isShadowCaster = false
            isShadowReceiver = false
        }
    }
    SideEffect {
        if (shadow.geometry.size != Size(quad.sizeX, 0f, quad.sizeZ)) {
            shadow.updateGeometry(size = Size(quad.sizeX, 0f, quad.sizeZ))
        }
        // A floor quad's u runs along +X and v along -Z: the rectangle is symmetric, so its
        // half-extents map straight onto X and Z.
        shadow.footprint = ContactShadowFootprint(
            halfU = quad.halfX,
            halfV = quad.halfZ,
            fade = quad.fade,
            cornerRadius = quad.cornerRadius,
        )
        shadow.position = Position(quad.centerX, 0f, quad.centerZ)
    }
    NodeLifecycle(shadow, null)
}

/** The model's eight bounds corners in its parent's frame: rotated, scaled and grounded. */
internal fun ModelNode.groundedCorners(): List<Position> = buildList {
    for (x in listOf(-1f, 1f)) for (y in listOf(-1f, 1f)) for (z in listOf(-1f, 1f)) {
        val corner = center + Position(x * halfExtent.x, y * halfExtent.y, z * halfExtent.z)
        add(quaternion * (corner * scale) + position)
    }
}

/** Geometry of the contact shadow quad — pure, pinned in `PlacementContactShadowTest`. */
internal object PlacementContactShadow {

    /**
     * Opacity at the object's edge and under it. The band outside the footprint is all a viewer
     * sees, so it has to read on a light floor on its own.
     */
    const val EDGE_ALPHA = 0.4f

    /** How far beyond the footprint the shadow fades out, metres of real floor, at any pinch. */
    const val FADE_METERS = 0.12f

    /**
     * A small object's fade, as a fraction of its larger half-extent (as displayed): a mug does
     * not get a 12 cm pool. Takes over from [FADE_METERS] below a 48 cm footprint.
     */
    const val FADE_FRACTION = 0.5f

    /**
     * Corner radius of the rectangle, as a fraction of its smaller half-extent. The bounds cannot
     * tell a round table from a square one, so this splits the difference: with 0.6, a sofa's
     * edge band is still half dark 9 cm from its bounds' corner, and a round table's band reaches
     * only 8 cm farther along its diagonals than along its axes (pinned in
     * `PlacementContactShadowTest`). A sharp corner (0) would leave a round object's square pool
     * a fifth of its radius past its rim at full darkness; a full round (1) would leave a box's
     * corners bare.
     */
    const val CORNER_FRACTION = 0.6f

    /** Bare quad kept beyond the end of the fade, metres: room for the material's 1 cm feather. */
    const val MARGIN_METERS = 0.02f

    /** Smallest half-extent, metres — a few millimetres of pool under a key or a ring. */
    const val MIN_HALF_EXTENT = 0.01f

    /**
     * The shadow under a footprint, in the surface frame's units (the pivot's, before its pinch
     * scale): the quad's centre (parent X/Z) and its size along X and Z, and the rounded rectangle
     * drawn in it ([halfX], [halfZ], [cornerRadius]), fully dark up to its outline and faded out
     * [fade] beyond it.
     */
    data class Quad(
        val centerX: Float,
        val centerZ: Float,
        val sizeX: Float,
        val sizeZ: Float,
        val halfX: Float,
        val halfZ: Float,
        val fade: Float,
        val cornerRadius: Float,
    ) {
        /** Farthest the shadow reaches from the centre: a corner, at the end of the fade. */
        val reach: Float
            get() = hypot(halfX - cornerRadius, halfZ - cornerRadius) + cornerRadius + fade
    }

    /**
     * The quad under the object whose bounds corners (in the surface frame, +Y the normal) are
     * [corners], shown at [pinch] times that size, or `null` when there is nothing finite to
     * ground. The rectangle is the footprint itself, so the object hides the inside and the
     * visible band starts at its edge. The fade is divided by [pinch], so it stays
     * [FADE_METERS] wide on the real floor.
     */
    fun quad(corners: List<Position>, pinch: Float = 1f): Quad? {
        if (corners.isEmpty()) return null
        val minX = corners.minOf { it.x }
        val maxX = corners.maxOf { it.x }
        val minZ = corners.minOf { it.z }
        val maxZ = corners.maxOf { it.z }
        if (!(minX.isFinite() && maxX.isFinite() && minZ.isFinite() && maxZ.isFinite())) return null
        val halfX = maxOf((maxX - minX) / 2f, MIN_HALF_EXTENT)
        val halfZ = maxOf((maxZ - minZ) / 2f, MIN_HALF_EXTENT)
        val scale = if (pinch > 0f && pinch.isFinite()) pinch else 1f
        val fade = minOf(FADE_METERS / scale, maxOf(halfX, halfZ) * FADE_FRACTION)
        return Quad(
            centerX = (minX + maxX) / 2f,
            centerZ = (minZ + maxZ) / 2f,
            sizeX = 2f * (halfX + fade + MARGIN_METERS),
            sizeZ = 2f * (halfZ + fade + MARGIN_METERS),
            halfX = halfX,
            halfZ = halfZ,
            fade = fade,
            cornerRadius = minOf(halfX, halfZ) * CORNER_FRACTION,
        )
    }
}

/**
 * The contact a placed model registers with the scene's
 * [GroundContacts][io.github.sceneview.ar.depth.GroundContacts]: the disc its contact shadow and
 * selection ring cover on the surface, inside which the depth occlusion counts the floor's depth
 * noise as the floor (rule 4 in `camera_stream_depth.mat`). Pure, pinned in
 * `PlacementGroundContactTest`.
 */
internal object PlacementGroundContact {

    /**
     * Disc radius in the surface frame, metres: the outer edge of the selection ring's edging, or the
     * contact shadow's [reach][PlacementContactShadow.Quad.reach] when that is farther.
     */
    fun radius(footprint: SelectionRing.Footprint, quad: PlacementContactShadow.Quad?): Float {
        // The outer edging of the held (widest) ring.
        val ring = if (footprint.radius > 0f) {
            SelectionRing.bands(footprint, held = true).maxOf { it.radius + it.tube }
        } else 0f
        val shadow = quad?.reach ?: 0f
        return maxOf(ring, shadow)
    }

    /**
     * The world-space contact of the disc of [radius] centred on ([centerX], 0, [centerZ]) in the
     * surface frame whose world transform is [surface] (+Y the surface normal), or `null` when
     * that frame is degenerate.
     */
    fun world(surface: Transform, centerX: Float, centerZ: Float, radius: Float): GroundContact? {
        val center = (surface * Float4(centerX, 0f, centerZ, 1f)).xyz
        val up = surface.y.xyz
        val scale = length(up)
        if (!(scale > 0f) || !scale.isFinite() || !(radius > 0f)) return null
        if (!(center.x.isFinite() && center.y.isFinite() && center.z.isFinite())) return null
        return GroundContact(center, up / scale, radius * scale)
    }
}
