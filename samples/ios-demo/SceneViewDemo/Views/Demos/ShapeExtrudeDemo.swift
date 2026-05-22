import SwiftUI
import RealityKit
import SceneViewSwift

/// Shape Extrude — 2D polygons extruded into 3D meshes.
///
/// Mirrors the Android `ShapeExtrudeDemo` (`samples/android-demo/.../ShapeExtrudeDemo.kt`):
/// - Gallery of preset shapes: Triangle, Star, Pentagon, Hexagon, L-Shape, Arrow
/// - Depth slider controls extrusion depth (0 = flat, up to 0.4 m)
/// - Material toggle: PBR metallic vs unlit flat color
///
/// Each shape is built with ``ShapeNode`` from `SceneViewSwift` which uses an
/// ear-clipping triangulator to create the mesh — identical algorithm to the
/// Android `sceneview-core` Earcut port.
struct ShapeExtrudeDemo: View {
    @State private var selectedPreset: ShapePreset = .star
    @State private var extrusionDepth: Float = 0.15
    @State private var useUnlit: Bool = false
    @State private var sceneKey = UUID()

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
        .demoSettingsSheet {
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
        // Tilt slightly so the extrusion depth reads clearly
        shape.entity.orientation = simd_quatf(angle: -.pi / 8, axis: .init(x: 1, y: 0, z: 0))

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
                                : Color(.secondarySystemBackground)
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

        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("Extrusion Depth")
                    .font(.subheadline)
                Spacer()
                Text(String(format: "%.2f m", extrusionDepth))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
            Slider(value: $extrusionDepth, in: 0...0.4, step: 0.01)
        }

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

    var points: [SIMD2<Float>] {
        switch self {
        case .triangle:
            return [
                .init(0, 0.5),
                .init(-0.5, -0.4),
                .init(0.5, -0.4),
            ]
        case .star:
            var pts: [SIMD2<Float>] = []
            let n = 5
            let outerR: Float = 0.5
            let innerR: Float = 0.22
            for i in 0..<(n * 2) {
                let angle = Float(i) / Float(n * 2) * 2 * .pi - .pi / 2
                let r: Float = i % 2 == 0 ? outerR : innerR
                pts.append(.init(r * cos(angle), r * sin(angle)))
            }
            return pts
        case .pentagon:
            return (0..<5).map { i in
                let angle = Float(i) / 5 * 2 * .pi - .pi / 2
                return .init(0.5 * cos(angle), 0.5 * sin(angle))
            }
        case .hexagon:
            return (0..<6).map { i in
                let angle = Float(i) / 6 * 2 * .pi
                return .init(0.5 * cos(angle), 0.5 * sin(angle))
            }
        case .lShape:
            return [
                .init(-0.4, -0.4),
                .init(0.1, -0.4),
                .init(0.1, 0.0),
                .init(-0.1, 0.0),
                .init(-0.1, 0.4),
                .init(-0.4, 0.4),
            ]
        case .arrow:
            return [
                // Shaft
                .init(-0.35, -0.1),
                .init(0.05, -0.1),
                .init(0.05, -0.3),
                // Arrowhead
                .init(0.4,  0.0),
                .init(0.05, 0.3),
                .init(0.05, 0.1),
                .init(-0.35, 0.1),
            ]
        }
    }
}
