import SwiftUI
import RealityKit
import SceneViewSwift

/// Physics simulation — dynamic bodies (cubes or streamed crash-test meshes)
/// falling onto a static floor.
///
/// Mirrors the Android `PhysicsDemo` (`samples/android-demo/.../PhysicsDemo.kt`):
///   - Default ("Bundled cubes") chip → 5 coloured PBR cubes + 1 bouncing
///     sphere. The deterministic v4.3.x mode useful for offline / store
///     screenshots.
///   - Streamed chips → the four entries in ``SampleAssets``'s `physics`
///     category (Ceramic Vase / Wooden Stool / Wooden Barrel / Clay Amphora).
///     Each "Drop" press spawns a new dynamic body of the picked mesh.
///
/// ### Honest-subset notes vs Android
///
/// - Android's `PhysicsDemo` wraps each falling mesh inside an invisible
///   simulated `SphereNode` from `sceneview-core` so the collider is a
///   bounding-sphere regardless of the visual mesh shape. The iOS demo uses
///   RealityKit's built-in `PhysicsBodyComponent` (the same engine
///   ``PhysicsNode`` wraps), which generates a collision shape from the
///   `ModelEntity.visualBounds`. Net effect is the same — falling, bouncing,
///   resting on a floor — driven by RealityKit instead of `sceneview-core`.
///   See follow-up #1033 (XCFramework lift) for the eventual unification.
/// - The Android version has a "Bodies: X" counter + Reset that re-instantiates
///   the scene. The iOS port keeps the Reset button (already shipped) and adds
///   a `Drop` + `Drop 10` pair so the counter feels useful when streaming.
struct PhysicsDemo: View {
    @State private var sceneKey = UUID()
    @State private var bodyCount: Int = 5
    @State private var selectedSlug: SketchfabSlug?
    @State private var streamedURL: URL?

    private let physicsSlugs: [SketchfabSlug] = SampleAssets.byCategory["physics"] ?? []

    private let hasSketchfabKey: Bool = SketchfabConfig.apiKey != nil

    /// `nil` while the demo drops its own generated cubes — they are not a
    /// substitution for anything, so there is nothing to declare. With a slug
    /// selected the caption reads "Streamed <name> falling", which is exactly
    /// the claim the pill has to keep honest on a keyless build (#2960): all
    /// four physics slugs currently resolve to `fantasy_book.usdz`.
    private var assetSource: AssetSourceState? {
        guard selectedSlug != nil else { return nil }
        return AssetSourceProbe.of(
            resolvedURL: streamedURL,
            hasAPIKey: hasSketchfabKey,
            // "Loaded" here is "the file arrived" — the scene consumes the URL
            // directly, there is no separate parse step to wait for.
            loaded: streamedURL != nil
        )
    }

    var body: some View {
        sceneContent
            .assetSourcePill(assetSource,
                         placeholder: selectedSlug?.fallbackRole == .placeholder)
            .demoSettingsSheet { controlsSheet }
            .task {
                _ = await SketchfabAssetResolver.shared.prefetchAll(category: "physics")
            }
            .task(id: selectedSlug?.uid) {
                await resolveSelectedSlug()
            }
    }

