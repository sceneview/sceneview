import SwiftUI
import RealityKit
import SceneViewSwift

/// Fog effect demo -- linear and exponential modes.
///
/// Height-based fog is not supported on RealityKit (Filament-only feature on Android);
/// see #1380. The mode is omitted from this demo so the UI does not advertise a
/// silent no-op.
struct FogDemo: View {
    @State private var fogMode: Int = 0
    @State private var density: Float = 0.3

    private let modeNames = ["Linear", "Exponential"]
    private let modeIcons = ["line.diagonal", "waveform"]

    var body: some View {
        sceneContent
            .demoSettingsSheet {
                controlsSheet
            }
    }

    @ViewBuilder
    private var sceneContent: some View {
        ZStack {
            SceneView { root in
                // Forest of cylinders (trees)
                let treePositions: [(Float, Float)] = [
                    (-0.6, -2.0), (-0.3, -2.5), (0.0, -1.8),
                    (0.3, -2.3), (0.6, -2.1), (-0.5, -3.0),
                    (0.1, -3.2), (0.5, -2.8), (-0.2, -1.5),
                ]
                for (x, z) in treePositions {
                    let height = Float.random(in: 0.3...0.7)
                    let trunk = GeometryNode.cylinder(radius: 0.03, height: height, color: .brown)
                    trunk.entity.position = .init(x: x, y: height / 2 - 0.3, z: z)
                    root.addChild(trunk.entity)

                    let canopy = GeometryNode.sphere(radius: 0.1, color: .init(red: 0.1, green: 0.5, blue: 0.15, alpha: 1))
                    canopy.entity.position = .init(x: x, y: height - 0.15, z: z)
                    root.addChild(canopy.entity)
                }

                // Ground
                let ground = GeometryNode.plane(width: 4, depth: 4, color: .init(red: 0.15, green: 0.3, blue: 0.12, alpha: 1))
                ground.entity.position = .init(x: 0, y: -0.3, z: -2)
                root.addChild(ground.entity)

                // Fog
                let fog: FogNode
                switch fogMode {
                case 0:
                    fog = FogNode.linear(start: 0.5, end: 5.0, color: .cool)
                        .density(density)
                default:
                    fog = FogNode.exponential(density: density, color: .warm)
                }
                fog.entity.position = .init(x: 0, y: 0, z: -2)
                root.addChild(fog.entity)
            }
            .cameraControls(.orbit)
            .id("fog-\(fogMode)-\(Int(density * 100))")
            .ignoresSafeArea()
        }
        .background(Color.black)
    }

    @ViewBuilder
    private var controlsSheet: some View {
        VStack(spacing: 12) {
            // Mode selector
            HStack(spacing: 8) {
                ForEach(0..<modeNames.count, id: \.self) { i in
                    Button {
                        fogMode = i
                        #if os(iOS)
                        SceneViewHaptic.shared.selection()
                        #endif
                    } label: {
                        VStack(spacing: 4) {
                            Image(systemName: modeIcons[i])
                            Text(modeNames[i])
                                .font(.caption2)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(
                            i == fogMode
                                ? AnyShapeStyle(.blue)
                                : AnyShapeStyle(.gray.opacity(0.15))
                        )
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                        .foregroundStyle(i == fogMode ? .white : .primary)
                    }
                    .accessibilityLabel("\(modeNames[i]) fog")
                    .accessibilityAddTraits(i == fogMode ? .isSelected : [])
                }
            }

            // Density slider
            HStack {
                Text("Density")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Slider(value: $density, in: 0.05...0.8)
                    .tint(.blue)
                    .accessibilityLabel("Fog density")
                Text("\(Int(density * 100))%")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
                    .frame(width: 36)
            }
        }
    }
}
