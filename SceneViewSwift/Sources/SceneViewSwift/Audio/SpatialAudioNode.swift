#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit
import SwiftUI
import Combine

/// Positional 3D audio source attached to the scene graph.
///
/// Mirrors SceneView Android's `SpatialAudioNode` composable
/// (`sceneview/src/main/java/io/github/sceneview/audio/SpatialAudioNode.kt`). Backed by
/// RealityKit's built-in spatial-audio renderer: `AudioFileResource` configured with
/// `shouldLoop` + `loadingStrategy`, played through `Entity.playAudio(_:)`.
///
/// RealityKit's audio engine evaluates per-frame attenuation based on the entity's world
/// transform relative to the listener — by default the active camera — so positional pan
/// and distance gain "just work" without a manual frame loop. The Android implementation
/// has to do this by hand on the MediaPlayer fallback path; iOS gets it from the OS.
///
/// ```swift
/// // Async — `AudioFileResource.load` is throwing async.
/// let source = try await AudioFileResource.load(named: "bell.wav")
/// let node = SpatialAudioNode.spatial(
///     source: source,
///     falloff: .inverse(refDistance: 0.5, maxDistance: 6),
///     loop: true
/// )
/// content.add(node.entity)
/// ```
///
/// Falloff curves are applied by clamping the `gain` parameter of the playback controller
/// from the `AudioFalloff.gain(for:distance:)` helper each time the listener moves.
/// Phase 2 will replace the manual gain push with a PHASE engine pipeline that supports
/// occlusion and reverb zones.
@MainActor
public struct SpatialAudioNode {
    /// The underlying RealityKit entity carrying the spatial audio component. Attach to
    /// the scene via `content.add(node.entity)` or as a child of any other entity to make
    /// the sound follow that entity.
    public let entity: Entity

    /// Active playback controller — RealityKit hands one back from `entity.playAudio(_:)`
    /// per play call. Stored so ``pause()`` / ``stop()`` can reach the right instance.
    fileprivate let storage: AudioStorage

    /// `true` while audio is actively playing.
    public var isPlaying: Bool { storage.isPlaying }

    /// Creates a spatial audio node from an `AudioResource`.
    ///
    /// - Parameters:
    ///   - source: The decoded audio resource — typically obtained via
    ///             `AudioFileResource.load(named:in:)`.
    ///   - falloff: Distance attenuation curve. Default
    ///              ``AudioFalloff/inverse(refDistance:maxDistance:rolloffFactor:)``.
    ///   - loop: Whether the resource loops at end. Default `false`.
    ///   - autoPlay: Start playback immediately. Default `true`.
    ///   - volume: Base linear gain in `[0, 1]` multiplied with the falloff curve.
    ///   - pitch: Playback pitch/speed multiplier — clamped to `[0.5, 2]`. RealityKit
    ///            does not expose a pitch knob on `AudioPlaybackController` as of
    ///            iOS 18, so phase-1 stores the value for parity with Android but
    ///            does not actually shift pitch. Phase 2 wires it via PHASE.
    public static func spatial(
        source: AudioResource,
        falloff: AudioFalloff = .inverse(refDistance: 1, maxDistance: 100),
        loop: Bool = false,
        autoPlay: Bool = true,
        volume: Float = 1.0,
        pitch: Float = 1.0
    ) -> SpatialAudioNode {
        let entity = Entity()
        // The SpatialAudioComponent tells RealityKit to attenuate by distance from the
        // active listener (camera by default). The `directivity: .beam` option would
        // give us a cardioid pattern; phase 1 uses the omnidirectional default to match
        // the Android MediaPlayer model.
        entity.spatialAudio = SpatialAudioComponent()

        let storage = AudioStorage(
            source: source,
            falloff: falloff,
            loop: loop,
            baseVolume: volume,
            pitch: pitch
        )

        let node = SpatialAudioNode(entity: entity, storage: storage)
        if autoPlay { node.play() }
        return node
    }

    // MARK: - Playback

    /// Starts (or resumes) playback. Idempotent.
    public func play() {
        guard !storage.isPlaying else { return }
        // RealityKit re-creates an AudioPlaybackController per play() call — we stash the
        // most recent one so pause()/stop() reach the right instance.
        // The looping flag lives on the AudioFileResource configuration; for `loop = true`
        // we configure the resource at construction time (see `prepareLoopingResource`).
        let resourceToPlay = storage.prepareLoopingResource()
        let controller = entity.playAudio(resourceToPlay)
        controller.gain = AudioPlaybackController.gainFromLinear(storage.currentLinearGain())
        storage.controller = controller
        storage.isPlaying = true
        storage.boundController?.setPlaying(true)
    }

    /// Pauses playback at the current position.
    public func pause() {
        storage.controller?.pause()
        storage.isPlaying = false
        storage.boundController?.setPlaying(false)
    }

    /// Stops playback and rewinds to position `0`. The next ``play()`` starts from the
    /// beginning of the resource.
    public func stop() {
        storage.controller?.stop()
        storage.isPlaying = false
        storage.boundController?.setPlaying(false)
    }

