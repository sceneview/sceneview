import SwiftUI
import RealityKit
import Metal
import SceneViewSwift

/// Composes a "Park" scene from the 4 models in ``SampleAssets``' `park`
/// category: a pair of oaks at the back, a bench in front of them, a street
/// lamp and a fern, on a round lawn in a garden (#4103).
///
/// Mirrors the Android Multi-Model section (`samples/android-demo/.../ModelViewerDemo.kt`):
/// same four `park` slugs, same layout and sizes (`ParkFraming.kt`), same garden
/// HDR, same visibility chips and "Spin scene" toggle. The park is centred
/// around `z = -1.5 m`; the scene's auto-framing does the rest.
///
/// The layout is positional and fixed; WHICH model stands in each slot is the
/// registry's call. Nothing here names a species: each chip reads its label off
/// the resolved ``SketchfabSlug/displayName`` — the same curated-English source
/// the Gallery chips use — falling back to a positional "Model N" only while a
/// slot has no slug. The labels used to be hardcoded "Tree" / "Bench" / "Dog" /
/// "Bird" from a composition the registry stopped holding: four oaks named after
/// a bench and a dog, with no bench and no dog on screen (#2933).
///
/// ### Streaming pipeline (Stage 2, issue #1152)
///
/// Every slug resolves through ``SketchfabAssetResolver``. Empty API key
/// (App Store builds) → the resolver returns the registered bundled USDZ, so
/// the demo still renders without a network, honouring the hard rule "no
/// network required to render something useful" from `feedback_demo_quality`.
///
/// > Important: the chip names the CATALOGUE ENTRY, not the geometry. On a
/// > keyless build a slot still reads "Oak Trees" over its bundled stand-in.
/// > Both platforms now say so on screen — Android through the scaffold's
/// > asset-source chip, iOS through ``AssetSourcePill`` (#2960), which reads
/// > "Offline model" as soon as any one slot resolves out of `fallback/`.
///
/// ⚠️ That swap is also why this demo is deliberately absent from the App Store
/// screenshot set (#2896). The bundled stand-ins are intentionally distinct
/// silhouettes rather than four copies of one hero (#2355), so a keyless build
/// composes a tree island, a piano, a lantern and a shiba — a scene no keyless
/// user sees as the documented park. The substitution itself is by design;
/// what it is not is a listing screenshot.
struct MultiModelDemo: View {
    /// One flag per SLOT, not per species — index `i` pairs with `Self.slots[i]`.
    @State private var visible: [Bool] = Array(repeating: true, count: MultiModelDemo.slots.count)
    @State private var spinScene: Bool = true

    /// `-qa_mode 1` / `?qa_mode=1` — freezes the orbit sweep so a capture lands
    /// on the pose below every time. Without it the store capture shot an
    /// arbitrary azimuth, which also decided which part of the `.studio` HDRI
    /// sat behind the models — one run got the plants, the next a blown-out
    /// softbox filling the top third of the frame (#2896).
    @AppStorage(DeepLinkRouter.qaModeDefaultsKey) private var qaMode: Bool = false

    /// Loaded entities keyed by slug uid. Adding / removing nodes from the
    /// scene happens reactively via the imperative `update` pass below — we
    /// re-evaluate visibility every recomposition.
    @State private var loadedEntities: [String: Entity] = [:]
    @State private var loadError: String?
    /// What the resolver handed back per slot uid — the pill's only honest
    /// input (#2960). A slot still resolving simply has no entry yet.
    @State private var resolvedURLs: [String: URL] = [:]
    /// Anchor under which every model lives. Stored so we can attach / detach
    /// individual entities without rebuilding the entire scene.
    @State private var sceneAnchor: AnchorEntity?
    /// Bumped when a slot lands late AND grows the park, while the camera is still
    /// the auto-framed one; fed to `.recenterCamera(_:)` so the camera re-fits the
    /// whole park (#4103). See `noteLanding(previousExtents:)`.
    @State private var reframeToken = 0
    /// When the last slot landed, to tell a late arrival from one inside the
    /// auto-framing's settle window.
    @State private var lastLanding: Date?
    /// Set by the first orbit drag or pinch on the viewport. From then on the
    /// camera is the user's: `.recenterCamera(_:)` resets the orbit angles and
    /// the zoom, so a late landing must never fire it again.
    @State private var userMovedCamera = false

