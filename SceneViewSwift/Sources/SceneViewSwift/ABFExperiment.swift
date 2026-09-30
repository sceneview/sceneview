// ABF-EXPERIMENT — temporary, NEVER COMMIT.
#if os(iOS)
import UIKit
import RealityKit
import ARKit

enum ABFExperiment {
    static let fix: Int = {
        let a = CommandLine.arguments
        if let i = a.firstIndex(of: "-abf_fix"), i + 1 < a.count { return Int(a[i + 1]) ?? 0 }
        return 0
    }()
    static func log(_ s: String) {
        NotificationCenter.default.post(name: Notification.Name("abf.sdklog"), object: s)
    }
    static func metalLayers(in layer: CALayer, into out: inout [CAMetalLayer]) {
        if let m = layer as? CAMetalLayer { out.append(m) }
        for sub in layer.sublayers ?? [] { metalLayers(in: sub, into: &out) }
    }
}

final class ABFExperimentARView: ARView {
    private func describe(_ tag: String) {
        var layers: [CAMetalLayer] = []
        ABFExperiment.metalLayers(in: layer, into: &layers)
        let d = layers.map { "\(Int($0.drawableSize.width))x\(Int($0.drawableSize.height))/pwt=\($0.presentsWithTransaction)" }.joined(separator: ",")
        ABFExperiment.log("SDK \(tag) \(Unmanaged.passUnretained(self).toOpaque()) win=\(window != nil) bounds=\(bounds.size) metal=\(d)")
    }

    private func forceNoTransaction() {
        var layers: [CAMetalLayer] = []
        ABFExperiment.metalLayers(in: layer, into: &layers)
        for m in layers where m.presentsWithTransaction { m.presentsWithTransaction = false }
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        describe("didMoveToWindow")
        guard window != nil else { return }
        let fix = ABFExperiment.fix
        if fix & 1 != 0 { forceNoTransaction(); describe("didMoveToWindow+fix1") }
        if fix & 2 != 0, let config = session.configuration {
            session.run(config)
            ABFExperiment.log("SDK fix2 session re-run")
        }
        if fix & 8 != 0 {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
                self?.describe("t+0.5")
                self?.forceNoTransaction()
            }
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        describe("layoutSubviews")
        if ABFExperiment.fix & 1 != 0 { forceNoTransaction() }
    }
}
#endif
