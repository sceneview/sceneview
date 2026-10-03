package io.github.sceneview.demo.demos.internal

import kotlin.math.hypot

/**
 * Detector output reduced to the fields needed for stable label association and placement.
 * Coordinates are detector-output pixels; [bottomY] is the lower edge of the bounding box, where
 * the object meets its support.
 */
internal data class ObjectLabelObservation(
    val trackingId: Int?,
    val label: String,
    val confidence: Float,
    val centerX: Float,
    val centerY: Float,
    val bottomY: Float = centerY,
)

/** One associated detector track and its current render payload. */
internal data class ObjectLabelTrack<T>(
    val key: String,
    val observation: ObjectLabelObservation,
    val payload: T?,
    val missedPasses: Int,
)

/**
 * Associates ML Kit tracking IDs (or nearest same-label fallbacks) and owns payload disposal.
 *
 * `createPayload` receives the track's previous payload. Returning that same instance keeps it
 * (nothing is disposed), returning a different one replaces and disposes the previous payload,
 * and returning `null` means the object could not be placed on this pass.
 *
 * A payload is retained briefly to absorb flicker, then disposed after [expiryPasses]
 * consecutive passes that did not confirm it — the object was not detected, or it was detected
 * but `createPayload` returned `null`. The second case matters: an object moved to where no
 * surface is known must not leave its label behind at the old place for as long as it stays
 * in view. Such a track survives without a payload and gets one on the next pass that can
 * place it.
 */
internal class ObjectLabelTracker<T>(
    private val expiryPasses: Int = 3,
    private val fallbackDistancePx: Float = 160f,
    private val maxTracks: Int = 6,
    private val dispose: (T) -> Unit,
) {
    private val tracks = linkedMapOf<String, ObjectLabelTrack<T>>()
    private var nextFallbackId = 0L

    init {
        require(expiryPasses > 0)
        require(fallbackDistancePx >= 0f)
        require(maxTracks > 0)
    }

    fun reconcile(
        observations: List<ObjectLabelObservation>,
        createPayload: (observation: ObjectLabelObservation, previous: T?) -> T?,
    ): List<ObjectLabelTrack<T>> {
        val usedKeys = mutableSetOf<String>()
        observations.forEach { observation ->
            val key = observation.trackingId?.let { "tracking:$it" }
                ?: nearestFallbackKey(observation, usedKeys)
                ?: "fallback:${nextFallbackId++}"
            if (!usedKeys.add(key)) return@forEach

            val previous = tracks[key]
            val previousPayload = previous?.payload
            val replacement = createPayload(observation, previousPayload)
            if (replacement != null && previousPayload != null && replacement !== previousPayload) {
                dispose(previousPayload)
            }
            // A pass that could not place the object does not confirm where its payload is:
            // it counts toward expiry like a pass that did not see the object at all.
            val unconfirmed = previousPayload.takeIf { replacement == null }
            val missed = if (unconfirmed != null) (previous?.missedPasses ?: 0) + 1 else 0
            val expired = unconfirmed != null && missed >= expiryPasses
            if (unconfirmed != null && expired) dispose(unconfirmed)
            tracks[key] = ObjectLabelTrack(
                key = key,
                observation = observation,
                payload = if (expired) null else replacement ?: previousPayload,
                missedPasses = if (expired) 0 else missed,
            )
        }

        tracks.keys.toList().forEach { key ->
            if (key !in usedKeys) {
                val previous = tracks.getValue(key)
                val missed = previous.missedPasses + 1
                if (missed >= expiryPasses) {
                    tracks.remove(key)?.payload?.let(dispose)
                } else {
                    tracks[key] = previous.copy(missedPasses = missed)
                }
            }
        }

        while (tracks.size > maxTracks) {
            val oldestKey = tracks.keys.first()
            tracks.remove(oldestKey)?.payload?.let(dispose)
        }
        return tracks.values.toList()
    }

    fun clear() {
        tracks.values.forEach { it.payload?.let(dispose) }
        tracks.clear()
    }

    private fun nearestFallbackKey(
        observation: ObjectLabelObservation,
        usedKeys: Set<String>,
    ): String? = tracks.values
        .asSequence()
        .filter { it.key.startsWith("fallback:") }
        .filter { it.key !in usedKeys && it.observation.label == observation.label }
        .map { track ->
            track.key to hypot(
                track.observation.centerX - observation.centerX,
                track.observation.centerY - observation.centerY,
            )
        }
        .filter { (_, distance) -> distance <= fallbackDistancePx }
        .minByOrNull { (_, distance) -> distance }
        ?.first
}
