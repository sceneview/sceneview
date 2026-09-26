package io.github.sceneview.flutter

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import com.google.android.filament.LightManager
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.ar.ARSceneScope
import io.github.sceneview.ar.arcore.getUpdatedPlanes
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.collision.HitResult
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener

/**
 * Flutter plugin entry point for SceneView on Android.
 *
 * Registers two platform view types:
 * - `io.github.sceneview.flutter/sceneview`   -- 3D scene (wraps SceneView Compose)
 * - `io.github.sceneview.flutter/arsceneview` -- AR scene (wraps ARSceneView Compose)
 */
class SceneViewPlugin : FlutterPlugin, ActivityAware {

    private var activity: Activity? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        binding.platformViewRegistry.registerViewFactory(
            "io.github.sceneview.flutter/sceneview",
            SceneViewFactory(binding)
        )
        binding.platformViewRegistry.registerViewFactory(
            "io.github.sceneview.flutter/arsceneview",
            ARSceneViewFactory(binding)
        )
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {}

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
    }
    override fun onDetachedFromActivityForConfigChanges() { activity = null }
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }
    override fun onDetachedFromActivity() { activity = null }
}

// ---------------------------------------------------------------------------
// Model descriptor passed from Dart via method channel
// ---------------------------------------------------------------------------

private data class FlutterModelNode(
    val path: String,
    val position: Position = Position(0f, 0f, 0f),
    val rotation: Rotation = Rotation(0f, 0f, 0f),
    val scale: Float = 1.0f,
    val autoAnimate: Boolean = true
)

// ---------------------------------------------------------------------------
// Geometry & light descriptors passed from Dart via method channel
// ---------------------------------------------------------------------------

/**
 * A procedural geometry node (`cube`/`box`, `sphere`, `cylinder`, `plane`).
 *
 * Mirrors the React Native bridge's `GeometryNodeData` so the Flutter and RN
 * geometry API shapes stay consistent (issue #909).
 */
private data class FlutterGeometryNode(
    val type: String,
    val position: Position = Position(0f, 0f, 0f),
    val rotation: Rotation = Rotation(0f, 0f, 0f),
    val size: Float = 1.0f,
    val color: Int = 0xFF888888.toInt(),
    /**
     * When `true` the material ignores all scene lighting and renders the flat
     * [color] straight to the framebuffer. See the Dart `GeometryNode.unlit`.
     */
    val unlit: Boolean = false,
)

/**
 * A light source (`directional`, `point`, `spot`).
 *
 * Mirrors the React Native bridge's `LightNodeData` (issue #909).
 */
private data class FlutterLightNode(
    val type: String = "directional",
    val intensity: Float = 100_000f,
    val color: Int = 0xFFFFFFFF.toInt(),
    val position: Position = Position(0f, 4f, 0f),
)

/** Parses the `addGeometry` method-channel arguments into a [FlutterGeometryNode]. */
private fun parseGeometryNode(call: MethodCall): FlutterGeometryNode = FlutterGeometryNode(
    type = call.argument<String>("type") ?: "cube",
    position = Position(
        call.argument<Double>("x")?.toFloat() ?: 0f,
        call.argument<Double>("y")?.toFloat() ?: 0f,
        call.argument<Double>("z")?.toFloat() ?: 0f,
    ),
    size = call.argument<Double>("size")?.toFloat() ?: 1.0f,
    color = call.argument<Number>("color")?.toInt() ?: 0xFF888888.toInt(),
    unlit = call.argument<Boolean>("unlit") ?: false,
)

/** Parses the `addLight` method-channel arguments into a [FlutterLightNode]. */
private fun parseLightNode(call: MethodCall): FlutterLightNode = FlutterLightNode(
    type = call.argument<String>("type") ?: "directional",
    intensity = call.argument<Double>("intensity")?.toFloat() ?: 100_000f,
    color = call.argument<Number>("color")?.toInt() ?: 0xFFFFFFFF.toInt(),
    position = Position(
        call.argument<Double>("x")?.toFloat() ?: 0f,
        call.argument<Double>("y")?.toFloat() ?: 4f,
        call.argument<Double>("z")?.toFloat() ?: 0f,
    ),
)

