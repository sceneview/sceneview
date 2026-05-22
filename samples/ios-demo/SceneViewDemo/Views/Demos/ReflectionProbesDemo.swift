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
    }

    // MARK: - Scene

    @ViewBuilder
    private var sceneContent: some View {
        SceneView { root in
            buildScene(root: root)
        }
        .cameraControls(.orbit)
        .environment(selectedEnvironment.sceneEnvironment)
        .id("\(selectedEnvironment.id)-\(showSphere)-\(String(format: "%.2f", intensity))")
        .ignoresSafeArea()
    }

    @MainActor
    private func buildScene(root: Entity) {
        // Centre box probe
        let probe = ReflectionProbeNode.box(size: [4, 4, 4], intensity: Float(intensity))
        probe.entity.position = .zero
        root.addChild(probe.entity)

        if showSphere {
            // A highly metallic sphere — reflects the environment clearly
            let sphere = GeometryNode.sphere(
                radius: 0.4,
                material: .pbr(color: .white, metallic: 0.9, roughness: 0.05)
            )
            sphere.entity.position = .init(x: 0, y: 0, z: -1.5)
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

        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("Intensity")
                    .font(.subheadline)
                Spacer()
                Text(String(format: "%.1f", intensity))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
            Slider(value: $intensity, in: 0.1...3.0, step: 0.1)
        }

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
