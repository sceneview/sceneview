package io.github.sceneview.reactnative

import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableType
import com.facebook.react.common.MapBuilder
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp
import com.google.android.filament.LightManager
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.gesture.GestureDetector
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader

/**
 * Per-instance scene state stored as a tag on the FrameLayout container.
 * Each `<RNSceneView>` gets its own independent state.
 */
class SceneViewState {
    val modelPaths = mutableStateListOf<ModelNodeData>()
    val geometryNodes = mutableStateListOf<GeometryNodeData>()
    val lightNodes = mutableStateListOf<LightNodeData>()
    val environmentPath = mutableStateOf<String?>(null)
    val orbitEnabled = mutableStateOf(true)

    /**
     * v4.3.0 camera control mode (#1053). Stored for parity with the iOS
     * bridge; Android's `SceneView` composable already orbits by default,
     * so `pan` / `firstPerson` currently fall back to orbit (tracked in #1051).
     */
    val cameraControlMode = mutableStateOf("orbit")

    /**
     * v4.3.0 content auto-centring (#1053). iOS-first; the Android
     * library-level implementation is tracked in #1051.
     */
    val autoCenterContent = mutableStateOf(true)
}

/**
 * ViewManager that bridges React Native's `<RNSceneView>` to the Jetpack Compose
 * `SceneView { }` composable from `io.github.sceneview`.
 *
 * State is stored per-instance via [FrameLayout.getTag] to support multiple
 * `<RNSceneView>` components on the same screen.
 */
class SceneViewManager : SimpleViewManager<FrameLayout>() {

    override fun getName(): String = "RNSceneView"

    private fun getState(view: FrameLayout): SceneViewState {
        return view.tag as? SceneViewState ?: SceneViewState().also { view.tag = it }
    }

    /**
     * Registers `onTap` so React Native delivers the native [TapEvent] dispatch
     * to the JS `onTap` prop. Without this entry the event is silently dropped
     * even when the native side dispatches it (issue #2053).
     */
    override fun getExportedCustomDirectEventTypeConstants(): Map<String, Any> =
        MapBuilder.builder<String, Any>()
            .put(TapEvent.NAME, MapBuilder.of("registrationName", "onTap"))
            .build()

    override fun createViewInstance(reactContext: ThemedReactContext): FrameLayout {
        val container = FrameLayout(reactContext)
        val state = SceneViewState()
        container.tag = state

        // Tap gesture → JS `onTap`. SceneView's GestureDetector resolves the
        // tapped node (if any) via collision hit-testing, so the event carries
        // the node name and its world-space position (issue #2053).
        val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(
                e: android.view.MotionEvent,
                node: io.github.sceneview.node.Node?,
            ) {
                dispatchTapEvent(reactContext, container, node)
            }
        }

