package io.github.sceneview.demo.demos.internal.spatial

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Version 1 uses right-handed, Y-up coordinates in metres. Box order is width, height, depth. */
@Serializable
data class SpatialAnswerSpec(
    val v: Int,
    @SerialName("object") val subject: SpatialObject,
    val dimensionSource: DimensionSource,
    val placement: SpatialPlacement,
    val label: String,
) {
    fun validate(): SpatialError? = when {
        v != 1 -> SpatialError.UnsupportedVersion
        label.length > 60 -> SpatialError.InvalidLabel
        subject is SpatialObject.Box && (subject.sizeM.size != 3 ||
            subject.sizeM.any { !it.isFinite() || it <= 0.01f || it > 10f }) -> SpatialError.InvalidSize
        subject is SpatialObject.Asset && subject.assetId !in SpatialAssets.paths -> SpatialError.UnknownAsset
        else -> null
    }
}

@Serializable
sealed interface SpatialObject {
    @Serializable @SerialName("box")
    data class Box(val sizeM: List<Float>) : SpatialObject
    @Serializable @SerialName("asset")
    data class Asset(val assetId: String) : SpatialObject
}

@Serializable
enum class DimensionSource {
    @SerialName("user") USER,
    @SerialName("estimated") ESTIMATED,
    @SerialName("measured") MEASURED,
}

@Serializable
enum class SpatialPlacement {
    @SerialName("floor") FLOOR,
    @SerialName("table") TABLE,
}

/** Bundled glTF models from assets/manifest.json. Asset geometry retains its authored metre scale. */
object SpatialAssets {
    val paths = mapOf("wishbone-chair" to "models/wishbone_chair.glb")
}

enum class SpatialError {
    MalformedPayload, UnsupportedVersion, InvalidSize, InvalidLabel, UnknownAsset, UnknownSentence,
}

sealed interface SpatialResult {
    data class Success(val spec: SpatialAnswerSpec) : SpatialResult
    data class Error(val reason: SpatialError) : SpatialResult
}

object SpatialPayload {
    private val json = Json { classDiscriminator = "kind"; prettyPrint = true }
    fun encode(spec: SpatialAnswerSpec): String = json.encodeToString(spec)
    fun decode(payload: String): SpatialResult = try {
        val spec = json.decodeFromString<SpatialAnswerSpec>(payload)
        spec.validate()?.let { SpatialResult.Error(it) } ?: SpatialResult.Success(spec)
    } catch (_: SerializationException) {
        SpatialResult.Error(SpatialError.MalformedPayload)
    } catch (_: IllegalArgumentException) {
        SpatialResult.Error(SpatialError.MalformedPayload)
    }
}
