// @sceneId     placement-reticle-preview
// @title       AR Placement Reticle Preview
// @subtitle    Non-AR preview of AR placement — reticle (searching/ready, ring/disc) and a placed model with a contact shadow
// @category    ar
// @available   true
// @icon        scope
// @iosOnly     true
// @status      working
import SwiftUI
import RealityKit
import SceneViewSwift

/// **AR Placement Reticle Preview** — a *non-AR* `SceneView` (RealityKit,
/// no `ARSession`, no ARKit, no camera permission) that renders the
/// production placement visuals — the searching/ready reticle (ring or
/// legacy disc) and the placed-model contact shadow — on a static synthetic
/// floor, so the whole "aim → ready → placed" look can be judged on the
/// simulator with no AR session at all.
///
/// Ports Android's `PlacementReticlePreviewDemo`
/// (`samples/android-demo/.../demos/PlacementReticlePreviewDemo.kt`, #2841)
/// — mirrors its two modes (reticle / placed model), its three toggles, and
/// its bottom coaching text.
///
/// ### Why the demo view lives in this file, not `Demos/`
/// Every other iOS demo splits its `Scenes/XxxScene.swift` registry stub
/// from a `Views/Demos/XxxDemo.swift` view. This port keeps the full
/// `PlacementReticlePreviewDemo` view in *this* file instead: the
/// concurrent iOS-parity porting batch (#2740) scopes each port agent to
/// touching exactly one Scene file so parallel PRs never conflict on
/// `Views/Demos/`. The collator only reads this file's header directives
/// and does not care where the `destination` view type is declared, so
/// this is mechanically equivalent — `PlacementReticlePreviewDemo` can be
/// hoisted into its own `Demos/` file later with a pure file move.
///
/// ### Known iOS/RealityKit deltas from the Android original
/// - **Camera framing**: Android hand-tunes a fixed `orbitHomePosition` /
///   `targetPosition` and disables auto-centering. `SceneViewSwift.SceneView`
///   has no public API to set an initial camera position, so this port
///   relies on the library's own fit-to-bounds auto-centering
///   (`autoCenterContent`, default `true`) instead — same "diorama fills the
///   frame" outcome, different mechanism.
/// - **Contact shadow**: Android's floor gets a Filament directional-light
///   hard shadow. iOS uses RealityKit's `GroundingShadowComponent` (via
///   `ModelEntity.withShadow()`) — the same soft, approximated grounding
///   shadow idiom `ARSceneView` already uses for real AR placement —
///   visually analogous, not pixel-identical.
/// - **Studio HDR**: Android loads `environments/studio_2k.hdr`; iOS uses
///   the bundled `.studio` preset (`studio.hdr`) — same "studio" intent,
///   different specific asset.
/// - **Placed-mode floor exposure**: Android's exact light-grey floor hex
///   (`0xFFC4C8CE`) clips to a flat white under RealityKit's default
///   exposure on a plane this size (verified on-device; halving both the
///   key light and the IBL intensity barely moved it, so this is a
///   rendering-pipeline characteristic, not a light-value tuning problem).
///   The iOS floor uses a darker grey instead so the contact shadow still
///   reads — a library-level exposure fix is out of scope for this port.
enum PlacementReticlePreviewScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(PlacementReticlePreviewDemo()) }
}

// MARK: - Demo

struct PlacementReticlePreviewDemo: View {
    @State private var placed = false
    @State private var ready = true
    @State private var ring = true
    @State private var sceneKey = UUID()
    @State private var helmetNode: ModelNode?
    @State private var helmetLoadFailed = false

    var body: some View {
        ZStack {
            configuredSceneView
                .id(sceneKey)
                .background(placed ? Color.black : Self.reticleBackdrop)
                .ignoresSafeArea()

            if placed, helmetNode == nil {
                VStack(spacing: 8) {
                    ProgressView()
                    Text(helmetLoadFailed ? "Model unavailable" : "Loading model…")
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.7))
                }
            }

