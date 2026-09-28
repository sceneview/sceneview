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
    /// The dense surfel map of a v2 LiDAR scan, meshed here rather than on the main actor.
    var dense: RerunDenseReplay? = nil

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
        let dense = pack.dense.flatMap { RerunDenseReplay($0, voxelM: pack.denseVoxelM) }
        return RerunReplayMedia(thumbnails: thumbnails, planeImages: planes, dense: dense)
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

/// A dense cloud ready to draw: surfels in insertion order, cut into chunks so the replay can
/// reveal the map as it grew (`RerunTrace.denseCountAt`) by switching whole chunks on — one
/// RealityKit mesh per chunk, one texel of `atlas` per surfel. The reveal runs ahead of the
/// recorded count by less than one chunk.
struct RerunDenseReplay: @unchecked Sendable {
    /// Surfels per chunk: ~60 chunks for the 500 000-surfel cap, each a 32 768-vertex mesh.
    static let chunkSurfels = 8_192
    /// Holds `RerunDenseCloud.maxPoints` texels.
    static let atlasSize = 1_024

    let count: Int
    /// The first surfel of each chunk, ascending.
    let starts: [Int]
    let chunks: [RerunMesh]
    let atlas: CGImage?

    init?(_ cloud: RerunDenseCloud, voxelM: Float) {
        let n = min(cloud.count, Self.atlasSize * Self.atlasSize)
        guard n > 0 else { return nil }
        let firsts = Array(stride(from: 0, to: n, by: Self.chunkSurfels))
        count = n
        starts = firsts
        chunks = firsts.map { start in
            RerunDenseSurfels.mesh(cloud, range: start..<min(start + Self.chunkSurfels, n),
                                   voxelM: voxelM, atlasSize: Self.atlasSize)
        }
        let pixels = RerunPointAtlas.pixels(cloud.colors[...], count: n, size: Self.atlasSize)
        atlas = RerunPointAtlas.image(pixels, size: Self.atlasSize)
    }

    /// How many chunks to show when the map holds `denseCount` surfels (`-1`: the whole map).
    func chunksShown(denseCount: Int) -> Int {
        guard denseCount >= 0 else { return starts.count }
        return starts.prefix { $0 < denseCount }.count
    }
}

/// One replay on screen: the pack, its clock, what the HUD shows and which groups are hidden.
/// Shared by the full stage, the picture-in-picture and the chrome; only one stage drives the
/// clock at a time (the camera view shows the inset instead of the full stage).
@MainActor
@Observable
final class RerunReplaySession {
    let pack: RerunPack
    let media: RerunReplayMedia
    /// The whole take at its last instant — what the camera frames and the grid covers.
    let whole: RerunFrame

    private(set) var playback: RerunPlayback
    /// The playhead, seconds. Read by the filmstrip and the camera view.
    private(set) var time: Float = 0
    private(set) var playing = false
    /// The HUD's figures, refreshed four times a second.
    var stats = RerunStats()
    var fps = 0
    private(set) var hidden: Set<RerunGroup> = []

    var duration: Float { pack.trace.duration }

    init(pack: RerunPack, media: RerunReplayMedia) {
        self.pack = pack
        self.media = media
        self.whole = pack.trace.frameAt(pack.trace.duration)
        self.playback = RerunPlayback(duration: pack.trace.duration)
        self.stats = RerunStats(frame: pack.trace.frameAt(0))
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
