import SwiftUI
import RealityKit
import SceneViewSwift
#if canImport(UIKit)
import UIKit
#endif

/// iOS equivalent of Android's `DebugOverlayDemo` — real-time performance stress test.
///
/// Spawns RealityKit `ModelEntity` spheres progressively and shows live FPS,
/// frame time, node count, and estimated triangle count via a custom SwiftUI
/// overlay (including a rolling FPS sparkline). A stress-test button ramps from
/// 1 → 1 000 spheres over 10 s so you can watch the FPS drop in real time.
///
/// Triangle estimate: each `MeshResource.generateSphere` at the default detail
/// level produces ~768 triangles (12 subdivisions × 2 tris/face × 32 faces).
/// The exact value is device-dependent; the estimate is intentionally honest.
///
/// Coverage: `sceneview://demo/debug-overlay`
struct DebugOverlayDemo: View {

    // MARK: — State

    @State private var targetCount = 1
    @State private var currentCount = 0
    @State private var isStressRunning = false
    @StateObject private var fps = FPSCounter()
    @State private var stage = DebugOverlayStage()
    @State private var cameraPose: SceneCameraPose?
    @State private var viewSize: CGSize = .zero
    @Environment(\.colorScheme) private var colorScheme

    private static let maxCount = 1_000

    // MARK: — Body

    private static let presets = [1, 10, 100, 500, 1_000]

    private var busy: Bool { currentCount < targetCount || isStressRunning }

    var body: some View {
        GeometryReader { proxy in
            sphereScene
                .onAppear { viewSize = proxy.size; reframe() }
                .onChange(of: proxy.size) { _, size in viewSize = size; reframe() }
        }
        .ignoresSafeArea()
        // The stats HUD and the node-count strip ride the scaffold's
        // accessory cluster; the stress ramp is a dock item and Reset is
        // the sheet's. The old `.regularMaterial` card was near-white in
        // dark mode and the screen had no back button (#3766 P2 §3, §6).
        .demoChrome(
            dock: [
                DockItem(icon: isStressRunning ? "stop.fill" : "gauge.with.needle",
                         label: isStressRunning ? "Stop" : "Stress test",
                         caption: isStressRunning ? nil : "1 → 1 000",
                         selected: isStressRunning) {
                    isStressRunning.toggle()
                    if isStressRunning { targetCount = 1 }
                }
            ],
            onReset: { isStressRunning = false; targetCount = 1 },
            accessory: {
                VStack(spacing: SceneViewTokens.Chrome.clusterGap) {
                    statsOverlay
                    DemoOptionStrip(Self.presets, selection: $targetCount) { "\($0)" }
                        .disabled(busy)
                        .opacity(busy ? 0.6 : 1)
                }
            }
        )
        .onAppear {
            fps.start()
            stage.setDark(colorScheme == .dark)
            // `-debug_overlay_count <n>` opens on a preset, so a capture can
            // show the 1 000-node framing without a tap.
            let preset = UserDefaults.standard.integer(forKey: "debug_overlay_count")
            if Self.presets.contains(preset) { targetCount = preset }
        }
        .onDisappear { fps.stop() }
        .onChange(of: colorScheme) { _, scheme in stage.setDark(scheme == .dark) }
        .onChange(of: currentCount) { _, count in stage.sync(count: count) }
        .onChange(of: targetCount) { _, _ in reframe() }
        .task(id: targetCount)     { await spawnProgressively() }
        .task(id: isStressRunning) { await runStressRamp() }
    }

    // MARK: — 3D scene

    /// Android's Debug Overlay stage (#4083): the spheres stand on a ruled floor
    /// under the themed stage sky, seen from above and off-axis so the layers
    /// read as a volume. A bare RealityView left the single sphere of the "1"
    /// preset at the screen edge, half behind the stats card, over an empty
    /// stage — which read as a broken sample.
    private var sphereScene: some View {
        ZStack {
            LinearGradient(colors: [SceneViewTokens.Stage.skyHorizon,
                                    SceneViewTokens.Stage.skyGround],
                           startPoint: .top, endPoint: .bottom)
            SceneView { root in stage.install(in: root) }
                .environment(Self.environment)
                .cameraControls(.orbit)
                // The grid is centred by construction; re-centring on its
                // bounds as spheres spawn would slide the stage every batch.
                .autoCenterContent(false)
                .cameraPose(cameraPose)
        }
    }

    private static var environment: SceneEnvironment {
        var studio = SceneEnvironment.studio
        studio.showSkybox = false
        return studio
    }