/**
 * The path part of [source] when it is a URL, [source] unchanged otherwise.
 *
 * Taking the last `/`-separated segment of a raw URL lands on the AUTHORITY
 * whenever the URL has no path, and an authority may carry userinfo:
 * `https://user:pa55w0rd@cdn.example` yields `user:pa55w0rd@cdn`. An app that
 * builds a signed CDN URL would then put its credentials one tap away from the
 * Dart payload (#3071). Reducing to the path first makes that structural
 * instead of depending on the URL happening to have a path.
 *
 * Returns `""` for a path-less URL: there is no file name in
 * `https://cdn.example` to report, and the caller's [tapNodeName] fallback is
 * the honest answer.
 *
 * Mirrored verbatim in the React Native bridge's `SceneViewManager.kt`. The two
 * bridges are separately published packages with no shared Kotlin, so the
 * duplication is the seam, not an oversight — both sides are pinned by their
 * own unit tests.
 */
private fun urlPathOf(source: String): String {
    val schemeEnd = source.indexOf("://")
    if (schemeEnd < 0) return source
    // A "://" that appears after a slash is not a scheme delimiter — the string
    // is already a path (`models/odd://name.glb`) and cutting at it would drop
    // real path segments.
    val firstSlash = source.indexOf('/')
    if (firstSlash in 0 until schemeEnd) return source
    val afterScheme = source.substring(schemeEnd + 3)
    val pathStart = afterScheme.indexOf('/')
    return if (pathStart < 0) "" else afterScheme.substring(pathStart)
}

/**
 * The name reported to Dart as the tap payload: the model file's base name
 * without extension, identical to what the iOS bridge derives.
 *
 * A model path may be a URL — `ModelLoader.loadModel` loads `https://` sources
 * — so the query and fragment are stripped FIRST. Cutting at the last `.` on a
 * raw URL only strips the extension when it is the last dot in the whole
 * string: `https://cdn/robot.glb?sig=SIG&v=1.2` would otherwise yield
 * `robot.glb?sig=SIG&v=1`, leaking a CDN signature into a payload apps put in
 * labels and analytics events (PR #3037). [urlPathOf] then drops the authority,
 * which carries the other half of the same exposure (#3071).
 *
 * [fallback] keeps a path with no usable base name from reporting "".
 */
internal fun tapNodeName(path: String, fallback: String): String =
    urlPathOf(path.substringBefore('?').substringBefore('#'))
        .substringAfterLast('/').substringBeforeLast('.')
        .ifEmpty { fallback }

/**
 * The APK asset path of a Dart asset key: Flutter packs `environments/x.hdr` as
 * `flutter_assets/environments/x.hdr`, so opening the key as-is threw
 * `FileNotFoundException` and crashed the demo at launch (#3928). For `loadModel`,
 * the documented `models/x.glb` key failed the same way, but silently: the asset read
 * is wrapped in `runCatching`, so no model appeared and nothing was logged. A path
 * that is not a Flutter asset (a native Android asset, a file, a URL) is returned
 * unchanged.
 */
private fun FlutterPlugin.FlutterPluginBinding.assetPathOf(path: String): String {
    if (path.contains("://") || path.startsWith("/")) return path
    val flutterPath = flutterAssets.getAssetFilePathByName(path)
    val packed = runCatching { applicationContext.assets.open(flutterPath).close() }.isSuccess
    return if (packed) flutterPath else path
}

// ---------------------------------------------------------------------------
// 3D SceneView
// ---------------------------------------------------------------------------

class SceneViewFactory(
    private val binding: FlutterPlugin.FlutterPluginBinding
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        val params = args as? Map<String, Any?> ?: emptyMap()
        return SceneViewPlatformView(context, viewId, params, binding)
    }
}

