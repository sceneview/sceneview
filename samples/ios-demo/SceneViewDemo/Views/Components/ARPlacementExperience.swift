#if os(iOS)
import SwiftUI
import RealityKit
import ARKit
import UIKit
import SceneViewSwift

/// The two placement facts the one-shot gesture hint needs, sampled together so a placement is
/// never mistaken for a tap. Haptics themselves come from the SDK (`.arHapticFeedback`).
struct PlacementFeedback: Equatable {
    let placed: Bool
    let selected: Bool

    enum Haptic: Equatable { case none, placed, selected }

    /// Placement wins the frame it happens on: the controller selects the object it just placed,
    /// and that selection is not a user action. Selection feedback is therefore reserved for an
    /// explicit tap on a standing object, exactly as the Android sample does.
    static func haptic(from old: PlacementFeedback, to new: PlacementFeedback) -> Haptic {
        if new.placed && !old.placed { return .placed }
        if new.selected && !old.selected { return .selected }
        return .none
    }
}

/// The single automatic-placement experience used by the tab, catalogue and viewers.
/// Assets remain app-owned; generation tickets prevent dismissed or superseded loads
/// from entering the SDK's scene. Bundled models use the same 0.3 m preview as Android.
struct ARPlacementExperience: View {
    enum Occlusion: Equatable {
        case depth, people

        /// Perception stays enabled while comparing the render effect. Changing the toggle
        /// never changes this configuration, the session, the anchor or the model.
        var configuration: ARSessionConfiguration {
            switch self {
            case .depth: return ARSessionConfiguration(sceneReconstruction: .mesh)
            case .people: return ARSessionConfiguration(frameSemantics: [.personSegmentationWithDepth])
            }
        }

        /// Asks the running session for this feature's perception option, once, and only
        /// when the device actually supports it — an unsupported option makes ARKit fail
        /// the whole session, and the container has already explained the requirement.
        @MainActor func configurePerception(of view: ARView) {
            guard configuration.unmetRequirement() == nil else { return }
            view.session.run(configuration.makeARConfiguration())
        }

        @MainActor func apply(enabled: Bool, to view: ARView) {
            switch self {
            case .depth:
                if enabled { view.environment.sceneUnderstanding.options.insert(.occlusion) }
                else { view.environment.sceneUnderstanding.options.remove(.occlusion) }
            case .people:
                if enabled { view.renderOptions.remove(.disablePersonOcclusion) }
                else { view.renderOptions.insert(.disablePersonOcclusion) }
            }
        }
    }

    static let comparisonModel = "khronos_damaged_helmet"
    var wallTV = false
    private var title: String?
    private var occlusion: Occlusion?
    private var featureAccessory: () -> AnyView
    private var featureControls: () -> AnyView
    @State private var occlusionEnabled = true
    var initialModel: String? = nil
    var initialModelURL: URL? = nil
    var initialModelUnit: ModelUnit? = nil

    @StateObject private var controller: ARPlacementController
    @AppStorage("ar-placement-selected-model") private var rememberedModel = "khronos_toy_car"
    @State private var selectedModel: String?
    @State private var placedModelName: String?
    @State private var placedActualSize = false
    @State private var loading = true
    @State private var loadError = false
    @State private var retry = 0
    @State private var show3D = false
    @State private var shareImage: UIImage?
    @State private var showShare = false
    @State private var viewBox = PlacementARViewBox()
    @State private var hintShown = false
    @State private var showHint = false
    @State private var showingActualSize = false

    init(initialModel: String? = nil, initialModelURL: URL? = nil, initialModelUnit: ModelUnit? = nil,
         wallTV: Bool = false, title: String? = nil, occlusion: Occlusion? = nil,
         featureAccessory: @escaping () -> AnyView = { AnyView(EmptyView()) },
         featureControls: @escaping () -> AnyView = { AnyView(EmptyView()) }) {
        self.title = title
        self.occlusion = occlusion
        self.featureAccessory = featureAccessory
        self.featureControls = featureControls
        self.wallTV = wallTV
        _controller = StateObject(wrappedValue: ARPlacementController(alignment: wallTV ? .vertical : .horizontal))
        self.initialModel = initialModel
        self.initialModelURL = initialModelURL
        self.initialModelUnit = initialModelUnit
        let actual = initialModelURL.map { url in
            UserDefaults.standard.object(forKey: Self.sizeBasisKey(url)) as? Bool ?? true
        } ?? false
        _showingActualSize = State(initialValue: actual)
    }

