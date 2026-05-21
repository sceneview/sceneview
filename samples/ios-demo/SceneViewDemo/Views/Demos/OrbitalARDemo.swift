#if os(iOS)
import SwiftUI
import RealityKit
import ARKit
import SceneViewSwift

/// Personal-solar-system AR demo — 8 themed models orbit around the user.
///
/// On session start, anchors a world-space `AnchorNode` at the camera's initial
/// position. Loads 8 assets at equirepartie angles (45° apart) on a ~1.5 m radius
/// circle around the origin, each at a different height (between -0.5 m and
/// +0.5 m relative to the user). A `Timer` ticks at 60 Hz and updates per-model
/// orbital angles (planetary speeds, between 0.05 and 0.30 rad/s).
///
/// Models with a baked animation (4 streamed creatures from the `solar`
/// `SampleAssets` category — butterfly, hummingbird, bee, fish — plus the bundled
/// `phoenix_bird`) face the orbit tangent so they "fly the orbit" naturally —
/// their own animation does the wing flap / locomotion. Static models (red_car,
/// nintendo_switch, retro_piano) keep a local Y spin (between 0.5 and 2.0 rad/s)
/// so they don't look frozen.
///
/// The user stays fixed in AR — turning the phone reveals each model as it
/// passes through view.
///
/// ### Streaming pipeline (Stage 2, issue #1152)
///
/// Four of the eight planets are streamed via `SketchfabAssetResolver` from the
/// curated `solar` category of `SampleAssets`. When `SketchfabConfig.apiKey` is
/// missing (App Store / first-launch / no-network) the resolver returns the
/// bundled fallback (`animated_butterfly.usdz`) so the demo always renders eight
/// orbiting models — no broken slots.
struct OrbitalARDemo: View {
    /// Per-model orbital/spin tuning.
    ///
    /// Speeds are deliberately varied so the formation looks like a real solar
    /// system rather than a single rigid ring — slow at 0.05 rad/s (~125 s per
    /// lap), fast at 0.30 rad/s (~21 s per lap). Heights step ±0.5 m so models
    /// pass at different elevations as the user turns.
    ///
    /// `scale` is a hand-tuned absolute scale (NOT `scaleToUnits` — the goal is
    /// for each model to feel realistic-sized at 1.5 m, so e.g. a Ferrari at
    /// 0.15 m total length reads as a toy car on your kitchen table).
    ///
    /// Exactly one of `streamedSlug` / `bundledAsset` is non-nil. Streamed
    /// entries resolve through `SketchfabAssetResolver.resolve(_:)` and fall
    /// back to the registry's bundled USDZ when no API key is configured.
    private struct Planet {
        let streamedSlug: SketchfabSlug?
        let bundledAsset: String?
        let scale: Float
        let initialAngle: Float
        let orbitSpeed: Float       // rad/s around the user
        let spinSpeed: Float        // rad/s local Y spin — ignored when hasBakedAnimation = true
        let height: Float           // y offset relative to user
        // True when the model ships its own baked animation (wing flap, etc.).
        // For these we skip the Y spin and orient the model along the orbit
        // tangent so it "flies the orbit" naturally — its own anim does the rest.
        let hasBakedAnimation: Bool

        init(
            streamedSlug: SketchfabSlug? = nil,
            bundledAsset: String? = nil,
            scale: Float,
            initialAngle: Float,
            orbitSpeed: Float,
            spinSpeed: Float,
            height: Float,
            hasBakedAnimation: Bool
        ) {
            precondition(
                (streamedSlug == nil) != (bundledAsset == nil),
                "Planet must define exactly one of streamedSlug or bundledAsset."
            )
            self.streamedSlug = streamedSlug
            self.bundledAsset = bundledAsset
            self.scale = scale
            self.initialAngle = initialAngle
            self.orbitSpeed = orbitSpeed
            self.spinSpeed = spinSpeed
            self.height = height
            self.hasBakedAnimation = hasBakedAnimation
        }
    }