class SceneViewPlatformView(
    private val context: Context,
    private val viewId: Int,
    private val params: Map<String, Any?>,
    private val binding: FlutterPlugin.FlutterPluginBinding,
) : PlatformView, MethodChannel.MethodCallHandler {

    private val channel = MethodChannel(
        binding.binaryMessenger,
        "io.github.sceneview.flutter/scene_$viewId"
    )

    // Reactive state for Compose -- updated via method channel
    private val modelNodes = mutableStateListOf<FlutterModelNode>()
    private val geometryNodes = mutableStateListOf<FlutterGeometryNode>()
    private val lightNodes = mutableStateListOf<FlutterLightNode>()
    private var environmentPath by mutableStateOf<String?>(null)

    private val composeView = ComposeView(context).apply {
        setContent {
            val engine = rememberEngine()
            val modelLoader = rememberModelLoader(engine)
            val materialLoader = rememberMaterialLoader(engine)
            val environmentLoader = rememberEnvironmentLoader(engine)

            val cameraNode = rememberCameraNode(engine) {
                position = Position(y = 0f, z = 3.0f)
            }

            // Single, stable environment call site (issue #2365): compute exactly one
            // non-null Environment rather than branching between a keyed HDR call and a
            // separate `environment ?: rememberEnvironment(...)` fallback at the SceneView
            // argument. The `path` is the remember key, so:
            //   - null path           → the default neutral environment;
            //   - path A → path B      → the Environment is rebuilt (skybox/IBL actually
            //                            swaps; the stale-factory bug of #2361).
            // Uses Compose key {} rather than rememberEnvironment's own key= param because
            // this bridge compiles against the published Maven artifact (sceneview:4.6.2)
            // that predates key=. key {} replaces the group on a new path, disposing the old
            // Environment via its DisposableEffect.
            // TODO(#2361): migrate to rememberEnvironment(..., key = path) once this bridge
            // consumes a SceneView release that includes the public key= param.
            val environment = key(environmentPath) {
                rememberEnvironment(environmentLoader) {
                    environmentPath?.let { path ->
                        environmentLoader.createHDREnvironment(path)
                    } ?: io.github.sceneview.createEnvironment(environmentLoader)
                }
            }

            SceneView(
                modifier = Modifier.fillMaxSize(),
                surfaceType = SurfaceType.TextureSurface,
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                cameraNode = cameraNode,
                environment = environment,
            ) {
                modelNodes.forEachIndexed { index, model ->
                    val instance = rememberModelInstance(modelLoader, fileLocation = model.path)
                    instance?.let {
                        ModelNode(
                            modelInstance = it,
                            scaleToUnits = model.scale,
                            autoAnimate = model.autoAnimate,
                            position = model.position,
                            rotation = model.rotation,
                            apply = {
                                val nodeName = tapNodeName(model.path, "node_$index")
                                onTouch = { _: MotionEvent, _: HitResult ->
                                    Handler(Looper.getMainLooper()).post {
                                        channel.invokeMethod("onTap", nodeName)
                                    }
                                    false // don't consume — allow camera gestures
                                }
                            },
                        )
                    }
                }

                geometryNodes.forEach { geom ->
                    // Cache the material instance per (color, unlit) so recomposition
                    // does not leak a fresh MaterialInstance every frame. The unlit
                    // flag is part of the key — switching lit ↔ unlit uses a
                    // different .filamat, so a new instance is required.
                    val mat = remember(geom.color, geom.unlit) {
                        if (geom.unlit) materialLoader.createUnlitColorInstance(geom.color)
                        else materialLoader.createColorInstance(geom.color)
                    }
                    DisposableEffect(geom.color, geom.unlit) {
                        onDispose { materialLoader.destroyMaterialInstance(mat) }
                    }
                    when (geom.type) {
                        "cube", "box" -> CubeNode(
                            size = Size(geom.size, geom.size, geom.size),
                            materialInstance = mat,
                            position = geom.position,
                            rotation = geom.rotation,
                        )
                        "sphere" -> SphereNode(
                            radius = geom.size / 2f,
                            materialInstance = mat,
                            position = geom.position,
                            rotation = geom.rotation,
                        )
                        "cylinder" -> CylinderNode(
                            radius = geom.size / 2f,
                            height = geom.size,
                            materialInstance = mat,
                            position = geom.position,
                            rotation = geom.rotation,
                        )
                        "plane" -> PlaneNode(
                            size = Size(geom.size, geom.size),
                            materialInstance = mat,
                            position = geom.position,
                            rotation = geom.rotation,
                        )
                    }
                }

                lightNodes.forEach { light ->
                    val lightType = when (light.type) {
                        "point" -> LightManager.Type.POINT
                        "spot" -> LightManager.Type.SPOT
                        else -> LightManager.Type.DIRECTIONAL
                    }
                    LightNode(
                        type = lightType,
                        intensity = light.intensity,
                        position = light.position,
                        apply = {
                            color(
                                android.graphics.Color.red(light.color) / 255f,
                                android.graphics.Color.green(light.color) / 255f,
                                android.graphics.Color.blue(light.color) / 255f,
                            )
                        },
                    )
                }
            }
        }
    }

    // Lifecycle, saved-state and view-model owners when the host has none (#3928).
    private val viewTreeOwners = PlatformViewOwners.installIfHostHasNone(composeView, context)

    init {
        channel.setMethodCallHandler(this)
    }

    override fun getView(): View = composeView

    override fun dispose() {
        channel.setMethodCallHandler(null)
        modelNodes.clear()
        geometryNodes.clear()
        lightNodes.clear()
        // Detach the ComposeView from any parent and dispose its composition
        // so that Filament resources (engine, loaders) are released.
        composeView.disposeComposition()
        (composeView.parent as? android.view.ViewGroup)?.removeView(composeView)
        viewTreeOwners?.destroy()
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "loadModel" -> {
                val modelPath = call.argument<String>("modelPath") ?: run {
                    result.error("INVALID_ARG", "modelPath is required", null)
                    return
                }
                val scale = call.argument<Double>("scale")?.toFloat() ?: 1.0f
                val x = call.argument<Double>("x")?.toFloat() ?: 0f
                val y = call.argument<Double>("y")?.toFloat() ?: 0f
                val z = call.argument<Double>("z")?.toFloat() ?: 0f
                val rotationX = call.argument<Double>("rotationX")?.toFloat() ?: 0f
                val rotationY = call.argument<Double>("rotationY")?.toFloat() ?: 0f
                val rotationZ = call.argument<Double>("rotationZ")?.toFloat() ?: 0f

                modelNodes.add(FlutterModelNode(
                    path = binding.assetPathOf(modelPath),
                    position = Position(x, y, z),
                    rotation = Rotation(rotationX, rotationY, rotationZ),
                    scale = scale,
                ))
                result.success(null)
            }
            "addGeometry" -> {
                // Geometry is rendered by appending to the reactive `geometryNodes`
                // list, which the SceneView content lambda observes (issue #909).
                geometryNodes.add(parseGeometryNode(call))
                result.success(null)
            }
            "addLight" -> {
                // Light is rendered by appending to the reactive `lightNodes`
                // list, which the SceneView content lambda observes (issue #909).
                lightNodes.add(parseLightNode(call))
                result.success(null)
            }
            "clearScene" -> {
                modelNodes.clear()
                geometryNodes.clear()
                lightNodes.clear()
                result.success(null)
            }
            "setEnvironment" -> {
                environmentPath = call.argument<String>("hdrPath")?.let(binding::assetPathOf)
                result.success(null)
            }
            "setCameraControlMode" -> {
                // v4.3.0 camera modes (#1053). Android's SceneView composable
                // already uses an orbit manipulator by default; `pan` and
                // `firstPerson` are iOS-first additions. Acknowledged here so
                // cross-platform Dart code does not throw — the per-mode
                // switch for Android is tracked in #1051.
                result.success(null)
            }
            "setAutoCenterContent" -> {
                // v4.3.0 content auto-centring (#1053) is iOS-first; the
                // Android library-level implementation is tracked in #1051.
                // Acknowledged so cross-platform Dart code does not throw.
                result.success(null)
            }
            "startRecording", "stopRecording", "saveRecordingToPhotoLibrary" -> {
                // ARRecorder is iOS-only via ReplayKit. The Dart `ARRecorder`
                // already guards against non-iOS platforms, so reaching here
                // would only happen on a direct channel call.
                result.error(
                    "UNSUPPORTED",
                    "ARRecorder is not supported on Android via the Flutter bridge (issue #1051).",
                    null,
                )
            }
            else -> result.notImplemented()
        }
    }
}