    private static func sizeBasisKey(_ url: URL) -> String {
        "ar-placement-actual-size.\(url.absoluteString)"
    }

    private static let models = [
        (asset: "khronos_toy_car", name: "Toy Car"),
        (asset: "khronos_lantern", name: "Lantern")
    ]

    private var modelName: String { selectedModel ?? initialModel ?? rememberedModel }
    private var usesOpenedFile: Bool { selectedModel == nil && initialModelURL != nil }
    private var actualSize: Bool { usesOpenedFile && showingActualSize }
    private var assetIdentity: String {
        "\(usesOpenedFile ? initialModelURL!.absoluteString : modelName)|\(actualSize)|\(retry)"
    }
    private var displayName: String {
        if wallTV { return "TV" }
        if !usesOpenedFile && modelName == Self.comparisonModel { return "Damaged Helmet" }
        if usesOpenedFile { return initialModelURL!.deletingPathExtension().lastPathComponent }
        return Self.models.first { $0.asset == modelName }?.name
            ?? modelName.replacingOccurrences(of: "_", with: " ").capitalized
    }
    private var renderedActualSize: Bool { placedModelName == nil ? actualSize : placedActualSize }
    private var sizeLabel: String { renderedActualSize ? "Actual size" : "Preview size" }

    var body: some View {
        DemoScaffold(title ?? (wallTV ? "Wall Placement" : "AR Placement"), chromeMode: .ar) {
            placementScene
        } accessory: {
            VStack(spacing: SceneViewTokens.Space.sm) {
                status
                // Apple's coaching overlay is up: step aside (HIG, "Coaching"). The status
                // above keeps its cards; its one-line guidance goes quiet on its own.
                if !controller.isCoachingActive {
                    if occlusion != nil {
                        Toggle("Occlusion", isOn: $occlusionEnabled)
                            .padding(SceneViewTokens.Space.md)
                            .modifier(PlacementStatusSurface())
                            .accessibilityIdentifier("ar-occlusion-toggle")
                    }
                    featureAccessory()
                }
            }
        } controls: {
            controls
        }
        .onChange(of: occlusionEnabled) { _, enabled in
            if let view = viewBox.value { occlusion?.apply(enabled: enabled, to: view) }
        }
        .task(id: assetIdentity) { await loadModel() }
        // Placement, tap, 100 % snap, scale limits, off-surface drag, tracking loss and recovery.
        .arHapticFeedback(controller)
        .onDisappear { controller.dismiss() }
        .onChange(of: showingActualSize) { _, actual in
            if let url = initialModelURL {
                UserDefaults.standard.set(actual, forKey: Self.sizeBasisKey(url))
            }
        }
        // One `onChange` on purpose: automatic placement raises `hasPlacement` and `selection`
        // in the same update, and only the placement opens the gesture hint.
        .onChange(of: PlacementFeedback(placed: controller.hasPlacement, selected: controller.selection)) { old, new in
            if PlacementFeedback.haptic(from: old, to: new) == .placed, !hintShown {
                hintShown = true
                showHint = true
            }
        }
        .onChange(of: controller.phase) { _, phase in
            if phase == .adjusting { showHint = false }
        }
        .task(id: showHint) {
            guard showHint else { return }
            try? await Task.sleep(for: .seconds(5))
            guard !Task.isCancelled else { return }
            showHint = false
        }
        .sheet(isPresented: $showShare) {
            if let shareImage { PlacementShareSheet(image: shareImage) }
        }
        .sheet(isPresented: $show3D) {
            NavigationStack {
                PlacementModelPreview(
                    wallTV: wallTV,
                    modelName: modelName,
                    url: usesOpenedFile ? initialModelURL : nil,
                    unit: initialModelUnit,
                    actualSize: actualSize
                )
                .navigationTitle(displayName)
                .navigationBarTitleInline()
                .toolbar { ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { show3D = false }
                } }
            }
        }
    }

    // One placement scene for every feature: the SDK's `AutoPlacementScene`, never a
    // second placement policy. The occlusion demos need one extra perception option
    // (LiDAR mesh, person segmentation) that the scene does not take as a parameter,
    // so they apply it to the live session once it is running — `session.run` without
    // `.resetTracking`, which keeps tracking, the anchor and the placed subject.
    private var placementScene: some View {
        AutoPlacementScene(controller: controller) { event, view in
            viewBox.value = view
            guard let occlusion else { return }
            if case .started = event { occlusion.configurePerception(of: view) }
            occlusion.apply(enabled: occlusionEnabled, to: view)
        }
    }

    @MainActor private func loadModel() async {
        let ticket = controller.selectModel()
        loading = true
        loadError = false
        do {
            if wallTV {
                guard controller.setModel(Self.makeWallTV(), ticket: ticket, previewSize: 0.3) else {
                    loading = false
                    loadError = true
                    SceneViewHaptic.shared.error()
                    return
                }
                placedModelName = "TV"
                placedActualSize = false
                loading = false
                return
            }
            let node: ModelNode
            if usesOpenedFile, let url = initialModelURL {
                node = try await ModelNode.load(contentsOf: url, unit: initialModelUnit)
            } else {
                node = try await ModelNode.load(modelName)
            }
            guard !Task.isCancelled, controller.acceptsAsset(ticket) else { return }
            // Apply grounding to asynchronous content explicitly, independent of the
            // legacy synchronous onTapOnPlane shadow pass.
            _ = node.withGroundingShadow()
            node.playAllAnimations()
            guard controller.setModel(node.entity, ticket: ticket, previewSize: actualSize ? nil : 0.3) else {
                loading = false
                loadError = true
                SceneViewHaptic.shared.error()
                return
            }
            placedModelName = displayName
            placedActualSize = actualSize
            loading = false
        } catch {
            guard !Task.isCancelled, controller.acceptsAsset(ticket) else { return }
            loading = false
            loadError = true
            SceneViewHaptic.shared.error()
        }
    }

    @ViewBuilder private var status: some View {
        if loadError {
            recoveryCard("Model couldn’t load.", detail: nil, primary: "Try again") { retry += 1 }
        } else if loading && controller.phase != .initializing {
            message("Loading model…")
        } else if controller.phase == .noSurface {
            recoveryCard("No surface found.", detail: "Try a brighter, textured area.", primary: "View in 3D", action: { show3D = true }) {
                Button("Keep scanning") { controller.keepScanning() }
            }
        } else if controller.phase == .recoveryFailed {
            recoveryCard("Couldn’t recover this placement.", detail: nil, primary: "Scan again") {
                controller.resetPlacement()
            }
        } else if controller.invalidMovement {
            message("Keep the object on a surface.")
        } else if controller.isCoachingActive {
            // The coaching overlay is speaking; one voice at a time.
            EmptyView()
        } else {
            switch controller.phase {
            case .initializing, .cameraError:
                // Permission, startup and camera failure belong to ARExperienceContainer.
                EmptyView()
            case .scanning: message(wallTV ? "Point at a wall and move slowly." : "Move slowly to find a surface.")
            case .trackingLost: message("Tracking paused. Move slowly.")
            case .recovering: message("Finding your placement…")
            case .adjusting: message("\(renderedActualSize ? "Actual" : "Preview") scale \(Int(controller.scale * 100))%")
            case .placed:
                if showHint { message("Drag to move. Pinch or twist to adjust.") }
            case .noSurface, .recoveryFailed: EmptyView()
            }
        }
    }

    private func message(_ text: String) -> some View {
        Text(text)
            .font(SceneViewTokens.TypeScale.bodyMedium)
            .fixedSize(horizontal: false, vertical: true)
            .padding(SceneViewTokens.Space.md)
            .modifier(PlacementStatusSurface())
            .allowsHitTesting(false)
    }

    private func recoveryCard(_ title: String, detail: String?, primary: String,
                              action: @escaping () -> Void) -> some View {
        recoveryCard(title, detail: detail, primary: primary, action: action) { EmptyView() }
    }

    private func recoveryCard<Secondary: View>(_ title: String, detail: String?, primary: String,
                                               action: @escaping () -> Void,
                                               @ViewBuilder secondary: () -> Secondary) -> some View {
        VStack(alignment: .leading, spacing: SceneViewTokens.Space.sm) {
            Text(title).font(SceneViewTokens.TypeScale.card)
            if let detail { Text(detail).font(SceneViewTokens.TypeScale.body) }
            Button(primary, action: action)
                .buttonStyle(.borderedProminent)
                .tint(SceneViewTokens.HomeColor.primary)
                .environment(\.colorScheme, .light)
                .frame(minHeight: SceneViewTokens.Layout.touchTarget)
            secondary()
                .buttonStyle(.bordered)
                .frame(minHeight: SceneViewTokens.Layout.touchTarget)
        }
        .fixedSize(horizontal: false, vertical: true)
        .padding(SceneViewTokens.Space.md)
        .modifier(PlacementStatusSurface())
    }

    private var controls: some View {
        VStack(alignment: .leading, spacing: SceneViewTokens.Space.md) {
            Text(placedModelName ?? displayName).font(SceneViewTokens.TypeScale.card)
            Text("\(sizeLabel) · \(Int(controller.scale * 100))%")
                .font(SceneViewTokens.TypeScale.caption)
            if loading { Text("Loading model… \(displayName)") }
            featureControls()
            if !wallTV && occlusion == nil {
                Menu("Pick model") {
                    ForEach(Self.models, id: \.asset) { model in
                        Button(model.name) {
                            selectedModel = model.asset
                            rememberedModel = model.asset
                            SceneViewHaptic.shared.selection()
                        }
                    }
                }
            } else {
                Text("Preview size uses a 0.3 m longest dimension.")
                    .font(SceneViewTokens.TypeScale.caption)
            }
            if usesOpenedFile {
                Toggle("Actual size", isOn: $showingActualSize)
                Text("Preview size uses a 0.3 m longest dimension.")
                    .font(SceneViewTokens.TypeScale.caption)
            }
            Button("Reset placement") { controller.resetPlacement() }
                .disabled(!controller.hasPlacement)
            Button("View in 3D") { show3D = true }
            Button("Share AR screenshot", action: shareScreenshot)
                .disabled(controller.phase == .initializing)
            if controller.hasPlacement && controller.selection {
                Group {
                    Text("Adjust object").font(SceneViewTokens.TypeScale.card)
                    adjustment("Move left", "Move right", decrease: { move(wallTV ? -0.02 : -0.05, 0) }, increase: { move(wallTV ? 0.02 : 0.05, 0) })
                    if wallTV {
                        adjustment("Move down", "Move up", decrease: { move(0, -0.02) }, increase: { move(0, 0.02) })
                    } else {
                        adjustment("Move closer", "Move farther", decrease: { move(0, 0.05) }, increase: { move(0, -0.05) })
                    }
                    adjustment("Rotate left", "Rotate right", decrease: { controller.rotate(by: wallTV ? -.pi / 90 : -.pi / 12) }, increase: { controller.rotate(by: wallTV ? .pi / 90 : .pi / 12) })
                    adjustment("Scale down", "Scale up", decrease: { controller.scale(to: controller.scale - 0.1) }, increase: { controller.scale(to: controller.scale + 0.1) })
                }
                .disabled(controller.phase != .placed)
            }
        }
    }

    private func adjustment(_ decrement: String, _ increment: String,
                            decrease: @escaping () -> Void, increase: @escaping () -> Void) -> some View {
        ViewThatFits(in: .horizontal) {
            HStack { Button(decrement, action: decrease); Button(increment, action: increase) }
            VStack(alignment: .leading) { Button(decrement, action: decrease); Button(increment, action: increase) }
        }
        .buttonStyle(.bordered)
    }

    private func shareScreenshot() {
        viewBox.value?.snapshot(saveToHDR: false) { image in
            Task { @MainActor in
                guard let image, viewBox.value != nil else { return }
                shareImage = image
                showShare = true
            }
        }
    }

    // No per-asset orientation correction (#3735). The helmet USDZ's +90° X node rotation is
    // its own Z-up-to-Y-up conversion: RealityKit loads it upright, as the Model Viewer shows
    // it. The -90° X that used to be applied here mirrored an Android correction that laid
    // the helmet on its back in AR only; both are gone.

    /// Same authored metre geometry and physical material parameters as Android's TV.
    /// This is a procedural TV model, not a simulated camera or placement.
    @MainActor static func makeWallTV() -> Entity {
        let root = Entity()
        let body = ModelEntity(mesh: .generateBox(size: [1.26, 0.74, 0.04]),
                               materials: [SimpleMaterial(color: UIColor(red: 32/255, green: 36/255, blue: 42/255, alpha: 1),
                                                          roughness: 0.8, isMetallic: false)])
        body.position = [0, 0.37, 0.02]
        let screen = ModelEntity(mesh: .generateBox(size: [1.20, 0.68, 0.01]),
                                 materials: [SimpleMaterial(color: UIColor(red: 6/255, green: 8/255, blue: 12/255, alpha: 1),
                                                            roughness: 0.15, isMetallic: false)])
        screen.position = [0, 0.37, 0.045]
        root.addChild(body)
        root.addChild(screen)
        return root
    }

    private func move(_ x: Float, _ y: Float) {
        // A refused move is felt through the SDK's `.invalidMove`.
        controller.move(by: SIMD2<Float>(x, y))
    }
}

