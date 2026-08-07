package io.github.sceneview.flutter

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
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
 * The name reported to Dart as the tap payload: the model file's base name
 * without extension, identical to what the iOS bridge derives.
 *
 * A model path may be a URL — `ModelLoader.loadModel` loads `https://` sources
 * — so everything that is not the path is dropped FIRST. Cutting at the last
 * `.` on a raw URL only strips the extension when it is the last dot in the
 * whole string: `https://cdn/robot.glb?sig=SIG&v=1.2` would otherwise yield
 * `robot.glb?sig=SIG&v=1`, leaking a CDN signature into a payload apps put in
 * labels and analytics events (PR #3037). The authority goes with it, because
 * `https://user:pass@host` has no path component for the last-`/` cut to step
 * over and the credentials would have been published verbatim.
 *
 * [fallback] keeps a path with no usable base name from reporting "".
 */
private fun tapNodeName(path: String, fallback: String): String =
    urlPathOf(path).substringAfterLast('/').substringBeforeLast('.')
        .ifEmpty { fallback }

/**
 * The path part of [source], or the whole thing when it is not a URL.
 *
 * Kept as string surgery rather than `android.net.Uri` so it stays exercisable
 * from a plain JVM unit test — `Uri.parse` is a stub outside an instrumented
 * run. Returns "" for a URL with an authority and no path, which is what makes
 * the caller fall back instead of naming a model after a host.
 */
private fun urlPathOf(source: String): String {
    val withoutQuery = source.substringBefore('?').substringBefore('#')
    val schemeEnd = withoutQuery.indexOf("://")
    if (schemeEnd < 0) return withoutQuery
    val afterScheme = withoutQuery.substring(schemeEnd + 3)
    val pathStart = afterScheme.indexOf('/')
    return if (pathStart < 0) "" else afterScheme.substring(pathStart)
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
                    val instance = rememberModelInstance(modelLoader, model.path)
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
                    path = modelPath,
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
                environmentPath = call.argument<String>("hdrPath")
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
                onSessionUpdated = { _, frame ->
                    val updatedPlanes = frame.getUpdatedPlanes()
                    for (plane in updatedPlanes) {
                        if (reportedPlanes.add(plane)) {
                            val planeType = when (plane.type) {
                                com.google.ar.core.Plane.Type.HORIZONTAL_UPWARD_FACING -> "horizontal_upward"
                                com.google.ar.core.Plane.Type.HORIZONTAL_DOWNWARD_FACING -> "horizontal_downward"
                                com.google.ar.core.Plane.Type.VERTICAL -> "vertical"
                                else -> "unknown"
                            }
                            Handler(Looper.getMainLooper()).post {
                                channel.invokeMethod("onPlaneDetected", planeType)
                            }
                        }
                    }
                },
            ) {
                modelNodes.forEachIndexed { index, model ->
                    val instance = rememberModelInstance(modelLoader, model.path)
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

    init {
        channel.setMethodCallHandler(this)
    }

    override fun getView(): View = composeView

    override fun dispose() {
        channel.setMethodCallHandler(null)
        modelNodes.clear()
        geometryNodes.clear()
        lightNodes.clear()
        reportedPlanes.clear()
        // Detach the ComposeView from any parent and dispose its composition
        // so that Filament/ARCore resources are released.
        composeView.disposeComposition()
        (composeView.parent as? android.view.ViewGroup)?.removeView(composeView)
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
                    path = modelPath,
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
