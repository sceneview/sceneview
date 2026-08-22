import SwiftUI

/// About page showing SDK info, links, and the SceneView logo.
struct AboutView: View {
    /// Marketing version read at runtime from the bundle's Info.plist
    /// (`CFBundleShortVersionString`). Pre-#review-2026-05-26 this was a
    /// hard-coded literal that drifted every release; tying the user-visible
    /// "Version" line to the same `MARKETING_VERSION` Xcode bumps keeps the
    /// two surfaces in lockstep with zero release-day discipline.
    private var marketingVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String
            ?? "unknown"
    }

    var body: some View {
        NavigationStack {
            List {
                // SDK info
                Section {
                    LabeledContent("Version", value: marketingVersion)
                    LabeledContent("Platform", value: "iOS 18+ / visionOS 2+")
                    LabeledContent("Engine", value: "RealityKit + ARKit")
                    LabeledContent("License", value: "Apache 2.0")
                } header: {
                    Text("SceneView iOS SDK")
                }

                // Features
                Section {
                    FeatureRow(
                        icon: "cube.transparent",
                        title: "3D Scenes",
                        subtitle: "SceneView with orbit camera and IBL"
                    )
                    FeatureRow(
                        icon: "arkit",
                        title: "Augmented Reality",
                        subtitle: "ARSceneView with plane detection"
                    )
                    FeatureRow(
                        icon: "cube.fill",
                        title: "Procedural Geometry",
                        subtitle: "Cube, sphere, cylinder, cone, plane"
                    )
                    FeatureRow(
                        icon: "textformat.abc",
                        title: "3D Text",
                        subtitle: "Extruded text with custom fonts"
                    )
                    FeatureRow(
                        icon: "light.max",
                        title: "Lighting",
                        subtitle: "Directional, point, spot with shadows"
                    )
                    FeatureRow(
                        icon: "camera.metering.center.weighted",
                        title: "Camera Controls",
                        subtitle: "Orbit with inertia and auto-rotate"
                    )
                } header: {
                    Text("Features")
                }

                // Node types count
                Section {
                    LabeledContent("ModelNode", value: "USDZ + animations")
                    LabeledContent("GeometryNode", value: "5 primitives + PBR")
                    LabeledContent("TextNode", value: "3D extruded text")
                    LabeledContent("BillboardNode", value: "Camera-facing")
                    LabeledContent("LineNode", value: "Segments + gizmo")
                    LabeledContent("PathNode", value: "Polyline + grid")
                    LabeledContent("LightNode", value: "3 light types")
                    LabeledContent("AnchorNode", value: "AR anchors")
                } header: {
                    Text("Node Types")
                }

                // Cross-platform
                Section {
                    Label {
                        VStack(alignment: .leading) {
                            Text("SceneView Android")
                                .font(.body)
                            Text("Jetpack Compose + Filament")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    } icon: {
                        Image(systemName: "apple.logo")
                            .hidden()
                            .overlay {
                                Text("A")
                                    .font(.title3.bold())
                                    .foregroundStyle(.green)
                            }
                    }

                    Label {
                        VStack(alignment: .leading) {
                            Text("SceneView iOS")
                                .font(.body)
                            Text("SwiftUI + RealityKit")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    } icon: {
                        Image(systemName: "apple.logo")
                    }
                } header: {
                    Text("Cross-Platform")
                } footer: {
                    Text("Same declarative API pattern on both platforms.")
                }
            }
            .navigationTitle("About")
            .navigationBarTitleDisplayMode(.large)
        }
    }
}

private struct FeatureRow: View {
    let icon: String
    let title: String
    let subtitle: String

    var body: some View {
        Label {
            VStack(alignment: .leading) {
                Text(title)
                    .font(.body)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        } icon: {
            Image(systemName: icon)
                .foregroundStyle(.accent)
        }
    }
}

#Preview {
    AboutView()
}
