package io.github.sceneview.flutter

// ---------------------------------------------------------------------------
// AR tap-to-place (#3780): pure logic, kept free of ARCore and Filament types
// so the JVM unit tests can pin it (ARTapToPlaceTest).
// ---------------------------------------------------------------------------

/**
 * How far a pinch can resize a placed model, relative to the size it was
 * placed at. The Dart doc for `SceneViewController.placeModel` quotes this
 * range, and the iOS bridge clamps to the same one.
 */
internal val PLACED_SCALE_RANGE = 0.25f..4f

/**
 * Maps an ARCore `Plane.Type` name to the plane type string that Dart
 * receives. `onPlaneDetected` and `onPlaneTap` use the same names. [typeName]
 * is `Plane.Type.name`, passed as a string so this stays testable off-device.
 */
internal fun planeTypeName(typeName: String?): String = when (typeName) {
    "HORIZONTAL_UPWARD_FACING" -> "horizontal_upward"
    "HORIZONTAL_DOWNWARD_FACING" -> "horizontal_downward"
    "VERTICAL" -> "vertical"
    else -> "unknown"
}

/**
 * Encodes a plane hit as the `onPlaneTap` payload that the Dart
 * `ARHitResult.fromMap` decodes.
 *
 * @param translation hit position (x, y, z), in metres.
 * @param rotation hit orientation as a quaternion (x, y, z, w). This is the
 * ARCore `Pose.rotationQuaternion` order.
 */
internal fun planeHitMap(
    id: String,
    translation: FloatArray,
    rotation: FloatArray,
    planeType: String,
    distance: Float,
): Map<String, Any> = mapOf(
    "id" to id,
    "x" to translation[0].toDouble(),
    "y" to translation[1].toDouble(),
    "z" to translation[2].toDouble(),
    "qx" to rotation[0].toDouble(),
    "qy" to rotation[1].toDouble(),
    "qz" to rotation[2].toDouble(),
    "qw" to rotation[3].toDouble(),
    "planeType" to planeType,
    "distance" to distance.toDouble(),
)

/** A decoded `placeModel` call. */
internal data class PlaceModelRequest(
    /** Native id of the hit, used to anchor the model to the tapped plane. */
    val hitId: String?,
    val tx: Float,
    val ty: Float,
    val tz: Float,
    val qx: Float,
    val qy: Float,
    val qz: Float,
    val qw: Float,
    val modelPath: String,
    /** Size of the model's largest dimension, in metres (`ModelNode.scale`). */
    val size: Float,
    val editable: Boolean,
    val draggable: Boolean,
    val rotatable: Boolean,
    val scalable: Boolean,
) {
    val canDrag get() = editable && draggable
    val canRotate get() = editable && rotatable
    val canScale get() = editable && scalable
}

/**
 * Decodes the `placeModel` method-channel arguments:
 * `{hit: ARHitResult.toMap(), model: ModelNode.toMap(), editable, draggable,
 * rotatable, scalable}`.
 *
 * Returns `null` when the hit or the model path is missing. Numbers can
 * arrive as `Double`, `Integer` or `Long` depending on how Dart wrote them,
 * so every numeric read goes through [Number].
 */
internal fun parsePlaceModelRequest(args: Map<*, *>?): PlaceModelRequest? {
    val hit = args?.get("hit") as? Map<*, *> ?: return null
    val model = args["model"] as? Map<*, *> ?: return null
    val modelPath = (model["modelPath"] as? String)?.takeIf { it.isNotBlank() } ?: return null
    fun Map<*, *>.float(key: String, fallback: Float) =
        (this[key] as? Number)?.toFloat() ?: fallback
    fun flag(key: String) = args[key] as? Boolean ?: true
    val size = model.float("scale", 1f).takeIf { it > 0f && it.isFinite() } ?: 1f
    return PlaceModelRequest(
        hitId = hit["id"] as? String,
        tx = hit.float("x", 0f),
        ty = hit.float("y", 0f),
        tz = hit.float("z", 0f),
        qx = hit.float("qx", 0f),
        qy = hit.float("qy", 0f),
        qz = hit.float("qz", 0f),
        qw = hit.float("qw", 1f),
        modelPath = modelPath,
        size = size,
        editable = flag("editable"),
        draggable = flag("draggable"),
        rotatable = flag("rotatable"),
        scalable = flag("scalable"),
    )
}

/**
 * Position of a model node, scaled with `scaleToUnits = size`, such that the
 * model's bounding box sits bottom-centred on its parent's origin: centred
 * on X and Z, with its lowest point at Y = 0.
 *
 * The parent is the anchored pivot, so the model stands on the tapped plane
 * and pinch and twist act around the contact point instead of the model's
 * own origin, wherever the source file put it.
 *
 * @param center bounding-box centre in model units (Filament `Box.center`).
 * @param halfExtent bounding-box half extent in model units
 * (Filament `Box.halfExtent`).
 * @param size target size of the largest dimension, in metres. This is the
 * same scale factor `ModelNode.scaleToUnitCube` applies.
 */
internal fun bottomCenterOffset(center: FloatArray, halfExtent: FloatArray, size: Float): FloatArray {
    val maxExtent = maxOf(halfExtent[0], halfExtent[1], halfExtent[2]) * 2f
    val scale = if (maxExtent > 0f) size / maxExtent else 1f
    return floatArrayOf(
        -center[0] * scale,
        (halfExtent[1] - center[1]) * scale,
        -center[2] * scale,
    )
}

/**
 * Keeps the last few plane hits so that a `placeModel` call arriving a few
 * frames after its `onPlaneTap` can still anchor to the plane that was hit.
 * Access is main-thread only. Taps and method calls both arrive there.
 */
internal class RecentHits<T>(private val capacity: Int = 8) {
    private var nextId = 0
    private val hits = object : LinkedHashMap<String, T>(capacity, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?) =
            size > capacity
    }

    /** Stores [hit] and returns its id. */
    fun put(hit: T): String {
        val id = "hit-${nextId++}"
        hits[id] = hit
        return id
    }

    operator fun get(id: String?): T? = id?.let { hits[it] }

    fun clear() = hits.clear()

    val size get() = hits.size
}
