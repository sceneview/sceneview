package io.github.sceneview.demo.demos.internal

import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A question-time snapshot. Missing measurements stay absent, including in scripted mode. */
internal data class AskLiveContext(
    val distanceAheadMeters: Float? = null,
    val surface: AskLiveSurface? = null,
    val cameraHeightMeters: Float? = null,
    val localTime: String = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
    val locale: String = Locale.getDefault().toLanguageTag(),
    val image: AskLiveImage? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        distanceAheadMeters.validLength()?.let { put("distanceAheadMeters", it) }
        surface?.let { value ->
            put("surface", buildJsonObject {
                put("kind", value.kind)
                value.widthMeters.validLength()?.let { put("widthMeters", it) }
                value.heightMeters.validLength()?.let { put("heightMeters", it) }
            })
        }
        cameraHeightMeters?.takeIf { it.isFinite() && it >= 0f }?.let { put("cameraHeightMeters", it) }
        put("localTime", localTime)
        put("locale", locale)
        image?.let { value ->
            put("image", buildJsonObject {
                put("mediaType", "image/jpeg")
                put("data", value.data)
            })
        }
    }
}

internal data class AskLiveImage(val data: String)
internal data class AskLiveSurface(val kind: String, val widthMeters: Float?, val heightMeters: Float?)
internal enum class AskPlaneKind { Vertical, Upward, Downward, Other }
internal data class AskPlaneFacts(
    val kind: AskPlaneKind,
    val centerY: Float,
    val extentX: Float,
    val extentZ: Float,
)

/** The lowest currently tracked upward plane is only a floor estimate, not room recognition. */
internal fun buildAskLiveContext(
    hitDistanceMeters: Float?,
    hitPlane: AskPlaneFacts?,
    planes: List<AskPlaneFacts>,
    cameraY: Float?,
    now: ZonedDateTime = ZonedDateTime.now(),
    locale: Locale = Locale.getDefault(),
): AskLiveContext {
    val floorY = planes.filter { it.kind == AskPlaneKind.Upward && it.centerY.isFinite() }
        .minOfOrNull { it.centerY }
    val surface = hitPlane?.let {
        val kind = when (it.kind) {
            AskPlaneKind.Vertical -> "wall"
            AskPlaneKind.Upward -> if (floorY != null && it.centerY <= floorY + 0.03f) "floor" else "table"
            AskPlaneKind.Downward -> "ceiling"
            AskPlaneKind.Other -> "other"
        }
        AskLiveSurface(kind, it.extentX.validLength(), it.extentZ.validLength())
    }
    return AskLiveContext(
        distanceAheadMeters = hitDistanceMeters.validLength(),
        surface = surface,
        cameraHeightMeters = if (cameraY != null && cameraY.isFinite() && floorY != null) {
            (cameraY - floorY).takeIf { it >= 0f }
        } else null,
        localTime = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        locale = locale.toLanguageTag(),
    )
}

/** Call inside onSessionUpdated. Placement's synthetic AHEAD distance is never a fact. */
internal fun askLiveContextFromFrame(
    frame: Frame,
    planes: Collection<Plane>,
    viewWidth: Int,
    viewHeight: Int,
): AskLiveContext {
    if (frame.camera.trackingState != TrackingState.TRACKING) return AskLiveContext()
    val hit = if (viewWidth > 0 && viewHeight > 0) {
        frame.hitTest(viewWidth / 2f, viewHeight / 2f).firstOrNull()
    } else null
    val hitPlane = (hit?.trackable as? Plane)?.takeIf {
        it.trackingState == TrackingState.TRACKING && it.subsumedBy == null && it.isPoseInPolygon(hit.hitPose)
    }
    return buildAskLiveContext(
        hitDistanceMeters = hit?.distance,
        hitPlane = hitPlane?.toAskPlaneFacts(),
        planes = planes.filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
            .map { it.toAskPlaneFacts() },
        cameraY = frame.camera.pose.ty(),
    )
}

private fun Plane.toAskPlaneFacts() = AskPlaneFacts(
    kind = when (type) {
        Plane.Type.VERTICAL -> AskPlaneKind.Vertical
        Plane.Type.HORIZONTAL_UPWARD_FACING -> AskPlaneKind.Upward
        Plane.Type.HORIZONTAL_DOWNWARD_FACING -> AskPlaneKind.Downward
    },
    centerY = centerPose.ty(),
    extentX = extentX,
    extentZ = extentZ,
)

private fun Float?.validLength() = this?.takeIf { it.isFinite() && it > 0f }
