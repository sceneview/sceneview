package io.github.sceneview

import androidx.annotation.RestrictTo
import io.github.sceneview.node.Node
import java.util.concurrent.ConcurrentHashMap

/**
 * One scene's answer to "is any node frame-active?", kept without walking the node tree (#3724).
 *
 * `SceneView` and `ARSceneView` each hold one: [setRoots] wherever the scene's top-level node
 * list is published to the render loop, [hasActiveNode] once per tick, [clear] when the scene
 * leaves. The answer is the one `roots.any { it.isFrameActive }` gives — see
 * [FrameActivityTracker] for why it can be trusted and what it costs.
 *
 * Plumbing shared with `arsceneview`, not API: do not call it from an app.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
class SceneFrameActivity {
    private val tracker = FrameActivityTracker(NodeFrameActivityAccess)

    /** Same answer as `roots.any { it.isFrameActive }`, asked of the nodes that can say yes. */
    val hasActiveNode: Boolean get() = tracker.hasActiveNodes

    /** The scene's top-level nodes. Call it with the full list each time; it diffs by itself. */
    fun setRoots(nodes: List<Node>) = tracker.setRoots(nodes)

    /** Lets go of every node. A node kept alive by its owner must not keep the scene alive. */
    fun clear() = tracker.setRoots(emptyList())
}

/** How [FrameActivityTracker] reads a real [Node]. */
internal object NodeFrameActivityAccess : FrameActivityTracker.Access<Node> {
    private val overrides = ConcurrentHashMap<Class<*>, Boolean>()

    override fun isOpaque(node: Node): Boolean {
        val type = node.javaClass
        return overrides[type] ?: overridesIsFrameActive(type).also { overrides[type] = it }
    }

    override fun isFrameActive(node: Node): Boolean = node.isFrameActive
    override fun isSelfFrameActive(node: Node): Boolean = node.isSelfFrameActive
    override fun mayBeSelfFrameActive(node: Node): Boolean = node.mayBeSelfFrameActive
    override fun children(node: Node): Collection<Node> = node.childNodes

    override fun addTracker(node: Node, tracker: FrameActivityTracker<Node>) {
        if (node.frameActivityTrackers.none { it === tracker }) {
            node.frameActivityTrackers = node.frameActivityTrackers + tracker
        }
    }

    override fun removeTracker(node: Node, tracker: FrameActivityTracker<Node>) {
        val remaining = node.frameActivityTrackers.filter { it !== tracker }
        node.frameActivityTrackers = remaining.ifEmpty { emptyList() }
    }
}

/**
 * True when [type] — a [Node] subclass — overrides the public `isFrameActive` getter instead of
 * inheriting [Node]'s.
 *
 * The property is public and `open`, so an app's node type may override it with anything, and an
 * override is the one place the tracker cannot see into: it is asked every tick, in full. Only the
 * class can tell the two apart, hence the reflection — once per class, never per node or per frame.
 *
 * Any doubt answers `true`. A shrinker that renamed the getter makes the lookup fail, and failing
 * means "ask the node the old way", which is slower and never wrong.
 */
internal fun overridesIsFrameActive(type: Class<*>): Boolean = try {
    type.getMethod(IS_FRAME_ACTIVE_GETTER).declaringClass != Node::class.java
} catch (_: ReflectiveOperationException) {
    true
} catch (_: LinkageError) {
    true
} catch (_: SecurityException) {
    true
}

private const val IS_FRAME_ACTIVE_GETTER = "isFrameActive"