            VStack {
                Spacer()
                coachingPill
                    // Cleared well above `.demoSettingsSheet`'s bottom-trailing
                    // gear FAB + "Settings" peek chip (~16-70pt band) so the
                    // longest coaching string ("Surface found — tap to
                    // place") never runs under them — verified on-device,
                    // see PR description.
                    .padding(.bottom, 90)
            }
        }
        .demoSettingsSheet {
            settingsContent
        }
        .onChange(of: placed) { _, _ in sceneKey = UUID() }
        .onChange(of: ready) { _, _ in sceneKey = UUID() }
        .onChange(of: ring) { _, _ in sceneKey = UUID() }
        .task {
            await loadHelmetIfNeeded()
        }
    }

    // MARK: - Scene setup

    /// The `SceneView` configured for the current mode. Built as a plain
    /// (non-`@ViewBuilder`) computed property since every `SceneView`
    /// modifier returns the concrete `SceneView` type, so the conditional
    /// `.environment(.studio)` (placed mode only — reticle mode wants no
    /// visible skybox, see `buildReticleScene`) can be applied with a
    /// simple `if`, no `ViewBuilder`/`Group` gymnastics required.
    private var configuredSceneView: SceneView {
        let base = SceneView { root in
            buildScene(root: root)
        }
        .cameraControls(.orbit)
        .renderQuality(placed ? .default : .performance)
        return placed ? base.environment(.studio) : base
    }

    @MainActor
    private func buildScene(root: Entity) {
        if placed {
            buildPlacedScene(root: root)
        } else {
            buildReticleScene(root: root)
        }
    }

    /// The placed-model mode: a lit floor plane plus the same compact model
    /// `ARPlacementDemo` drops on a real detected plane, tagged with a
    /// `GroundingShadowComponent` contact shadow. Mirrors Android's
    /// `groundPlane` + `khronos_damaged_helmet.glb` (scaleToUnits 0.3,
    /// resting on the floor at half its height).
    @MainActor
    private func buildPlacedScene(root: Entity) {
        let floor = GeometryNode.plane(
            width: Self.placedFloorSize,
            depth: Self.placedFloorSize,
            color: Self.placedFloorColor
        )
        root.addChild(floor.entity)

        if let helmetNode {
            root.addChild(helmetNode.entity)
        }
    }

    /// The reticle mode: a dark unlit synthetic floor stands in for a
    /// detected AR surface, under the production `PlacementReticleStyle`
    /// geometry (ring + centre dot, or the legacy disc) recoloured for the
    /// current `ReticlePhase`. Mirrors Android's `PlacementReticleVisual`
    /// (`arsceneview/.../ar/PlacementReticleVisual.kt`) constants exactly —
    /// same radii, same lift, same searching/ready alpha.
    @MainActor
    private func buildReticleScene(root: Entity) {
        let floor = GeometryNode.cylinder(
            radius: Self.reticleFloorRadius,
            height: Self.reticleFloorHeight,
            color: Self.reticleFloorColor,
            unlit: true
        )
        root.addChild(floor.entity)

        let alpha: Float = ready ? Self.readyAlpha : Self.searchingAlpha

        if ring {
            let torus = GeometryNode.torus(
                majorRadius: Self.ringMajorRadius,
                minorRadius: Self.ringMinorRadius,
                majorSegments: 64,
                minorSegments: 12,
                material: .custom(Self.reticleMaterial(alpha: alpha))
            )
            torus.entity.position.y = Self.reticleLift
            root.addChild(torus.entity)

            // Centre dot only once a surface is acquired — reinforces the
            // "ready" signal, matching Android's conditional CylinderNode.
            if ready {
                let dot = GeometryNode.cylinder(
                    radius: Self.ringDotRadius,
                    height: Self.reticleHeight,
                    color: Self.reticleTint,
                    unlit: true
                )
                dot.entity.model?.materials = [Self.reticleMaterial(alpha: alpha)]
                dot.entity.position.y = Self.reticleLift
                root.addChild(dot.entity)
            }
        } else {
            // Legacy disc style — flush with the floor (no lift), matching
            // Android's DISC branch, which passes no explicit `position`.
            let disc = GeometryNode.cylinder(
                radius: Self.ringMajorRadius,
                height: Self.reticleHeight,
                color: Self.reticleTint,
                unlit: true
            )
            disc.entity.model?.materials = [Self.reticleMaterial(alpha: alpha)]
            root.addChild(disc.entity)
        }
    }

    /// Loads the bundled placed-mode model once and caches it — the
    /// `placed` toggle only needs to reference it afterwards, no reload per
    /// toggle. Bumps `sceneKey` on completion so a scene built before the
    /// load finished (user flips to "placed" before the async load lands)
    /// picks the model up on the next rebuild — same idiom as
    /// `GestureEditingDemo`'s `.id("gesture-\(loadedModel != nil)")`.
    @MainActor
    private func loadHelmetIfNeeded() async {
        guard helmetNode == nil, !helmetLoadFailed else { return }
        do {
            let node = try await ModelNode.load("khronos_damaged_helmet")
            _ = node.scaleToUnits(Self.helmetScaleUnits)
            node.entity.position = SIMD3(x: 0, y: Self.helmetRestingHeight, z: 0)
            node.entity.withShadow()
            helmetNode = node
            sceneKey = UUID()
        } catch {
            helmetLoadFailed = true
        }
    }

    // MARK: - Coaching text

    /// The same coaching copy `ARPlacementDemo`'s real flow shows, mirrored
    /// here (as static illustrative text, same as Android's own preview —
    /// neither platform wires actual tap/drag/pinch gestures in this
    /// preview) so the full "point → ready → placed" story reads in one
    /// screen. Mirrors Android's `coaching` `when` block verbatim.
    private var coachingText: String {
        if placed {
            return "Placed — drag to move, pinch to rotate & scale"
        } else if ready {
            return "Surface found — tap to place"
        } else {
            return "Move your phone to find a surface"
        }
    }

    private var coachingPill: some View {
        Text(coachingText)
            .font(.caption.weight(.medium))
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(.ultraThinMaterial, in: Capsule())
    }

    // MARK: - Settings sheet

    @ViewBuilder
    private var settingsContent: some View {
        Toggle("Show placed model (contact shadow)", isOn: $placed)
            .font(.subheadline)

        if !placed {
            Toggle("Ready phase (surface acquired)", isOn: $ready)
                .font(.subheadline)
            Toggle("Ring style (off = legacy disc)", isOn: $ring)
                .font(.subheadline)
        }
    }

    // MARK: - Constants (mirrors PlacementReticlePreviewDemo.kt / PlacementReticleVisual.kt)

    /// Reticle tint — DESIGN.md primary cyan, identical hex to the one
    /// `ARSceneView`'s real AR placement reticle already uses
    /// (`0x44E7FF`, Android's `RETICLE_TINT`).
    private static let reticleTint = UIColor(red: 0x44 / 255.0, green: 0xE7 / 255.0, blue: 1.0, alpha: 1.0)
    /// Android `RETICLE_SEARCHING_ALPHA`.
    private static let searchingAlpha: Float = 0.35
    /// Android `RETICLE_READY_ALPHA`.
    private static let readyAlpha: Float = 0.95
    /// Android `RING_MAJOR_RADIUS`.
    private static let ringMajorRadius: Float = 0.06
    /// Android `RING_MINOR_RADIUS`.
    private static let ringMinorRadius: Float = 0.005
    /// Android `RING_DOT_RADIUS`.
    private static let ringDotRadius: Float = 0.013
    /// Android `RETICLE_LIFT`.
    private static let reticleLift: Float = 0.004
    /// Android `RETICLE_HEIGHT`.
    private static let reticleHeight: Float = 0.005
    /// Android demo's synthetic-floor `CylinderNode` radius (reticle mode).
    private static let reticleFloorRadius: Float = 0.6
    /// Android demo's synthetic-floor `CylinderNode` height (reticle mode).
    private static let reticleFloorHeight: Float = 0.002
    /// Android demo's `groundDisc` color (`0xFF2A2E35`).
    private static let reticleFloorColor = UIColor(red: 0x2A / 255.0, green: 0x2E / 255.0, blue: 0x35 / 255.0, alpha: 1.0)
    /// Android's `groundPlane` (`0xFFC4C8CE`) reads as a clean light grey
    /// under Filament's default tonemapping, but verified on-device (see PR
    /// description), that exact value clips to a flat white under
    /// RealityKit/the simulator's default exposure on a plane this large,
    /// which also washes out the very contact shadow this mode exists to
    /// show. Darkened here — a mid grey still reads as "light neutral
    /// floor" while staying inside RealityKit's headroom; a library/
    /// RenderQuality-level exposure fix is out of scope for this port.
    private static let placedFloorColor = UIColor(red: 0x50 / 255.0, green: 0x54 / 255.0, blue: 0x58 / 255.0, alpha: 1.0)
    /// Android demo's `PlaneNode(size = Size(x = 1.4, z = 1.4))`.
    private static let placedFloorSize: Float = 1.4
    /// Android demo's `ModelNode(scaleToUnits = 0.3f)`.
    private static let helmetScaleUnits: Float = 0.3
    /// Android demo's `ModelNode(position = Position(y = 0.15f))` — half the
    /// scaled model's height, so it rests on the floor instead of
    /// intersecting it.
    private static let helmetRestingHeight: Float = 0.15
    /// Android demo's reticle-mode `darkSkybox` color
    /// (`Skybox.Builder().color(0.10f, 0.11f, 0.13f, 1.0f)`), applied here
    /// as a plain SwiftUI background since reticle mode intentionally sets
    /// no `SceneEnvironment` (its materials are all unlit, so an HDR would
    /// only cost a load for zero lighting benefit, and Android's own dark
    /// solid color — standing in for the camera feed — is exactly what a
    /// visible HDR sky would defeat).
    private static let reticleBackdrop = Color(red: 0.10, green: 0.11, blue: 0.13)

    /// Builds the reticle body material. `UnlitMaterial(color:)`'s base-color
    /// alpha is known to be dropped on some RealityKit material paths — the
    /// same gotcha already documented and worked around in
    /// `ARSceneView.swift`'s `makeReticleAnchor()` — so the searching (dim)
    /// vs ready (bright) phases ride `.blending` instead, not the tint alpha.
    private static func reticleMaterial(alpha: Float) -> any RealityKit.Material {
        var material = UnlitMaterial(color: reticleTint)
        material.blending = .transparent(opacity: .init(floatLiteral: alpha))
        return material
    }
}
