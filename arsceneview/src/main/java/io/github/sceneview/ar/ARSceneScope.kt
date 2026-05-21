// ARSceneScope extends SceneScope with AR-specific composables (plane detection, anchors, etc.).
// Kept in the separate `arsceneview` module so that `sceneview` stays ARCore-free.
//  - NodeLifecycle is already reused from SceneScope (no override needed)
//  - AR node imports (AnchorNodeImpl, etc.) would move to a `sceneview/ar/node/` sub-package

package io.github.sceneview.ar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.ar.core.Anchor
import com.google.ar.core.AugmentedFace
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImage.TrackingMethod
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.StreetscapeGeometry
import com.google.ar.core.Trackable
import com.google.ar.core.TrackingState
import io.github.sceneview.NodeScope
import io.github.sceneview.SceneDsl
import io.github.sceneview.SceneScope
import io.github.sceneview.loaders.EnvironmentLoader
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.node.Node as NodeImpl
import io.github.sceneview.ar.node.AnchorNode as AnchorNodeImpl
import io.github.sceneview.ar.node.AugmentedFaceNode as AugmentedFaceNodeImpl
import io.github.sceneview.ar.node.AugmentedImageNode as AugmentedImageNodeImpl
import io.github.sceneview.ar.node.CloudAnchorNode as CloudAnchorNodeImpl
import io.github.sceneview.ar.node.DepthHitResultNode as DepthHitResultNodeImpl
import io.github.sceneview.ar.node.DepthMeshNode as DepthMeshNodeImpl
import io.github.sceneview.ar.node.DepthMeshSnapshot
import io.github.sceneview.ar.physics.DepthCollider
import io.github.sceneview.ar.node.HitResultNode as HitResultNodeImpl
import io.github.sceneview.ar.node.PoseNode as PoseNodeImpl
import io.github.sceneview.ar.node.RooftopAnchorNode as RooftopAnchorNodeImpl
import io.github.sceneview.ar.node.StreetscapeGeometryNode as StreetscapeGeometryNodeImpl
import io.github.sceneview.ar.node.TerrainAnchorNode as TerrainAnchorNodeImpl
import io.github.sceneview.ar.node.TrackableNode as TrackableNodeImpl

/**
 * The composable DSL scope for building AR scenes inside [ARScene].
 *
 * `ARSceneScope` extends [SceneScope] with AR-specific node composables that follow ARCore-tracked
 * objects — anchors, images, faces, cloud anchors, hit-test results, and generic trackables.
 * Every node is a `@Composable` function: it enters the scene on first composition and is
 * destroyed automatically when it leaves.
 *
 * Drive AR content with ordinary Compose state. When state changes, the composition reacts and
 * the AR scene updates on the next frame — no imperative add/remove calls needed.
 *
 * ```kotlin
 * ARSceneView(modifier = Modifier.fillMaxSize()) {
 *     // anchor is a mutableStateOf<Anchor?> — null until a plane is detected
 *     anchor?.let { a ->
 *         AnchorNode(anchor = a) {
 *             ModelNode(
 *                 modelInstance = rememberModelInstance(modelLoader, "models/helmet.glb"),
 *                 scaleToUnits = 0.5f
 *             )
 *         }
 *     }
 * }
 * ```
 *
 * AR-specific composables ([AnchorNode], [PoseNode], [HitResultNode], etc.) are only available
 * at the top level of `ARSceneView { }`. Inside a nested [NodeScope] (the `content` block of any
 * node), only the base [SceneScope] composables are in scope.
 *
 * **Naming convention:** Composable functions in this scope (e.g. `AnchorNode`, `TrackableNode`)
 * share their names with the underlying imperative node classes. Import aliases (`AnchorNodeImpl`,
 * etc.) are used internally to disambiguate. The composable is the primary API surface.
 *
 * @param engine            The Filament [Engine] shared with the parent [ARScene].
 * @param modelLoader       [ModelLoader] for loading glTF/GLB models.
 * @param materialLoader    [MaterialLoader] for creating material instances.
 * @param environmentLoader [EnvironmentLoader] for loading HDR environments.
 * @param _nodes            Internal SnapshotStateList backing the scene's root node list.
 */
