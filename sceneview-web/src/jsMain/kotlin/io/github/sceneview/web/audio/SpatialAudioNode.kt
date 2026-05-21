package io.github.sceneview.web.audio

import kotlinx.browser.window
import kotlin.js.Promise

/**
 * A minimal 3-component vector used by the spatial-audio API.
 *
 * The whole spatial-audio module is consumed from Kotlin/JS — it relies on
 * `suspend` functions, `external` Web Audio declarations and a `sealed
 * interface`, none of which are `@JsExport`-compatible. [Vec3] is therefore a
 * plain Kotlin/JS value type for Kotlin callers, not a `@JsExport`-ed
 * plain-JavaScript type. It exists so the audio API does not pull
 * `dev.romainguy.kotlin.math.Float3` (an internal geometry type) into its
 * signatures.
 *
 * Mirrors the role of `io.github.sceneview.math.Position` (Android) and
 * `SIMD3<Float>` (iOS) for the spatial-audio feature.
 *
 * @property x X component, metres.
 * @property y Y component, metres.
 * @property z Z component, metres.
 */
public data class Vec3(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
)

/**
 * Distance-attenuation model applied to a [SpatialAudioNode].
 *
 * The three variants map 1:1 onto the Web Audio
 * [PannerNode.distanceModel](https://developer.mozilla.org/docs/Web/API/PannerNode/distanceModel)
 * settings, so behaviour matches the Android (`io.github.sceneview.audio.AudioFalloff`)
 * and iOS (`SceneViewSwift.AudioFalloff`) curves of the same feature.
 */
public sealed interface AudioFalloff {

    /**
     * Inverse distance attenuation — the Web Audio `"inverse"` model and the
     * physically-realistic default.
     *
     * `gain = refDistance / (refDistance + rolloffFactor * (max(distance, refDistance) - refDistance))`
     *
     * @param refDistance   Distance (m) at which gain is `1.0`.
     * @param maxDistance   Distance (m) beyond which gain stops decreasing.
     * @param rolloffFactor How quickly gain drops past [refDistance]. `1f` matches
     *                      the inverse-square style curve; higher falls off faster.
     */
    public data class Inverse(
        val refDistance: Float,
        val maxDistance: Float,
        val rolloffFactor: Float = 1f,
    ) : AudioFalloff

    /**
     * Linear distance attenuation — the Web Audio `"linear"` model. Gain reaches
     * `0.0` exactly at [maxDistance].
     *
     * @param refDistance Distance (m) at which gain is `1.0`.
     * @param maxDistance Distance (m) at which gain hits `0.0`.
     */
    public data class Linear(
        val refDistance: Float,
        val maxDistance: Float,
    ) : AudioFalloff

    /** Disable distance attenuation — gain is always `1.0`. */
    public object None : AudioFalloff
}

// --- Web Audio API external declarations ---------------------------------
// Only the members the spatial-audio feature actually uses are declared.

/**
 * The Web Audio [AudioContext](https://developer.mozilla.org/docs/Web/API/AudioContext)
 * — the processing graph that owns every audio node.
 */
public external class AudioContext {
    /** The final destination node (the speakers / headphones). */
    val destination: AudioDestinationNode

    /** Current hardware audio clock time, in seconds. */
    val currentTime: Double

    /** The single [AudioListener] representing the person hearing the scene. */
    val listener: AudioListener

    /** Creates a [GainNode] for volume control. */
    fun createGain(): GainNode

    /** Creates a [PannerNode] for positional / HRTF panning. */
    fun createPanner(): PannerNode

    /** Creates an [AudioBufferSourceNode] that plays a decoded [AudioBuffer]. */
    fun createBufferSource(): AudioBufferSourceNode

    /** Decodes raw encoded audio bytes into a playable [AudioBuffer]. */
    fun decodeAudioData(audioData: dynamic): Promise<AudioBuffer>

    /** Resumes a context that started in the `suspended` state (autoplay policy). */
    fun resume(): Promise<Unit>
}

/** A decoded, in-memory PCM audio asset. */
public external interface AudioBuffer

/** Base type for every node in the Web Audio graph. */
public external interface AudioNode {
    /** Connects this node's output to [destination]. */
    fun connect(destination: AudioNode)

    /** Disconnects every outgoing connection from this node. */
    fun disconnect()
}

