import SwiftUI
import RealityKit
import SceneViewSwift

/// `TubeNode` — Android's `LinesPathsDemo` on RealityKit: eight control points, the closed
/// route through them as a polyline, a rounded polyline or a spline, a marker running laps
/// with a trail behind it, and a dashed ground track marching underneath.
///
/// Every stroke is a lit tube rebuilt in place (`TubeNode.update`), so the trail and the
/// dashes move without a single entity being created after the first frame.
///
/// Coverage: `sceneview://demo/lines-paths`
struct LinesPathsDemo: View {
    @State private var curve = CurveKind.smooth
    @State private var strokeMillimetres = Double(LinesPathsGeometry.defaultStrokeMillimetres)
    @State private var showPoints = true
    @State private var animating = true
    @State private var stage = LinesPathsStage()
    @AppStorage(DeepLinkRouter.qaModeDefaultsKey) private var qaMode = false

    var body: some View {
        ZStack {
            LinearGradient(colors: [SceneViewTokens.Stage.skyHorizon, SceneViewTokens.Stage.skyGround],
                           startPoint: .top, endPoint: .bottom)
            SceneView { root in stage.install(in: root) }
                .cameraControls(.orbit)
                // The dashes move every frame: a fit to their bounds would re-centre the
                // scene 60 times a second. The stage lifts itself into place once instead.
                .autoCenterContent(false)
                .cameraPose(LinesPathsGeometry.cameraPose)
                // Animate stops the camera as well as the marker, as on Android.
                .autoRotate(speed: animating && !qaMode ? 0.2 : 0)
                // The strokes are lit tubes: without an IBL they read as flat silhouettes.
                .environment(.custom(name: "Studio", hdrFile: "studio.hdr", showSkybox: false))
        }
        .ignoresSafeArea()
        .demoChrome(
            title: "Lines & Paths",
            dock: [
                DockItem(icon: "circle.grid.cross", label: "Points", control: "points",
                         selected: showPoints) { showPoints.toggle() },
                DockItem(icon: animating ? "pause.fill" : "play.fill", label: "Animate", control: "animate",
                         selected: animating) { animating.toggle() },
            ],
            onReset: reset,
            accessory: { DemoHint("Follow the moving marker along the curve") }
        ) {
            Picker("Curve", selection: $curve) {
                ForEach(CurveKind.allCases) { kind in Text(kind.label).tag(kind) }
            }
            .pickerStyle(.segmented)
            LabeledSlider(label: "Stroke", value: $strokeMillimetres,
                          range: Double(LinesPathsGeometry.minStrokeMillimetres)...Double(LinesPathsGeometry.maxStrokeMillimetres),
                          decimals: 0, unit: "mm")
            Toggle("Control Points", isOn: $showPoints)
            Toggle("Animate", isOn: $animating)
        }
        .onAppear {
            stage.configure(curve: curve, strokeMillimetres: Float(strokeMillimetres), showPoints: showPoints)
            stage.setMotion(animating: animating, frozen: qaMode)
        }
        .onDisappear { stage.stop() }
        .onChange(of: curve) { _, value in stage.configure(curve: value) }
        .onChange(of: strokeMillimetres) { _, value in stage.configure(strokeMillimetres: Float(value)) }
        .onChange(of: showPoints) { _, value in stage.configure(showPoints: value) }
        .onChange(of: animating) { _, value in stage.setMotion(animating: value, frozen: qaMode) }
        .onChange(of: qaMode) { _, value in stage.setMotion(animating: animating, frozen: value) }
    }

    private func reset() {
        curve = .smooth
        strokeMillimetres = Double(LinesPathsGeometry.defaultStrokeMillimetres)
        showPoints = true
        animating = true
    }
}

// MARK: - Stage

/// The route, trail, marker, dashes and control points, all built once and updated in place.
@MainActor
final class LinesPathsStage {
    private let root = Entity()
    private let ticker = FrameTicker()
    private let route: TubeNode
    private let trail: TubeNode
    private let marker: ModelEntity
    private let dashes: [TubeNode]
    private let points = Entity()

