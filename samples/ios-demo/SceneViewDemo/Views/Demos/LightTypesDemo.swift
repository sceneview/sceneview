import SwiftUI
import RealityKit
import SceneViewSwift

/// All three light types: directional, point, and spot.
/// Named `LightingDemo` to mirror the Android demo of the same name.
struct LightingDemo: View {
    @State private var selectedLight: Int = 0

    private let lightNames = ["Directional", "Point", "Spot"]
    private let lightIcons = ["sun.max.fill", "lightbulb.fill", "flashlight.on.fill"]

    var body: some View {
        ZStack {
            SceneView { root in
                // Row of spheres to see lighting effects
                for i in 0..<5 {
                    let x = Float(i - 2) * 0.35
                    let sphere = GeometryNode.sphere(
                        radius: 0.12,
                        material: .pbr(color: .white, metallic: 0.3, roughness: 0.4)
                    )
                    sphere.entity.position = .init(x: x, y: 0, z: -2)
                    root.addChild(sphere.entity)
                }

                // Floor
                let floor = GeometryNode.plane(width: 3, depth: 2, color: .darkGray)
                floor.entity.position = .init(x: 0, y: -0.2, z: -2)
                root.addChild(floor.entity)

                // Add the selected light type
                switch selectedLight {
                case 0:
                    let sun = LightNode.directional(color: .warm, intensity: 2000, castsShadow: true)
                        .position(.init(x: 2, y: 3, z: 0))
                        .lookAt(.init(x: 0, y: 0, z: -2))
                    root.addChild(sun.entity)
                case 1:
                    let point = LightNode.point(color: .custom(r: 0.3, g: 0.6, b: 1.0), intensity: 5000, attenuationRadius: 3)
                        .position(.init(x: 0, y: 0.5, z: -1.5))
                    root.addChild(point.entity)
                    // Visual indicator
                    let indicator = GeometryNode.sphere(radius: 0.03, material: .unlit(color: .cyan))
                    indicator.entity.position = .init(x: 0, y: 0.5, z: -1.5)
                    root.addChild(indicator.entity)
                default:
                    let spot = LightNode.spot(color: .white, intensity: 8000, innerAngle: .pi / 8, outerAngle: .pi / 5, attenuationRadius: 5)
                        .position(.init(x: 0, y: 1.5, z: -1))
                        .lookAt(.init(x: 0, y: 0, z: -2))
                    root.addChild(spot.entity)
                    // Visual indicator
                    let indicator = GeometryNode.cone(height: 0.06, radius: 0.03, color: .yellow)
                    indicator.entity.position = .init(x: 0, y: 1.5, z: -1)
                    root.addChild(indicator.entity)
                }
            }
            .cameraControls(.orbit)
            // Swap the light inside the scene that is already rendering. A
            // SceneView re-created with `.id(_:)` intermittently comes back
            // rendering nothing on iOS 26 Simulator, permanently (#3008).
            .contentID(selectedLight)
            .ignoresSafeArea()

            VStack {
                Spacer()
                // Light type picker
                HStack(spacing: 8) {
                    ForEach(0..<3, id: \.self) { i in
                        Button {
                            selectedLight = i
                            #if os(iOS)
                            SceneViewHaptic.shared.selection()
                            #endif
                        } label: {
                            VStack(spacing: 4) {
                                Image(systemName: lightIcons[i])
                                    .font(.body)
                                Text(lightNames[i])
                                    .font(.caption2)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 8)
                            .background(
                                i == selectedLight
                                    ? AnyShapeStyle(.orange)
                                    : AnyShapeStyle(.white.opacity(0.15))
                            )
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                            .foregroundStyle(.white)
                        }
                        .accessibilityLabel("\(lightNames[i]) light")
                        .accessibilityAddTraits(i == selectedLight ? .isSelected : [])
                    }
                }
                .padding()
                .background(.ultraThinMaterial)
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .padding()
            }
        }
        .background(Color.black)
    }
}
