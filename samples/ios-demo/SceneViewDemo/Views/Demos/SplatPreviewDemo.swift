import SwiftUI
import RealityKit
import SceneViewSwift

/// **Real-World Scan** — the iOS face of Android's `SplatPreviewDemo`: a tree stump and its
/// raccoons, captured by walking around it with a phone, opened from the same bundled
/// `raccoon_family.spz` (Niantic Labs SPZ samples, MIT).
///
/// What is the same: the file, the decode (``SPZReader``), the orbit home (``SplatFraming``,
/// Android's `scanFraming`), the copy and the "Points drawn" control.
///
/// What is not, and the screen says so: Android renders each point as a soft, camera-sorted
/// gaussian (`SplatNode`, #2646). SceneViewSwift has no splat renderer yet and RealityKit no
/// point primitive, so iOS draws every captured point as a small solid dot
/// (``SplatPointCloud``). The capture is real; the look is a point cloud, not a splat.
struct SplatPreviewDemo: View {
    @State private var model = SplatPreviewModel()
    @State private var drawn: Double = 0
    @State private var cameraPose: SceneCameraPose?

    var body: some View {
        GeometryReader { proxy in
            stage
                .onAppear { cameraPose = Self.homePose(for: proxy.size) }
                .onChange(of: proxy.size) { _, size in cameraPose = Self.homePose(for: size) }
        }
        .ignoresSafeArea()
        .demoChrome(
            onReset: { reset() },
            accessory: { DemoHint(peekText) }
        ) {
            controlsSheet
        }
        .task {
            await model.load()
            drawn = Double(model.total)
        }
        .onChange(of: drawn) { _, value in model.show(Int(value.rounded())) }
    }

    private var peekText: String {
        switch model.phase {
        case .loading:
            return Self.loadingText
        case .failed(let message):
            return message
        case .ready:
            return "Phone capture · \(Self.formatPoints(model.total)) points drawn as dots · "
                + Self.formatMegabytes(model.fileBytes)
        }
    }

    private func reset() {
        drawn = Double(model.total)
        cameraPose = nil
        DispatchQueue.main.async { cameraPose = Self.homePose(for: lastSize) }
    }

    @State private var lastSize: CGSize = .zero

    // MARK: Stage

    private var stage: some View {
        ZStack {
            LinearGradient(colors: [SceneViewTokens.Stage.skyHorizon, SceneViewTokens.Stage.skyGround],
                           startPoint: .top, endPoint: .bottom)
            SceneView { root in
                model.install(in: root)
            }
            .environment(Self.environment)
            .cameraControls(.orbit)
            .autoCenterContent(false)
            .cameraPose(cameraPose)
        }
        .background {
            GeometryReader { proxy in
                Color.clear
                    .onAppear { lastSize = proxy.size }
                    .onChange(of: proxy.size) { _, size in lastSize = size }
            }
        }
    }

    private static var environment: SceneEnvironment {
        var studio = SceneEnvironment.studio
        studio.showSkybox = false
        return studio
    }

    /// Orbit home: the scan's centroid (the model recentres the cloud on it), seen from ~3.6°
    /// above, at the distance that fits the median half of the points across the screen's width
    /// — Android's `scanFraming`, on SceneViewSwift's 60° lens.
    static func homePose(for size: CGSize) -> SceneCameraPose {
        let aspect = Float(max(size.width, 1) / max(size.height, 1))
        return SceneCameraPose(azimuth: 0, elevation: SplatFraming.homeTilt,
                               distance: SplatFraming.distance(radius: SplatPreviewModel.displayRadius,
                                                               aspect: aspect),
                               target: .zero)
    }

    // MARK: Settings sheet

