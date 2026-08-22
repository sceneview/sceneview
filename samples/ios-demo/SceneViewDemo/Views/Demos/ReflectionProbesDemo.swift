import SwiftUI
import RealityKit
import SceneViewSwift

/// Reflection Probes — local IBL override zones with distinct reflection environments.
///
/// Mirrors the Android `ReflectionProbesDemo`: a scene with two zones, each covered
/// by a `ReflectionProbeNode` that overrides the scene's global IBL with a different
/// environment. A model placed at a zone's center picks up the zone's reflections.
///
/// ### iOS vs Android
/// RealityKit's `ImageBasedLightComponent` is the closest equivalent to Filament's
/// local reflection probes. The iOS demo shows a metallic sphere, and the settings
/// sheet lets you switch between two distinct environments so the probe effect
/// (different sky/hue reflected on the sphere) is visible.
struct ReflectionProbesDemo: View {
    @State private var selectedEnvironment: ProbeEnvironment = .sunset
    @State private var intensity: Double = 1.0
    @State private var showSphere: Bool = true
    /// The selected environment, loaded once per selection and handed to the
    /// probe as its reflection texture. `nil` until the load lands (#3158).
    @State private var probeEnvironment: EnvironmentResource?

    var body: some View {
        ZStack {
            sceneContent
            VStack {
                Spacer()
                Text("ReflectionProbeNode — local IBL override zone")
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.6))
                    .padding(.bottom, 12)
            }
        }
        .demoSettingsSheet {
            settingsContent
        }
        .task(id: selectedEnvironment) {
            probeEnvironment = nil
            probeEnvironment = try? await selectedEnvironment.sceneEnvironment.load()
        }
    }

    // MARK: - Scene

    @ViewBuilder
    private var sceneContent: some View {
        SceneView { root in
            buildScene(root: root)
        }
        .cameraControls(.orbit)
        .environment(selectedEnvironment.sceneEnvironment)
        // The key also flips when the probe texture finishes loading: keyed on
        // the picker alone the scene would already sit at its final key while
        // `probeEnvironment` is still `nil`, and the probe would stay empty.
        .id("\(selectedEnvironment.id)-\(showSphere)-\(String(format: "%.2f", intensity))-\(probeEnvironment != nil)")
        .ignoresSafeArea()
    }

    /// Builds the demo's probe: a 4 m box at the origin carrying the selected
    /// environment as its reflection texture.
    ///
    /// `ReflectionProbeNode.intensity` only reaches RealityKit through the
    /// `ImageBasedLightComponent` that `environmentTexture(_:)` installs — a
    /// probe without a texture is an empty `Entity`, and the intensity slider
    /// driving it is inert (#3158). Static and internal so
    /// `ReflectionProbesDemoTests` can pin that the demo path installs the
    /// component.
    @MainActor
    static func makeProbe(intensity: Float, environment: EnvironmentResource?) -> ReflectionProbeNode {
        let probe = ReflectionProbeNode.box(size: [4, 4, 4], intensity: intensity)
        probe.entity.position = .zero
        if let environment {
            probe.environmentTexture(environment)
        }
        return probe
    }

    /// Points a reflective entity at the probe instead of the scene's global IBL.
    ///
    /// RealityKit resolves `ImageBasedLightReceiverComponent` per entity: the
    /// receiver `environmentTexture(_:)` sets lives on the probe entity itself,
    /// so the geometry that should *show* the probe needs its own receiver
    /// targeting the probe — otherwise it keeps reflecting the global
    /// environment and the probe contributes nothing (#3158).
    @MainActor
    static func attach(_ entity: Entity, to probe: ReflectionProbeNode) {
        entity.components.set(
            ImageBasedLightReceiverComponent(imageBasedLight: probe.entity)
        )
    }

    @MainActor
    private func buildScene(root: Entity) {
        // Centre box probe
        let probe = Self.makeProbe(intensity: Float(intensity), environment: probeEnvironment)
        root.addChild(probe.entity)

        if showSphere {
            // A highly metallic sphere — reflects the environment clearly
            let sphere = GeometryNode.sphere(
                radius: 0.4,
                material: .pbr(color: .white, metallic: 0.9, roughness: 0.05)
            )
            sphere.entity.position = .init(x: 0, y: 0, z: -1.5)
            Self.attach(sphere.entity, to: probe)
            root.addChild(sphere.entity)
        }

        // Three cubes with different metallicness — high metal reflects env vividly, matte doesn't
        let positions: [SIMD3<Float>] = [
            .init(-0.9, 0, -1.5),
            .init(0, 0, -1.5),
            .init(0.9, 0, -1.5),
        ]
        let metals: [Float] = [0.9, 0.5, 0.1]
        for (i, pos) in positions.enumerated() {
            let cube = GeometryNode.cube(
                size: 0.25,
                material: .pbr(color: .white, metallic: metals[i], roughness: 0.1)
            )
            cube.entity.position = pos
            Self.attach(cube.entity, to: probe)
            root.addChild(cube.entity)
        }

        // Labels
        let metalLabel = TextNode(text: "High ← Metallic → Low", fontSize: 0.04, color: .white, depth: 0.003)
            .centered()
        metalLabel.entity.position = .init(x: 0, y: -0.55, z: -1.5)
        root.addChild(metalLabel.entity)
    }

    // MARK: - Settings

    @ViewBuilder
    private var settingsContent: some View {
        Text("Probe Environment")
            .font(.headline)
            .padding(.top, 4)

        ForEach(ProbeEnvironment.allCases) { (env: ProbeEnvironment) in
            Button {
                selectedEnvironment = env
            } label: {
                HStack {
                    Image(systemName: env.icon)
                        .frame(width: 24)
                    Text(env.displayName)
                    Spacer()
                    if selectedEnvironment == env {
                        Image(systemName: "checkmark")
                            .foregroundStyle(.tint)
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .padding(.vertical, 4)
        }

        Divider()

        LabeledSlider(
            label: "Intensity",
            value: $intensity,
            range: 0.1...3.0,
            step: 0.1,
            decimals: 1
        )

        Divider()

        Toggle("Show metallic sphere", isOn: $showSphere)
            .font(.subheadline)
    }
}

// MARK: - Probe environment options

private enum ProbeEnvironment: String, CaseIterable, Identifiable {
    case sunset
    case night
    case studio
    case outdoor

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .sunset:  return "Sunset"
        case .night:   return "Night Sky"
        case .studio:  return "Studio"
        case .outdoor: return "Outdoor"
        }
    }

    var icon: String {
        switch self {
        case .sunset:  return "sun.horizon.fill"
        case .night:   return "moon.stars.fill"
        case .studio:  return "lamp.desk.fill"
        case .outdoor: return "cloud.sun.fill"
        }
    }

    var sceneEnvironment: SceneEnvironment {
        switch self {
        case .sunset:  return .sunset
        case .night:   return .nightSky
        case .studio:  return .studio
        case .outdoor: return .outdoor
        }
    }
}
