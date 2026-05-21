package io.github.sceneview

import com.google.android.filament.Box
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import io.github.sceneview.math.times
import io.github.sceneview.math.toPosition
import io.github.sceneview.node.Node
import io.github.sceneview.node.RenderableNode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Library-level auto-centering of `SceneView` content — the Android counterpart of the iOS
 * `autoCenterContent` feature shipped in v4.3.0 ([#1026], [PR #1038]).
 *
 * iOS introduces an intermediate `contentRoot` `Entity`; on the first frame where the content's
 * `visualBounds` is non-empty the library translates `contentRoot` so the content centroid lands
 * at the orbit pivot. Android mirrors that here: every node declared inside the `SceneView { }`
 * DSL block is parented to a single intermediate [Node] (the "content root"), and once the union
 * of every renderable's bounding box is non-degenerate the content root is translated by minus
 * the centroid. Lights and the camera are passed to `SceneView` as separate parameters — they are
 * never DSL children — so they are unaffected by the translation, exactly like iOS keeping lights
 * on `entities.root` rather than `contentRoot`.
 *
 * The math here is intentionally split from the Compose / Filament plumbing so it can be unit
 * tested in pure JVM — see `SceneAutoCenterTest`.
 *
 * @see io.github.sceneview.SceneView
 */

/**
 * Smallest mesh extent (in metres) the auto-centre pass accepts as "content has loaded enough to
 * be centred". Below this threshold the bounds are assumed to come from an async model load that
 * has not populated yet, so the pass is deferred to the next frame. Mirrors the iOS
 * `minVisualExtent` constant in `SceneView.swift`.
 */
const val AUTO_CENTER_MIN_VISUAL_EXTENT: Float = 0.001f

/**
 * An axis-aligned bounding box expressed as a centre / half-extent pair, mirroring Filament's
 * [Box] convention. Used by the auto-centring pass to union the bounds of every renderable in the
 * scene without touching Filament's mutable [Box] type — keeping the union math pure and testable.
 */
data class Aabb(
    val center: Position = Position(0f, 0f, 0f),
    val halfExtent: Position = Position(0f, 0f, 0f)
) {
    /** Minimum corner of the box. */
    val min: Position get() = center - halfExtent

    /** Maximum corner of the box. */
    val max: Position get() = center + halfExtent

    /** Full side lengths of the box along x / y / z. */
    val extents: Position get() = halfExtent * 2.0f

    /**
     * Length of the box's space diagonal — `sqrt(x² + y² + z²)` of [extents]. `0` for an empty
     * box. This single scalar is what [FramingGate] tracks frame-to-frame to detect an async model
     * landing and growing the scene's union bounds.
     */
    val diagonal: Float
        get() {
            if (isEmpty) return 0f
            val e = extents
            return sqrt(e.x * e.x + e.y * e.y + e.z * e.z)
        }

    /**
     * `true` when this box carries no measurable volume — either a freshly created zero box or
     * one whose extents are non-finite (Filament reports an empty renderable AABB as
     * `min = +∞ / max = -∞`).
     */
    val isEmpty: Boolean
        get() = !halfExtent.x.isFinite() || !halfExtent.y.isFinite() || !halfExtent.z.isFinite() ||
            (halfExtent.x <= 0f && halfExtent.y <= 0f && halfExtent.z <= 0f)

    /** Returns the smallest box that contains both `this` and [other]. */
    fun union(other: Aabb): Aabb {
        if (other.isEmpty) return this
        if (this.isEmpty) return other
        val minCorner = Position(
            minOf(min.x, other.min.x),
            minOf(min.y, other.min.y),
            minOf(min.z, other.min.z)
        )
        val maxCorner = Position(
            maxOf(max.x, other.max.x),
            maxOf(max.y, other.max.y),
            maxOf(max.z, other.max.z)
        )
        return fromMinMax(minCorner, maxCorner)
    }

    companion object {
        /** Builds an [Aabb] from an explicit min / max corner pair. */
        fun fromMinMax(min: Position, max: Position): Aabb = Aabb(
            center = (min + max) * 0.5f,
            halfExtent = (max - min) * 0.5f
        )
    }
}

/**
 * Unions an arbitrary collection of [Aabb]s, skipping empty boxes. Returns an empty [Aabb] when
 * the input is empty or every box is degenerate.
 */
fun Iterable<Aabb>.union(): Aabb = fold(Aabb()) { acc, box -> acc.union(box) }

/**
 * Computes the union AABB of every renderable found in the subtree rooted at [node], expressed in
 * the local space of [relativeTo]. Empty / not-yet-loaded renderables contribute nothing.
 *
 * Walks [Node.childNodes] recursively. For each [RenderableNode] — including the renderable child
 * nodes a `ModelNode` exposes for its meshes — the per-renderable Filament AABB is transformed
 * into [relativeTo]-local space via the renderable's world transform and the inverse world
 * transform of [relativeTo], so the result is invariant of any rotation / scale the camera
 * manipulator applies to the scene root on each frame.
 *
 * **Threading:** reads Filament `RenderableManager` / `TransformManager` state, so it must run on
 * the main (render) thread — `SceneView`'s frame loop satisfies this.
 *
 * @param node       Subtree root to measure.
 * @param relativeTo Node whose local space the resulting bounds are expressed in.
 */
fun computeContentBounds(node: Node, relativeTo: Node): Aabb {
    val relativeToWorldInverse = relativeTo.worldToLocal
    val acc = ArrayList<Aabb>()
    fun visit(n: Node) {
        if (!n.isVisible) return
        renderableAabbsRelativeTo(n, relativeToWorldInverse, acc)
        n.childNodes.forEach { visit(it) }
    }
    visit(node)
    return acc.union()
}

/**
 * Appends the world-space AABB of the renderable owned by [node] — re-expressed in the local
 * space implied by [relativeToWorldInverse] — into [out]. `ModelNode`s expose their meshes as
 * child `RenderableNode`s, so the recursive `childNodes` walk in [computeContentBounds] already
 * covers them — this only reads the renderable component a node carries itself.
 */
private fun renderableAabbsRelativeTo(
    node: Node,
    relativeToWorldInverse: Transform,
    out: MutableList<Aabb>
) {
    if (node !is RenderableNode) return
    val filamentBox: Box = runCatching { node.axisAlignedBoundingBox }.getOrNull() ?: return
    val localCenter = filamentBox.center.toPosition()
    val localHalf = filamentBox.halfExtent.toPosition()
    if (Aabb(localCenter, localHalf).isEmpty) return
    // Transform the 8 corners of the renderable-local AABB into `relativeTo`-local space, then
    // re-fit an axis-aligned box around them. Going corner-by-corner (rather than just the
    // centre) keeps the box correct under rotation.
    val nodeToRelativeTo = relativeToWorldInverse * node.worldTransform
    var min = Position(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    var max = Position(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
    for (sx in intArrayOf(-1, 1)) {
        for (sy in intArrayOf(-1, 1)) {
            for (sz in intArrayOf(-1, 1)) {
                val corner = Position(
                    localCenter.x + sx * localHalf.x,
                    localCenter.y + sy * localHalf.y,
                    localCenter.z + sz * localHalf.z
                )
                val world = nodeToRelativeTo * corner
                min = Position(
                    minOf(min.x, world.x),
                    minOf(min.y, world.y),
                    minOf(min.z, world.z)
                )
                max = Position(
                    maxOf(max.x, world.x),
                    maxOf(max.y, world.y),
                    maxOf(max.z, world.z)
                )
            }
        }
    }
    out += Aabb.fromMinMax(min, max)
}

/**
 * Union-diagonal-stability gate shared by the library-level auto-centre ([SceneAutoCenterState])
 * and auto-fit ([SceneAutoFitState]) passes.
 *
 * This is the Android port of the web `AutoCenterGate` (#1391 / #1540) and the iOS
 * `lastFramedDiagonal` + `framingStabilityEpsilon` logic.
 *
 * ## Why the old first-frame latch was wrong (#1596 / #1540)
 *
 * The previous design latched `didCenter` / `didFit` on the **first** render frame with
 * non-degenerate bounds and relied on an explicit [reset] at content-change time. That works for
 * models loaded all at once, but an async model that finishes loading *after* a sibling has
 * already framed never re-frames — by the time its bounds populate the latch is already set. The
 * result is the multi-model "bunched-in-the-corner" regression #1391 fixed on the other platforms.
 *
 * ## The fix
 *
 * Every frame the pass measures the content's union AABB diagonal and calls [shouldFrame]: it
 * re-frames whenever the diagonal moved by more than [STABILITY_EPSILON] (relative) since the last
 * framed diagonal — a freshly streamed model always grows the union materially, so it always
 * re-frames. [recordFraming] latches [latched] only once a frame sees the diagonal stable versus
 * the previous framed diagonal, so deferred async models keep the pass alive until the whole scene
 * has settled.
 *
 * ## Why an unconditional ceiling is also needed (Tier-2 #1596 review)
 *
 * The diagonal-stability latch alone is unbounded: a scene whose union diagonal jitters by *more*
 * than [STABILITY_EPSILON] every single frame — an animated / skeletal model, a physics demo, or
 * two async models alternately growing the union — satisfies [shouldFrame] forever and *never*
 * latches. The pass would then re-apply the camera / centroid every frame indefinitely, fighting
 * user interaction and burning a `computeContentBounds` walk per frame. So [recordFraming] also
 * latches unconditionally once it has framed [MAX_FRAMING_PASSES] times: a scene that has not
 * settled by then is genuinely animated, and re-framing it every frame is wrong.
 *
 * Isolating the state machine from any Filament binding keeps it directly unit-testable in pure
 * JVM — see `FramingGateTest`.
 */
class FramingGate {

    companion object {
        /**
         * Fraction of the union diagonal below which a frame-to-frame change is treated as
         * sub-millimetre float jitter rather than a genuine content change. Tolerant enough to
         * ignore floating-point noise, tight enough that a freshly streamed model (which grows the
         * union materially) always re-frames. Mirrors web `AutoCenterGate.STABILITY_EPSILON` and
         * iOS `framingStabilityEpsilon`.
         */
        const val STABILITY_EPSILON: Float = 0.01f

        /**
         * Hard ceiling on how many times the gate will (re-)frame before latching unconditionally.
         * The diagonal-stability check handles a scene that genuinely settles; this ceiling caps
         * the pathological case where the union diagonal jitters above [STABILITY_EPSILON] every
         * frame (animated / skeletal models, physics demos, alternating async loads) and would
         * otherwise re-frame forever. Generous enough to absorb a handful of staggered async model
         * loads (the #1391 case the gate exists for), small enough that an animated scene stops
         * fighting the user almost immediately.
         */
        const val MAX_FRAMING_PASSES: Int = 10
    }

    /** `true` once the union diagonal has stabilised and the pass has latched. */
    var latched: Boolean = false
        private set

    /**
     * Union diagonal of the content the pass last framed, or `< 0` when no frame has run yet.
     * Compared against the current diagonal to decide whether a streamed model just landed.
     */
    private var lastFramedDiagonal: Float = -1f

    /**
     * How many times [recordFraming] has framed the content since the last [reset]. Once this
     * reaches [MAX_FRAMING_PASSES] the gate latches unconditionally so a perpetually-jittering
     * scene stops re-framing.
     */
    private var framingPasses: Int = 0

    /**
     * Whether the pass should run at all this frame. Returns `false` once the gate has [latched]
     * (so later frames are a cheap no-op) or while no content is loaded.
     *
     * @param hasContent whether any non-degenerate content bounds are present this frame.
     */
    fun shouldRun(hasContent: Boolean): Boolean = hasContent && !latched

    /**
     * Whether the pass should (re-)apply the framing for the given union [diagonal] this frame.
     *
     * A freshly streamed model grows the union, moving the diagonal by more than
     * [STABILITY_EPSILON] — that always re-frames. Once the diagonal is within tolerance of the
     * last framed diagonal the scene has settled, so the frame is skipped (the latch itself
     * happens in [recordFraming]).
     */
    fun shouldFrame(diagonal: Float): Boolean {
        if (lastFramedDiagonal < 0f) return true
        val delta = abs(diagonal - lastFramedDiagonal)
        return delta > STABILITY_EPSILON * max(diagonal, lastFramedDiagonal)
    }

    /**
     * Record that the pass framed the content at union [diagonal]. Latches [latched] once either:
     *
     * - this diagonal is stable versus the previously recorded one — i.e. the scene has settled
     *   across consecutive ticks and no more async models are growing the union; or
     * - the gate has now framed [MAX_FRAMING_PASSES] times — a hard ceiling that stops a scene
     *   whose diagonal jitters above [STABILITY_EPSILON] every frame (animated / skeletal models,
     *   physics demos) from re-framing forever and fighting user interaction.
     *
     * Until one of those triggers the pass keeps running so deferred async models re-frame the
     * scene.
     */
    fun recordFraming(diagonal: Float) {
        val stable = lastFramedDiagonal >= 0f &&
            abs(diagonal - lastFramedDiagonal) <=
            STABILITY_EPSILON * max(diagonal, lastFramedDiagonal)
        lastFramedDiagonal = diagonal
        framingPasses++
        if (stable || framingPasses >= MAX_FRAMING_PASSES) latched = true
    }

    /**
     * Start a fresh content generation — clears the latch, the recorded diagonal and the framing
     * pass counter so the next frame re-frames from scratch.
     *
     * With the diagonal-stability logic the pass already re-frames automatically whenever an async
     * model grows the union, so an explicit `reset()` is no longer required for the deferred-async
     * case. It is still called when content is *replaced* so a shrinking union (which would
     * otherwise look stable) re-frames.
     */
    fun reset() {
        latched = false
        lastFramedDiagonal = -1f
        framingPasses = 0
    }
}

/**
 * Mutable holder for the library-level auto-centring pass. Lives in a Compose `remember` so the
 * gate state survives recomposition. Mirrors the iOS `autoCenterContent` framing state.
 *
 * Backed by a [FramingGate]: the pass re-runs whenever an async model grows the content's union
 * bounds and only latches once the union diagonal has settled — so a model that finishes loading
 * after a sibling already centred still triggers a re-centre (#1596 / #1540).
 */
class SceneAutoCenterState {
    private val gate = FramingGate()

    /** `true` once the union diagonal has settled and the centring pass has latched. */
    val didCenter: Boolean get() = gate.latched

    /**
     * Runs the centring pass against [contentRoot]. No-op once the gate has latched on a settled
     * union, or while the content bounds are still empty / degenerate (async loads not finished).
     * On a frame where the union diagonal materially changed, the content root is translated so
     * the content centroid lands at its parent origin; the gate latches once that diagonal settles
     * across consecutive frames.
     *
     * **Threading:** must run on the main thread — it reads and writes Filament transforms.
     *
     * @return `true` if this call performed the centring translation.
     */
    fun maybeCenter(contentRoot: Node): Boolean {
        if (!gate.shouldRun(hasContent = true)) return false
        // Bounds in content-root-local space so they are invariant of any rotation / scale the
        // camera manipulator applies to the scene each frame.
        val bounds = computeContentBounds(contentRoot, relativeTo = contentRoot)
        if (bounds.isEmpty) return false
        val maxExtent = maxOf(bounds.extents.x, bounds.extents.y, bounds.extents.z)
        if (!maxExtent.isFinite() || maxExtent <= AUTO_CENTER_MIN_VISUAL_EXTENT) return false
        val diagonal = bounds.diagonal
        val framed = gate.shouldFrame(diagonal)
        if (framed) contentRoot.position = -bounds.center
        gate.recordFraming(diagonal)
        return framed
    }

    /**
     * Resets the gate so the next frame re-runs the centring pass from scratch. Call this when the
     * scene content is *replaced* so a shrinking union still re-centres — a growing union already
     * re-frames automatically via the diagonal-stability gate.
     */
    fun reset() {
        gate.reset()
    }
}