    private var controlsSheet: some View {
        VStack(alignment: .leading, spacing: SceneViewTokens.Space.md) {
            Text("Someone walked around this tree stump filming with a phone. There is no model "
                 + "and no texture here — the scan is stored as coloured points, and that is what "
                 + "you are looking at. Here, each point is drawn as a small solid dot.")
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)

            VStack(alignment: .leading, spacing: SceneViewTokens.Space.sm) {
                LabeledSlider(label: "Points drawn",
                              value: $drawn,
                              range: 0...Double(max(model.total, 1)),
                              valueText: "\(Self.formatPoints(Int(drawn.rounded()))) of "
                                  + Self.formatPoints(model.total))
                    .disabled(model.total == 0)
                Text("Drawing fewer points is how a big capture keeps a steady frame rate on an "
                     + "older phone. The file still holds every one of them.")
                    .font(.caption)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Text(fileText)
                .font(.caption)
                .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var fileText: String {
        guard model.total > 0 else { return "Opening raccoon_family.spz…" }
        return "raccoon_family.spz · \(Self.formatMegabytes(model.fileBytes)) · opened in "
            + "\(model.decodeMillis) ms. Capture by Niantic Labs (SPZ samples, MIT), cropped to "
            + "the stump for this demo."
    }

    static let loadingText = "Opening the scan…"

    /// `233808` → `233 808`: grouped for readability, with no locale surprise (Android's
    /// `formatPoints`).
    static func formatPoints(_ count: Int) -> String {
        let digits = Array(String(count))
        var out = ""
        for (i, d) in digits.enumerated() {
            if i > 0 && (digits.count - i) % 3 == 0 { out.append(" ") }
            out.append(d)
        }
        return out
    }

    /// Bytes → `3.1 MB` (Android's `formatMegabytes`).
    static func formatMegabytes(_ bytes: Int) -> String {
        String(format: "%.1f MB", locale: Locale(identifier: "en_US_POSIX"), Double(bytes) / 1024 / 1024)
    }
}

// MARK: - Model

/// Loads the bundled scan off the main thread, turns it into per-chunk meshes and atlases, and
/// shows the first `n` points on request.
@MainActor
@Observable
final class SplatPreviewModel {
    enum Phase: Equatable { case loading, ready, failed(String) }

    /// The cloud is recentred on its centroid and scaled so its median half fits a sphere of
    /// this radius, in metres. The raw capture's is ~0.29 m: at that size the orbit camera's
    /// 1 m minimum distance would already be the home distance, leaving no room to lean in.
    static let displayRadius: Float = 0.6
    static let assetName = "raccoon_family"

    private(set) var phase: Phase = .loading
    private(set) var total = 0
    private(set) var fileBytes = 0
    private(set) var decodeMillis = 0

    /// Created on first use: SwiftUI re-runs `@State`'s initialiser on every view re-init and
    /// throws the extra models away, so the init builds nothing.
    @ObservationIgnored private var containerEntity: Entity?
    @ObservationIgnored private var chunkEntities: [ModelEntity] = []
    @ObservationIgnored private var materials: [UnlitMaterial] = []
    @ObservationIgnored private var partial: ModelEntity?
    /// The chunk `partial` is a cut of.
    @ObservationIgnored private var partialChunk = -1
    @ObservationIgnored private var scan: SplatScan?
    @ObservationIgnored private var framing: SplatFraming?
    @ObservationIgnored private var scale: Float = 1
    @ObservationIgnored private var shown = -1
    @ObservationIgnored private var partialTask: Task<Void, Never>?

    private var container: Entity {
        if let containerEntity { return containerEntity }
        let entity = Entity()
        containerEntity = entity
        return entity
    }

    func install(in root: Entity) {
        container.removeFromParent()
        root.addChild(container)
    }

    func load() async {
        guard scan == nil else { return }
        guard let url = Bundle.main.url(forResource: Self.assetName, withExtension: "spz") else {
            phase = .failed("raccoon_family.spz is missing from this build.")
            return
        }
        let displayRadius = Self.displayRadius
        do {
            let prepared = try await Task.detached(priority: .userInitiated) {
                try Self.prepare(url: url, displayRadius: displayRadius)
            }.value
            scan = prepared.scan
            framing = prepared.framing
            scale = prepared.scale
            fileBytes = prepared.fileBytes
            decodeMillis = prepared.decodeMillis
            // All chunks or none: `show` indexes chunks by point range, so a skipped chunk
            // would shift every later one onto the wrong points.
            var entities: [ModelEntity] = []
            var chunkMaterials: [UnlitMaterial] = []
            for (mesh, pixels) in zip(prepared.meshes, prepared.atlases) {
                guard let resource = RerunRealityKit.resource(mesh),
                      let texture = Self.atlasTexture(pixels) else {
                    phase = .failed("The scan could not be drawn.")
                    return
                }
                let material = Self.pointMaterial(texture)
                chunkMaterials.append(material)
                entities.append(ModelEntity(mesh: resource, materials: [material]))
            }
            materials = chunkMaterials
            chunkEntities = entities
            entities.forEach { container.addChild($0) }
            total = prepared.scan.count
            phase = .ready
            show(total)
        } catch {
            phase = .failed(String(describing: error))
        }
    }

    /// Draws the first `count` points: whole chunks toggle, the one chunk cut in the middle is
    /// rebuilt at that length (debounced, off the main thread).
    func show(_ count: Int) {
        guard let scan, !chunkEntities.isEmpty else { return }
        let count = min(max(count, 0), scan.count)
        guard count != shown else { return }
        shown = count
        let size = SplatPointCloud.chunkSize
        // A chunk is on once its last point is drawn; the last chunk is the short one, so at
        // `count == scan.count` every chunk is on.
        for (i, entity) in chunkEntities.enumerated() {
            entity.isEnabled = min((i + 1) * size, scan.count) <= count
        }
        partialTask?.cancel()
        let cut = count / size
        let start = cut * size
        guard cut < chunkEntities.count, start < count, count < min(start + size, scan.count) else {
            removePartial()
            return
        }
        // A cut of another chunk would now overlap a whole chunk or run past the count:
        // drop it now rather than after the debounce.
        if partialChunk != cut { removePartial() }
        let range = start ..< count
        let centroid = framing?.centroid ?? .zero
        let scale = scale
        let material = materials[cut]
        partialTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(60))
            guard !Task.isCancelled else { return }
            let mesh = await Task.detached(priority: .userInitiated) {
                SplatPointCloud.mesh(scan, range: range, centroid: centroid, scale: scale)
            }.value
            guard !Task.isCancelled, let self, let resource = RerunRealityKit.resource(mesh) else { return }
            self.removePartial()
            let entity = ModelEntity(mesh: resource, materials: [material])
            self.container.addChild(entity)
            self.partial = entity
            self.partialChunk = cut
        }
    }