    private var curve = CurveKind.smooth
    private var routePoints = LinesPathsGeometry.route(.smooth)
    private var strokeRadius = LinesPathsGeometry.strokeRadius(LinesPathsGeometry.defaultStrokeMillimetres)
    private var progress = LinesPathsGeometry.staticProgress
    private var animating = true
    private var frozen = false

    init() {
        // The route the brightest ramp colour, the marker the second brightest so it reads
        // on the route it rides, the control points mid purple, the ground track a backdrop.
        let ramp = SceneViewTokens.Stage.shapeRamp
        let routeMaterial = GeometryMaterial.pbr(color: ramp[2], metallic: 0, roughness: 0.35)
        let markerMaterial = GeometryMaterial.pbr(color: ramp[3], metallic: 0, roughness: 0.25)
        let groundMaterial = GeometryMaterial.pbr(color: ramp[2], metallic: 0, roughness: 0.5)
        let segments = LinesPathsGeometry.tubeSegments

        route = TubeNode(points: routePoints, radius: strokeRadius, radialSegments: segments,
                         closed: true, material: routeMaterial)
        trail = TubeNode(points: [], radialSegments: segments, caps: true, material: markerMaterial)
        marker = GeometryNode.sphere(radius: LinesPathsGeometry.markerRadius, material: markerMaterial).entity
        dashes = (0..<LinesPathsGeometry.dashCount).map { _ in
            TubeNode(points: [], radialSegments: segments, caps: true, material: groundMaterial)
        }
        for point in LinesPathsGeometry.controlPoints {
            let sphere = GeometryNode.sphere(radius: LinesPathsGeometry.pointRadius,
                                             material: .pbr(color: ramp[1], metallic: 0.1, roughness: 0.4)).entity
            sphere.position = point
            points.addChild(sphere)
        }

        root.position = [0, LinesPathsGeometry.sceneLift, 0]
        for dash in dashes { root.addChild(dash.entity) }
        for entity in [route.entity, trail.entity, marker, points] { root.addChild(entity) }
        applyProgress()
    }

    func install(in scene: Entity) {
        root.removeFromParent()
        scene.addChild(root)
        ticker.start(on: root) { [weak self] delta in self?.tick(delta) }
    }

    func stop() { ticker.stop() }

    func configure(curve: CurveKind? = nil, strokeMillimetres: Float? = nil, showPoints: Bool? = nil) {
        if let curve, curve != self.curve {
            self.curve = curve
            routePoints = LinesPathsGeometry.route(curve)
        }
        if let strokeMillimetres { strokeRadius = LinesPathsGeometry.strokeRadius(strokeMillimetres) }
        if let showPoints { points.isEnabled = showPoints }
        if curve != nil || strokeMillimetres != nil {
            route.updateGeometry(points: routePoints, radius: strokeRadius)
            applyProgress()
        }
    }

    /// A paused or QA-mode scene sits at Android's fixed phase, so captures are deterministic.
    func setMotion(animating: Bool, frozen: Bool) {
        self.animating = animating
        self.frozen = frozen
        if !animating || frozen {
            progress = LinesPathsGeometry.staticProgress
            applyProgress()
        }
    }

    private func tick(_ delta: Float) {
        guard animating, !frozen else { return }
        progress = (progress + delta / LinesPathsGeometry.lapSeconds).truncatingRemainder(dividingBy: 1)
        applyProgress()
    }

    private func applyProgress() {
        let length = LinesPathsGeometry.totalLength(routePoints, closed: true)
        let distance = progress * length
        marker.position = LinesPathsGeometry.sample(routePoints, closed: true, at: distance)
        let trailLength = length * LinesPathsGeometry.trailFraction
        trail.updateGeometry(points: LinesPathsGeometry.span(routePoints, closed: true, from: distance - trailLength,
                                                          length: trailLength, samples: LinesPathsGeometry.trailSamples),
                             radius: strokeRadius * 1.35)
        let ground = LinesPathsGeometry.dashes(LinesPathsGeometry.groundTrack, closed: true,
                                            count: LinesPathsGeometry.dashCount,
                                            dutyCycle: LinesPathsGeometry.dashDutyCycle,
                                            phase: progress, samples: LinesPathsGeometry.dashSamples)
        for (dash, points) in zip(dashes, ground) {
            dash.updateGeometry(points: points, radius: strokeRadius * LinesPathsGeometry.groundStrokeRatio)
        }
    }
}