        val composeView = ComposeView(reactContext).apply {
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
                // this bridge compiles against the published Maven artifact (sceneview:4.7.0)
                // that predates key=. key {} replaces the group on a new path, disposing the old
                // Environment via its DisposableEffect.
                // TODO(#2361): migrate to rememberEnvironment(..., key = path) once this bridge
                // consumes a SceneView release that includes the public key= param.
                val environmentPath = state.environmentPath.value
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
                    onGestureListener = gestureListener,
                ) {
                    state.modelPaths.forEach { model ->
                        val instance = rememberModelInstance(modelLoader, model.src)
                        instance?.let {
                            ModelNode(
                                modelInstance = it,
                                scaleToUnits = model.scale,
                                autoAnimate = model.animate,
                                position = model.position,
                                rotation = model.rotation,
                                apply = {
                                    // The tap payload's `nodeName`. Without a name the
                                    // hit-tested ModelNode reports `null` for every model
                                    // tap, while iOS reports the model file's base name —
                                    // so name the node the same way here (PR #3037).
                                    name = model.nodeName()
                                },
                            )
                        }
                    }

                    state.geometryNodes.forEach { geom ->
                        val colorInt = geom.color?.let {
                            runCatching { android.graphics.Color.parseColor(it) }.getOrNull()
                        }
                        // Cache material instance per (color, unlit) to avoid leaking on recomposition.
                        // The unlit flag is part of the cache key — switching from lit ↔ unlit
                        // returns a fresh instance because the underlying .filamat is different.
                        // Use varargs `keys` (Compose handles Boolean autobox internally without
                        // a per-recomposition Pair allocation).
                        val mat = colorInt?.let { c ->
                            val instance = remember(c, geom.unlit) {
                                if (geom.unlit) materialLoader.createUnlitColorInstance(c)
                                else materialLoader.createColorInstance(c)
                            }
                            DisposableEffect(c, geom.unlit) {
                                onDispose {
                                    materialLoader.destroyMaterialInstance(instance)
                                }
                            }
                            instance
                        }
                        when (geom.type) {
                            "cube", "box" -> CubeNode(
                                size = geom.size?.let { Size(it[0], it[1], it[2]) }
                                    ?: Size(1f, 1f, 1f),
                                materialInstance = mat,
                                position = geom.position,
                                rotation = geom.rotation,
                                scale = geom.scale,
                            )
                            "sphere" -> SphereNode(
                                radius = geom.size?.let { it[0] / 2f } ?: 0.5f,
                                materialInstance = mat,
                                position = geom.position,
                                rotation = geom.rotation,
                                scale = geom.scale,
                            )
                            "cylinder" -> CylinderNode(
                                radius = geom.size?.let { it[0] / 2f } ?: 0.5f,
                                height = geom.size?.let { it[1] } ?: 1f,
                                materialInstance = mat,
                                position = geom.position,
                                rotation = geom.rotation,
                                scale = geom.scale,
                            )
                            "plane" -> PlaneNode(
                                size = geom.size?.let { Size(it[0], it[1]) }
                                    ?: Size(1f, 1f),
                                materialInstance = mat,
                                position = geom.position,
                                rotation = geom.rotation,
                                scale = geom.scale,
                            )
                        }
                    }

                    state.lightNodes.forEach { light ->
                        val lightType = when (light.type) {
                            "directional" -> LightManager.Type.DIRECTIONAL
                            "point" -> LightManager.Type.POINT
                            "spot" -> LightManager.Type.SPOT
                            else -> LightManager.Type.DIRECTIONAL
                        }
                        LightNode(
                            type = lightType,
                            intensity = light.intensity,
                            direction = light.direction,
                            position = light.position,
                            apply = {
                                light.color?.let { hex ->
                                    val c = runCatching { android.graphics.Color.parseColor(hex) }.getOrNull() ?: return@let
                                    color(
                                        android.graphics.Color.red(c) / 255f,
                                        android.graphics.Color.green(c) / 255f,
                                        android.graphics.Color.blue(c) / 255f,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
        container.addView(composeView)
        return container
    }

    override fun onDropViewInstance(view: FrameLayout) {
        // Remove the ComposeView so its Composition is disposed, releasing Filament resources.
        view.removeAllViews()
        super.onDropViewInstance(view)
    }

    @ReactProp(name = "environment")
    fun setEnvironment(view: FrameLayout, environment: String?) {
        getState(view).environmentPath.value = environment
    }

    @ReactProp(name = "modelNodes")
    fun setModelNodes(view: FrameLayout, nodes: ReadableArray?) {
        val state = getState(view)
        state.modelPaths.clear()
        nodes?.let { array ->
            for (i in 0 until array.size()) {
                val map = array.getMap(i) ?: continue
                val src = map.getString("src") ?: continue
                val scale = if (map.hasKey("scale")) {
                    try {
                        map.getDouble("scale").toFloat()
                    } catch (_: Exception) {
                        1.0f
                    }
                } else {
                    1.0f
                }
                // "animation" is a string (animation name) in the TS types.
                // If present and non-null, auto-animate is enabled.
                val animate = if (map.hasKey("animation")) {
                    map.getString("animation") != null
                } else {
                    true
                }
                val position = readPosition(map, "position")
                val rotation = readRotation(map, "rotation")
                state.modelPaths.add(
                    ModelNodeData(
                        src = src,
                        scale = scale,
                        animate = animate,
                        position = position,
                        rotation = rotation,
                    )
                )
            }
        }
    }

    @ReactProp(name = "geometryNodes")
    fun setGeometryNodes(view: FrameLayout, nodes: ReadableArray?) {
        val state = getState(view)
        state.geometryNodes.clear()
        nodes?.let { array ->
            for (i in 0 until array.size()) {
                val map = array.getMap(i) ?: continue
                val type = map.getString("type") ?: continue
                val size = readFloatArray3(map, "size")
                val position = readPosition(map, "position")
                val rotation = readRotation(map, "rotation")
                val scale = readScale(map, "scale")
                val color = if (map.hasKey("color")) map.getString("color") else null
                // `getBoolean` throws on type-mismatch; guard against JS sending
                // `unlit: "true"` (string) or `unlit: 1` (number) which would crash
                // the property setter and leave the view in a partial state.
                val unlit = map.hasKey("unlit") &&
                    map.getType("unlit") == ReadableType.Boolean &&
                    map.getBoolean("unlit")
                state.geometryNodes.add(
                    GeometryNodeData(
                        type = type,
                        size = size,
                        position = position,
                        rotation = rotation,
                        scale = scale,
                        color = color,
                        unlit = unlit,
                    )
                )
            }
        }
    }

    @ReactProp(name = "lightNodes")
    fun setLightNodes(view: FrameLayout, nodes: ReadableArray?) {
        val state = getState(view)
        state.lightNodes.clear()
        nodes?.let { array ->
            for (i in 0 until array.size()) {
                val map = array.getMap(i) ?: continue
                val type = map.getString("type") ?: continue
                val intensity = if (map.hasKey("intensity")) {
                    map.getDouble("intensity").toFloat()
                } else null
                val color = if (map.hasKey("color")) map.getString("color") else null
                val position = readPosition(map, "position")
                val direction = readDirection(map, "direction")
                state.lightNodes.add(
                    LightNodeData(
                        type = type,
                        intensity = intensity,
                        color = color,
                        position = position,
                        direction = direction,
                    )
                )
            }
        }
    }

    @ReactProp(name = "cameraOrbit", defaultBoolean = true)
    fun setCameraOrbit(view: FrameLayout, enabled: Boolean) {
        getState(view).orbitEnabled.value = enabled
    }

    @ReactProp(name = "cameraControlMode")
    fun setCameraControlMode(view: FrameLayout, mode: String?) {
        // v4.3.0 camera modes (#1053). The Android `SceneView` composable
        // already uses an orbit manipulator by default; `pan` and
        // `firstPerson` are iOS-first additions. Acknowledged here so
        // cross-platform JS code does not crash the prop setter — the
        // per-mode switch for Android is tracked in issue #1051.
        getState(view).cameraControlMode.value = mode ?: "orbit"
    }

    @ReactProp(name = "autoCenterContent", defaultBoolean = true)
    fun setAutoCenterContent(view: FrameLayout, enabled: Boolean) {
        // v4.3.0 content auto-centring (#1053) is iOS-first; the Android
        // library-level implementation is tracked in issue #1051.
        // Acknowledged so cross-platform JS code does not crash.
        getState(view).autoCenterContent.value = enabled
    }
}

data class ModelNodeData(
    val src: String,
    val scale: Float = 1.0f,
    val animate: Boolean = true,
    val position: Position = Position(x = 0f),
    val rotation: Rotation = Rotation(x = 0f),
) {
    /**
     * The name reported as the tap payload's `nodeName`: the model file's base
     * name without extension, matching the iOS bridge (which names each loaded
     * model root after its file and strips the extension on tap).
     *
     * [src] is documented as an "asset path **or URL**", and `ModelLoader`
     * really does load `https://` sources, so the query and fragment are
     * stripped FIRST. Cutting at the last `.` on a raw URL only works when the
     * extension is the last dot in the whole string: for
     * `https://cdn/robot.glb?sig=SIG&v=1.2` it yields
     * `robot.glb?sig=SIG&v=1` — a CDN signature leaking into a payload that
     * apps routinely put in a label or an analytics event.
     *
     * [urlPathOf] then drops the authority, which carries the other half of the
     * same exposure: the last `/`-separated segment of a path-less URL IS the
     * authority, and an authority may carry userinfo, so
     * `https://user:pa55w0rd@cdn.example` would report `user:pa55w0rd@cdn`
     * (#3071).
     *
     * `null` for a path with no usable base name, so the payload stays
     * `nodeName: null` rather than an empty string.
     */
    fun nodeName(): String? = modelNodeName(src)
}

/**
 * [ModelNodeData.nodeName]'s derivation, as a top-level function.
 *
 * Split out so it can be unit-tested without constructing a [ModelNodeData]:
 * that data class defaults `position`/`rotation` to `Position`/`Rotation` from
 * the **published** SceneView artifacts, which are compiled for JVM 21, while
 * this module targets JVM 17 and its CI gate runs on a JDK 17. Compiling
 * against a newer class file is fine; *loading* one is not, so instantiating
 * the data class in a JVM unit test throws `UnsupportedClassVersionError`.
 *
 * That mismatch is harmless on a device — D8 re-compiles everything to DEX and
 * the class-file version stops existing — so the fix belongs in the test's
 * reach, not in the module's or the gate's toolchain.
 *
 * This also puts the derivation at the same level as the Flutter bridge's
 * `tapNodeName`, which the shared case table already assumed.
 */
internal fun modelNodeName(src: String): String? =
    urlPathOf(src.substringBefore('?').substringBefore('#'))
        .substringAfterLast('/').substringBeforeLast('.')
        .takeIf { it.isNotEmpty() }

/**
 * The path part of [source] when it is a URL, [source] unchanged otherwise.
 *
 * Returns `""` for a path-less URL: there is no file name in
 * `https://cdn.example` to report, and `null` is the honest payload.
 *
 * Mirrored verbatim in the Flutter bridge's `SceneViewPlugin.kt`. The two
 * bridges are separately published packages with no shared Kotlin, so the
 * duplication is the seam, not an oversight — both sides are pinned by their
 * own unit tests.
 */
internal fun urlPathOf(source: String): String {
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

data class GeometryNodeData(
    val type: String,
    val size: FloatArray? = null,
    val position: Position = Position(x = 0f),
    val rotation: Rotation = Rotation(x = 0f),
    val scale: Scale = Scale(1f),
    val color: String? = null,
    /**
     * When `true` the node's material ignores all scene lighting (no PBR shading,
     * no IBL, no shadows) and renders the flat [color] straight to the framebuffer.
     * Use for HUD overlays, gizmos, axes, lines, AR face/body meshes — anywhere
     * lighting would fight the use case. Defaults to `false` (lit PBR).
     */
    val unlit: Boolean = false,
)

data class LightNodeData(
    val type: String,
    val intensity: Float? = null,
    val color: String? = null,
    val position: Position = Position(x = 0f),
    val direction: Direction? = null,
)

// ---------------------------------------------------------------------------
// Helpers for reading ReadableMap arrays into SceneView math types
// ---------------------------------------------------------------------------

internal fun readFloatArray3(
    map: com.facebook.react.bridge.ReadableMap,
    key: String
): FloatArray? {
    if (!map.hasKey(key)) return null
    val arr = map.getArray(key) ?: return null
    if (arr.size() < 3) return null
    return floatArrayOf(
        arr.getDouble(0).toFloat(),
        arr.getDouble(1).toFloat(),
        arr.getDouble(2).toFloat(),
    )
}

internal fun readPosition(
    map: com.facebook.react.bridge.ReadableMap,
    key: String
): Position {
    if (!map.hasKey(key)) return Position(x = 0f)
    val arr = map.getArray(key) ?: return Position(x = 0f)
    if (arr.size() < 3) return Position(x = 0f)
    return Position(
        x = arr.getDouble(0).toFloat(),
        y = arr.getDouble(1).toFloat(),
        z = arr.getDouble(2).toFloat(),
    )
}

internal fun readRotation(
    map: com.facebook.react.bridge.ReadableMap,
    key: String
): Rotation {
    if (!map.hasKey(key)) return Rotation(x = 0f)
    val arr = map.getArray(key) ?: return Rotation(x = 0f)
    if (arr.size() < 3) return Rotation(x = 0f)
    return Rotation(
        x = arr.getDouble(0).toFloat(),
        y = arr.getDouble(1).toFloat(),
        z = arr.getDouble(2).toFloat(),
    )
}

internal fun readScale(
    map: com.facebook.react.bridge.ReadableMap,
    key: String
): Scale {
    if (!map.hasKey(key)) return Scale(1f)
    return try {
        val v = map.getDouble(key).toFloat()
        Scale(v)
    } catch (_: Exception) {
        val arr = map.getArray(key) ?: return Scale(1f)
        if (arr.size() < 3) return Scale(1f)
        Scale(
            x = arr.getDouble(0).toFloat(),
            y = arr.getDouble(1).toFloat(),
            z = arr.getDouble(2).toFloat(),
        )
    }
}

internal fun readDirection(
    map: com.facebook.react.bridge.ReadableMap,
    key: String
): Direction? {
    if (!map.hasKey(key)) return null
    val arr = map.getArray(key) ?: return null
    if (arr.size() < 3) return null
    return Direction(
        x = arr.getDouble(0).toFloat(),
        y = arr.getDouble(1).toFloat(),
        z = arr.getDouble(2).toFloat(),
    )
}
