package io.github.sceneview.node

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.GestureDetector
import android.view.GestureDetector.OnContextClickListener
import android.view.GestureDetector.OnDoubleTapListener
import android.view.MotionEvent
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Scene
import com.google.android.filament.TransformManager
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.inverse
import dev.romainguy.kotlin.math.lookAt
import dev.romainguy.kotlin.math.lookTowards
import io.github.sceneview.Entity
import io.github.sceneview.EntityInstance
import io.github.sceneview.FilamentEntity
import io.github.sceneview.animation.NodeAnimator
import io.github.sceneview.collision.Collider
import io.github.sceneview.collision.CollisionShape
import io.github.sceneview.collision.CollisionSystem
import io.github.sceneview.collision.HitResult
import io.github.sceneview.collision.Matrix
import io.github.sceneview.collision.TransformProvider
import io.github.sceneview.gesture.MoveGestureDetector
import io.github.sceneview.gesture.RotateGestureDetector
import io.github.sceneview.gesture.ScaleGestureDetector
import io.github.sceneview.managers.getParentOrNull
import io.github.sceneview.managers.getTransform
import io.github.sceneview.managers.getWorldTransform
import io.github.sceneview.managers.setTransform
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Transform
import io.github.sceneview.math.localToWorldQuaternion
import io.github.sceneview.math.quaternion
import io.github.sceneview.math.times
import io.github.sceneview.math.toMatrix
import io.github.sceneview.math.toQuaternion
import io.github.sceneview.math.worldToLocalQuaternion
import io.github.sceneview.NULL_ENTITY
import io.github.sceneview.safeDestroyEntity
import io.github.sceneview.safeDestroyTransformable
import io.github.sceneview.transformGeneration
import io.github.sceneview.safeRecycleEntity

/**
 * A Node represents a transformation within the scene graph's hierarchy.
 *
 * It can contain a renderable for the rendering engine to render.
 *
 * Each node can have an arbitrary number of child nodes and one parent. The parent may be
 * another node, or the scene.
 *
 * Gesture handling is delegated to [gestureDelegate] and smooth animation to
 * [animationDelegate]. The node itself retains transform management, parent/child
 * relationships, collision, visibility and scene lifecycle.
 *
 * ------- +y ----- -z
 *
 * ---------|----/----
 *
 * ---------|--/------
 *
 * -x - - - 0 - - - +x
 *
 * ------/--|---------
 *
 * ----/----|---------
 *
 * +z ---- -y --------
 */
