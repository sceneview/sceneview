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
import io.github.sceneview.math.quaternion
import io.github.sceneview.math.times
import io.github.sceneview.math.toMatrix
import io.github.sceneview.math.toQuaternion
import io.github.sceneview.safeDestroyEntity
import io.github.sceneview.safeDestroyTransformable

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
open class Node(
    val engine: Engine,
    @FilamentEntity val entity: Entity = EntityManager.get().create(),
) : GestureDetector.OnGestureListener,
    OnDoubleTapListener,
    OnContextClickListener,
    MoveGestureDetector.OnMoveListener,
    RotateGestureDetector.OnRotateListener,
    ScaleGestureDetector.OnScaleListener,
    TransformProvider {

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
    // Fix: cache `_position`, `_quaternion`, `_scale` as pristine Kotlin values. `transform.set`
    // decomposes once on write and updates all three. Individual getters read the caches; individual
    // setters compose from the caches (no round-trip through the Filament matrix).
    //
    // Invariant: whenever `transform.set` fires the caches are synchronised. This covers every
    // write path — direct `transform = …`, `position = …`, `quaternion = …`, `scale = …`,
    // `worldTransform = …`, smooth-animation ticks, and parenting reparents.
    private var _position: Position = Position()
    private var _quaternion: Quaternion = Quaternion()
    private var _scale: Scale = Scale(1.0f)

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
        _worldRotation = _worldQuaternion.toEulerAngles()
        return world
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
            transform = Transform(_position, _quaternion, _scale)
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
            transform = Transform(_position, _quaternion, _scale)
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
            transform = Transform(_position, _quaternion, _scale)
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
        get() = transformManager.getTransform(transformInstance)
        set(value) {
            transformManager.setTransform(transformInstance, value)
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

    var parentEntity: Entity?
        get() = transformManager.getParentOrNull(transformInstance)
        set(value) {
            if (parentEntity != value) {
                parentInstance = value?.let { transformManager.getInstance(it) }
            }
        }

    var parentInstance: EntityInstance?
        get() = parentEntity?.let { transformManager.getInstance(it) }
        set(value) {
            if (parentInstance != value) {
                transformManager.setParent(transformInstance, value ?: 0)
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
     */
    private var _transformInstance: EntityInstance = 0
    val transformInstance: EntityInstance
        get() {
            if (_transformInstance == 0) {
                _transformInstance = transformManager.getInstance(entity)
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
     * Converts a position in the world-space to a local-space of this node.
     *
     * @param worldPosition the position in world-space to convert.
     * @return a new position that represents the world position in local-space.
     */
    fun getLocalPosition(worldPosition: Position) = worldToLocal * worldPosition

    /**
     * Converts a position in the local-space of this node to world-space.
     *
     * @param localPosition the position in local-space to convert.
     * @return a new position that represents the local position in world-space.
     */
    fun getWorldPosition(localPosition: Position) = worldTransform * localPosition

    /**
     * Converts a quaternion in the world-space to a local-space of this node.
     *
     * @param worldQuaternion the quaternion in world-space to convert.
     * @return a new quaternion that represents the world quaternion in local-space.
     */
    fun getLocalQuaternion(worldQuaternion: Quaternion) =
        worldToLocal.toQuaternion() * worldQuaternion

    /**
     * Converts a quaternion in the local-space of this node to world-space.
     *
     * @param quaternion the quaternion in local-space to convert.
     * @return a new quaternion that represents the local quaternion in world-space.
     */
    fun getWorldQuaternion(quaternion: Quaternion) = worldTransform.toQuaternion() * quaternion

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

    fun getLocalScale(worldScale: Scale) = worldToLocal * worldScale
    fun getWorldScale(scale: Scale) = worldTransform * scale

    fun getLocalTransform(node: Node) = getLocalTransform(node.worldTransform)
    fun getLocalTransform(worldTransform: Transform) = worldToLocal * worldTransform
    fun getWorldTransform(node: Node) = getWorldTransform(node.transform)
    fun getWorldTransform(localTransform: Transform) = worldTransform * localTransform

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
     */
    open fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        // Snapshot the children before iterating: each child's destroy() mutates
        // childNodes (via `parent = null`), so iterating the live set would throw
        // ConcurrentModificationException.
        childNodes.toList().forEach { it.destroy() }
        runCatching { parent = null }
        engine.safeDestroyTransformable(entity)
        engine.safeDestroyEntity(entity)
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
