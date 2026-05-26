package io.github.sceneview.ar.collaborative

/**
 * Pure-Kotlin payload framing for [NearbyCollaborativeTransport] — the
 * SDK-independent half of the Nearby transport, kept separate so it is unit
 * testable on the JVM without Play Services on the classpath.
 *
 * ### Why framing is needed
 *
 * Nearby Connections delivers `Payload.Type.BYTES` payloads whole — one
 * `Payload.fromBytes(...)` in equals one `onPayloadReceived(...)` out, with no
 * splitting or coalescing. So a single [CollaborativeWireFormat] line maps 1:1
 * to a single `BYTES` payload and **no length-prefixing is strictly required**.
 *
 * The collaborative stack is, however, a **JSON-lines** protocol: every
 * [CollaborativeWireFormat] message is exactly one line and a peer may
 * legitimately batch several lines into one buffer. To stay robust against
 * that — and against any future stream-oriented transport reusing this logic —
 * the framing here treats the wire as newline-delimited records:
 *
 * - [frame] guarantees the outgoing buffer ends in exactly one `\n`, never
 *   embeds a stray one, and rejects an empty record.
 * - [unframe] splits an incoming buffer on `\n` and yields each non-blank
 *   record, so a single received payload carrying two batched lines surfaces
 *   as two messages.
 *
 * Bytes are UTF-8; [CollaborativeWireFormat] is ASCII-safe JSON so this is a
 * lossless round trip.
 */
internal object NearbyPayloadFraming {

    /** The record delimiter — collaborative AR is a JSON-lines protocol. */
    const val DELIMITER: Char = '\n'

    /**
     * Frames a single outbound [message] for transmission: the returned bytes
     * are the UTF-8 message followed by exactly one trailing [DELIMITER].
     *
     * Any trailing newlines already on [message] are normalised away first so
     * the result never carries a blank record. [CollaborativeSession] hands
     * this transport already-serialized single lines, but normalising keeps
     * the contract total.
     *
     * @throws IllegalArgumentException if [message] is empty or, once trimmed
     *   of trailing newlines, blank — an empty record carries no information
     *   and would just waste a Nearby payload.
     */
    fun frame(message: ByteArray): ByteArray {
        require(message.isNotEmpty()) { "cannot frame an empty message" }
        var text = message.toString(Charsets.UTF_8)
        // Strip any delimiters the caller already appended so we never emit a
        // blank record or a double newline.
        text = text.trimEnd(DELIMITER)
        require(text.isNotBlank()) { "cannot frame a blank message" }
        return (text + DELIMITER).toByteArray(Charsets.UTF_8)
    }

    /**
     * Splits an inbound [payload] into the individual UTF-8 records it carries.
     *
     * A well-behaved peer sends one record per Nearby payload, but a peer that
     * batches several JSON lines into one buffer is handled transparently:
     * every non-blank `\n`-delimited segment becomes one entry. Blank segments
     * (e.g. from a trailing newline) are dropped.
     *
     * @return one [ByteArray] per record, in order. Empty when [payload]
     *   carries no non-blank record.
     */
    fun unframe(payload: ByteArray): List<ByteArray> {
        if (payload.isEmpty()) return emptyList()
        return payload.toString(Charsets.UTF_8)
            .split(DELIMITER)
            .filter { it.isNotBlank() }
            .map { it.toByteArray(Charsets.UTF_8) }
    }
}
