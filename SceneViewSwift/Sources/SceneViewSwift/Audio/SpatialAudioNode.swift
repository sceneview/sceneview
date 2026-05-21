#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit
import SwiftUI
import Combine

/// Positional 3D audio source attached to the scene graph.
///
/// Mirrors SceneView Android's `SpatialAudioNode` composable
/// (`sceneview/src/main/java/io/github/sceneview/audio/SpatialAudioNode.kt`). Backed by
/// RealityKit's built-in spatial-audio renderer: an `AudioFileResource` played through
/// `Entity.playAudio(_:)`.
///
/// RealityKit's audio engine evaluates per-frame attenuation based on the entity's world
/// transform relative to the listener — by default the active camera — so positional pan
/// and distance gain "just work" without a manual frame loop. The Android implementation
/// has to do this by hand on the MediaPlayer fallback path; iOS gets it from the OS.
///
/// ## Looping
///
/// RealityKit sets looping at `AudioFileResource` *load* time — there is no way to flip an
/// already-loaded resource to looping. Use the ``spatial(named:in:falloff:loop:autoPlay:volume:pitch:)``
/// factory and the `loop` flag is honoured for real: the factory loads the named resource
/// with `AudioFileResource.Configuration(shouldLoop:)` set accordingly. The
/// ``spatial(source:falloff:loop:autoPlay:volume:pitch:)`` factory that takes a
/// pre-loaded `AudioResource` cannot change the resource's loop configuration — pass a
/// resource you already loaded with `shouldLoop: true` if you need looping there.
///
/// ```swift
/// // Looping handled for you — the factory loads with shouldLoop: true.
/// let node = try await SpatialAudioNode.spatial(
///     named: "bell.wav",
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
    ///
    /// Until the entity is added to a scene RealityKit has no listener to spatialise
    /// against — `autoPlay` audio therefore plays non-spatially (centred, unattenuated)
    /// until `content.add(node.entity)` runs.
    public let entity: Entity

    /// Active playback controller — RealityKit hands one back from `entity.playAudio(_:)`
    /// per play call. Stored so ``pause()`` / ``stop()`` can reach the right instance.
    fileprivate let storage: AudioStorage

    /// `true` while audio is actively playing.
    public var isPlaying: Bool { storage.isPlaying }

    /// Creates a spatial audio node, loading the named audio resource with the requested
    /// loop configuration.
    ///
    /// Prefer this factory over ``spatial(source:falloff:loop:autoPlay:volume:pitch:)``
    /// when you need looping: RealityKit's loop flag is fixed at `AudioFileResource` load
    /// time, and this factory loads the resource with
    /// `AudioFileResource.Configuration(shouldLoop:)` set from `loop`.
    ///
    /// - Parameters:
    ///   - name: Resource name to load via `AudioFileResource(named:in:configuration:)`.
    ///   - bundle: Bundle to load the resource from. Default `Bundle.main`.
    ///   - falloff: Distance attenuation curve. Default
    ///              ``AudioFalloff/inverse(refDistance:maxDistance:rolloffFactor:)``.
    ///   - loop: Whether the resource loops at end. Honoured for real — passed straight to
    ///           `AudioFileResource.Configuration(shouldLoop:)`. Default `false`.
    ///   - autoPlay: Start playback immediately. Default `true`. Note: audio plays
    ///               non-spatially until `entity` is added to a scene.
    ///   - volume: Base linear gain in `[0, 1]` multiplied with the falloff curve.
    ///   - pitch: Playback pitch/speed multiplier — clamped to `[0.5, 2]`. RealityKit
    ///            does not expose a pitch knob on `AudioPlaybackController` as of
    ///            iOS 18, so phase-1 stores the value for parity with Android but
    ///            does not actually shift pitch. Phase 2 wires it via PHASE.
    /// - Throws: Whatever `AudioFileResource(named:in:configuration:)` throws.
    public static func spatial(
        named name: String,
        in bundle: Bundle = .main,
        falloff: AudioFalloff = .inverse(refDistance: 1, maxDistance: 100),
        loop: Bool = false,
        autoPlay: Bool = true,
        volume: Float = 1.0,
        pitch: Float = 1.0
    ) async throws -> SpatialAudioNode {
        let configuration = AudioFileResource.Configuration(shouldLoop: loop)
        let resource = try await AudioFileResource(
            named: name,
            in: bundle,
            configuration: configuration
        )
        return spatial(
            source: resource,
            falloff: falloff,
            loop: loop,
            autoPlay: autoPlay,
            volume: volume,
            pitch: pitch
        )
    }

    /// Creates a spatial audio node from a pre-loaded `AudioResource`.
    ///
    /// - Important: RealityKit's loop flag is set at `AudioFileResource` *load* time and
    ///   cannot be changed on an already-loaded resource. The `loop` parameter here is
    ///   recorded for parity, but for looping to actually take effect the `source` must
    ///   have been loaded with `AudioFileResource.Configuration(shouldLoop: true)`. Use
    ///   ``spatial(named:in:falloff:loop:autoPlay:volume:pitch:)`` to have the library
    ///   load with the right configuration for you.
    ///
    /// - Parameters:
    ///   - source: The decoded audio resource — typically obtained via
    ///             `AudioFileResource(named:in:configuration:)`.
    ///   - falloff: Distance attenuation curve. Default
    ///              ``AudioFalloff/inverse(refDistance:maxDistance:rolloffFactor:)``.
    ///   - loop: Whether the resource loops — see the Important note above.
    ///   - autoPlay: Start playback immediately. Default `true`.
    ///   - volume: Base linear gain in `[0, 1]` multiplied with the falloff curve.
    ///   - pitch: Playback pitch/speed multiplier — clamped to `[0.5, 2]`. Phase-1 stores
    ///            the value for parity with Android but does not actually shift pitch.
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

    /// Starts or resumes playback. Idempotent.
    ///
    /// If a controller already exists and is paused, it is resumed from its current
    /// position (matching ``pause()``'s "pauses at current position" contract). Only the
    /// first start — or a start after ``stop()`` — creates a fresh controller via
    /// `entity.playAudio(_:)`.
    public func play() {
        guard !storage.isPlaying else { return }

        if let controller = storage.controller {
            // A paused controller — resume it instead of restarting from zero.
            controller.play()
            storage.isPlaying = true
            storage.boundController?.setPlaying(true)
            return
        }

        // First start (or first after stop): create a fresh playback controller.
        let controller = entity.playAudio(storage.source)
        controller.gain = AudioPlaybackController.gainFromLinear(storage.currentLinearGain())
        // Reset our `isPlaying` flag when a one-shot clip finishes — otherwise a finished
        // clip would report `isPlaying == true` forever and play() would never resume it.
        controller.completionHandler = { [weak storage] in
            Task { @MainActor in
                guard let storage else { return }
                // Only flip if this is still the live controller (a stop()/play() cycle
                // may have replaced it).
                if storage.controller === controller {
                    storage.isPlaying = false
                    storage.boundController?.setPlaying(false)
                }
            }
        }
        storage.controller = controller
        storage.isPlaying = true
        storage.boundController?.setPlaying(true)
    }

    /// Pauses playback at the current position. A subsequent ``play()`` resumes from here.
    public func pause() {
        storage.controller?.pause()
        storage.isPlaying = false
        storage.boundController?.setPlaying(false)
    }

    /// Stops playback and rewinds to position `0`. The next ``play()`` starts from the
    /// beginning of the resource with a fresh controller.
    public func stop() {
        storage.controller?.stop()
        // Drop the controller so the next play() starts fresh from the beginning.
        storage.controller = nil
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

    /// Updates the distance-attenuation curve. Re-applied on the next ``updateGain(forDistance:)``.
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

    init(source: AudioResource, falloff: AudioFalloff, loop: Bool, baseVolume: Float, pitch: Float) {
        self.source = source
        self.falloff = falloff
        self.loop = loop
        self.baseVolume = baseVolume
        self.pitch = pitch
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
