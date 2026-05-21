package io.github.sceneview.ar.collaborative

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember

/**
 * Creates and remembers a [CollaborativeSession] bound to the composable
 * lifecycle.
 *
 * The session is [CollaborativeSession.start]ed in a [DisposableEffect] and
 * [CollaborativeSession.stop]ped — broadcasting a `bye` to peers — when the
 * composable leaves the tree. This mirrors [io.github.sceneview.ar.rerun.rememberRerunBridge]:
 * a lifecycle-bound helper so the I/O scope is never leaked across recompositions.
 *
 * ### Transport ownership
 *
 * If you pass [closeTransportOnDispose] `= true` (the default) the helper also
 * closes the [transport] on dispose. Pass `false` when the transport outlives
 * the composable (e.g. a Nearby Connections link shared by several screens).
 *
 * ### Typical usage
 *
 * ```kotlin
 * @Composable
 * fun MultiplayerARScreen() {
 *     // Loopback for a single-process demo / test; swap for a real transport
 *     // (Nearby Connections, Firebase, WebRTC) in production.
 *     val transport = remember { LoopbackCollaborativeTransport.LoopbackHub().join("me") }
 *     val session = rememberCollaborativeSession(transport, displayName = "Alice")
 *
 *     ARSceneView(
 *         modifier = Modifier.fillMaxSize(),
 *         onSessionUpdated = { _, frame -> session.onFrame(frame) },
 *     ) {
 *         // Parent collaborative content to session.sharedAnchorNode and
 *         // reconcile against session.placedNodes / session.participants.
 *     }
 * }
 * ```
 *
 * @param transport               the [CollaborativeTransport] relaying peer messages.
 * @param displayName             a human-readable name broadcast to peers.
 *   Defaults to the transport's local peer id.
 * @param poseRateHz              camera-pose broadcast rate. Default
 *   [CollaborativeSession.DEFAULT_POSE_RATE_HZ].
 * @param closeTransportOnDispose close [transport] when the composable is
 *   disposed. Default `true`.
 */
@Composable
public fun rememberCollaborativeSession(
    transport: CollaborativeTransport,
    displayName: String = transport.localPeerId,
    poseRateHz: Int = CollaborativeSession.DEFAULT_POSE_RATE_HZ,
    closeTransportOnDispose: Boolean = true,
): CollaborativeSession {
    // Rebuild the session if any session-defining input changes. The transport
    // identity is a key so swapping transports gives a fresh session.
    val session = remember(transport, displayName, poseRateHz) {
        CollaborativeSession(
            transport = transport,
            displayName = displayName,
            poseRateHz = poseRateHz,
        )
    }

    DisposableEffect(session) {
        session.start()
        onDispose {
            session.stop()
            if (closeTransportOnDispose) {
                transport.close()
            }
        }
    }

    return session
}
