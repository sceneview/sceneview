package io.github.sceneview.node

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the collider-follows-geometry contract of issue #3194.
 *
 * `updateGeometry` pushes a new AABB to Filament — which is what culling and rendering use — but
 * `collisionShape` is separate state that kept whatever box the node was built with. A node
 * resized after construction therefore rendered at its new size and *picked* at its old one:
 * build a `CubeNode`, assign a larger `size`, and a `hitTest` near a corner reported no hit while
 * the cube visibly rendered there.
 *
 * The rigorous engine-backed proof — build a real `CubeNode`, resize it, read back the real
 * collider extents — needs a Filament `Engine` (JNI) and so lives in the instrumented
 * `ResizedNodeColliderTest`, next to `CollisionSystemIntegrationTest`. See
 * `src/test/java/io/github/sceneview/node/UNTESTABLE.md`: a `Node` cannot be constructed on the
 * JVM at all.
 *
 * What is left to pin here is the wiring the engine-backed test cannot reach from CI, and it is
 * the part that actually decides whether the bug is fixed *everywhere* rather than at one call
 * site. Two things must hold:
 *
 *  1. **The refresh sits at the choke point.** The obvious hoist — `GeometryNode.updateGeometry`
 *     — would have fixed almost nothing: the shape-specific overloads (`CubeNode`, `SphereNode`,
 *     `PlaneNode`, …) do not delegate to it, they call `setGeometry(...)` themselves. The one
 *     point all eleven overloads share is `RenderableNode.setGeometry`.
 *  2. **That choke point stays the only route.** [everyUpdateGeometryOverloadRoutesThroughSetGeometry]
 *     is the guard: a twelfth node type whose `updateGeometry` bypassed `setGeometry` would
 *     silently reintroduce the bug for that type only, which is exactly how this defect survived
 *     #2845/#3169.
 *
 * These are source/bytecode assertions, so they prove the wiring exists, not that Filament
 * reports the extents we expect — that is the instrumented test's job. Both are needed; neither
 * subsumes the other.
 */
class RenderableNodeCollisionShapeContractTest {

    private val nodeDir = File("src/main/java/io/github/sceneview/node")

    private val renderableNodeSource = File(nodeDir, "RenderableNode.kt").readText()
    private val nodeSource = File(nodeDir, "Node.kt").readText()

    /**
     * Every node type that declares an `updateGeometry` overload. All eleven were listed in
     * #3194 as call sites that failed to refresh the collider.
     */
    private val updateGeometryNodes = listOf(
        "GeometryNode", "PlaneNode", "CubeNode", "SphereNode", "CylinderNode", "ConeNode",
        "TorusNode", "CapsuleNode", "LineNode", "PathNode", "ShapeNode"
    )

    // ── 1. The refresh is wired at the choke point ───────────────────────────

    @Test
    fun `RenderableNode overrides setGeometry to refresh the collider`() {
        val override = renderableNodeSource
            .substringAfter("override fun setGeometry(", missingDelimiterValue = "")
            .substringBefore("\n    }")

        assertTrue(
            "RenderableNode must override setGeometry — it is the single point every " +
                "updateGeometry overload passes through, so it is the only place a collider " +
                "refresh fixes all eleven of them (#3194)",
            override.isNotEmpty()
        )
        assertTrue(
            "the override must still perform the real geometry change",
            override.contains("super.setGeometry(geometry)")
        )
        assertTrue(
            "the override must re-derive the collider from the new bounding box",
            override.contains("updateCollisionShape()")
        )
        assertTrue(
            "the refresh must back off for an app-assigned collider — overwriting one would " +
                "break the documented ability to set collisionShape by hand",
            override.contains("hasCustomCollisionShape")
        )
    }

    @Test
    fun `the custom-collision-shape latch exists on Node`() {
        // An internal Kotlin property still emits a backing field under its source name, so this
        // reads the compiled class rather than the source text.
        val field = Node::class.java.declaredFields.firstOrNull {
            it.name == "hasCustomCollisionShape"
        }
        assertTrue(
            "Node must carry the opt-out latch that tells an automatic collider refresh apart " +
                "from an app-assigned collisionShape (#3194)",
            field != null
        )
    }

    @Test
    fun `assigning collisionShape latches the opt-out`() {
        val setter = nodeSource
            .substringAfter("var collisionShape: CollisionShape? = null", missingDelimiterValue = "")
            .substringBefore("\n\n")

        assertTrue(
            "Node.collisionShape must exist and be settable",
            setter.contains("set(value)")
        )
        assertTrue(
            "the public collisionShape setter must latch hasCustomCollisionShape, so a later " +
                "updateGeometry does not overwrite the app's collider — including a deliberate " +
                "null, which a blind refresh would resurrect into a pickable collider (#3194)",
            setter.contains("hasCustomCollisionShape = true")
        )
    }

    @Test
    fun `updateCollisionShape clears the opt-out`() {
        val body = renderableNodeSource
            .substringAfter("fun updateCollisionShape() {", missingDelimiterValue = "")
            .substringBefore("\n    }")

        assertTrue(
            "RenderableNode.updateCollisionShape() must exist",
            body.contains("collisionShape = axisAlignedBoundingBox.toVector3Box()")
        )
        assertTrue(
            "updateCollisionShape() assigns through the public setter, which latches the " +
                "opt-out; it must unlatch it afterwards or the two construction paths " +
                "(RenderableNode's builder constructor and MeshNode's init) would leave every " +
                "node permanently opted out and #3194 would be unfixed",
            body.contains("hasCustomCollisionShape = false")
        )
    }

    // ── 2. The choke point stays the only route ──────────────────────────────

    @Test
    fun everyUpdateGeometryOverloadRoutesThroughSetGeometry() {
        updateGeometryNodes.forEach { type ->
            val source = File(nodeDir, "$type.kt").readText()
            val overload = source
                .substringAfter("fun updateGeometry(", missingDelimiterValue = "")
                .substringBefore("\n\n")

            assertTrue("$type.kt must declare an updateGeometry overload", overload.isNotEmpty())
            assertTrue(
                "$type.updateGeometry must terminate in setGeometry(...): that call is what " +
                    "refreshes the collider, so an overload reaching Filament by another route " +
                    "would silently pick at its old size again (#3194)",
                overload.contains("setGeometry(")
            )
        }
    }

    @Test
    fun `ViewNode resizes through the same choke point`() {
        // ViewNode was the acute case in #3194: its quad is sized from the measured view, so the
        // collider stayed at Plane.DEFAULT_SIZE's 1 x 1 x 0 forever and a card lost its outer
        // band to hitTest — which is where buttons usually are.
        val viewNodeSource = File(nodeDir, "ViewNode.kt").readText()
        val body = viewNodeSource
            .substringAfter("fun updateGeometrySize() {", missingDelimiterValue = "")
            .substringBefore("\n    }")

        assertTrue("ViewNode.updateGeometrySize() must exist", body.isNotEmpty())
        assertTrue(
            "ViewNode must resize via updateGeometry so it inherits the collider refresh " +
                "instead of needing its own patch (#3194)",
            body.contains("updateGeometry(")
        )
    }
}
