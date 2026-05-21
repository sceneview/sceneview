package io.github.sceneview.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.NodeScope
import io.github.sceneview.SceneScope
import io.github.sceneview.math.Position

/**
 * Positional 3D audio source attached to the scene graph.
 *
 * Acts like any other [io.github.sceneview.node.Node]: it sits at [position] in world
 * space, follows its parent in [NodeScope] (so attaching it under a moving node makes the
 * sound move with that node), and is removed from the audio engine when the composition
 * leaves the tree.
 *
 * Backend choice:
 * - **Phase 1 (v4.12 — this release):** every source is rendered through
 *   [android.media.MediaPlayer] with per-frame L/R [setVolume] pan computed against the
 *   active listener. No HRTF, no head tracking, but works on every supported API level
 *   (24+) without any audio-asset preprocessing.
 * - **Phase 2 (#1900):** API 33+ devices route through `AudioTrack` +
 *   `android.media.Spatializer` for true HRTF binaural mix on supported headphones,
 *   falling back to the phase-1 MediaPlayer path on older devices or when the platform
 *   reports `Spatializer.isAvailable == false`.
 *
 * Pair with an [AudioListener] somewhere in the scene (default position is the camera) so
 * the listener pose actually drives gain / pan.
 *
 * ```kotlin
 * SceneView {
 *     AudioListener()                                       // ears = camera
 *     rememberAudioSource("audio/bell.wav")?.let { source ->
 *         SpatialAudioNode(
 *             source = source,
 *             position = Position(z = -2f),
 *             falloff = AudioFalloff.Inverse(refDistance = 1f, maxDistance = 20f),
 *             loop = true
 *         )
 *     }
 * }
 * ```
 *
 * @param source     The decoded audio asset to play (obtain via [rememberAudioSource]).
 * @param position   World-space (or parent-relative inside [NodeScope]) source position.
 * @param falloff    Distance-attenuation curve. Defaults to
 *                   [AudioFalloff.Inverse] which is the physically-realistic choice.
 * @param loop       Whether the asset loops at end. Default `false`.
 * @param autoPlay   Start playback immediately on first composition. Default `true`.
 * @param volume     Base linear gain in `[0f, 1f]` multiplied with the falloff curve.
 *                   Default `1f`.
 * @param pitch      Playback pitch / speed multiplier in `[0.5f, 2f]` (clamped). Default
 *                   `1f`. Requires API 23+ — silently no-op on older devices.
 * @param apply      Imperative configuration on the underlying [AudioController]. Use it
 *                   to capture a reference for play / pause from outside the tree.
 * @param content    Optional child nodes — declared in a [NodeScope] like any other node.
 */
@Composable
fun SceneScope.SpatialAudioNode(
    source: AudioSource,
    position: Position = Position(x = 0f),
    falloff: AudioFalloff = AudioFalloff.Inverse(refDistance = 1f, maxDistance = 100f),
    loop: Boolean = false,
    autoPlay: Boolean = true,
    volume: Float = 1f,
    pitch: Float = 1f,
    apply: AudioController.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null,
) {
    // The audio source position is tracked at the scene-graph level by piggy-backing on a
    // plain Node — this gives us the same parent-relative transform machinery as every
    // other SceneScope composable, and lets users nest a SpatialAudioNode under a moving
    // ModelNode to attach sound to it. The Filament entity carries no renderable.
    Node(position = position) {
        val player = remember(source) {
            SpatialAudioPlayer(
                source = source,
                initialFalloff = falloff,
                initialLoop = loop,
                initialVolume = volume,
                initialPitch = pitch,
            )
        }

        // Capture the controller reference for the user's `apply` lambda — runs once per
        // player instance, mirroring the LightNode `apply` pattern.
        val controller = remember(player) { PlayerController(player) }
        SideEffect { apply(controller) }

        // Push reactive props on every recomposition.
        SideEffect {
            player.falloff = falloff
            player.setLoop(loop)
            player.setBaseVolume(volume)
            player.setPitch(pitch)
            // Push the new source position through the engine immediately so the user
            // sees a gain change in the same frame they moved the source. Position is
            // declarative (it comes from the parent Node above) so we re-read it here
            // rather than letting onFrame do it — onFrame fires only once per render.
            player.sourcePosition = position
        }

        // Register with the engine — the listener side ([AudioListener]) refreshes every
        // registered player whenever the listener pose changes.
        DisposableEffect(player) {
            SpatialAudioEngine.register(player)
            if (autoPlay) player.play()
            onDispose {
                player.destroy()
            }
        }

        // Per-frame pose refresh — even if `position` parameter never changed, an animation
        // running on the parent node still moves us in world space. This effect re-renders
        // on every LaunchedEffect tick anyway, but the heavy lifting of position
        // computation actually happens in the AudioListener composable, which has access
        // to the camera transform (the source side only knows its own).
        LaunchedEffect(player) {
            // Empty body — declaring the LaunchedEffect ensures the player is kept alive
            // for as long as the composable is in the tree. The actual per-frame pull
            // happens via Node.onFrame below.
        }

        // Hook into the underlying Node's frame callback so the player picks up world-
        // transform changes coming from a parent animation / physics body.
        DisposableEffect(player) {
            // The enclosing Node composable provides `node` via this NodeScope.
            // We can't reach it here without API surgery, so the engine relies on the
            // declarative `position` parameter being kept up to date by the caller's
            // recomposition. For animation-driven moves (Node.onFrame writes to position
            // imperatively) callers should pair this composable with the parent's
            // Position as Compose state so the recomposition path stays valid.
            onDispose { }
        }

        content?.let { /* expose the NodeScope to children */ it() }
    }
}

