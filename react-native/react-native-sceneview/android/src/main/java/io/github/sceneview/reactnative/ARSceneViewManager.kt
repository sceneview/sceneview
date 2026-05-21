package io.github.sceneview.reactnative

import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
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
import com.google.ar.core.Config
import com.google.ar.core.Plane
import io.github.sceneview.SurfaceType
import io.github.sceneview.ar.arcore.getUpdatedPlanes
import io.github.sceneview.gesture.GestureDetector
import io.github.sceneview.math.Size
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader

/**
 * Per-instance AR scene state stored as a tag on the FrameLayout container.
 */
class ARSceneViewState {
    val modelPaths = mutableStateListOf<ModelNodeData>()
    val geometryNodes = mutableStateListOf<GeometryNodeData>()
    val lightNodes = mutableStateListOf<LightNodeData>()
    val planeDetection = mutableStateOf(true)
    val depthOcclusion = mutableStateOf(false)
    val instantPlacement = mutableStateOf(false)

    /**
     * ARCore [Plane] instances already reported to JS via `onPlaneDetected`.
     * ARCore returns the same `Plane` reference for a given trackable across
     * frames, so reference identity is the correct de-duplication key — the
     * event fires exactly once per newly-detected plane (issue #2053).
     */
    val reportedPlanes = HashSet<Plane>()
}

/**
 * ViewManager that bridges React Native's `<RNARSceneView>` to the Jetpack Compose
 * `ARSceneView { }` composable from `io.github.sceneview.ar`.
 *
 * State is stored per-instance via [FrameLayout.getTag] to support multiple
 * `<RNARSceneView>` components on the same screen.
 */
class ARSceneViewManager : SimpleViewManager<FrameLayout>() {

    override fun getName(): String = "RNARSceneView"

    private fun getState(view: FrameLayout): ARSceneViewState {
        return view.tag as? ARSceneViewState ?: ARSceneViewState().also { view.tag = it }
    }

    /**
     * Registers `onTap` / `onPlaneDetected` so React Native delivers the native
     * event dispatches to the matching JS props. Without these entries the
     * events are silently dropped even when the native side dispatches them
     * (issue #2053).
     */
    override fun getExportedCustomDirectEventTypeConstants(): Map<String, Any> =
        MapBuilder.builder<String, Any>()
            .put(TapEvent.NAME, MapBuilder.of("registrationName", "onTap"))
            .put(
                PlaneDetectedEvent.NAME,
                MapBuilder.of("registrationName", "onPlaneDetected"),
            )
            .build()

    override fun createViewInstance(reactContext: ThemedReactContext): FrameLayout {
        val container = FrameLayout(reactContext)
        val state = ARSceneViewState()
        container.tag = state

        // Tap gesture → JS `onTap` (issue #2053).
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

                // Read the AR config flags here so the `sessionConfiguration`
                // lambda below captures them by value — its identity then
                // changes whenever JS toggles the prop, which re-applies the
                // ARCore Config on the live session (issue #2055).
                val depthOcclusion = state.depthOcclusion.value
                val instantPlacement = state.instantPlacement.value

                io.github.sceneview.ar.ARSceneView(
                    modifier = Modifier.fillMaxSize(),
                    surfaceType = SurfaceType.TextureSurface,
                    engine = engine,
                    modelLoader = modelLoader,
                    materialLoader = materialLoader,
                    planeRenderer = state.planeDetection.value,
                    onGestureListener = gestureListener,
                    // depthOcclusion / instantPlacement → ARCore Config. The
                    // typed `depthMode` / `instantPlacementMode` composable
                    // params (#1766) post-date the consumed arsceneview
                    // artifact, so the flags are applied via the stable
                    // `sessionConfiguration` callback instead (issue #2055).
                    sessionConfiguration = { session, config ->
                        config.depthMode =
                            if (depthOcclusion &&
                                session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                            ) {
                                Config.DepthMode.AUTOMATIC
                            } else {
                                Config.DepthMode.DISABLED
                            }
                        config.instantPlacementMode =
                            if (instantPlacement) {
                                Config.InstantPlacementMode.LOCAL_Y_UP
                            } else {
                                Config.InstantPlacementMode.DISABLED
                            }
                    },
                    // Per-frame plane diff → JS `onPlaneDetected`. Fires once
                    // per newly-tracked ARCore plane (issue #2053).
                    onSessionUpdated = { _, frame ->
                        for (plane in frame.getUpdatedPlanes()) {
                            if (plane.trackingState != com.google.ar.core.TrackingState.TRACKING) {
                                continue
                            }
                            if (state.reportedPlanes.add(plane)) {
                                dispatchPlaneEvent(reactContext, container, plane)
                            }
                        }
                    },
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
                            )
                        }
                    }

                    state.geometryNodes.forEach { geom ->
                        val colorInt = geom.color?.let {
                            runCatching { android.graphics.Color.parseColor(it) }.getOrNull()
                        }
                        // Cache material instance per (color, unlit) to avoid leaking on recomposition.
                        // Use varargs `keys` to skip the per-recomposition Pair allocation.
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
        // AR scenes use camera feed as background; environment HDR
        // affects lighting only. Not yet wired to ARScene parameters.
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
                    // Scale can be a single number or array [x,y,z]; handle single number
                    try {
                        map.getDouble("scale").toFloat()
                    } catch (_: Exception) {
                        1.0f
                    }
                } else {
                    1.0f
                }
                val animate = if (map.hasKey("animation")) {
                    // "animation" is a string (animation name) in the TS types.
                    // If present, auto-animate is enabled.
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
                // Type-safe `unlit` parsing — guards against JS sending `"true"` or `1`.
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

    @ReactProp(name = "planeDetection", defaultBoolean = true)
    fun setPlaneDetection(view: FrameLayout, enabled: Boolean) {
        getState(view).planeDetection.value = enabled
    }

    @ReactProp(name = "depthOcclusion", defaultBoolean = false)
    fun setDepthOcclusion(view: FrameLayout, enabled: Boolean) {
        getState(view).depthOcclusion.value = enabled
    }

    @ReactProp(name = "instantPlacement", defaultBoolean = false)
    fun setInstantPlacement(view: FrameLayout, enabled: Boolean) {
        getState(view).instantPlacement.value = enabled
    }
}