    private func reframe() {
        guard viewSize.width > 0, viewSize.height > 0 else { return }
        cameraPose = DebugOverlayStage.framingPose(count: targetCount, in: viewSize)
    }

    // MARK: — Stats overlay

    @ViewBuilder
    private var statsOverlay: some View {
        VStack(alignment: .leading, spacing: 2) {
            let fpsValue = fps.currentFPS
            let fpsColor: Color = fpsValue >= 55 ? SceneViewTokens.ARChrome.success
                : fpsValue >= 30 ? SceneViewTokens.ARChrome.warning
                : SceneViewTokens.ARChrome.danger
            Text(String(format: "FPS: %.1f", fpsValue)).foregroundStyle(fpsColor)
            Text(String(format: "Frame: %.1f ms", fps.frameTimeMs))
            Text("Nodes: \(currentCount) / \(targetCount)")
            Text("Tris: ≈\(formatThousands(Int64(currentCount) * 768))")
            sparklineView
                .frame(height: 28)
        }
        .font(.system(.caption, design: .monospaced))
        .foregroundStyle(SceneViewTokens.Glass.onGlass)
        .padding(.horizontal, SceneViewTokens.Glass.pillPaddingHorizontal)
        .padding(.vertical, SceneViewTokens.Space.sm)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassBackground(in: RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg,
                                              style: .continuous))
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var sparklineView: some View {
        Canvas { context, size in
            let history = fps.history
            guard history.count > 1 else { return }
            let maxFPS: CGFloat = 120
            // 60 fps reference line
            let refY = size.height - (60.0 / maxFPS) * size.height
            context.stroke(
                Path { p in
                    p.move(to: CGPoint(x: 0, y: refY))
                    p.addLine(to: CGPoint(x: size.width, y: refY))
                },
                with: .color(SceneViewTokens.Glass.onGlassDisabled),
                lineWidth: 1
            )
            // FPS sparkline
            var linePath = Path()
            for (i, value) in history.enumerated() {
                let x = size.width * CGFloat(i) / CGFloat(history.count - 1)
                let y = size.height - (value / maxFPS) * size.height
                if i == 0 { linePath.move(to: CGPoint(x: x, y: y)) }
                else { linePath.addLine(to: CGPoint(x: x, y: y)) }
            }
            context.stroke(linePath, with: .color(SceneViewTokens.ARChrome.success), lineWidth: 2)
        }
    }

    // MARK: — Progressive spawn task

    private func spawnProgressively() async {
        if currentCount > targetCount { currentCount = targetCount; return }
        do {
            while currentCount < targetCount {
                currentCount = min(currentCount + 16, targetCount)
                try await Task.sleep(nanoseconds: 16_000_000)
            }
        } catch { /* task cancelled by new targetCount */ }
    }

    // MARK: — Stress ramp task

    private func runStressRamp() async {
        guard isStressRunning else { return }
        let start = Date()
        let duration: TimeInterval = 10
        do {
            while isStressRunning {
                let progress = min(Date().timeIntervalSince(start) / duration, 1.0)
                let want = 1 + Int(Double(Self.maxCount - 1) * progress)
                if want != targetCount { targetCount = want }
                if progress >= 1.0 { isStressRunning = false; break }
                try await Task.sleep(nanoseconds: 50_000_000)
            }
        } catch { /* task cancelled by stop button */ }
    }
}

// MARK: — Stage

/// The spheres, the floor they stand on and the camera framing, shared with
/// Android's `DebugOverlayDemo` (#4083): 10 × 10 × N grid at 0.18 m spacing,
/// centred on its occupied extent so one sphere sits on the centre of the
/// floor, the bottom row just above the floor, seen from 22° above at 35°
/// azimuth (Android's `qa_mode` rest angle).
@MainActor
final class DebugOverlayStage {
    static let spacing: Float = 0.18
    static let radius: Float = 0.04
    private static let floorGap: Float = 0.02
    private static let gridHalfLines = 15
    private static let gridLineWidth: Float = 0.006
    private static let gridLineHeight: Float = 0.002
    private static let elevation: Float = 22 * .pi / 180
    private static let azimuth: Float = 35 * .pi / 180
    /// Points under the stats card, node-count strip and dock: the spheres are
    /// framed in the band above them, not behind the card.
    private static let hudReserve: CGFloat = 320