// MARK: - Scene

/// The three curve families, in increasing order of smoothness.
enum CurveKind: CaseIterable, Identifiable {
    case linear, rounded, smooth

    var id: Self { self }

    var label: String {
        switch self {
        case .linear: "Polyline"
        case .rounded: "Rounded"
        case .smooth: "Spline"
        }
    }
}

/// Geometry, arc-length sampling and framing — Android's `LinesPathsScene`, value for value.
enum LinesPathsGeometry {
    static let controlCount = 8
    static let loopRadius: Float = 0.62
    static let loopWave: Float = 0.20
    static let groundY: Float = -0.42
    static let sceneLift: Float = 0.11
    static let samplesPerSpan = 24
    static let filletFraction: Float = 0.35
    static let dashCount = 16
    static let dashDutyCycle: Float = 0.55
    static let dashSamples = 3
    static let trailFraction: Float = 0.10
    static let trailSamples = 14
    static let markerRadius: Float = 0.045
    static let pointRadius: Float = 0.032
    static let staticProgress: Float = 0.22
    static let lapSeconds: Float = 9
    static let minStrokeMillimetres: Float = 8
    static let maxStrokeMillimetres: Float = 36
    static let defaultStrokeMillimetres: Float = 16
    static let tubeSegments = 10
    static let groundStrokeRatio: Float = 1
    static let cameraDistance: Float = 3.9
    static let elevationRatio: Float = 0.46

    static let cameraPose = SceneCameraPose(azimuth: 0, elevation: asin(elevationRatio),
                                            distance: cameraDistance, target: .zero)

    /// Eight points on a circle, lifted by a two-period sine so the loop is truly 3D.
    static let controlPoints: [SIMD3<Float>] = (0..<controlCount).map { i in
        let angle = Float(i) / Float(controlCount) * 2 * .pi
        return [cos(angle) * loopRadius, sin(2 * angle) * loopWave, sin(angle) * loopRadius]
    }

    static let groundTrack: [SIMD3<Float>] = controlPoints.map { [$0.x, groundY, $0.z] }

    /// A stroke diameter in millimetres as a tube radius in metres.
    static func strokeRadius(_ millimetres: Float) -> Float { millimetres / 2000 }

    static func route(_ kind: CurveKind) -> [SIMD3<Float>] {
        switch kind {
        case .linear: linear(controlPoints)
        case .rounded: rounded(controlPoints)
        case .smooth: smooth(controlPoints)
        }
    }

    /// Each side subdivided, half-open, so every control point stays a sharp corner.
    static func linear(_ points: [SIMD3<Float>], segments: Int = samplesPerSpan) -> [SIMD3<Float>] {
        points.indices.flatMap { i in
            let from = points[i]
            let to = points[(i + 1) % points.count]
            return (0..<segments).map { s in from + (to - from) * (Float(s) / Float(segments)) }
        }
    }

    /// A closed centripetal Catmull-Rom spline through every point.
    static func smooth(_ points: [SIMD3<Float>], segments: Int = samplesPerSpan) -> [SIMD3<Float>] {
        let padded = [points[points.count - 1]] + points + [points[0], points[1]]
        var result: [SIMD3<Float>] = []
        for i in 0..<(padded.count - 3) {
            for s in 0..<segments {
                result.append(catmullRom(padded[i], padded[i + 1], padded[i + 2], padded[i + 3],
                                         t: Float(s) / Float(segments)))
            }
        }
        // The final sample would land back on points[0], which the closed tube draws anyway.
        return result
    }

