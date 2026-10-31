import SwiftUI
import Combine
import RealityKit
import SceneViewSwift

/// Custom Geometry — a (2, 3) torus knot generated vertex by vertex, Android's
/// `CustomGeometryDemo` on RealityKit.
///
/// ``TorusKnot`` computes every position, normal and UV in Swift; the stage hands them
/// to `MeshNode.fromVertices`. Segments, Twist and Ripple rebuild the mesh live, and
/// Wireframe swaps the solid for `MeshNode.wireframe(positions:lineIndices:)` — RealityKit
/// has no line primitive, so each wire is a thin unlit prism, not a 1-pixel line.
///
/// Coverage: `sceneview://demo/custom-geometry` (alias of `custom-mesh`).
struct CustomMeshDemo: View {
    @State private var segments = Double(TorusKnot.defaultSegments)
    @State private var twist = Double(TorusKnot.defaultTwist)
    @State private var ripple = Double(TorusKnot.defaultRipple)
    @State private var wireframe = false
    @State private var stage = CustomGeometryStage()
    @Environment(\.colorScheme) private var colorScheme
    @AppStorage(DeepLinkRouter.qaModeDefaultsKey) private var qaMode = false

    private var parameters: TorusKnot.Parameters {
        TorusKnot.Parameters(segments: TorusKnot.snapSegments(segments),
                             twist: TorusKnot.snapTwist(Float(twist)),
                             ripple: Float(ripple))
    }

    var body: some View {
        ZStack {
            LinearGradient(colors: [SceneViewTokens.Stage.skyHorizon, SceneViewTokens.Stage.skyGround],
                           startPoint: .top, endPoint: .bottom)
            SceneView { root in stage.install(in: root) }
                .environment(Self.environment)
                .cameraControls(.orbit)
                // The knot spins in place: fitting the camera to its bounds every
                // frame would breathe the framing in and out.
                .autoCenterContent(false)
                .cameraPose(TorusKnot.cameraPose)
        }
        .ignoresSafeArea()
        .demoChrome(
            title: "Custom Geometry",
            dock: [
                DockItem(icon: "squareshape.split.3x3", label: "Wireframe", control: "wireframe",
                         selected: wireframe) { wireframe.toggle() }
            ],
            onReset: reset,
            accessory: {
                DemoHint(TorusKnot.summary(segments: parameters.segments))
            }
        ) {
            Text("Every position, normal and UV of this trefoil knot is computed in Swift, "
                 + "uploaded to a RealityKit mesh and drawn by a MeshNode. Move a slider and "
                 + "the mesh is rebuilt live.")
                .font(.footnote)
                .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                .fixedSize(horizontal: false, vertical: true)
            LabeledSlider(label: "Segments", value: $segments,
                          range: Double(TorusKnot.minSegments)...Double(TorusKnot.maxSegments),
                          step: Double(TorusKnot.segmentStep),
                          valueText: "\(parameters.segments) rings")
            LabeledSlider(label: "Twist", value: $twist, range: 0...4, step: 0.5,
                          valueText: String(format: "%.1f turns", parameters.twist))
            LabeledSlider(label: "Ripple", value: $ripple, range: 0...0.6, decimals: 2)
        }
        .onAppear {
            stage.configure(parameters: parameters, wireframe: wireframe, dark: colorScheme == .dark)
            stage.setFrozen(qaMode)
        }
        .onDisappear { stage.stop() }
        .onChange(of: parameters) { _, value in stage.configure(parameters: value) }
        .onChange(of: wireframe) { _, value in stage.configure(wireframe: value) }
        .onChange(of: colorScheme) { _, scheme in stage.configure(dark: scheme == .dark) }
        .onChange(of: qaMode) { _, frozen in stage.setFrozen(frozen) }
    }

    private func reset() {
        segments = Double(TorusKnot.defaultSegments)
        twist = Double(TorusKnot.defaultTwist)
        ripple = Double(TorusKnot.defaultRipple)
        wireframe = false
    }

    private static var environment: SceneEnvironment {
        var studio = SceneEnvironment.studio
        studio.showSkybox = false
        return studio
    }
}

// MARK: - Stage

/// The knot, its two lights and its spin. One `ModelEntity` per mode, regenerated in
/// place when a parameter changes.
@MainActor
final class CustomGeometryStage {
    private static let tilt = simd_quatf(angle: 24 * .pi / 180, axis: [1, 0, 0])
    private static let spinRadiansPerSecond: Float = 20 * .pi / 180
    private static let frozenSpin: Float = 30 * .pi / 180
    /// 3 mm reads as a hairline at the 2.4 m home distance on a phone.
    private static let wireThickness: Float = 0.003

    private let root = Entity()
    private let pivot = Entity()
    private var knot: Entity?
    private let ticker = FrameTicker()
    private var parameters = TorusKnot.Parameters()
    private var wireframe = false
    private var dark = false
    private var spin: Float = 0
    private var frozen = false
    private var installed = false

