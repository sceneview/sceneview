#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit
import Foundation
import AVFoundation

/// Displays a video on a 3D plane in the scene.
///
/// Mirrors SceneView Android's `VideoNode` — renders video content on a flat
/// quad using AVFoundation and RealityKit's `VideoPlayerComponent`.
///
/// ```swift
/// @State private var videoNode: VideoNode?
///
/// SceneView { content in
///     if let videoNode {
///         content.addChild(videoNode.entity)
///     }
/// }
/// .task {
///     videoNode = VideoNode.load("videos/intro.mp4")
///         .position(.init(x: 0, y: 1.5, z: -3))
///         .size(width: 1.6, height: 0.9)
///     videoNode?.play()
/// }
/// ```
/// Prevents memory leaks by removing the notification observer on deallocation.
private final class VideoLoopObserver: @unchecked Sendable {
    let token: NSObjectProtocol

    init(token: NSObjectProtocol) {
        self.token = token
    }

    deinit {
        NotificationCenter.default.removeObserver(token)
    }
}

public struct VideoNode: @unchecked Sendable {
    /// The underlying RealityKit entity.
    public let entity: Entity

    /// The AVPlayer driving video playback.
    public let player: AVPlayer

    /// Retains the loop observer so the notification is removed when the node is released.
    private let loopObserver: VideoLoopObserver?

    /// World-space position.
    public var position: SIMD3<Float> {
        get { entity.position }
        nonmutating set { entity.position = newValue }
    }

    /// Orientation as a quaternion.
    public var rotation: simd_quatf {
        get { entity.orientation }
        nonmutating set { entity.orientation = newValue }
    }

    /// Scale factor.
    public var scale: SIMD3<Float> {
        get { entity.scale }
        nonmutating set { entity.scale = newValue }
    }

    // MARK: - Factory methods

    /// Creates a video node from a bundle resource name.
    ///
    /// - Parameters:
    ///   - name: Bundle resource name (e.g. `"videos/intro.mp4"`).
    ///   - width: Plane width in meters. Default 1.6 (16:9 at 0.9m height).
    ///   - height: Plane height in meters. Default 0.9.
    ///   - loop: Whether the video loops. Default false.
    /// - Returns: A `VideoNode` ready for playback. Call `play()` to start.
    public static func load(
        _ name: String,
        width: Float = 1.6,
        height: Float = 0.9,
        loop: Bool = false
    ) -> VideoNode {
        let components = name.split(separator: ".")
        let baseName = String(components.dropLast().joined(separator: "."))
        let ext = components.last.map(String.init) ?? "mp4"

        let url: URL
        if let bundleURL = Bundle.main.url(forResource: baseName, withExtension: ext) {
            url = bundleURL
        } else {
            // Try as a direct path
            url = URL(fileURLWithPath: name)
        }

        return load(contentsOf: url, width: width, height: height, loop: loop)
    }

    /// Creates a video node from a URL.
    ///
    /// - Parameters:
    ///   - url: File or remote URL to the video.
    ///   - width: Plane width in meters.
    ///   - height: Plane height in meters.
    ///   - loop: Whether the video loops.
    /// - Returns: A `VideoNode` ready for playback.
    public static func load(
        contentsOf url: URL,
        width: Float = 1.6,
        height: Float = 0.9,
        loop: Bool = false
    ) -> VideoNode {
        let player = AVPlayer(url: url)
        return create(player: player, width: width, height: height, loop: loop)
    }

    /// Creates a video node from an existing AVPlayer.
    ///
    /// - Parameters:
    ///   - player: A configured AVPlayer instance.
    ///   - width: Plane width in meters.
    ///   - height: Plane height in meters.
    ///   - loop: Whether the video loops.
    /// - Returns: A `VideoNode` ready for playback.
    public static func create(
        player: AVPlayer,
        width: Float = 1.6,
        height: Float = 0.9,
        loop: Bool = false
    ) -> VideoNode {
        let videoEntity = Entity()
        videoEntity.name = "VideoNode"

        // Add VideoPlayerComponent for RealityKit rendering.
        let videoComponent = VideoPlayerComponent(avPlayer: player)
        videoEntity.components.set(videoComponent)

        // Set scale to approximate the desired display size
        videoEntity.scale = SIMD3<Float>(width, height, 1.0)

        // Set up looping if requested, storing the observer token to avoid leaks
        let loopObserver: VideoLoopObserver?
        if loop {
            let token = NotificationCenter.default.addObserver(
                forName: .AVPlayerItemDidPlayToEndTime,
                object: player.currentItem,
                queue: .main
            ) { [weak player] _ in
                player?.seek(to: .zero)
                player?.play()
            }
            loopObserver = VideoLoopObserver(token: token)
        } else {
            loopObserver = nil
        }

        return VideoNode(entity: videoEntity, player: player, loopObserver: loopObserver)
    }

    // MARK: - Playback controls

    /// Starts or resumes video playback.
    public func play() {
        player.play()
    }

    /// Pauses video playback.
    public func pause() {
        player.pause()
    }

    /// Stops playback and resets to the beginning.
    public func stop() {
        player.pause()
        player.seek(to: .zero)
    }

    /// Seeks to a specific time in seconds.
    public func seek(to seconds: Double) {
        let time = CMTime(seconds: seconds, preferredTimescale: 600)
        player.seek(to: time)
    }

    /// Whether the video is currently playing.
    public var isPlaying: Bool {
        player.timeControlStatus == .playing
    }

    /// Sets the playback volume (0.0 to 1.0).
    public func volume(_ volume: Float) {
        player.volume = volume
    }

    /// Mutes or unmutes the video.
    public func muted(_ muted: Bool) {
        player.isMuted = muted
    }

    // MARK: - Transform helpers

    /// Returns self positioned at the given coordinates.
    @discardableResult
    public func position(_ position: SIMD3<Float>) -> VideoNode {
        entity.position = position
        return self
    }

    /// Returns self rotated by the given quaternion.
    @discardableResult
    public func rotation(_ rotation: simd_quatf) -> VideoNode {
        entity.orientation = rotation
        return self
    }

    /// Returns self scaled uniformly.
    @discardableResult
    public func scale(_ uniform: Float) -> VideoNode {
        entity.scale = .init(repeating: uniform)
        return self
    }

    /// Sets the display size of the video plane.
    @discardableResult
    public func size(width: Float, height: Float) -> VideoNode {
        entity.scale = SIMD3<Float>(width, height, 1.0)
        return self
    }
}

#endif // os(iOS) || os(macOS) || os(visionOS)
