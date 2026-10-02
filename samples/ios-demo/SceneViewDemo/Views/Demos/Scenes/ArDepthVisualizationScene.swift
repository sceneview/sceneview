// @sceneId     ar-depth-visualization
// @title       Depth Visualization
// @subtitle    False-color depth map blended with camera
// @category    ar
// @section     understand
// @available   true
// @icon        square.3.layers.3d
// @iosOnly     true
// @status      knownIssue
// @order       40
// @tags        ar,depth,visualization,false-color,depth-map,ml,lidar
// @addedIn     4.51.0
import SwiftUI

enum ArDepthVisualizationScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(ARDepthVisualizationDemo())
        #else
        return AnyView(EmptyView())
        #endif
    }
}

#if os(iOS)
import ARKit
import SceneViewSwift
import SceneViewDepthML

/// iOS port of Android's `ar-depth-visualization`: the depth map in false colour,
/// blended over the camera, from one of two sources picked in a mode pill.
///
/// - **LiDAR** — `.depthSource(.native)`, ARKit `sceneDepth` (Android: ARCore Depth API).
/// - **ML** — `.depthSource(.ml(DepthAnythingV2Estimator))`, Depth Anything V2 Small on the
///   Neural Engine, scaled to metres against ARKit's feature points and planes. Picking ML
///   fetches the model once (pinned revision, SHA-256 checked) — the same behaviour as
///   Android, where picking ML starts the download.
///
/// The ML card mirrors Android's `MlDepthCard` text for text, anchor count included. Known
/// gaps: the model is 25.4 MB here (Core ML, 8-bit weights) against 27.7 MB on Android (LiteRT
/// int8), and it takes a 518×392 image against 686×518 on Android.
///
/// `@status knownIssue`: the simulator has no camera, so neither path has run on hardware
/// from this PR; the iPhone SE (3rd gen) measurement is the merge gate.
struct ARDepthVisualizationDemo: View {
    @State private var source: DepthVizSource = .sensor
    /// Camera (0) to depth (1). Android default 0.6.
    @State private var blend: Double = 0.6
    @StateObject private var model = DepthVizModel()

    private let hasLiDAR: Bool = {
        #if targetEnvironment(simulator)
        return false
        #else
        return ARWorldTrackingConfiguration.supportsFrameSemantics(.sceneDepth)
        #endif
    }()

    var body: some View {
        #if targetEnvironment(simulator)
        if let preview = DepthVizPreview.forced {
            // DEBUG render of one chrome state (`-depth-ml-preview <state>`): no camera,
            // no model, no depth — a capture of it is a render, never a proof.
            chrome(
                stage: SceneViewTokens.Stage.background.ignoresSafeArea(),
                source: preview.source,
                lidar: preview.lidar,
                card: preview.card,
                depthFlowing: preview.depthFlowing
            )
        } else {
            ARUnavailableStage(
                icon: "square.3.layers.3d",
                message: "Run on iPhone or iPad to see the depth map in false colour over the camera — LiDAR depth, or an on-device ML estimate on any iPhone."
            )
            .demoChrome(chromeMode: .ar)
        }
        #else
        chrome(
            stage: arStage,
            source: source,
            lidar: hasLiDAR,
            card: source == .ml ? model.cardStatus : nil,
            depthFlowing: model.overlay != nil
        )
        .onChange(of: source) { _, newSource in
            model.reset()
            if newSource == .ml { model.loadModel() }
        }
        #endif
    }

    // MARK: - Chrome (device and DEBUG render)

    private func chrome<Stage: View>(
        stage: Stage,
        source shown: DepthVizSource,
        lidar: Bool,
        card: MLCardStatus?,
        depthFlowing: Bool
    ) -> some View {
        stage
            .demoChrome(
                onReset: { blend = 0.6 },
                chromeMode: .ar,
                accessory: {
                    VStack(spacing: SceneViewTokens.Space.sm) {
                        DepthSourcePill(selection: shown) { source = $0 }
                        if let card {
                            MLDepthCard(status: card)
                        }
                        if shown == .sensor, lidar, depthFlowing, blend > 0.05 {
                            DepthLegendPill()
                        }
                        if shown == .sensor, lidar, !depthFlowing {
                            DemoHint("Warming up depth — move the camera slowly across a textured scene")
                        }
                        if shown == .sensor, !lidar {
                            DemoHint("This iPhone has no LiDAR scanner. Pick ML to estimate depth from the camera.")
                        }
                    }
                }
            ) {
                controls(source: shown, lidar: lidar)
            }
    }