    @ViewBuilder
    private var sceneContent: some View {
        ZStack {
            SceneView { root in
                setupScene(root: root)
            }
            .cameraControls(.orbit)
            .environment(.studio) // parity with android-demo IBL fix (#2114)
            // Reset / Drop rebuild the floor and bodies under the same
            // `RealityView` — removing the content root restarts the physics
            // simulation without discarding the renderer, which a `.id(_:)`
            // re-key did and which intermittently left the viewport black on
            // iOS 26 Simulator (#3008).
            .contentID(sceneKey)
            .ignoresSafeArea()

            VStack {
                Spacer()
                HStack {
                    Text(selectedSlug == nil
                         ? "Dynamic cubes fall under gravity"
                         : "Streamed \(selectedSlug?.displayName ?? "model") falling")
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.6))
                    Spacer()
                    Button {
                        sceneKey = UUID()
                        bodyCount = 5
                        #if os(iOS)
                        SceneViewHaptic.shared.medium()
                        #endif
                    } label: {
                        Label("Reset", systemImage: "arrow.counterclockwise")
                            .font(.caption)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(.ultraThinMaterial)
                            .clipShape(Capsule())
                    }
                    .foregroundStyle(.white)
                    .accessibilityLabel("Reset physics simulation")
                }
                .padding()
            }
        }
        .background(Color.black)
    }

    /// Imperative scene setup — recomposes the floor + bodies whenever
    /// `sceneKey` changes. Mirrors the Android `key(generation) { … }`
    /// recomposition pattern.
    private func setupScene(root: Entity) {
        // Static floor — same darkGray plane as the v4.3.x demo.
        let floor = GeometryNode.plane(width: 3, depth: 3, color: .darkGray)
        floor.entity.position = .init(x: 0, y: -0.5, z: -2)
        root.addChild(floor.entity)
        PhysicsNode.static(floor.entity, restitution: 0.6, friction: 0.8)

        // If a streamed slug is selected and downloaded, drop `bodyCount`
        // instances of that mesh in a tight grid. Otherwise drop the cubes
        // + sphere from the v4.3.x bundled mode.
        if let slug = selectedSlug, let url = streamedURL {
            Task { @MainActor in
                await dropStreamed(slug: slug, url: url, root: root)
            }
        } else {
            dropBundledCubes(root: root)
        }
    }

    @MainActor
    private func dropStreamed(slug: SketchfabSlug, url: URL, root: Entity) async {
        let safeCount = min(bodyCount, 12) // RealityKit physics gets sluggish past ~15 active bodies
        for i in 0..<safeCount {
            do {
                let node = try await ModelNode.load(contentsOf: url)
                // scale to a comfortable 0.18 m tall so several stack on
                // the floor without occluding each other.
                _ = node.scaleToUnits(0.18)
                _ = node.centerOrigin()
                let x = Float(i % 5 - 2) * 0.18
                let y = 0.6 + Float(i / 5) * 0.20
                let z = Float((i / 5) % 3 - 1) * 0.18 - 2
                node.entity.position = .init(x: x, y: y, z: z)
                root.addChild(node.entity)
                PhysicsNode.dynamic(node.entity, mass: 0.4, restitution: 0.55)
            } catch {
                // Per-body failure — skip and keep dropping the remaining
                // bodies so the scene doesn't lock up.
                continue
            }
        }
        _ = slug
    }

    private func dropBundledCubes(root: Entity) {
        let colors: [UIColor] = [.systemBlue, .systemRed, .systemGreen, .systemOrange, .systemPurple]
        for i in 0..<bodyCount {
            let color = colors[i % colors.count]
            let cube = GeometryNode.cube(
                size: 0.1,
                material: .pbr(color: color, metallic: 0.5, roughness: 0.3),
                cornerRadius: 0.005
            )
            let x = Float(i % 5 - 2) * 0.15 + Float.random(in: -0.02...0.02)
            let y = Float(i) * 0.12 + 0.5
            cube.entity.position = .init(x: x, y: y, z: -2)
            root.addChild(cube.entity)
            PhysicsNode.dynamic(cube.entity, mass: 0.5, restitution: 0.4)
        }

        // Bouncing yellow sphere — the v4.3.x icon for the demo.
        let ball = GeometryNode.sphere(
            radius: 0.06,
            material: .pbr(color: .systemYellow, metallic: 0.8, roughness: 0.1)
        )
        ball.entity.position = .init(x: 0.3, y: 1.5, z: -2)
        root.addChild(ball.entity)
        PhysicsNode.dynamic(ball.entity, mass: 0.3, restitution: 0.9)
    }

    // MARK: - Controls

    @ViewBuilder
    private var controlsSheet: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Text("Bodies: \(bodyCount)")
                    .font(.subheadline.weight(.semibold))
                Spacer()
                Button {
                    bodyCount = min(bodyCount + 1, 20)
                    sceneKey = UUID()
                    #if os(iOS)
                    SceneViewHaptic.shared.light()
                    #endif
                } label: {
                    Text("Drop")
                        .font(.caption.weight(.semibold))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(.blue, in: Capsule())
                        .foregroundStyle(.white)
                }
                .buttonStyle(.plain)
                Button {
                    bodyCount = min(bodyCount + 10, 20)
                    sceneKey = UUID()
                    #if os(iOS)
                    SceneViewHaptic.shared.medium()
                    #endif
                } label: {
                    Text("Drop 10")
                        .font(.caption.weight(.semibold))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(.purple, in: Capsule())
                        .foregroundStyle(.white)
                }
                .buttonStyle(.plain)
            }

            Text("Crash-test mesh")
                .font(.subheadline.weight(.semibold))
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    pickerChip(label: "Bundled cubes", isSelected: selectedSlug == nil) {
                        selectedSlug = nil
                        bodyCount = 5
                        sceneKey = UUID()
                    }
                    ForEach(physicsSlugs, id: \.uid) { slug in
                        pickerChip(label: slug.displayName, isSelected: selectedSlug?.uid == slug.uid) {
                            selectedSlug = slug
                            bodyCount = 5
                            sceneKey = UUID()
                        }
                    }
                }
            }

            if let slug = selectedSlug {
                if streamedURL == nil {
                    Text("Streaming \(slug.displayName)…")
                        .font(.caption2)
                        .foregroundStyle(.orange)
                }
                Text("by \(slug.author) · CC-BY 4.0")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            } else {
                Text("Drop count capped at 20 on RealityKit — beyond that the simulation lags.")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func pickerChip(label: String, isSelected: Bool, action: @escaping () -> Void) -> some View {
        Button {
            action()
            #if os(iOS)
            SceneViewHaptic.shared.selection()
            #endif
        } label: {
            Text(label)
                .font(.caption.weight(.semibold))
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(
                    Capsule()
                        .fill(isSelected ? AnyShapeStyle(.blue) : AnyShapeStyle(.gray.opacity(0.18)))
                )
                .foregroundStyle(isSelected ? .white : .primary)
        }
        .buttonStyle(.plain)
    }

    @MainActor
    private func resolveSelectedSlug() async {
        guard let slug = selectedSlug else {
            streamedURL = nil
            return
        }
        streamedURL = nil
        do {
            streamedURL = try await SketchfabAssetResolver.shared.resolve(slug)
            // After resolve, trigger a fresh scene so the dropped bodies use
            // the freshly-downloaded mesh.
            sceneKey = UUID()
        } catch {
            streamedURL = nil
        }
    }
}
