package io.github.sceneview.demo.demos.internal.spatial

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.Base64

/** Portable v1 answer links: compact UTF-8 SpatialPayload JSON in unpadded base64url. */
object SpatialLink {
    const val MAX_PAYLOAD_CHARS = 2048
    private val alphabet = Regex("[A-Za-z0-9_-]+")

    fun encode(spec: SpatialAnswerSpec): String {
        require(spec.validate() == null) { "Invalid spatial answer" }
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(SpatialPayload.encodeCompact(spec).toByteArray(Charsets.UTF_8))
            .also { require(it.length <= MAX_PAYLOAD_CHARS) { "Spatial answer is too large" } }
    }

    fun decode(payload: String): SpatialResult {
        if (payload.length > MAX_PAYLOAD_CHARS || !alphabet.matches(payload)) {
            return SpatialResult.Error(SpatialError.MalformedPayload)
        }
        return try {
            val bytes = Base64.getUrlDecoder().decode(payload)
            // Reject non-canonical encodings, including nonzero unused trailing bits.
            if (Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) != payload) {
                return SpatialResult.Error(SpatialError.MalformedPayload)
            }
            SpatialPayload.decode(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString())
        } catch (_: CharacterCodingException) {
            SpatialResult.Error(SpatialError.MalformedPayload)
        } catch (_: IllegalArgumentException) {
            SpatialResult.Error(SpatialError.MalformedPayload)
        }
    }

    fun customUri(spec: SpatialAnswerSpec) = "sceneview://demo/spatial-answer?answer=${encode(spec)}"
    fun httpsUri(spec: SpatialAnswerSpec) =
        "https://sceneview.github.io/open?demo=spatial-answer&answer=${encode(spec)}"
}