    @ViewBuilder
    private func controls(source shown: DepthVizSource, lidar: Bool) -> some View {
        VStack(alignment: .leading, spacing: SceneViewTokens.Space.xs) {
            Text("How to read")
                .font(.subheadline.weight(.semibold))
            Text("Warm colors (red/yellow) = near (~0.3\u{00A0}m). Cool colors (cyan/blue) = far (~5\u{00A0}m). Transparent pixels = no depth data.")
                .font(.footnote)
                .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                .fixedSize(horizontal: false, vertical: true)
        }
        LabeledSlider(label: "Blend", value: $blend, range: 0...1,
                      valueText: "\(Int((blend * 100).rounded())) %")
            .disabled(shown == .sensor && !lidar)
        HStack {
            Text("Camera")
            Spacer()
            Text("Depth")
        }
        .font(.footnote)
        .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
        .accessibilityHidden(true)
    }

    // MARK: - AR stage (physical device)

    #if !targetEnvironment(simulator)
    private var depthSource: DepthSource? {
        switch source {
        case .sensor: return hasLiDAR ? .native : nil
        case .ml: return model.estimator.map { .ml($0) }
        }
    }

    private var arStage: some View {
        ZStack {
            ARSceneView(
                configuration: ARSessionConfiguration(
                    planeDetection: .both,
                    // ML mode leaves `.sceneDepth` out: the estimator never reads it, and
                    // the LiDAR stream would cost power for nothing.
                    frameSemantics: hasLiDAR && source == .sensor ? [.sceneDepth] : []
                ),
                showPlaneOverlay: false,
                showCoachingOverlay: true
            )
            .depthSource(depthSource)
            .onDepthFrame { model.receive($0) }
            .onDepthSourceState { model.depthState = $0 }

            // Depth maps arrive in the sensor's landscape orientation; the demo runs in
            // portrait, so the map turns a quarter right and fills the screen the way
            // ARView's camera image does (aspect fill, centred).
            if let overlay = model.overlay {
                Image(uiImage: overlay)
                    .resizable()
                    .interpolation(.none)
                    .scaledToFill()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .clipped()
                    .opacity(blend)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
        }
        .ignoresSafeArea()
    }
    #endif
}

// MARK: - Source

/// Where the overlay's depth comes from. Android: `DepthSource { ARCore, Ml }`.
enum DepthVizSource: String, CaseIterable, Hashable {
    case sensor
    case ml

    var label: String {
        switch self {
        case .sensor: return "LiDAR"
        case .ml: return "ML"
        }
    }
}

/// LiDAR / ML: the over-media mode pill of `DESIGN.md` (`mode-pill-*`) — opaque, so it reads
/// on any camera frame in both themes. Android: `DepthSourcePill`.
private struct DepthSourcePill: View {
    let selection: DepthVizSource
    let onSelect: (DepthVizSource) -> Void

    private typealias Pill = SceneViewTokens.ModePill