@Suppress("FunctionName") // Composable functions follow PascalCase (Compose convention)
@SceneDsl
class ARSceneScope internal constructor(
    engine: Engine,
    modelLoader: ModelLoader,
    materialLoader: MaterialLoader,
    environmentLoader: EnvironmentLoader,
    _nodes: SnapshotStateList<NodeImpl>,
    // Same SIGABRT-prevention contract as the 3D root scope: removes the node
    // from the Filament scene synchronously inside detach() before node.destroy()
    // runs, so a MaterialInstance is never destroyed while its Renderable entity
    // is still registered. Without this the AR scope re-introduces the crash
    // class fixed in PR #851/#852 for any AR demo that disposes nodes.
    nodeRemover: ((NodeImpl) -> Unit)? = null
) : SceneScope(
    engine = engine,
    modelLoader = modelLoader,
    materialLoader = materialLoader,
    environmentLoader = environmentLoader,
    _nodes = _nodes,
    nodeRemover = nodeRemover
) {

    // ── AnchorNode ────────────────────────────────────────────────────────────────────────────────

    /**
     * A node that tracks a real-world [Anchor] position and orientation.
     *
     * The node's transform is updated each frame to match the anchor's pose as ARCore refines its
     * understanding of the environment. The node is only visible when the anchor is in
     * [TrackingState.TRACKING].
     *
     * Typical usage — place a model at a tapped surface:
     * ```kotlin
     * var anchor by remember { mutableStateOf<Anchor?>(null) }
     *
     * ARSceneView(
     *     onSessionUpdated = { _, frame ->
     *         if (anchor == null) {
     *             anchor = frame.hitTest(centerX, centerY)
     *                 .firstOrNull()?.createAnchor()
     *         }
     *     }
     * ) {
     *     anchor?.let { a ->
     *         AnchorNode(anchor = a) {
     *             ModelNode(modelInstance = rememberModelInstance(modelLoader, "helmet.glb"))
     *         }
     *     }
     * }
     * ```
     *
     * @param anchor                  The ARCore anchor to follow.
     * @param updateAnchorPose        Whether to automatically update the node's pose when the
     *                                anchor pose changes. Default `true`.
     * @param visibleTrackingStates   The set of [TrackingState]s for which the node is rendered.
     *                                Default: only [TrackingState.TRACKING].
     * @param onTrackingStateChanged  Callback invoked when the anchor's tracking state changes.
     * @param onAnchorChanged         Callback invoked when the [Anchor] reference is replaced.
     * @param onUpdated               Callback invoked each frame while the anchor is updated.
     * @param apply                   Additional imperative configuration on the [AnchorNodeImpl].
     * @param content                 Optional child nodes declared in a [NodeScope].
     */
    @Composable
    fun AnchorNode(
        anchor: Anchor,
        updateAnchorPose: Boolean = true,
        visibleTrackingStates: Set<TrackingState> = setOf(TrackingState.TRACKING),
        onTrackingStateChanged: ((TrackingState) -> Unit)? = null,
        onAnchorChanged: ((Anchor) -> Unit)? = null,
        onUpdated: ((Anchor) -> Unit)? = null,
        apply: AnchorNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val node = remember(engine, anchor) {
            AnchorNodeImpl(
                engine = engine,
                anchor = anchor,
                onTrackingStateChanged = onTrackingStateChanged,
                onAnchorChanged = onAnchorChanged,
                onUpdated = onUpdated
            ).apply {
                this.updateAnchorPose = updateAnchorPose
                this.visibleTrackingStates = visibleTrackingStates
                apply()
            }
        }
        SideEffect {
            node.updateAnchorPose = updateAnchorPose
            node.visibleTrackingStates = visibleTrackingStates
            node.onTrackingStateChanged = onTrackingStateChanged
            node.onAnchorChanged = onAnchorChanged
            node.onUpdated = onUpdated
        }
        NodeLifecycle(node, content)
    }

    // ── PoseNode ──────────────────────────────────────────────────────────────────────────────────

    /**
     * A node that is positioned at a specific ARCore [Pose] in the real world.
     *
     * Unlike [AnchorNode], a `PoseNode` is not persisted across sessions — it follows the given
     * pose directly. It is useful for placing temporary indicators or hit-test results.
     *
     * @param pose                        The world-space [Pose] to track.
     * @param visibleCameraTrackingStates States in which the node is visible based on camera tracking.
     * @param onPoseChanged               Callback invoked when the pose changes.
     * @param apply                       Additional imperative configuration on the [PoseNodeImpl].
     * @param content                     Optional child nodes declared in a [NodeScope].
     */
    @Composable
    fun PoseNode(
        pose: Pose = Pose.IDENTITY,
        visibleCameraTrackingStates: Set<TrackingState> = setOf(TrackingState.TRACKING),
        onPoseChanged: ((Pose) -> Unit)? = null,
        apply: PoseNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val node = remember(engine) {
            PoseNodeImpl(
                engine = engine,
                pose = pose,
                onPoseChanged = onPoseChanged
            ).apply {
                this.visibleCameraTrackingStates = visibleCameraTrackingStates
                apply()
            }
        }
        SideEffect {
            node.pose = pose
            node.visibleCameraTrackingStates = visibleCameraTrackingStates
            node.onPoseChanged = onPoseChanged
        }
        NodeLifecycle(node, content)
    }

    // ── HitResultNode ─────────────────────────────────────────────────────────────────────────────

    /**
     * A node that follows real-time AR hit-test results at the given view coordinates.
     *
     * On each [Frame] update, the node performs a hit test at ([xPx], [yPx]) in view space and
     * moves to the intersection with detected scene geometry. Useful for placement cursors or
     * interactive positioning UIs.
     *
     * ```kotlin
     * ARSceneView {
     *     HitResultNode(xPx = viewWidth / 2f, yPx = viewHeight / 2f) {
     *         CubeNode(size = Float3(0.05f))
     *     }
     * }
     * ```
     *
     * **Defaults are plane-only ([#1891](https://github.com/sceneview/sceneview/issues/1891)).**
     * [point], [depthPoint], and [instantPlacementPoint] all default to `false` because
     * depth / feature-point hits before motion-stereo convergence return positions
     * extremely close to the camera (often <10 cm), which causes a child placement disc
     * to render as a fullscreen overlay that blanks the camera feed on session start. Opt
     * each filter back in explicitly once your scene is tracking-stable.
     *
     * [minCameraDistance] is an additional defensive floor (meters) — hits closer than this
     * are dropped, so even a re-enabled depth/point filter can't snap to the lens. Defaults
     * to `0.3f` (30 cm); pass `null` to disable.
     *
     * @param xPx                       View X coordinate in pixels for the hit test.
     * @param yPx                       View Y coordinate in pixels for the hit test.
     * @param planeTypes                Which plane types to include in results.
     * @param point                     Include [Point] trackable results. Default `false` (#1891).
     * @param depthPoint                Include depth-based hit results. Default `false` (#1891).
     * @param instantPlacementPoint     Include instant placement results. Default `false` (#1891).
     * @param trackingStates            Only accept results where the trackable has these states.
     * @param pointOrientationModes     Filter by point orientation mode.
     * @param planePoseInPolygon        Require the pose to lie inside the plane polygon.
     * @param minCameraDistance         Camera-to-hit floor (meters). Default `0.3f`; `null` to disable.
     * @param minCameraDistanceFromPlane Legacy plane-only distance gate. Prefer [minCameraDistance].
     * @param predicate                 Custom filter applied to each [HitResult].
     * @param apply                     Additional imperative configuration on [HitResultNodeImpl].
     * @param content                   Optional child nodes declared in a [NodeScope].
     */
    @Composable
    fun HitResultNode(
        xPx: Float,
        yPx: Float,
        planeTypes: Set<Plane.Type> = Plane.Type.entries.toSet(),
        point: Boolean = false,
        depthPoint: Boolean = false,
        instantPlacementPoint: Boolean = false,
        trackingStates: Set<TrackingState> = setOf(TrackingState.TRACKING),
        pointOrientationModes: Set<Point.OrientationMode> = setOf(
            Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
        ),
        planePoseInPolygon: Boolean = true,
        minCameraDistance: Float? = 0.3f,
        minCameraDistanceFromPlane: Pair<Camera, Float>? = null,
        predicate: ((HitResult) -> Boolean)? = null,
        apply: HitResultNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val node = remember(engine, xPx, yPx) {
            HitResultNodeImpl(
                engine = engine,
                xPx = xPx,
                yPx = yPx,
                planeTypes = planeTypes,
                point = point,
                depthPoint = depthPoint,
                instantPlacementPoint = instantPlacementPoint,
                trackingStates = trackingStates,
                pointOrientationModes = pointOrientationModes,
                planePoseInPolygon = planePoseInPolygon,
                minCameraDistance = minCameraDistance,
                minCameraDistanceFromPlane = minCameraDistanceFromPlane,
                predicate = predicate
            ).apply(apply)
        }
        NodeLifecycle(node, content)
    }

    /**
     * A node that follows a custom real-time AR hit-test.
     *
     * Provide your own [hitTest] lambda for full control over which [HitResult] the node follows.
     *
     * @param hitTest   Invoked each frame with the current [Frame]; return the [HitResult] to
     *                  follow or `null` to keep the last known pose.
     * @param apply     Additional imperative configuration on [HitResultNodeImpl].
     * @param content   Optional child nodes declared in a [NodeScope].
     */
    @Composable
    fun HitResultNode(
        hitTest: HitResultNodeImpl.(Frame) -> HitResult?,
        apply: HitResultNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val node = remember(engine) {
            HitResultNodeImpl(engine = engine, hitTest = hitTest).apply(apply)
        }
        NodeLifecycle(node, content)
    }

    // ── DepthHitResultNode ────────────────────────────────────────────────────────────────────────

    /**
     * A node that follows real-time **depth-based** AR hit-test results at the given view
     * coordinates — Compose-idiomatic mirror of [HitResultNode] for placement against arbitrary
     * real-world geometry rather than against detected planes / points (#1814).
     *
     * On each [Frame] update, the node performs a depth hit test at ([xPx], [yPx]) in view space
     * via [io.github.sceneview.ar.arcore.hitTestDepth] and moves to the world-space surface point
     * under that pixel. Unlike [HitResultNode], a depth hit test resolves a point on *any* visible
     * geometry the depth camera can see (sofa, slope, cluttered desk) without waiting for ARCore
     * to grow a plane there, and the underlying [io.github.sceneview.ar.arcore.DepthHitResult]
     * carries a real surface normal — read it via [DepthHitResultNodeImpl.depthHitResult] if you
     * want to align the placed object with the surface orientation.
     *
     * Requires the session depth mode set to [com.google.ar.core.Config.DepthMode.AUTOMATIC] or
     * [com.google.ar.core.Config.DepthMode.RAW_DEPTH_ONLY]. When depth is unavailable, the node
     * keeps its last known pose (same fallback contract as [HitResultNode]).
     *
     * ```kotlin
     * ARSceneView(
     *     sessionConfiguration = { _, config ->
     *         config.depthMode = Config.DepthMode.AUTOMATIC
     *     }
     * ) {
     *     DepthHitResultNode(xPx = viewWidth / 2f, yPx = viewHeight / 2f) {
     *         CubeNode(size = Float3(0.05f))
     *     }
     * }
     * ```
     *
     * @param xPx       View X coordinate in pixels for the depth hit test.
     * @param yPx       View Y coordinate in pixels for the depth hit test.
     * @param apply     Additional imperative configuration on the underlying [DepthHitResultNodeImpl].
     * @param content   Optional child nodes declared in a [NodeScope].
     */
    @Composable
    fun DepthHitResultNode(
        xPx: Float,
        yPx: Float,
        apply: DepthHitResultNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null,
    ) {
        val node = remember(engine, xPx, yPx) {
            DepthHitResultNodeImpl(engine = engine, xPx = xPx, yPx = yPx).apply(apply)
        }
        NodeLifecycle(node, content)
    }

    /**
     * A node that follows depth-based AR hit-test results computed by a caller-supplied
     * [hitTest] lambda — lambda-overload sibling of the screen-pixel composable above
     * (#1844). Mirrors the 2-overload surface that [HitResultNode] already exposes
     * (`xPx/yPx` + custom-lambda) so apps can sample multiple pixels, raycast at a moving
     * reticle, or skip frames cheaply without subclassing [DepthHitResultNodeImpl].
     *
     * The [hitTest] lambda runs on the AR frame thread once per frame (when `update` is true)
     * and is expected to return `null` to keep the previous pose — same fallback semantics as
     * the screen-pixel overload.
     *
     * ```kotlin
     * DepthHitResultNode(
     *     hitTest = { frame ->
     *         // Sample a 3×3 grid and return the closest valid hit
     *         (-1..1).flatMap { dx -> (-1..1).map { dy -> dx to dy } }
     *             .mapNotNull { (dx, dy) -> frame.hitTestDepth(cx + dx * 8f, cy + dy * 8f) }
     *             .minByOrNull { it.distance }
     *     },
     * ) {
     *     CubeNode(size = Float3(0.05f))
     * }
     * ```
     *
     * @param hitTest Selector — receives the live [Frame] and returns a depth hit, or `null`.
     * @param apply   Additional imperative configuration on the underlying node.
     * @param content Optional child nodes declared in a [NodeScope].
     */
    @Composable
    fun DepthHitResultNode(
        hitTest: DepthHitResultNodeImpl.(Frame) -> io.github.sceneview.ar.arcore.DepthHitResult?,
        apply: DepthHitResultNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null,
    ) {
        val node = remember(engine) {
            DepthHitResultNodeImpl(engine = engine, hitTest = hitTest).apply(apply)
        }
        NodeLifecycle(node, content)
    }

    // ── AugmentedImageNode ────────────────────────────────────────────────────────────────────────

    /**
     * A node that tracks a detected [AugmentedImage] in the camera feed.
     *
     * The node's pose is updated to match the image's center pose while it is being tracked.
     * Optionally scales the node to match the physical image's real-world dimensions.
     *
     * Usage — show content over a magazine cover:
     * ```kotlin
     * ARSceneView(
     *     sessionConfiguration = { session, config ->
     *         config.augmentedImageDatabase = AugmentedImageDatabase(session).also { db ->
     *             db.addImage("cover", coverBitmap)
     *         }
     *     },
     *     onSessionUpdated = { _, frame ->
     *         frame.getUpdatedTrackables(AugmentedImage::class.java).forEach { image ->
     *             if (image.trackingState == TrackingState.TRACKING) detectedImages += image
     *         }
     *     }
     * ) {
     *     detectedImages.forEach { image ->
     *         AugmentedImageNode(augmentedImage = image) {
     *             ModelNode(modelInstance = rememberModelInstance(modelLoader, "drone.glb"))
     *         }
     *     }
     * }
     * ```
     *
     * @param augmentedImage            The ARCore [AugmentedImage] to track.
     * @param applyImageScale           If `true`, scales the node to match the image's physical size.
     * @param visibleTrackingMethods    Tracking methods for which the node is visible.
     * @param onTrackingStateChanged    Callback when tracking state changes.
     * @param onTrackingMethodChanged   Callback when the tracking method changes.
     * @param onUpdated                 Callback invoked each frame while the image is updated.
     * @param apply                     Additional imperative configuration.
     * @param content                   Optional child nodes declared in a [NodeScope].
     */
    @Composable
    fun AugmentedImageNode(
        augmentedImage: AugmentedImage,
        applyImageScale: Boolean = false,
        visibleTrackingMethods: Set<TrackingMethod> = setOf(
            TrackingMethod.FULL_TRACKING, TrackingMethod.LAST_KNOWN_POSE
        ),
        onTrackingStateChanged: ((TrackingState) -> Unit)? = null,
        onTrackingMethodChanged: ((TrackingMethod) -> Unit)? = null,
        onUpdated: ((AugmentedImage) -> Unit)? = null,
        apply: AugmentedImageNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val node = remember(engine, augmentedImage) {
            AugmentedImageNodeImpl(
                engine = engine,
                augmentedImage = augmentedImage,
                applyImageScale = applyImageScale,
                visibleTrackingMethods = visibleTrackingMethods,
                onTrackingStateChanged = onTrackingStateChanged,
                onTrackingMethodChanged = onTrackingMethodChanged,
                onUpdated = onUpdated
            ).apply(apply)
        }
        SideEffect {
            node.applyImageScale = applyImageScale
            node.onTrackingStateChanged = onTrackingStateChanged
            node.onUpdated = onUpdated
        }
        NodeLifecycle(node, content)
    }

    // ── AugmentedFaceNode ─────────────────────────────────────────────────────────────────────────

    /**
     * A node that renders a 3D mesh aligned to a detected [AugmentedFace].
     *
     * Automatically updates the face mesh vertices and region poses each frame while
     * [AugmentedFace] tracking is active. Requires the session to be configured with
     * `AugmentedFaceMode.MESH3D` and the front camera.
     *
     * **Both `sessionFeatures` and `sessionCameraConfig` must be set.** `FRONT_CAMERA` only
     * makes the front camera eligible; the session stays on the default BACK camera until
     * `sessionCameraConfig = ::frontCameraConfig` actually selects a FRONT-facing config —
     * without it `AugmentedFaceMode.MESH3D` yields no trackables and no mesh ever appears.
     *
     * ```kotlin
     * ARSceneView(
     *     sessionFeatures = setOf(Session.Feature.FRONT_CAMERA),
     *     sessionCameraConfig = ::frontCameraConfig,
     *     sessionConfiguration = { _, config ->
     *         config.augmentedFaceMode = Config.AugmentedFaceMode.MESH3D
     *     },
     *     onSessionUpdated = { session, _ ->
     *         detectedFaces = session.getAllTrackables(AugmentedFace::class.java)
     *             .filter { it.trackingState == TrackingState.TRACKING }
     *     }
     * ) {
     *     detectedFaces.forEach { face ->
     *         AugmentedFaceNode(augmentedFace = face, meshMaterialInstance = faceMaterial)
     *     }
     * }
     * ```
     *
     * @param augmentedFace         The ARCore [AugmentedFace] to render.
     * @param meshMaterialInstance  Optional material applied to the face mesh.
     * @param computeTangents       Whether to compute and upload per-vertex tangent
     *                              quaternions every frame. Required by PBR (lit) materials;
     *                              **set `false` when [meshMaterialInstance] is unlit**
     *                              (e.g. `materialLoader.createUnlitColorInstance(...)`)
     *                              to skip ~30 Hz of pure-waste Mikkelsen compute + JNI
     *                              roundtrip + buffer upload. Default `true` (#878 audit).
     * @param onTrackingStateChanged Callback when tracking state changes.
     * @param onUpdated             Callback invoked each frame while the face is updated.
     * @param apply                 Additional imperative configuration.
     * @param content               Optional child nodes declared in a [NodeScope].
     */
    @Composable
    fun AugmentedFaceNode(
        augmentedFace: AugmentedFace,
        meshMaterialInstance: MaterialInstance? = null,
        computeTangents: Boolean = true,
        onTrackingStateChanged: ((TrackingState) -> Unit)? = null,
        onUpdated: ((AugmentedFace) -> Unit)? = null,
        apply: AugmentedFaceNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val node = remember(engine, augmentedFace, computeTangents) {
            AugmentedFaceNodeImpl(
                engine = engine,
                augmentedFace = augmentedFace,
                meshMaterialInstance = meshMaterialInstance,
                computeTangents = computeTangents,
                onTrackingStateChanged = onTrackingStateChanged,
                onUpdated = onUpdated
            ).apply(apply)
        }
        SideEffect {
            node.onTrackingStateChanged = onTrackingStateChanged
            node.onUpdated = onUpdated
        }
        NodeLifecycle(node, content)
    }

    // ── CloudAnchorNode ───────────────────────────────────────────────────────────────────────────

    /**
     * A node that tracks an ARCore Cloud Anchor, enabling persistent AR experiences across devices
     * and sessions.
     *
     * After placing the node, call [CloudAnchorNodeImpl.host] to upload the anchor to the
     * Google Cloud ARCore API and receive a persistent cloud anchor ID.
     * To resolve a previously hosted anchor by ID, use [CloudAnchorNodeImpl.resolve] companion.
     *
     * ```kotlin
     * var cloudNode: CloudAnchorNode? by remember { mutableStateOf(null) }
     *
     * ARSceneView {
     *     cloudNode?.let { node ->
     *         // The node is already created; just add children
     *     }
     * }
     *
     * // Resolve a previously hosted anchor
     * LaunchedEffect(session) {
     *     CloudAnchorNode.resolve(engine, session, "ua-...") { state, node ->
     *         cloudNode = node
     *     }
     * }
     * ```
     *
     * @param anchor                  The local [Anchor] to associate with a cloud anchor.
     * @param cloudAnchorId           The cloud anchor ID if already resolved; `null` when hosting.
     * @param onTrackingStateChanged  Callback when tracking state changes.
     * @param onUpdated               Callback invoked each frame while the anchor is updated.
     * @param onHosted                Callback invoked when cloud hosting completes (success or fail).
     * @param apply                   Additional imperative configuration.
     * @param content                 Optional child nodes declared in a [NodeScope].
     */
    @Composable
    fun CloudAnchorNode(
        anchor: Anchor,
        cloudAnchorId: String? = null,
        onTrackingStateChanged: ((TrackingState) -> Unit)? = null,
        onUpdated: ((Anchor?) -> Unit)? = null,
        onHosted: ((cloudAnchorId: String?, state: com.google.ar.core.Anchor.CloudAnchorState) -> Unit)? = null,
        apply: CloudAnchorNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val node = remember(engine, anchor) {
            CloudAnchorNodeImpl(
                engine = engine,
                anchor = anchor,
                cloudAnchorId = cloudAnchorId,
                onTrackingStateChanged = onTrackingStateChanged,
                onUpdated = onUpdated,
                onHosted = onHosted
            ).apply(apply)
        }
        SideEffect {
            node.onTrackingStateChanged = onTrackingStateChanged
            node.onUpdated = onUpdated
            node.onHosted = onHosted
        }
        NodeLifecycle(node, content)
    }

    // ── TrackableNode ─────────────────────────────────────────────────────────────────────────────

    /**
     * A generic node that tracks any ARCore [Trackable].
     *
     * The node is only visible while the trackable's state is within [visibleTrackingStates].
     * Useful for custom trackable types or when only the base tracking behavior is needed.
     *
     * @param trackable               The [Trackable] to follow.
     * @param visibleTrackingStates   States in which the node is rendered.
     * @param onTrackingStateChanged  Callback when tracking state changes.
     * @param onUpdated               Callback invoked each frame while the trackable is updated.
     * @param apply                   Additional imperative configuration.
     * @param content                 Optional child nodes declared in a [NodeScope].
     */
    @Composable
    fun <T : Trackable> TrackableNode(
        trackable: T,
        visibleTrackingStates: Set<TrackingState> = setOf(TrackingState.TRACKING),
        onTrackingStateChanged: ((TrackingState) -> Unit)? = null,
        onUpdated: ((T) -> Unit)? = null,
        apply: TrackableNodeImpl<T>.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val node = remember(engine, trackable) {
            TrackableNodeImpl<T>(
                engine = engine,
                visibleTrackingStates = visibleTrackingStates,
                onTrackingStateChanged = onTrackingStateChanged,
                onUpdated = onUpdated
            ).apply {
                this.trackable = trackable
                apply()
            }
        }
        SideEffect {
            node.trackable = trackable
            node.onTrackingStateChanged = onTrackingStateChanged
            node.onUpdated = onUpdated
        }
        NodeLifecycle(node, content)
    }

    // ── StreetscapeGeometryNode ───────────────────────────────────────────────────────────────────

    /**
     * A node that renders a [StreetscapeGeometry] mesh from the ARCore Geospatial Streetscape API.
     *
     * Requires `Config.StreetscapeGeometryMode.ENABLED` and `Config.GeospatialMode.ENABLED` to be
     * set in the ARCore session config. Obtain streetscape geometry from
     * [Frame.getUpdatedTrackables].
     *
     * ```kotlin
     * ARSceneView(
     *     sessionConfiguration = { _, config ->
     *         config.geospatialMode = Config.GeospatialMode.ENABLED
     *         config.streetscapeGeometryMode = Config.StreetscapeGeometryMode.ENABLED
     *     },
     *     onSessionUpdated = { _, frame ->
     *         geometries = frame.getUpdatedTrackables(StreetscapeGeometry::class.java).toList()
     *     }
     * ) {
     *     geometries.forEach { geo ->
     *         // Render only buildings, and only the higher-LOD ones:
     *         StreetscapeGeometryNode(
     *             streetscapeGeometry = geo,
     *             types = setOf(StreetscapeGeometry.Type.BUILDING),
     *             minQuality = StreetscapeGeometry.Quality.BUILDING_LOD_2,
     *             meshMaterialInstance = buildingMat
     *         )
     *     }
     * }
     * ```
     *
     * @param streetscapeGeometry     The [StreetscapeGeometry] mesh to render.
     * @param types                   Filter — only geometries whose [StreetscapeGeometry.getType]
     *                                is in this set render (#1772). Defaults to
     *                                `setOf(BUILDING, TERRAIN)` (no filtering). Set to
     *                                `setOf(BUILDING)` to drop the (often noisy) ground terrain
     *                                in dense urban scenes.
     * @param minQuality              Filter — geometries with a [StreetscapeGeometry.getQuality]
     *                                whose ordinal is lower than this enum's ordinal are skipped
     *                                (#1772). Default `Quality.NONE` (no filtering). Set to
     *                                `BUILDING_LOD_2` to render only the higher-LOD buildings and
     *                                save the frame-rate cliff on low-end devices. The ordering
     *                                used here is ARCore's declaration order:
     *                                `NONE < BUILDING_LOD_1 < BUILDING_LOD_2`.
     * @param meshMaterialInstance    Optional material applied to the geometry mesh.
     * @param onTrackingStateChanged  Callback when tracking state changes.
     * @param onUpdated               Callback invoked each frame while the geometry is updated.
     * @param apply                   Additional imperative configuration.
     * @param content                 Optional child nodes declared in a [NodeScope].
     */
    @Composable
    fun StreetscapeGeometryNode(
        streetscapeGeometry: StreetscapeGeometry,
        types: Set<StreetscapeGeometry.Type> = setOf(
            StreetscapeGeometry.Type.BUILDING,
            StreetscapeGeometry.Type.TERRAIN
        ),
        minQuality: StreetscapeGeometry.Quality = StreetscapeGeometry.Quality.NONE,
        meshMaterialInstance: MaterialInstance? = null,
        onTrackingStateChanged: ((TrackingState) -> Unit)? = null,
        onUpdated: ((StreetscapeGeometry) -> Unit)? = null,
        apply: StreetscapeGeometryNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        // No-op composable when the geometry doesn't pass the filter (#1772). Logic centralised
        // in [streetscapeGeometryPasses] so it's exercised by pure-JVM unit tests.
        if (!streetscapeGeometryPasses(streetscapeGeometry, types, minQuality)) return

        val node = remember(engine, streetscapeGeometry) {
            StreetscapeGeometryNodeImpl(
                engine = engine,
                streetscapeGeometry = streetscapeGeometry,
                meshMaterialInstance = meshMaterialInstance,
                onTrackingStateChanged = onTrackingStateChanged,
                onUpdated = onUpdated
            ).apply(apply)
        }
        SideEffect {
            node.onTrackingStateChanged = onTrackingStateChanged
            node.onUpdated = onUpdated
        }
        NodeLifecycle(node, content)
    }

    // ── TerrainAnchorNode ─────────────────────────────────────────────────────────────────────────

    /**
     * A composable wrapper for an already-resolved [TerrainAnchorNode][TerrainAnchorNodeImpl].
     *
     * **Resolution stays imperative.** ARCore's `TerrainAnchorNode.resolve(...)` is async and
     * may take seconds (it calls Google Cloud's VPS service). Wrap that call in a
     * `LaunchedEffect` keyed on the (lat, lng, altitude) triple, then pass the resolved
     * instance to this composable so it lives inside the scene tree like any other node:
     *
     * ```kotlin
     * val anchorNodes = remember { mutableStateListOf<TerrainAnchorNode>() }
     *
     * ARSceneView(
     *     sessionConfiguration = { _, config -> config.geospatialMode = Config.GeospatialMode.ENABLED },
     *     onSessionUpdated = { session, _ ->
     *         val earth = session.earth ?: return@ARSceneView
     *         if (earth.trackingState == TrackingState.TRACKING && anchorNodes.isEmpty()) {
     *             TerrainAnchorNode.resolve(
     *                 engine = engine,
     *                 earth = earth,
     *                 latitude = 48.8584, longitude = 2.2945, altitudeAboveTerrain = 1.5,
     *                 eusQuaternion = Quaternion(),
     *             ) { state, anchorNode ->
     *                 if (state == Anchor.TerrainAnchorState.SUCCESS && anchorNode != null) {
     *                     anchorNodes += anchorNode
     *                 }
     *             }
     *         }
     *     }
     * ) {
     *     anchorNodes.forEach { TerrainAnchorNode(node = it) {
     *         ModelNode(modelInstance = signpost, scaleToUnits = 0.5f)
     *     } }
     * }
     * ```
     *
     * Requires `Config.GeospatialMode.ENABLED`, ARCore Cloud API key, ACCESS_FINE_LOCATION,
     * internet, and outdoor VPS coverage. 100-anchor cap (Terrain + Rooftop combined).
     *
     * @param node      The resolved [TerrainAnchorNodeImpl] returned by `TerrainAnchorNode.resolve(...)`.
     * @param apply     Additional imperative configuration applied once on first composition.
     * @param content   Optional child nodes declared in a [NodeScope].
     */
    @Composable
    fun TerrainAnchorNode(
        node: TerrainAnchorNodeImpl,
        apply: TerrainAnchorNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val attached = remember(node) { node.apply(apply) }
        NodeLifecycle(attached, content)
    }

    // ── RooftopAnchorNode ─────────────────────────────────────────────────────────────────────────

    /**
     * A composable wrapper for an already-resolved [RooftopAnchorNode][RooftopAnchorNodeImpl].
     *
     * Same usage pattern as [TerrainAnchorNode] — call `RooftopAnchorNode.resolve(...)`
     * imperatively (async, returns a `Future`), then pass the resolved instance here so it
     * participates in the scene tree. The altitude argument is interpreted relative to the
     * rooftop of the building at the given lat/lng, falling back to terrain altitude when
     * no building is detected.
     *
     * Requires `Config.GeospatialMode.ENABLED`, ARCore Cloud API key, ACCESS_FINE_LOCATION,
     * internet, and outdoor VPS coverage. 100-anchor cap (Terrain + Rooftop combined).
     *
     * @param node      The resolved [RooftopAnchorNodeImpl] returned by `RooftopAnchorNode.resolve(...)`.
     * @param apply     Additional imperative configuration applied once on first composition.
     * @param content   Optional child nodes declared in a [NodeScope].
     */
    @Composable
    fun RooftopAnchorNode(
        node: RooftopAnchorNodeImpl,
        apply: RooftopAnchorNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val attached = remember(node) { node.apply(apply) }
        NodeLifecycle(attached, content)
    }

    // ── DepthMeshNode ─────────────────────────────────────────────────────────────────────────────

    /**
     * Creates and remembers a [DepthMeshNodeImpl] that reifies the live ARCore environment depth
     * image as a renderable Filament mesh, with edge-discontinuity culling so triangles do not
     * stretch across depth jumps.
     *
     * The returned node is the SceneView equivalent of Google's
     * [arcore-depth-lab](https://github.com/googlesamples/arcore-depth-lab) `ScreenSpaceDepthMesh`.
     * Drop it inside an [ARSceneView] content block to let virtual objects cast shadows onto the
     * real world, to carry a scan/pulse material over real geometry, or to provide the geometry
     * for a depth-driven physics collider ([#1713](https://github.com/sceneview/sceneview/issues/1713)).
     *
     * Requires the session depth mode set to [com.google.ar.core.Config.DepthMode.AUTOMATIC] or
     * [com.google.ar.core.Config.DepthMode.RAW_DEPTH_ONLY] — without it the mesh stays empty.
     *
     * ```kotlin
     * ARSceneView(
     *     sessionConfiguration = { _, config ->
     *         config.depthMode = Config.DepthMode.AUTOMATIC
     *     }
     * ) {
     *     val depthMesh = rememberDepthMesh()                 // 5 Hz refresh
     *     DepthMeshNode(depthMesh)                            // adds it to the scene
     * }
     * ```
     *
     * @param refreshIntervalMs    Minimum interval, in ms, between two mesh rebuilds (default 200 ms).
     * @param stride               Pixel stride between depth samples used as vertices (default 4).
     *                             Higher = coarser mesh, faster rebuild.
     * @param edgeThresholdMeters  Depth-jump threshold above which the spanning triangle is culled
     *                             (default 0.10 m).
     * @param materialInstance     Optional material applied to the mesh. Defaults to Filament's
     *                             basic material. A transparent shadow-receiver material is a
     *                             common choice.
     * @param onMeshRebuilt        Invoked on each rebuild with the freshly-computed
     *                             [DepthMeshSnapshot]. Use this to feed a downstream consumer
     *                             (physics collider, point-cloud exporter, debug overlay).
     */
    @Composable
    fun rememberDepthMesh(
        refreshIntervalMs: Long = DepthMeshNodeImpl.DEFAULT_REFRESH_INTERVAL_MS,
        stride: Int = io.github.sceneview.ar.arcore.DEFAULT_DEPTH_MESH_STRIDE,
        edgeThresholdMeters: Float =
            io.github.sceneview.ar.arcore.DEFAULT_DEPTH_EDGE_THRESHOLD_METERS,
        materialInstance: MaterialInstance? = null,
        builder: RenderableManager.Builder.() -> Unit = {},
        onMeshRebuilt: ((DepthMeshSnapshot) -> Unit)? = null,
    ): DepthMeshNodeImpl {
        val node = remember(engine) {
            DepthMeshNodeImpl(
                engine = engine,
                refreshIntervalMs = refreshIntervalMs,
                stride = stride,
                edgeThresholdMeters = edgeThresholdMeters,
                materialInstance = materialInstance,
                builder = builder,
                onMeshRebuilt = onMeshRebuilt,
            )
        }
        // Keep the live params in sync so a recomposition with new arguments rebinds without
        // recreating the underlying Filament buffers.
        SideEffect {
            node.refreshIntervalMs = refreshIntervalMs
            node.stride = stride
            node.edgeThresholdMeters = edgeThresholdMeters
            node.onMeshRebuilt = onMeshRebuilt
        }
        DisposableEffect(node) {
            onDispose {
                // Detaches the node from its parent and frees the entity + owned buffers. The
                // base ARSceneScope's nodeRemover already removes it from Filament's scene; this
                // covers the case where the caller held the node outside the composition.
                node.destroy()
            }
        }
        return node
    }

    /**
     * Adds a [DepthMeshNodeImpl] returned by [rememberDepthMesh] to the AR scene.
     *
     * Splitting the factory ([rememberDepthMesh]) from the scene-attach composable lets the
     * caller hold the node reference (to read [DepthMeshNodeImpl.latestSnapshot] for downstream
     * consumers like the #1713 physics collider) while still benefiting from the standard
     * lifecycle attach/detach.
     *
     * @param node      The depth mesh node returned by [rememberDepthMesh].
     * @param apply     Imperative configuration applied once on first composition.
     * @param content   Optional child nodes declared in a [NodeScope].
     */
    @Composable
    fun DepthMeshNode(
        node: DepthMeshNodeImpl,
        apply: DepthMeshNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null,
    ) {
        val attached = remember(node) { node.apply(apply) }
        NodeLifecycle(attached, content)
    }

    // ── DepthCollider ─────────────────────────────────────────────────────────────────────────────

    /**
     * Creates and remembers a [DepthCollider] (#1713) — a static physics collider built from the
     * live ARCore environment depth mesh.
     *
     * Virtual rigid bodies driven by [io.github.sceneview.node.PhysicsNode] can pass this collider
     * as their `floorProvider` to bounce off the **real** floor, table or wall instead of the
     * default static `floorY` plane. The collider is the SceneView equivalent of Google's
     * [arcore-depth-lab](https://github.com/googlesamples/arcore-depth-lab) `Collider` scene.
     *
     * Under the hood the collider is a thin wrapper over a hidden [DepthMeshNodeImpl] (same
     * primitive as [rememberDepthMesh]): each rebuild's vertex/index buffers are converted to
     * world-space triangles once, then per-frame, per-body lookups are cheap point-vs-triangle
     * math. Edge-discontinuity culling is inherited from the mesh — the collider does not stretch
     * "curtain" surfaces across depth jumps. The hidden mesh is **not rendered** (it carries no
     * material instance and is filtered out of all layer masks); set [renderMesh] to `true` if
     * you also want a visible depth surface (e.g. for debugging or shadow-receiver overlays).
     *
     * Requires the session depth mode set to [com.google.ar.core.Config.DepthMode.AUTOMATIC] or
     * [com.google.ar.core.Config.DepthMode.RAW_DEPTH_ONLY]. Without it the collider stays empty
     * and [io.github.sceneview.node.PhysicsBody] falls back to its static floor.
     *
     * ```kotlin
     * ARSceneView(
     *     sessionConfiguration = { _, config ->
     *         config.depthMode = Config.DepthMode.AUTOMATIC
     *     }
     * ) {
     *     val depthCollider = rememberDepthCollider(refreshIntervalMs = 200L)
     *     PhysicsNode(
     *         node = ballNode,
     *         restitution = 0.7f,
     *         radius = 0.05f,
     *         floorProvider = depthCollider,
     *     )
     * }
     * ```
     *
     * @param refreshIntervalMs    Minimum interval, in ms, between two mesh rebuilds (default 200
     *                             ms = 5 Hz). Acceptance from #1713: "rebuild cost measured;
     *                             refresh interval keeps the frame budget green".
     * @param stride               Pixel stride between depth samples used as vertices. Default is
     *                             the same as [rememberDepthMesh].
     * @param edgeThresholdMeters  Depth-jump threshold above which the spanning triangle is culled.
     *                             Default matches the rendered depth mesh.
     * @param renderMesh           When `true`, the underlying [DepthMeshNodeImpl] is also rendered
     *                             (useful when you want to debug the collider visually, or to layer
     *                             a shadow-receiver material on top of the same geometry). When
     *                             `false` (default) the collider operates invisibly.
     */
    @Composable
    fun rememberDepthCollider(
        refreshIntervalMs: Long = DepthMeshNodeImpl.DEFAULT_REFRESH_INTERVAL_MS,
        stride: Int = io.github.sceneview.ar.arcore.DEFAULT_DEPTH_MESH_STRIDE,
        edgeThresholdMeters: Float =
            io.github.sceneview.ar.arcore.DEFAULT_DEPTH_EDGE_THRESHOLD_METERS,
        renderMesh: Boolean = false,
    ): DepthCollider {
        // Reuse rememberDepthMesh so the mesh node is fully lifecycle-managed by the existing
        // path (DisposableEffect destroys it on leave, ARScene's per-frame `update` call already
        // walks `childNodes.filterIsInstance<DepthMeshNode>()`).
        val meshNode = rememberDepthMesh(
            refreshIntervalMs = refreshIntervalMs,
            stride = stride,
            edgeThresholdMeters = edgeThresholdMeters,
        )

        val collider = remember(meshNode) { DepthCollider(meshNode) }

        // Attach the mesh node to the scene so ARScene's per-frame iteration picks it up. When
        // the caller doesn't want a visible mesh (default), apply a no-render layer so Filament
        // skips rasterising it but the AR frame thread still feeds it depth images. With layer
        // 0xFE we keep the renderable in the scene (so update() still fires) but exclude it from
        // both the default and IBL layers — the AR scene view's view layer masks all use the
        // lower nibble.
        DepthMeshNode(
            node = meshNode,
            apply = {
                if (!renderMesh) {
                    // Layer-mask trick: setLayerMask doesn't exist on the node itself but the
                    // underlying RenderableManager does. The next rebuild will overwrite the
                    // bounding box, but the layer mask survives because it's set via the
                    // renderable instance.
                    renderableManager.setLayerMask(renderableInstance, 0xFF, 0x00)
                }
            },
        )

        DisposableEffect(collider) {
            onDispose { collider.destroy() }
        }
        return collider
    }
}