    private static let planets: [Planet] = {
        // The four streamed entries — order matches the SampleAssets `solar`
        // category (butterfly, hummingbird, bee, koi fish). Looked up by uid
        // so a registry re-ordering doesn't silently break the per-slot tuning.
        let butterfly = SampleAssets.byUID["78d8345fffe54a55ae62fadcf9eaece6"]!
        let hummingbird = SampleAssets.byUID["9c54b62d3c2f4f0db8e7a3a8a78a4d92"]!
        let bee = SampleAssets.byUID["6cb9f9a4c6e94f9da5b7c8a85e8a5c2d"]!
        let koi = SampleAssets.byUID["d1ca3a3ddf3845abb98f4e5d62ae34c6"]!

        return [
            // Slot 0 — bundled red car, static spinning (hero anchor at angle 0).
            Planet(bundledAsset: "red_car",         scale: 0.18, initialAngle: 0,               orbitSpeed: 0.08, spinSpeed: 0.7, height:  0.0, hasBakedAnimation: false),
            // Slot 1 — streamed butterfly (flies the orbit tangent).
            Planet(streamedSlug: butterfly,         scale: 0.20, initialAngle: .pi * 2 / 8 * 1, orbitSpeed: 0.20, spinSpeed: 0,   height: -0.2, hasBakedAnimation: true),
            // Slot 2 — bundled game boy, static spinning.
            Planet(bundledAsset: "game_boy_classic", scale: 0.12, initialAngle: .pi * 2 / 8 * 2, orbitSpeed: 0.20, spinSpeed: 1.6, height:  0.4, hasBakedAnimation: false),
            // Slot 3 — streamed hummingbird, baked anim.
            Planet(streamedSlug: hummingbird,       scale: 0.15, initialAngle: .pi * 2 / 8 * 3, orbitSpeed: 0.15, spinSpeed: 0,   height: -0.4, hasBakedAnimation: true),
            // Slot 4 — bundled phoenix bird (baked flight cycle).
            // Replaced `animated_dragon.usdz` (8.6 MB) with `phoenix_bird.usdz`
            // (1.1 MB, baked-animation peer) as part of the Stage 3 IPA slim-down
            // — see #1152 Stage 3 PR.
            Planet(bundledAsset: "phoenix_bird",    scale: 0.15, initialAngle: .pi * 2 / 8 * 4, orbitSpeed: 0.05, spinSpeed: 0,   height:  0.2, hasBakedAnimation: true),
            // Slot 5 — streamed bee, baked anim.
            Planet(streamedSlug: bee,               scale: 0.10, initialAngle: .pi * 2 / 8 * 5, orbitSpeed: 0.25, spinSpeed: 0,   height: -0.5, hasBakedAnimation: true),
            // Slot 6 — bundled nintendo switch, static spinning.
            Planet(bundledAsset: "nintendo_switch", scale: 0.18, initialAngle: .pi * 2 / 8 * 6, orbitSpeed: 0.10, spinSpeed: 2.0, height:  0.5, hasBakedAnimation: false),
            // Slot 7 — streamed koi fish, baked anim (closes the ring).
            Planet(streamedSlug: koi,               scale: 0.25, initialAngle: .pi * 2 / 8 * 7, orbitSpeed: 0.30, spinSpeed: 0,   height:  0.3, hasBakedAnimation: true),
        ]
    }()

    private static let orbitRadius: Float = 1.5

    @State private var loadedNodes: [Int: ModelNode] = [:]
    @State private var anchorAdded = false
    @State private var orbitTimer: Timer?

    var body: some View {
        ZStack {
            #if !targetEnvironment(simulator)
            arSceneView
                .ignoresSafeArea()
            #else
            simulatorPlaceholder
            #endif

            VStack {
                statusPill
                Spacer()
            }
        }
        .navigationTitle("Orbital AR")
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear {
            orbitTimer?.invalidate()
            orbitTimer = nil
        }
    }

    // MARK: - AR Scene

