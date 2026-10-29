#if os(iOS)
import CoreGraphics
import Foundation
import UIKit
import UniformTypeIdentifiers

/// App content drawn over every photo and video frame a ``SceneRecorder`` captures — a
/// watermark, a caption, a logo. Never drawn on screen.
///
/// The closure gets a `CGContext` in UIKit coordinates (origin top-left, y down) the size
/// of the captured image in pixels, already pushed as the current UIKit context, so
/// `NSAttributedString.draw(at:)`, `UIImage.draw(in:)` and `UIBezierPath.fill()` work as
/// they do in `draw(_:)`.
///
/// For video it runs on the recorder's encoding queue, once per frame, so it must not touch
/// views or other main-actor state and should stay cheap.
///
/// ```swift
/// recorder.overlay = CaptureOverlay { _, size in
///     let text = NSAttributedString(string: "Made with SceneView", attributes: [
///         .font: UIFont.boldSystemFont(ofSize: min(size.width, size.height) * 0.04),
///         .foregroundColor: UIColor.white,
///     ])
///     text.draw(at: CGPoint(x: 24, y: size.height - 24 - text.size().height))
/// }
/// ```
public struct CaptureOverlay: Sendable {
    private let body: @Sendable (CGContext, CGSize) -> Void

    /// - Parameter draw: Draws the overlay. `size` is the frame size in pixels.
    public init(_ draw: @escaping @Sendable (_ context: CGContext, _ size: CGSize) -> Void) {
        body = draw
    }

    /// Draws the overlay into `context`, whose user space must already be top-left / y-down.
    func draw(in context: CGContext, size: CGSize) {
        context.saveGState()
        UIGraphicsPushContext(context)
        body(context, size)
        UIGraphicsPopContext()
        context.restoreGState()
    }

    /// Draws the overlay into a bitmap context whose origin is bottom-left (a pixel buffer).
    func drawFlipped(in context: CGContext, size: CGSize) {
        context.saveGState()
        context.translateBy(x: 0, y: size.height)
        context.scaleBy(x: 1, y: -1)
        draw(in: context, size: size)
        context.restoreGState()
    }
}

/// The file format of a photo saved by ``SceneRecorder/savePhoto(to:format:quality:)``.
public enum PhotoFormat: String, Sendable, CaseIterable {
    /// Lossy, small; the default.
    case jpeg
    /// Lossless, larger.
    case png

    /// The MIME type, for a share sheet or an upload.
    public var mimeType: String {
        switch self {
        case .jpeg: "image/jpeg"
        case .png: "image/png"
        }
    }

    /// The file extension, without the dot.
    public var fileExtension: String {
        switch self {
        case .jpeg: "jpg"
        case .png: "png"
        }
    }

    /// The uniform type identifier.
    public var contentType: UTType {
        switch self {
        case .jpeg: .jpeg
        case .png: .png
        }
    }
}

/// A finished video, as returned by ``SceneRecorder/stopRecording()``.
public struct SceneRecording: Sendable, Equatable {
    /// The MP4 file.
    public let url: URL
    /// Encoded width in pixels.
    public let width: Int
    /// Encoded height in pixels.
    public let height: Int
    /// Length of the video, in seconds.
    public let duration: TimeInterval
    /// Whether the file has a microphone track.
    public let hasAudio: Bool
    /// `true` when the recording stopped by itself at ``VideoSettings/maxDuration``.
    public let reachedMaxDuration: Bool
    /// Whether ``SceneRecorder/overlay`` was drawn on the frames.
    public let hasOverlay: Bool

    public init(
        url: URL,
        width: Int,
        height: Int,
        duration: TimeInterval,
        hasAudio: Bool,
        reachedMaxDuration: Bool,
        hasOverlay: Bool
    ) {
        self.url = url
        self.width = width
        self.height = height
        self.duration = duration
        self.hasAudio = hasAudio
        self.reachedMaxDuration = reachedMaxDuration
        self.hasOverlay = hasOverlay
    }
}

/// Why a ``SceneRecorder`` call failed, as found in ``SceneRecorder/lastError``.
public enum SceneRecorderError: LocalizedError, Equatable {
    /// No `SceneView` or `ARSceneView` uses this recorder (see `.sceneRecorder(_:)`), or it
    /// has not been laid out yet.
    case notAttached
    /// The ``VideoSettings`` cannot be recorded; the message says which value.
    case invalidSettings(String)
    /// No video size fits the scene (a view with an empty frame).
    case noVideoSize(width: Int, height: Int)
    /// The scene produced no frame.
    case captureFailed
    /// The image could not be encoded in that format.
    case encodingFailed(PhotoFormat)
    /// The file could not be written; the message comes from the system.
    case writeFailed(String)
    /// The recording ended without a single frame.
    case noFrames

    public var errorDescription: String? {
        switch self {
        case .notAttached:
            "No scene renders through this recorder: attach it with "
                + ".sceneRecorder(_:) on a SceneView or an ARSceneView"
        case let .invalidSettings(message):
            message
        case let .noVideoSize(width, height):
            "No video size for a \(width) x \(height) scene"
        case .captureFailed:
            "The scene returned no image"
        case let .encodingFailed(format):
            "The image could not be encoded as \(format.rawValue.uppercased())"
        case let .writeFailed(message):
            message
        case .noFrames:
            "The encoder produced no video — was the scene rendering while recording?"
        }
    }
}
#endif