    private struct ParkSlot {
        let slug: SketchfabSlug?
        let position: SIMD3<Float>
        let scale: Float
        /// Turn on the vertical axis, in radians, so a model authored side-on
        /// (the bench) faces the camera.
        let yaw: Float
        /// Zero-based place in the formation, used for the positional fallback label.
        let index: Int

        /// Chip label, and the name used when logging a failed slot.
        ///
        /// Read off the resolved slug so it always names the registry entry the
        /// slot actually loaded. The positional fallback only fires when the
        /// registry has no slug for this slot — a chip with no model behind it
        /// still needs a stable, non-lying handle.
        var displayName: String { slug?.displayName ?? "Model \(index + 1)" }
    }

    /// The four `park` slots, back row first. Order matches the visibility chips.
    ///
    /// Each slot carries the uid it loads, so the layout, the loader and the chip
    /// label are all indexed by one thing — a slot can never end up labelled with
    /// another slot's model. Slugs are resolved by uid (stable across registry
    /// re-orderings), falling back to category-by-index if a uid is somehow missing.
    private static let slots: [ParkSlot] = {
        let park = SampleAssets.byCategory["park"] ?? []
        // Layout only — where a model stands and how big it is drawn. What stands
        // there is whatever `uid` resolves to in the registry.
        //
        // The same slots as Android's `PARK_SLOTS` (#4103), moved back to
        // `z = -1.5`: one corner of a park at one consistent scale, each size the
        // model's LARGEST axis (height for the oaks and the lamp, width for the
        // bench and the fern). `position.y` is the ground: every model is
        // bottom-aligned onto the lawn in `loadSlot`.
        let layout: [(uid: String, position: SIMD3<Float>, scale: Float, yaw: Float)] = [
            ("d841c3bcc5324daebee50f45619e05fc", .init(x: 0.0, y: 0.0, z: -1.95), 2.00, 0),
            ("378cd6e6f505493aa8e22f68db1cabec", .init(x: -0.05, y: 0.0, z: -1.15), 0.70, .pi / 2),
            ("6881aa1e84b047d79860fa9297e05e22", .init(x: 0.55, y: 0.0, z: -1.25), 1.10, 0),
            ("42cb7fad10ba44ecbc9ae9cf5fdd63b6", .init(x: -0.62, y: 0.0, z: -1.08), 0.45, 0),
        ]
        return layout.enumerated().map { index, entry in
            ParkSlot(
                slug: SampleAssets.byUID[entry.uid] ?? (park.indices.contains(index) ? park[index] : nil),
                position: entry.position,
                scale: entry.scale,
                yaw: entry.yaw,
                index: index
            )
        }
    }()

    private let hasSketchfabKey: Bool = SketchfabConfig.apiKey != nil

    /// A WHOLE-SCENE verdict over the four park slots: one stand-in makes the
    /// pill read "Offline model" for the scene. This demo's header already
    /// admits in prose that a keyless slot reads "Oak Trees" over a stand-in
    /// — the pill is that admission made visible on screen (#2960).
    private var assetSource: AssetSourceState {
        let slugs = Self.slots.compactMap(\.slug)
        return AssetSourceProbe.ofAll(
            resolvedURLs: slugs.map { resolvedURLs[$0.uid] },
            hasAPIKey: hasSketchfabKey,
            loaded: slugs.allSatisfy { loadedEntities[$0.uid] != nil }
        )
    }

    var body: some View {
        sceneContent
            .demoChrome(status: {
                AssetSourceStatus(
                    state: assetSource,
                    // Three of the four park stand-ins are not trees (#2960);
                    // the whole-scene verdict says so.
                    isPlaceholder: Self.slots.compactMap(\.slug).contains { $0.fallbackRole == .placeholder }
                )
            }) { controlsSheet }
            .task {
                _ = await SketchfabAssetResolver.shared.prefetchAll(category: "park")
            }
            .task { await loadAllSlots() }
            .onChange(of: visible) { _, _ in syncVisibility() }
    }

