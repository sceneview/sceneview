package io.github.sceneview.demo.demos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.sceneview.ar.collaborative.CollaborativeSession
import io.github.sceneview.ar.collaborative.LoopbackCollaborativeTransport
import io.github.sceneview.demo.DemoScaffold

/**
 * Collaborative AR demo — proves the multi-user sync stack end-to-end on a
 * **single device, no networking**.
 *
 * Real multiplayer AR needs two phones and a [io.github.sceneview.ar.collaborative.CollaborativeTransport]
 * implementation (Nearby Connections / Firebase / WebRTC). To keep this demo
 * always-runnable in the showcase app and in CI, it instead drives **two**
 * [CollaborativeSession]s — "Alice" and "Bob" — through an in-process
 * [LoopbackCollaborativeTransport.LoopbackHub]. Tapping *Alice places a cube*
 * broadcasts a node placement from Alice's session; the panel then shows that
 * same node appearing in Bob's `placedNodes` (and vice versa) — exactly the
 * data flow that would cross the network between two real devices.
 *
 * The production wiring is identical: swap the loopback transport for a real
 * one and parent your content to `CollaborativeSession.sharedAnchorNode` (an
 * ARCore Cloud Anchor). See the `ar-cloud-anchor` demo for the shared-frame
 * half and `llms.txt` "Collaborative AR" for the full recipe.
 */
@Composable
fun ARCollaborativeDemo(onBack: () -> Unit) {
    // One in-process hub wiring two simulated peers together.
    val hub = remember { LoopbackCollaborativeTransport.LoopbackHub() }
    val alice = remember {
        CollaborativeSession(hub.join("alice"), displayName = "Alice")
    }
    val bob = remember {
        CollaborativeSession(hub.join("bob"), displayName = "Bob")
    }

    // Start both sessions for the lifetime of the screen; stop on dispose so
    // each broadcasts a `bye` and the supervisor scopes are released.
    DisposableEffect(alice, bob) {
        alice.start()
        bob.start()
        onDispose {
            alice.stop()
            bob.stop()
        }
    }

    // Monotonic counters so each placement gets a unique node key.
    var aliceCubeCount by remember { mutableIntStateOf(0) }
    var bobCubeCount by remember { mutableIntStateOf(0) }

    DemoScaffold(title = "Collaborative AR", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Two in-process sessions over a loopback transport",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "ARCore has no collaboration API — multi-user AR is a shared " +
                    "Cloud Anchor (one coordinate frame) plus a pluggable transport " +
                    "relaying state. This demo runs both peers in one process so the " +
                    "sync is visible with no second phone and no networking.",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        aliceCubeCount++
                        alice.placeNode(
                            nodeKey = "alice-cube-$aliceCubeCount",
                            modelKey = "cube",
                            translation = floatArrayOf(
                                0.15f * aliceCubeCount, 0f, -0.5f,
                            ),
                            quaternion = floatArrayOf(0f, 0f, 0f, 1f),
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Alice places a cube") }
                Button(
                    onClick = {
                        bobCubeCount++
                        bob.placeNode(
                            nodeKey = "bob-cube-$bobCubeCount",
                            modelKey = "sphere",
                            translation = floatArrayOf(
                                -0.15f * bobCubeCount, 0f, -0.5f,
                            ),
                            quaternion = floatArrayOf(0f, 0f, 0f, 1f),
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Bob places a sphere") }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SessionCard(
                    title = "Alice's session",
                    session = alice,
                    modifier = Modifier.weight(1f),
                )
                SessionCard(
                    title = "Bob's session",
                    session = bob,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = "Each card is one CollaborativeSession's view. A cube placed " +
                    "by Alice appears in Bob's list (and vice versa) — that is the " +
                    "exact data that would travel over a real transport between two " +
                    "phones sharing a Cloud Anchor.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Renders one [CollaborativeSession]'s observed participants + placed nodes. */
@Composable
private fun SessionCard(
    title: String,
    session: CollaborativeSession,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier, shape = RoundedCornerShape(12.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)

            Text(
                text = "Participants: ${session.participants.size}",
                style = MaterialTheme.typography.labelMedium,
            )
            session.participants.forEach { participant ->
                Text(
                    text = "  • ${participant.displayName}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }

            Spacer(Modifier.width(2.dp))
            Text(
                text = "Placed nodes: ${session.placedNodes.size}",
                style = MaterialTheme.typography.labelMedium,
            )
            session.placedNodes.forEach { node ->
                Text(
                    text = "  • ${node.nodeKey} (${node.modelKey}) by ${node.ownerPeerId}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (session.placedNodes.isEmpty()) {
                Box(
                    modifier = Modifier.padding(start = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = "  (none yet)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
