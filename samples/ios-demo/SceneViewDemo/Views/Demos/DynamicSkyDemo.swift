import SwiftUI
import RealityKit
import SceneViewSwift

/// Dynamic sky -- slider to control time of day from midnight to midnight.
struct DynamicSkyDemo: View {
    @State private var timeOfDay: Float = 12
    @State private var heroNode: ModelNode?
    @State private var heroLoadFailed = false
    /// The sun currently in the scene. The time-of-day slider re-aims it in
    /// place (`DynamicSkyNode.time(_:)` writes the new sun state onto the
    /// same entity) instead of rebuilding the scene per slider tick.
    @State private var skyNode: DynamicSkyNode?
    @AppStorage(DeepLinkRouter.qaModeDefaultsKey) private var qaMode: Bool = false

    /// Framing margin, mirroring `ModelViewerDemo`'s split for the same reason:
    /// the interactive value has to keep the subject inside the frame while the
    /// orbit sweeps, and under `qa_mode` the orbit is frozen so the shot can be
    /// tighter. Without this the auto-framing default left the helmet at about
    /// a quarter of the frame height on iPhone.
    ///
    /// Deliberately looser than `ModelViewerDemo`'s 0.62, and the difference is
    /// the subject's aspect, not a preference. That demo frames a hovercar,
    /// which is wide and short; this one frames a helmet, which is nearly as
    /// tall as it is wide. Measured on both store classes: at 0.62 the iPhone
    /// shot is right but the 13" iPad — whose frame is far less tall relative
    /// to its width — clips the helmet's lower housing against the bottom edge.
    /// One value has to serve both classes, because iOS has no per-device
    /// framing lever from the capture script (#2785).
    private static let captureFramingMargin: Float = 0.75

    /// Hero size and placement. With no ground plane the subject IS the union
    /// bounding sphere, so the auto-framing pass fits the helmet itself and
    /// `heroUnits` no longer competes with a plane for the frame (#2896).
    private static let subjectZ: Float = -2
    private static let heroUnits: Float = 0.62

    private var timeLabel: String {
        let hour = Int(timeOfDay) % 24
        let minute = Int((timeOfDay - Float(Int(timeOfDay))) * 60)
        return String(format: "%02d:%02d", hour, minute)
    }

    private var periodLabel: String {
        if timeOfDay < 5 || timeOfDay > 20 { return "Night" }
        if timeOfDay < 7 { return "Dawn" }
        if timeOfDay < 10 { return "Morning" }
        if timeOfDay < 14 { return "Noon" }
        if timeOfDay < 17 { return "Afternoon" }
        if timeOfDay < 19 { return "Sunset" }
        return "Dusk"
    }

    /// Pick an HDR environment that visually matches the current time of day so the
    /// background isn't a flat black box when the sun is up. Mirrors the Android fix
    /// in commit `15bcaf8c`. Three buckets is coarse — the tint jumps rather than
    /// fading smoothly — but it covers the obvious user expectations. The deep-night
    /// bucket uses the dramatic `night_sky` Milky Way HDR (v4.4.0, #1219) so the
    /// midnight sky actually wraps the scene instead of fading to neutral dark.
    private var skyEnvironment: SceneEnvironment {
        switch timeOfDay {
        case ..<6, 19...: return .nightSky
        case ..<9, 17...: return .sunset
        default: return .outdoor
        }
    }

    var body: some View {
        sceneContent
            .demoSettingsSheet {
                controlsSheet
            }
    }

