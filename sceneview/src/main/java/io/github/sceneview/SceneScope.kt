// SceneScope is the base class for both 3D and AR scopes.
// The class hierarchy (SceneScope -> ARSceneScope -> NodeScope) is designed so that arsceneview
// extends sceneview without code duplication. Modules are kept separate intentionally:
// sceneview = lightweight 3D-only (no ARCore dependency), arsceneview = opt-in AR.

package io.github.sceneview

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaPlayer
import androidx.annotation.DrawableRes
import androidx.annotation.RestrictTo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.platform.LocalContext
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.LightManager
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene as FilamentScene
import com.google.android.filament.VertexBuffer
import io.github.sceneview.environment.Environment
import io.github.sceneview.geometries.Capsule
import io.github.sceneview.geometries.Cone
import io.github.sceneview.geometries.Cube
import io.github.sceneview.geometries.Cylinder
import io.github.sceneview.geometries.Plane
import io.github.sceneview.geometries.Sphere
import io.github.sceneview.geometries.Torus
import io.github.sceneview.geometries.UvScale
import io.github.sceneview.loaders.EnvironmentLoader
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.math.Color
import io.github.sceneview.math.Position2
import io.github.sceneview.node.BillboardNode as BillboardNodeImpl
import io.github.sceneview.node.CameraNode as CameraNodeImpl
import io.github.sceneview.node.CapsuleNode as CapsuleNodeImpl
import io.github.sceneview.node.ConeNode as ConeNodeImpl
import io.github.sceneview.node.CubeNode as CubeNodeImpl
import io.github.sceneview.node.CylinderNode as CylinderNodeImpl
import io.github.sceneview.node.ImageNode as ImageNodeImpl
import io.github.sceneview.node.LightNode as LightNodeImpl
import io.github.sceneview.node.LineNode as LineNodeImpl
import io.github.sceneview.node.MeshNode as MeshNodeImpl
import io.github.sceneview.node.ModelNode as ModelNodeImpl
import io.github.sceneview.node.Node as NodeImpl
import io.github.sceneview.node.PathNode as PathNodeImpl
import io.github.sceneview.node.PlaneNode as PlaneNodeImpl
import io.github.sceneview.node.PhysicsBody
import io.github.sceneview.node.ReflectionProbeNode as ReflectionProbeNodeComposable
import io.github.sceneview.node.ShapeNode as ShapeNodeImpl
import io.github.sceneview.node.SphereNode as SphereNodeImpl
import io.github.sceneview.node.TextNode as TextNodeImpl
import io.github.sceneview.node.TorusNode as TorusNodeImpl
import io.github.sceneview.node.VideoNode as VideoNodeImpl
import io.github.sceneview.node.ViewNode as ViewNodeImpl

/**
 * DSL marker annotation that prevents implicit access to outer [SceneScope] from inside a
 * [NodeScope], enforcing correct nesting.
 */
@DslMarker
annotation class SceneDsl

/**
 * The composable DSL scope for building 3D scenes inside [Scene].
 *
 * `SceneScope` is the receiver of `SceneView { }` content blocks. Every node type — models, lights,
 * geometry, images, Compose UI planes, custom meshes — is a `@Composable` function in this scope.
 * Nodes enter the Filament scene on first composition and are automatically destroyed when they
 * leave, with no manual lifecycle management.
 *
 * Build scenes the same way you build Compose UI: nest composables, react to state, use
 * `remember` and `LaunchedEffect`. The 3D scene graph mirrors the Compose tree.
 *
 * ```kotlin
 * SceneView(modifier = Modifier.fillMaxSize()) {
 *     // Async model — null while loading, node appears on recomposition when ready
 *     rememberModelInstance(modelLoader, "models/helmet.glb")?.let { instance ->
 *         ModelNode(modelInstance = instance, scaleToUnits = 0.5f)
 *     }
 *     // Nested nodes build a scene graph hierarchy
 *     Node(position = Position(y = 1.0f)) {
 *         CubeNode(size = Size(0.1f))
 *         SphereNode(radius = 0.05f)
 *     }
 *     LightNode(type = LightManager.Type.DIRECTIONAL)
 * }
 * ```
 *
 * **Naming convention:** Composable functions in this scope (e.g. `ModelNode`, `CubeNode`) share
 * their names with the underlying imperative node classes they wrap (e.g. `io.github.sceneview.node.ModelNode`).
 * This is intentional — the composable is the primary API surface and internally creates/manages
 * the imperative node instance. Import aliases (`ModelNodeImpl`, `CubeNodeImpl`, etc.) are used
 * internally to disambiguate.
 *
 * @param engine            The Filament [Engine] shared with the parent [Scene].
 * @param modelLoader       [ModelLoader] for loading glTF/GLB models.
 * @param materialLoader    [MaterialLoader] for creating material instances.
 * @param environmentLoader [EnvironmentLoader] for loading HDR/KTX environments.
 * @param _nodes            Internal SnapshotStateList backing the scene's root node list.
 */
