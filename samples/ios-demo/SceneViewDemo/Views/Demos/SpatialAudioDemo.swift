import SwiftUI
import RealityKit
import SceneViewSwift

/// Positional 3D audio showcase — a bell tone orbits the scene as a
/// ``SpatialAudioNode``.
///
/// Mirrors the Android `SpatialAudioDemo`
/// (`samples/android-demo/.../demos/SpatialAudioDemo.kt`): a small sphere
/// orbits a fixed centre, and a looping bell tone is attached to it via a
/// ``SpatialAudioNode``. RealityKit's spatial-audio renderer treats the active
/// camera as the listener, so as the sphere swings past the audio pans between
/// the ears and attenuates with distance — no manual per-frame gain loop. Orbit
/// the camera and the sound rotates with you, because the source stays anchored
/// to the world while your ears move.
///
/// Two falloff curves are pickable, matching the Android demo:
///  - **Inverse** (default) — physically realistic; faint at long range but
///    never quite silent.
///  - **Linear** — drops to silence at the picked `maxDistance`.
///
/// The audio asset is `bell.wav` (CC0 — see `assets/audio/CREDITS.md`), bundled
/// into the app. Headphones recommended to hear the binaural pan clearly.
///
/// ### Honest-subset note
///
/// RealityKit applies its own built-in distance attenuation; the picked
/// ``AudioFalloff`` is pushed onto the playback gain via
/// ``SpatialAudioNode/updateGain(forDistance:)`` each frame so the curve choice
/// is audible. Occlusion and reverb zones are phase 3 of #1900.
struct SpatialAudioDemo: View {
    @State private var falloffMode: FalloffMode = .inverse
    @State private var isPlaying: Bool = true
    @State private var loadError: String?

    /// Owns the RealityKit entities + orbit timer off the SwiftUI view struct so
    /// they survive `body` recomputation.
    @StateObject private var coordinator = SpatialAudioCoordinator()

    private enum FalloffMode: String, CaseIterable, Identifiable {
        case inverse = "Inverse"
        case linear = "Linear"
        var id: String { rawValue }

        var falloff: AudioFalloff {
            switch self {
            case .inverse: return .inverse(refDistance: 0.5, maxDistance: 6)
            case .linear:  return .linear(refDistance: 0.2, maxDistance: 4)
            }
        }
    }

    var body: some View {
        sceneContent
            .demoSettingsSheet {
                controlsSheet
            }
            .task {
                await coordinator.loadIfNeeded()
                if let error = coordinator.loadError {
                    loadError = error
                }
            }
            .onChange(of: falloffMode) { _, newValue in
                coordinator.setFalloff(newValue.falloff)
            }
            .onChange(of: isPlaying) { _, playing in
                coordinator.setPlaying(playing)
            }
            .onDisappear {
                coordinator.stop()
            }
    }

