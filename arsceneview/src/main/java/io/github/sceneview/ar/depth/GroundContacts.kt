package io.github.sceneview.ar.depth

import dev.romainguy.kotlin.math.Float3
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.sqrt

/**
 * Where a placed object touches its surface, in world space: the input of occlusion rule 4
 * ([DepthOcclusionShaping.groundBand]).
 *
 * @property center A point of the contact plane, the centre of the contact disc.
 * @property normal The plane's normal (any length).
 * @property radius The contact disc's radius, metres: what the object's contact shadow and
 *                  placement ring cover. The rule fades out over [DepthOcclusionShaping.GROUND_FADE]
 *                  of it beyond.
 */
internal data class GroundContact(val center: Float3, val normal: Float3, val radius: Float)

/**
 * The contacts of the objects placed in an AR scene, read each frame by the camera stream to
 * ground its depth occlusion around them. Registered from composition and read on the render
 * thread, which is the main thread in SceneView; the list is copy-on-write all the same.
 */
internal class GroundContacts {

    private val providers = CopyOnWriteArrayList<() -> GroundContact?>()

    /**
     * Adds a contact read again every frame (`null` while it has none, e.g. not placed yet) and
     * returns what removes it.
     */
    fun register(provider: () -> GroundContact?): () -> Unit {
        providers += provider
        return { providers.remove(provider) }
    }

    /**
     * Writes the camera materials' `groundContact` (view-space centre, radius) into
     * `out[0..3]` and `groundNormal` (view-space unit normal, `1`) into `out[4..7]` for the
     * contact nearest the camera, from the column-major [viewMatrix]. All zero — the rule off —
     * when no contact is registered. Returns whether one was.
     */
    fun viewParameters(viewMatrix: FloatArray, out: FloatArray): Boolean {
        out.fill(0f)
        var nearest = Float.POSITIVE_INFINITY
        for (provider in providers) {
            val contact = provider() ?: continue
            if (!(contact.radius > 0f) || !contact.radius.isFinite()) continue
            val (cx, cy, cz) = transformPoint(viewMatrix, contact.center)
            val distance = cx * cx + cy * cy + cz * cz
            if (!distance.isFinite() || distance >= nearest) continue
            val (nx, ny, nz) = transformDirection(viewMatrix, contact.normal)
            val length = sqrt(nx * nx + ny * ny + nz * nz)
            if (!(length > 0f) || !length.isFinite()) continue
            nearest = distance
            out[0] = cx; out[1] = cy; out[2] = cz; out[3] = contact.radius
            out[4] = nx / length; out[5] = ny / length; out[6] = nz / length; out[7] = 1f
        }
        return nearest.isFinite()
    }

    private fun transformPoint(m: FloatArray, p: Float3) = Float3(
        m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12],
        m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13],
        m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14],
    )

    // The view matrix is rigid, so its 3x3 block carries normals as it carries directions.
    private fun transformDirection(m: FloatArray, d: Float3) = Float3(
        m[0] * d.x + m[4] * d.y + m[8] * d.z,
        m[1] * d.x + m[5] * d.y + m[9] * d.z,
        m[2] * d.x + m[6] * d.y + m[10] * d.z,
    )
}
