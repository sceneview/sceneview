#if os(iOS)
import Foundation
import ImageIO
import Observation
import UIKit

/// The decoded pictures a replay needs: every camera frame as a small thumbnail (the filmstrip,
/// the frustums, the camera card) and each plane's photo. Decoded once, off the main thread.
struct RerunReplayMedia: @unchecked Sendable {
    var thumbnails: [String: CGImage]
    var planeImages: [Int: CGImage]

    /// Thumbnails are ~a quarter of a 480×640 frame: 184 of them fit in ~14 MB, like Android.
    static let thumbnailMaxPixels = 160

    static func decode(_ pack: RerunPack) -> RerunReplayMedia {
        var thumbnails: [String: CGImage] = [:]
        for i in 0..<pack.trace.imageCount {
            let path = pack.trace.imagePaths[i]
            if let data = pack.bytes(for: path), let image = downsample(data, maxPixels: thumbnailMaxPixels) {
                thumbnails[path] = image
            }
        }
        var planes: [Int: CGImage] = [:]
        for texture in pack.manifest.textures {
            if let data = pack.bytes(for: texture.path), let image = decodeFull(data) { planes[texture.planeId] = image }
        }
        return RerunReplayMedia(thumbnails: thumbnails, planeImages: planes)
    }

    static func decodeFull(_ data: Data) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        return CGImageSourceCreateImageAtIndex(source, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary)
    }

    static func downsample(_ data: Data, maxPixels: Int) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixels,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }
}

/// One replay on screen: the pack, its clock, the figures the settings sheet reads out and which
/// layers are hidden. Shared by the stage, the camera view and the chrome; whichever of the stage
/// and the camera view is up drives the clock.
@MainActor
@Observable
final class RerunReplaySession {
    let pack: RerunPack
    let media: RerunReplayMedia
    /// The whole take at its last instant — what the camera frames and the grid covers.
    let whole: RerunFrame
    /// The floor's height over the whole take: what the room's planes are measured against.
    let floorY: Float
    /// The room the take has outlined at the playhead, or `nil` while its walls and floor do
    /// not give one. Refreshed with ``stats``.
    private(set) var room: RerunRoomMeasure?

    private(set) var playback: RerunPlayback
    /// The playhead, seconds. Read by the filmstrip and the camera view.
    private(set) var time: Float = 0
    private(set) var playing = false
    /// The layers' figures at the playhead, refreshed four times a second.
    private(set) var stats = RerunStats()
    private(set) var hidden: Set<RerunGroup> = []

    var duration: Float { pack.trace.duration }

    init(pack: RerunPack, media: RerunReplayMedia) {
        self.pack = pack
        self.media = media
        let whole = pack.trace.frameAt(pack.trace.duration)
        self.whole = whole
        self.floorY = RerunGeometry.floorHeight(whole)
        self.playback = RerunPlayback(duration: pack.trace.duration)
        count(pack.trace.frameAt(0))
    }

    /// Counts `frame` into the figures the settings sheet reads: the layers, and the room its
    /// planes outline so far. Whoever drives the clock calls it a few times a second.
    func count(_ frame: RerunFrame) {
        let counted = RerunStats(frame: frame)
        if counted != stats { stats = counted }
        let measured = RerunRoomMeasure.of(frame.planes, floorY: floorY)
        if measured != room { room = measured }
    }

    static func load(_ pack: RerunPack) async -> RerunReplaySession {
        let media = await Task.detached(priority: .userInitiated) { RerunReplayMedia.decode(pack) }.value
        return RerunReplaySession(pack: pack, media: media)
    }

    func isVisible(_ group: RerunGroup) -> Bool { !hidden.contains(group) }

    func toggle(_ group: RerunGroup) {
        if hidden.contains(group) { hidden.remove(group) } else { hidden.insert(group) }
    }

    func tick(_ delta: Float) {
        playback.tick(delta)
        sync()
    }

    func togglePlay() {
        playback.togglePlay()
        sync()
    }

    func playFromStart() {
        playback.playFromStart()
        sync()
    }

    func scrub(to seconds: Float) {
        playback.scrub(to: seconds)
        sync()
    }

    /// Resumes after a scrub that interrupted playback.
    func resume() {
        if !playback.playing { playback.togglePlay() }
        sync()
    }

    private func sync() {
        if time != playback.cursor { time = playback.cursor }
        if playing != playback.playing { playing = playback.playing }
    }

    /// The camera image in force at the playhead.
    var currentImagePath: String? {
        let index = pack.trace.imageIndexAt(time)
        return index >= 0 ? pack.trace.imagePaths[index] : nil
    }

    func thumbnail(_ path: String?) -> CGImage? { path.flatMap { media.thumbnails[$0] } }

    /// A camera frame at full size, decoded on demand for the camera view.
    func fullFrame(_ path: String) async -> CGImage? {
        guard let data = pack.bytes(for: path) else { return nil }
        return await Task.detached(priority: .userInitiated) { RerunReplayMedia.decodeFull(data) }.value
    }
}
#endif