// ---------------------------------------------------------------------------
// AR SceneView
// ---------------------------------------------------------------------------

class ARSceneViewFactory(
    private val binding: FlutterPlugin.FlutterPluginBinding
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        val params = args as? Map<String, Any?> ?: emptyMap()
        return ARSceneViewPlatformView(context, viewId, params, binding)
    }
}

class ARSceneViewPlatformView(
    private val context: Context,
    private val viewId: Int,
    private val params: Map<String, Any?>,
    private val binding: FlutterPlugin.FlutterPluginBinding,
) : PlatformView, MethodChannel.MethodCallHandler {

    private val channel = MethodChannel(
        binding.binaryMessenger,
        "io.github.sceneview.flutter/scene_$viewId"
    )

    private val modelNodes = mutableStateListOf<FlutterModelNode>()
    private val geometryNodes = mutableStateListOf<FlutterGeometryNode>()
    private val lightNodes = mutableStateListOf<FlutterLightNode>()

    // Track which planes have already been reported to avoid duplicate callbacks.
    // Keyed on the ARCore Plane reference itself via an IdentityHashMap-backed set:
    // System.identityHashCode is NOT collision-free, so a distinct plane whose hash
    // collided with an already-reported one would be silently dropped (#2488). Reference
    // identity is genuinely unique for the lifetime of the session.
    private val reportedPlanes: MutableSet<com.google.ar.core.Plane> =
        java.util.Collections.newSetFromMap(java.util.IdentityHashMap())

    // ── Tap-to-place (#3780) ──────────────────────────────────────────────
    // Everything below is touched on the main thread only: ARCore frames,
    // gestures and method calls all arrive there.

    /** A model placed with `placeModel`, rendered under its own AnchorNode. */
    private class PlacedModelState(
        val id: String,
        val anchor: Anchor,
        val request: PlaceModelRequest,
    )

    private val placedModels = mutableStateListOf<PlacedModelState>()
    private val recentHits = RecentHits<com.google.ar.core.HitResult>()
    private var nextPlacedId = 0
    private var session: Session? = null
    private var latestFrame: Frame? = null

    /** Whether plane taps are hit-tested and sent to Dart (`onPlaneTap`). */
    private var planeTapEnabled = params["planeTap"] as? Boolean ?: false

    private val composeView = ComposeView(context).apply {
        setContent {
            val engine = rememberEngine()
            val modelLoader = rememberModelLoader(engine)
            val materialLoader = rememberMaterialLoader(engine)

            io.github.sceneview.ar.ARSceneView(
                modifier = Modifier.fillMaxSize(),
                surfaceType = SurfaceType.TextureSurface,
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                planeRenderer = true,
                onSessionCreated = { session = it },
                onSessionUpdated = { updatedSession, frame ->
                    session = updatedSession
                    latestFrame = frame
                    val updatedPlanes = frame.getUpdatedPlanes()
                    for (plane in updatedPlanes) {
                        if (reportedPlanes.add(plane)) {
                            val planeType = planeTypeName(plane.type?.name)
                            Handler(Looper.getMainLooper()).post {
                                channel.invokeMethod("onPlaneDetected", planeType)
                            }
                        }
                    }
                },
                onGestureListener = rememberOnGestureListener(
                    // `node` is the node under the finger, if any. A tap on a
                    // placed model is the start of an edit, not a plane tap.
                    onSingleTapConfirmed = { e, node -> if (node == null) onPlaneTap(e) },
                ),
            ) {
                placedModels.forEach { placed ->
                    key(placed.id) {
                        PlacedModelContent(placed, modelLoader)
                    }
                }


                modelNodes.forEachIndexed { index, model ->
                    val instance = rememberModelInstance(modelLoader, fileLocation = model.path)
                    instance?.let {
                        ModelNode(
                            modelInstance = it,
                            scaleToUnits = model.scale,
                            autoAnimate = model.autoAnimate,
                            position = model.position,
                            rotation = model.rotation,
                            apply = {
                                val nodeName = tapNodeName(model.path, "node_$index")
                                onTouch = { _: MotionEvent, _: HitResult ->
                                    Handler(Looper.getMainLooper()).post {
                                        channel.invokeMethod("onTap", nodeName)
                                    }
                                    false // don't consume — allow camera gestures
                                }
                            },
                        )
                    }
                }

                geometryNodes.forEach { geom ->
                    // Material instance cached per (color, unlit) — see the 3D
                    // SceneView path for the rationale (issue #909).
                    val mat = remember(geom.color, geom.unlit) {
                        if (geom.unlit) materialLoader.createUnlitColorInstance(geom.color)
                        else materialLoader.createColorInstance(geom.color)
                    }
                    DisposableEffect(geom.color, geom.unlit) {
                        onDispose { materialLoader.destroyMaterialInstance(mat) }
                    }
                    when (geom.type) {
                        "cube", "box" -> CubeNode(
                            size = Size(geom.size, geom.size, geom.size),
                            materialInstance = mat,
                            position = geom.position,
                            rotation = geom.rotation,
                        )
                        "sphere" -> SphereNode(
                            radius = geom.size / 2f,
                            materialInstance = mat,
                            position = geom.position,
                            rotation = geom.rotation,
                        )
                        "cylinder" -> CylinderNode(
                            radius = geom.size / 2f,
                            height = geom.size,
                            materialInstance = mat,
                            position = geom.position,
                            rotation = geom.rotation,
                        )
                        "plane" -> PlaneNode(
                            size = Size(geom.size, geom.size),
                            materialInstance = mat,
                            position = geom.position,
                            rotation = geom.rotation,
                        )
                    }
                }

                lightNodes.forEach { light ->
                    val lightType = when (light.type) {
                        "point" -> LightManager.Type.POINT
                        "spot" -> LightManager.Type.SPOT
                        else -> LightManager.Type.DIRECTIONAL
                    }
                    LightNode(
                        type = lightType,
                        intensity = light.intensity,
                        position = light.position,
                        apply = {
                            color(
                                android.graphics.Color.red(light.color) / 255f,
                                android.graphics.Color.green(light.color) / 255f,
                                android.graphics.Color.blue(light.color) / 255f,
                            )
                        },
                    )
                }
            }
        }
    }

    // Lifecycle, saved-state and view-model owners when the host has none (#3928).
    private val viewTreeOwners = PlatformViewOwners.installIfHostHasNone(composeView, context)

    init {
        channel.setMethodCallHandler(this)
    }

    override fun getView(): View = composeView

    /**
     * Hit-tests a tap against detected planes and sends the hit to Dart as
     * `onPlaneTap`. Runs on the main thread, from the scene's gesture
     * listener.
     */
    private fun onPlaneTap(e: MotionEvent) {
        if (!planeTapEnabled) return
        val frame = latestFrame ?: return
        if (frame.camera.trackingState != TrackingState.TRACKING) return
        val hit = runCatching { frame.hitTest(e) }.getOrNull()
            ?.firstOrNull { isPlaneHit(it) } ?: return
        val plane = hit.trackable as Plane
        val pose = hit.hitPose
        channel.invokeMethod(
            "onPlaneTap",
            planeHitMap(
                id = recentHits.put(hit),
                translation = pose.translation,
                rotation = pose.rotationQuaternion,
                planeType = planeTypeName(plane.type?.name),
                distance = hit.distance,
            ),
        )
    }

    /**
     * A hit counts as a plane hit when it lands inside the polygon of a
     * tracked plane that has not been merged into another one. Tap and drag
     * both use this test, so a drag cannot pull a model off the surface.
     */
    private fun isPlaneHit(hit: com.google.ar.core.HitResult): Boolean {
        val plane = hit.trackable as? Plane ?: return false
        return plane.trackingState == TrackingState.TRACKING &&
            plane.subsumedBy == null &&
            plane.isPoseInPolygon(hit.hitPose)
    }

    /**
     * Anchors a placement. It anchors to the tapped plane when the hit is
     * still cached, which keeps the model on the surface as ARCore refines
     * it. Otherwise it creates a free world anchor at the hit pose, which
     * covers a hit built on the Dart side.
     */
    private fun createPlacementAnchor(request: PlaceModelRequest): Anchor? {
        recentHits[request.hitId]?.let { hit ->
            runCatching { hit.createAnchor() }.getOrNull()?.let { return it }
        }
        val pose = Pose(
            floatArrayOf(request.tx, request.ty, request.tz),
            floatArrayOf(request.qx, request.qy, request.qz, request.qw),
        )
        return runCatching { session?.createAnchor(pose) }.getOrNull()
    }

    /**
     * Renders one placed model as three nested nodes:
     * - an AnchorNode, which anchors the model in the world and handles drag;
     * - a pivot Node, which handles twist and pinch around the contact point;
     * - a ModelNode, bottom-centred on the pivot and scaled to `size` metres.
     *
     * The ModelNode is not editable, so every gesture that starts on it
     * bubbles up. Twist and pinch stop at the pivot. Drag continues to the
     * AnchorNode, which hit-tests planes under the finger and re-anchors
     * where the drag ends.
     */
    @Composable
    private fun ARSceneScope.PlacedModelContent(
        placed: PlacedModelState,
        modelLoader: io.github.sceneview.loaders.ModelLoader,
    ) {
        val request = placed.request
        AnchorNode(
            anchor = placed.anchor,
            apply = {
                isPositionEditable = request.canDrag
                moveHitTest = { frame, e -> frame.hitTest(e).firstOrNull { isPlaneHit(it) } }
                // Keep the heading while dragging. The raw hit pose faces the
                // camera from each new spot, which would spin the model under
                // the finger. Only the translation follows the drag.
                onMove = { _, _, worldPosition ->
                    pose = Pose(
                        floatArrayOf(worldPosition.x, worldPosition.y, worldPosition.z),
                        pose.rotationQuaternion,
                    )
                    false
                }
            },
        ) {
            Node(
                isEditable = request.editable,
                apply = {
                    isPositionEditable = false
                    isRotationEditable = request.rotatable
                    isScaleEditable = request.scalable
                    editableScaleRange = PLACED_SCALE_RANGE
                },
            ) {
                // Named `fileLocation` selects the overload that also loads
                // http(s) URLs. The two-argument positional call resolves to
                // the asset-only overload.
                val instance = rememberModelInstance(modelLoader, fileLocation = request.modelPath)
                if (instance != null) {
                    val offset = remember(instance) {
                        val box = instance.asset.boundingBox
                        bottomCenterOffset(box.center, box.halfExtent, request.size)
                    }
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = request.size,
                        position = Position(offset[0], offset[1], offset[2]),
                    )
                }
            }
        }
    }

    private fun removePlacedModels(predicate: (PlacedModelState) -> Boolean) {
        val removed = placedModels.filter(predicate)
        // The AnchorNode detaches its current anchor when it is disposed.
        // Detaching the original one here as well covers a model removed
        // before its node was ever composed. Detach is idempotent.
        removed.forEach { runCatching { it.anchor.detach() } }
        placedModels.removeAll(removed)
    }

    override fun dispose() {
        channel.setMethodCallHandler(null)
        modelNodes.clear()
        geometryNodes.clear()
        lightNodes.clear()
        reportedPlanes.clear()
        placedModels.clear()
        recentHits.clear()
        latestFrame = null
        session = null
        // Detach the ComposeView from any parent and dispose its composition
        // so that Filament/ARCore resources are released.
        composeView.disposeComposition()
        (composeView.parent as? android.view.ViewGroup)?.removeView(composeView)
        viewTreeOwners?.destroy()
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "loadModel" -> {
                val modelPath = call.argument<String>("modelPath") ?: run {
                    result.error("INVALID_ARG", "modelPath is required", null)
                    return
                }
                val scale = call.argument<Double>("scale")?.toFloat() ?: 1.0f
                val x = call.argument<Double>("x")?.toFloat() ?: 0f
                val y = call.argument<Double>("y")?.toFloat() ?: 0f
                val z = call.argument<Double>("z")?.toFloat() ?: 0f
                val rotationX = call.argument<Double>("rotationX")?.toFloat() ?: 0f
                val rotationY = call.argument<Double>("rotationY")?.toFloat() ?: 0f
                val rotationZ = call.argument<Double>("rotationZ")?.toFloat() ?: 0f

                modelNodes.add(FlutterModelNode(
                    path = binding.assetPathOf(modelPath),
                    position = Position(x, y, z),
                    rotation = Rotation(rotationX, rotationY, rotationZ),
                    scale = scale,
                ))
                result.success(null)
            }
            "addGeometry" -> {
                // Geometry is rendered by appending to the reactive `geometryNodes`
                // list, which the ARSceneView content lambda observes (issue #909).
                geometryNodes.add(parseGeometryNode(call))
                result.success(null)
            }
            "addLight" -> {
                // Light is rendered by appending to the reactive `lightNodes`
                // list, which the ARSceneView content lambda observes (issue #909).
                lightNodes.add(parseLightNode(call))
                result.success(null)
            }
            "clearScene" -> {
                modelNodes.clear()
                geometryNodes.clear()
                lightNodes.clear()
                reportedPlanes.clear()
                removePlacedModels { true }
                recentHits.clear()
                result.success(null)
            }
            "placeModel" -> {
                val parsed = parsePlaceModelRequest(call.arguments as? Map<*, *>) ?: run {
                    result.error(
                        "INVALID_ARGS",
                        "placeModel needs a hit and a model with a modelPath",
                        null,
                    )
                    return
                }
                // A Dart asset key lives under flutter_assets/ in the APK.
                val request = parsed.copy(modelPath = binding.assetPathOf(parsed.modelPath))
                val anchor = createPlacementAnchor(request) ?: run {
                    result.error(
                        "NOT_TRACKING",
                        "Could not anchor the model: AR tracking is not available right now.",
                        null,
                    )
                    return
                }
                val id = "placed-${nextPlacedId++}"
                placedModels.add(PlacedModelState(id, anchor, request))
                result.success(id)
            }
            "removePlacedModel" -> {
                val id = call.argument<String>("id")
                removePlacedModels { it.id == id }
                result.success(null)
            }
            "setPlaneTapEnabled" -> {
                planeTapEnabled = call.argument<Boolean>("enabled") ?: false
                result.success(null)
            }
            "setEnvironment" -> {
                // AR scenes use camera feed as background; environment HDR
                // affects lighting but not the skybox.
                result.success(null)
            }
            "startRecording", "stopRecording", "saveRecordingToPhotoLibrary" -> {
                // ARRecorder is iOS-only via ReplayKit (#1053). Android's
                // `io.github.sceneview.ar.recording.ARRecorder` records an
                // ARCore dataset (a replayable session capture, not a video)
                // and needs Session/Frame access the platform-view bridge
                // does not expose. Tracked in #1051. The Dart `ARRecorder`
                // already guards non-iOS platforms.
                result.error(
                    "UNSUPPORTED",
                    "ARRecorder is not supported on Android via the Flutter bridge (issue #1051).",
                    null,
                )
            }
            else -> result.notImplemented()
        }
    }
}