    private let root = Entity()
    private let spheres = Entity()
    private var floor: Entity?
    private var dark = false
    private var count = 0
    private var built = false
    private lazy var sphereMesh = MeshResource.generateSphere(radius: Self.radius)
    private lazy var sphereMaterial: SimpleMaterial = {
        var material = SimpleMaterial(color: SceneViewTokens.Stage.shapeRamp[0], isMetallic: false)
        material.roughness = MaterialScalarParameter(floatLiteral: 0.4)
        return material
    }()

    func install(in scene: Entity) {
        if !built {
            root.addChild(spheres)
            buildFloor()
            built = true
            sync(count: count)
        }
        root.removeFromParent()
        scene.addChild(root)
    }

    func setDark(_ dark: Bool) {
        guard dark != self.dark || floor == nil else { return }
        self.dark = dark
        if built { buildFloor() }
    }

    /// Brings the sphere count to `count` and lays the grid out for it.
    func sync(count: Int) {
        self.count = count
        guard built else { return }
        let existing = spheres.children.count
        if existing < count {
            for _ in existing..<count {
                spheres.addChild(ModelEntity(mesh: sphereMesh, materials: [sphereMaterial]))
            }
        } else if existing > count {
            Array(spheres.children.suffix(existing - count)).forEach { $0.removeFromParent() }
        }
        let grid = Self.grid(for: count)
        let xOffset = -Float(grid.cols - 1) / 2 * Self.spacing
        let yOffset = -Float(grid.rows - 1) / 2 * Self.spacing + Self.centreHeight(count: count)
        let zOffset = Float(grid.layers - 1) / 2 * Self.spacing
        for (i, sphere) in spheres.children.enumerated() {
            let col = i % grid.cols
            let row = (i / grid.cols) % grid.rows
            let layer = i / (grid.cols * grid.rows)
            sphere.position = SIMD3(Float(col) * Self.spacing + xOffset,
                                    Float(row) * Self.spacing + yOffset,
                                    -Float(layer) * Self.spacing + zOffset)
        }
    }

    /// Columns, rows and layers actually occupied by `count` spheres.
    static func grid(for count: Int) -> (cols: Int, rows: Int, layers: Int) {
        let n = max(count, 1)
        let cols = min(10, n)
        let rows = max(1, min(10, (n + cols - 1) / cols))
        let layers = max(1, (n + cols * rows - 1) / (cols * rows))
        return (cols, rows, layers)
    }

    /// Height of the grid's centre above the floor.
    static func centreHeight(count: Int) -> Float {
        Float(grid(for: count).rows - 1) / 2 * spacing + radius + floorGap
    }

    /// The orbit pose that fits the grid for `count` spheres in the band
    /// between the top scrim and the stats card, from Android's rest angle.
    static func framingPose(count: Int, in size: CGSize) -> SceneCameraPose {
        let grid = grid(for: count)
        let extent = SIMD3<Float>(Float(grid.cols - 1) * spacing + 2 * radius,
                                  Float(grid.rows - 1) * spacing + 2 * radius,
                                  Float(grid.layers - 1) * spacing + 2 * radius)
        let target = SIMD3<Float>(0, centreHeight(count: count), 0)
        let width = Float(max(size.width, 1))
        let height = Float(max(size.height, 1))
        let top = Float(SceneViewTokens.Chrome.scrimTop) * 0.6
        let bottom = Float(hudReserve)
        let band = max(height - top - bottom, height * 0.3)
        // One sphere alone would fill the band; keep it at Android's single-
        // sphere scale, a small ball on a wide floor.
        let fill: Float = count <= 1 ? 0.12 : 0.9
        let tanV = tan(Float.pi / 6)
        let tanH = tanV * width / height
        let tanBand = tanV * band / height
        let back = SIMD3<Float>(sin(azimuth) * cos(elevation), sin(elevation),
                                cos(azimuth) * cos(elevation))
        let right = SIMD3<Float>(cos(azimuth), 0, -sin(azimuth))
        let up = simd_cross(back, right)
        var distance: Float = 1
        for sx in [-1, 1] as [Float] {
            for sy in [-1, 1] as [Float] {
                for sz in [-1, 1] as [Float] {
                    let corner = extent / 2 * SIMD3(sx, sy, sz)
                    let depth = simd_dot(corner, back)
                    distance = max(distance, depth + abs(simd_dot(corner, right)) / (tanH * fill))
                    distance = max(distance, depth + abs(simd_dot(corner, up)) / (tanBand * fill))
                }
            }
        }
        // Centre the grid on the band, not on the screen: move the look-at
        // point along the camera's up axis by the band's offset.
        let bandCentreOffset = (top + band / 2) - height / 2
        let shift = bandCentreOffset / (height / 2) * distance * tanV
        return SceneCameraPose(azimuth: azimuth, elevation: elevation, distance: distance,
                               target: target + up * shift)
    }