    /// Every corner replaced by a quadratic Bezier fillet.
    static func rounded(_ points: [SIMD3<Float>], fraction: Float = filletFraction,
                        segments: Int = samplesPerSpan) -> [SIMD3<Float>] {
        let count = points.count
        return (0..<count).flatMap { i in
            let corner = points[i]
            let entry = corner + (points[(i - 1 + count) % count] - corner) * fraction
            let exit = corner + (points[(i + 1) % count] - corner) * fraction
            return (0..<segments).map { s in
                let t = Float(s) / Float(segments - 1)
                let u = 1 - t
                return u * u * entry + 2 * u * t * corner + t * t * exit
            }
        }
    }

    static func totalLength(_ points: [SIMD3<Float>], closed: Bool) -> Float {
        guard points.count >= 2 else { return 0 }
        let segments = closed ? points.count : points.count - 1
        return (0..<segments).reduce(0) { $0 + simd_distance(points[$1], points[($1 + 1) % points.count]) }
    }

    /// The point `distance` metres along the path; wraps when closed, clamps otherwise.
    static func sample(_ points: [SIMD3<Float>], closed: Bool, at distance: Float) -> SIMD3<Float> {
        let total = totalLength(points, closed: closed)
        guard total > 0 else { return points.first ?? .zero }
        var remaining = closed
            ? (distance.truncatingRemainder(dividingBy: total) + total).truncatingRemainder(dividingBy: total)
            : min(max(distance, 0), total)
        let segments = closed ? points.count : points.count - 1
        for i in 0..<segments {
            let from = points[i]
            let to = points[(i + 1) % points.count]
            let length = simd_distance(from, to)
            if remaining <= length || length <= 0 {
                return from + (to - from) * (length > 0 ? remaining / length : 0)
            }
            remaining -= length
        }
        return points[closed ? 0 : points.count - 1]
    }

    /// `samples` points evenly spaced over `length` metres from `start`: a fixed count, so a
    /// span rebuilt every frame keeps the same vertex count.
    static func span(_ points: [SIMD3<Float>], closed: Bool, from start: Float, length: Float,
                     samples: Int) -> [SIMD3<Float>] {
        (0..<samples).map { i in
            sample(points, closed: closed, at: start + length * Float(i) / Float(samples - 1))
        }
    }

    /// `count` evenly pitched dashes; `phase` is in pitches, so 0…1 loops seamlessly.
    static func dashes(_ points: [SIMD3<Float>], closed: Bool, count: Int, dutyCycle: Float,
                       phase: Float, samples: Int) -> [[SIMD3<Float>]] {
        let pitch = totalLength(points, closed: closed) / Float(count)
        return (0..<count).map { i in
            span(points, closed: closed, from: (phase + Float(i)) * pitch, length: pitch * dutyCycle,
                 samples: samples)
        }
    }

    /// Centripetal (α = ½) Catmull-Rom between `p1` and `p2`, Barry-Goldman form.
    private static func catmullRom(_ p0: SIMD3<Float>, _ p1: SIMD3<Float>, _ p2: SIMD3<Float>,
                                   _ p3: SIMD3<Float>, t: Float) -> SIMD3<Float> {
        func knot(_ ti: Float, _ a: SIMD3<Float>, _ b: SIMD3<Float>) -> Float { ti + sqrt(simd_distance(a, b)) }
        let k0: Float = 0
        var k1 = knot(k0, p0, p1)
        var k2 = knot(k1, p1, p2)
        var k3 = knot(k2, p2, p3)
        if k1 <= k0 { k1 = k0 + 1 }
        if k2 <= k1 { k2 = k1 + 1 }
        if k3 <= k2 { k3 = k2 + 1 }
        let x = k1 + t * (k2 - k1)
        func lerp(_ a: SIMD3<Float>, _ b: SIMD3<Float>, _ ta: Float, _ tb: Float) -> SIMD3<Float> {
            let w = (tb - x) / (tb - ta)
            return a * w + b * (1 - w)
        }
        let a1 = lerp(p0, p1, k0, k1)
        let a2 = lerp(p1, p2, k1, k2)
        let a3 = lerp(p2, p3, k2, k3)
        return lerp(lerp(a1, a2, k0, k2), lerp(a2, a3, k1, k3), k1, k2)
    }
}