    @ViewBuilder
    private var sceneContent: some View {
        ZStack {
            SceneView { root in
                // No ground plane. There used to be one, sized to the five-cube
                // skyline's footprint so the auto-framing pass — which fits the
                // *union* bounding sphere — would not pull the camera back and
                // shrink everything (#2896). With a single hero subject it
                // earns nothing and costs twice: measured on both classes, the
                // plane still dominated the union sphere and left the helmet at
                // roughly a sixth of the frame height, and the subject visibly
                // intersected it. Android's shot of this same subject has no
                // plane either — it is the helmet against the sky.
                //
                // Hero subject — the same `khronos_damaged_helmet` Android's
                // Lighting Lab puts under this demo id.
                //
                // This slot used to be five `systemGray` cubes standing in for a
                // skyline. That worked as written, but it demonstrated the sun
                // with an object that has almost nothing to show: a matte grey
                // box reads the same at noon and at dusk apart from its shadow,
                // so the one thing this demo exists to show — an environment
                // driving a surface — was invisible. A PBR subject with metal
                // and rough dielectric regions renders the time-of-day change
                // directly in its reflections. It also ends the divergence where
                // the App Store showed grey cubes for the very demo id whose
                // Play screenshot is this helmet (#3003 / #2773).
                if let hero = heroNode {
                    root.addChild(hero.entity)
                }

                // Dynamic sky light
                let sky = DynamicSkyNode(timeOfDay: timeOfDay, turbidity: 3, sunIntensity: 1500)
                root.addChild(sky.entity)
                DispatchQueue.main.async { skyNode = sky }
            }
            .environment(skyEnvironment)
            .cameraControls(.orbit)
            // The orbit camera's 30° default pitch puts the horizon exactly on
            // the top edge of a 60°-FOV frame, so a time-of-day demo showed
            // everything *except* its sky. Drop to 12° for a low skyline angle
            // where the sky fills most of the frame (#2896).
            .cameraOrbit(elevation: .pi / 15)
            .framingMargin(qaMode ? Self.captureFramingMargin : 0.95)
            // The content is rebuilt exactly once, when the helmet lands: the
            // key goes `nil` -> "hero" so a scene built before the async load
            // picks the model up (same idiom as `GestureEditingDemo`). The
            // time of day and the matching HDR are continuous parameters and
            // are applied reactively — the sun below, the environment by
            // `SceneView` itself. Keying the whole view on them with `.id(_:)`
            // re-created the `RealityView` per slider tick and intermittently
            // left it black on iOS 26 Simulator (#3008).
            .contentID(heroNode == nil ? nil : "hero")
            .ignoresSafeArea()
        }
        .background(Color.black)
        .onChange(of: timeOfDay) { _, newValue in
            skyNode = skyNode?.time(newValue)
        }
        // On the ZStack, not inside the SceneView modifier chain: `.task`
        // erases to `some View`, and `.environment` / `.cameraControls` /
        // `.cameraOrbit` are SceneView-specific, so inserting it mid-chain
        // stops them resolving.
        .task { await loadHeroIfNeeded() }
    }

    @ViewBuilder
    private var controlsSheet: some View {
        VStack(spacing: 10) {
            HStack {
                Image(systemName: timeIcon)
                    .foregroundStyle(.yellow)
                Text(timeLabel)
                    .font(.title2).bold()
                    .monospacedDigit()
                Text(periodLabel)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(.gray.opacity(0.15))
                    .clipShape(Capsule())
            }

            Slider(value: $timeOfDay, in: 0...24, step: 0.25)
                .tint(.orange)
                .accessibilityLabel("Time of day slider")
                .accessibilityValue("\(timeLabel), \(periodLabel)")

            HStack {
                Text("00:00").font(.caption2).foregroundStyle(.secondary)
                Spacer()
                Text("12:00").font(.caption2).foregroundStyle(.secondary)
                Spacer()
                Text("24:00").font(.caption2).foregroundStyle(.secondary)
            }
        }
    }

    /// Loads the hero model once and caches it. Failure is recorded so the
    /// scene does not retry on every rebuild — the demo still renders (ground
    /// plane + dynamic sky), it just has no subject, which is the same
    /// degradation the other bundled-model demos accept.
    @MainActor
    private func loadHeroIfNeeded() async {
        guard heroNode == nil, !heroLoadFailed else { return }
        do {
            let node = try await ModelNode.load("khronos_damaged_helmet")
            _ = node.scaleToUnits(Self.heroUnits)
            // Centred on the orbit target. No `withShadow()`: there is no
            // surface left to receive one, so it would only cost a shadow pass.
            node.entity.position = .init(x: 0, y: 0, z: Self.subjectZ)
            heroNode = node
        } catch {
            heroLoadFailed = true
        }
    }

    private var timeIcon: String {
        if timeOfDay < 5 || timeOfDay > 20 { return "moon.stars.fill" }
        if timeOfDay < 7 || timeOfDay > 18 { return "sun.horizon.fill" }
        return "sun.max.fill"
    }
}