    @ViewBuilder
    private var sceneContent: some View {
        ZStack {
            SceneView { root in
                // Stash a single sub-anchor so we can spin the whole formation
                // without re-laying out the SceneView every frame.
                // The lawn is NOT added here: `syncVisibility` attaches it with the
                // first model that lands (#4103). Drawn from the first frame, it
                // was all the auto-framing saw while the models streamed; its
                // bounds held still for the 2.5 s settle window, the fit latched
                // on a 2.2 m disc, and the 2 m oaks then grew out of the top of
                // the frame.
                let anchor = AnchorEntity()
                root.addChild(anchor)
                Task { @MainActor in
                    self.sceneAnchor = anchor
                    self.syncVisibility()
                }
            }
            .cameraControls(.orbit)
            // Value-driven, with NO `.id(...)` re-key (#2935). `autoRotate`'s
            // speed is reactive since v4.31.0, so flipping "Spin scene" starts
            // and stops the turntable under the SAME `RealityView`. The demo
            // used to re-key on `.id("multi-model-spin-\(spinScene)-\(qaMode)")`,
            // which is the #3008 teardown anti-pattern the SDK docs forbid: a
            // rebuilt `RealityView` on the iOS 26 Simulator intermittently
            // renders nothing at all — no model, no skybox — and never
            // recovers, so turning the toggle off blanked the viewport.
            .autoRotate(speed: (spinScene && !qaMode) ? 0.2 : 0.0)
            // A garden, drawn as the skybox and lighting the models, so the park
            // reads as outdoors because an outdoor sky lights it (#4103) — the
            // same Poly Haven "Chinese Garden" as Android. It used to be
            // `.studio`, a plant-filled living room with a white softbox.
            .environment(Self.gardenEnvironment)
            // The fit inscribes the bounds' space diagonal in a sphere, and the
            // park is a 2.2 m disc under 2 m of oak: that sphere (radius ~1.9 m)
            // is far wider than anything the camera sees side-on, which is never
            // more than the lawn's 2.2 m at any azimuth. 0.95 left the park in a
            // third of a portrait frame (#4103); at 0.75 the widest azimuth
            // still clears the frame edge while the scene spins (#2896).
            .framingMargin(0.75)
            // Shallower than the 30° default so the formation is seen from
            // near its own eye level — a 30° top-down pitch spent the bottom
            // half of a portrait frame on empty ground (#2896).
            .cameraOrbit(azimuth: 0, elevation: .pi / 10)
            // A model that lands after the fit latched re-arms it (#4103).
            .recenterCamera(reframeToken)
            // Observes, never drives: the SDK's own orbit drag and pinch still
            // move the camera; these only record that the user took it over.
            .simultaneousGesture(DragGesture(minimumDistance: 10).onChanged { _ in
                userMovedCamera = true
            })
            .simultaneousGesture(MagnifyGesture().onChanged { _ in
                userMovedCamera = true
            })
            .ignoresSafeArea()

            if loadedEntities.isEmpty && loadError == nil {
                VStack(spacing: 12) {
                    ProgressView().tint(.white)
                    Text("Loading park scene…")
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.7))
                }
            }
            if let loadError {
                Text(loadError)
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.7))
            }
        }
        .background(Color.black)
    }

    @ViewBuilder
    private var controlsSheet: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Visibility")
                .font(.subheadline.weight(.semibold))
            // Horizontally scrolling for the same reason the Gallery chips are:
            // catalogue names run long ("Street Lamp", "Simple Park Bench") and four of them do
            // not fit an iPhone's sheet width without truncating.
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Array(Self.slots.enumerated()), id: \.offset) { index, slot in
                        visibilityChip(slot.displayName, isOn: $visible[index])
                    }
                }
            }

            Toggle(isOn: $spinScene) {
                Text("Spin scene")
                    .font(.subheadline)
            }
            .tint(.blue)

            Text("Tap any chip to toggle visibility. Spin uses the orbit camera's auto-rotate.")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
    }

    private func visibilityChip(_ label: String, isOn: Binding<Bool>) -> some View {
        Button {
            isOn.wrappedValue.toggle()
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
                        .fill(isOn.wrappedValue ? AnyShapeStyle(.blue) : AnyShapeStyle(.gray.opacity(0.15)))
                )
                .foregroundStyle(isOn.wrappedValue ? .white : .primary)
        }
        .buttonStyle(.plain)
    }

    // MARK: - Loading + visibility sync

    /// Load every park slot. Two deliberate properties make the deep-link
    /// entry point (`sceneview://demo/multi-model`) reliable — issue #1056:
    ///
    ///  1. **Concurrent, not sequential** — each slot loads in its own child
    ///     task. The previous sequential loop loaded the ~15 MB hero asset
    ///     first; on the iOS Simulator RealityKit's `Entity(contentsOf:)`
    ///     parse of that heavy, texture-dense USDZ stalls for a very long
    ///     time, and a sequential loop left the three lighter slots blocked
    ///     behind it — so the demo showed an eternal "Loading park
    ///     scene…" scrim. Loading concurrently means a slow slot only
    ///     delays itself.
    ///  2. **Progressive reveal** — each entity is stored into
    ///     `loadedEntities` the instant it finishes (each slot also re-runs
    ///     `syncVisibility()`), so the loading scrim is dismissed by the
    ///     *first* model that lands rather than waiting for all four.
    ///
    /// A slot that fails outright is dropped; the rest of the park still
    /// renders.
    @MainActor
    private func loadAllSlots() async {
        // One child `Task` per slot. All tasks inherit `@MainActor`
        // isolation, so the heavy `Entity(contentsOf:)` parses still
        // interleave at their `await` suspension points — a slow slot only
        // delays itself, not its siblings.
        let tasks: [Task<Void, Never>] = Self.slots.compactMap { slot in
            guard slot.slug != nil else { return nil }
            return Task { @MainActor in await self.loadSlot(slot) }
        }
        for task in tasks {
            await task.value
        }
        // If every slot failed (e.g. offline + missing bundle fallback),
        // replace the spinner with an honest message instead of an eternal
        // "Loading park scene…" scrim.
        if loadedEntities.isEmpty && loadError == nil {
            loadError = "Couldn't load the park scene — check your connection and reopen the demo."
        }
    }

    /// Resolve + load a single park slot, then store its `Entity` and re-sync
    /// visibility so the scene reveals models progressively. A failure is
    /// logged and skipped so one bad slot never blocks the rest. (#1056)
    @MainActor
    private func loadSlot(_ slot: ParkSlot) async {
        guard let slug = slot.slug else { return }
        do {
            let url = try await SketchfabAssetResolver.shared.resolve(slug)
            resolvedURLs[slug.uid] = url
            let node = try await ModelNode.load(contentsOf: url)
            _ = node.scaleToUnits(slot.scale)
            // Bottom-aligned, like Android's `centerOrigin = Position(0, -1, 0)`:
            // the model's bounding-box floor on its origin, so every slot stands
            // on the lawn. `centerOrigin()` centred it instead, and the position
            // assignment that followed replaced even that — each model hung
            // from its authored pivot at whatever height that put it.
            _ = node.centerOrigin(normalized: SIMD3<Float>(0, -1, 0))
            node.entity.components.set(GroundingShadowComponent(castsShadow: true))
            // Only a Sketchfab export carries the miswired opacity (see
            // `cutOutTexturedOpacity`); the bundled stand-ins keep their
            // authored blending.
            if !SketchfabAssetResolver.isBundledFallback(url) {
                Self.cutOutTexturedOpacity(node.entity)
            }
            if slug.hasBakedAnimation && node.animationCount > 0 {
                node.playAllAnimations()
            }
            // A holder carries the slot's place and turn, so the grounding offset
            // above stays on the model and the turn happens about the model's
            // footprint rather than its authored pivot.
            let holder = Entity()
            holder.position = slot.position
            holder.orientation = simd_quatf(angle: slot.yaw, axis: SIMD3<Float>(0, 1, 0))
            holder.addChild(node.entity)
            let previousExtents = parkExtents()
            loadedEntities[slug.uid] = holder
            noteLanding(previousExtents: previousExtents)
            syncVisibility()
        } catch {
            // Per-slot failure — log and move on so the rest of the park
            // still renders. Matches Android's per-slug fallback path.
            print("[MultiModelDemo] Skipped \(slot.displayName): \(error)")
        }
    }

    /// The SDK's auto-framing latches once the scene's bounds hold still for
    /// 2.5 s (`framingStableHoldSeconds`) and does not re-fit on its own after
    /// that. Slots stream in over seconds to a minute, so a slot landing after
    /// such a gap could stand outside a frame fitted to the ones before it: on
    /// a first run, the oaks above a frame fitted to the fern and the bench.
    ///
    /// `.recenterCamera(_:)` is not free, though: it also resets the orbit
    /// angles and the zoom. So a late landing re-arms the fit only when all
    /// three hold:
    ///  - it lands more than 2 s after the previous one (closer landings are
    ///    still inside the settle window and need nothing);
    ///  - it grows the park by more than `reframeGrowth` on some axis — the
    ///    oaks over a fern-and-bench park do, a fern under the oaks does not;
    ///  - the user has not orbited or zoomed yet. Once they have, the camera is
    ///    theirs and a landing never snaps it back.
    @MainActor
    private func noteLanding(previousExtents: SIMD3<Float>?) {
        let now = Date()
        defer { lastLanding = now }
        guard let lastLanding, now.timeIntervalSince(lastLanding) > 2.0 else { return }
        guard !userMovedCamera else { return }
        guard let previousExtents, let extents = parkExtents() else { return }
        let grows = (0..<3).contains { axis in
            extents[axis] > previousExtents[axis] * Self.reframeGrowth
        }
        if grows { reframeToken += 1 }
    }

    /// How much one axis of the park's bounds must grow for a late landing to
    /// re-fit the camera.
    private static let reframeGrowth: Float = 1.25

    /// Extents of what the auto-framing fits — the lawn plus every visible
    /// landed slot, in the anchor's space — or `nil` before anything landed.
    /// Computed from the slot holders rather than read off the anchor, so it
    /// holds even before `syncVisibility` has attached them.
    @MainActor
    private func parkExtents() -> SIMD3<Float>? {
        var lower = SIMD3<Float>(-1.1, -0.05, -2.6)   // the lawn, see `makeLawn`
        var upper = SIMD3<Float>(1.1, 0.0, -0.4)
        var any = false
        for slot in Self.slots {
            guard let slug = slot.slug, let holder = loadedEntities[slug.uid],
                  !visible.indices.contains(slot.index) || visible[slot.index] else { continue }
            let local = holder.visualBounds(relativeTo: holder)
            guard !local.isEmpty else { continue }
            let matrix = holder.transform.matrix
            for corner in 0..<8 {
                let point = SIMD3<Float>(
                    corner & 1 == 0 ? local.min.x : local.max.x,
                    corner & 2 == 0 ? local.min.y : local.max.y,
                    corner & 4 == 0 ? local.min.z : local.max.z
                )
                let world = matrix * SIMD4<Float>(point, 1)
                lower = simd_min(lower, SIMD3<Float>(world.x, world.y, world.z))
                upper = simd_max(upper, SIMD3<Float>(world.x, world.y, world.z))
            }
            any = true
        }
        return any ? upper - lower : nil
    }

    /// Sketchfab's USDZ exports wire leaf opacity to the base-colour texture's
    /// alpha (`tex_base.outputs:a`) and drop the glTF `MASK` mode. RealityKit
    /// samples that texture's red channel instead, about 0.4 over the whole
    /// card, so every leaf card showed as a pale translucent square. Reading
    /// the alpha channel and thresholding it gives back the cut-out the glTF
    /// authored. Uniform opacity (a lamp's glass) is left blended. Streamed
    /// files only: the bundled stand-ins were converted separately and their
    /// opacity is authored as meant.
    private static func cutOutTexturedOpacity(_ entity: Entity) {
        if var model = entity.components[ModelComponent.self] {
            var changed = false
            model.materials = model.materials.map { material in
                guard var pbr = material as? PhysicallyBasedMaterial,
                      case .transparent(let opacity) = pbr.blending,
                      var texture = opacity.texture else { return material }
                texture.swizzle = MTLTextureSwizzleChannels(red: .alpha, green: .alpha, blue: .alpha, alpha: .alpha)
                pbr.blending = .transparent(opacity: .init(scale: opacity.scale, texture: texture))
                pbr.opacityThreshold = 0.5
                changed = true
                return pbr
            }
            if changed { entity.components.set(model) }
        }
        for child in entity.children { cutOutTexturedOpacity(child) }
    }

    /// Poly Haven's "Chinese Garden" (CC0), bundled as `chinese_garden.hdr`.
    private static let gardenEnvironment = SceneEnvironment.custom(
        name: "Chinese Garden",
        hdrFile: "chinese_garden.hdr"
    )

    /// How `syncVisibility` finds the lawn it attached.
    private static let lawnName = "park-lawn"

    /// The lawn every slot stands on: a thin disc whose top face is `y = 0`,
    /// centred under the park, the same 1.1 m radius as Android's
    /// `PARK_LAWN_RADIUS`. Kept tight because it counts here: the scene's
    /// auto-framing fits everything under the content root, and a wider disc
    /// would shrink every model to make room for grass.
    private static func makeLawn() -> Entity {
        let thickness: Float = 0.05
        let lawn = ModelEntity(
            mesh: .generateCylinder(height: thickness, radius: 1.1),
            materials: [SimpleMaterial(color: lawnColor, roughness: 1.0, isMetallic: false)]
        )
        lawn.name = lawnName
        lawn.position = SIMD3<Float>(0, -thickness / 2, -1.5)
        lawn.components.set(GroundingShadowComponent(castsShadow: false, receivesShadow: true))
        return lawn
    }

    /// Mown grass. The base colour that RENDERS as a natural lawn green (about
    /// #536A46 on screen; Android's lawn shows about #587839) (#4103). Not the
    /// same value as Android's `PARK_LAWN_COLOR`: RealityKit lights and
    /// tone-maps the garden HDR much brighter and cooler than Filament, so
    /// Android's #335222 came out here as a pale sage (#7AA566). The two are
    /// matched on screen, not in code. A 3D material, not UI chrome, so it is
    /// not a DESIGN.md token.
    private static let lawnColor = UIColor(red: 0x23 / 255.0, green: 0x3B / 255.0, blue: 0x0F / 255.0, alpha: 1)

    /// Re-attach / detach entities based on the four visibility toggles.
    /// Cheap because RealityKit only does an add / remove on the anchor.
    @MainActor
    private func syncVisibility() {
        guard let anchor = sceneAnchor else { return }
        // The lawn comes with the first model, never before (see `sceneContent`).
        if !loadedEntities.isEmpty, anchor.findEntity(named: Self.lawnName) == nil {
            anchor.addChild(Self.makeLawn())
        }
        for slot in Self.slots {
            guard let slug = slot.slug, let entity = loadedEntities[slug.uid] else { continue }
            // `visible` is sized from `slots` at init and never resized, so this guard is
            // unreachable today. It stays because a Swift out-of-bounds subscript TRAPS:
            // "fail loudly" here would mean crashing a shipped App Store demo, which is a
            // worse outcome than a chip that quietly stops toggling. Android's
            // `instances[index]` does throw instead — the asymmetry is a deliberate call
            // about who pays for a future desync, not an oversight (#2933).
            let show = visible.indices.contains(slot.index) ? visible[slot.index] : true
            let alreadyAdded = entity.parent === anchor
            if show && !alreadyAdded {
                anchor.addChild(entity)
            } else if !show && alreadyAdded {
                anchor.removeChild(entity)
            }
        }
    }
}
