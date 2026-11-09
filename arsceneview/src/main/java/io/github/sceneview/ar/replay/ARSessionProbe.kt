package io.github.sceneview.ar.replay

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import com.google.ar.core.Frame
import com.google.ar.core.Session
import io.github.sceneview.node.Node
import java.io.File

/**
 * One hook that every [io.github.sceneview.ar.ARSceneView] below it in the composition honours —
 * the single injection point a QA harness needs to drive a whole app through a recorded ARCore
 * session, instead of wiring `playbackDataset` and a frame callback into each screen.
 *
 * Provide it once, near the root, and only for a test launch:
 *
 * ```kotlin
 * val probe = ARReplayProbe(playbackDataset = File(recordingPath))
 * CompositionLocalProvider(LocalARSessionProbe provides probe) {
 *     AppContent()   // every ARSceneView in here replays the recording
 * }
 * ```
 *
 * Without a provider ([LocalARSessionProbe] is `null`, the default) `ARSceneView` behaves exactly
 * as before: there is no cost and no behaviour change for a normal launch.
 *
 * [ARReplayProbe] is the ready-made implementation: it counts replayed frames, records when the
 * first plane tracked and whether an anchor exists, and measures how much of the screen the
 * visible models cover.
 */
interface ARSessionProbe {

    /**
     * Recorded ARCore dataset to replay when the `ARSceneView` call site passes neither
     * `playbackDataset` nor `playbackDatasetUri`. An explicit dataset at the call site wins.
     * Read once, when the session is built — the same snapshot rule as `playbackDataset`.
     */
    val playbackDataset: File? get() = null

    /**
     * Called on the main (Filament) thread once per ARCore frame, after the scene has been
     * updated and after the host's own `onSessionUpdated`. [nodes] are the view's root nodes;
     * walk their `childNodes` for the whole tree. What this throws is logged, never rethrown.
     */
    fun onSessionFrame(session: Session, frame: Frame, nodes: List<Node>) {}
}

/**
 * The [ARSessionProbe] for every `ARSceneView` below the provider, or `null` (no probe) by
 * default. See [ARSessionProbe] for the contract.
 */
val LocalARSessionProbe: ProvidableCompositionLocal<ARSessionProbe?> =
    staticCompositionLocalOf { null }
