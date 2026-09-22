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

/// Logs `AVPlayerItem` playback failures instead of leaving a silently black
/// video plane: a wrong codec, an unreadable file or a dead remote URL all end
/// up as `AVPlayerItem.status == .failed`, which nothing used to observe.
private final class VideoStatusObserver: @unchecked Sendable {
    private var observation: NSKeyValueObservation?

    init?(player: AVPlayer) {
        guard let item = player.currentItem else { return nil }
        observation = item.observe(\.status, options: [.new, .initial]) { item, _ in
            guard item.status == .failed else { return }
            let reason = item.error?.localizedDescription ?? "unknown error"
            print("[SceneViewSwift] VideoNode playback failed: \(reason)")
        }
    }

    deinit {
        observation?.invalidate()
    }
}

/// Errors thrown by ``VideoNode/load(resource:width:height:loop:)``.
public enum VideoNodeError: Error, LocalizedError, Sendable {
    /// No video file matched the requested resource name, in the main bundle or
    /// on disk.
    case resourceNotFound(name: String, triedExtensions: [String])

    public var errorDescription: String? {
        switch self {
        case let .resourceNotFound(name, extensions):
            return "No video resource named \"\(name)\" was found "
                + "(tried extensions: \(extensions.joined(separator: ", ")))."
        }
    }
}

public struct VideoNode: @unchecked Sendable {
    /// The underlying RealityKit entity.
    public let entity: Entity

    /// The AVPlayer driving video playback.
    public let player: AVPlayer

    /// Retains the loop observer so the notification is removed when the node is released.
    private let loopObserver: VideoLoopObserver?

    /// Retains the playback-status observer so a failed item is reported.
    private let statusObserver: VideoStatusObserver?

    /// Extensions tried, in order, when a resource name carries none.
    public static let videoFileExtensions = ["mp4", "mov", "m4v"]

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
        do {
            return try load(resource: name, width: width, height: height, loop: loop)
        } catch {
            // Never point an AVPlayer at a URL that does not exist: that used to
            // produce a node whose controls reported success while nothing ever
            // played. Say why, and hand back a node with an empty player.
            print("[SceneViewSwift] VideoNode.load(\"\(name)\") failed: "
                + "\(error.localizedDescription)")
            return create(player: AVPlayer(), width: width, height: height, loop: loop)
        }
    }

    /// Creates a video node from a bundle resource name, throwing when no such
    /// video exists.
    ///
    /// A name with no extension (`"sample"`) is tried against each of
    /// ``videoFileExtensions`` in turn — `"sample"` used to be parsed as an
    /// empty base name with the extension `sample`, which matched nothing.
    ///
    /// - Parameters:
    ///   - name: Bundle resource name, with or without extension
    ///     (`"videos/intro.mp4"`, `"sample"`), or a path on disk.
    ///   - width: Plane width in meters. Default 1.6 (16:9 at 0.9m height).
    ///   - height: Plane height in meters. Default 0.9.
    ///   - loop: Whether the video loops. Default false.
    /// - Returns: A `VideoNode` ready for playback. Call `play()` to start.
    /// - Throws: ``VideoNodeError/resourceNotFound(name:triedExtensions:)``.
    public static func load(
        resource name: String,
        width: Float = 1.6,
        height: Float = 0.9,
        loop: Bool = false
    ) throws -> VideoNode {
        let url = try resolveVideoURL(named: name)
        return load(contentsOf: url, width: width, height: height, loop: loop)
    }

    /// Resolves a resource name to an existing video file: bundle first, then a
    /// path on disk, trying ``videoFileExtensions`` when the name carries none.
    static func resolveVideoURL(named name: String) throws -> URL {
        let providedExtension = (name as NSString).pathExtension
        let baseName = providedExtension.isEmpty
            ? name
            : String(name.dropLast(providedExtension.count + 1))
        let extensions = providedExtension.isEmpty
            ? videoFileExtensions
            : [providedExtension]

        for ext in extensions {
            if let bundleURL = Bundle.main.url(forResource: baseName, withExtension: ext) {
                return bundleURL
            }
        }

        let fileManager = FileManager.default
        let directURL = URL(fileURLWithPath: name)
        if !providedExtension.isEmpty, fileManager.fileExists(atPath: directURL.path) {
            return directURL
        }
        if providedExtension.isEmpty {
            for ext in extensions {
                let candidate = directURL.appendingPathExtension(ext)
                if fileManager.fileExists(atPath: candidate.path) { return candidate }
            }
        }

        throw VideoNodeError.resourceNotFound(name: name, triedExtensions: extensions)
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

        return VideoNode(
            entity: videoEntity,
            player: player,
            loopObserver: loopObserver,
            statusObserver: VideoStatusObserver(player: player)
        )
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
