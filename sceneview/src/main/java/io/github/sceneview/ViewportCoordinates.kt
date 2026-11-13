package io.github.sceneview

import com.google.android.filament.Camera
import com.google.android.filament.View
import com.google.android.filament.Viewport
import dev.romainguy.kotlin.math.Float2
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** A clipped viewport and the surface it belongs to. All pixel inputs use the full view. */
internal data class ViewportCoordinates(
    val surfaceWidth: Int,
    val surfaceHeight: Int,
    val left: Int,
    val bottom: Int,
    val width: Int,
    val height: Int,
) {
    val top get() = surfaceHeight - bottom - height
    fun toViewport() = Viewport(left, bottom, width, height)

    /** Full-view pixels (top-left) to Filament picking/manipulator pixels (bottom-left). */
    fun toViewportPixels(x: Float, y: Float) = Float2(x - left, surfaceHeight - y - bottom)

    /** Full-view normalized coordinates and viewport normalized coordinates both point up. */
    fun toViewportNormalized(point: Float2) = Float2(
        (point.x * surfaceWidth - left) / width,
        (point.y * surfaceHeight - bottom) / height,
    )

    fun toViewNormalized(point: Float2) = Float2(
        (point.x * width + left) / surfaceWidth,
        (point.y * height + bottom) / surfaceHeight,
    )
}

/** Pixel edges are rounded once; an over-covered surface retains one pixel on each axis. */
internal fun clippedViewport(width: Int, height: Int, padding: ViewportPadding): ViewportCoordinates {
    val w = width.coerceAtLeast(1)
    val h = height.coerceAtLeast(1)
    fun edge(value: Float, limit: Int) =
        if (value.isFinite()) value.coerceIn(0f, limit.toFloat()).roundToInt() else 0
    val left = edge(padding.left, w - 1)
    val bottom = edge(padding.bottom, h - 1)
    val right = edge(padding.right, w - left - 1)
    val top = edge(padding.top, h - bottom - 1)
    return ViewportCoordinates(w, h, left, bottom, w - left - right, h - top - bottom)
}

/**
 * Main-thread metadata only while clipping. Filament has no surface dimensions on View, and
 * Engine.getCameraComponent returns a fresh Java wrapper each time, so cameras use native ids.
 * Weak view references and explicit release on detach prevent retaining a renderer or camera.
 */
internal object ClippedViewports {
    private data class Entry(val owner: Any, val cameraId: Long, val coordinates: ViewportCoordinates)
    private val views = WeakHashMap<View, Entry>()
    private val cameras = mutableMapOf<Long, WeakReference<View>>()

    fun set(view: View, camera: Camera, coordinates: ViewportCoordinates?, owner: Any) {
        clear(view)
        if (coordinates != null) {
            val id = camera.nativeObject
            views[view] = Entry(owner, id, coordinates)
            cameras[id] = WeakReference(view)
        }
    }

    fun clear(view: View, owner: Any? = null) {
        if (owner != null && views[view]?.owner !== owner) return
        views.remove(view)?.let { entry ->
            if (cameras[entry.cameraId]?.get() === view) cameras.remove(entry.cameraId)
        }
    }

    fun owned(view: View, owner: Any): ViewportCoordinates? =
        views[view]?.takeIf { it.owner === owner }?.coordinates

    fun get(view: View): ViewportCoordinates? = views[view]?.coordinates
    fun get(camera: Camera): ViewportCoordinates? = cameras[camera.nativeObject]?.get()?.let(::get)
}

internal val View.surfaceWidth get() = ClippedViewports.get(this)?.surfaceWidth ?: viewport.width
internal val View.surfaceHeight get() = ClippedViewports.get(this)?.surfaceHeight ?: viewport.height