/** The graph's terminal node — the speakers. */
public external interface AudioDestinationNode : AudioNode

/** A scalar audio parameter that can be set instantly or scheduled / ramped. */
public external interface AudioParam {
    /** The current immediate value. */
    var value: Float

    /** Schedules an immediate value change at the given context time. */
    fun setValueAtTime(value: Float, startTime: Double)
}

/** A node that scales the amplitude of its input. */
public external interface GainNode : AudioNode {
    /** Linear gain multiplier, `0.0`..`1.0+`. */
    val gain: AudioParam
}

/** Plays back a decoded [AudioBuffer]. Single-use — create a fresh one per play. */
public external interface AudioBufferSourceNode : AudioNode {
    /** The decoded asset to play. */
    var buffer: AudioBuffer?

    /** Whether playback repeats from the start when it reaches the end. */
    var loop: Boolean

    /**
     * Starts playback.
     *
     * @param whenTime Context time at which to start (`0.0` = now).
     * @param offset   Offset into the buffer, in seconds, at which playback begins
     *                 (`0.0` = clip start). Used by [SpatialAudioNode.seekTo].
     */
    fun start(whenTime: Double = definedExternally, offset: Double = definedExternally)

    /** Stops playback at the given context time (`0.0` = now). */
    fun stop(whenTime: Double = definedExternally)
}

/**
 * A [PannerNode](https://developer.mozilla.org/docs/Web/API/PannerNode) — applies
 * HRTF panning and distance attenuation to its input based on the node's position
 * relative to the [AudioContext.listener].
 */
public external interface PannerNode : AudioNode {
    /** `"HRTF"` for binaural panning, or `"equalpower"` for the cheap stereo pan. */
    var panningModel: String

    /** `"inverse"`, `"linear"`, or `"exponential"`. */
    var distanceModel: String

    /** Distance at which attenuation begins. */
    var refDistance: Double

    /** Distance beyond which attenuation no longer increases. */
    var maxDistance: Double

    /** How fast gain drops between `refDistance` and `maxDistance`. */
    var rolloffFactor: Double

    /** Source X position, world space (modern AudioParam form). */
    val positionX: AudioParam

    /** Source Y position, world space. */
    val positionY: AudioParam

    /** Source Z position, world space. */
    val positionZ: AudioParam

    /**
     * Legacy positional setter for browsers without the `positionX/Y/Z`
     * AudioParam form (older Safari). [SpatialAudioNode] feature-detects and
     * prefers the AudioParam form.
     */
    fun setPosition(x: Float, y: Float, z: Float)
}

/**
 * The Web Audio [AudioListener](https://developer.mozilla.org/docs/Web/API/AudioListener)
 * — represents the person hearing the scene. There is exactly one per [AudioContext].
 *
 * Both the modern `positionX/Y/Z` + `forwardX/.../upZ` AudioParam form and the
 * legacy `setPosition` / `setOrientation` methods are declared; the feature
 * code feature-detects and prefers the AudioParam form.
 */
public external interface AudioListener {
    /** Listener X position (modern form). */
    val positionX: AudioParam

    /** Listener Y position. */
    val positionY: AudioParam

    /** Listener Z position. */
    val positionZ: AudioParam

    /** Listener forward-vector X component. */
    val forwardX: AudioParam

    /** Listener forward-vector Y component. */
    val forwardY: AudioParam

    /** Listener forward-vector Z component. */
    val forwardZ: AudioParam

    /** Listener up-vector X component. */
    val upX: AudioParam

    /** Listener up-vector Y component. */
    val upY: AudioParam

    /** Listener up-vector Z component. */
    val upZ: AudioParam

    /** Legacy listener position setter (older Safari). */
    fun setPosition(x: Float, y: Float, z: Float)

    /** Legacy listener orientation setter (older Safari). */
    fun setOrientation(
        forwardX: Float, forwardY: Float, forwardZ: Float,
        upX: Float, upY: Float, upZ: Float,
    )
}

// --- Shared AudioContext --------------------------------------------------

/**
 * Process-wide [AudioContext]. Created lazily on first use because browsers
 * refuse to start an `AudioContext` outside a user gesture — constructing one
 * eagerly at module load would log an autoplay-policy warning.
 */
private var sharedContext: AudioContext? = null

