package io.github.sceneview.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.length
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.NodeScope
import io.github.sceneview.SceneScope
import io.github.sceneview.math.Position

/**
 * Positional 3D audio source attached to the scene graph.
 *
 * Acts like any other [io.github.sceneview.node.Node]: it sits at [position] in world
 * space and is removed from the audio engine when the composition leaves the tree.
 *
 * **Position must be supplied as Compose state.** The player reads the [position]
 * *parameter* on every recomposition (via [SideEffect]); it does **not** read the enclosing
 * Filament node's world transform. A parent animation that moves the node imperatively
 * (e.g. through `Node.onFrame` writes) therefore does **not** move the sound — pass the
 * animated position as a Compose `State` so the recomposition path keeps the audio source
 * pose current. Driving the source from a `withFrameNanos` loop (as the demo does) is the
 * supported pattern.
 *
 * Backend choice:
 * - **Phase 1 (v4.12 — this release):** every source is rendered through its own private
 *   [android.media.MediaPlayer] with per-frame L/R [android.media.MediaPlayer.setVolume]
 *   pan computed against the active listener. No HRTF, no head tracking, but works on
 *   every supported API level (24+) without any audio-asset preprocessing. Each node owns
 *   its own player, so two nodes backed by the same [AudioSource] never cross-talk.
 * - **Phase 2 (#1900):** API 33+ devices route through `AudioTrack` +
 *   `android.media.Spatializer` for true HRTF binaural mix on supported headphones,
 *   falling back to the phase-1 MediaPlayer path on older devices or when the platform
 *   reports `Spatializer.isAvailable == false`.
 *
 * Drive the listener pose with [setSpatialAudioListenerPose] from a `SceneView` `onFrame`
 * callback so gain / pan track the camera — see [AudioListener] for the exact snippet.
 *
 * ```kotlin
 * SceneView(
 *     // … cameraNode, etc.
 *     onFrame = { setSpatialAudioListenerPose(cameraNode) },   // ears follow the camera
 * ) {
 *     AudioListener()                                          // declares phase-1 intent
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
 * @param source     The audio asset to play (obtain via [rememberAudioSource]). Safe to
 *                   share across several [SpatialAudioNode]s — each builds its own player.
 * @param position   World-space source position. Pass it as Compose state if it is
 *                   animated (see the note above).
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
    // The audio source piggy-backs on a plain Node so it nests in SceneScope like every
    // other composable and can carry children. The Filament entity carries no renderable.
    // Note: the player tracks `position` via the parameter below, NOT via this Node's
    // world transform — see the composable's KDoc.
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

        // Push reactive props on every recomposition. `position` is declarative: when the
        // caller updates it (directly or via a withFrameNanos loop) this SideEffect
        // re-reads it and the player self-refreshes its gain in the same frame.
        SideEffect {
            player.falloff = falloff
            player.setLoop(loop)
            player.setBaseVolume(volume)
            player.setPitch(pitch)
            player.sourcePosition = position
        }

        // Register with the engine and fully release the player on dispose.
        DisposableEffect(player) {
            SpatialAudioEngine.register(player)
            if (autoPlay) player.play()
            onDispose {
                player.destroy()
            }
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
 * Marks the intent to use a camera-tracked audio listener for the [SpatialAudioNode]s in
 * this scene.
 *
 * **Phase 1 (v4.12) — the caller drives the listener.** [SceneScope] does not expose the
 * camera node, so this composable cannot read the camera or push a pose itself. In phase 1
 * it is purely a declarative marker; you **must** drive the listener yourself by calling
 * [setSpatialAudioListenerPose] from the `SceneView` `onFrame` callback:
 *
 * ```kotlin
 * SceneView(
 *     cameraNode = cameraNode,
 *     onFrame = { setSpatialAudioListenerPose(cameraNode) },   // ears follow the camera
 * ) {
 *     AudioListener()                                          // documents the intent
 *     // … positional sources …
 * }
 * ```
 *
 * Automatic, frame-driven camera tracking (so `AudioListener()` alone is enough) is
 * **phase 2** of [#1900](https://github.com/sceneview/sceneview/issues/1900) — it needs
 * the engine to reach the active camera, which requires a core `SceneScope` change.
 *
 * [AudioListenerSource.Anchor] is likewise reserved for phase 2; it is declared today so
 * the public API does not churn between phases. The phase-1 fallback logs a warning and
 * substitutes the camera at runtime.
 *
 * @param source Where the virtual ears sit. Default [AudioListenerSource.Camera].
 */
@Composable
fun SceneScope.AudioListener(
    source: AudioListenerSource = AudioListenerSource.Camera,
) {
    // Phase 1: only Camera is implemented. Anchor is API-stable but logs + falls back.
    remember(source) {
        when (source) {
            is AudioListenerSource.Camera -> Unit
            is AudioListenerSource.Anchor -> android.util.Log.w(
                TAG,
                "Anchor listener — phase 2 (#1900) — falling back to camera"
            )
        }
    }
    // No effect body: phase-1 listener tracking is caller-driven via
    // setSpatialAudioListenerPose from a SceneView onFrame (see the KDoc above).
}

/**
 * Pushes the listener pose for the [SpatialAudioEngine] so every [SpatialAudioNode] pans /
 * fades correctly relative to where the listener is.
 *
 * Call this from a `SceneView` `onFrame` callback (or any per-frame hook) whenever the
 * listener moves — e.g. as the camera orbits. The convenience overload that takes a
 * `CameraNode` lives in `arsceneview` is not needed here: pass the camera's world
 * position, forward and up vectors directly.
 *
 * The basis is `(position, forward, up)` — the same convention as the Web platform's
 * `setSpatialAudioListenerPose` and the Web Audio `AudioListener`. The listener's right
 * vector is derived internally via `right = normalize(forward × up)`.
 *
 * The vectors must already be in world space and need not be unit-length — the engine
 * normalises them internally.
 *
 * @param position World position of the virtual ears.
 * @param forward  Direction the listener is facing.
 * @param up       The listener's up direction.
 */
fun setSpatialAudioListenerPose(
    position: Position,
    forward: Position,
    up: Position,
) {
    val fwdVec = Float3(forward.x, forward.y, forward.z)
    val fwd = if (length(fwdVec) > 1e-4f) {
        normalize(fwdVec).let { Position(it.x, it.y, it.z) }
    } else {
        Position(z = -1f)
    }
    val right = rightFromForwardUp(fwd, up)
    SpatialAudioEngine.setListenerPose(position, fwd, right)
}