@MainActor
private final class PlacementARViewBox { weak var value: ARView? }

private struct PlacementShareSheet: UIViewControllerRepresentable {
    let image: UIImage
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [image], applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

struct PlacementStatusSurface: ViewModifier {
    @Environment(\.colorScheme) private var scheme
    func body(content: Content) -> some View {
        content
            .foregroundStyle(SceneViewTokens.ARChrome.onScrim)
            .background(SceneViewTokens.ARChrome.scrim(scheme), in: RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg))
            .overlay(RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg)
                .strokeBorder(SceneViewTokens.ARChrome.border(scheme), lineWidth: SceneViewTokens.ARChrome.borderWidth))
    }
}

/// A real 3D alternative using the selected asset, never a stand-in on load failure.
private struct PlacementModelPreview: View {
    var wallTV = false
    @State private var television: Entity?
    let modelName: String
    let url: URL?
    let unit: ModelUnit?
    let actualSize: Bool
    @State private var model: ModelNode?
    @State private var failed = false

    var body: some View {
        ZStack {
            SceneView { root in
                if let model { root.addChild(model.entity) }
                if let television { root.addChild(television) }
            }
            .environment(.studio)
            .cameraControls(.orbit)
            .autoCenterContent(true)
            if failed { Text("Model couldn’t load.") }
            else if model == nil && television == nil { ProgressView("Loading model…") }
        }
        .task {
            if wallTV {
                let tv = ARPlacementExperience.makeWallTV()
                tv.scale = SIMD3<Float>(repeating: 0.3 / 1.26)
                television = tv
                return
            }
            do {
                let loaded: ModelNode
                if let url { loaded = try await ModelNode.load(contentsOf: url, unit: unit) }
                else { loaded = try await ModelNode.load(modelName) }
                guard !Task.isCancelled else { return }
                if !actualSize { _ = loaded.scaleToUnits(0.3) }
                model = loaded
            } catch {
                guard !Task.isCancelled else { return }
                failed = true
            }
        }
    }
}
#endif