// ── StreetscapeGeometry filter helpers (#1772) ────────────────────────────────────────────────────

/**
 * Pure-logic gate for [ARSceneScope.StreetscapeGeometryNode]'s `types` + `minQuality` filter
 * (#1772). Reads the underlying [StreetscapeGeometry.getType] / [StreetscapeGeometry.getQuality]
 * once and delegates to the JVM-friendly [streetscapeGeometryPasses] overload.
 *
 * Visible at file scope (not a `companion object` member) so it can be exercised without
 * spinning up the `ARSceneScope` constructor under unit tests.
 */
internal fun streetscapeGeometryPasses(
    streetscapeGeometry: StreetscapeGeometry,
    types: Set<StreetscapeGeometry.Type>,
    minQuality: StreetscapeGeometry.Quality
): Boolean = streetscapeGeometryPasses(
    actualType = streetscapeGeometry.type,
    actualQuality = streetscapeGeometry.quality,
    types = types,
    minQuality = minQuality
)

/**
 * JVM-friendly overload of [streetscapeGeometryPasses] taking the type/quality directly. ARCore's
 * `StreetscapeGeometry` is JNI-only — the constructor takes a native handle and is unmockable
 * under pure-JVM tests. This signature lets the test feed enum values without an instance, while
 * the production overload above is the one called from `ARSceneScope`.
 *
 * Rules:
 *  - The geometry's [StreetscapeGeometry.getType] must be in [types]. An empty set means
 *    "nothing matches" — the composable becomes a no-op for every geometry.
 *  - The geometry's [StreetscapeGeometry.getQuality] ordinal must be >= [minQuality].ordinal.
 *    ARCore declares quality in ascending order
 *    (`NONE < BUILDING_LOD_1 < BUILDING_LOD_2`), so the ordinal comparison maps directly to the
 *    documented "must be at least this good" semantics.
 */
internal fun streetscapeGeometryPasses(
    actualType: StreetscapeGeometry.Type,
    actualQuality: StreetscapeGeometry.Quality,
    types: Set<StreetscapeGeometry.Type>,
    minQuality: StreetscapeGeometry.Quality
): Boolean = actualType in types && actualQuality.ordinal >= minQuality.ordinal