@Suppress("FunctionName") // Composable functions follow PascalCase (Compose convention)
@SceneDsl
open class SceneScope @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX) constructor(
    val engine: Engine,
    val modelLoader: ModelLoader,
    val materialLoader: MaterialLoader,
    val environmentLoader: EnvironmentLoader,
    internal val _nodes: SnapshotStateList<NodeImpl>,
    // Called synchronously in detach() to remove the node from the Filament scene before
    // node.destroy() runs. This prevents the SIGABRT caused by destroying a MaterialInstance
    // while its Renderable entity is still registered in the scene.
    internal val nodeRemover: ((NodeImpl) -> Unit)? = null
) {

    // ── Attachment helpers ────────────────────────────────────────────────────────────────────────

    /**
     * Attach [node] to this scope's container. Overridden in [NodeScope] to attach to a parent.
     */
    internal open fun attach(node: NodeImpl) {
        _nodes.add(node)
    }

    /**
     * Detach [node] from this scope's container. Overridden in [NodeScope] to remove from parent.
     */
    internal open fun detach(node: NodeImpl) {
        // Remove from the Filament scene synchronously before node.destroy() is called.
        // For child nodes (NodeScope) this happens via parentNode.removeChildNode → onChildRemoved.
        // For root-level nodes the LaunchedEffect that watches scopeChildNodes is async, so we
        // must remove explicitly here to guarantee the entity leaves the scene first.
        nodeRemover?.invoke(node)
        _nodes.remove(node)
    }

    // ── Base Node ─────────────────────────────────────────────────────────────────────────────────

    /**
     * A base scene-graph node with no renderable geometry.
     *
     * Useful as a pivot/anchor point for grouping other nodes or for attaching a camera.
     *
     * @param position  Local position relative to the scene root (or parent node in [NodeScope]).
     * @param rotation  Local rotation in Euler angles (degrees).
     * @param scale     Local scale.
     * @param isVisible Whether the node (and all its children) should be rendered.
     * @param isEditable Whether the node can be interactively moved/rotated/scaled.
     * @param apply     Additional imperative configuration applied once on creation.
     * @param content   Optional child nodes declared in a [NodeScope].
     */
    @Composable
    fun Node(
        position: Position = Position(x = 0f),
        rotation: Rotation = Rotation(x = 0f),
        // `Scale(1f)` (positional) calls kotlin-math's single-arg Float3(v) which fills
        // all three components → (1, 1, 1). The named-arg form `Scale(x = 1f)` only sets x
        // and leaves y/z at 0 → (1, 0, 0), a singular transform that cascades NaN through
        // every subsequent matrix op (quaternion decomposition returns NaN, children
        // inherit zero volume, physics integration produces NaN positions). Use the
        // positional form. Every specialised node composable (SphereNode, CubeNode, …)
        // already does this — only the bare Node composable had the buggy default.
        scale: Scale = Scale(1f),
        isVisible: Boolean = true,
        isEditable: Boolean = false,
        apply: NodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val node = remember(engine) { NodeImpl(engine = engine).apply(apply) }
        // Push declarative props to the underlying node only when they actually change —
        // NOT on every successful recomposition. The previous `SideEffect { node.position =
        // position }` ran each frame and overwrote any position that a frame-loop driver
        // (PhysicsBody, animations, camera follow) had just written via `node.onFrame`,
        // so PhysicsDemo spheres never appeared to move.
        //
        // Keyed on the individual scalar components (not the Float3 wrapper) because
        // `Float3` from `dev.romainguy.kotlin.math` uses reference equality by default —
        // each recomposition that literally writes `Position(x = ...)` produces a *new*
        // instance that `==`-compares as unequal to the previous one, which would
        // re-trigger the effect and re-clobber driver state. Component-wise keys let
        // `DisposableEffect` recognise identical values across recompositions and stay
        // idle while a frame driver owns the node.
        DisposableEffect(node, position.x, position.y, position.z) {
            node.position = position; onDispose {}
        }
        DisposableEffect(node, rotation.x, rotation.y, rotation.z) {
            node.rotation = rotation; onDispose {}
        }
        DisposableEffect(node, scale.x, scale.y, scale.z) {
            node.scale = scale; onDispose {}
        }
        DisposableEffect(node, isVisible) { node.isVisible = isVisible; onDispose {} }
        DisposableEffect(node, isEditable) { node.isEditable = isEditable; onDispose {} }
        NodeLifecycle(node, content)
    }

    // ── ModelNode ─────────────────────────────────────────────────────────────────────────────────

    /**
     * A node that renders a glTF/GLB 3D model.
     *
     * Typically used in combination with [rememberModelInstance] to load a model asynchronously:
     * ```kotlin
     * SceneView {
     *     rememberModelInstance(modelLoader, "models/helmet.glb")?.let { instance ->
     *         ModelNode(modelInstance = instance, scaleToUnits = 0.5f)
     *     }
     * }
     * ```
     *
     * **Reactive animation** — drive animations from Compose state:
     * ```kotlin
     * var isWalking by remember { mutableStateOf(false) }
     *
     * ModelNode(
     *     modelInstance = instance,
     *     autoAnimate = false,
     *     animationName = if (isWalking) "Walk" else "Idle"
     * )
     * ```
     * When [animationName] changes the previous animation is stopped and the new one starts.
     * When [animationName] is `null` the [autoAnimate] behaviour applies instead.
     *
     * @param modelInstance  The loaded model instance to render.
     * @param autoAnimate    Automatically play all animations in the model when [animationName]
     *                       is `null`. Default `true`.
     * @param animationName  Name of the glTF animation to play. `null` defers to [autoAnimate].
     *                       Changing this value from Compose state switches animations reactively.
     * @param animationLoop  Whether [animationName] loops. Default `true`.
     * @param animationSpeed Playback speed multiplier for [animationName]. Default `1f`.
     * @param scaleToUnits   Uniformly scales the model to fit within a cube of this size (meters).
     *                       **Note:** when set, the [scale] parameter is ignored — the model's scale
     *                       is computed from its bounding box. Use `null` to control scale manually.
     * @param centerOrigin   Origin alignment relative to the model's bounding box.
     *                       - `null` keeps the model's original center
     *                       - `Position(0,0,0)` centers horizontally and vertically
     *                       - `Position(0,-1,0)` = centered horizontally, bottom-aligned
     * @param position       Local position.
     * @param rotation       Local rotation in Euler angles (degrees).
     * @param scale          Local scale.
     * @param isVisible      Whether to render the node.
     * @param isEditable     Whether the node can be interactively transformed.
     * @param apply          Additional imperative configuration applied once on creation.
     * @param content        Optional child nodes declared in a [NodeScope].
     */
    @Composable
    fun ModelNode(
        modelInstance: ModelInstance,
        autoAnimate: Boolean = true,
        animationName: String? = null,
        animationLoop: Boolean = true,
        animationSpeed: Float = 1f,
        scaleToUnits: Float? = null,
        centerOrigin: Position? = null,
        position: Position = Position(x = 0f),
        rotation: Rotation = Rotation(x = 0f),
        // Positional `Scale(1f)` fills all three axes → (1, 1, 1). Using the named form
        // `Scale(x = 1f)` would leave y/z at 0 and produce a singular transform — see the
        // Node() doc for the full breakdown.
        scale: Scale = Scale(1f),
        isVisible: Boolean = true,
        isEditable: Boolean = false,
        apply: ModelNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val node = remember(engine, modelInstance) {
            ModelNodeImpl(
                modelInstance = modelInstance,
                autoAnimate = autoAnimate && animationName == null,
                scaleToUnits = scaleToUnits,
                centerOrigin = centerOrigin
            ).apply {
                this.position = position
                this.rotation = rotation
                // Don't reset scale here — scaleToUnitCube() already set it in the constructor.
                // If scaleToUnits is null, scale stays at its default (Scale(1f)); apply() can
                // override either way.
                if (scaleToUnits == null) this.scale = scale
                this.isVisible = isVisible
                this.isEditable = isEditable
                apply()
            }
        }
        SideEffect {
            node.position = position
            node.rotation = rotation
            // Don't clobber scaleToUnits-computed scale on every recomposition.
            if (scaleToUnits == null) node.scale = scale
            node.isVisible = isVisible
            node.isEditable = isEditable
        }
        // Switch animation reactively when animationName changes.
        if (animationName != null) {
            DisposableEffect(node, animationName) {
                node.playAnimation(animationName, speed = animationSpeed, loop = animationLoop)
                onDispose { node.stopAnimation(animationName) }
            }
        }
        NodeLifecycle(node, content)
    }

    // ── LightNode ─────────────────────────────────────────────────────────────────────────────────

    /**
     * A light source node (directional, point, spot, or sun).
     *
     * At least one light must be added to the scene to see anything unless using unlit materials.
     *
     * Simplified overload — pass common properties directly without needing `apply` lambdas:
     * ```kotlin
     * LightNode(
     *     type = LightManager.Type.DIRECTIONAL,
     *     intensity = 100_000f,
     *     color = Color(1f, 0.95f, 0.9f),
     *     direction = Direction(0f, -1f, 0f),
     *     position = Position(0f, 5f, 0f)
     * )
     * ```
     *
     * Advanced overload — use `apply` for full [LightManager.Builder] access:
     * ```kotlin
     * LightNode(
     *     type = LightManager.Type.SPOT,
     *     apply = {
     *         intensity(50_000f)
     *         falloff(10f)
     *         spotLightCone(innerAngle = 0.1f, outerAngle = 0.5f)
     *     }
     * )
     * ```
     *
     * @param type       The [LightManager.Type] of light (DIRECTIONAL, POINT, SPOT, SUN, etc.).
     * @param intensity  Luminous intensity in candela (point/spot) or lux (directional/sun).
     * @param direction  Direction vector for directional/spot/sun lights.
     * @param position   World-space position of the light node.
     * @param color      Linear RGB light colour (defaults to white when null).
     * @param apply      Builder configuration for [LightManager.Builder] — for advanced properties
     *                   (falloff, spotLightCone, sunAngularRadius, etc.).
     * @param nodeApply  Additional imperative configuration on the [LightNodeImpl] after creation.
     * @param content    Optional child nodes declared in a [NodeScope].
     */
    @Composable
    fun LightNode(
        type: LightManager.Type,
        intensity: Float? = null,
        direction: Direction? = null,
        position: Position = Position(x = 0f),
        color: io.github.sceneview.math.Color? = null,
        apply: LightManager.Builder.() -> Unit = {},
        nodeApply: LightNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val node = remember(engine, type) {
            LightNodeImpl(engine = engine, type = type, apply = {
                intensity?.let { intensity(it) }
                direction?.let { direction(it.x, it.y, it.z) }
                color?.let { color(it.r, it.g, it.b) }
                apply()
            }).apply(nodeApply)
        }
        SideEffect {
            // Push every reactive prop on each recomposition so Compose state changes
            // (sliders, colour pickers, toggles) actually drive the underlying Light.
            // The builder `apply` block only runs at creation time — without this
            // SideEffect, sliders that live-modified intensity / direction / colour
            // silently had no effect on the rendered scene.
            node.position = position
            intensity?.let { node.intensity = it }
            direction?.let { node.lightDirection = it }
            color?.let { node.color = it }
        }
        NodeLifecycle(node, content)
    }

    // ── SecondaryCamera (formerly CameraNode) ───────────────────────────────────────────────────

    /**
     * A secondary camera node that can be used as an alternative viewpoint.
     *
     * **Note:** This does NOT automatically become the scene's active rendering camera.
     * The main rendering camera is configured via the `cameraNode` parameter of [Scene].
     * Use this composable to add cameras as named scene nodes (e.g. imported from a glTF model).
     *
     * @param apply   Configuration applied to the [CameraNodeImpl] on creation.
     * @param content Optional child nodes declared in a [NodeScope].
     */
    @Composable
    fun SecondaryCamera(
        apply: CameraNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val node = remember(engine) {
            CameraNodeImpl(engine = engine).apply(apply)
        }
        NodeLifecycle(node, content)
    }

    /**
     * @deprecated Use [SecondaryCamera] instead. This composable creates a non-active camera —
     * the name `CameraNode` is misleading because the scene's active camera is set via the
     * `cameraNode` parameter of [Scene], not via this composable.
     */
    @Deprecated(
        message = "Renamed to SecondaryCamera for clarity. CameraNode creates a non-active camera.",
        replaceWith = ReplaceWith("SecondaryCamera(apply, content)")
    )
    @Composable
    fun CameraNode(
        apply: CameraNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) = SecondaryCamera(apply, content)

    // ── CubeNode ──────────────────────────────────────────────────────────────────────────────────

    /**
     * A box/cube geometry node.
     *
     * @param size             Full size (width × height × depth) of the cube in meters.
     * @param center           Center of the cube in local space.
     * @param materialInstance The material instance to apply to all faces.
     * @param position         World-space position.
     * @param rotation         World-space rotation (Euler angles in degrees).
     * @param scale            Uniform or non-uniform scale.
     * @param apply            Additional configuration on the [CubeNodeImpl].
     * @param content          Optional child nodes.
     */
    @Composable
    fun CubeNode(
        size: Size = Cube.DEFAULT_SIZE,
        center: Position = Cube.DEFAULT_CENTER,
        materialInstance: MaterialInstance? = null,
        position: Position = Position(x = 0f),
        rotation: Rotation = Rotation(x = 0f),
        scale: Scale = Scale(1f),
        apply: CubeNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val node = remember(engine) {
            CubeNodeImpl(
                engine = engine,
                size = size,
                center = center,
                materialInstance = materialInstance
            ).apply(apply)
        }
        val prevCubeGeometry = remember { mutableStateOf(listOf<Any?>(size, center)) }
        val prevCubeMaterial = remember { mutableStateOf(materialInstance) }
        SideEffect {
            val current = listOf<Any?>(size, center)
            if (current != prevCubeGeometry.value) {
                node.updateGeometry(center = center, size = size)
                prevCubeGeometry.value = current
            }
            // Propagate MaterialInstance reassignments — see SphereNode for rationale.
            if (materialInstance != null && materialInstance != prevCubeMaterial.value) {
                node.setMaterialInstanceAt(0, materialInstance)
                prevCubeMaterial.value = materialInstance
            }
            node.position = position
            node.rotation = rotation
            node.scale = scale
        }
        NodeLifecycle(node, content)
    }

    // ── SphereNode ────────────────────────────────────────────────────────────────────────────────

    /**
     * A sphere geometry node.
     *
     * @param radius           Radius of the sphere in meters.
     * @param center           Center of the sphere in local space.
     * @param stacks           Number of horizontal subdivisions.
     * @param slices           Number of vertical subdivisions.
     * @param materialInstance The material instance to apply.
     * @param position         World-space position.
     * @param rotation         World-space rotation (Euler angles in degrees).
     * @param scale            Uniform or non-uniform scale.
     * @param apply            Additional configuration on the [SphereNodeImpl].
     * @param content          Optional child nodes.
     */
    @Composable
    fun SphereNode(
        radius: Float = Sphere.DEFAULT_RADIUS,
        center: Position = Sphere.DEFAULT_CENTER,
        stacks: Int = Sphere.DEFAULT_STACKS,
        slices: Int = Sphere.DEFAULT_SLICES,
        materialInstance: MaterialInstance? = null,
        position: Position = Position(x = 0f),
        rotation: Rotation = Rotation(x = 0f),
        scale: Scale = Scale(1f),
        apply: SphereNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val node = remember(engine) {
            SphereNodeImpl(
                engine = engine,
                radius = radius,
                center = center,
                stacks = stacks,
                slices = slices,
                materialInstance = materialInstance
            ).apply(apply)
        }
        val prevSphereGeometry = remember { mutableStateOf(listOf<Any?>(radius, center, stacks, slices)) }
        val prevSphereMaterial = remember { mutableStateOf(materialInstance) }
        SideEffect {
            val current = listOf<Any?>(radius, center, stacks, slices)
            if (current != prevSphereGeometry.value) {
                node.updateGeometry(radius = radius, center = center, stacks = stacks, slices = slices)
                prevSphereGeometry.value = current
            }
            // Propagate MaterialInstance reassignments from the caller — without this,
            // swapping `materialInstance = highlightMaterial` on recomposition leaves the
            // node with its construction-time material forever. Filament's
            // `setMaterialInstanceAt(primitiveIndex, newInstance)` is cheap (no geometry
            // rebuild), so doing it unconditionally on change is fine.
            if (materialInstance != null && materialInstance != prevSphereMaterial.value) {
                node.setMaterialInstanceAt(0, materialInstance)
                prevSphereMaterial.value = materialInstance
            }
        }
        // Push declarative transform props ONLY when they actually change — never on every
        // recomposition. A bare `SideEffect { node.position = position }` re-runs after each
        // successful recomposition and clobbers any position a frame-loop driver
        // (PhysicsBody, animations, camera follow) wrote via `node.onFrame` in between, so
        // PhysicsDemo spheres never appeared to move. Component-wise keys (not the Float3
        // wrapper — `dev.romainguy.kotlin.math.Float3` uses reference equality, so a freshly
        // allocated `Position(x = …)` always `!=` the previous instance) let
        // `DisposableEffect` stay idle while a frame driver owns the node. Mirrors the fix
        // already applied to the bare `Node` composable above.
        DisposableEffect(node, position.x, position.y, position.z) {
            node.position = position; onDispose {}
        }
        DisposableEffect(node, rotation.x, rotation.y, rotation.z) {
            node.rotation = rotation; onDispose {}
        }
        DisposableEffect(node, scale.x, scale.y, scale.z) {
            node.scale = scale; onDispose {}
        }
        NodeLifecycle(node, content)
    }

    // ── CylinderNode ──────────────────────────────────────────────────────────────────────────────

    /**
     * A cylinder geometry node.
     *
     * @param radius           Radius of the cylinder in meters.
     * @param height           Height of the cylinder in meters.
     * @param center           Center of the cylinder in local space.
     * @param sideCount        Number of sides (polygon resolution of the cylinder).
     * @param materialInstance The material instance to apply.
     * @param position         World-space position.
     * @param rotation         World-space rotation (Euler angles in degrees).
     * @param scale            Uniform or non-uniform scale.
     * @param apply            Additional configuration on the [CylinderNodeImpl].
     * @param content          Optional child nodes.
     */
    @Composable
    fun CylinderNode(
        radius: Float = Cylinder.DEFAULT_RADIUS,
        height: Float = Cylinder.DEFAULT_HEIGHT,
        center: Position = Cylinder.DEFAULT_CENTER,
        sideCount: Int = Cylinder.DEFAULT_SIDE_COUNT,
        materialInstance: MaterialInstance? = null,
        position: Position = Position(x = 0f),
        rotation: Rotation = Rotation(x = 0f),
        scale: Scale = Scale(1f),
        apply: CylinderNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val node = remember(engine) {
            CylinderNodeImpl(
                engine = engine,
                radius = radius,
                height = height,
                center = center,
                sideCount = sideCount,
                materialInstance = materialInstance
            ).apply(apply)
        }
        val prevCylinderGeometry = remember { mutableStateOf(listOf<Any?>(radius, height, center, sideCount)) }
        val prevCylinderMaterial = remember { mutableStateOf(materialInstance) }
        SideEffect {
            val current = listOf<Any?>(radius, height, center, sideCount)
            if (current != prevCylinderGeometry.value) {
                node.updateGeometry(
                    radius = radius, height = height, center = center, sideCount = sideCount
                )
                prevCylinderGeometry.value = current
            }
            // Propagate MaterialInstance reassignments — see SphereNode for rationale.
            if (materialInstance != null && materialInstance != prevCylinderMaterial.value) {
                node.setMaterialInstanceAt(0, materialInstance)
                prevCylinderMaterial.value = materialInstance
            }
            node.position = position
            node.rotation = rotation
            node.scale = scale
        }
        NodeLifecycle(node, content)
    }

    // ── ConeNode ─────────────────────────────────────────────────────────────────────────────────

    /**
     * A cone geometry node.
     *
     * @param radius           Base radius in meters.
     * @param height           Height of the cone in meters.
     * @param center           Center of the cone in local space.
     * @param sideCount        Number of sides (polygon resolution).
     * @param materialInstance The material instance to apply.
     * @param position         World-space position.
     * @param rotation         World-space rotation (Euler angles in degrees).
     * @param scale            Uniform or non-uniform scale.
     * @param apply            Additional configuration on the [ConeNodeImpl].
     * @param content          Optional child nodes.
     */
    @Composable
    fun ConeNode(
        radius: Float = Cone.DEFAULT_RADIUS,
        height: Float = Cone.DEFAULT_HEIGHT,
        center: Position = Cone.DEFAULT_CENTER,
        sideCount: Int = Cone.DEFAULT_SIDE_COUNT,
        materialInstance: MaterialInstance? = null,
        position: Position = Position(x = 0f),
        rotation: Rotation = Rotation(x = 0f),
        scale: Scale = Scale(1f),
        apply: ConeNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val node = remember(engine) {
            ConeNodeImpl(
                engine = engine,
                radius = radius,
                height = height,
                center = center,
                sideCount = sideCount,
                materialInstance = materialInstance
            ).apply(apply)
        }
        val prevConeGeometry = remember { mutableStateOf(listOf<Any?>(radius, height, center, sideCount)) }
        val prevConeMaterial = remember { mutableStateOf(materialInstance) }
        SideEffect {
            val current = listOf<Any?>(radius, height, center, sideCount)
            if (current != prevConeGeometry.value) {
                node.updateGeometry(radius = radius, height = height, center = center, sideCount = sideCount)
                prevConeGeometry.value = current
            }
            if (materialInstance != null && materialInstance != prevConeMaterial.value) {
                node.setMaterialInstanceAt(0, materialInstance)
                prevConeMaterial.value = materialInstance
            }
            node.position = position
            node.rotation = rotation
            node.scale = scale
        }
        NodeLifecycle(node, content)
    }

    // ── TorusNode ────────────────────────────────────────────────────────────────────────────────

    /**
     * A torus (donut) geometry node.
     *
     * @param majorRadius      Distance from centre to tube centre in meters.
     * @param minorRadius      Tube radius in meters.
     * @param center           Center of the torus in local space.
     * @param majorSegments    Segments around the ring.
     * @param minorSegments    Segments around the tube cross-section.
     * @param materialInstance The material instance to apply.
     * @param position         World-space position.
     * @param rotation         World-space rotation (Euler angles in degrees).
     * @param scale            Uniform or non-uniform scale.
     * @param apply            Additional configuration on the [TorusNodeImpl].
     * @param content          Optional child nodes.
     */
    @Composable
    fun TorusNode(
        majorRadius: Float = Torus.DEFAULT_MAJOR_RADIUS,
        minorRadius: Float = Torus.DEFAULT_MINOR_RADIUS,
        center: Position = Torus.DEFAULT_CENTER,
        majorSegments: Int = Torus.DEFAULT_MAJOR_SEGMENTS,
        minorSegments: Int = Torus.DEFAULT_MINOR_SEGMENTS,
        materialInstance: MaterialInstance? = null,
        position: Position = Position(x = 0f),
        rotation: Rotation = Rotation(x = 0f),
        scale: Scale = Scale(1f),
        apply: TorusNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val node = remember(engine) {
            TorusNodeImpl(
                engine = engine,
                majorRadius = majorRadius,
                minorRadius = minorRadius,
                center = center,
                majorSegments = majorSegments,
                minorSegments = minorSegments,
                materialInstance = materialInstance
            ).apply(apply)
        }
        val prevTorusGeometry = remember { mutableStateOf(listOf<Any?>(majorRadius, minorRadius, center, majorSegments, minorSegments)) }
        val prevTorusMaterial = remember { mutableStateOf(materialInstance) }
        SideEffect {
            val current = listOf<Any?>(majorRadius, minorRadius, center, majorSegments, minorSegments)
            if (current != prevTorusGeometry.value) {
                node.updateGeometry(
                    majorRadius = majorRadius, minorRadius = minorRadius,
                    center = center, majorSegments = majorSegments, minorSegments = minorSegments
                )
                prevTorusGeometry.value = current
            }
            if (materialInstance != null && materialInstance != prevTorusMaterial.value) {
                node.setMaterialInstanceAt(0, materialInstance)
                prevTorusMaterial.value = materialInstance
            }
            node.position = position
            node.rotation = rotation
            node.scale = scale
        }
        NodeLifecycle(node, content)
    }

    // ── CapsuleNode ──────────────────────────────────────────────────────────────────────────────

    /**
     * A capsule geometry node (cylinder with hemispherical caps).
     *
     * @param radius           Hemisphere/tube radius in meters.
     * @param height           Cylindrical section height in meters (total = height + 2*radius).
     * @param center           Center of the capsule in local space.
     * @param capStacks        Hemisphere subdivision stacks.
     * @param sideSlices       Circumference slices.
     * @param materialInstance The material instance to apply.
     * @param position         World-space position.
     * @param rotation         World-space rotation (Euler angles in degrees).
     * @param scale            Uniform or non-uniform scale.
     * @param apply            Additional configuration on the [CapsuleNodeImpl].
     * @param content          Optional child nodes.
     */
    @Composable
    fun CapsuleNode(
        radius: Float = Capsule.DEFAULT_RADIUS,
        height: Float = Capsule.DEFAULT_HEIGHT,
        center: Position = Capsule.DEFAULT_CENTER,
        capStacks: Int = Capsule.DEFAULT_CAP_STACKS,
        sideSlices: Int = Capsule.DEFAULT_SIDE_SLICES,
        materialInstance: MaterialInstance? = null,
        position: Position = Position(x = 0f),
        rotation: Rotation = Rotation(x = 0f),
        scale: Scale = Scale(1f),
        apply: CapsuleNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val node = remember(engine) {
            CapsuleNodeImpl(
                engine = engine,
                radius = radius,
                height = height,
                center = center,
                capStacks = capStacks,
                sideSlices = sideSlices,
                materialInstance = materialInstance
            ).apply(apply)
        }
        val prevCapsuleGeometry = remember { mutableStateOf(listOf<Any?>(radius, height, center, capStacks, sideSlices)) }
        val prevCapsuleMaterial = remember { mutableStateOf(materialInstance) }
        SideEffect {
            val current = listOf<Any?>(radius, height, center, capStacks, sideSlices)
            if (current != prevCapsuleGeometry.value) {
                node.updateGeometry(
                    radius = radius, height = height, center = center,
                    capStacks = capStacks, sideSlices = sideSlices
                )
                prevCapsuleGeometry.value = current
            }
            if (materialInstance != null && materialInstance != prevCapsuleMaterial.value) {
                node.setMaterialInstanceAt(0, materialInstance)
                prevCapsuleMaterial.value = materialInstance
            }
            node.position = position
            node.rotation = rotation
            node.scale = scale
        }
        NodeLifecycle(node, content)
    }

    // ── PlaneNode ─────────────────────────────────────────────────────────────────────────────────

    /**
     * A flat quad/plane geometry node.
     *
     * @param size             Width × height of the plane in meters.
     * @param center           Center of the plane in local space.
     * @param normal           Facing direction of the plane.
     * @param uvScale          UV texture coordinate scale.
     * @param materialInstance The material instance to apply.
     * @param position         World-space position.
     * @param rotation         World-space rotation (Euler angles in degrees).
     * @param scale            Uniform or non-uniform scale.
     * @param apply            Additional configuration on the [PlaneNodeImpl].
     * @param content          Optional child nodes.
     */
    @Composable
    fun PlaneNode(
        size: Size = Plane.DEFAULT_SIZE,
        center: Position = Plane.DEFAULT_CENTER,
        normal: Direction = Plane.DEFAULT_NORMAL,
        uvScale: UvScale = UvScale(1.0f),
        materialInstance: MaterialInstance? = null,
        position: Position = Position(x = 0f),
        rotation: Rotation = Rotation(x = 0f),
        scale: Scale = Scale(1f),
        apply: PlaneNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val node = remember(engine) {
            PlaneNodeImpl(
                engine = engine,
                size = size,
                center = center,
                normal = normal,
                uvScale = uvScale,
                materialInstance = materialInstance
            ).apply(apply)
        }
        val prevPlaneGeometry = remember { mutableStateOf(listOf<Any?>(size, center, normal)) }
        val prevPlaneMaterial = remember { mutableStateOf(materialInstance) }
        SideEffect {
            val current = listOf<Any?>(size, center, normal)
            if (current != prevPlaneGeometry.value) {
                node.updateGeometry(size = size, center = center, normal = normal)
                prevPlaneGeometry.value = current
            }
            if (materialInstance != null && materialInstance != prevPlaneMaterial.value) {
                node.setMaterialInstanceAt(0, materialInstance)
                prevPlaneMaterial.value = materialInstance
            }
            node.position = position
            node.rotation = rotation
            node.scale = scale
        }
        NodeLifecycle(node, content)
    }

    // ── ImageNode ─────────────────────────────────────────────────────────────────────────────────

    /**
     * A flat plane node that renders a [Bitmap] image.
     *
     * The plane size is automatically derived from the image's aspect ratio unless [size] is set.
     *
     * @param bitmap           The image to display.
     * @param size             Override size. `null` derives size from the bitmap's aspect ratio.
     * @param center           Center of the image plane in local space.
     * @param normal           Facing direction of the image plane.
     * @param apply            Additional configuration on the [ImageNodeImpl].
     * @param content          Optional child nodes.
     */
    @Composable
    fun ImageNode(
        bitmap: Bitmap,
        size: Size? = null,
        center: Position = Plane.DEFAULT_CENTER,
        normal: Direction = Plane.DEFAULT_NORMAL,
        position: Position = Position(x = 0f),
        rotation: Rotation = Rotation(x = 0f),
        scale: Scale = Scale(1f),
        apply: ImageNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val node = remember(engine, materialLoader, bitmap) {
            ImageNodeImpl(
                materialLoader = materialLoader,
                bitmap = bitmap,
                size = size,
                center = center,
                normal = normal
            ).apply(apply)
        }
        SideEffect {
            node.bitmap = bitmap
            node.position = position
            node.rotation = rotation
            node.scale = scale
        }
        NodeLifecycle(node, content)
    }

    /**
     * A flat plane node that renders an image loaded from [imageFileLocation] in the asset folder.
     *
     * @param imageFileLocation Path to the image file relative to the `assets` folder.
     * @param size              Override size. `null` derives size from the image's aspect ratio.
     * @param center            Center of the image plane in local space.
     * @param normal            Facing direction of the image plane.
     * @param apply             Additional configuration on the [ImageNodeImpl].
     * @param content           Optional child nodes.
     */
    @Composable
    fun ImageNode(
        imageFileLocation: String,
        size: Size? = null,
        center: Position = Plane.DEFAULT_CENTER,
        normal: Direction = Plane.DEFAULT_NORMAL,
        position: Position = Position(x = 0f),
        rotation: Rotation = Rotation(x = 0f),
        scale: Scale = Scale(1f),
        apply: ImageNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val node = remember(engine, materialLoader, imageFileLocation) {
            ImageNodeImpl(
                materialLoader = materialLoader,
                imageFileLocation = imageFileLocation,
                size = size,
                center = center,
                normal = normal
            ).apply(apply)
        }
        SideEffect {
            node.position = position
            node.rotation = rotation
            node.scale = scale
        }
        NodeLifecycle(node, content)
    }

    /**
     * A flat plane node that renders an image from a drawable resource.
     *
     * @param imageResId Drawable resource ID.
     * @param size       Override size. `null` derives size from the image's aspect ratio.
     * @param center     Center of the image plane in local space.
     * @param normal     Facing direction of the image plane.
     * @param apply      Additional configuration on the [ImageNodeImpl].
     * @param content    Optional child nodes.
     */
    @Composable
    fun ImageNode(
        @DrawableRes imageResId: Int,
        size: Size? = null,
        center: Position = Plane.DEFAULT_CENTER,
        normal: Direction = Plane.DEFAULT_NORMAL,
        position: Position = Position(x = 0f),
        rotation: Rotation = Rotation(x = 0f),
        scale: Scale = Scale(1f),
        apply: ImageNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val node = remember(engine, materialLoader, imageResId) {
            ImageNodeImpl(
                materialLoader = materialLoader,
                imageResId = imageResId,
                size = size,
                center = center,
                normal = normal
            ).apply(apply)
        }
        SideEffect {
            node.position = position
            node.rotation = rotation
            node.scale = scale
        }
        NodeLifecycle(node, content)
    }

    // ── BillboardNode ─────────────────────────────────────────────────────────────────────────────

    /**
     * A flat quad node that always faces the camera (billboard behaviour).
     *
     * Pass a [Bitmap] and optionally explicit [widthMeters]/[heightMeters] to control the world-
     * space size of the quad. Provide [cameraPositionProvider] so the node can rotate toward the
     * camera every frame.
     *
     * @param bitmap                 The bitmap texture to display.
     * @param widthMeters            Quad width in meters (`null` derives from bitmap aspect ratio).
     * @param heightMeters           Quad height in meters (`null` derives from bitmap aspect ratio).
     * @param position               Local position.
     * @param cameraPositionProvider Lambda returning the camera world position every frame.
     * @param apply                  Additional configuration on the [BillboardNodeImpl].
     * @param content                Optional child nodes.
     */
    @Composable
    fun BillboardNode(
        bitmap: Bitmap,
        widthMeters: Float? = null,
        heightMeters: Float? = null,
        position: Position = Position(x = 0f),
        scale: Scale = Scale(1f),
        cameraPositionProvider: (() -> Position)? = null,
        apply: BillboardNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val node = remember(engine, materialLoader, bitmap) {
            BillboardNodeImpl(
                materialLoader = materialLoader,
                bitmap = bitmap,
                widthMeters = widthMeters,
                heightMeters = heightMeters,
                cameraPositionProvider = cameraPositionProvider
            ).apply(apply)
        }
        SideEffect {
            node.bitmap = bitmap
            node.position = position
            node.scale = scale
        }
        NodeLifecycle(node, content)
    }

    // ── TextNode ──────────────────────────────────────────────────────────────────────────────────

    /**
     * A 3D text-label node that always faces the camera.
     *
     * Text is rendered to an Android [android.graphics.Bitmap] via [android.graphics.Canvas] and
     * displayed on a flat quad that rotates toward the camera each frame.
     *
     * @param text                   The string to display.
     * @param fontSize               Font size in pixels used when rendering the bitmap (default 48).
     * @param textColor              ARGB text colour (default opaque white).
     * @param backgroundColor        ARGB background fill colour (default semi-transparent black).
     * @param widthMeters            Quad width in meters (default 0.6).
     * @param heightMeters           Quad height in meters (default 0.2).
     * @param position               Local position.
     * @param cameraPositionProvider Lambda returning the camera world position every frame.
     * @param apply                  Additional configuration on the [TextNodeImpl].
     * @param content                Optional child nodes.
     */
    @Composable
    fun TextNode(
        text: String,
        fontSize: Float = 48f,
        textColor: Int = android.graphics.Color.WHITE,
        backgroundColor: Int = 0xCC000000.toInt(),
        typeface: android.graphics.Typeface = android.graphics.Typeface.DEFAULT_BOLD,
        widthMeters: Float = 0.6f,
        heightMeters: Float = 0.2f,
        position: Position = Position(x = 0f),
        scale: Scale = Scale(1f),
        cameraPositionProvider: (() -> Position)? = null,
        apply: TextNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val node = remember(engine, materialLoader) {
            TextNodeImpl(
                materialLoader = materialLoader,
                text = text,
                fontSize = fontSize,
                textColor = textColor,
                backgroundColor = backgroundColor,
                typeface = typeface,
                widthMeters = widthMeters,
                heightMeters = heightMeters,
                cameraPositionProvider = cameraPositionProvider
            ).apply(apply)
        }
        SideEffect {
            node.text = text
            node.fontSize = fontSize
            node.textColor = textColor
            node.backgroundColor = backgroundColor
            node.typeface = typeface
            node.position = position
            node.scale = scale
        }
        NodeLifecycle(node, content)
    }

        // ── VideoNode ─────────────────────────────────────────────────────────────────────────────────

    /**
     * A node that renders video from an Android [android.media.MediaPlayer] onto a flat plane in
     * 3D space.
     *
     * The plane is auto-sized to the video's aspect ratio (longer edge = 1.0 world unit) unless
     * you provide an explicit [size]. When the video dimensions become known via
     * [android.media.MediaPlayer.OnVideoSizeChangedListener] the geometry is updated automatically.
     *
     * ```kotlin
     * val player = remember {
     *     MediaPlayer().apply {
     *         setDataSource(context, videoUri)
     *         isLooping = true
     *         prepare()
     *         start()
     *     }
     * }
     * DisposableEffect(Unit) { onDispose { player.release() } }
     *
     * SceneView {
     *     VideoNode(player = player, position = Position(z = -2f))
     * }
     * ```
     *
     * @param player           [android.media.MediaPlayer] whose frames are rendered on this node.
     * @param chromaKeyColor   Optional ARGB chroma-key colour for green-screen compositing.
     * @param size             Fixed plane size in world units. `null` = auto-size from video.
     * @param apply            Additional configuration on the [VideoNodeImpl] instance.
     * @param content          Optional child nodes in a [NodeScope].
     */
    @Composable
    fun VideoNode(
        player: MediaPlayer,
        chromaKeyColor: Int? = null,
        size: Size? = null,
        position: Position = Position(x = 0f),
        rotation: Rotation = Rotation(x = 0f),
        scale: Scale = Scale(1f),
        apply: VideoNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val node = remember(materialLoader, player) {
            VideoNodeImpl(
                materialLoader = materialLoader,
                player = player,
                chromaKeyColor = chromaKeyColor,
                size = size
            ).apply(apply)
        }
        SideEffect {
            node.position = position
            node.rotation = rotation
            node.scale = scale
        }
        NodeLifecycle(node, content)
    }

    /**
     * Convenience overload that loads a video from an asset file path and manages the
     * [MediaPlayer] lifecycle automatically.
     *
     * Mirrors the Android roadmap goal of `VideoNode(videoPath = "videos/promo.mp4", autoPlay = true)`.
     * Internally uses [rememberMediaPlayer] for lifecycle management and [VideoNode] for rendering.
     *
     * ```kotlin
     * SceneView {
     *     VideoNode(
     *         videoPath = "videos/promo.mp4",
     *         position = Position(z = -2f)
     *     )
     * }
     * ```
     *
     * @param videoPath        Asset file path (e.g. `"videos/promo.mp4"`).
     * @param autoPlay         Whether to start playback immediately. Default `true`.
     * @param isLooping        Whether the video should loop. Default `true`.
     * @param chromaKeyColor   Optional ARGB chroma-key colour for green-screen compositing.
     * @param size             Fixed plane size in world units. `null` = auto-size from video.
     * @param position         World-space position.
     * @param rotation         World-space rotation.
     * @param scale            World-space scale.
     * @param apply            Additional configuration on the [VideoNodeImpl] instance.
     * @param content          Optional child nodes in a [NodeScope].
     */
    @ExperimentalSceneViewApi
    @Composable
    fun VideoNode(
        videoPath: String,
        autoPlay: Boolean = true,
        isLooping: Boolean = true,
        chromaKeyColor: Int? = null,
        size: Size? = null,
        position: Position = Position(x = 0f),
        rotation: Rotation = Rotation(x = 0f),
        scale: Scale = Scale(1f),
        apply: VideoNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val context: Context = LocalContext.current
        val player = rememberMediaPlayer(
            context = context,
            assetFileLocation = videoPath,
            isLooping = isLooping,
            autoStart = autoPlay
        )
        if (player != null) {
            VideoNode(
                player = player,
                chromaKeyColor = chromaKeyColor,
                size = size,
                position = position,
                rotation = rotation,
                scale = scale,
                apply = apply,
                content = content
            )
        }
    }

    // ── ViewNode ──────────────────────────────────────────────────────────────────────────────────

    /**
     * A node that renders Jetpack Compose UI content onto a flat plane in 3D space.
     *
     * The Compose [viewContent] is rendered to an OpenGL texture and displayed on a plane node,
     * enabling rich UI overlays in the 3D scene (labels, info cards, HUDs, etc.).
     *
     * **Requires a [ViewNodeImpl.WindowManager]** — obtain one with [rememberViewNodeManager]:
     * ```kotlin
     * val windowManager = rememberViewNodeManager()
     * SceneView {
     *     ViewNode(windowManager = windowManager) {
     *         Text("Hello from 3D!")
     *     }
     * }
     * ```
     *
     * @param windowManager         The [ViewNodeImpl.WindowManager] to attach the view to.
     * @param unlit                 If `true`, ignores scene lighting (always fully bright).
     * @param invertFrontFaceWinding Inverts face winding — useful for front-facing AR cameras.
     * @param position              World-space position.
     * @param rotation              World-space rotation (Euler angles in degrees).
     * @param scale                 Uniform or non-uniform scale.
     * @param isVisible             Whether the node renders this frame.
     * @param apply                 Additional configuration on the [ViewNodeImpl] instance,
     *                              applied once at construction time. For reactive props,
     *                              prefer the top-level [position]/[rotation]/[scale]/[isVisible]
     *                              parameters above, which propagate changes on recomposition.
     * @param content               Optional 3D child nodes in a [NodeScope].
     * @param viewContent           The Compose UI to render inside the 3D node.
     */
    @Composable
    fun ViewNode(
        windowManager: ViewNodeImpl.WindowManager,
        unlit: Boolean = false,
        invertFrontFaceWinding: Boolean = false,
        position: Position = Position(x = 0f),
        rotation: Rotation = Rotation(x = 0f),
        scale: Scale = Scale(1f),
        isVisible: Boolean = true,
        apply: ViewNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null,
        viewContent: @Composable () -> Unit
    ) {
        val node = remember(engine, windowManager) {
            ViewNodeImpl(
                engine = engine,
                windowManager = windowManager,
                materialLoader = materialLoader,
                unlit = unlit,
                invertFrontFaceWinding = invertFrontFaceWinding,
                content = viewContent
            ).apply(apply)
        }
        // Keyed on scalar components (Float3 uses reference equality so a fresh
        // Position(x = …) on each recomposition would re-trigger even when unchanged).
        DisposableEffect(node, position.x, position.y, position.z) {
            node.position = position; onDispose {}
        }
        DisposableEffect(node, rotation.x, rotation.y, rotation.z) {
            node.rotation = rotation; onDispose {}
        }
        DisposableEffect(node, scale.x, scale.y, scale.z) {
            node.scale = scale; onDispose {}
        }
        DisposableEffect(node, isVisible) { node.isVisible = isVisible; onDispose {} }
        NodeLifecycle(node, content)
    }

    // ── LineNode ──────────────────────────────────────────────────────────────────────────────────

    /**
     * A node that renders a single line segment between two 3D points.
     *
     * ```kotlin
     * SceneView {
     *     val mat = remember(materialLoader) { materialLoader.createColorInstance(Color.Red) }
     *     LineNode(
     *         start = Position(0f, 0f, 0f),
     *         end   = Position(1f, 0f, 0f),
     *         materialInstance = mat
     *     )
     * }
     * ```
     *
     * @param start            Start point of the line in local space (meters).
     * @param end              End point of the line in local space (meters).
     * @param materialInstance The material instance to apply (color, unlit, etc.).
     * @param position         Local position of the node's origin.
     * @param rotation         Local rotation in Euler angles (degrees).
     * @param scale            Uniform or non-uniform scale.
     * @param apply            Additional configuration on the [LineNodeImpl].
     * @param content          Optional child nodes.
     */
    @Composable
    fun LineNode(
        start: Position = io.github.sceneview.geometries.Line.DEFAULT_START,
        end: Position = io.github.sceneview.geometries.Line.DEFAULT_END,
        materialInstance: MaterialInstance? = null,
        position: Position = Position(x = 0f),
        rotation: Rotation = Rotation(x = 0f),
        scale: Scale = Scale(1f),
        apply: LineNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val node = remember(engine) {
            LineNodeImpl(
                engine = engine,
                start = start,
                end = end,
                materialInstance = materialInstance
            ).apply(apply)
        }
        val prevLineGeometry = remember { mutableStateOf(listOf<Any?>(start, end)) }
        val prevLineMaterial = remember { mutableStateOf(materialInstance) }
        SideEffect {
            val current = listOf<Any?>(start, end)
            if (current != prevLineGeometry.value) {
                node.updateGeometry(start = start, end = end)
                prevLineGeometry.value = current
            }
            if (materialInstance != null && materialInstance != prevLineMaterial.value) {
                node.setMaterialInstanceAt(0, materialInstance)
                prevLineMaterial.value = materialInstance
            }
            node.position = position
            node.rotation = rotation
            node.scale = scale
        }
        NodeLifecycle(node, content)
    }

    // ── PathNode ──────────────────────────────────────────────────────────────────────────────────

    /**
     * A node that renders a polyline through a list of 3D points.
     *
     * ```kotlin
     * SceneView {
     *     val mat = remember(materialLoader) { materialLoader.createColorInstance(Color.Green) }
     *     PathNode(
     *         points = spiralPoints,
     *         closed = false,
     *         materialInstance = mat
     *     )
     * }
     * ```
     *
     * @param points           Ordered list of 3D points forming the polyline (at least 2).
     * @param closed           When `true`, adds a closing segment from the last to the first point.
     * @param materialInstance The material instance to apply.
     * @param position         Local position of the node's origin.
     * @param rotation         Local rotation in Euler angles (degrees).
     * @param scale            Uniform or non-uniform scale.
     * @param apply            Additional configuration on the [PathNodeImpl].
     * @param content          Optional child nodes.
     */
    @Composable
    fun PathNode(
        points: List<Position> = io.github.sceneview.geometries.Path.DEFAULT_POINTS,
        closed: Boolean = false,
        materialInstance: MaterialInstance? = null,
        position: Position = Position(x = 0f),
        rotation: Rotation = Rotation(x = 0f),
        scale: Scale = Scale(1f),
        apply: PathNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val node = remember(engine) {
            PathNodeImpl(
                engine = engine,
                points = points,
                closed = closed,
                materialInstance = materialInstance
            ).apply(apply)
        }
        val prevPathGeometry = remember { mutableStateOf(listOf<Any?>(points, closed)) }
        val prevPathMaterial = remember { mutableStateOf(materialInstance) }
        SideEffect {
            val current = listOf<Any?>(points, closed)
            if (current != prevPathGeometry.value) {
                node.updateGeometry(points = points, closed = closed)
                prevPathGeometry.value = current
            }
            if (materialInstance != null && materialInstance != prevPathMaterial.value) {
                node.setMaterialInstanceAt(0, materialInstance)
                prevPathMaterial.value = materialInstance
            }
            node.position = position
            node.rotation = rotation
            node.scale = scale
        }
        NodeLifecycle(node, content)
    }

    // ── MeshNode ──────────────────────────────────────────────────────────────────────────────────

    /**
     * A node with custom mesh geometry defined by a [VertexBuffer] and [IndexBuffer].
     *
     * @param primitiveType    How vertices are interpreted (TRIANGLES, LINES, POINTS, etc.).
     * @param vertexBuffer     The GPU vertex buffer.
     * @param indexBuffer      The GPU index buffer.
     * @param boundingBox      Optional bounding box for culling. When `null` (default), culling is
     *                         disabled and Filament auto-computes the bounding box.
     * @param materialInstance Optional material to apply to the mesh.
     * @param apply            Additional configuration on the [MeshNodeImpl].
     * @param content          Optional child nodes.
     */
    @Composable
    fun MeshNode(
        primitiveType: RenderableManager.PrimitiveType,
        vertexBuffer: VertexBuffer,
        indexBuffer: IndexBuffer,
        boundingBox: Box? = null,
        materialInstance: MaterialInstance? = null,
        apply: MeshNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val node = remember(engine, vertexBuffer, indexBuffer) {
            MeshNodeImpl(
                engine = engine,
                primitiveType = primitiveType,
                vertexBuffer = vertexBuffer,
                indexBuffer = indexBuffer,
                boundingBox = boundingBox,
                materialInstance = materialInstance
            ).apply(apply)
        }
        val prevMeshMaterial = remember { mutableStateOf(materialInstance) }
        SideEffect {
            if (materialInstance != null && materialInstance != prevMeshMaterial.value) {
                node.setMaterialInstanceAt(0, materialInstance)
                prevMeshMaterial.value = materialInstance
            }
        }
        NodeLifecycle(node, content)
    }

    // ── ReflectionProbeNode ───────────────────────────────────────────────────────────────────────

    /**
     * Overrides the scene's indirect light (IBL) with a baked [Environment] for a defined zone.
     *
     * When [radius] is greater than zero, the probe is a **local zone**: the IBL override is only
     * applied while [cameraPosition] is within [radius] metres of [position]. Set [radius] to `0f`
     * (or any non-positive value) for a **global probe** that always overrides the scene IBL.
     *
     * ```kotlin
     * val probeEnv = rememberEnvironment(environmentLoader) {
     *     environmentLoader.createHDREnvironment("environments/office.hdr") ?: createEnvironment(environmentLoader)
     * }
     * var cameraPos by remember { mutableStateOf(Position()) }
     *
     * SceneView(
     *     scene = scene,
     *     onFrame = { cameraPos = cameraNode.worldPosition }
     * ) {
     *     // Local probe — active only when the camera is within 3 m of the origin
     *     ReflectionProbeNode(
     *         filamentScene = scene,
     *         environment = probeEnv,
     *         position = Position(x = 0f, y = 1f, z = 0f),
     *         radius = 3f,
     *         cameraPosition = cameraPos
     *     )
     * }
     * ```
     *
     * @param filamentScene  The Filament [com.google.android.filament.Scene] whose indirect light
     *                       is overridden. Obtain via [rememberScene] and pass it to `SceneView`.
     * @param environment    The [Environment] whose [Environment.indirectLight] is applied.
     * @param position       Centre of the reflection zone in world space. Defaults to the origin.
     * @param radius         Sphere radius in metres. `0f` or negative means always-active (global).
     * @param priority       Higher value wins when multiple probes are simultaneously active.
     * @param cameraPosition Current camera world-space position, updated each frame.
     */
    @Composable
    fun ReflectionProbeNode(
        filamentScene: FilamentScene,
        environment: Environment,
        position: Position = Position(x = 0f, y = 0f, z = 0f),
        radius: Float = 0f,
        priority: Int = 0,
        cameraPosition: Position = Position(x = 0f, y = 0f, z = 0f)
    ) {
        ReflectionProbeNodeComposable(
            filamentScene = filamentScene,
            environment = environment,
            position = position,
            radius = radius,
            priority = priority,
            cameraPosition = cameraPosition
        )
    }

    // ── ShapeNode ──────────────────────────────────────────────────────────────────────────────────

    /**
     * A node that renders a 2D polygon extruded into 3D space using triangulated geometry.
     *
     * Useful for rendering custom shapes like floor plans, map overlays, or UI elements generated
     * from 2D polygon paths.
     *
     * ```kotlin
     * SceneView {
     *     val mat = remember(materialLoader) { materialLoader.createColorInstance(Color.Blue) }
     *     ShapeNode(
     *         polygonPath = listOf(
     *             Position2(-0.5f, -0.5f),
     *             Position2( 0.5f, -0.5f),
     *             Position2( 0.5f,  0.5f),
     *             Position2(-0.5f,  0.5f)
     *         ),
     *         materialInstance = mat,
     *         position = Position(0f, 0f, -2f)
     *     )
     * }
     * ```
     *
     * @param polygonPath      Ordered 2D vertices of the polygon outline (at least 3).
     * @param polygonHoles     Indices into [polygonPath] marking the start of each hole.
     * @param delaunayPoints   Extra interior points for better triangulation quality.
     * @param normal           Facing direction of the shape. Default up (+Y).
     * @param uvScale          UV texture coordinate scale.
     * @param color            Optional vertex colour.
     * @param materialInstance The material instance to apply.
     * @param position         World-space position.
     * @param rotation         World-space rotation (Euler angles in degrees).
     * @param scale            Uniform or non-uniform scale.
     * @param apply            Additional configuration on the [ShapeNodeImpl].
     * @param content          Optional child nodes.
     */
    @Composable
    fun ShapeNode(
        polygonPath: List<Position2> = listOf(),
        polygonHoles: List<Int> = listOf(),
        delaunayPoints: List<Position2> = listOf(),
        normal: Direction = io.github.sceneview.geometries.Shape.DEFAULT_NORMAL,
        uvScale: UvScale = UvScale(1.0f),
        color: Color? = null,
        materialInstance: MaterialInstance? = null,
        position: Position = Position(x = 0f),
        rotation: Rotation = Rotation(x = 0f),
        scale: Scale = Scale(1f),
        apply: ShapeNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val node = remember(engine) {
            ShapeNodeImpl(
                engine = engine,
                polygonPath = polygonPath,
                polygonHoles = polygonHoles,
                delaunayPoints = delaunayPoints,
                normal = normal,
                uvScale = uvScale,
                color = color,
                materialInstance = materialInstance
            ).apply(apply)
        }
        val prevShapeGeometry = remember { mutableStateOf(listOf<Any?>(polygonPath, polygonHoles, delaunayPoints, normal, uvScale, color)) }
        val prevShapeMaterial = remember { mutableStateOf(materialInstance) }
        SideEffect {
            val current = listOf<Any?>(polygonPath, polygonHoles, delaunayPoints, normal, uvScale, color)
            if (current != prevShapeGeometry.value) {
                node.updateGeometry(
                    polygonPath = polygonPath,
                    polygonHoles = polygonHoles,
                    delaunayPoints = delaunayPoints,
                    normal = normal,
                    uvScale = uvScale,
                    color = color
                )
                prevShapeGeometry.value = current
            }
            if (materialInstance != null && materialInstance != prevShapeMaterial.value) {
                node.setMaterialInstanceAt(0, materialInstance)
                prevShapeMaterial.value = materialInstance
            }
            node.position = position
            node.rotation = rotation
            node.scale = scale
        }
        NodeLifecycle(node, content)
    }

    // ── PhysicsNode ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Attaches a simple rigid-body physics simulation to a node.
     *
     * Applies gravity (-9.8 m/s²), floor collision, and bouncing via Euler integration. The target
     * node must already exist in the scene graph — this composable drives its position each frame.
     *
     * ```kotlin
     * SceneView {
     *     val mat = remember(materialLoader) { materialLoader.createColorInstance(Color.Red) }
     *     SphereNode(radius = 0.15f, materialInstance = mat, position = Position(0f, 3f, -2f)) {
     *         // This sphere will fall and bounce:
     *     }
     *     // Or use a remembered node reference:
     *     val sphereNode = remember(engine) { SphereNode(engine, radius = 0.15f) }
     *     PhysicsNode(
     *         node = sphereNode,
     *         restitution = 0.7f,
     *         linearVelocity = Position(x = 0.5f, y = 2f, z = 0f),
     *         radius = 0.15f
     *     )
     * }
     * ```
     *
     * @param node           The [Node] whose position is driven by the simulation. Must be in scene.
     * @param restitution    Bounciness in [0, 1]. 0 = inelastic, 1 = perfectly elastic.
     * @param linearVelocity Initial velocity in m/s (world space).
     * @param floorY         World Y coordinate of the floor plane. Default 0.
     * @param radius         Collision radius in metres (offsets contact point for sphere surface).
     */
    @Composable
    fun PhysicsNode(
        node: NodeImpl,
        restitution: Float = 0.6f,
        linearVelocity: Position = Position(0f, 0f, 0f),
        floorY: Float = 0f,
        radius: Float = 0f
    ) {
        val body = remember(node) {
            PhysicsBody(
                node = node,
                restitution = restitution,
                floorY = floorY,
                radius = radius,
                initialVelocity = linearVelocity
            )
        }
        DisposableEffect(node) {
            var prevFrameTime: Long? = null
            node.onFrame = { frameTimeNanos ->
                body.step(frameTimeNanos, prevFrameTime)
                prevFrameTime = frameTimeNanos
            }
            onDispose { node.onFrame = null }
        }
    }

    /**
     * Deprecated overload kept for source compatibility — the `mass` parameter is a no-op.
     *
     * The Euler integration applies only gravity, which is an acceleration and therefore
     * mass-independent. Use the [PhysicsNode] overload without `mass`.
     */
    @Deprecated(
        "The 'mass' parameter is currently a no-op (the Euler integration applies only " +
            "gravity, which is mass-independent). Use the PhysicsNode overload without 'mass'.",
        ReplaceWith("PhysicsNode(node, restitution, linearVelocity, floorY, radius)"),
        DeprecationLevel.WARNING
    )
    @Composable
    fun PhysicsNode(
        node: NodeImpl,
        @Suppress("UNUSED_PARAMETER") mass: Float,
        restitution: Float = 0.6f,
        linearVelocity: Position = Position(0f, 0f, 0f),
        floorY: Float = 0f,
        radius: Float = 0f
    ) = PhysicsNode(
        node = node,
        restitution = restitution,
        linearVelocity = linearVelocity,
        floorY = floorY,
        radius = radius
    )

    // ── Internal lifecycle helper ─────────────────────────────────────────────────────────────────

    /**
     * Internal helper shared by all node composables.
     *
     * Attaches [node] to this scope's container on entry and detaches/destroys it on exit.
     * Also runs any nested [content] inside a [NodeScope] receiver.
     *
     * **Extension point:** subclasses (e.g. [io.github.sceneview.ar.ARSceneScope]) should call this
     * method from their own node composables to get automatic lifecycle management. The [attach] and
     * [detach] methods can be overridden to customize how nodes are added/removed (see [NodeScope]).
     */
    @Composable
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    fun NodeLifecycle(
        node: NodeImpl,
        content: (@Composable NodeScope.() -> Unit)?
    ) {
        DisposableEffect(node) {
            attach(node)
            onDispose {
                detach(node)
                node.destroy()
            }
        }
        if (content != null) {
            NodeScope(parentNode = node, scope = this).content()
        }
    }
}