    @ViewBuilder
    private var sceneContent: some View {
        ZStack {
            SceneView { root in
                coordinator.attach(to: root)
            }
            .cameraControls(.orbit)
            .ignoresSafeArea()

            if let loadError {
                VStack(spacing: 8) {
                    Image(systemName: "speaker.slash.fill")
                        .font(.title)
                        .foregroundStyle(.white.opacity(0.7))
                    Text(loadError)
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.7))
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)
                }
            }
        }
        .background(Color.black)
    }

    @ViewBuilder
    private var controlsSheet: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("A looping bell tone orbits the scene. Drag to orbit the camera — the sound pans between your ears and fades with distance. Headphones recommended.")
                .font(.caption)
                .foregroundStyle(.secondary)

            // Playback row.
            HStack {
                Text("Playback")
                    .font(.subheadline.weight(.semibold))
                Spacer()
                Button {
                    isPlaying.toggle()
                    #if os(iOS)
                    HapticManager.lightTap()
                    #endif
                } label: {
                    Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                        .font(.title3)
                        .padding(10)
                        .background(.gray.opacity(0.15), in: Circle())
                }
                .accessibilityLabel(isPlaying ? "Pause" : "Play")
                .buttonStyle(.plain)
            }

            // Falloff picker — mirrors the Android FilterChip row.
            VStack(alignment: .leading, spacing: 8) {
                Text("Distance falloff")
                    .font(.subheadline.weight(.semibold))
                HStack(spacing: 8) {
                    ForEach(FalloffMode.allCases) { mode in
                        Button {
                            falloffMode = mode
                            #if os(iOS)
                            HapticManager.selectionChanged()
                            #endif
                        } label: {
                            Text(mode.rawValue)
                                .font(.caption.weight(.semibold))
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(
                                    falloffMode == mode
                                        ? AnyShapeStyle(.blue)
                                        : AnyShapeStyle(.gray.opacity(0.15)),
                                    in: Capsule()
                                )
                                .foregroundStyle(falloffMode == mode ? .white : .primary)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }

            Text("Backed by RealityKit spatial audio. Occlusion and reverb zones are coming in a future release. Audio: bell.wav (CC0).")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
    }
}

/// Owns the orbiting sphere, the ``SpatialAudioNode``, and the 60 Hz orbit
/// timer. Kept off the SwiftUI view struct so the entity graph + timer survive
/// `body` recomputation — same pattern as `DoublePendulumDemo`'s coordinator.
@MainActor
private final class SpatialAudioCoordinator: ObservableObject {
    /// Surfaced to the view so a load failure can be shown in the scene overlay.
    @Published var loadError: String?

    private var audioNode: SpatialAudioNode?
    private let orbiter = ModelEntity(
        mesh: .generateSphere(radius: 0.08),
        materials: [SimpleMaterial(color: .systemBlue, isMetallic: false)]
    )
    private let centerMarker = ModelEntity(
        mesh: .generateSphere(radius: 0.02),
        materials: [SimpleMaterial(color: .gray, isMetallic: false)]
    )
    private var timer: Timer?
    private var orbitAngle: Float = 0
    private var loaded = false
    private var currentFalloff: AudioFalloff = .inverse(refDistance: 0.5, maxDistance: 6)
    private let orbitRadius: Float = 0.6

    /// Loads `bell.wav` and builds the audio node. Idempotent — safe to call
    /// from `.task` on every appearance.
    func loadIfNeeded() async {
        guard !loaded else { return }
        loaded = true
        do {
            // `bell.wav` is bundled with the app; load it with looping enabled
            // so the orbiting bell plays continuously.
            let configuration = AudioFileResource.Configuration(shouldLoop: true)
            let resource = try await AudioFileResource(
                named: "bell.wav",
                configuration: configuration
            )
            let node = SpatialAudioNode.spatial(
                source: resource,
                falloff: currentFalloff,
                loop: true,
                autoPlay: true
            )
            // Attach the audio entity as a child of the orbiter so the sound
            // moves with the sphere.
            orbiter.addChild(node.entity)
            audioNode = node
            startOrbitTimer()
        } catch {
            loadError = "Could not load the bell audio clip: \(error.localizedDescription)"
        }
    }

    /// Adds the orbiter + centre marker to the scene root.
    func attach(to root: Entity) {
        orbiter.position = SIMD3<Float>(0, 0, orbitRadius)
        centerMarker.position = .zero
        root.addChild(orbiter)
        root.addChild(centerMarker)
    }

    /// Swaps the distance-attenuation curve.
    func setFalloff(_ falloff: AudioFalloff) {
        currentFalloff = falloff
        audioNode?.setFalloff(falloff)
    }

    /// Starts / stops playback from the controls sheet.
    func setPlaying(_ playing: Bool) {
        if playing {
            audioNode?.play()
        } else {
            audioNode?.pause()
        }
    }

    /// Stops playback and the orbit timer. Called on disappear.
    func stop() {
        timer?.invalidate()
        timer = nil
        audioNode?.stop()
    }

    /// Drives the orbiter around a circle at ~0.5 rev/s and pushes the
    /// distance-attenuated gain each tick so the picked falloff is audible.
    private func startOrbitTimer() {
        timer?.invalidate()
        let start = Date()
        let t = Timer.scheduledTimer(withTimeInterval: 1.0 / 60.0, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                let elapsed = Float(Date().timeIntervalSince(start))
                self.orbitAngle = (elapsed * 2 * .pi * 0.5).truncatingRemainder(dividingBy: 2 * .pi)
                let x = cos(self.orbitAngle) * self.orbitRadius
                let z = sin(self.orbitAngle) * self.orbitRadius
                self.orbiter.position = SIMD3<Float>(x, 0, z)
                // Distance from the world-origin listener (RealityKit's default
                // camera listener orbits, but the source-to-origin distance is
                // a good proxy for the falloff curve demonstration).
                let distance = (self.orbiter.position).magnitudeAudio
                self.audioNode?.updateGain(forDistance: distance)
            }
        }
        RunLoop.main.add(t, forMode: .common)
        timer = t
    }
}

private extension SIMD3<Float> {
    /// Euclidean length — named to avoid colliding with any RealityKit helper.
    var magnitudeAudio: Float {
        (x * x + y * y + z * z).squareRoot()
    }
}