private fun audioContext(): AudioContext {
    val existing = sharedContext
    if (existing != null) return existing
    val ctx = js("new (window.AudioContext || window.webkitAudioContext)()")
        .unsafeCast<AudioContext>()
    sharedContext = ctx
    return ctx
}

// --- Audio source ---------------------------------------------------------

/**
 * Opaque handle to a decoded audio asset, ready to be played by a
 * [SpatialAudioNode]. Obtain one with [loadAudioSource]; never construct it
 * directly.
 *
 * Mirrors Android's `io.github.sceneview.audio.AudioSource` and iOS's
 * RealityKit `AudioResource`.
 */
public class AudioSource internal constructor(
    internal val buffer: AudioBuffer,
)

/**
 * Fetches an audio file from [url] and decodes it into an [AudioSource].
 *
 * Uses the browser `fetch` API followed by [AudioContext.decodeAudioData], so
 * any format the browser can decode (WAV / MP3 / OGG / AAC / FLAC) is supported.
 * The asset is fully decoded into memory — keep clips short, as a 3D-positional
 * cue typically is.
 *
 * ```kotlin
 * val source = loadAudioSource("audio/bell.wav")
 * val node = SpatialAudioNode(source = source, position = Vec3(0f, 0f, -2f), loop = true)
 * ```
 *
 * Callers that do not run inside a coroutine — `sceneview-web` itself is
 * `Promise`-based, like [io.github.sceneview.web.SceneView] — should use the
 * [loadAudioSourcePromise] variant instead.
 *
 * @param url URL or path of the audio file, resolved relative to the page.
 * @return The decoded [AudioSource].
 * @throws Throwable if the fetch fails or the bytes cannot be decoded.
 */
public suspend fun loadAudioSource(url: String): AudioSource =
    loadAudioSourcePromise(url).awaitAudio()

/**
 * `Promise`-returning twin of [loadAudioSource], for plain-JavaScript callers
 * and Kotlin code that is not inside a coroutine.
 *
 * `sceneview-web` does not depend on `kotlinx-coroutines`, and its public
 * surface (model loading, environment loading) is uniformly `Promise`-based —
 * this overload keeps the audio API consistent with that idiom.
 *
 * ```kotlin
 * loadAudioSourcePromise("audio/bell.wav").then { source ->
 *     SpatialAudioNode(source = source, position = Vec3(0f, 0f, -2f), loop = true)
 * }
 * ```
 *
 * @param url URL or path of the audio file, resolved relative to the page.
 * @return A [Promise] resolving with the decoded [AudioSource].
 */
public fun loadAudioSourcePromise(url: String): Promise<AudioSource> {
    val ctx = audioContext()
    return window.fetch(url)
        .then { response ->
            if (!response.ok) throw Throwable("Failed to fetch audio '$url': HTTP ${response.status}")
            response.arrayBuffer()
        }
        .then { arrayBuffer -> ctx.decodeAudioData(arrayBuffer) }
        .then { decodedPromise -> decodedPromise }
        .unsafeCast<Promise<AudioBuffer>>()
        .then { decoded -> AudioSource(decoded) }
}

/**
 * Minimal `await` bridge for the spatial-audio module so it does not depend on
 * `kotlinx-coroutines-core`'s JS `await` extension (which `sceneview-web` does
 * not pull in). Suspends until the promise settles.
 */
private suspend fun <T> Promise<T>.awaitAudio(): T =
    kotlin.coroutines.suspendCoroutine { cont ->
        this.then(
            onFulfilled = { value -> cont.resumeWith(Result.success(value)) },
            onRejected = { error -> cont.resumeWith(Result.failure(error.toThrowableOrDefault())) },
        )
    }

private fun Throwable?.toThrowableOrDefault(): Throwable =
    this ?: Throwable("Audio promise rejected")

// --- Spatial audio node ---------------------------------------------------

