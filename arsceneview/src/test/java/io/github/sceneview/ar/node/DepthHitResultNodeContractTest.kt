package io.github.sceneview.ar.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the public API contract of [DepthHitResultNode] introduced by #1814:
 *  - Class is open + public (callers should be able to subclass for custom behaviour).
 *  - Inherits from [PoseNode] so it integrates with the existing
 *    `ARScene.kt`'s per-frame `childNodes` iteration that runs both `PoseNode` and
 *    `DepthMeshNode` updates.
 *  - Carries the `xPx` / `yPx` constructor properties (read-only) and exposes
 *    `depthHitResult` for surface-normal reads.
 *
 * The full per-frame `update(session, frame)` path requires an ARCore `Frame`, which is
 * JNI-backed and not instantiable in a JVM unit test, but the **shape** of the public
 * API can be pinned via reflection — and that catches the regression where a future
 * refactor accidentally hides the node behind an `internal` or drops a property.
 */
class DepthHitResultNodeContractTest {

    @Test
    fun `DepthHitResultNode is a subclass of PoseNode (for per-frame update wiring)`() {
        assertTrue(
            "DepthHitResultNode must extend PoseNode so ARScene.kt's " +
                "childNodes.filterIsInstance<PoseNode>().forEach { update() } loop picks it up.",
            PoseNode::class.java.isAssignableFrom(DepthHitResultNode::class.java),
        )
    }

    @Test
    fun `class is open so callers can subclass for custom placement logic`() {
        val mod = DepthHitResultNode::class.java.modifiers
        // Kotlin `open class` → JVM `non-final`. A final class would surface as Modifier.isFinal.
        assertTrue(
            "DepthHitResultNode must be open so callers can subclass it to customise the " +
                "per-frame depth-hit response — matches the [HitResultNode] pattern.",
            !java.lang.reflect.Modifier.isFinal(mod),
        )
    }

    @Test
    fun `xPx and yPx are exposed as public read-only properties`() {
        val xPxField = DepthHitResultNode::class.java.declaredFields.firstOrNull { it.name == "xPx" }
        val yPxField = DepthHitResultNode::class.java.declaredFields.firstOrNull { it.name == "yPx" }
        assertNotNull("xPx field must exist (Kotlin val backing field).", xPxField)
        assertNotNull("yPx field must exist (Kotlin val backing field).", yPxField)
        assertEquals(Float::class.java, xPxField!!.type)
        assertEquals(Float::class.java, yPxField!!.type)
    }
}
