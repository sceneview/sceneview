#if os(iOS) || os(macOS) || os(visionOS)
import Foundation
import Combine

/// Imperative control surface for a ``SpatialAudioNode``.
///
/// Mirrors Android's `io.github.sceneview.audio.AudioController`. Capture a reference via
/// the static factory's return value, then drive playback from SwiftUI state:
///
/// ```swift
/// @StateObject private var audio = AudioController()
/// // … later, after building the node:
/// audio.bind(node)
/// Button("Play") { audio.play() }
/// ```
///
/// `@MainActor`-isolated because RealityKit's audio APIs require the main actor.
@MainActor
public final class AudioController: ObservableObject {
    /// Reactive flag — `true` while the underlying audio is playing.
    @Published public private(set) var isPlaying: Bool = false

    /// The bound node — captured by value because ``SpatialAudioNode`` is a struct façade
    /// over a reference-typed ``AudioStorage`` (which is what actually owns the
    /// `AudioPlaybackController`). Holding the struct keeps the storage retained as long
    /// as the controller is alive, which matches how SwiftUI `@StateObject` lifetimes
    /// usually outlive the view that built the node.
    private var boundNode: SpatialAudioNode?

    /// Empty initialiser so callers can `@StateObject` one up front and bind it later.
    public init() {}

    /// Attach this controller to a ``SpatialAudioNode``. Called automatically by
    /// ``SpatialAudioNode/controlled(by:)``.
    public func bind(_ node: SpatialAudioNode) {
        boundNode = node
        // Reflect the node's current playing state immediately on bind.
        isPlaying = node.isPlaying
    }

    /// Internal hook used by ``SpatialAudioNode`` to publish a state change.
    func setPlaying(_ value: Bool) {
        if isPlaying != value {
            isPlaying = value
        }
    }

    public func play() {
        boundNode?.play()
    }

    public func pause() {
        boundNode?.pause()
    }

    public func stop() {
        boundNode?.stop()
    }

    /// Seeks the underlying playback head. On iOS RealityKit the seek is best-effort —
    /// `AudioPlaybackController.seek(to:)` is not exposed publicly; the player is restarted
    /// instead. For sample-accurate seeking use the AVAudioEngine integration in phase 2.
    public func seek(toMilliseconds positionMs: Int64) {
        boundNode?.seek(toMilliseconds: positionMs)
    }
}
#endif // os(iOS) || os(macOS) || os(visionOS)