    func install(in scene: Entity) {
        if !installed {
            root.addChild(pivot)
            for light in TorusKnot.lights() { root.addChild(light) }
            installed = true
            rebuild()
        }
        root.removeFromParent()
        scene.addChild(root)
        ticker.start(on: root) { [weak self] delta in self?.tick(delta) }
    }

    func stop() { ticker.stop() }

    func configure(parameters: TorusKnot.Parameters? = nil, wireframe: Bool? = nil, dark: Bool? = nil) {
        var changed = false
        if let parameters, parameters != self.parameters { self.parameters = parameters; changed = true }
        if let wireframe, wireframe != self.wireframe { self.wireframe = wireframe; changed = true }
        // Only the wires take the theme colour; the solid is the same in both themes.
        if let dark, dark != self.dark { self.dark = dark; changed = changed || self.wireframe }
        if changed { rebuild() }
    }

    func setFrozen(_ frozen: Bool) {
        self.frozen = frozen
        if frozen { spin = Self.frozenSpin }
        applySpin()
    }

    private func tick(_ delta: Float) {
        guard !frozen else { return }
        spin = (spin + delta * Self.spinRadiansPerSecond).truncatingRemainder(dividingBy: 2 * .pi)
        applySpin()
    }

    private func applySpin() {
        pivot.orientation = Self.tilt * simd_quatf(angle: spin, axis: [0, 1, 0])
    }

    private func rebuild() {
        guard installed else { return }
        let mesh = TorusKnot.mesh(parameters)
        let node: MeshNode?
        if wireframe {
            // Theme `primary`: #005BC1 light, #A4C1FF dark.
            let wire = SceneViewTokens.Stage.shapeRamp[dark ? 2 : 0]
            node = try? MeshNode.wireframe(positions: mesh.positions, lineIndices: mesh.lineIndices,
                                           thickness: Self.wireThickness, material: .unlit(color: wire))
        } else {
            node = try? MeshNode.fromVertices(positions: mesh.positions, normals: mesh.normals, uvs: mesh.uvs,
                                              indices: mesh.triangleIndices,
                                              material: .custom(TorusKnot.solidMaterial))
        }
        knot?.removeFromParent()
        knot = node?.entity
        if let knot { pivot.addChild(knot) }
        applySpin()
    }
}

// MARK: - Torus knot

/// Android's `TorusKnot` generator, line for line: a (2, 3) torus knot swept by a flat
/// ribbon that twists and ripples along its length.
enum TorusKnot {
    struct Parameters: Equatable {
        var segments = TorusKnot.defaultSegments
        var twist = TorusKnot.defaultTwist
        var ripple = TorusKnot.defaultRipple
    }

    struct Mesh {
        var positions: [SIMD3<Float>] = []
        var normals: [SIMD3<Float>] = []
        var uvs: [SIMD2<Float>] = []
        var triangleIndices: [UInt32] = []
        var lineIndices: [UInt32] = []
    }

    static let minSegments = 24
    static let maxSegments = 264
    static let segmentStep = 8
    static let defaultSegments = 168
    static let defaultTwist: Float = 2.5
    static let defaultRipple: Float = 0.3

    private static let p: Float = 2
    private static let q: Float = 3
    private static let majorRadius: Float = 0.22
    private static let ribbonHalfWidth: Float = 0.055
    private static let crossSectionFlatten: Float = 0.42
    private static let rippleCycles: Float = 6
    private static let delta: Float = 1e-3
    private static let tau = 2 * Float.pi

    /// Android's home framing: 2.4 m away, 0.22 of it above the knot's centre.
    static let cameraPose = SceneCameraPose(azimuth: 0, elevation: asin(0.22), distance: 2.4,
                                            target: .zero)

    static func snapSegments(_ value: Double) -> Int {
        let steps = ((value - Double(minSegments)) / Double(segmentStep)).rounded()
        return min(max(minSegments + Int(steps) * segmentStep, minSegments), maxSegments)
    }

    static func snapTwist(_ value: Float) -> Float { (value * 2).rounded() / 2 }

    /// Vertices around the ribbon's cross-section: more along the knot, more around it.
    static func ringSegments(_ segments: Int) -> Int { min(max(segments / 14, 4), 18) / 2 * 2 }
    static func vertexCount(_ segments: Int) -> Int { (segments + 1) * (ringSegments(segments) + 1) }
    static func triangleCount(_ segments: Int) -> Int { segments * ringSegments(segments) * 2 }

    static func summary(segments: Int) -> String {
        "\(vertexCount(segments).formatted()) vertices · \(triangleCount(segments).formatted()) triangles"
    }