    var body: some View {
        HStack(spacing: 0) {
            ForEach(DepthVizSource.allCases, id: \.self) { option in
                let selected = option == selection
                Button { onSelect(option) } label: {
                    Text(option.label)
                        .font(SceneViewTokens.TypeScale.chromeLabel)
                        .lineLimit(1)
                        .foregroundStyle(selected ? Pill.onSelected : Pill.onContainer)
                        .padding(.horizontal, SceneViewTokens.Space.md)
                        .frame(minWidth: Pill.segmentMinWidth, minHeight: Pill.segmentHeight)
                        .background {
                            if selected { Capsule().fill(Pill.selectedContainer) }
                        }
                        // The ring round the segment belongs to its hit area: 40 + 2 × 4 =
                        // 48 pt high (`Layout.touchTarget`), not just the 40 pt capsule.
                        .padding(.vertical, SceneViewTokens.Space.xs)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? .isSelected : [])
                .accessibilityIdentifier("depth-source-\(option.rawValue)")
            }
        }
        .padding(.horizontal, SceneViewTokens.Space.xs)
        .background(Capsule().fill(Pill.container))
        .overlay(Capsule().strokeBorder(Pill.outline, lineWidth: Pill.outlineWidth))
        .sensoryFeedback(.selection, trigger: selection)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Depth source")
    }
}

/// "Near (~0.3 m) ──── Far (~5 m)" — only once a LiDAR map is on screen. Android's legend pill.
private struct DepthLegendPill: View {
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Text("Near (~0.3\u{00A0}m) ──── Far (~5\u{00A0}m)")
            .font(SceneViewTokens.TypeScale.chromeCaption)
            .foregroundStyle(SceneViewTokens.ARChrome.onScrim)
            .padding(.horizontal, SceneViewTokens.Space.md)
            .padding(.vertical, SceneViewTokens.ARChrome.captionPillVerticalPadding)
            .background(Capsule().fill(SceneViewTokens.ARChrome.scrim(scheme)))
            .overlay(Capsule().strokeBorder(SceneViewTokens.ARChrome.border(scheme),
                                            lineWidth: SceneViewTokens.ARChrome.borderWidth))
    }
}

// MARK: - ML card

/// What the ML estimate is doing, in Android's `MlDepthCard` words.
enum MLCardStatus: Equatable {
    case downloading(Double)
    case preparing
    case waiting(anchors: Int)
    case running(medianMs: Int, hz: Double, anchors: Int, fitErrorPercent: Int)
    case holding
    case throttled
    case failed(String)

    static let modelSize: String = {
        let bytes = Double(PinnedRemoteModel.depthAnythingV2SmallF16INT8.totalBytes)
        return String(format: "%.1f MB", bytes / 1_000_000)
    }()

    var text: String {
        switch self {
        case .downloading(let fraction):
            return "Downloading the model (\(Self.modelSize), once) — \(Int((fraction * 100).rounded(.down))) %"
        case .preparing:
            return "Preparing the model…"
        case .waiting(let anchors):
            return "Scaling to metres needs surfaces — move slowly over the floor and a table (\(anchors) anchors)"
        case .running(let ms, let hz, let anchors, let error):
            return "\(ms) ms per estimate · \(String(format: "%.1f", hz)) Hz · \(anchors) anchors · \(error) % fit error"
        case .holding:
            return "Holding the last scale — find a surface again"
        case .throttled:
            return "Paused: the phone is too hot"
        case .failed(let reason):
            return "Could not load the depth model: \(reason)"
        }
    }
}

/// `DESIGN.md` "AR Overlay Card": `ar-scrim` ground, `ar-scrim-border` hairline,
/// `radius-lg`, `space-md` padding. Title `type-card`, body `type-body`, credit `type-caption`.
private struct MLDepthCard: View {
    let status: MLCardStatus

    @Environment(\.colorScheme) private var scheme
    private typealias Chrome = SceneViewTokens.ARChrome
    private static let segments = 10

    var body: some View {
        VStack(alignment: .leading, spacing: SceneViewTokens.Space.sm) {
            Text("ML depth · Depth Anything V2 Small")
                .font(SceneViewTokens.TypeScale.card)
                .foregroundStyle(Chrome.onScrim)
            Text(status.text)
                .font(SceneViewTokens.TypeScale.body)
                .monospacedDigit()
                .foregroundStyle(Chrome.onScrimDim)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("depth-ml-status")
            if case .downloading(let fraction) = status {
                let filled = Int((fraction * Double(Self.segments)).rounded(.down))
                HStack(spacing: SceneViewTokens.Space.xs) {
                    ForEach(0..<Self.segments, id: \.self) { index in
                        Capsule()
                            .fill(index < filled ? Chrome.accentProgress : Chrome.meterTrack)
                            .frame(height: Chrome.meterHeight)
                    }
                }
                .accessibilityElement()
                .accessibilityLabel("Model download progress")
                .accessibilityValue("\(Int((fraction * 100).rounded(.down))) %")
            }
            Text("Apache-2.0 · runs on this device · near 0.3\u{00A0}m to far 5\u{00A0}m")
                .font(SceneViewTokens.TypeScale.caption)
                .foregroundStyle(Chrome.onScrimDim)
        }
        .padding(SceneViewTokens.Space.md)
        .frame(maxWidth: Chrome.cardMaxWidth, alignment: .leading)
        .background(Chrome.scrim(scheme))
        .overlay(
            RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg, style: .continuous)
                .strokeBorder(Chrome.border(scheme), lineWidth: Chrome.borderWidth)
        )
        .clipShape(RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("depth-ml-card")
    }
}

