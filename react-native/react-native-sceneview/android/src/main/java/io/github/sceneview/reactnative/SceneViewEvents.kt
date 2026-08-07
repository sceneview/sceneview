package io.github.sceneview.reactnative

import android.view.View
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.events.Event
import com.google.ar.core.Plane
import io.github.sceneview.node.Node

/**
 * RN Fabric event delivered to the JS `onTap` prop of `<SceneView>` / `<ARSceneView>`.
 *
 * Payload mirrors the TypeScript `TapEvent` interface in `src/index.tsx`:
 * `{ x, y, z, nodeName }` — the key is never optional. [getEventData] writes it
 * on every dispatch, `putNull` when no node was hit, so JS sees `null` and never
 * `undefined`. Wired in [SceneViewManager] / [ARSceneViewManager] via
 * `getExportedCustomDirectEventTypeConstants` (issue #2053).
 *
 * Both managers share this event, so an AR tap that lands on a model reports
 * that model's name exactly as the 3D view does. iOS AR cannot: its
 * `ARSceneView` has no entity hit-test hook and always reports `null` (#2051).
 */
class TapEvent(
    surfaceId: Int,
    viewId: Int,
    private val x: Float,
    private val y: Float,
    private val z: Float,
    private val nodeName: String?,
) : Event<TapEvent>(surfaceId, viewId) {

    override fun getEventName(): String = NAME

    override fun getEventData(): WritableMap = Arguments.createMap().apply {
        putDouble("x", x.toDouble())
        putDouble("y", y.toDouble())
        putDouble("z", z.toDouble())
        if (nodeName != null) putString("nodeName", nodeName) else putNull("nodeName")
    }

    companion object {
        const val NAME = "topSceneViewTap"
    }
}

/**
 * RN Fabric event delivered to the JS `onPlaneDetected` prop of `<ARSceneView>`.
 *
 * Payload mirrors the TypeScript `PlaneDetectedEvent` interface in `src/index.tsx`:
 * `{ id, type, center, extent }`. Fires once per newly-detected ARCore plane
 * (issue #2053).
 */
class PlaneDetectedEvent(
    surfaceId: Int,
    viewId: Int,
    private val id: String,
    private val type: String,
    private val center: FloatArray,
    private val extent: FloatArray,
) : Event<PlaneDetectedEvent>(surfaceId, viewId) {

    override fun getEventName(): String = NAME

    override fun getEventData(): WritableMap = Arguments.createMap().apply {
        putString("id", id)
        putString("type", type)
        putArray("center", Arguments.fromArray(center))
        putArray("extent", Arguments.fromArray(extent))
    }

    companion object {
        const val NAME = "topSceneViewPlaneDetected"
    }
}

/**
 * Dispatches a [TapEvent] for [view] through React Native's [EventDispatcher],
 * carrying the tapped [node]'s name and world-space position (issue #2053).
 */
internal fun dispatchTapEvent(
    reactContext: ReactContext,
    view: View,
    node: Node?,
) {
    val surfaceId = UIManagerHelper.getSurfaceId(view)
    val dispatcher =
        UIManagerHelper.getEventDispatcherForReactTag(reactContext, view.id) ?: return
    val worldPosition = node?.worldPosition
    dispatcher.dispatchEvent(
        TapEvent(
            surfaceId = surfaceId,
            viewId = view.id,
            x = worldPosition?.x ?: 0f,
            y = worldPosition?.y ?: 0f,
            z = worldPosition?.z ?: 0f,
            nodeName = node?.name,
        )
    )
}

/**
 * Maps an ARCore [Plane] to a [PlaneDetectedEvent] and dispatches it for [view]
 * through React Native's [EventDispatcher] (issue #2053).
 *
 * The JS `PlaneDetectedEvent` only distinguishes `'horizontal'` / `'vertical'`,
 * so ARCore's two horizontal sub-types both collapse to `'horizontal'`.
 */
internal fun dispatchPlaneEvent(
    reactContext: ReactContext,
    view: View,
    plane: Plane,
) {
    val surfaceId = UIManagerHelper.getSurfaceId(view)
    val dispatcher =
        UIManagerHelper.getEventDispatcherForReactTag(reactContext, view.id) ?: return
    val type = when (plane.type) {
        Plane.Type.VERTICAL -> "vertical"
        else -> "horizontal"
    }
    val centerPose = plane.centerPose
    dispatcher.dispatchEvent(
        PlaneDetectedEvent(
            surfaceId = surfaceId,
            viewId = view.id,
            // ARCore exposes no stable string id; the Plane's identity hash is
            // stable for the lifetime of the trackable, which is all the JS
            // `id` field needs.
            id = System.identityHashCode(plane).toString(),
            type = type,
            center = floatArrayOf(centerPose.tx(), centerPose.ty(), centerPose.tz()),
            extent = floatArrayOf(plane.extentX, plane.extentZ),
        )
    )
}
