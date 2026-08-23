import SwiftUI
import RealityKit
import SceneViewSwift

/// Streamed model gallery — themed bundles (Animals, Furniture, Retro, …)
/// rotating Sketchfab CC-BY content. Each chip selects one `SketchfabSlug` in
/// the curated `gallery` category of `SampleAssets`; the resolver hands back
/// either the streamed asset or the bundled fallback when no API key is
/// configured. `SceneView` then renders the model with an orbit camera.
///
/// Honours the umbrella's hard rules:
///   - **No Sketchfab WebView / external link** — the demo only ever feeds the
///     local `URL` returned by `SketchfabAssetResolver.resolve` to
///     `ModelNode.load(contentsOf:)`.
///   - **No network required to render something useful** — empty key (App Store
///     cold-cache builds) → the resolver stages the bundled fallback under the
///     same cache root and the demo renders it the same way as the streamed file.
///   - **License attribution preserved** — the per-chip caption shows the
///     author name. The Credits sheet (Stage 3) will surface the full
///     per-model attribution.
struct SceneGalleryDemo: View {
    private let slugs: [SketchfabSlug] = SampleAssets.byCategory["gallery"] ?? []

    @State private var selectedIndex: Int = 0
    @State private var loadedNode: ModelNode?
    @State private var loadError: String?
    /// What the resolver actually handed back for the selected slug — the only
    /// honest input to the asset-source pill (#2960). `nil` while resolving.
    @State private var resolvedURL: URL?

    private let hasSketchfabKey: Bool = SketchfabConfig.apiKey != nil

    private var selectedSlug: SketchfabSlug? {
        guard slugs.indices.contains(selectedIndex) else { return nil }
        return slugs[selectedIndex]
    }

    /// "Loaded" here means the model *parsed*, not merely that the file
    /// arrived, so the pill and the centre spinner stay in lockstep.
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
            // Warm the whole gallery on first appear so chip taps land on a
            // hot cache. The resolver is idempotent — re-running it is cheap.
            _ = await SketchfabAssetResolver.shared.prefetchAll(category: "gallery")
        }
    }

    @ViewBuilder
    private var sceneView: some View {
        ZStack {
            // Mounted for the demo's whole lifetime, and never re-keyed with
            // `.id(_:)`. Both a conditional mount and an `.id(_:)` change throw
            // the `RealityView` away and build a new one, and a re-created
            // `RealityView` on iOS 26 Simulator intermittently renders nothing
            // at all — no model, no skybox — permanently (#3008). This gallery
            // was measured going black that way on a cold launch.
            // `.contentID(_:)` swaps the model without touching the renderer;
            // the previous entity is removed by the library, so nothing is
            // overlaid. The key is optional so it also changes when the model
            // lands, not only when the chip changes.
            SceneView { root in
                guard let loadedNode else { return }
                loadedNode.entity.position = .init(x: 0, y: 0, z: -2)
                root.addChild(loadedNode.entity)
            }
            .cameraControls(.orbit)
            .autoRotate(speed: 0.25)
            // Curated Sketchfab models are authored as PBR: their metallic and
            // rough surfaces are defined by what they reflect. With no IBL they
            // fall back to flat shading and the gallery undersells every model
            // it exists to show off. Same preset as ModelViewerDemo (#2114).
            .environment(.studio)
            .contentID(loadedNode == nil ? nil : selectedSlug?.uid)
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
                        Text("Streaming model…")
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.7))
                    }
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
                // CC-BY 4.0 attribution lives inline so it's always visible
                // without a tap. The Credits sheet (Stage 3) carries the full
                // attribution + Sketchfab page link.
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