// ── NodeScope ─────────────────────────────────────────────────────────────────────────────────────

/**
 * Composable DSL scope for declaring child nodes under a specific parent node.
 *
 * `NodeScope` is the receiver of the optional `content` trailing lambda accepted by every node
 * composable in [SceneScope]. Composables declared inside are attached as children of
 * [parentNode] rather than the scene root, mirroring how nested `Column`/`Box` composables
 * work in standard Compose UI.
 *
 * ```kotlin
 * SceneView {
 *     Node(position = Position(y = 0.5f)) {  // <- this block is a NodeScope
 *         ModelNode(modelInstance = helmet)   // child of the Node above
 *         CubeNode(size = Size(0.05f))        // sibling, also a child of Node
 *     }
 * }
 * ```
 *
 * @param parentNode The node that newly declared composables are attached to as children.
 * @param scope      The parent [SceneScope] providing shared resources (engine, loaders, etc.).
 */
@SceneDsl
class NodeScope internal constructor(
    val parentNode: NodeImpl,
    scope: SceneScope
) : SceneScope(
    engine = scope.engine,
    modelLoader = scope.modelLoader,
    materialLoader = scope.materialLoader,
    environmentLoader = scope.environmentLoader,
    _nodes = scope._nodes,
    // Inherit the parent's synchronous-detach hook so a child node's renderable
    // entity is removed from the Filament scene BEFORE node.destroy() runs.
    // Without this, destroying a child's MaterialInstance while its Renderable
    // is still registered triggers the same SIGABRT class as PR #851/#852.
    // Mirrors the contract enforced by SceneScope (root) and ARSceneScope.
    nodeRemover = scope.nodeRemover
) {
    /**
     * Attaches [node] as a child of [parentNode].
     */
    override fun attach(node: NodeImpl) {
        parentNode.addChildNode(node)
    }

    /**
     * Removes [node] from [parentNode]'s children, then asks the parent scope's
     * [nodeRemover] to detach the renderable entity from the Filament scene
     * synchronously (see super [detach] for the lifecycle contract).
     */
    override fun detach(node: NodeImpl) {
        parentNode.removeChildNode(node)
        // super.detach also calls nodeRemover + removes from _nodes — but
        // child nodes are NOT in _nodes (they're tracked via parentNode.children),
        // so we only invoke nodeRemover directly to avoid a no-op _nodes.remove
        // and keep the per-frame Filament-scene contract honest.
        nodeRemover?.invoke(node)
    }
}