    /// The floor and its measuring grid at the spheres' own spacing, in the
    /// colour scheme's stage colours (Android's `StageSky.floor` / `.grid`).
    private func buildFloor() {
        floor?.removeFromParent()
        let floor = Entity()
        let span = Self.spacing * Float(Self.gridHalfLines) * 2
        let slab = ModelEntity(
            mesh: .generateBox(width: span + Self.spacing * 2, height: 0.01, depth: span + Self.spacing * 2),
            materials: [SimpleMaterial(color: SceneViewTokens.Stage.trayFloor(dark: dark), isMetallic: false)]
        )
        slab.position.y = -0.005
        floor.addChild(slab)
        let lineColor = dark
            ? UIColor(red: 0x46 / 255, green: 0x51 / 255, blue: 0x6A / 255, alpha: 1)
            : UIColor(red: 0xD6 / 255, green: 0xDA / 255, blue: 0xE0 / 255, alpha: 1)
        let lineMaterial = SimpleMaterial(color: lineColor, isMetallic: false)
        let alongZ = MeshResource.generateBox(width: Self.gridLineWidth, height: Self.gridLineHeight, depth: span)
        let alongX = MeshResource.generateBox(width: span, height: Self.gridLineHeight, depth: Self.gridLineWidth)
        for i in -Self.gridHalfLines...Self.gridHalfLines {
            let offset = Float(i) * Self.spacing
            let z = ModelEntity(mesh: alongZ, materials: [lineMaterial])
            z.position = SIMD3(offset, Self.gridLineHeight / 2, 0)
            floor.addChild(z)
            let x = ModelEntity(mesh: alongX, materials: [lineMaterial])
            x.position = SIMD3(0, Self.gridLineHeight / 2, offset)
            floor.addChild(x)
        }
        root.addChild(floor)
        self.floor = floor
    }
}

// MARK: — FPS Counter

// CADisplayLink(target:selector:) is iOS/tvOS/visionOS only;
// macOS does not expose this initialiser. Provide a no-op stub on macOS so
// the demo still compiles for the macOS App Store target (#1794).
#if os(iOS) || os(tvOS) || targetEnvironment(macCatalyst)
private final class FPSCounter: ObservableObject {
    @Published var currentFPS: Double = 0
    @Published var frameTimeMs: Double = 0
    @Published var history: [CGFloat] = []

    private var link: CADisplayLink?
    private var frameCount = 0
    private var lastTime: CFTimeInterval = 0
    private var historyBuffer: [Float] = []

    func start() {
        guard link == nil else { return }
        lastTime = CACurrentMediaTime()
        let l = CADisplayLink(target: self, selector: #selector(tick(_:)))
        l.add(to: .main, forMode: .common)
        link = l
    }

    func stop() { link?.invalidate(); link = nil }

    @objc private func tick(_ dl: CADisplayLink) {
        frameCount += 1
        let now = dl.timestamp
        let delta = now - lastTime
        guard delta >= 0.5 else { return }
        let newFPS = Double(frameCount) / delta
        currentFPS = newFPS
        frameTimeMs = (delta / Double(frameCount)) * 1_000
        historyBuffer.append(Float(newFPS))
        if historyBuffer.count > 120 { historyBuffer.removeFirst() }
        history = historyBuffer.map { CGFloat($0) }
        frameCount = 0
        lastTime = now
    }

    deinit { stop() }
}
#else
private final class FPSCounter: ObservableObject {
    @Published var currentFPS: Double = 0
    @Published var frameTimeMs: Double = 0
    @Published var history: [CGFloat] = []
    func start() {}
    func stop() {}
}
#endif

// MARK: — Helpers

private func formatThousands(_ v: Int64) -> String {
    let s = String(v)
    guard s.count > 3 else { return s }
    var result = ""
    var i = s.startIndex
    let first = s.count % 3
    if first > 0 {
        result = String(s[i..<s.index(i, offsetBy: first)])
        i = s.index(i, offsetBy: first)
    }
    while i < s.endIndex {
        if !result.isEmpty { result += "," }
        let end = s.index(i, offsetBy: 3)
        result += s[i..<end]
        i = end
    }
    return result
}
