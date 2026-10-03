package io.github.sceneview.demo.demos.internal

import kotlin.math.hypot

/** Detector output reduced to the fields needed for stable label association. */
internal data class ObjectLabelObservation(
    val trackingId: Int?,
    val label: String,
    val confidence: Float,
    val centerX: Float,
    val centerY: Float,
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
 * and returning `null` keeps the previous payload because no better one could be produced. A
 * missing observation is retained briefly to absorb detector flicker, then disposed after
 * [expiryPasses].
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
            tracks[key] = ObjectLabelTrack(
                key = key,
                observation = observation,
                payload = replacement ?: previousPayload,
                missedPasses = 0,
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