// MARK: - Model

/// Loads the estimator, colourises depth maps off the main thread, and turns the SDK's
/// `DepthSourceState` into the card's status — the same mapping as Android's `MlDepthCard`.
@MainActor
final class DepthVizModel: ObservableObject {
    enum ModelPhase: Equatable {
        case idle
        case downloading(Double)
        case preparing
        case ready
        case failed(String)
    }

    @Published private(set) var modelPhase: ModelPhase = .idle
    @Published private(set) var estimator: DepthAnythingV2Estimator?
    @Published private(set) var overlay: UIImage?
    @Published var depthState: DepthSourceState?
    private var rendering = false
    private var generation = 0
    private var loadTask: Task<Void, Never>?

    var cardStatus: MLCardStatus {
        switch modelPhase {
        case .downloading(let fraction): return .downloading(fraction)
        case .failed(let reason): return .failed(reason)
        case .idle, .preparing: return .preparing
        case .ready: break
        }
        switch depthState {
        case .none, .preparing?, .native?:
            return .preparing
        case .waitingForAnchors(let anchors)?:
            return .waiting(anchors: anchors)
        case .running(let stats)?:
            if stats.holding { return .holding }
            return .running(
                medianMs: Int(stats.medianInferenceMs.rounded()),
                hz: stats.publishedHz,
                anchors: stats.inliers,
                fitErrorPercent: Int((stats.rmsRelativeError * 100).rounded())
            )
        case .throttled(.thermal)?:
            return .throttled
        case .throttled(.tracking)?:
            return .holding
        case .failed(let error)?:
            return .failed(error.localizedDescription)
        case .unavailable(.tooSlow)?:
            return .failed("too slow on this iPhone")
        case .unavailable?:
            return .failed("the estimator stopped")
        }
    }

    /// Fetches the model once (cached afterwards), then builds the estimator off the main thread.
    func loadModel() {
        guard estimator == nil, loadTask == nil else { return }
        let progress: @Sendable (Double) -> Void = { [weak self] fraction in
            Task { @MainActor in self?.applyDownloadProgress(fraction) }
        }
        loadTask = Task { [weak self] in
            do {
                let store = DepthModelStore.shared
                self?.modelPhase = store.cachedCompiledModel() == nil ? .downloading(0) : .preparing
                // Off the main actor: fetch if needed, compile, load. A cached model that no
                // longer loads is purged and fetched again once.
                let estimator = try await Task.detached(priority: .userInitiated) {
                    try await store.estimator(from: .pinnedDownload(), progress: progress)
                }.value
                self?.estimator = estimator
                self?.modelPhase = .ready
            } catch {
                self?.modelPhase = .failed(error.localizedDescription)
            }
            self?.loadTask = nil
        }
    }

    private func applyDownloadProgress(_ fraction: Double) {
        guard case .downloading = modelPhase else { return }
        modelPhase = fraction >= 1 ? .preparing : .downloading(fraction)
    }

    /// Switching source clears the overlay: a LiDAR map must never pass for an ML one.
    func reset() {
        generation += 1
        overlay = nil
        depthState = nil
    }

    func receive(_ frame: ARDepthFrame?) {
        guard let frame else {
            overlay = nil
            return
        }
        // LiDAR maps come at 60 Hz: skip while the previous one is still being coloured.
        guard !rendering else { return }
        rendering = true
        let token = generation
        Task { [weak self] in
            let image = await Task.detached(priority: .userInitiated) {
                RenderedDepth(image: DepthFalseColor.image(frame))
            }.value
            guard let self else { return }
            self.rendering = false
            guard token == self.generation, let cgImage = image.image else { return }
            self.overlay = UIImage(cgImage: cgImage, scale: 1, orientation: .right)
        }
    }
}