    private func removePartial() {
        partial?.removeFromParent()
        partial = nil
        partialChunk = -1
    }

    private struct Prepared: Sendable {
        let scan: SplatScan
        let framing: SplatFraming
        let scale: Float
        let fileBytes: Int
        let decodeMillis: Int
        let meshes: [RerunMesh]
        let atlases: [[UInt8]]
    }

    nonisolated private static func prepare(url: URL, displayRadius: Float) throws -> Prepared {
        let data = try Data(contentsOf: url)
        let started = DispatchTime.now().uptimeNanoseconds
        let scan = try SPZReader.read(data)
        let framing = SplatFraming(scan)
        let millis = Int((DispatchTime.now().uptimeNanoseconds - started) / 1_000_000)
        let scale = displayRadius / framing.subjectRadius
        var meshes: [RerunMesh] = []
        var atlases: [[UInt8]] = []
        let size = SplatPointCloud.chunkSize
        for chunk in 0..<SplatPointCloud.chunkCount(scan) {
            let range = chunk * size ..< min((chunk + 1) * size, scan.count)
            meshes.append(SplatPointCloud.mesh(scan, range: range, centroid: framing.centroid, scale: scale))
            atlases.append(SplatPointCloud.atlasPixels(scan, range: range))
        }
        return Prepared(scan: scan, framing: framing, scale: scale, fileBytes: data.count,
                        decodeMillis: millis, meshes: meshes, atlases: atlases)
    }

    // MARK: Resources

    /// Unlit and untonemapped: a point shows the colour the phone recorded, not a lit surface.
    private static func pointMaterial(_ texture: TextureResource) -> UnlitMaterial {
        var material = UnlitMaterial(applyPostProcessToneMap: false)
        material.color = .init(tint: .white, texture: .init(texture, sampler: RerunRealityKit.nearest))
        material.faceCulling = .none
        return material
    }

    /// One texel per point, read nearest so each point keeps its own colour.
    private static func atlasTexture(_ pixels: [UInt8]) -> TextureResource? {
        let size = RerunPointAtlas.size
        guard let provider = CGDataProvider(data: Data(pixels) as CFData),
              let image = CGImage(width: size, height: size, bitsPerComponent: 8, bitsPerPixel: 32,
                                  bytesPerRow: size * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                  bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.last.rawValue),
                                  provider: provider, decode: nil, shouldInterpolate: false,
                                  intent: .defaultIntent)
        else { return nil }
        return try? TextureResource(image: image, withName: nil,
                                    options: .init(semantic: .color, mipmapsMode: .none))
    }
}
