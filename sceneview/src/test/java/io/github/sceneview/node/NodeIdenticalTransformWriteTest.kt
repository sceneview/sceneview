package io.github.sceneview.node

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.math.Transform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pins the premise of the identical-write guard in `Node.applyCachedTransform()` (#3718).
 *
 * A per-frame producer keeps writing after its motion has settled — a physics step re-pushes the
 * resting position of every body, an animation sampler re-pushes the last keyframe. Measured on the
 * animation-physics demo with every body at rest: 420 of 420 writes byte-identical, and 2016–2968
 * `requestRender()` calls per 60 ticks, all from the single `applyCachedTransform` → `requestRender`
 * site, which held the scene at full cadence for as long as the stepper ran. The fix compares the
 * freshly composed matrix against `_transform` — the exact matrix last pushed to Filament — and
 * returns without writing or notifying when they are equal.
 *
 * That comparison is only as good as [Transform]'s equality, which Filament cannot answer for us on
 * the JVM: this is the part of the fix that can be pinned here, and it is the part that could
 * silently stop working. A `Mat4` that compared by identity would never match — the never-parking
 * behaviour would come straight back with no test going red — and one that compared too loosely
 * would swallow real movement.
 */
class NodeIdenticalTransformWriteTest {

    private val restingQuaternion = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), 37.5f)
    private val restingScale = Float3(1f, 1f, 1f)

    private fun compose(position: Float3, quaternion: Quaternion = restingQuaternion) =
        Transform(position, quaternion, restingScale)

    @Test
    fun `the same TRS composed from distinct instances compares equal`() {
        // The resting-body case, with the allocation the caller really makes: `PhysicsNode.step`
        // writes `node.position = Position(nx, ny, nz)` — a new object every frame, equal
        // component by component. Equality must be by value, or the guard never fires.
        assertEquals(
            compose(Float3(0.412f, 0.0899f, -1.337f)),
            compose(Float3(0.412f, 0.0899f, -1.337f))
        )
    }

    @Test
    fun `a sub-millimetre move compares different`() {
        // The other direction, and the one a too-clever epsilon would break: a body still creeping
        // must keep invalidating. 1e-6 world unit is well below the motion threshold the demo's own
        // settle detector uses, and it still compares different.
        assertNotEquals(
            compose(Float3(0.412f, 0.0899f, -1.337f)),
            compose(Float3(0.412f, 0.0899f + 1e-6f, -1.337f))
        )
    }

    @Test
    fun `a rotation with no translation compares different`() {
        // A body spinning in place writes the same position every frame and must still invalidate:
        // the guard compares the composed matrix, not the position.
        assertNotEquals(
            compose(Float3(0.412f, 0.0899f, -1.337f)),
            compose(
                Float3(0.412f, 0.0899f, -1.337f),
                Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), 38.5f)
            )
        )
    }
}