// MARK: - False colour

/// A freshly made, never-shared image crossing from the colouring task to the main actor.
private struct RenderedDepth: @unchecked Sendable {
    let image: CGImage?
}

/// Android `DepthVisualization`: 0.3 m red → yellow → green → cyan → 5 m blue, opaque;
/// no depth (0 mm) transparent.
enum DepthFalseColor {
    static let nearMillimetres: Float = 300
    static let farMillimetres: Float = 5_000

    /// RGBA, premultiplied (alpha is 0 or 255, so the colour is unchanged).
    nonisolated static func rgba(millimetres mm: UInt16) -> (UInt8, UInt8, UInt8, UInt8) {
        guard mm > 0 else { return (0, 0, 0, 0) }
        let t = min(1, max(0, (Float(mm) - nearMillimetres) / (farMillimetres - nearMillimetres)))
        let r: Float, g: Float, b: Float
        if t < 1.0 / 3.0 {
            let k = t * 3
            (r, g, b) = (255, k * 255, 0)
        } else if t < 2.0 / 3.0 {
            let k = (t - 1.0 / 3.0) * 3
            (r, g, b) = ((1 - k) * 255, 255, k * 255)
        } else {
            let k = (t - 2.0 / 3.0) * 3
            (r, g, b) = (0, (1 - k) * 255, 255)
        }
        return (UInt8(r.rounded()), UInt8(g.rounded()), UInt8(b.rounded()), 255)
    }

    nonisolated static func image(_ frame: ARDepthFrame) -> CGImage? {
        let w = frame.width, h = frame.height
        guard w > 0, h > 0, frame.millimetres.count >= w * h else { return nil }
        var bytes = [UInt8](repeating: 0, count: w * h * 4)
        for i in 0..<(w * h) {
            let (r, g, b, a) = rgba(millimetres: frame.millimetres[i])
            bytes[i * 4] = r
            bytes[i * 4 + 1] = g
            bytes[i * 4 + 2] = b
            bytes[i * 4 + 3] = a
        }
        guard let provider = CGDataProvider(data: Data(bytes) as CFData) else { return nil }
        return CGImage(
            width: w, height: h, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: w * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
            provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent
        )
    }
}

// MARK: - Preview override (DEBUG, simulator)

/// `-depth-ml-preview <state>` renders one chrome state on the Simulator, where there is no
/// camera and the model is never fetched. Pair it with `-ar-state-preview live` so the AR
/// container mounts the screen without its "Starting camera…" pill over the card. A capture made this way is a render of the state, never
/// a proof that depth ran. Compiled out of release builds.
struct DepthVizPreview {
    let source: DepthVizSource
    let lidar: Bool
    let card: MLCardStatus?
    let depthFlowing: Bool

    static var forced: DepthVizPreview? {
        #if DEBUG
        let args = CommandLine.arguments
        guard let index = args.firstIndex(of: "-depth-ml-preview"), index + 1 < args.count else { return nil }
        func ml(_ card: MLCardStatus) -> DepthVizPreview {
            DepthVizPreview(source: .ml, lidar: false, card: card, depthFlowing: false)
        }
        switch args[index + 1] {
        case "downloading": return ml(.downloading(0.42))
        case "preparing": return ml(.preparing)
        case "waiting": return ml(.waiting(anchors: 3))
        case "running": return ml(.running(medianMs: 31, hz: 4.8, anchors: 214, fitErrorPercent: 4))
        case "holding": return ml(.holding)
        case "throttled": return ml(.throttled)
        case "failed": return ml(.failed("The depth model could not be downloaded (HTTP 503)."))
        case "warming": return DepthVizPreview(source: .sensor, lidar: true, card: nil, depthFlowing: false)
        case "nolidar": return DepthVizPreview(source: .sensor, lidar: false, card: nil, depthFlowing: false)
        default: return nil
        }
        #else
        return nil
        #endif
    }
}
#endif // os(iOS)
