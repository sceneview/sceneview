import SwiftUI
import RealityKit
import SceneViewSwift

/// Movable light demo — drag anywhere on the screen to orbit a point light
/// around a PBR model so the user can see specular highlights track the
/// light position in real time.
///
/// UX:
/// - PBR model centred (Ferrari F40 — a bundled USDZ with prominent metallic
///   paint that makes the specular highlight obvious as the light orbits).
/// - Yellow unlit sphere marks the light position so the user always sees
///   *where* the light is.
/// - DragGesture maps horizontal delta to azimuth, vertical delta to
///   elevation. Spherical → Cartesian with fixed radius (1.5 m).
/// - Intensity slider (1 000 → 100 000 lumens).
/// - Toggle to hide the marker sphere.
/// - Camera is fixed (no `.cameraControls`) — only the light moves.
struct MovableLightDemo: View {
    // Orbit parameters — initial azimuth puts the light up-and-to-the-right
    // of the camera so the helmet reads well on first paint.
    @State private var azimuth: Float = .pi / 4          // 45° around Y
    @State private var elevation: Float = .pi / 6        // 30° above equator
    @State private var lastDragTranslation: CGSize = .zero
    @State private var intensity: Float = 30_000
    @State private var showLightSource: Bool = true
    @State private var loadedModel: ModelNode?
    @State private var isLoading: Bool = true
    @State private var loadError: String?
    // Hold references to the live entities so drag gestures and the controls
    // sheet can mutate them directly. The scene content is built once the
    // model lands and never rebuilt afterwards: position, intensity and
    // marker visibility are all in-place writes.
    @State private var lightEntityRef: Entity?
    @State private var markerEntityRef: Entity?

    /// Fixed orbit radius. ~1.5 m sits the light close enough to the model that
    /// the specular highlight is sharp and clearly tracks the cursor, but far
    /// enough that the marker sphere never visually clips into the body.
    private let orbitRadius: Float = 1.5

    /// Min/max elevation clamps (±85°) — avoids pole gimbal-lock and keeps the
    /// light from sinking under the floor.
    private let minElevation: Float = -(.pi / 2 - 0.087)
    private let maxElevation: Float = .pi / 2 - 0.087

    /// Drag sensitivity in radians-per-screen-point. Tuned for smooth feel on
    /// a 6.1" device — one full screen-width drag ≈ a half-turn around the model.
    private let sensitivity: Float = 0.01

    private var lightPosition: SIMD3<Float> {
        let cosE = cos(elevation)
        return SIMD3<Float>(
            orbitRadius * cosE * sin(azimuth),
            orbitRadius * sin(elevation),
            // Model sits at z = -2 (see ferrariOffset), so add that to keep the
            // light orbiting the *model*, not the world origin.
            -2.0 + orbitRadius * cosE * cos(azimuth)
        )
    }

    /// World-space position of the model — kept here so the marker sphere and
    /// light orbit centre stay in sync if we ever move it.
    private let modelOffset = SIMD3<Float>(0, 0, -2)

    var body: some View {
        sceneContent
            .demoChrome {
                controlsSheet
            }
    }

