import SwiftUI
import RealityKit
import SceneViewSwift

/// Streamed showcase of the `KHR_materials_*` PBR extension family — sheen,
/// transmission, iridescence — sourced from Sketchfab's CC-BY PBR catalogue
/// (the same curated set declared in `SampleAssets`'s `materials` category).
///
/// The previous version of this demo was a 5-sphere metallic/roughness
/// spectrum that didn't actually exercise any of the modern glTF material
/// extensions. Stage 2 replaces it with curated extension-bearing models so
/// the demo answers "what do `KHR_materials_sheen` / `_transmission` /
/// `_iridescence` look like in SceneView?" at a glance.
///
/// Honours the umbrella's hard rules:
///   - **No Sketchfab WebView / external link.** Local file URLs only.
///   - **No network required to render something useful.** Empty key / cold
///     cache → the resolver stages the bundled fallback. The fallback
///     assets do not carry the actual extension materials (those are
///     author-controlled) but they keep the viewport non-empty.
struct MaterialsDemo: View {
    private let slugs: [SketchfabSlug] = SampleAssets.byCategory["materials"] ?? []

    @State private var selectedIndex: Int = 0
    @State private var loadedNode: ModelNode?
    @State private var loadError: String?

    private var selectedSlug: SketchfabSlug? {
        guard slugs.indices.contains(selectedIndex) else { return nil }
        return slugs[selectedIndex]
    }

    var body: some View {
        ZStack {
            sceneView
            VStack {
                Spacer()
                controls
            }
        }
        .background(Color.black)
        .task(id: selectedSlug?.uid) {
            await loadSelectedSlug()
        }
        .task {
            _ = await SketchfabAssetResolver.shared.prefetchAll(category: "materials")
        }
    }

    @ViewBuilder
    private var sceneView: some View {
        if let loadedNode {
            SceneView { root in
                loadedNode.entity.position = .init(x: 0, y: 0, z: -1.5)
                root.addChild(loadedNode.entity)
            }
            .cameraControls(.orbit)
            .autoRotate(speed: 0.3)
            .ignoresSafeArea()
            .id("materials-\(selectedSlug?.uid ?? "none")")
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
                    Text("Streaming material…")
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.7))
                }
            }
        }
    }

    private var controls: some View {
        VStack(spacing: 8) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Array(slugs.enumerated()), id: \.element.uid) { index, slug in
                        Button {
                            selectedIndex = index
                            #if os(iOS)
                            SceneViewHaptic.shared.light()
                            #endif
                        } label: {
                            Text(slug.displayName)
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(index == selectedIndex ? Color.black : Color.white)
                                .padding(.horizontal, 14)
                                .padding(.vertical, 8)
                                .background(
                                    Capsule()
                                        .fill(index == selectedIndex ? Color.white : Color.white.opacity(0.12))
                                )
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 20)
            }

            if let slug = selectedSlug {
                // `tags[0]` is the `KHR_materials_*` extension name in the
                // curated registry — surface it so the user maps the chip
                // choice to the extension being demoed.
                if let ext = slug.tags.first, !ext.isEmpty {
                    Text(ext)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.white.opacity(0.85))
                }
                Text("by \(slug.author) · CC-BY 4.0")
                    .font(.caption2)
                    .foregroundStyle(.white.opacity(0.75))
            }
        }
        .padding(.vertical, 12)
        .background(.ultraThinMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .padding(.horizontal, 16)
        .padding(.bottom, 24)
    }

    @MainActor
    private func loadSelectedSlug() async {
        guard let slug = selectedSlug else { return }
        loadedNode = nil
        loadError = nil
        do {
            let url = try await SketchfabAssetResolver.shared.resolve(slug)
            let node = try await ModelNode.load(contentsOf: url)
            _ = node.scaleToUnits(slug.scaleToUnits)
            _ = node.centerOrigin()
            loadedNode = node
        } catch {
            loadError = error.localizedDescription
        }
    }
}
