#if os(iOS)
import Foundation

/// How ``SceneRecorder/startRecording(to:settings:)`` encodes a video.
///
/// The defaults record an H.264 MP4 at up to 1920 × 1080, 30 frames per second, 12 Mbit/s,
/// without sound and without a time limit.
///
/// The video keeps the scene's aspect ratio inside a `maxLongEdge` × `maxShortEdge` box
/// turned like the view (a portrait view records a portrait video), with even edges as
/// H.264 requires. When that size is beyond H.264 level 5.2 (4096 pixels per edge, 36 864
/// macroblocks, as 4096 × 2304), the recorder steps down through 1080p, 720p, 540p and 360p,
/// never above the requested box. The size is fixed when the recording starts: a view turned
/// mid-recording is letterboxed in it, not stretched.
public struct VideoSettings: Sendable, Equatable {
    /// Longest edge of the video, in pixels. Default 1920.
    public var maxLongEdge: Int
    /// Shortest edge of the video, in pixels. Default 1080.
    public var maxShortEdge: Int
    /// Frames per second. Default 30; a scene rendering faster is sampled down.
    public var frameRate: Int
    /// Average video bit rate, in bits per second. Default 12 000 000.
    public var bitRate: Int
    /// Records the microphone as AAC mono when the app already holds microphone access
    /// (`AVCaptureDevice.authorizationStatus(for: .audio) == .authorized`). The recorder
    /// never asks for it: without access, or when the microphone cannot be opened, the video
    /// is recorded silent and ``SceneRecording/hasAudio`` is `false`. Needs
    /// `NSMicrophoneUsageDescription` in the app's Info.plist. The app's audio session is
    /// left as it is when it can already record; otherwise it is switched to `.playAndRecord`,
    /// mixing with other audio, for the length of the recording, then put back.
    /// Default `false`.
    public var recordAudio: Bool
    /// Stops the recording by itself after this many seconds, with
    /// ``SceneRecording/reachedMaxDuration`` set. Frames past the limit are refused, so the
    /// video is never longer. `nil` (the default) records until
    /// ``SceneRecorder/stopRecording()``.
    public var maxDuration: TimeInterval?

    public init(
        maxLongEdge: Int = 1920,
        maxShortEdge: Int = 1080,
        frameRate: Int = 30,
        bitRate: Int = 12_000_000,
        recordAudio: Bool = false,
        maxDuration: TimeInterval? = nil
    ) {
        self.maxLongEdge = maxLongEdge
        self.maxShortEdge = maxShortEdge
        self.frameRate = frameRate
        self.bitRate = bitRate
        self.recordAudio = recordAudio
        self.maxDuration = maxDuration
    }

    /// Throws ``SceneRecorderError/invalidSettings(_:)`` for a value that cannot be recorded.
    /// ``SceneRecorder/startRecording(to:settings:)`` runs it and reports the error through
    /// ``SceneRecorder/lastError``.
    public func validate() throws {
        guard maxShortEdge > 0, maxLongEdge >= maxShortEdge else {
            throw SceneRecorderError.invalidSettings(
                "maxShortEdge must be positive and at most maxLongEdge, got \(maxLongEdge) x \(maxShortEdge)"
            )
        }
        guard frameRate > 0 else {
            throw SceneRecorderError.invalidSettings("frameRate must be positive, got \(frameRate)")
        }
        guard bitRate > 0 else {
            throw SceneRecorderError.invalidSettings("bitRate must be positive, got \(bitRate)")
        }
        if let maxDuration, !(maxDuration > 0) {
            throw SceneRecorderError.invalidSettings("maxDuration must be positive, got \(maxDuration)")
        }
    }
}

/// A video size in pixels.
struct VideoSize: Equatable, Hashable, CustomStringConvertible {
    let width: Int
    let height: Int

    var description: String { "\(width)x\(height)" }
}

/// Step-down boxes when the encoder refuses the requested one, each clamped to it.
private let fallbackBoxes: [(Int, Int)] = [(1920, 1080), (1280, 720), (960, 540), (640, 360)]

/// Whether an H.264 encoder takes this size: level 5.2 caps each edge at 4096 pixels and a
/// frame at 36 864 macroblocks of 16 × 16 (4096 × 2304).
func isH264SizeSupported(_ width: Int, _ height: Int) -> Bool {
    guard width > 0, height > 0, width <= 4096, height <= 4096 else { return false }
    return ((width + 15) / 16) * ((height + 15) / 16) <= 36_864
}

/// H.264 4:2:0 needs even edges; some encoders only take multiples of 16.
private let alignments = [2, 16]

/// The size to encode a `sourceWidth` × `sourceHeight` scene at: its aspect ratio fitted in
/// a `maxLongEdge` × `maxShortEdge` box oriented like the source, edges aligned to 2 (then 16
/// if refused), stepping down through the fallback boxes until `isSupported` accepts one.
///
/// - Returns: `nil` before the view is laid out, or when no size is supported.
func chooseVideoSize(
    sourceWidth: Int,
    sourceHeight: Int,
    maxLongEdge: Int,
    maxShortEdge: Int,
    isSupported: (_ width: Int, _ height: Int) -> Bool = { _, _ in true }
) -> VideoSize? {
    guard sourceWidth > 0, sourceHeight > 0 else { return nil }
    var boxes: [(Int, Int)] = [(maxLongEdge, maxShortEdge)]
    for (long, short) in fallbackBoxes {
        let box = (min(long, maxLongEdge), min(short, maxShortEdge))
        if !boxes.contains(where: { $0 == box }) { boxes.append(box) }
    }
    for (longEdge, shortEdge) in boxes {
        for alignment in alignments {
            guard let size = fit(sourceWidth, sourceHeight, longEdge, shortEdge, alignment) else { continue }
            if isSupported(size.width, size.height) { return size }
        }
    }
    return nil
}

private func fit(_ sourceWidth: Int, _ sourceHeight: Int, _ longEdge: Int, _ shortEdge: Int, _ alignment: Int) -> VideoSize? {
    let portrait = sourceHeight > sourceWidth
    let boxWidth = Int64(portrait ? shortEdge : longEdge)
    let boxHeight = Int64(portrait ? longEdge : shortEdge)
    let srcW = Int64(sourceWidth), srcH = Int64(sourceHeight)
    let (width, height): (Int64, Int64) = srcW * boxHeight >= srcH * boxWidth
        ? (boxWidth, srcH * boxWidth / srcW)
        : (srcW * boxHeight / srcH, boxHeight)
    let alignedWidth = Int(width / Int64(alignment) * Int64(alignment))
    let alignedHeight = Int(height / Int64(alignment) * Int64(alignment))
    return alignedWidth > 0 && alignedHeight > 0 ? VideoSize(width: alignedWidth, height: alignedHeight) : nil
}
#endif