    @ViewBuilder
    private var sceneContent: some View {
        ZStack {
            // 3D scene — no .cameraControls() so the orbit gestures are not
            // consumed; we attach our own DragGesture as a SwiftUI overlay below
            // to drive the light position.
            SceneView { (root: Entity) -> Void in
                // Load the bundled Ferrari F40 (USDZ, PBR with metallic paint
                // that produces a strong specular response — exactly what this
                // demo is designed to show off).
                if let model = loadedModel {
                    model.entity.position = modelOffset
                    // Scale to roughly 1 m wide so it fits the framing.
                    model.entity.scale = .init(repeating: 0.6)
                    root.addChild(model.entity)
                }

                // Subtle ground plane so the light direction reads in space.
                // SwiftUI iOS GeometryNode.plane only exposes `color:`, not the
                // full `material:` overload — close enough for a backdrop.
                let floor = GeometryNode.plane(
                    width: 4,
                    depth: 4,
                    color: .darkGray
                )
                floor.entity.position = .init(x: 0, y: -0.35, z: modelOffset.z)
                root.addChild(floor.entity)

                // User-controlled point light orbiting the model.
                let userLight = LightNode.point(
                    color: .custom(r: 1.0, g: 0.95, b: 0.8),
                    intensity: intensity,
                    attenuationRadius: 6.0
                )
                userLight.entity.position = lightPosition
                root.addChild(userLight.entity)
                // Stash for direct mutation by the drag gesture (no scene rebuild).
                DispatchQueue.main.async { lightEntityRef = userLight.entity }

                // Yellow unlit marker sphere — unlit so it always reads as
                // self-emissive regardless of where *the* light is. Always in
                // the scene; the toggle flips `isEnabled` in place.
                let marker = GeometryNode.sphere(
                    radius: 0.05,
                    material: .unlit(color: .yellow)
                )
                marker.entity.position = lightPosition
                marker.entity.isEnabled = showLightSource
                root.addChild(marker.entity)
                DispatchQueue.main.async { markerEntityRef = marker.entity }
            }
            // The only content change is the model landing: the key goes
            // `nil` -> "loaded" and the closure re-runs under the same
            // `RealityView`. Intensity and marker visibility are applied to
            // the live entities below; they used to re-key the whole view
            // with `.id(_:)`, which re-created the renderer per slider tick
            // and intermittently left it black on iOS 26 Simulator (#3008).
            .contentID(loadedModel == nil ? nil : "loaded")
            .ignoresSafeArea()

            // Transparent overlay that captures drag gestures *before* SceneView
            // would. Without this we'd either have to disable SceneView's gestures
            // entirely or fight Apple's gesture-arbitration heuristics. The
            // Rectangle().contentShape() makes the entire area hit-testable even
            // though it's invisible.
            Color.clear
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            let dx = Float(value.translation.width - lastDragTranslation.width)
                            let dy = Float(value.translation.height - lastDragTranslation.height)
                            azimuth += dx * sensitivity
                            // Drag down → light goes down (negative elevation).
                            // Screen Y grows downwards so the sign is preserved.
                            elevation -= dy * sensitivity
                            elevation = min(max(elevation, minElevation), maxElevation)
                            lastDragTranslation = value.translation
                            // Direct entity mutation — no SceneView rebuild.
                            let pos = lightPosition
                            lightEntityRef?.position = pos
                            markerEntityRef?.position = pos
                        }
                        .onEnded { _ in
                            lastDragTranslation = .zero
                        }
                )

            // Loading + error overlays
            if isLoading {
                ProgressView()
                    .progressViewStyle(.circular)
                    .tint(.white)
                    .scaleEffect(1.4)
                    .accessibilityLabel("Loading model")
            }
            if let loadError {
                Text(loadError)
                    .font(.caption)
                    .padding()
                    .background(.ultraThinMaterial)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            }

            // Top hint — sits above the SceneView but must NOT eat drag events.
            VStack {
                HStack {
                    Image(systemName: "hand.draw.fill")
                    Text("Drag anywhere to move the light")
                        .font(.caption)
                }
                .foregroundStyle(.white.opacity(0.8))
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(.ultraThinMaterial)
                .clipShape(Capsule())
                .padding(.top, 12)
                .allowsHitTesting(false)

                Spacer()
            }
        }
        .background(Color.black)
        .task {
            await loadFerrari()
        }
        .onChange(of: intensity) { _, newValue in
            lightEntityRef?.components[PointLightComponent.self]?.intensity = newValue
        }
        .onChange(of: showLightSource) { _, newValue in
            markerEntityRef?.isEnabled = newValue
        }
    }

    @ViewBuilder
    private var controlsSheet: some View {
        VStack(spacing: 14) {
            HStack {
                Image(systemName: "sun.max")
                Slider(value: $intensity, in: 1_000...100_000)
                    .tint(.yellow)
                    .accessibilityLabel("Light intensity")
                    .accessibilityValue("\(Int(intensity)) lumens")
                Image(systemName: "sun.max.fill")
            }

            Toggle(isOn: $showLightSource) {
                Label("Show light source", systemImage: "circle.dotted")
            }
            .tint(.yellow)
            .accessibilityHint("Toggles the yellow marker sphere at the light position")
        }
    }

    @MainActor
    private func loadFerrari() async {
        do {
            // Ferrari F40 ships bundled with the app (see ExploreTab.swift).
            // The shiny red paint + chrome trim is the perfect PBR showcase for
            // a "drag the light" demo — the specular highlight tracks visibly.
            let node = try await ModelNode.load("ferrari_f40")
            loadedModel = node
            isLoading = false
        } catch {
            // Fall back to a metallic sphere if for any reason the model can't
            // be loaded — the demo is still useful, just less wow.
            loadError = "Could not load Ferrari model (\(error.localizedDescription)). Falling back to sphere."
            isLoading = false
        }
    }
}
