package io.github.sceneview.ar.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the public API contract of [DepthHitResultNode] introduced by #1814 and extended in
 * #1844:
 *  - Class is open + public (callers should be able to subclass for custom behaviour).
 *  - Inherits from [PoseNode] so it integrates with the existing
 *    `ARScene.kt`'s per-frame `childNodes` iteration that runs both `PoseNode` and
 *    `DepthMeshNode` updates.
 *  - Exposes a `hitTest` lambda (primary constructor, #1844) so callers can override the
 *    selection rule without subclassing.
 *  - Ships a secondary `(engine, xPx, yPx)` constructor for the common centre-screen case
 *    — mirrors [io.github.sceneview.ar.node.HitResultNode]'s 2-overload surface.
 *
 * The full per-frame `update(session, frame)` path requires an ARCore `Frame`, which is
 * JNI-backed and not instantiable in a JVM unit test, but the **shape** of the public
 * API can be pinned via reflection — and that catches the regression where a future
 * refactor accidentally hides the node behind an `internal` or drops the secondary
 * `(xPx, yPx)` overload.
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
    fun `hitTest lambda is exposed on the primary constructor (1844)`() {
        // Kotlin primary `val hitTest: …` surfaces as a JVM backing field of type Function1
        // (or Function2 for receiver lambdas — JVM lowers `T.(Frame) -> R` to a Function1
        // taking the receiver as first param + an extra Frame, hence Function2). Either way
        // the field exists and is non-null.
        val hitField = DepthHitResultNode::class.java.declaredFields
            .firstOrNull { it.name == "hitTest" }
        assertNotNull(
            "hitTest field must exist so callers can swap in a custom depth-hit selector " +
                "without subclassing (matches HitResultNode's lambda overload, #1844).",
            hitField,
        )
        // Sanity-check the type is a kotlin.jvm.functions.Function* instance.
        assertTrue(
            "hitTest must be a Kotlin function type — got ${hitField!!.type}.",
            hitField.type.name.startsWith("kotlin.jvm.functions.Function"),
        )
    }

    @Test
    fun `secondary (engine xPx yPx) constructor mirrors HitResultNode surface (1844)`() {
        // The convenience overload for "raycast at one fixed screen pixel" must remain available
        // — drop-in equivalent of `HitResultNode(engine, xPx, yPx, …)`. The Engine type is
        // JNI-backed so we only verify by parameter-type signature; reflection cannot instantiate.
        val ctor = DepthHitResultNode::class.java.declaredConstructors.firstOrNull { ctor ->
            ctor.parameterTypes.size == 3 &&
                ctor.parameterTypes[0].name == "com.google.android.filament.Engine" &&
                ctor.parameterTypes[1] == java.lang.Float.TYPE &&
                ctor.parameterTypes[2] == java.lang.Float.TYPE
        }
        assertNotNull(
            "Secondary `(engine: Engine, xPx: Float, yPx: Float)` constructor must exist — " +
                "mirrors HitResultNode's screen-pixel overload (#1844).",
            ctor,
        )
        // Witness that assertEquals is wired for downstream test maintenance.
        assertEquals(3, ctor!!.parameterTypes.size)
    }
}