    #if !targetEnvironment(simulator)
    private var arSceneView: some View {
        ARSceneView(
            planeDetection: .none,
            showPlaneOverlay: false,
            showCoachingOverlay: false
        )
        .onSessionStarted { arView in
            // Anchor at world origin — ARKit world origin is the camera's pose
            // when tracking begins, so this places the formation exactly around
            // where the user is standing. The user can turn around to see each
            // planet pass through view.
            guard !anchorAdded else { return }
            anchorAdded = true

            let anchor = AnchorNode.world(position: .init(0, 0, 0))
            arView.scene.addAnchor(anchor.entity)

            // Kick off model loads — order them so the closest-to-front planet
            // (index 0, angle 0) lands first for the most visible drop-in.
            //
            // Bundled-only planets call `ModelNode.load(name:)` directly; streamed
            // planets go through `SketchfabAssetResolver.resolve(_:)` which gives
            // us either the downloaded USDZ or the registered bundled fallback,
            // then loads it via `ModelNode.load(contentsOf:)`. Resolver failures
            // are logged silently — the slot just stays empty (matches Android).
            for (index, planet) in Self.planets.enumerated() {
                Task { @MainActor in
                    do {
                        let node: ModelNode
                        if let slug = planet.streamedSlug {
                            let url = try await SketchfabAssetResolver.shared.resolve(slug)
                            node = try await ModelNode.load(contentsOf: url)
                        } else if let bundled = planet.bundledAsset {
                            node = try await ModelNode.load(bundled)
                        } else {
                            return
                        }
                        _ = node.scaleToUnits(planet.scale)
                        node.entity.position = position(for: planet, time: 0)
                        // Auto-play any baked animation (dragon, phoenix, etc.)
                        if node.animationCount > 0 {
                            node.playAllAnimations()
                        }
                        anchor.add(node.entity)
                        loadedNodes[index] = node
                    } catch {
                        let label = planet.streamedSlug?.displayName ?? planet.bundledAsset ?? "(unknown)"
                        print("[OrbitalAR] Failed to load \(label): \(error)")
                    }
                }
            }

            startTicker()
        }
    }
    #endif

    // MARK: - Animation tick

    private func startTicker() {
        // Invalidate any prior timer (e.g. session re-start) to avoid stacking ticks.
        orbitTimer?.invalidate()
        let start = Date()
        // The Timer fires on the main run loop, but its closure is `@Sendable`,
        // so the body must explicitly hop onto the main actor before touching
        // main-actor state (`Self.planets`, `loadedNodes`, `position(for:time:)`).
        // `MainActor.assumeIsolated` is correct here — the closure already runs
        // on the main thread (the timer is added to `RunLoop.main`) — and it
        // keeps the per-frame update synchronous, with no scheduling latency.
        let timer = Timer.scheduledTimer(withTimeInterval: 1.0 / 60.0, repeats: true) { _ in
            MainActor.assumeIsolated {
                let now = Date().timeIntervalSince(start)
                for (index, planet) in Self.planets.enumerated() {
                    guard let node = loadedNodes[index] else { continue }
                    node.entity.position = position(for: planet, time: Float(now))
                    // Models with a baked animation (dragon, phoenix) face the tangent
                    // of the orbit (= direction of motion) instead of spinning on Y —
                    // a flying creature pirouetting on itself breaks the illusion.
                    // The orbit angle modulo 2π is the same precision guard as #978.
                    let orbitAngle = (planet.initialAngle + planet.orbitSpeed * Float(now))
                        .truncatingRemainder(dividingBy: 2 * .pi)
                    let yawAngle: Float
                    if planet.hasBakedAnimation {
                        // For position (R·cos θ, h, R·sin θ) on a CCW orbit, the tangent
                        // is (-sin θ, 0, cos θ). For a USDZ whose forward is -Z, that
                        // maps to a Y-rotation of θ + π.
                        yawAngle = orbitAngle + .pi
                    } else {
                        yawAngle = (planet.spinSpeed * Float(now))
                            .truncatingRemainder(dividingBy: 2 * .pi)
                    }
                    node.entity.orientation = simd_quatf(angle: yawAngle, axis: .init(0, 1, 0))
                }
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        orbitTimer = timer
    }

    private func position(for planet: Planet, time: Float) -> SIMD3<Float> {
        // Wrap the cumulative angle into [0, 2π) before passing to cos/sin so a
        // long-running session (~290 h+) doesn't lose Float precision and stutter
        // the orbit (#978).
        let angle = (planet.initialAngle + planet.orbitSpeed * time)
            .truncatingRemainder(dividingBy: 2 * .pi)
        return SIMD3<Float>(
            x: cos(angle) * Self.orbitRadius,
            y: planet.height,
            z: sin(angle) * Self.orbitRadius
        )
    }

    // MARK: - Simulator placeholder

    private var simulatorPlaceholder: some View {
        VStack(spacing: 16) {
            Image(systemName: "circle.dotted")
                .font(.system(size: 60))
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            Text("AR requires a physical device")
                .font(.headline)
            Text("Run on iPhone or iPad to see 8 models orbit around you.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemGroupedBackground))
    }

    // MARK: - Status pill

    private var statusPill: some View {
        Text("Turn around — \(loadedNodes.count) of \(Self.planets.count) models orbiting")
            .font(.caption)
            .fontWeight(.medium)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(.ultraThinMaterial)
            .clipShape(Capsule())
            .padding(.top, 8)
    }
}
#endif