open class Node protected constructor(
    val engine: Engine,
    /**
     * The Filament entity this node drives — already resolved, never the [NULL_ENTITY]
     * sentinel.
     *
     * Declared on the **primary** constructor on purpose: Kotlin resolves a bare `entity` in
     * an `init` block or a property initializer to the constructor *parameter*, so a
     * same-named property declared in the body would silently shadow it and every initializer
     * below would read the sentinel instead of the real id (it did: `transformManager.create`
     * ran on entity 0, and the #2762 canary caught it).
     */
    @FilamentEntity val entity: Entity,
    /**
     * Whether [destroy] returns [entity]'s id to the [EntityManager].
     *
     * `true` only when this node allocated the entity itself. An entity handed in by the
     * caller is **borrowed**: [ModelNode] wraps `modelInstance.root` and its children wrap
     * `gltfio` node entities, all owned by the `AssetLoader`, which destroys them with the
     * asset. Recycling those ids here would let Filament reissue them while the asset is
     * still alive — a use-after-free that surfaces as one node silently driving another's
     * transform.
     *
     * Mirrors the ownership flags this codebase already uses for the same reason
     * (`RenderableNode.destroyMaterialsOnDispose`, `MeshNode.destroyBuffersOnDispose`).
     * Subclasses that allocate an entity through some other route can pass `true` here.
     */
    private val ownsEntity: Boolean,
) : GestureDetector.OnGestureListener,
    OnDoubleTapListener,
    OnContextClickListener,
    MoveGestureDetector.OnMoveListener,
    RotateGestureDetector.OnRotateListener,
    ScaleGestureDetector.OnScaleListener,
    TransformProvider {

    /**
     * @param entity the Filament entity to drive. Leave it out (the default) to have the node
     * allocate — and, on [destroy], recycle — its own entity. Pass one to **borrow** an entity
     * someone else owns: the node then drives its components but never returns the id to the
     * [EntityManager] (#2859).
     */
    constructor(engine: Engine, @FilamentEntity entity: Entity = NULL_ENTITY) : this(
        engine = engine,
        entity = if (entity == NULL_ENTITY) EntityManager.get().create() else entity,
        ownsEntity = entity == NULL_ENTITY,
    )

    /**
     * The Filament scene this node's entities are currently registered in, or `null`.
     *
     * Tracked by [io.github.sceneview.SceneNodeManager] so [destroy] can un-register the
     * entities before recycling their ids. The composable path already detaches before
     * destroying (`SceneScope.detach` → `node.destroy()`), but an imperative caller may not:
     * an id left in a `Scene` and then reissued would make the next renderable built on it
     * appear in that scene without ever having been added to it.
     */
    internal var attachedScene: Scene? = null

    // ---- Delegates ----

    /** Handles all gesture detection and callback logic. */
    val gestureDelegate = NodeGestureDelegate(this)

    /** Handles smooth transform interpolation. */
    val animationDelegate = NodeAnimationDelegate(this)

    // ---- Identity & flags ----

    var isHittable: Boolean = true

    /** Define your own custom name. */
    open var name: String? = null

    /**
     * The node can be selected when a touch event happened.
     *
     * If a not touchable child [Node] is touched, we check the parent hierarchy to find the
     * closest touchable parent. In this case, the first selectable parent will be the one to have
     * its [isTouchable] value to `true`.
     */
    open var isTouchable: Boolean = true
    open var isEditable: Boolean = false
    open var isPositionEditable: Boolean = false
        get() = isEditable && field
    open var isRotationEditable: Boolean = true
        get() = isEditable && field
    open var isScaleEditable: Boolean = true
        get() = isEditable && field

    var editableScaleRange = 0.1f..10.0f

    /**
     * Sensitivity multiplier applied to pinch-to-scale gestures.
     *
     * `1.0` passes the raw detector factor directly; values below `1.0` make scaling more
     * progressive by reducing the delta on each event. `0.5` (default) halves the per-frame
     * delta, giving a noticeably smoother and more controlled feel.
     */
    var scaleGestureSensitivity: Float = 0.5f

    /**
     * The visible state of this node.
     *
     * Note that a Node may be visible but still not rendered if its parent is not visible or if it
     * isn't part of the scene.
     */
    open var isVisible = true
        get() = field && parent?.isVisible != false
        set(value) {
            if (field != value) {
                field = value
                updateVisibility()
            }
        }

    // ---- Smooth animation aliases (delegated) ----

    var isSmoothTransformEnabled
        get() = animationDelegate.isSmoothTransformEnabled
        set(value) { animationDelegate.isSmoothTransformEnabled = value }

    /**
     * The smooth position, rotation and scale speed.
     *
     * This value is used by [smoothTransform]
     */
    var smoothTransformSpeed
        get() = animationDelegate.smoothTransformSpeed
        set(value) { animationDelegate.smoothTransformSpeed = value }

    var smoothTransform: Transform?
        get() = animationDelegate.smoothTransform
        set(value) { animationDelegate.smoothTransform = value }

    // ---- Transform ----

    // Pristine backing fields for the local TRS components (#2187).
    //
    // The previous design read `position`, `quaternion`, and `scale` from the Filament
    // TransformManager 4×4 matrix on every individual-property getter call. Each getter
    // decomposed the matrix (scale = column vector lengths, quaternion = polar decomposition).
    // When a caller updated a single axis at 60–120 Hz, the setter re-read the other two
    // components from the matrix to compose a new Transform — feeding float imprecision back in
    // on every tick. After ~10 000 frames the scale drifted by ~1e-4 per axis and the mesh
    // visibly warped.
    //
    // Fix: cache `_position`, `_quaternion`, `_scale` as pristine Kotlin values. Individual getters
    // read the caches directly. Individual setters update the one cache they own and push the
    // composed matrix to Filament via `applyCachedTransform()` WITHOUT reading it back — they never
    // round-trip through matrix decomposition (#2335). Only the public `transform` setter, whose
    // input is an arbitrary external matrix, decomposes (once) to re-seed all three caches.
    //
    // #2187/#2217 fixed the getters; #2335 closed the remaining drift on the per-component setter
    // path, which still re-decomposed because it routed through the `transform` setter.
    //
    // Invariant: every write path keeps the caches synchronised with the Filament matrix — direct
    // `transform = …` (decomposes), `position = …` / `quaternion = …` / `scale = …` /
    // `rotation = …` (compose-only via `applyCachedTransform()`), `worldTransform = …`,
    // smooth-animation ticks, and parenting reparents.
    private var _position: Position = Position()
    private var _quaternion: Quaternion = Quaternion()
    private var _scale: Scale = Scale(1.0f)

    // Local-space transform cache (#2405). `null` whenever the cache is dirty (initially, until
    // the first read or write). The local matrix is read at 60–120 Hz — once per frame per
    // animated node (`NodeAnimationDelegate.onFrame` reads `node.transform`), plus every
    // collision/query read — and each read otherwise paid a `TransformManager.getTransform()`
    // JNI round-trip (which itself allocates a `FloatArray(16)` + a `Mat4`).
    //
    // The two — and ONLY two — writers of this node's local Filament matrix (the `transform`
    // setter and `applyCachedTransform()`) POPULATE this cache with the exact matrix they push,
    // so even mid-animation (write then read each frame) the read is served without JNI. Filament
    // round-trips the matrix unchanged, so the cached value is byte-identical to a `getTransform()`
    // read. A reparent does NOT change the local (parent-relative) matrix, so it deliberately
    // leaves this cache valid; only a LOCAL transform write refreshes it.
    private var _transform: Transform? = null

    // World-space TRS cache (#2264, completes the #2187 fix for world-space getters).
    //
    // `_worldTransform` is `null` whenever the cache is dirty (initially, after a
    // local transform write, or after `onWorldTransformChanged()` propagates from a
    // moving ancestor). On the next world-space read we re-fetch the matrix from
    // Filament once and decompose its TRS into the three pristine caches — so
    // subsequent reads of `worldPosition / worldQuaternion / worldScale / worldRotation`
    // never re-decompose the matrix or JNI back into TransformManager.
    private var _worldTransform: Transform? = null
    private var _worldPosition: Position = Position()
    private var _worldQuaternion: Quaternion = Quaternion()
    private var _worldScale: Scale = Scale(1.0f)
    private var _worldRotation: Rotation = Rotation()

    private fun refreshWorldCache(): Transform {
        val world = transformManager.getWorldTransform(transformInstance)
        _worldTransform = world
        _worldPosition = world.position
        _worldQuaternion = world.toQuaternion()
        _worldScale = world.scale
        // Extract Euler directly from the matrix (not via the quaternion) to stay
        // bit-equivalent to the pre-cache `worldTransform.rotation` behavior — the
        // matrix→quaternion→Euler path can pick a different branch near gimbal lock
        // (e.g. 179.9° vs -180.1°) and break callers that compare successive readings.
        _worldRotation = world.rotation
        return world
    }

    /**
     * Pushes the pristine [_position] / [_quaternion] / [_scale] caches to Filament without
     * re-decomposing them.
     *
     * A per-frame component setter ([position] / [quaternion] / [scale] / [rotation]) must NOT
     * route through the [transform] setter: that setter re-decomposes the composed matrix back into
     * TRS, and the column-length / polar decomposition drifts off the pristine values a little every
     * frame (local scale 1.0 → 1.000354 over 10 000 frames). The #2187/#2217 fix corrected the
     * getters, but the setter path still round-tripped through decomposition — this completes the
     * fix (#2335) by composing the matrix from the caches and pushing it straight to Filament, never
     * reading it back.
     */
    private fun applyCachedTransform() {
        val composed = Transform(_position, _quaternion, _scale)
        transformManager.setTransform(transformInstance, composed)
        // Populate the local-matrix cache with the exact matrix just pushed to Filament, so a
        // subsequent `transform` read is served without a `getTransform()` JNI round-trip (#2405).
        _transform = composed
        onTransformChanged()
    }

    /**
     * Position to locate within the coordinate system the parent.
     *
     * Default is `Position(x = 0.0f, y = 0.0f, z = 0.0f)`, indicating that the component is placed
     * at the origin of the parent component's coordinate system.
     *
     * **Horizontal (X):**
     * - left: x < 0.0f
     * - center horizontal: x = 0.0f
     * - right: x > 0.0f
     *
     * **Vertical (Y):**
     * - top: y > 0.0f
     * - center vertical : y = 0.0f
     * - bottom: y < 0.0f
     *
     * **Depth (Z):**
     * - forward: z < 0.0f
     * - origin/camera position: z = 0.0f
     * - backward: z > 0.0f
     *
     * ------- +y ----- -z
     *
     * ---------|----/----
     *
     * ---------|--/------
     *
     * -x - - - 0 - - - +x
     *
     * ------/--|---------
     *
     * ----/----|---------
     *
     * +z ---- -y --------
     *
     * @see transform
     */
    open var position: Position
        get() = _position
        set(value) {
            _position = value
            applyCachedTransform()
        }

    /**
     * World-space position.
     *
     * The world position of this component (i.e. relative to the scene root).
     * This is the composition of this component's local position with its parent's world position.
     *
     * @see worldTransform
     */
    open var worldPosition: Position
        get() {
            if (_worldTransform == null) refreshWorldCache()
            return _worldPosition
        }
        set(value) {
            position = parent?.getLocalPosition(value) ?: value
        }

    /**
     * Quaternion rotation.
     *
     * @see transform
     */
    open var quaternion: Quaternion
        get() = _quaternion
        set(value) {
            _quaternion = value
            applyCachedTransform()
        }

    /**
     * The world-space quaternion.
     *
     * The world quaternion of this component (i.e. relative to the scene root).
     * This is the composition of this component's local quaternion with its parent's world
     * quaternion.
     *
     * @see worldTransform
     */
    open var worldQuaternion: Quaternion
        get() {
            if (_worldTransform == null) refreshWorldCache()
            return _worldQuaternion
        }
        set(value) {
            quaternion = parent?.getLocalQuaternion(value) ?: value
        }

    /**
     * Orientation in Euler Angles Degrees per axis from `0.0f` to `360.0f`.
     *
     * The three-component rotation vector specifies the direction of the rotation axis in degrees.
     * Rotation is applied relative to the component's origin property.
     *
     * Default is `Rotation(x = 0.0f, y = 0.0f, z = 0.0f)`, specifying no rotation.
     *
     * Note that modifying the individual components of the returned rotation doesn't have any
     * effect.
     *
     * The getter derives the Euler angles from [quaternion] on every read and deliberately does
     * **not** cache the result. Euler `rotation` is not read by any per-frame render or animation
     * path (those read [quaternion] / [worldQuaternion] directly), so a cache would only add
     * invalidation cost to every — hot — [quaternion] write for no hot-loop benefit. The only
     * internal callers are one-shot (animator setup, debug inspection). See #2328 (N2).
     *
     * @see transform
     */
    open var rotation: Rotation
        get() = quaternion.toEulerAngles()
        set(value) {
            quaternion = Quaternion.fromEuler(value)
        }

    /**
     * World-space rotation.
     *
     * The world rotation of this component (i.e. relative to the scene root).
     * This is the composition of this component's local rotation with its parent's world rotation.
     *
     * @see worldTransform
     */
    open var worldRotation: Rotation
        get() {
            if (_worldTransform == null) refreshWorldCache()
            return _worldRotation
        }
        set(value) {
            worldQuaternion = Quaternion.fromEuler(value)
        }

    /**
     * Scale on each axis.
     *
     * Reduce (`scale < 1.0f`) / Increase (`scale > 1.0f`).
     *
     * @see transform
     */
    open var scale: Scale
        get() = _scale
        set(value) {
            _scale = value
            applyCachedTransform()
        }

    /**
     * World-space scale.
     *
     * The world scale of this component (i.e. relative to the scene root).
     * This is the composition of this component's local scale with its parent's world scale.
     *
     * @see worldTransform
     */
    open var worldScale: Scale
        get() {
            if (_worldTransform == null) refreshWorldCache()
            return _worldScale
        }
        set(value) {
            scale = parent?.getLocalScale(value) ?: value
        }

    /**
     * Local transform of the transform component (i.e. relative to the parent).
     *
     * Setting this property always decomposes the matrix once to update the pristine
     * [_position] / [_quaternion] / [_scale] caches (#2187), so subsequent reads of the
     * individual properties never re-decompose the Filament 4×4 matrix.
     *
     * @see TransformManager.getTransform
     * @see TransformManager.setTransform
     */
    open var transform: Transform
        get() = _transform ?: transformManager.getTransform(transformInstance).also { _transform = it }
        set(value) {
            transformManager.setTransform(transformInstance, value)
            // Populate the local-matrix cache (#2405): Filament round-trips the matrix unchanged, so
            // the cached `value` is byte-identical to a subsequent `getTransform()` read. This keeps
            // the per-frame `node.transform` read free even while a smooth animation writes every tick.
            _transform = value
            // Synchronise the TRS caches from the new matrix so that any subsequent
            // getter for `position`, `quaternion`, or `scale` reads the pristine value
            // rather than re-decomposing the matrix (#2187).
            _position = value.position
            _quaternion = value.quaternion
            _scale = value.scale
            onTransformChanged()
        }

    /**
     * World transform of a transform component (i.e. relative to the root).
     *
     * @see TransformManager.getWorldTransform
     */
    var worldTransform: Transform
        get() = _worldTransform ?: refreshWorldCache()
        set(value) {
            transform = parent?.getLocalTransform(value) ?: value
        }

    // ---- Parent / children ----

    // Parent-entity (#2403) / parent-instance (#2404) caches. A validity FLAG is required — not a
    // null sentinel — because `null` is a legitimate cached value (a detached / root node has no
    // parent). Without the cache, `parentEntity` paid a `getParentOrNull()` JNI round-trip and
    // `parentInstance` paid that PLUS a `getInstance()` on every read. The single Filament write
    // path for the parent is the `parentInstance` setter (`setParent`); it invalidates both caches,
    // so the first read after a reparent re-fetches the fresh value once and every read after that
    // is served without JNI.
    //
    // `_parentEntity` is an `Entity` id, stable across TransformManager reindexing, so a reparent
    // is the only thing that invalidates it. `_parentInstance` is an `EntityInstance` handle from
    // the SAME packed array as `transformInstance` above, and it feeds a WRITE path
    // (`transformManager.setParent(transformInstance, value ?: 0)`) — a stale handle here doesn't
    // just misread a transform, it can reparent the wrong entity. It needs the same generation
    // check as `transformInstance` (#2978 review gap 1).
    private var _parentEntityValid = false
    private var _parentEntity: Entity? = null
    private var _parentInstanceValid = false
    private var _parentInstance: EntityInstance? = null
    private var _parentInstanceGeneration = -1

    var parentEntity: Entity?
        get() {
            if (!_parentEntityValid) {
                _parentEntity = transformManager.getParentOrNull(transformInstance)
                _parentEntityValid = true
            }
            return _parentEntity
        }
        set(value) {
            if (parentEntity != value) {
                parentInstance = value?.let { transformManager.getInstance(it) }
            }
        }

    var parentInstance: EntityInstance?
        get() {
            val currentGeneration = engine.transformGeneration()
            if (!_parentInstanceValid || _parentInstanceGeneration != currentGeneration) {
                _parentInstance = parentEntity?.let { transformManager.getInstance(it) }
                _parentInstanceValid = true
                _parentInstanceGeneration = currentGeneration
            }
            return _parentInstance
        }
        set(value) {
            if (parentInstance != value) {
                transformManager.setParent(transformInstance, value ?: 0)
                // The reparent changed both parent caches; invalidate so the next read re-fetches
                // the fresh entity/instance from Filament (#2403 / #2404).
                _parentEntityValid = false
                _parentInstanceValid = false
                // Reparenting changes this node's (and its descendants') world transform
                // even though `transform` (local) is unchanged. Invalidate the world-space
                // cache so subsequent reads re-fetch from Filament (#2264).
                onWorldTransformChanged()
            }
        }

    /**
     * Changes the parent node.
     *
     * If set to null, this node will be detached.
     *
     * The local position, rotation, and scale of this node will remain the same.
     * Therefore, the world position, rotation, and scale of this node may be different after the
     * parent changes.
     *
     * In addition to setting this field, it will also do the following things:
     * - Remove this node from its previous parent's children.
     * - Add this node to its new parent's children.
     * - Recursively update the node's transformation to reflect the change in parent.
     * - Recursively update the scene field to match the new parent's scene field.
     */
    open var parent: Node? = null
        set(value) {
            if (field != value) {
                val oldParent = field
                field = value
                oldParent?.let { it.childNodes = it.childNodes - this }
                value?.let { it.childNodes = it.childNodes + this }
                parentEntity = value?.entity
            }
        }

    var childNodes = setOf<Node>()
        set(value) {
            if (field != value) {
                val removedNodes = field - value
                val addedNodes = value - field
                field = value
                removedNodes.forEach { child ->
                    if (child.parent == this@Node) {
                        child.parent = null
                    }
                    onChildRemoved.forEach { it(child) }
                }
                addedNodes.forEach { child ->
                    if (child.parent != this@Node) {
                        child.parent = this@Node
                    }
                    onChildAdded.forEach { it(child) }
                }
                onTransformChanged()
            }
        }

    // ---- Collision ----

    var collisionSystem: CollisionSystem? = null
        set(value) {
            if (field != value) {
                field = value
                collider?.setAttachedCollisionSystem(value)
            }
        }

    var collider: Collider? = null
        set(value) {
            if (field != value) {
                field?.let { collisionSystem?.removeCollider(it) }
                field = value
                value?.let { collisionSystem?.addCollider(it) }
            }
        }

    /**
     * The shape to used to detect collisions for this [Node].
     *
     * If the shape is not set and renderable is set, then [Collider.setShape] is used to detect
     * collisions for this [Node].
     *
     * [CollisionShape] represents a geometric shape, i.e. sphere, box, convex hull.
     * If null, this node's current collision shape will be removed.
     */
    var collisionShape: CollisionShape? = null
        get() = collider?.getShape()
        set(value) {
            field = value
            if (value != null) {
                val collider = collider ?: Collider(
                    this
                ).also { collider = it }
                collider.setShape(value)
            } else {
                collider = null
            }
            // Refresh the collider to ensure it is using the correct collision shape now
            // that the renderable has changed.
            onTransformChanged()
        }

    // ---- Gesture callback aliases (backward compatibility) ----
    // These delegate to gestureDelegate so existing code like `node.onTouch = { ... }` still works.

    var onTouch
        get() = gestureDelegate.onTouch
        set(value) { gestureDelegate.onTouch = value }
    var onDown
        get() = gestureDelegate.onDown
        set(value) { gestureDelegate.onDown = value }
    var onShowPress
        get() = gestureDelegate.onShowPress
        set(value) { gestureDelegate.onShowPress = value }
    var onSingleTapUp
        get() = gestureDelegate.onSingleTapUp
        set(value) { gestureDelegate.onSingleTapUp = value }
    var onScroll
        get() = gestureDelegate.onScroll
        set(value) { gestureDelegate.onScroll = value }
    var onLongPress
        get() = gestureDelegate.onLongPress
        set(value) { gestureDelegate.onLongPress = value }
    var onFling
        get() = gestureDelegate.onFling
        set(value) { gestureDelegate.onFling = value }
    var onSingleTapConfirmed
        get() = gestureDelegate.onSingleTapConfirmed
        set(value) { gestureDelegate.onSingleTapConfirmed = value }
    var onDoubleTap
        get() = gestureDelegate.onDoubleTap
        set(value) { gestureDelegate.onDoubleTap = value }
    var onDoubleTapEvent
        get() = gestureDelegate.onDoubleTapEvent
        set(value) { gestureDelegate.onDoubleTapEvent = value }
    var onContextClick
        get() = gestureDelegate.onContextClick
        set(value) { gestureDelegate.onContextClick = value }
    var onMoveBegin
        get() = gestureDelegate.onMoveBegin
        set(value) { gestureDelegate.onMoveBegin = value }
    var onMove
        get() = gestureDelegate.onMove
        set(value) { gestureDelegate.onMove = value }
    var onMoveEnd
        get() = gestureDelegate.onMoveEnd
        set(value) { gestureDelegate.onMoveEnd = value }
    var onRotateBegin
        get() = gestureDelegate.onRotateBegin
        set(value) { gestureDelegate.onRotateBegin = value }
    var onRotate
        get() = gestureDelegate.onRotate
        set(value) { gestureDelegate.onRotate = value }
    var onRotateEnd
        get() = gestureDelegate.onRotateEnd
        set(value) { gestureDelegate.onRotateEnd = value }
    var onScaleBegin
        get() = gestureDelegate.onScaleBegin
        set(value) { gestureDelegate.onScaleBegin = value }
    var onScale
        get() = gestureDelegate.onScale
        set(value) { gestureDelegate.onScale = value }
    var onScaleEnd
        get() = gestureDelegate.onScaleEnd
        set(value) { gestureDelegate.onScaleEnd = value }
    var onEditingChanged
        get() = gestureDelegate.onEditingChanged
        set(value) { gestureDelegate.onEditingChanged = value }
    var editingTransforms
        get() = gestureDelegate.editingTransforms
        set(value) { gestureDelegate.editingTransforms = value }

    var onSmoothEnd
        get() = animationDelegate.onSmoothEnd
        set(value) { animationDelegate.onSmoothEnd = value }

    // ---- Scene lifecycle callbacks ----

    var onFrame: ((frameTimeNanos: Long) -> Unit)? = null
    var onAddedToScene: ((scene: Scene) -> Unit)? = null
    var onRemovedFromScene: ((scene: Scene) -> Unit)? = null

    // ---- Derived transforms ----

    /** Transform from the world coordinate system to the coordinate system of this node. */
    val worldToLocal: Transform get() = inverse(worldTransform)

    val transformManager get() = engine.transformManager

    /**
     * Cached [TransformManager] instance handle for this entity.
     *
     * `0` means "not yet looked up". The handle is stable for the lifetime of the
     * entity in the [TransformManager], so we only pay the JNI thunk once instead of
     * on every transform getter/setter — read by every `transform` / `worldTransform`
     * / `worldPosition` / smooth-animation tick at 60–120 Hz (#2269).
     *
     * That stability assumption only holds *between* other entities' transform-component
     * destructions: [TransformManager] is a packed-array store that compacts on removal by
     * swapping the last live entity into the removed slot, which silently reindexes that one
     * other live entity's handle (creation only appends/copies in place and never reindexes
     * an existing entity). [Engine.transformGeneration] is bumped every time any transform
     * component is destroyed anywhere on this [engine] — including glTF asset teardown via
     * [io.github.sceneview.loaders.ModelLoader.destroyModel], which bypasses [destroy] entirely
     * — so comparing the snapshotted generation against the current one on every read detects a
     * stale handle in O(1) and forces a fresh, correct lookup.
     */
    private var _transformInstance: EntityInstance = 0
    private var _transformInstanceGeneration = -1
    val transformInstance: EntityInstance
        get() {
            val currentGeneration = engine.transformGeneration()
            if (_transformInstance == 0 || _transformInstanceGeneration != currentGeneration) {
                _transformInstance = transformManager.getInstance(entity)
                _transformInstanceGeneration = currentGeneration
            }
            return _transformInstance
        }

    internal open val sceneEntities = listOf(entity)
    internal val onChildAdded = mutableListOf<(child: Node) -> Unit>()
    internal val onChildRemoved = mutableListOf<(child: Node) -> Unit>()

    init {
        if (!transformManager.hasComponent(entity)) {
            transformManager.create(entity)
        }
    }

    // ---- Coordinate conversion ----

    /**
     * The world-to-local matrix derived from this node's **live** Filament world transform.
     *
     * Unlike [worldToLocal] (which inverts the cached [worldTransform]), this forces a
     * [refreshWorldCache] first so a world→local conversion never trusts a stale cache.
     * Used by the public conversion helpers below — they back the world-space setters
     * ([worldPosition] / [worldScale] / [worldTransform] on a child), which must convert
     * against the parent's live world transform to round-trip (#2392). Same live-matrix
     * read 4.15.2 did; the cached [worldTransform] getter stays the hot-path reader.
     */
    private val freshWorldToLocal: Transform get() = inverse(refreshWorldCache())

    /**
     * Converts a position in the world-space to a local-space of this node.
     *
     * Reflects this node's **live** world transform (forces a [refreshWorldCache]) so the
     * world-space position setter round-trips even when the cache is stale (#2392).
     *
     * @param worldPosition the position in world-space to convert.
     * @return a new position that represents the world position in local-space.
     */
    fun getLocalPosition(worldPosition: Position) = freshWorldToLocal * worldPosition

    /**
     * Converts a position in the local-space of this node to world-space.
     *
     * @param localPosition the position in local-space to convert.
     * @return a new position that represents the local position in world-space.
     */
    fun getWorldPosition(localPosition: Position) = refreshWorldCache() * localPosition

    /**
     * Converts a quaternion in the world-space to a local-space of this node.
     *
     * When this node's world transform has **no scale**, the conversion is rotation-only:
     * it uses this node's world quaternion directly and skips the Mat4 polar
     * decomposition that `worldToLocal.toQuaternion()` paid on every call (#2267).
     *
     * When this node IS scaled the fast path is NOT used: `inverse(M).toQuaternion()`
     * (legacy) and `inverse(M.toQuaternion())` (fast) diverge once `M` carries scale
     * (verified divergence even for uniform scale — #2294 review), so the scaled case
     * falls back to the exact legacy matrix path to preserve behavior.
     *
     * This conversion is the parent-side of the world-space setters
     * ([worldQuaternion] / [worldRotation] on a child), so it MUST reflect this node's
     * **live** Filament world transform — not a possibly-stale cache. It therefore
     * forces a [refreshWorldCache] first: an ancestor (or an engine-driven write) can
     * change this node's world transform without going through the [Node] setters that
     * fire [onWorldTransformChanged], leaving `_worldQuaternion` decomposed from an
     * out-of-date matrix. Trusting that stale cache here broke `set worldQuaternion`
     * round-trips on parented nodes (#2392). Refreshing on this (write-time) path is
     * the same live-matrix read 4.15.2 did, and keeps the per-frame world-quaternion
     * *getter* — the actual 60–120 Hz hot path — fully cache-served.
     *
     * @param worldQuaternion the quaternion in world-space to convert.
     * @return a new quaternion that represents the world quaternion in local-space.
     */
    fun getLocalQuaternion(worldQuaternion: Quaternion): Quaternion {
        // Re-read the live world transform so the conversion never trusts a stale cache (#2392).
        val world = refreshWorldCache()
        return if (world.scale.isApproximatelyUnitScale()) {
            worldToLocalQuaternion(worldQuaternion = worldQuaternion, parentWorldQuaternion = _worldQuaternion)
        } else {
            inverse(world).toQuaternion() * worldQuaternion
        }
    }

    /**
     * Converts a quaternion in the local-space of this node to world-space.
     *
     * Uses this node's world quaternion directly — a rotation-only conversion never
     * needs the 4×4 matrix, so this skips the Mat4 polar decomposition that
     * `worldTransform.toQuaternion()` paid on every call (#2267).
     *
     * Like [getLocalQuaternion], this forces a [refreshWorldCache] first so the
     * conversion reflects this node's **live** Filament world transform rather than a
     * possibly-stale `_worldQuaternion` (#2392).
     *
     * @param quaternion the quaternion in local-space to convert.
     * @return a new quaternion that represents the local quaternion in world-space.
     */
    fun getWorldQuaternion(quaternion: Quaternion): Quaternion {
        // Re-read the live world transform so the conversion never trusts a stale cache (#2392).
        refreshWorldCache()
        return localToWorldQuaternion(localQuaternion = quaternion, parentWorldQuaternion = _worldQuaternion)
    }

    /**
     * Converts a rotation in the world-space to a local-space of this node.
     *
     * @param worldRotation the rotation in world-space to convert.
     * @return a new rotation that represents the world rotation in local-space.
     */
    fun getLocalRotation(worldRotation: Rotation) =
        getLocalQuaternion(Quaternion.fromEuler(worldRotation)).toEulerAngles()

    /**
     * Converts a rotation in the local-space of this node to world-space.
     *
     * @param rotation the rotation in local-space to convert.
     * @return a new rotation that represents the local rotation in world-space.
     */
    fun getWorldRotation(rotation: Rotation) =
        getWorldQuaternion(Quaternion.fromEuler(rotation)).toEulerAngles()

    /**
     * True when each axis of this scale is within a small epsilon of 1.0 — i.e. the
     * transform carries no meaningful scale and the rotation-only quaternion fast path in
     * [getLocalQuaternion] is exactly equivalent to the legacy matrix path (#2294 review).
     */
    private fun Scale.isApproximatelyUnitScale(): Boolean {
        val lo = 1f - 1e-4f
        val hi = 1f + 1e-4f
        return x in lo..hi && y in lo..hi && z in lo..hi
    }

    fun getLocalScale(worldScale: Scale) = freshWorldToLocal * worldScale
    fun getWorldScale(scale: Scale) = refreshWorldCache() * scale

    fun getLocalTransform(node: Node) = getLocalTransform(node.worldTransform)
    fun getLocalTransform(worldTransform: Transform) = freshWorldToLocal * worldTransform
    fun getWorldTransform(node: Node) = getWorldTransform(node.transform)
    fun getWorldTransform(localTransform: Transform) = refreshWorldCache() * localTransform

    // ---- Transform mutation ----

    /**
     * The node scale.
     *
     * - reduce size: scale < 1.0f
     * - same size: scale = 1.0f
     * - increase size: scale > 1.0f
     */
    fun setScale(scale: Float) {
        this.scale = Scale(scale)
    }

    /**
     * Change the node transform.
     */
    open fun transform(
        transform: Transform,
        smooth: Boolean = isSmoothTransformEnabled,
        smoothSpeed: Float = smoothTransformSpeed
    ) = apply {
        if (smooth) {
            this.smoothTransformSpeed = smoothSpeed
            this.smoothTransform = transform
        } else {
            this.smoothTransform = null
            this.transform = transform
        }
    }

    /**
     * Change the node transform.
     *
     * @see position
     * @see quaternion
     * @see scale
     */
    fun transform(
        position: Position = this.position,
        quaternion: Quaternion = this.quaternion,
        scale: Scale = this.scale,
        smooth: Boolean = isSmoothTransformEnabled,
        smoothSpeed: Float = smoothTransformSpeed
    ) = transform(Transform(position, quaternion, scale), smooth, smoothSpeed)

    /**
     * Change the node transform.
     *
     * @see position
     * @see rotation
     * @see scale
     */
    fun transform(
        position: Position = this.position,
        rotation: Rotation,
        scale: Scale = this.scale,
        smooth: Boolean = isSmoothTransformEnabled,
        smoothSpeed: Float = smoothTransformSpeed
    ) = transform(position, rotation.toQuaternion(), scale, smooth, smoothSpeed)

    /**
     * Change the node world transform.
     */
    open fun worldTransform(
        worldTransform: Transform,
        smooth: Boolean = isSmoothTransformEnabled,
        smoothSpeed: Float = smoothTransformSpeed
    ) = transform(parent?.getLocalTransform(worldTransform) ?: worldTransform, smooth, smoothSpeed)

    /**
     * Change the node world transform.
     *
     * @see position
     * @see quaternion
     * @see scale
     */
    fun worldTransform(
        position: Position = this.worldPosition,
        quaternion: Quaternion = this.worldQuaternion,
        scale: Scale = this.worldScale,
        smooth: Boolean = isSmoothTransformEnabled,
        smoothSpeed: Float = smoothTransformSpeed
    ) = worldTransform(Transform(position, quaternion, scale), smooth, smoothSpeed)

    /**
     * Change the node world transform.
     *
     * @see position
     * @see rotation
     * @see scale
     */
    fun worldTransform(
        position: Position = this.worldPosition,
        rotation: Rotation,
        scale: Scale = this.worldScale,
        smooth: Boolean = isSmoothTransformEnabled,
        smoothSpeed: Float = smoothTransformSpeed
    ) = worldTransform(Transform(position, rotation.toQuaternion(), scale), smooth, smoothSpeed)

    /**
     * Rotates the node to face another node.
     *
     * @param targetNode The target node to look at
     * @param upDirection The up direction will determine the orientation of the node around the direction
     * @param smooth Whether the rotation should happen smoothly
     */
    fun lookAt(
        targetNode: Node,
        upDirection: Direction = Direction(y = 1.0f),
        smooth: Boolean = isSmoothTransformEnabled,
        smoothSpeed: Float = smoothTransformSpeed
    ) = lookAt(
        targetWorldPosition = targetNode.worldPosition,
        upDirection = upDirection,
        smooth = smooth,
        smoothSpeed = smoothSpeed
    )

    /**
     * Rotates the node to face a point in world-space.
     *
     * @param targetWorldPosition The target position to look at in world space
     * @param upDirection The up direction will determine the orientation of the node around the direction
     * @param smooth Whether the rotation should happen smoothly
     */
    fun lookAt(
        targetWorldPosition: Position,
        upDirection: Direction = Direction(y = 1.0f),
        smooth: Boolean = isSmoothTransformEnabled,
        smoothSpeed: Float = smoothTransformSpeed
    ) = worldTransform(
        quaternion = lookAt(
            eye = worldPosition,
            target = targetWorldPosition,
            up = upDirection
        ).toQuaternion(),
        smooth = smooth,
        smoothSpeed = smoothSpeed
    )

    /**
     * Rotates the node to face a direction in world-space.
     *
     * The look direction and up direction cannot be coincident (parallel) or the orientation will
     * be invalid.
     *
     * @param lookDirection The desired look direction in world-space.
     * @param upDirection The up direction will determine the orientation of the node around the
     * look direction.
     * @param smooth Whether the rotation should happen smoothly.
     */
    fun lookTowards(
        lookDirection: Direction,
        upDirection: Direction = Direction(y = 1.0f),
        smooth: Boolean = isSmoothTransformEnabled,
        smoothSpeed: Float = smoothTransformSpeed
    ) = worldTransform(
        quaternion = lookTowards(
            eye = worldPosition,
            forward = lookDirection,
            up = upDirection
        ).toQuaternion(),
        smooth = smooth,
        smoothSpeed = smoothSpeed
    )

    // ---- Children management ----

    fun addChildNode(node: Node) = apply { childNodes += node }
    fun addChildNodes(nodes: Set<Node>) = apply { childNodes += nodes }
    fun removeChildNode(node: Node) = apply { childNodes -= node }
    fun removeChildNodes(nodes: Set<Node>) = apply { childNodes = childNodes - nodes }
    fun clearChildNodes() = apply { childNodes = setOf() }

    // ---- ObjectAnimator helpers ----

    fun animatePositions(vararg positions: Position): ObjectAnimator =
        NodeAnimator.ofPosition(this, *positions)

    fun animateQuaternions(vararg quaternions: Quaternion): ObjectAnimator =
        NodeAnimator.ofQuaternion(this, *quaternions)

    fun animateRotations(vararg rotations: Rotation): ObjectAnimator =
        NodeAnimator.ofRotation(this, *rotations)

    fun animateScales(vararg scales: Scale): ObjectAnimator =
        NodeAnimator.ofScale(this, *scales)

    fun animateTransforms(vararg transforms: Transform): AnimatorSet =
        NodeAnimator.ofTransform(this, *transforms)

    // ---- Collision tests ----

    /**
     * Tests to see if this node collision shape overlaps the collision shape of any other nodes in
     * the scene using [Node.collisionShape].
     *
     * @return A node that is overlapping the test node. If no node is overlapping the test node,
     * then this is null. If multiple nodes are overlapping the test node, then this could be any of
     * them.
     */
    fun overlapTest(): Node? {
        val cs = collisionSystem ?: return null
        val c = collider ?: return null
        return cs.intersects(c)?.node
    }

    /**
     * Tests to see if a node is overlapping any other nodes within the scene using
     * [Node.collisionShape].
     *
     * @return A list of all nodes that are overlapping this node. If no node is overlapping the
     * test node, then the list is empty.
     */
    fun overlapTestAll(): List<Node> {
        val cs = collisionSystem ?: return emptyList()
        val c = collider ?: return emptyList()
        return buildList {
            cs.intersectsAll(c) {
                add(it.node)
            }
        }
    }

    // ---- Per-frame lifecycle ----

    open fun onFrame(frameTimeNanos: Long) {
        // Smooth transform interpolation
        animationDelegate.onFrame(frameTimeNanos)

        // Propagate to children
        childNodes.forEach { it.onFrame(frameTimeNanos) }

        // User callback
        onFrame?.invoke(frameTimeNanos)
    }

    // ---- Transform change notifications ----

    /**
     * The transformation (position, rotation or scale) of the [Node] has changed.
     *
     * If node's position is changed, then that will trigger [onWorldTransformChanged] to be called
     * for all of it's descendants.
     */
    open fun onTransformChanged() {
        onWorldTransformChanged()
    }

    /**
     * The transformation (position, rotation or scale) of the [Node] has changed.
     *
     * If node's position is changed, then that will trigger [onWorldTransformChanged] to be called
     * for all of it's descendants.
     */
    open fun onWorldTransformChanged() {
        // Invalidate the world-space TRS cache (#2264). The next read of
        // worldTransform / worldPosition / worldQuaternion / worldScale /
        // worldRotation will re-fetch from Filament and refresh the caches.
        _worldTransform = null
        collider?.markWorldShapeDirty()
        childNodes.forEach { it.onWorldTransformChanged() }
    }

    // ---- Scene lifecycle ----

    open fun onAddedToScene(scene: Scene) {
        onAddedToScene?.invoke(scene)
    }

    open fun onRemovedFromScene(scene: Scene) {
        onRemovedFromScene?.invoke(scene)
    }

    // ---- Gesture interface implementations (delegate to gestureDelegate) ----

    open fun onTouchEvent(e: MotionEvent, hitResult: HitResult) =
        gestureDelegate.onTouchEvent(e, hitResult)

    override fun onDown(e: MotionEvent) = gestureDelegate.onDown(e)
    override fun onShowPress(e: MotionEvent) = gestureDelegate.onShowPress(e)
    override fun onSingleTapUp(e: MotionEvent) = gestureDelegate.onSingleTapUp(e)
    override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float
    ) = gestureDelegate.onScroll(e1, e2, distanceX, distanceY)

    override fun onLongPress(e: MotionEvent) = gestureDelegate.onLongPress(e)
    override fun onFling(
        e1: MotionEvent?,
        e2: MotionEvent,
        velocityX: Float,
        velocityY: Float
    ) = gestureDelegate.onFling(e1, e2, velocityX, velocityY)

    override fun onSingleTapConfirmed(e: MotionEvent) = gestureDelegate.onSingleTapConfirmed(e)
    override fun onDoubleTap(e: MotionEvent) = gestureDelegate.onDoubleTap(e)
    override fun onDoubleTapEvent(e: MotionEvent) = gestureDelegate.onDoubleTapEvent(e)
    override fun onContextClick(e: MotionEvent) = gestureDelegate.onContextClick(e)

    override fun onMoveBegin(detector: MoveGestureDetector, e: MotionEvent) =
        gestureDelegate.onMoveBegin(detector, e)

    override fun onMove(detector: MoveGestureDetector, e: MotionEvent) =
        gestureDelegate.onMove(detector, e)

    open fun onMove(
        detector: MoveGestureDetector,
        e: MotionEvent,
        worldPosition: Position
    ) = gestureDelegate.onMove(detector, e, worldPosition)

    override fun onMoveEnd(detector: MoveGestureDetector, e: MotionEvent) =
        gestureDelegate.onMoveEnd(detector, e)

    override fun onRotateBegin(detector: RotateGestureDetector, e: MotionEvent) =
        gestureDelegate.onRotateBegin(detector, e)

    override fun onRotate(detector: RotateGestureDetector, e: MotionEvent) =
        gestureDelegate.onRotate(detector, e)

    open fun onRotate(
        detector: RotateGestureDetector,
        e: MotionEvent,
        rotationDelta: Quaternion
    ) = gestureDelegate.onRotate(detector, e, rotationDelta)

    override fun onRotateEnd(detector: RotateGestureDetector, e: MotionEvent) =
        gestureDelegate.onRotateEnd(detector, e)

    override fun onScaleBegin(detector: ScaleGestureDetector, e: MotionEvent) =
        gestureDelegate.onScaleBegin(detector, e)

    override fun onScale(detector: ScaleGestureDetector, e: MotionEvent) =
        gestureDelegate.onScale(detector, e)

    open fun onScale(detector: ScaleGestureDetector, e: MotionEvent, scaleFactor: Float) =
        gestureDelegate.onScale(detector, e, scaleFactor)

    override fun onScaleEnd(detector: ScaleGestureDetector, e: MotionEvent) =
        gestureDelegate.onScaleEnd(detector, e)

    // ---- Visibility ----

    /**
     * Updates the children visibility.
     *
     * @see RenderableNode.updateVisibility
     */
    protected open fun updateVisibility() {
        childNodes.forEach { childNode ->
            childNode.updateVisibility()
        }
    }

    // ---- Collision bridge ----

    // Bridge for legacy collision system; returns world transform as a collision Matrix.
    override fun getTransformationMatrix(): Matrix {
        return worldTransform.toMatrix()
    }

    // ---- Destroy ----

    /** Guards [destroy] against re-entrancy when a node tree references itself. */
    private var isDestroyed = false

    /**
     * Detach and destroy the node and all its children.
     *
     * Every descendant's [destroy] is invoked first (post-order), so an entire
     * imperatively-built node tree is released by a single call on the root.
     * Compose-declared children are disposed independently by `rememberNode`;
     * destroying them again here is a safe no-op.
     *
     * Releases the entity's components, then returns its id to the [EntityManager] — but only
     * for a self-allocated entity, never a borrowed one (see the `ownsEntity` constructor
     * parameter and #2859).
     */
    open fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        // Snapshot the children before iterating: each child's destroy() mutates
        // childNodes (via `parent = null`), so iterating the live set would throw
        // ConcurrentModificationException.
        childNodes.toList().forEach { it.destroy() }
        runCatching { parent = null }
        // Un-register before the id is recycled: a Scene holding a reissued id would render
        // whatever renderable is built on it next (see [attachedScene]). No-op on the
        // composable path, where SceneScope.detach() already removed the node.
        attachedScene?.let { scene ->
            runCatching { scene.removeEntities(sceneEntities.toIntArray()) }
            attachedScene = null
        }
        // safeDestroyTransformable bumps the engine-wide transform generation counter (see
        // Engine.kt), so every other live Node's cached transformInstance/parentInstance
        // re-resolves on next read instead of silently operating on a stale/reassigned slot.
        engine.safeDestroyTransformable(entity)
        engine.safeDestroyEntity(entity)
        // Components are gone; the id itself is only ours to give back when we allocated it.
        if (ownsEntity) {
            engine.safeRecycleEntity(entity)
        }
    }
}

interface OnNodeGestureListener : GestureDetector.OnGestureListener,
    OnDoubleTapListener,
    OnContextClickListener,
    MoveGestureDetector.OnMoveListener,
    RotateGestureDetector.OnRotateListener,
    ScaleGestureDetector.OnScaleListener

open class SimpleOnNodeGestureListener : GestureDetector.SimpleOnGestureListener(),
    MoveGestureDetector.SimpleOnMoveListener,
    RotateGestureDetector.SimpleOnRotateListener,
    ScaleGestureDetector.SimpleOnScaleListener,
    OnNodeGestureListener
