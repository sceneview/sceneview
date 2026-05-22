import SwiftUI
import RealityKit
import SceneViewSwift

/// HDR environment switching demo.
///
/// Loads the bundled hero model (metallic/reflective) and lets the user
/// switch between the seven bundled HDR environments. Each environment
/// changes the scene's image-based lighting, making reflections and
/// overall scene tone update instantly.
///
/// Mirrors the Android `EnvironmentDemo` (`samples/android-demo/.../EnvironmentDemo.kt`):
/// the same hero model (reflective, PBR) is held static while the environment
/// rotates so A/B comparison is easy — if both the model and the environment
/// moved simultaneously, the reflections would be ambiguous.
///
/// The `.environment(_:)` modifier + `SceneEnvironment` presets are the iOS
/// SceneViewSwift API; the Android demo uses `rememberEnvironmentLoader` /
/// `createHDREnvironment` from the Filament backend — different renderer,
/// same concept.
struct EnvironmentDemo: View {
    @State private var selectedEnvironment: SceneEnvironment = .studio
    @State private var loadedNode: ModelNode?
    @State private var loadError: String?

    var body: some View {
        content
            .demoSettingsSheet {
                environmentPicker
            }
    }

    @ViewBuilder
    private var content: some View {
        ZStack {
            if let loadedNode {
                SceneView { root in
                    loadedNode.entity.position = .init(x: 0, y: 0, z: -1.5)
                    root.addChild(loadedNode.entity)
                }
                .environment(selectedEnvironment)
                .cameraControls(.orbit)
                .autoRotate(speed: 0.15)
                .ignoresSafeArea()
                // Re-mount the RealityView when the environment changes so the new
                // EnvironmentResource is applied cleanly. A `.id` keyed on the
                // environment name is simpler than patching ImageBasedLightComponent
                // in place and avoids any cached-resource aliasing edge cases.
                .id("environment-\(selectedEnvironment.name)")
            } else {
                VStack(spacing: 12) {
                    ProgressView()
                        .tint(.white)
                    if let loadError {
                        Text(loadError)
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.7))
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 24)
                    } else {
                        Text("Loading model…")
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.7))
                    }
                }
            }
        }
        .background(Color.black)
        .task {
            await loadHero()
        }
    }

    @ViewBuilder
    private var environmentPicker: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Environment")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.secondary)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 100))], spacing: 8) {
                ForEach(SceneEnvironment.allPresets, id: \.name) { env in
                    Button {
                        selectedEnvironment = env
                        #if os(iOS)
                        SceneViewHaptic.shared.light()
                        #endif
                    } label: {
                        Text(env.name)
                            .font(.caption.weight(.semibold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                            .background(
                                RoundedRectangle(cornerRadius: 10)
                                    .fill(selectedEnvironment.name == env.name
                                          ? Color.accentColor
                                          : Color(.systemFill))
                            )
                            .foregroundStyle(selectedEnvironment.name == env.name
                                             ? Color.white
                                             : Color.primary)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Environment: \(env.name)")
                    .accessibilityAddTraits(selectedEnvironment.name == env.name ? .isSelected : [])
                }
            }
        }
        .padding(.bottom, 4)
    }

    @MainActor
    private func loadHero() async {
        loadError = nil
        do {
            // Use the same cyberpunk_hovercar hero as ModelViewerDemo — it has high
            // specular roughness variation so environmental lighting reads clearly.
            let node = try await ModelNode.load("cyberpunk_hovercar")
            _ = node.scaleToUnits(0.6)
            _ = node.centerOrigin()
            loadedNode = node
        } catch {
            loadError = "Could not load model: \(error.localizedDescription)"
        }
    }
}
