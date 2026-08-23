import SwiftUI
import RealityKit
import SceneViewSwift

/// Interactive camera controls demo — mirrors Android `CameraControlsDemo.kt`.
///
/// Users can switch between ``CameraControlMode/orbit``, ``CameraControlMode/pan``,
/// and ``CameraControlMode/firstPerson`` to feel the difference in gesture
/// handling. Closes #1034 (last #928 silent-stub item).
///
/// The mode switcher also exercises the v4.4.0 follow-up #1236: switching
/// orbit ↔ firstPerson keeps the camera in place (true look-around, no
/// teleport), and the "Recenter on orbit" toggle re-pivots a panned scene
/// when you return to orbit mode.
struct CameraControlsDemo: View {
    @State private var mode: CameraControlMode = .orbit
    /// When on, returning to `.orbit` snaps the orbit pivot back to the
    /// scene centroid so repeated pan→orbit cycles don't drift (#1236).
    @State private var recenterOnOrbit = false

    var body: some View {
        sceneContent
            .demoChrome {
                controlsSheet
            }
    }

    @ViewBuilder
    private var sceneContent: some View {
        ZStack {
            SceneView { root in
                // Central object — orange PBR cube
                let cube = GeometryNode.cube(
                    size: 0.35,
                    material: .pbr(color: .systemOrange, metallic: 0.7, roughness: 0.2),
                    cornerRadius: 0.025
                )
                cube.entity.position = .init(x: 0, y: 0, z: -2)
                root.addChild(cube.entity)

                // Reference grid so .pan and .firstPerson translations are visible.
                let grid = PathNode.grid(
                    size: 2.0,
                    divisions: 10,
                    thickness: 0.002,
                    color: .init(white: 0.2, alpha: 1)
                ).position(.init(x: 0, y: -0.3, z: -2))
                root.addChild(grid.entity)

                // Axis gizmo to anchor user orientation when panning/looking around.
                let gizmo = LineNode.axisGizmo(
                    at: .init(x: -0.8, y: -0.29, z: -1.2),
                    length: 0.2,
                    thickness: 0.004
                )
                for line in gizmo {
                    root.addChild(line.entity)
                }

                let xLabel = TextNode(text: "X", fontSize: 0.04, color: .red, depth: 0.005)
                    .position(.init(x: -0.55, y: -0.27, z: -1.2))
                root.addChild(xLabel.entity)
                let yLabel = TextNode(text: "Y", fontSize: 0.04, color: .green, depth: 0.005)
                    .position(.init(x: -0.8, y: -0.05, z: -1.2))
                root.addChild(yLabel.entity)
                let zLabel = TextNode(text: "Z", fontSize: 0.04, color: .systemBlue, depth: 0.005)
                    .position(.init(x: -0.8, y: -0.27, z: -0.95))
                root.addChild(zLabel.entity)
            }
            .cameraControls(mode)
            .recentersTargetOnOrbit(recenterOnOrbit)
            // The central object is an explicit "orange PBR cube" (metallic
            // 0.7, roughness 0.2) — without an IBL it has nothing to reflect
            // and renders flat regardless of which camera mode is active.
            // Same `.studio` preset as ModelViewerDemo (#2114).
            .environment(.studio)
            .ignoresSafeArea()
        }
        .background(Color.black)
    }

    @ViewBuilder
    private var controlsSheet: some View {
        VStack(spacing: 16) {
            // Custom SceneView modes — full SceneView gesture math.
            Text("SceneView modes")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
            Picker("Camera mode", selection: $mode) {
                Text("Orbit").tag(CameraControlMode.orbit)
                Text("Pan").tag(CameraControlMode.pan)
                Text("Look").tag(CameraControlMode.firstPerson)
            }
            .pickerStyle(.segmented)

            // Native Apple RealityKit modes (iOS 18+, macOS 15+).
            // These delegate to realityViewCameraControls(_:) so
            // gesture handling is owned by Apple's RealityKit SDK.
            Text("Apple RealityKit modes (iOS 18+)")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
            Picker("Native mode", selection: $mode) {
                Text("None").tag(CameraControlMode.none)
                Text("Tilt").tag(CameraControlMode.tilt)
                Text("Dolly").tag(CameraControlMode.dolly)
            }
            .pickerStyle(.segmented)

            VStack(spacing: 8) {
                instructionRow(icon: gestureIconForMode, text: dragHintForMode)
                instructionRow(icon: "hand.pinch.fill", text: pinchHintForMode)
            }

            // #1236: opt-in pivot recentre so pan-then-orbit doesn't drift
            // the model out of frame on repeated cycles.
            Toggle(isOn: $recenterOnOrbit) {
                Label("Recenter pivot on orbit", systemImage: "scope")
                    .font(.caption)
            }
        }
    }

    // MARK: - Per-mode hints

    private var gestureIconForMode: String {
        switch mode {
        case .orbit:       return "arrow.triangle.2.circlepath"
        case .pan:         return "hand.draw.fill"
        case .firstPerson: return "eye.fill"
        // Native Apple modes — RealityKit owns the camera gesture.
        case .none:        return "hand.raised.slash.fill"
        case .tilt:        return "arrow.up.and.down"
        case .dolly:       return "arrow.forward.and.backward"
        }
    }

    private var dragHintForMode: String {
        switch mode {
        case .orbit:       return "Drag — orbit around object"
        case .pan:         return "Drag — translate scene"
        case .firstPerson: return "Drag — look around"
        // Native Apple modes — gesture hint provided by RealityKit.
        case .none:        return "Camera locked — no gestures"
        case .tilt:        return "Apple native tilt — pan up/down"
        case .dolly:       return "Apple native dolly — move forward/back"
        }
    }

    private var pinchHintForMode: String {
        switch mode {
        case .orbit, .pan: return "Pinch — zoom in / out"
        case .firstPerson: return "Pinch — zoom field of view"
        // Native Apple modes — pinch handled by RealityKit internally.
        case .none, .tilt, .dolly: return "Gestures handled by Apple RealityKit"
        }
    }

    private func instructionRow(icon: String, text: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: icon)
                .font(.body)
                .foregroundStyle(.secondary)
                .frame(width: 24)
                .accessibilityHidden(true)
            Text(text)
                .font(.caption)
                .foregroundStyle(.primary)
        }
    }
}
