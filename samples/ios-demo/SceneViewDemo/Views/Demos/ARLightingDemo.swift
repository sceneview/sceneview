#if os(iOS)
import SwiftUI
import RealityKit
import ARKit
import SceneViewSwift

/// AR demonstration of the `.mainLight(_:)` + `.fillLight(_:)` modifiers
/// shipped in v4.2.0 (PR #1151, closes #1138, follow-up demo #1155).
///
/// The two-light setup mirrors Android's `ARSceneView(mainLightNode = …,
/// fillLightNode = …)` defaults (`#1063`):
///
/// - **Main light** — bright directional (~10 000 lux), straight down, casts shadows.
/// - **Fill light** — soft directional (~3 000 lux), upper-front, no shadows.
///
/// Three filter chips at the bottom let the user toggle between:
/// 1. **Default** — `.systemDefault` on both slots (Android-parity).
/// 2. **Dim key** — `.custom(LightNode.directional(intensity: 5_000))` main +
///    `.systemDefault` fill (moody studio-style lighting).
/// 3. **Key only** — `.systemDefault` main + `.disabled` fill (single-light
///    setup, hard shadows, dramatic).
///
/// The model auto-anchors at world origin (the camera pose when tracking
/// begins), so the user just needs to point the camera at a clear floor area
/// and walk around to compare the three lighting setups from different angles.
///
/// Mirrors Android `samples/android-demo/.../demos/ARPlacementDemo.kt` for the
/// AR scaffolding (anchor at world origin, model load, status pill) but
/// focuses the demo on the lighting modifiers rather than tap-to-place.
struct ARLightingDemo: View {
    /// The three lighting presets the user can switch between.
    private enum LightingMode: String, CaseIterable, Identifiable {
        case `default` = "Default"
        case dimKey = "Dim Key"
        case keyOnly = "Key Only"

        var id: String { rawValue }

        var subtitle: String {
            switch self {
            case .default: return "10k lux main + 3k lux fill"
            case .dimKey: return "5k lux main + 3k lux fill"
            case .keyOnly: return "10k lux main, no fill"
            }
        }

        // `LightNode.directional(...)` is `@MainActor` (touches a RealityKit
        // `Entity`), so the slot helpers that allocate it must be too. The
        // call sites below are inside SwiftUI `body` / button actions that
        // are already MainActor-isolated.
        @MainActor
        var mainSlot: LightSlot {
            switch self {
            case .default, .keyOnly: return .systemDefault
            case .dimKey:
                // Custom dimmer main light — half the default 10 000 lux. The
                // factory does not bake in a direction so the ARSceneView
                // default-orientation logic still applies (straight down).
                return .custom(LightNode.directional(intensity: 5_000))
            }
        }

        var fillSlot: LightSlot {
            switch self {
            case .default, .dimKey: return .systemDefault
            case .keyOnly: return .disabled
            }
        }
    }

    @State private var mode: LightingMode = .default
    @State private var modelLoaded = false
    @State private var loadFailed = false

    var body: some View {
        ZStack {
            #if !targetEnvironment(simulator)
            arSceneView
                .ignoresSafeArea()
            #else
            simulatorPlaceholder
            #endif

            VStack {
                statusPill
                Spacer()
                modeChips
            }
        }
        .navigationTitle("AR Lighting")
        .navigationBarTitleInline()
    }

    // MARK: - AR Scene

    #if !targetEnvironment(simulator)
    private var arSceneView: some View {
        // Re-creating the view when `mode` changes makes the SwiftUI diff pick
        // up the new modifier values. Reactive light swapping inside a single
        // ARSceneView is tracked as a follow-up — for the demo this clean
        // re-init is fine (model reloads in a fraction of a second).
        ARSceneView(
            planeDetection: .horizontal,
            showPlaneOverlay: false,
            showCoachingOverlay: true
        )
        .mainLight(mode.mainSlot)
        .fillLight(mode.fillSlot)
        .onSessionStarted { arView in
            // Anchor at the camera pose when tracking begins, ~1 m in front
            // of the user so they always see the model without needing to
            // hunt for it. The model is loaded asynchronously and added as
            // a child of the anchor entity.
            let anchor = AnchorNode.world(position: .init(0, -0.3, -1.0))
            arView.scene.addAnchor(anchor.entity)

            Task { @MainActor in
                do {
                    let node = try await ModelNode.load("phoenix_bird")
                    _ = node.scaleToUnits(0.5)
                    if node.animationCount > 0 {
                        node.playAllAnimations()
                    }
                    anchor.add(node.entity)
                    modelLoaded = true
                } catch {
                    print("[ARLighting] Failed to load model: \(error)")
                    loadFailed = true
                }
            }
        }
        .id(mode)  // force re-init when the lighting mode flips
    }
    #endif

    // MARK: - UI

    private var statusPill: some View {
        let label: String = {
            if loadFailed { return "Couldn't load the model" }
            if !modelLoaded { return "Loading model — keep the camera steady" }
            return "\(mode.rawValue) — \(mode.subtitle)"
        }()
        return Text(label)
            .font(.caption.weight(.medium))
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(.ultraThinMaterial)
            .clipShape(Capsule())
            .padding(.top, 8)
    }

    private var modeChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(LightingMode.allCases) { option in
                    Button {
                        mode = option
                        SceneViewHaptic.shared.selection()
                    } label: {
                        VStack(spacing: 2) {
                            Text(option.rawValue)
                                .font(.subheadline.weight(.semibold))
                            Text(option.subtitle)
                                .font(.caption2)
                                .opacity(0.85)
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .background(
                            mode == option
                                ? AnyShapeStyle(.tint)
                                : AnyShapeStyle(.regularMaterial),
                            in: Capsule()
                        )
                        .foregroundStyle(mode == option ? Color.white : Color.primary)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(option.rawValue) lighting: \(option.subtitle)")
                    .accessibilityAddTraits(mode == option ? .isSelected : [])
                }
            }
            .padding(.horizontal, 16)
        }
        .scrollClipDisabled()
        .padding(.bottom, 24)
    }

    // MARK: - Simulator placeholder

    private var simulatorPlaceholder: some View {
        VStack(spacing: 16) {
            Image(systemName: "lightbulb.max.fill")
                .font(.system(size: 60))
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            Text("AR requires a physical device")
                .font(.headline)
            Text("Run on iPhone or iPad to compare the three lighting modes.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemGroupedBackground))
    }
}
#endif