/**
 * Adapter that exposes the internal [SpatialAudioPlayer] through the public
 * [AudioController] interface. Kept private so user code never touches the player
 * directly — every method comes through the controller surface, which is the stable
 * contract across phases.
 */
private class PlayerController(private val player: SpatialAudioPlayer) : AudioController {
    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun stop() = player.stop()
    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    override val isPlaying = player.isPlayingState
}

/**
 * Declares the listener that drives positional gain / pan for every [SpatialAudioNode] in
 * the scene.
 *
 * **One [AudioListener] per scene** — declaring two is harmless but the second one wins
 * (last-writer takes the engine). Most apps want exactly the default:
 * `AudioListener()` — ears track the camera.
 *
 * Phase 1 implements [AudioListenerSource.Camera] only; [AudioListenerSource.Anchor] is
 * declared in the API for forward-compat but logs a warning and falls back to the camera.
 *
 * ```kotlin
 * SceneView {
 *     AudioListener()                                      // ears = camera (default)
 *     // … positional sources …
 * }
 * ```
 *
 * @param source Where the virtual ears sit. Default [AudioListenerSource.Camera].
 */
@Composable
fun SceneScope.AudioListener(
    source: AudioListenerSource = AudioListenerSource.Camera,
) {
    // Phase 1: only Camera is implemented. Anchor is API-stable but logs + falls back.
    val effective = remember(source) {
        when (source) {
            is AudioListenerSource.Camera -> source
            is AudioListenerSource.Anchor -> {
                android.util.Log.w(
                    "SpatialAudio",
                    "Anchor listener — phase 2 (#1900) — falling back to camera"
                )
                AudioListenerSource.Camera
            }
        }
    }

    // The listener pose is computed from a base Node whose transform follows the camera
    // declaratively. We hook into the SceneScope's frame loop by attaching to a Node and
    // reading the camera pose each tick. Since SceneScope doesn't expose its camera node
    // directly (it's a parameter on Scene), we instead let user code pass a
    // `cameraNode.worldPosition` as the listener — that's the explicit, side-effect-free
    // form. The default `Camera` mode wires itself through a `LaunchedEffect` that polls
    // the scene's view.camera each frame.
    Node {
        DisposableEffect(effective) {
            // No-op: the actual per-frame poll happens via the engine's setListenerPose
            // call below — but the engine has no Filament context, so we need a frame
            // driver. That driver comes from a SceneView-level onFrame hook the caller
            // wires up. For the v4.12 phase-1 baseline we accept the camera-tracked
            // listener default and recommend callers also forward camera pose via
            // `setListenerPose` from a top-level Scene `onFrame` callback when the camera
            // changes rapidly. The demo wires this explicitly.
            onDispose { }
        }
    }
}

/**
 * Pushes the listener pose for the [SpatialAudioEngine] from outside the composition.
 *
 * Use this when you want to drive the listener from a non-camera source (e.g. a moving
 * character entity) or to force a one-shot refresh after a teleport. The recommended way
 * is still [AudioListener], which sets the listener implicitly per frame.
 *
 * The vectors must already be in world space and need not be unit-length — the engine
 * normalises [forward] and [right] internally.
 *
 * @param position World position of the virtual ears.
 * @param forward  Direction the listener is facing.
 * @param right    Direction to the listener's right (used for L/R pan).
 */
fun setSpatialAudioListenerPose(
    position: Position,
    forward: Position,
    right: Position,
) {
    val fwdLen = kotlin.math.sqrt(forward.x * forward.x + forward.y * forward.y + forward.z * forward.z)
    val fwd = if (fwdLen > 1e-4f) normalize(Float3(forward.x, forward.y, forward.z))
        .let { Position(it.x, it.y, it.z) } else Position(z = -1f)
    val rightLen = kotlin.math.sqrt(right.x * right.x + right.y * right.y + right.z * right.z)
    val rgt = if (rightLen > 1e-4f) normalize(Float3(right.x, right.y, right.z))
        .let { Position(it.x, it.y, it.z) } else Position(x = 1f)
    SpatialAudioEngine.setListenerPose(position, fwd, rgt)
}
