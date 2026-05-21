package io.github.sceneview.ar.xr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.xr.arcore.Hand
import com.google.android.filament.Engine
import io.github.sceneview.NodeScope
import io.github.sceneview.SceneScope
import io.github.sceneview.math.Position
import io.github.sceneview.node.Node

/**
 * Opt-in marker for the **preview** Jetpack XR layer of SceneView.
 *
 * Every public symbol that wraps `androidx.xr.arcore` (`1.0.0-alpha14` at the
 * time of writing) carries this annotation. The upstream SDK is alpha and
 * subject to breaking changes; requiring an explicit opt-in makes the preview
 * status visible at every call site rather than burying it in a doc comment.
 *
 * Opt in per call site with `@OptIn(XrPreviewApi::class)`, or module-wide via
 * the `-opt-in` compiler flag.
 *
 * See [arsceneview/docs/JETPACK-XR-INTEGRATION.md](https://github.com/sceneview/sceneview/blob/main/arsceneview/docs/JETPACK-XR-INTEGRATION.md)
 * — tracking issue [#1738](https://github.com/sceneview/sceneview/issues/1738).
 */
@RequiresOptIn(
    message = "Jetpack XR API is preview (androidx.xr.arcore 1.0.0-alpha14) and may change.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class XrPreviewApi

/**
 * A scene-graph node that mirrors a hand tracked by **ARCore for Jetpack XR**
 * (`androidx.xr.arcore.Hand`) on an Android XR headset or glasses.
 *
 * `XrHandNode` is the Jetpack XR sibling of [io.github.sceneview.ar.node.AugmentedFaceNode]:
 * it wraps a single tracked trackable (here a [Hand]) and exposes a child node
 * per skeleton joint whose transform follows the live pose. Attach renderable
 * geometry — a [io.github.sceneview.node.SphereNode] per joint, a
 * [io.github.sceneview.node.LineNode] per bone — under those joint nodes to
 * draw a hand skeleton, or anchor a model to a single joint (e.g. a tool menu
 * on the palm).
 *
 * **Updating.** This node holds no `StateFlow` subscription of its own — the
 * consumer drives it. Collect [Hand.state] on the main dispatcher and call
 * [update] with each emitted snapshot, or call the no-arg [update] to pull the
 * current `Hand.state.value`. The composable wrapper
 * [SceneScope.XrHandNode] does this for you.
 *
 * **Threading.** [update] only writes Filament `Node` transforms (position /
 * quaternion) — it performs no Filament JNI resource creation — but, like every
 * `Node` mutation, it MUST run on the main thread. Drive it from a Compose
 * `LaunchedEffect` collecting the flow on `Dispatchers.Main` (the composable
 * wrapper already does). See `CLAUDE.md` "Critical threading rule".
 *
 * **Availability.** Only meaningful when the Jetpack XR runtime is present —
 * gate construction on [XrFeatures.isAvailable]. On a phone-only build the
 * `androidx.xr.arcore` classes are absent and merely referencing [Hand] would
 * throw `NoClassDefFoundError` at class-load time, so never instantiate this
 * node unless [XrFeatures.isAvailable] returned `true`.
 *
 * Preview: this API wraps `androidx.xr.arcore` `1.0.0-alpha14` and may change —
 * see [XrPreviewApi].
 *
 * @property engine     The Filament [Engine] that owns the joint child nodes.
 * @property hand       The upstream [Hand] trackable this node mirrors.
 * @property handedness Which hand [hand] represents — left or right.
 */
@XrPreviewApi
open class XrHandNode(
    engine: Engine,
    val hand: Hand,
    val handedness: XrHandedness,
    /**
     * Optional per-frame callback fired after [update] has applied the latest
     * joint poses. Receives the count of joints tracked this frame (0 ..
     * [XrHandSkeleton.jointCount]) so the consumer can react to the hand
     * entering / leaving tracking.
     */
    private val onUpdated: ((trackedJointCount: Int) -> Unit)? = null,
) : Node(engine) {

    /**
     * One child [Node] per [XrHandJoint], indexed by [XrHandJoint.ordinal].
     *
     * Each joint node's local transform is rewritten every [update] to the
     * latest tracked pose. A joint that is not tracked this frame has its node
     * hidden ([Node.isVisible] = `false`) rather than left at a stale pose.
     * Attach renderable geometry as children of these nodes.
     */
    val jointNodes: List<Node> = List(XrHandSkeleton.jointCount) {
        Node(engine).apply { parent = this@XrHandNode }
    }

    /**
     * Returns the joint node for [joint] — a convenience over
     * `jointNodes[joint.ordinal]`.
     */
    fun jointNode(joint: XrHandJoint): Node = jointNodes[joint.ordinal]

    /**
     * Snapshot of each joint's local position from the most recent [update],
     * indexed by [XrHandJoint.ordinal]. A `null` slot is a joint that was not
     * tracked. Pass this to [XrHandSkeleton] helpers (bone lengths, midpoints,
     * total length) — it is the bridge between the preview XR poses and the
     * pure-logic skeleton math.
     */
    val jointPositions: Array<Position?> = arrayOfNulls(XrHandSkeleton.jointCount)

    /** Whether at least one joint was tracked in the most recent [update]. */
    var isTracking: Boolean = false
        private set

    /**
     * Pulls the current [Hand.state] snapshot and applies it — equivalent to
     * `update(readHandJoints(hand))`.
     */
    fun update() {
        update(readHandJoints(hand))
    }

    /**
     * Applies a pre-extracted set of joint positions.
     *
     * [joints] is keyed by [XrHandJoint]; a missing key is an untracked joint.
     * Use this overload when the consumer already collected [Hand.state] and
     * converted the poses (e.g. transformed into a different coordinate space)
     * — it never touches the preview XR types.
     *
     * Must run on the main thread (writes `Node` transforms).
     */
    fun update(joints: Map<XrHandJoint, Position>) {
        var tracked = 0
        for (joint in XrHandJoint.entries) {
            val node = jointNodes[joint.ordinal]
            val position = joints[joint]
            if (position != null) {
                node.position = position
                node.isVisible = true
                jointPositions[joint.ordinal] = position
                tracked++
            } else {
                node.isVisible = false
                jointPositions[joint.ordinal] = null
            }
        }
        isTracking = tracked > 0
        onUpdated?.invoke(tracked)
    }

    override fun destroy() {
        // Joint child nodes are owned by this node — destroy them before the
        // parent chain tears down so their entities leave the Filament scene
        // first (same ordering rationale as AugmentedFaceNode.destroy()).
        jointNodes.forEach { runCatching { it.destroy() } }
        super.destroy()
    }
}

/**
 * Reads the live joint poses of [hand] into a [XrHandJoint]-keyed map.
 *
 * Bridges the upstream `androidx.xr.arcore` perception types to SceneView's own
 * [Position]. The upstream `HandState.handJoints` is a `Map<HandJointType,
 * Pose>`; this matches each upstream `HandJointType` to a SceneView
 * [XrHandJoint] **by enum name** so the mapping survives the alpha SDK adding
 * or reordering joints — an unrecognised upstream joint is simply skipped, and
 * a SceneView joint with no upstream counterpart is absent from the result
 * (the node hides it).
 *
 * The name match is reflective so this function never hard-references an
 * individual `HandJointType` constant that a future alpha might rename. It
 * still requires the `androidx.xr.arcore` runtime to be present — only call it
 * behind [XrFeatures.isAvailable].
 *
 * Returns an empty map when the hand is not currently tracked or the upstream
 * state cannot be read.
 */
@XrPreviewApi
fun readHandJoints(hand: Hand): Map<XrHandJoint, Position> = runCatching {
    // `Hand.state` is a StateFlow<HandState>; `.value` is the current snapshot.
    val state: Any = hand.state.value
    // `HandState.handJoints: Map<HandJointType, Pose>` — read reflectively so a
    // rename of the property or the joint enum on the alpha SDK degrades to an
    // empty map (hand hidden) instead of a hard crash.
    @Suppress("UNCHECKED_CAST")
    val handJoints = state.javaClass.getMethod("getHandJoints").invoke(state)
        as? Map<Any, Any> ?: return emptyMap()

    val result = HashMap<XrHandJoint, Position>(handJoints.size)
    val byName = XrHandJoint.entries.associateBy { it.name }
    for ((jointType, pose) in handJoints) {
        // HandJointType is an enum — `.name` gives e.g. "INDEX_TIP".
        val name = (jointType as? Enum<*>)?.name ?: continue
        val sceneJoint = byName[name] ?: continue
        val position = pose.toPosition() ?: continue
        result[sceneJoint] = position
    }
    result
}.getOrDefault(emptyMap())

/**
 * Converts an upstream `androidx.xr.runtime.math.Pose` to a SceneView
 * [Position] by reading its `translation` ( `Vector3` ) reflectively. Returns
 * `null` if the pose shape is not what this preview integration expects.
 */
private fun Any.toPosition(): Position? = runCatching {
    val translation = javaClass.getMethod("getTranslation").invoke(this)
    val x = translation.javaClass.getMethod("getX").invoke(translation) as Float
    val y = translation.javaClass.getMethod("getY").invoke(translation) as Float
    val z = translation.javaClass.getMethod("getZ").invoke(translation) as Float
    Position(x = x, y = y, z = z)
}.getOrNull()

/**
 * Declarative [XrHandNode] for use inside a `SceneView { }` / `ARSceneView { }`
 * content block.
 *
 * Mirrors the `SceneScope.SphereNode(...)` etc. composable style: it creates
 * and remembers the imperative [XrHandNode], attaches it to the scene for the
 * lifetime of the composition, and exposes the joint child nodes through a
 * [io.github.sceneview.NodeScope] `content` block so renderable geometry can
 * be nested under them.
 *
 * **Pose refresh.** The composable re-reads the live hand pose in a
 * `SideEffect`, which runs after every successful (re)composition on the main
 * thread. Consumers that recompose once per AR frame (e.g. by hoisting frame
 * state from `onSessionUpdated`) get a per-frame skeleton update for free,
 * without spinning up a separate `StateFlow` collector. Consumers that need an
 * update cadence decoupled from recomposition can instead collect [Hand.state]
 * themselves and call [XrHandNode.update] on the imperative node.
 *
 * ```kotlin
 * @OptIn(XrPreviewApi::class)
 * SceneView {
 *     if (XrFeatures.isAvailable(context)) {
 *         XrHandNode(hand = Hand.right(session), handedness = XrHandedness.RIGHT) {
 *             // `this` is a NodeScope rooted at the hand node — declare per-joint geometry.
 *         }
 *     }
 * }
 * ```
 *
 * Preview: see [XrPreviewApi].
 *
 * @param hand       The upstream [Hand] trackable to mirror.
 * @param handedness Which hand [hand] is.
 * @param onUpdated  Optional callback fired after each pose update with the
 *                   number of joints tracked that frame.
 * @param apply      Imperative configuration applied once on creation.
 * @param content    Optional child nodes declared in a `NodeScope` rooted at
 *                   the hand node.
 */
@XrPreviewApi
@Composable
fun SceneScope.XrHandNode(
    hand: Hand,
    handedness: XrHandedness,
    onUpdated: ((trackedJointCount: Int) -> Unit)? = null,
    apply: XrHandNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null,
) {
    val node = remember(engine, hand) {
        XrHandNode(
            engine = engine,
            hand = hand,
            handedness = handedness,
            onUpdated = onUpdated,
        ).apply(apply)
    }
    // Re-read the live hand pose on every recomposition. The consumer triggers a
    // recomposition per AR frame (e.g. from `onSessionUpdated`), so SideEffect —
    // which runs after a successful composition on the main thread — is the
    // right place to push transforms without spinning up a coroutine collector.
    SideEffect {
        node.update()
    }
    NodeLifecycle(node, content)
}
