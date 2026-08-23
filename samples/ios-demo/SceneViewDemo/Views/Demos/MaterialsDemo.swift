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
    /// What the resolver actually handed back — the pill measures this, never
    /// the API-key configuration (#2960).
    @State private var resolvedURL: URL?

    private let hasSketchfabKey: Bool = SketchfabConfig.apiKey != nil

    private var selectedSlug: SketchfabSlug? {
        guard slugs.indices.contains(selectedIndex) else { return nil }
        return slugs[selectedIndex]
    }

    private var assetSource: AssetSourceState {
        AssetSourceProbe.of(
            resolvedURL: resolvedURL,
            hasAPIKey: hasSketchfabKey,
            loaded: loadedNode != nil
        )
    }

    var body: some View {
        ZStack {
            sceneView
            VStack {
                Spacer()
                controls
            }
        }
        .assetSourcePill(assetSource,
                         placeholder: selectedSlug?.fallbackRole == .placeholder)
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
        ZStack {
            // The scene stays mounted for the whole demo — never wrapped in
            // `if let loadedNode` and never re-keyed with `.id(_:)`. Both
            // discard the `RealityView`, and a re-created one intermittently
            // renders nothing at all on iOS 26 Simulator (#3008).
            // `.contentID(_:)` swaps the model inside the scene that is
            // already rendering; the key is `nil` while loading so it also
            // changes when the load lands (same shape as AnimationDemo).
            SceneView { root in
                guard let loadedNode else { return }
                loadedNode.entity.position = .init(x: 0, y: 0, z: -1.5)
                root.addChild(loadedNode.entity)
            }
            .cameraControls(.orbit)
            .autoRotate(speed: 0.3)
            // The whole point of this demo is KHR_materials_transmission /
            // _iridescence / _sheen, and every one of them is defined by what it
            // does to the light *around* the model: transmission refracts the
            // environment, iridescence and sheen shift with the reflected view
            // angle. With no IBL there is nothing to refract or reflect, so the
            // curated models render as flat silhouettes — the demo hides its own
            // subject. Same `.studio` preset ModelViewerDemo uses (#2114).
            .environment(.studio)
            .contentID(loadedContentKey)
            .ignoresSafeArea()

            if loadedNode == nil {
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
    }

    /// What the content closure currently builds: `nil` while the selected
    /// material is streaming, its slug once the model is in hand.
    private var loadedContentKey: String? {
        guard loadedNode != nil else { return nil }
        return selectedSlug?.uid ?? "none"
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
                // Credits the model actually on screen — streamed author or the
                // bundled fallback's own author and licence (#2966).
                AssetCreditLine(slug: slug, source: assetSource,
                                style: AnyShapeStyle(.white.opacity(0.75)))
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
        resolvedURL = nil
        do {
            let url = try await SketchfabAssetResolver.shared.resolve(slug)
            resolvedURL = url
            let node = try await ModelNode.load(contentsOf: url)
            _ = node.scaleToUnits(slug.scaleToUnits)
            _ = node.centerOrigin()
            loadedNode = node
        } catch {
            loadError = error.localizedDescription
        }
    }
}
