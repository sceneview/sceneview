import SwiftUI
import RealityKit
import SceneViewSwift

/// Shape Extrude — 2D polygons extruded into 3D meshes.
///
/// Conceptually mirrors Android's `ShapeNode` / `ShapeGeometry`:
/// - Gallery of preset shapes: Triangle, Star, Pentagon, Hexagon, L-Shape, Arrow
/// - Depth slider controls extrusion depth (0 = flat, up to 0.4 m)
/// - Material toggle: PBR metallic vs unlit flat color
///
/// Each shape is built with ``ShapeNode`` from `SceneViewSwift`. iOS uses an
/// equivalent hand-rolled ear-clipping triangulation rather than the Android
/// `sceneview-core` Earcut port, so the meshes are equivalent but not produced
/// by identical code.
struct ShapeExtrudeDemo: View {
    @State private var selectedPreset: ShapePreset = ShapeExtrudeDemo.launchArgPreset ?? .star
    @State private var extrusionDepth: Float = 0.15
    @State private var useUnlit: Bool = false
    @State private var sceneKey = UUID()

    /// Optional `-shapePreset <id>` launch argument (e.g. `-shapePreset arrow`)
    /// that pre-selects a preset, so the QA / screenshot harness can drive the
    /// concave presets without tapping the settings sheet. Mirrors the
    /// `-demo <id>` / `-qa_mode 1` launch-arg idiom in `SceneViewDemoApp`.
    /// Unknown / absent values fall back to the default `.star`.
    private static let launchArgPreset: ShapePreset? = {
        let args = CommandLine.arguments
        guard let idx = args.firstIndex(of: "-shapePreset"), idx + 1 < args.count else { return nil }
        return ShapePreset(rawValue: args[idx + 1])
    }()

    var body: some View {
        ZStack {
            SceneView { root in
                buildScene(root: root)
            }
            .cameraControls(.orbit)
            .environment(.studio)
            .id(sceneKey)
            .ignoresSafeArea()

            VStack {
                Spacer()
                Text("ShapeNode — 2D polygon extruded into 3D mesh")
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.6))
                    .padding(.bottom, 12)
            }
        }
        .demoChrome {
            settingsContent
        }
        .onChange(of: selectedPreset) { _, _ in sceneKey = UUID() }
        .onChange(of: extrusionDepth) { _, _ in sceneKey = UUID() }
        .onChange(of: useUnlit) { _, _ in sceneKey = UUID() }
    }

    // MARK: - Scene

    @MainActor
    private func buildScene(root: Entity) {
        let preset = selectedPreset
        let depth = extrusionDepth
        let unlit = useUnlit

        let shape = ShapeNode(
            points: preset.points,
            extrusionDepth: depth,
            color: preset.color,
            isMetallic: !unlit,
            unlit: unlit
        )
        shape.entity.position = .init(x: 0, y: 0, z: -2)
        // The shape now lives in the XY plane facing the camera (+Z), so it
        // reads as a flat star head-on. Apply a gentle compound tilt — a small
        // yaw (Y) plus a small pitch (X) — so the silhouette stays clearly
        // readable while the extrusion thickness along Z is still visible.
        let yaw = simd_quatf(angle: .pi / 7, axis: .init(x: 0, y: 1, z: 0))
        let pitch = simd_quatf(angle: -.pi / 14, axis: .init(x: 1, y: 0, z: 0))
        shape.entity.orientation = pitch * yaw

        if !unlit {
            // Add a grounding shadow for PBR materials (iOS 18+)
            if #available(iOS 18.0, *) {
                shape.entity.components.set(GroundingShadowComponent(castsShadow: true))
            }
        }

        root.addChild(shape.entity)

        // Label
        let label = TextNode(
            text: preset.displayName,
            fontSize: 0.055,
            color: .white,
            depth: 0.005
        ).centered()
        label.entity.position = .init(x: 0, y: -0.85, z: -2)
        root.addChild(label.entity)
    }

    // MARK: - Settings

    @ViewBuilder
    private var settingsContent: some View {
        Text("Shape Preset")
            .font(.headline)
            .padding(.top, 4)

        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
            ForEach(ShapePreset.allCases) { preset in
                Button {
                    selectedPreset = preset
                } label: {
                    Text(preset.displayName)
                        .font(.caption)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                        .background(
                            selectedPreset == preset
                                ? Color.accentColor
                                : Color(.systemFill)
                        )
                        .foregroundStyle(
                            selectedPreset == preset ? .white : .primary
                        )
                        .cornerRadius(8)
                }
            }
        }
        .padding(.bottom, 4)

        Divider()

        LabeledSlider(
            label: "Extrusion Depth",
            value: $extrusionDepth,
            range: 0...0.4,
            step: 0.01,
            unit: "m"
        )

        Divider()

        Toggle("Unlit (flat color)", isOn: $useUnlit)
            .font(.subheadline)
    }
}

// MARK: - Shape presets

private enum ShapePreset: String, CaseIterable, Identifiable {
    case triangle
    case star
    case pentagon
    case hexagon
    case lShape
    case arrow

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .triangle: return "Triangle"
        case .star:     return "Star"
        case .pentagon: return "Pentagon"
        case .hexagon:  return "Hexagon"
        case .lShape:   return "L-Shape"
        case .arrow:    return "Arrow"
        }
    }

    var color: SimpleMaterial.Color {
        switch self {
        case .triangle: return .systemOrange
        case .star:     return .systemYellow
        case .pentagon: return .systemIndigo
        case .hexagon:  return .systemTeal
        case .lShape:   return .systemGreen
        case .arrow:    return .systemPink
        }
    }

    /// The polygon geometry lives in ``SceneViewSwift/ShapePresets`` — the
    /// single source of truth shared with the `SceneViewSwift` triangulation
    /// tests, so a retune (e.g. the star's inner/outer radius) updates the demo
    /// and its guarding test together rather than silently drifting apart.
    var points: [SIMD2<Float>] {
        switch self {
        case .triangle: return ShapePresets.triangle
        case .star:     return ShapePresets.star
        case .pentagon: return ShapePresets.pentagon
        case .hexagon:  return ShapePresets.hexagon
        case .lShape:   return ShapePresets.lShape
        case .arrow:    return ShapePresets.arrow
        }
    }
}