/**
 * Positional 3D audio source for the SceneView web viewer.
 *
 * Backed by the Web Audio API: every node owns its own
 * `AudioBufferSourceNode -> PannerNode -> GainNode -> destination` chain on the
 * shared [AudioContext]. The [PannerNode] applies `"HRTF"` binaural panning and
 * distance attenuation, so as the [position] moves relative to the listener
 * (see [setSpatialAudioListenerPose]) the sound pans between the ears and
 * attenuates with distance — automatically, every audio frame, with no manual
 * per-frame gain loop.
 *
 * API parity with Android's `io.github.sceneview.audio.SpatialAudioNode`
 * composable and iOS's `SceneViewSwift.SpatialAudioNode`.
 *
 * ```kotlin
 * val source = loadAudioSource("audio/bell.wav")
 * val node = SpatialAudioNode(
 *     source = source,
 *     position = Vec3(0f, 0f, -2f),
 *     falloff = AudioFalloff.Inverse(refDistance = 1f, maxDistance = 20f),
 *     loop = true,
 * )
 * // later, as the camera orbits:
 * setSpatialAudioListenerPose(cameraPos, cameraForward, cameraUp)
 * ```
 *
 * @param source   The decoded asset to play, from [loadAudioSource].
 * @param position World-space position of the source. Defaults to the origin.
 * @param falloff  Distance-attenuation curve. Defaults to a realistic inverse curve.
 * @param loop     Whether the asset loops at end. Default `false`.
 * @param autoPlay Start playback immediately on construction. Default `true`.
 * @param volume   Base linear gain, clamped to `[0f, 1f]` to match Android / iOS.
 *                 Default `1f`.
 */
public class SpatialAudioNode(
    private val source: AudioSource,
    position: Vec3 = Vec3(0f, 0f, 0f),
    falloff: AudioFalloff = AudioFalloff.Inverse(refDistance = 1f, maxDistance = 100f),
    private val loop: Boolean = false,
    autoPlay: Boolean = true,
    private val volume: Float = 1f,
) {
    private val context: AudioContext = audioContext()
    private val panner: PannerNode = context.createPanner()
    private val gain: GainNode = context.createGain()

    /** The currently-playing buffer source, or `null` when stopped / paused. */
    private var bufferSource: AudioBufferSourceNode? = null
    private var playing: Boolean = false

    /**
     * World-space position of the audio source. Assigning a new value moves the
     * underlying [PannerNode] immediately, so the next audio frame pans / fades
     * to the new position.
     */
    public var position: Vec3 = position
        set(value) {
            field = value
            applyPosition(value)
        }

    init {
        panner.panningModel = "HRTF"
        applyFalloff(falloff)
        applyPosition(position)
        // PannerNode -> GainNode -> speakers. The buffer source is connected to
        // the panner per play() call (a buffer source is single-use).
        panner.connect(gain)
        gain.connect(context.destination)
        // Clamp to [0, 1] to match the Android and iOS `volume` contract.
        gain.gain.value = volume.coerceIn(0f, 1f)
        if (autoPlay) play()
    }

    /**
     * Starts (or restarts) playback from the beginning.
     *
     * A Web Audio `AudioBufferSourceNode` is single-use, so each [play] creates
     * a fresh source node. Calling [play] while already playing restarts the
     * clip. If the browser suspended the [AudioContext] under its autoplay
     * policy, this also resumes it.
     */
    public fun play() {
        // Browsers start the AudioContext suspended until a user gesture; a
        // play() triggered from a click handler is the right moment to resume.
        runCatching { context.resume() }
        stopInternal()
        val src = context.createBufferSource()
        src.buffer = source.buffer
        src.loop = loop
        src.connect(panner)
        src.start(0.0)
        bufferSource = src
        playing = true
    }

    /**
     * Pauses playback.
     *
     * Web Audio has no native pause for a buffer source, so this stops the
     * current source; a subsequent [play] restarts the clip from the beginning.
     * For looping ambience that is the expected behaviour.
     */
    public fun pause() {
        stopInternal()
        playing = false
    }

    /** Stops playback and releases the current buffer source. */
    public fun stop() {
        stopInternal()
        playing = false
    }

    /**
     * Seeks playback to [positionMs] milliseconds from the start of the clip.
     *
     * A Web Audio `AudioBufferSourceNode` is single-use and has no native seek,
     * so this stops the current source and starts a fresh one from the requested
     * offset — parity with the iOS `seek` and Android `seekTo`. Seeking to `0`
     * rewinds to the beginning. If the node is not currently playing the new
     * offset is still applied: a fresh source is started, so `seek` doubles as a
     * "play from offset". Out-of-range offsets are clamped to the clip duration.
     *
     * @param positionMs Target offset from the clip start, in milliseconds.
     */
    public fun seekTo(positionMs: Long) {
        val offsetSeconds = (positionMs.coerceAtLeast(0L).toDouble() / 1000.0)
        runCatching { context.resume() }
        stopInternal()
        val src = context.createBufferSource()
        src.buffer = source.buffer
        src.loop = loop
        src.connect(panner)
        src.start(0.0, offsetSeconds)
        bufferSource = src
        playing = true
    }

    /** `true` while a buffer source is actively playing. */
    public val isPlaying: Boolean get() = playing

    /** Disconnects every Web Audio node owned by this source. Call when removing it. */
    public fun dispose() {
        stopInternal()
        runCatching { panner.disconnect() }
        runCatching { gain.disconnect() }
        playing = false
    }

    private fun stopInternal() {
        bufferSource?.let { src ->
            runCatching { src.stop(0.0) }
            runCatching { src.disconnect() }
        }
        bufferSource = null
    }

    private fun applyPosition(value: Vec3) {
        // Prefer the modern positionX/Y/Z AudioParam form; fall back to the
        // deprecated setPosition for older Safari that does not expose it.
        val px = panner.asDynamic().positionX
        if (px != null) {
            panner.positionX.setValueAtTime(value.x, context.currentTime)
            panner.positionY.setValueAtTime(value.y, context.currentTime)
            panner.positionZ.setValueAtTime(value.z, context.currentTime)
        } else {
            panner.setPosition(value.x, value.y, value.z)
        }
    }

    private fun applyFalloff(falloff: AudioFalloff) {
        when (falloff) {
            is AudioFalloff.Inverse -> {
                panner.distanceModel = "inverse"
                panner.refDistance = falloff.refDistance.toDouble()
                panner.maxDistance = falloff.maxDistance.toDouble()
                panner.rolloffFactor = falloff.rolloffFactor.toDouble()
            }
            is AudioFalloff.Linear -> {
                panner.distanceModel = "linear"
                panner.refDistance = falloff.refDistance.toDouble()
                panner.maxDistance = falloff.maxDistance.toDouble()
                panner.rolloffFactor = 1.0
            }
            is AudioFalloff.None -> {
                // Provably flat at unity for *every* distance: the Web Audio
                // "linear" distance model is
                //   gain = 1 - rolloffFactor * (clamp(d, ref, max) - ref) / (max - ref)
                // so a rolloffFactor of 0 makes the attenuation term identically
                // zero → gain == 1 at all distances, matching Android/iOS `None`
                // (which return a hard 1f). The "inverse" model is only unity for
                // d >= refDistance, so it cannot be used here.
                panner.distanceModel = "linear"
                panner.refDistance = 1.0
                panner.maxDistance = 2.0  // any value > refDistance; unused at rolloff 0
                panner.rolloffFactor = 0.0
            }
        }
    }
}

