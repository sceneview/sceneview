import SwiftUI
import RealityKit
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
    @State private var stressAborted = false
    @StateObject private var fps = FPSCounter()

    // Fixed grid layout dimensions — positions are stable regardless of
    // current sphere count so add/remove doesn't re-centre existing entities.
    private static let maxCount = 1_000
    private static let cols  = 10
    private static let rows  = 10
    private static let layers = 10
    private static let spacing: Float = 0.15
    private static let radius: Float  = 0.035

    // MARK: — Body

    var body: some View {
        ZStack {
            sphereScene
                .ignoresSafeArea()

            VStack(alignment: .leading) {
                statsOverlay
                    .padding([.top, .horizontal])
                Spacer()
                controlsOverlay
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
                    .padding()
            }
        }
        .navigationTitle("Debug Overlay")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear  { fps.start() }
        .onDisappear { fps.stop() }
        .task(id: targetCount)     { await spawnProgressively() }
        .task(id: isStressRunning) { await runStressRamp() }
    }

    // MARK: — 3D scene

    @ViewBuilder
    private var sphereScene: some View {
        RealityView { content in
            let root = Entity()
            root.name = "sphereRoot"
            content.add(root)

            // Directional light so spheres are shaded — without an explicit
            // light they render as flat silhouettes against the grey backdrop.
            var lightComponent = DirectionalLightComponent(color: .white,
                                                           intensity: 1_500,
                                                           isRealWorldProxy: false)
            lightComponent.canCastShadow = false
            let lightEntity = Entity()
            lightEntity.components.set(lightComponent)
            lightEntity.orientation = simd_quatf(angle: -.pi / 4, axis: [1, 0, 0])
            content.add(lightEntity)
        } update: { content in
            guard let root = content.entities.first(where: { $0.name == "sphereRoot" })
            else { return }
            let existing = root.children.count
            if existing < currentCount {
                for i in existing..<currentCount {
                    root.addChild(DebugOverlayDemo.makeSphere(index: i))
                }
            } else if existing > currentCount {
                root.children.suffix(existing - currentCount).forEach { root.removeChild($0) }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: — Stats overlay

    @ViewBuilder
    private var statsOverlay: some View {
        VStack(alignment: .leading, spacing: 2) {
            let fpsValue = fps.currentFPS
            let fpsColor: Color = fpsValue >= 55 ? .green : fpsValue >= 30 ? .yellow : .red
            Text(String(format: "FPS: %.1f", fpsValue)).foregroundStyle(fpsColor)
            Text(String(format: "Frame: %.1f ms", fps.frameTimeMs))
            Text("Nodes: \(currentCount)")
            Text("Tris: ≈\(formatThousands(Int64(currentCount) * 768))")
            sparklineView
                .frame(height: 28)
        }
        .font(.system(.caption, design: .monospaced))
        .foregroundStyle(.white)
        .padding(8)
        .background(.black.opacity(0.65))
        .frame(maxWidth: .infinity, alignment: .leading)
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
                with: .color(.white.opacity(0.3)),
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
            context.stroke(linePath, with: .color(.green), lineWidth: 2)
        }
    }

    // MARK: — Controls overlay

    @ViewBuilder
    private var controlsOverlay: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Nodes: \(currentCount) / \(targetCount)")
                .font(.caption.bold())
                .padding(.horizontal, 16)
                .padding(.top, 12)

            if currentCount < targetCount {
                ProgressView(value: Float(currentCount), total: Float(max(targetCount, 1)))
                    .padding(.horizontal, 16)
                Text("Spawning \(currentCount) / \(targetCount)…")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 16)
            }
            if stressAborted {
                Text("Stress test aborted (device limit).")
                    .font(.caption2)
                    .foregroundStyle(.red)
                    .padding(.horizontal, 16)
            }

            let busy = currentCount < targetCount || isStressRunning
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach([1, 10, 100, 500, 1_000], id: \.self) { preset in
                        Button(action: { stressAborted = false; targetCount = preset }) {
                            Text("\(preset)").font(.caption)
                        }
                        .buttonStyle(.bordered)
                        .disabled(busy)
                    }
                    Button("Reset") { stressAborted = false; targetCount = 1 }
                        .buttonStyle(.bordered)
                        .disabled(busy)
                }
                .padding(.horizontal, 16)
            }

            Button {
                stressAborted = false
                isStressRunning.toggle()
                if isStressRunning { targetCount = 1 }
            } label: {
                Text(isStressRunning ? "Stop stress test" : "Stress test (1 → 1 000)")
                    .font(.caption)
            }
            .buttonStyle(.borderedProminent)
            .padding(.horizontal, 16)
            .padding(.bottom, 12)
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

    // MARK: — Sphere factory (static, fixed grid layout)

    private static func makeSphere(index i: Int) -> ModelEntity {
        let col   = i % cols
        let row   = (i / cols) % rows
        let layer = i / (cols * rows)
        let xOff  = -(Float(cols  - 1) / 2) * spacing
        let yOff  = -(Float(rows  - 1) / 2) * spacing
        let zOff  =  (Float(layers - 1) / 2) * spacing
        var mat = SimpleMaterial(color: .systemBlue, isMetallic: false)
        mat.roughness = MaterialScalarParameter(floatLiteral: 0.5)
        let mesh   = MeshResource.generateSphere(radius: radius)
        let entity = ModelEntity(mesh: mesh, materials: [mat])
        entity.position = [
            Float(col)   * spacing + xOff,
            Float(row)   * spacing + yOff,
            -(Float(layer) * spacing) + zOff - 1.2
        ]
        return entity
    }
}

// MARK: — FPS Counter

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
