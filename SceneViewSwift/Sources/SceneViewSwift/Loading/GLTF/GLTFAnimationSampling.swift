import Foundation
import simd

// Pure animation and skeleton math, kept free of RealityKit so it is unit-testable:
// keyframe sampling (LINEAR / STEP / CUBICSPLINE), whole-scene poses at a time, and the
// mapping from a glTF skin to RealityKit's parent-first joint list.

extension GLTFAnimationChannel {

    /// Number of floats in one value: 4 for a rotation quaternion, else 3.
    var width: Int { path == .rotation ? 4 : 3 }

    /// The channel's value at time `t`, clamped to the first/last key outside its range.
    /// Rotations come back as a normalized `(x, y, z, w)` quaternion.
    func sample(at t: Float) -> SIMD4<Float> {
        let count = times.count
        guard count > 0 else { return .zero }
        if count == 1 || t <= times[0] { return value(key: 0) }
        if t >= times[count - 1] { return value(key: count - 1) }

        // Largest k with times[k] <= t.
        var lo = 0, hi = count - 1
        while hi - lo > 1 {
            let mid = (lo + hi) / 2
            if times[mid] <= t { lo = mid } else { hi = mid }
        }
        let t0 = times[lo], t1 = times[lo + 1]
        let dt = t1 - t0
        let s = dt > 0 ? (t - t0) / dt : 0

        switch interpolation {
        case .step:
            return value(key: lo)
        case .linear:
            let a = value(key: lo), b = value(key: lo + 1)
            if path == .rotation {
                return Self.slerp(a, b, s)
            }
            return a + (b - a) * s
        case .cubicSpline:
            let v0 = value(key: lo), v1 = value(key: lo + 1)
            let out0 = element(key: lo, slot: 2), in1 = element(key: lo + 1, slot: 0)
            let s2 = s * s, s3 = s2 * s
            var result = (2 * s3 - 3 * s2 + 1) * v0
                + (s3 - 2 * s2 + s) * dt * out0
                + (-2 * s3 + 3 * s2) * v1
                + (s3 - s2) * dt * in1
            if path == .rotation {
                let length = simd_length(result)
                result = length > 0 ? result / length : SIMD4(0, 0, 0, 1)
            } else {
                result.w = 0
            }
            return result
        }
    }

    /// The keyframe value — slot 1 of a cubic-spline triple.
    private func value(key: Int) -> SIMD4<Float> {
        element(key: key, slot: interpolation == .cubicSpline ? 1 : 0)
    }

    private func element(key: Int, slot: Int) -> SIMD4<Float> {
        let stride = interpolation == .cubicSpline ? width * 3 : width
        let base = key * stride + slot * width
        guard base + width <= values.count else { return .zero }
        if width == 4 {
            return SIMD4(values[base], values[base + 1], values[base + 2], values[base + 3])
        }
        return SIMD4(values[base], values[base + 1], values[base + 2], 0)
    }

    /// Shortest-arc spherical interpolation of two `(x, y, z, w)` quaternions.
    static func slerp(_ a: SIMD4<Float>, _ b: SIMD4<Float>, _ s: Float) -> SIMD4<Float> {
        let qa = simd_quatf(vector: a), qb = simd_quatf(vector: b)
        let target = simd_dot(qa, qb) < 0 ? -qb : qb
        return simd_normalize(simd_slerp(qa, target, s)).vector
    }
}

extension GLTFScene {

    /// Every node's local transform at time `t` of `animation`: rest values, overridden
    /// by whatever the animation's channels drive.
    func localTransforms(animation: GLTFAnimation, at t: Float) -> [GLTFTransform] {
        var locals = nodes.map(\.rest)
        for channel in animation.channels where channel.node < locals.count {
            let v = channel.sample(at: t)
            switch channel.path {
            case .translation: locals[channel.node].translation = SIMD3(v.x, v.y, v.z)
            case .scale: locals[channel.node].scale = SIMD3(v.x, v.y, v.z)
            case .rotation: locals[channel.node].rotation = simd_quatf(vector: v)
            }
        }
        return locals
    }

    /// The nodes an animation moves.
    func animatedNodes(_ animation: GLTFAnimation) -> Set<Int> {
        Set(animation.channels.map(\.node))
    }

    /// Whether `node` is `ancestor` or below it.
    func isDescendant(_ node: Int, of ancestor: Int) -> Bool {
        var current: Int? = node
        while let c = current {
            if c == ancestor { return true }
            current = nodes[safe: c]?.parent
        }
        return false
    }
}

/// A glTF skin laid out the way `MeshResource.Skeleton` wants it.
///
/// glTF lists a skin's joints in any order and lets non-joint nodes sit between two
/// joints; RealityKit wants parents before children and a parent index per joint. So
/// joints are sorted by depth, each joint's RealityKit parent is its nearest ancestor
/// that is also a joint, and its local transform is measured against that ancestor —
/// which folds any in-between nodes in. Root joints are measured against the scene root,
/// because the skinned entity itself sits at the scene root (glTF ignores the skinned
/// mesh node's own transform).
struct GLTFSkeletonLayout: Sendable {
    /// Node index of each RealityKit joint, parents first.
    let jointNodes: [Int]
    /// RealityKit parent of each joint (index into ``jointNodes``).
    let parentIndices: [Int?]
    /// glTF joint slot (index into `skin.joints`) → RealityKit joint index.
    let remap: [Int]
    /// Inverse bind matrices in RealityKit order.
    let inverseBindMatrices: [simd_float4x4]
    /// Joint names in RealityKit order — the node names, unique by construction.
    let names: [String]

    init(skin: GLTFSkin, scene: GLTFScene) {
        func depth(_ node: Int) -> Int {
            var d = 0
            var current = scene.nodes[safe: node]?.parent
            while let c = current { d += 1; current = scene.nodes[safe: c]?.parent }
            return d
        }
        let slots = skin.joints.indices.sorted { a, b in
            let da = depth(skin.joints[a]), db = depth(skin.joints[b])
            return da != db ? da < db : a < b
        }
        let nodes = slots.map { skin.joints[$0] }
        var indexOfNode: [Int: Int] = [:]
        for (i, n) in nodes.enumerated() where indexOfNode[n] == nil { indexOfNode[n] = i }

        var parents: [Int?] = []
        for node in nodes {
            var ancestor = scene.nodes[safe: node]?.parent
            var found: Int?
            while let a = ancestor {
                if let i = indexOfNode[a] { found = i; break }
                ancestor = scene.nodes[safe: a]?.parent
            }
            parents.append(found)
        }

        var remap = [Int](repeating: 0, count: skin.joints.count)
        for (rkIndex, slot) in slots.enumerated() { remap[slot] = rkIndex }

        jointNodes = nodes
        parentIndices = parents
        self.remap = remap
        inverseBindMatrices = slots.map { skin.inverseBindMatrices[safe: $0] ?? matrix_identity_float4x4 }
        names = nodes.map { scene.nodes[$0].name }
    }

    /// Joint-local matrices (relative to the RealityKit parent joint, or the scene root)
    /// for a pose given as world matrices of every node.
    func jointLocalMatrices(world: [simd_float4x4]) -> [simd_float4x4] {
        jointNodes.enumerated().map { i, node in
            guard let parent = parentIndices[i] else { return world[node] }
            return world[jointNodes[parent]].inverse * world[node]
        }
    }
}