/**
 * Updates the Web Audio listener pose so every [SpatialAudioNode] pans / fades
 * correctly relative to where the viewer is.
 *
 * Call this from your render loop whenever the camera moves — e.g. as the orbit
 * camera sweeps around the scene — passing the camera's world position and its
 * forward / up basis vectors. The vectors need not be unit length.
 *
 * Mirrors Android's `setSpatialAudioListenerPose` and iOS's camera-tracked
 * audio listener.
 *
 * @param position World position of the listener (the camera).
 * @param forward  Direction the listener faces.
 * @param up       The listener's up direction.
 */
public fun setSpatialAudioListenerPose(position: Vec3, forward: Vec3, up: Vec3) {
    val ctx = audioContext()
    val listener = ctx.listener
    val now = ctx.currentTime
    // Prefer the modern positionX/forwardX AudioParam form; fall back to the
    // deprecated setPosition / setOrientation pair for older Safari.
    val px = listener.asDynamic().positionX
    if (px != null) {
        listener.positionX.setValueAtTime(position.x, now)
        listener.positionY.setValueAtTime(position.y, now)
        listener.positionZ.setValueAtTime(position.z, now)
        listener.forwardX.setValueAtTime(forward.x, now)
        listener.forwardY.setValueAtTime(forward.y, now)
        listener.forwardZ.setValueAtTime(forward.z, now)
        listener.upX.setValueAtTime(up.x, now)
        listener.upY.setValueAtTime(up.y, now)
        listener.upZ.setValueAtTime(up.z, now)
    } else {
        listener.setPosition(position.x, position.y, position.z)
        listener.setOrientation(forward.x, forward.y, forward.z, up.x, up.y, up.z)
    }
}