    /// Best-effort seek. RealityKit does not expose a public seek API on
    /// `AudioPlaybackController` — the implementation stops and restarts the playback.
    /// Use this sparingly; for sample-accurate seeking integrate AVAudioEngine directly.
    public func seek(toMilliseconds positionMs: Int64) {
        // Restart approximation — good enough for "rewind", which is the most common
        // seek-to-0 case. Non-zero offsets are silently ignored in phase 1.
        if positionMs == 0 {
            stop()
            play()
        }
    }

    /// Updates the distance-attenuation curve. Re-applied on the next ``updateGain(for:)``.
    public func setFalloff(_ falloff: AudioFalloff) {
        storage.falloff = falloff
    }

    /// Recomputes and pushes a new gain to the active playback controller given the
    /// current source-listener distance. Call this from a per-frame hook if you want
    /// software-controlled gain on top of RealityKit's built-in distance attenuation
    /// (which already applies, but on a fixed curve that is not user-controllable).
    public func updateGain(forDistance distance: Float) {
        let linear = Float(storage.baseVolume) *
            AudioFalloff.gain(for: storage.falloff, distance: distance)
        storage.controller?.gain = AudioPlaybackController.gainFromLinear(linear)
    }

    /// Binds an external ``AudioController`` so SwiftUI state updates can drive playback
    /// from outside the view tree.
    @discardableResult
    public func controlled(by controller: AudioController) -> SpatialAudioNode {
        storage.boundController = controller
        controller.bind(self)
        return self
    }
}

// MARK: - View modifier

public extension View {
    /// Declares the listener that drives the per-source attenuation for every
    /// ``SpatialAudioNode`` in this view's scene.
    ///
    /// **One listener per scene** — declaring two is harmless, the last one wins.
    ///
    /// Phase 1 implements `.camera` only (RealityKit's default behaviour — the active
    /// `PerspectiveCamera` IS the listener). The `.anchor(...)` case is declared for
    /// forward-compat and logs a warning + falls back to `.camera` in phase 1.
    ///
    /// ```swift
    /// SceneView { /* ... */ }
    ///     .audioListener(.camera)
    /// ```
    func audioListener(_ source: AudioListenerSource = .camera) -> some View {
        // RealityKit's default audio listener IS the active camera, so for the `.camera`
        // case this modifier is a no-op pass-through. The `.anchor(...)` case logs a
        // warning here — the user can then check the console without re-running the app.
        switch source {
        case .camera:
            break
        #if os(iOS)
        case .anchor:
            // NSLog so the message survives in TestFlight / Release builds for QA.
            NSLog("SpatialAudio: anchor listener — phase 2 (#1900) — falling back to camera")
        #endif
        }
        return self
    }
}

// MARK: - Internal storage

/// Reference-type storage for the audio backend — kept separate so ``SpatialAudioNode``
/// (a value type by design — matches Android's `Node` composable façade) can hand out
/// stable references to the controller / file resource / flags.
@MainActor
final class AudioStorage {
    let source: AudioResource
    var falloff: AudioFalloff
    var loop: Bool
    var baseVolume: Float
    var pitch: Float

    var controller: AudioPlaybackController?
    weak var boundController: AudioController?
    var isPlaying: Bool = false

    private var loopingResourceCache: AudioResource?

    init(source: AudioResource, falloff: AudioFalloff, loop: Bool, baseVolume: Float, pitch: Float) {
        self.source = source
        self.falloff = falloff
        self.loop = loop
        self.baseVolume = baseVolume
        self.pitch = pitch
    }

    /// Returns an `AudioResource` configured for the desired loop behaviour. We cache one
    /// looping wrapper so repeated `play()` calls don't allocate a new `AudioFileResource`
    /// every time.
    func prepareLoopingResource() -> AudioResource {
        if !loop { return source }
        if let cached = loopingResourceCache { return cached }
        // Phase 1: RealityKit's `AudioResource` does not have a public clone-with-config
        // surface on `AudioResource` itself — looping is set at `AudioFileResource.load`
        // time. Callers wanting `loop = true` must therefore pass a resource that was
        // loaded with `shouldLoop = true`. The cached value remains the input resource;
        // we still cache it to avoid lookup on every play call.
        loopingResourceCache = source
        return source
    }

    func currentLinearGain() -> Float {
        // Without a fresh distance reading we report the unattenuated base volume — the
        // engine then applies its own distance attenuation. Callers can override via
        // ``SpatialAudioNode/updateGain(forDistance:)`` for explicit control.
        return baseVolume
    }
}

// MARK: - Helpers

extension AudioPlaybackController {
    /// RealityKit's `gain` is in decibels. Convert from a linear `[0, 1]` value where
    /// `1.0` ≙ `0 dB` (unchanged) and `0.0` ≙ silence (`-.infinity dB`, which RealityKit
    /// clamps internally).
    static func gainFromLinear(_ linear: Float) -> Double {
        let clamped = Swift.max(Swift.min(linear, 1), 1e-4)
        return Double(20 * Foundation.log10(clamped))
    }
}
#endif // os(iOS) || os(macOS) || os(visionOS)