    static func mesh(_ parameters: Parameters) -> Mesh {
        let segments = parameters.segments
        let ring = ringSegments(segments)
        var mesh = Mesh()
        mesh.positions.reserveCapacity(vertexCount(segments))
        for i in 0...segments {
            let u = Float(i) / Float(segments)
            let frame = frame(at: u)
            let ahead = Self.frame(at: u + delta)
            for j in 0...ring {
                let v = Float(j) / Float(ring)
                let position = surfacePoint(frame, u: u, v: v, parameters)
                var normal = simd_normalize(simd_cross(surfacePoint(ahead, u: u + delta, v: v, parameters) - position,
                                                       surfacePoint(frame, u: u, v: v + delta, parameters) - position))
                if simd_dot(normal, position - frame.point) < 0 { normal = -normal }
                mesh.positions.append(position)
                mesh.normals.append(normal)
                mesh.uvs.append([u, v])
            }
        }
        let stride = UInt32(ring + 1)
        for i in 0..<UInt32(segments) {
            for j in 0..<UInt32(ring) {
                let a = i * stride + j, b = a + 1, c = a + stride, d = c + 1
                mesh.triangleIndices += [a, b, d, a, d, c]
            }
        }
        // The last ring and the last column repeat the first ones (the UV seams): wire each
        // edge once, or the duplicate prisms z-fight.
        for i in 0..<UInt32(segments) {
            for j in 0..<UInt32(ring) {
                let a = i * stride + j
                mesh.lineIndices += [a, a + stride, a, a + 1]
            }
        }
        return mesh
    }

    private struct Frame {
        let point: SIMD3<Float>
        let normal: SIMD3<Float>
        let binormal: SIMD3<Float>
    }

    private static func curvePoint(_ u: Float) -> SIMD3<Float> {
        let a = u * p * tau
        let b = u * q * tau
        let radial = majorRadius * (2 + cos(b)) * 0.5
        return [radial * cos(a), radial * sin(a), majorRadius * sin(b) * 0.5]
    }

    private static func frame(at u: Float) -> Frame {
        let p1 = curvePoint(u)
        let p2 = curvePoint(u + delta)
        let tangent = p2 - p1
        let binormal = simd_normalize(simd_cross(tangent, p2 + p1))
        let normal = simd_normalize(simd_cross(binormal, tangent))
        return Frame(point: p1, normal: normal, binormal: binormal)
    }

    private static func surfacePoint(_ frame: Frame, u: Float, v: Float, _ parameters: Parameters) -> SIMD3<Float> {
        let angle = (v + parameters.twist * u) * tau
        let halfWidth = ribbonHalfWidth * (1 + parameters.ripple * sin(rippleCycles * u * tau))
        return frame.point
            + frame.normal * (halfWidth * cos(angle))
            + frame.binormal * (halfWidth * crossSectionFlatten * sin(angle))
    }

    /// Primary #005BC1, polished — the same in both themes, double-sided like Android's.
    static var solidMaterial: PhysicallyBasedMaterial {
        var material = PhysicallyBasedMaterial()
        material.baseColor = .init(tint: SceneViewTokens.Stage.shapeRamp[0])
        material.metallic = 0.7
        material.roughness = 0.25
        material.faceCulling = .none
        return material
    }

    /// Android's warm key and cool rim, by direction of travel.
    @MainActor static func lights() -> [Entity] {
        let key = LightNode.directional(color: .custom(r: 1, g: 0.95, b: 0.88), intensity: 2_500, castsShadow: false)
            .position(-simd_normalize(SIMD3<Float>(0.35, -0.8, -0.5)))
            .lookAt(.zero)
        let rim = LightNode.directional(color: .custom(r: 0.62, g: 0.68, b: 1), intensity: 1_750, castsShadow: false)
            .position(-simd_normalize(SIMD3<Float>(-0.45, 0.35, 0.8)))
            .lookAt(.zero)
        return [key.entity, rim.entity]
    }
}

// MARK: - Frame ticker

/// Calls `tick` with the frame's delta time on every RealityKit scene update, once
/// `root` is in a scene. Shared by the procedural demos (geometry, lines, knot).
@MainActor
final class FrameTicker {
    private var updates: (any Cancellable)?
    private var attachment: Task<Void, Never>?

    func start(on root: Entity, _ tick: @escaping @MainActor (Float) -> Void) {
        stop()
        attachment = Task { @MainActor [weak self, weak root] in
            while let root, root.scene == nil {
                guard !Task.isCancelled else { return }
                try? await Task.sleep(for: .milliseconds(16))
            }
            guard !Task.isCancelled, let self, let scene = root?.scene else { return }
            self.updates = scene.subscribe(to: SceneEvents.Update.self) { event in
                let delta = Float(event.deltaTime)
                MainActor.assumeIsolated { tick(delta) }
            }
        }
    }

    func stop() {
        attachment?.cancel()
        attachment = nil
        updates?.cancel()
        updates = nil
    }
}
