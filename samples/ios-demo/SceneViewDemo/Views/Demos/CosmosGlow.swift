import Accelerate
import Foundation
import Metal
import RealityKit
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif
import simd

// The Cosmos scenes without a single line of shader source.
//
// Android expands its sprites and strokes on the GPU (a vertex shader faces them to the camera)
// and paints them in a fragment shader. RealityKit's stock `UnlitMaterial` has neither hook, but
// it does have what the look depends on: an **additive** blend mode (iOS 18) and HDR textures.
// So the work moves to the CPU, once, at build time:
//
// - every sprite becomes a fixed quad facing the scene's camera, every stroke a fixed ribbon
//   widened across the camera's line of sight — the cameras only sway by a few degrees;
// - every sprite and every stroke owns a cell of a float16 texture atlas holding its colour ×
//   its glow profile (the gaussian of `cosmos_sprite.mat`, the round cross-section of
//   `cosmos_ribbon.mat`), already in linear HDR radiance.
//
// What the shaders animated per fragment becomes per-frame uniforms: the material tint for
// fades, and — for the burst's growing tracks — the index count, since the segments are sorted
// by the moment they appear.

/// Quads ready for a `LowLevelMesh`: packed float3 position + float2 uv per vertex.
struct GlowQuads: Sendable {
    var vertices: [Float] = []
    var indices: [UInt32] = []
    var boundsMin = SIMD3<Float>(repeating: .greatestFiniteMagnitude)
    var boundsMax = SIMD3<Float>(repeating: -.greatestFiniteMagnitude)
    /// For ribbons: one key per segment (six indices), ascending — the head value from which
    /// the segment is drawn. Empty for sprites.
    var revealKeys: [Float] = []
    /// For twinkling sprites: how many indices each phase group spans, in draw order. Each
    /// group gets its own material, so its tint can flicker out of step with the others.
    var groups: [Int] = []

    static let stride = 5

    /// `v` counts rows from the image's top, as `GlowImage` stores them; RealityKit's texture
    /// coordinates start at the bottom, so it is flipped here, once for every builder.
    fileprivate mutating func vertex(_ p: SIMD3<Float>, _ u: Float, _ v: Float) {
        vertices.append(contentsOf: [p.x, p.y, p.z, u, 1 - v])
        boundsMin = simd_min(boundsMin, p)
        boundsMax = simd_max(boundsMax, p)
    }
}

/// A linear-HDR RGBA image, top row first, uploaded as `rgba16Float` without mips.
struct GlowImage: Sendable {
    let width: Int
    let height: Int
    var pixels: [Float]
    /// Cell size in texels.
    let cell: Int

    init(width: Int, height: Int, cell: Int) {
        self.width = width
        self.height = height
        self.cell = cell
        pixels = [Float](repeating: 0, count: width * height * 4)
    }

    mutating func set(_ x: Int, _ y: Int, _ rgb: SIMD3<Float>, alpha: Float) {
        let i = (y * width + x) * 4
        pixels[i] = rgb.x
        pixels[i + 1] = rgb.y
        pixels[i + 2] = rgb.z
        pixels[i + 3] = alpha
    }
}

/// One glowing layer: its quads and the atlas they sample.
struct GlowLayer: Sendable {
    var quads: GlowQuads
    var image: GlowImage
    /// Sprites: `cosmos_sprite.mat`'s `twinkle`, the depth of each group's flicker, already
    /// baked into the atlas as a `1 + twinkle` headroom.
    var twinkle: Float = 0
    /// Dashed ribbons: the texture repeats along `u`, one dash cycle per unit, and scrolls by
    /// `dashSpeed / 2π` cycles a second.
    var dashSpeed: Float?
}

/// Where the camera looks from while a scene is on screen, used to face quads and ribbons
/// and to keep far points at a minimum on-screen size.
struct GlowView: Sendable {
    var eye: SIMD3<Float>
    /// Pixels per world unit at distance 1.
    var focal: Float
}

enum GlowBuilder {

    // MARK: Sprites (cosmos_sprite.mat, cosmos_dust.mat)

    /// Twinkling sprites flicker in this many phase groups, each a material of its own.
    static let twinkleGroups = 8

    /// How a sprite's light falls off from its centre.
    enum SpriteProfile: Sendable {
        /// A star: a tight gaussian core over a wide soft skirt, added onto what is behind.
        case glow
        /// A dust puff: a soft disc whose alpha is how much light it blocks.
        case dust

        func value(_ d2: Float) -> Float {
            switch self {
            case .glow: (exp(-d2 * 10) + 0.18 * exp(-d2 * 2.5)) * max(0, 1 - d2)
            case .dust: exp(-d2 * 3) * max(0, 1 - d2)
            }
        }
    }

    /// Faces each sprite of `mesh` towards `facing(center)` and paints its atlas cell.
    ///
    /// Sprites of the same colour share a cell — colours are rounded to 1/16 of an octave per
    /// channel, far below what the eye can tell — so 60,000 stars need a few thousand cells,
    /// not 60,000.
    /// - Parameters:
    ///   - facing: the direction a sprite at `center` looks along (towards the viewer).
    ///   - gain: radiance multiplier baked into the atlas (a tint can only scale down).
    ///   - twinkle: the shader's `1 + twinkle · sin(2.7 t + 2π phase)` flicker. Sprites are
    ///     split into `twinkleGroups` groups by phase, drawn with one material each.
    static func sprites(_ mesh: GlowMesh, view: GlowView, minPixels: Float, gain: Float = 1,
                        profile: SpriteProfile = .glow, twinkle: Float = 0,
                        facing: (SIMD3<Float>) -> SIMD3<Float>) -> GlowLayer {
        let s = mesh.stride
        let count = mesh.vertexCount / 4
        let cell = 16
        let gain = gain * (1 + twinkle)

        // Pass 1: each sprite's quad and the cell of its colour.
        var corners: [SIMD3<Float>] = []
        corners.reserveCapacity(count * 4)
        var spriteCell: [Int] = []
        spriteCell.reserveCapacity(count)
        var cellOf: [SIMD4<Int32>: Int] = [:]
        var cellColor: [SIMD4<Float>] = []
        for sprite in 0..<count {
            let v = sprite * 4 * s
            let center = SIMD3(mesh.vertices[v], mesh.vertices[v + 1], mesh.vertices[v + 2])
            let color = SIMD3(mesh.vertices[v + 3], mesh.vertices[v + 4], mesh.vertices[v + 5])
            let alpha = mesh.vertices[v + 6]
            var radius = mesh.vertices[v + 9]

            let depth = max(simd_length(view.eye - center), 1e-3)
            let radiusPx = radius * view.focal / depth
            var energy: Float = 1
            if radiusPx < minPixels {
                energy = (radiusPx * radiusPx) / (minPixels * minPixels)
                radius = minPixels * depth / view.focal
            }
            let (right, up) = basis(facing(center))
            corners.append(center + (-right - up) * radius)
            corners.append(center + (right - up) * radius)
            corners.append(center + (right + up) * radius)
            corners.append(center + (-right + up) * radius)

            let radiance = profile == .glow ? color * (energy * gain) : color
            let opacity = profile == .glow ? 1 : alpha * energy
            let key = SIMD4(quantize(radiance.x), quantize(radiance.y), quantize(radiance.z),
                            Int32((opacity * 128).rounded()))
            if let index = cellOf[key] {
                spriteCell.append(index)
            } else {
                let index = cellColor.count
                cellOf[key] = index
                cellColor.append(SIMD4(dequantize(key.x), dequantize(key.y), dequantize(key.z), Float(key.w) / 128))
                spriteCell.append(index)
            }
        }

        // Pass 2: the atlas, one cell per distinct colour.
        let cells = max(cellColor.count, 1)
        let columns = Int(Float(cells).squareRoot().rounded(.up))
        let rows = (cells + columns - 1) / columns
        var image = GlowImage(width: columns * cell, height: rows * cell, cell: cell)
        // The profile of one cell, shared by every sprite (texel k ↔ corner −1 + 2k/(c−1)).
        var falloff = [Float](repeating: 0, count: cell * cell)
        for y in 0..<cell {
            for x in 0..<cell {
                let px = -1 + 2 * Float(x) / Float(cell - 1)
                let py = -1 + 2 * Float(y) / Float(cell - 1)
                falloff[y * cell + x] = profile.value(px * px + py * py)
            }
        }
        for (index, color) in cellColor.enumerated() {
            let column = index % columns
            let row = index / columns
            let rgb = SIMD3(color.x, color.y, color.z)
            for y in 0..<cell {
                for x in 0..<cell {
                    let g = falloff[y * cell + x]
                    switch profile {
                    case .glow:
                        image.set(column * cell + x, row * cell + y, rgb * g, alpha: min(g, 1))
                    case .dust:
                        // Premultiplied: the puff's own faint colour, over what it lets through.
                        let a = g * color.w
                        image.set(column * cell + x, row * cell + y, rgb * a, alpha: a)
                    }
                }
            }
        }

        // Pass 3: the quads, grouped by twinkle phase.
        var quads = GlowQuads()
        quads.vertices.reserveCapacity(count * 4 * GlowQuads.stride)
        quads.indices.reserveCapacity(count * 6)
        var order = Array(0..<count)
        if twinkle > 0 {
            let groups = twinkleGroups
            let groupOf = (0..<count).map { sprite in
                let phase = mesh.vertices[sprite * 4 * s + 10]
                return min(max(Int((phase - floor(phase)) * Float(groups)), 0), groups - 1)
            }
            order.sort { (groupOf[$0], $0) < (groupOf[$1], $1) }
            quads.groups = (0..<groups).map { group in groupOf.lazy.filter { $0 == group }.count * 6 }
        }
        for sprite in order {
            let index = spriteCell[sprite]
            let column = index % columns
            let row = index / columns
            let u0 = (Float(column * cell) + 0.5) / Float(image.width)
            let u1 = (Float(column * cell + cell) - 0.5) / Float(image.width)
            let v0 = (Float(row * cell) + 0.5) / Float(image.height)
            let v1 = (Float(row * cell + cell) - 0.5) / Float(image.height)
            let base = UInt32(quads.vertices.count / GlowQuads.stride)
            quads.vertex(corners[sprite * 4], u0, v1)
            quads.vertex(corners[sprite * 4 + 1], u1, v1)
            quads.vertex(corners[sprite * 4 + 2], u1, v0)
            quads.vertex(corners[sprite * 4 + 3], u0, v0)
            quads.indices.append(contentsOf: [base, base + 1, base + 2, base, base + 2, base + 3])
        }
        return GlowLayer(quads: quads, image: image, twinkle: twinkle)
    }

    /// A radiance channel on a log2 scale in 1/16-octave steps; `Int32.min` for black.
    private static func quantize(_ value: Float) -> Int32 {
        value > 1e-6 ? Int32((log2(value) * 16).rounded()) : .min
    }

    private static func dequantize(_ step: Int32) -> Float {
        step == .min ? 0 : exp2(Float(step) / 16)
    }

    // MARK: Glowing limbs (the view-dependent half of cosmos_plasma.mat)

    /// The part of a plasma ball's shading that depends on the angle to the viewer, which a
    /// texture on the sphere cannot hold.
    struct Limb: Sendable {
        var radius: Float
        /// The mean of the sphere's own radiance, before limb brightening.
        var surface: SIMD3<Float>
        var rim: SIMD3<Float>
        var rimPower: Float
        /// The plasma's `PlasmaLook.exposure`; zero keeps the plain `filmic` roll-off.
        var exposure: SIMD3<Float> = .zero
    }

    /// A disc just in front of a sphere centred at the origin and seen from `distance` along
    /// +Z, exactly covering its silhouette. It carries the shader's limb brightening
    /// `0.5 (1 − N·V)` of the surface and its rim light `rim (1 − N·V)^rimPower`; the sphere's
    /// texture holds the rest (`0.75 ×` its radiance). Turn it towards the eye every frame.
    static func limb(_ limb: Limb, distance: Float, resolution cell: Int) -> GlowLayer {
        var image = GlowImage(width: cell, height: cell, cell: cell)
        var quads = GlowQuads()
        let front = limb.radius * 1.02
        let silhouette = (distance - front) * tan(asin(min(limb.radius / distance, 0.999)))
        // Two texels of margin past the silhouette, so its edge is antialiased in the texture.
        let size = silhouette * (1 + 4 / Float(cell))
        let u0 = 0.5 / Float(cell)
        let u1 = 1 - u0
        quads.vertex(SIMD3(-size, -size, front), u0, u1)
        quads.vertex(SIMD3(size, -size, front), u1, u1)
        quads.vertex(SIMD3(size, size, front), u1, u0)
        quads.vertex(SIMD3(-size, size, front), u0, u0)
        quads.indices = [0, 1, 2, 0, 2, 3]
        let scale = size / silhouette
        let texel = 2 / Float(cell - 1) * scale
        for y in 0..<cell {
            for x in 0..<cell {
                let px = (-1 + 2 * Float(x) / Float(cell - 1)) * scale
                let py = (-1 + 2 * Float(y) / Float(cell - 1)) * scale
                let s = (px * px + py * py).squareRoot()
                let coverage = min(max((1 - s) / texel + 0.5, 0), 1)
                let clamped = min(s, 1)
                let limbLight = 1 - max(1 - clamped * clamped, 0).squareRoot()
                let radiance = limb.surface * (0.5 * limbLight) + limb.rim * pow(limbLight, limb.rimPower)
                let shown: SIMD3<Float>
                if limb.exposure.x > 0 {
                    // The disc adds onto the sphere's already tone-mapped texture: add only
                    // what the limb light brings on top of the average surface under it.
                    let base = limb.surface * 0.75
                    shown = shoulder(base + radiance, exposure: limb.exposure) - shoulder(base, exposure: limb.exposure)
                } else {
                    shown = filmic(radiance)
                }
                image.set(x, y, shown * coverage, alpha: coverage)
            }
        }
        return GlowLayer(quads: quads, image: image)
    }

    /// The wide blue glow around a bright ball: on Android it is the HDR bloom of the star,
    /// which a display-space pass cannot rebuild from a frame that tops out at white. A quad
    /// through the ball's centre, facing +Z (turn it towards the eye), `extent` silhouette
    /// radii wide; the ball in front hides its middle. `falloff(r)` is the added radiance at
    /// `r` silhouette radii from the centre.
    static func corona(radius: Float, distance: Float, extent: Float, resolution cell: Int,
                       falloff: (Float) -> SIMD3<Float>) -> GlowLayer {
        var image = GlowImage(width: cell, height: cell, cell: cell)
        var quads = GlowQuads()
        let silhouette = radius / max(1 - (radius * radius) / (distance * distance), 1e-3).squareRoot()
        let size = silhouette * extent
        quads.vertex(SIMD3(-size, -size, 0), 0, 1)
        quads.vertex(SIMD3(size, -size, 0), 1, 1)
        quads.vertex(SIMD3(size, size, 0), 1, 0)
        quads.vertex(SIMD3(-size, size, 0), 0, 0)
        quads.indices = [0, 1, 2, 0, 2, 3]
        for y in 0..<cell {
            for x in 0..<cell {
                let px = (-1 + 2 * (Float(x) + 0.5) / Float(cell)) * extent
                let py = (-1 + 2 * (Float(y) + 0.5) / Float(cell)) * extent
                let r = (px * px + py * py).squareRoot()
                // Fade to nothing at the quad's edge so its square never shows.
                let edge = 1 - smoothstepf(extent * 0.8, extent, r)
                let rgb = falloff(r) * edge
                // Alpha 1: the additive blend scales colour by alpha, which would square the falloff.
                image.set(x, y, rgb, alpha: 1)
            }
        }
        return GlowLayer(quads: quads, image: image)
    }

    // MARK: Ribbons (cosmos_ribbon.mat)

    /// How bright a stroke is along its length: `cosmos_ribbon.mat`'s steady `base` plus its
    /// travelling dashes, `amp · (½ + ½ sin(arc · freq − time · speed + seed · 2π))⁸`.
    struct Dash: Sendable {
        var base: Float
        var amp: Float = 0
        var freq: Float = 0
        var speed: Float = 0

        static func steady(_ value: Float) -> Dash { Dash(base: value) }

        /// The same dashes, `gain` times brighter.
        func scaled(_ gain: Float) -> Dash { Dash(base: base * gain, amp: amp * gain, freq: freq, speed: speed) }

        func value(arc: Float, time: Float, seed: Float) -> Float {
            guard amp > 0 else { return base }
            let wave = 0.5 + 0.5 * sin(arc * freq - time * speed + seed * 2 * .pi)
            let w2 = wave * wave
            let w4 = w2 * w2
            return base + amp * w4 * w4
        }
    }

    /// Widens each stroke of `mesh` across the line of sight from `view.eye` and paints one
    /// atlas row per stroke. `dash` is the stroke's brightness along its arc, frozen at `time`
    /// (the dashes stand still: the atlas is painted once); `tailTaper` dims the far end like
    /// the shader.
    ///
    /// With `halo`, the stroke is instead widened `halo` times with a soft falloff: a baked
    /// light spill around the line that stands in for bloom where no post-process runs.
    static func ribbons(_ mesh: GlowMesh, view: GlowView, minPixels: Float,
                        dash: Dash, time: Float, tailTaper: Float, halo: Float? = nil) -> GlowLayer {
        let s = mesh.stride
        let total = mesh.vertexCount / 2
        // Split the flat point list back into strokes: t restarts at 0 on each new stroke.
        var starts: [Int] = []
        for point in 0..<total where mesh.vertices[point * 2 * s + 12] == 0 {
            starts.append(point)
        }
        starts.append(total)
        let strokes = starts.count - 1
        let longest = (0..<strokes).map { starts[$0 + 1] - starts[$0] }.max() ?? 2
        let rowHeight = 8
        // Rows wrap into side-by-side columns: Metal caps a texture side at 8192 texels.
        let perColumn = min(max(strokes, 1), 8192 / rowHeight)
        let columns = (strokes + perColumn - 1) / perColumn
        var image = GlowImage(width: longest * max(columns, 1), height: perColumn * rowHeight, cell: rowHeight)
        var quads = GlowQuads()
        var segments: [(key: Float, a: UInt32)] = []

        for stroke in 0..<strokes {
            let first = starts[stroke]
            let n = starts[stroke + 1] - first
            let row = (stroke % perColumn) * rowHeight
            let column = (stroke / perColumn) * longest
            let v0 = (Float(row) + 0.5) / Float(image.height)
            let v1 = (Float(row + rowHeight) - 0.5) / Float(image.height)
            let seed = mesh.vertices[first * 2 * s + 13]
            let reach = 0.8 + 0.4 * seed
            let firstVertex = UInt32(quads.vertices.count / GlowQuads.stride)
            for i in 0..<n {
                let v = (first + i) * 2 * s
                let p = SIMD3(mesh.vertices[v], mesh.vertices[v + 1], mesh.vertices[v + 2])
                let color = SIMD3(mesh.vertices[v + 3], mesh.vertices[v + 4], mesh.vertices[v + 5])
                let tangent = SIMD3(mesh.vertices[v + 7], mesh.vertices[v + 8], mesh.vertices[v + 9])
                var halfWidth = mesh.vertices[v + 11]
                let t = mesh.vertices[v + 12]
                let arc = mesh.vertices[v + 14]

                let toEye = view.eye - p
                let depth = max(simd_length(toEye), 1e-3)
                var side = simd_cross(tangent, toEye)
                let sideLength = simd_length(side)
                side = sideLength > 1e-6 ? side / sideLength : .zero
                let halfWidthPx = halfWidth * view.focal / depth
                var energy: Float = 1
                if halfWidthPx < minPixels {
                    energy = halfWidthPx / minPixels
                    halfWidth = minPixels * depth / view.focal
                }
                if let halo { halfWidth *= halo }
                let u = (Float(column + i) + 0.5) / Float(image.width)
                quads.vertex(p - side * halfWidth, u, v0)
                quads.vertex(p + side * halfWidth, u, v1)

                let taper = 1 + (1 - smoothstepf(0.55, 1, t) - 1) * tailTaper
                let body = dash.value(arc: arc, time: time, seed: seed)
                let radiance = color * (body * taper * energy)
                for y in 0..<rowHeight {
                    let across = -1 + 2 * Float(y) / Float(rowHeight - 1)
                    let profile = halo == nil
                        ? exp(-across * across * 4.5) * (1 - across * across)
                        : exp(-across * across * 6) * (1 - across * across)
                    image.set(column + i, row + y, filmic(radiance * profile), alpha: profile)
                }
                if i < n - 1 {
                    segments.append((t / reach, firstVertex + UInt32(i * 2)))
                }
            }
        }
        segments.sort { $0.key < $1.key }
        quads.indices.reserveCapacity(segments.count * 6)
        quads.revealKeys.reserveCapacity(segments.count)
        for segment in segments {
            let a = segment.a
            let b = a + 2
            quads.indices.append(contentsOf: [a, a + 1, b, a + 1, b + 1, b])
            quads.revealKeys.append(segment.key)
        }
        return GlowLayer(quads: quads, image: image)
    }

    /// Texels per dash cycle in a dashed atlas row.
    private static let dashPeriod = 64

    /// Like `ribbons`, but the dashes travel. A dash is the same shape wherever it is — only its
    /// colour changes — so the atlas holds one dash cycle per colour class, repeating along `u`,
    /// and every segment maps its arc to `u` in dash cycles. Scrolling the material's texture
    /// coordinates by `−time · speed / 2π` then moves every dash along its stroke, as
    /// `cosmos_ribbon.mat` does with `arc · freq − time · speed`.
    ///
    /// Each segment is its own quad, in the row of its colour at its midpoint: colours are
    /// rounded to 1/16 of an octave per channel — coarser if a stroke set has more colours than
    /// the atlas has rows.
    static func dashedRibbons(_ mesh: GlowMesh, view: GlowView, minPixels: Float,
                              dash: Dash, tailTaper: Float) -> GlowLayer {
        let s = mesh.stride
        let total = mesh.vertexCount / 2
        var starts: [Int] = []
        for point in 0..<total where mesh.vertices[point * 2 * s + 12] == 0 {
            starts.append(point)
        }
        starts.append(total)
        let strokes = starts.count - 1

        // Pass 1: every point's two edges, `u` and radiance.
        var left = [SIMD3<Float>](repeating: .zero, count: total)
        var right = [SIMD3<Float>](repeating: .zero, count: total)
        var u = [Float](repeating: 0, count: total)
        var radiance = [SIMD3<Float>](repeating: .zero, count: total)
        var segments: [(key: Float, point: Int)] = []
        for stroke in 0..<strokes {
            let first = starts[stroke]
            let n = starts[stroke + 1] - first
            let seed = mesh.vertices[first * 2 * s + 13]
            let reach = 0.8 + 0.4 * seed
            for i in 0..<n {
                let point = first + i
                let v = point * 2 * s
                let p = SIMD3(mesh.vertices[v], mesh.vertices[v + 1], mesh.vertices[v + 2])
                let color = SIMD3(mesh.vertices[v + 3], mesh.vertices[v + 4], mesh.vertices[v + 5])
                let tangent = SIMD3(mesh.vertices[v + 7], mesh.vertices[v + 8], mesh.vertices[v + 9])
                var halfWidth = mesh.vertices[v + 11]
                let t = mesh.vertices[v + 12]
                let arc = mesh.vertices[v + 14]

                let toEye = view.eye - p
                let depth = max(simd_length(toEye), 1e-3)
                var side = simd_cross(tangent, toEye)
                let sideLength = simd_length(side)
                side = sideLength > 1e-6 ? side / sideLength : .zero
                let halfWidthPx = halfWidth * view.focal / depth
                var energy: Float = 1
                if halfWidthPx < minPixels {
                    energy = halfWidthPx / minPixels
                    halfWidth = minPixels * depth / view.focal
                }
                left[point] = p - side * halfWidth
                right[point] = p + side * halfWidth
                u[point] = arc * dash.freq / (2 * .pi) + seed
                let taper = 1 + (1 - smoothstepf(0.55, 1, t) - 1) * tailTaper
                radiance[point] = color * (taper * energy)
                if i < n - 1 { segments.append((t / reach, point)) }
            }
        }

        // Pass 2: colour classes, as fine as the atlas height allows.
        let rowHeight = 6
        // 8192 texels: devices take 16384, but the Simulator's Metal aborts above 8192.
        let maxRows = 8192 / rowHeight
        var step: Float = 16
        var classOf: [SIMD3<Int32>: Int] = [:]
        var segmentClass = [Int](repeating: 0, count: segments.count)
        while true {
            classOf.removeAll(keepingCapacity: true)
            for (index, segment) in segments.enumerated() {
                let mid = (radiance[segment.point] + radiance[segment.point + 1]) * 0.5
                let key = SIMD3(quantize(mid.x, steps: step), quantize(mid.y, steps: step), quantize(mid.z, steps: step))
                if let row = classOf[key] {
                    segmentClass[index] = row
                } else {
                    segmentClass[index] = classOf.count
                    classOf[key] = classOf.count
                }
            }
            if classOf.count <= maxRows || step <= 1 { break }
            step /= 2
        }
        let rows = max(classOf.count, 1)
        let width = dashPeriod
        var image = GlowImage(width: width, height: rows * rowHeight, cell: rowHeight)
        var along = [Float](repeating: 0, count: width)
        for x in 0..<width {
            let wave = 0.5 + 0.5 * sin(2 * .pi * (Float(x) + 0.5) / Float(width))
            let w2 = wave * wave
            let w4 = w2 * w2
            along[x] = dash.base + dash.amp * w4 * w4
        }
        var across = [Float](repeating: 0, count: rowHeight)
        for y in 0..<rowHeight {
            let a = -1 + 2 * Float(y) / Float(rowHeight - 1)
            across[y] = exp(-a * a * 4.5) * (1 - a * a)
        }
        for (key, row) in classOf {
            let color = SIMD3<Float>(dequantize(key.x, steps: step), dequantize(key.y, steps: step),
                                     dequantize(key.z, steps: step))
            for y in 0..<rowHeight {
                for x in 0..<width {
                    let level: Float = along[x] * across[y]
                    image.set(x, row * rowHeight + y, filmic(color * level), alpha: across[y])
                }
            }
        }

        // Pass 3: one quad per segment, drawn in reveal order.
        var order = Array(segments.indices)
        order.sort { segments[$0].key < segments[$1].key }
        var quads = GlowQuads()
        quads.vertices.reserveCapacity(segments.count * 4 * GlowQuads.stride)
        quads.indices.reserveCapacity(segments.count * 6)
        quads.revealKeys.reserveCapacity(segments.count)
        for index in order {
            let a = segments[index].point
            let b = a + 1
            let row = segmentClass[index]
            let v0 = (Float(row * rowHeight) + 0.5) / Float(image.height)
            let v1 = (Float(row * rowHeight + rowHeight) - 0.5) / Float(image.height)
            let base = UInt32(quads.vertices.count / GlowQuads.stride)
            quads.vertex(left[a], u[a], v0)
            quads.vertex(right[a], u[a], v1)
            quads.vertex(left[b], u[b], v0)
            quads.vertex(right[b], u[b], v1)
            quads.indices.append(contentsOf: [base, base + 1, base + 2, base + 1, base + 3, base + 2])
            quads.revealKeys.append(segments[index].key)
        }
        NSLog("[Cosmos] dashed ribbons: %d segments, %d colour classes at 1/%.0f octave",
              segments.count, rows, step)
        return GlowLayer(quads: quads, image: image, dashSpeed: dash.speed)
    }

    private static func quantize(_ value: Float, steps: Float) -> Int32 {
        value > 1e-6 ? Int32((log2(value) * steps).rounded()) : .min
    }

    private static func dequantize(_ step: Int32, steps: Float) -> Float {
        step == .min ? 0 : exp2(Float(step) / steps)
    }

    /// Right and up vectors of a plane facing along `normal`.
    private static func basis(_ normal: SIMD3<Float>) -> (SIMD3<Float>, SIMD3<Float>) {
        let n = simd_normalize(normal)
        let reference: SIMD3<Float> = abs(n.y) > 0.95 ? [0, 0, 1] : [0, 1, 0]
        let right = simd_normalize(simd_cross(reference, n))
        return (right, simd_cross(n, right))
    }

    /// Rolls bright colours towards white the way Filament's filmic tone map does on Android;
    /// RealityKit's keeps them saturated, which turns the white-hot star cyan. Applied to the
    /// baked radiance, so it only sees one texel's light, not the sum on screen.
    static func filmic(_ c: SIMD3<Float>) -> SIMD3<Float> {
        let peak = max(c.x, c.y, c.z)
        let w = smoothstepf(0.35, 1.6, peak) * 0.6
        return c + (SIMD3(repeating: peak) - c) * w
    }

    /// Filament's filmic curve as it renders the star on Android: a per-channel exposure and
    /// shoulder, then a roll towards white of the brightest filaments only. The surface keeps
    /// its blue cells and white-hot ridges instead of clipping to flat white the way `filmic`
    /// alone lets it. The exposures were fitted to the Android capture's cell and ridge colours.
    static func shoulder(_ c: SIMD3<Float>, exposure: SIMD3<Float>) -> SIMD3<Float> {
        let e = SIMD3<Float>(1 - exp(-max(c.x, 0) * exposure.x),
                             1 - exp(-max(c.y, 0) * exposure.y),
                             1 - exp(-max(c.z, 0) * exposure.z))
        let peak = max(e.x, e.y, e.z)
        return e + (SIMD3(repeating: peak) - e) * (smoothstepf(0.85, 1, peak) * 0.5)
    }

    fileprivate static func smoothstepf(_ e0: Float, _ e1: Float, _ x: Float) -> Float {
        let t = min(max((x - e0) / (e1 - e0), 0), 1)
        return t * t * (3 - 2 * t)
    }
}

// MARK: - Plasma (the view-independent half of cosmos_plasma.mat)

/// Surface colours of a plasma ball — Android's `PlasmaLook`, in linear rgb.
struct PlasmaLook: Sendable {
    var deep: SIMD3<Float>
    var hot: SIMD3<Float>
    var rim: SIMD3<Float>
    var rimPower: Float
    var noiseScale: Float
    var flow: Float
    /// Non-zero, the surface goes through `GlowBuilder.shoulder` at these per-channel
    /// exposures instead of `filmic` — for a surface bright enough to clip.
    var exposure: SIMD3<Float> = .zero

    static let star = PlasmaLook(deep: [0.09, 0.42, 0.95], hot: [0.26, 0.8, 1.7], rim: [0.3, 0.9, 2.2],
                                 rimPower: 3, noiseScale: 9, flow: 0.22, exposure: [2.6, 1.8, 2.5])
    static let nucleus = PlasmaLook(deep: [0.004, 0.008, 0.02], hot: [0.03, 0.07, 0.16], rim: [0.25, 0.6, 1.6],
                                    rimPower: 3, noiseScale: 4, flow: 0.2)
}

/// The plasma shader's noise, evaluated on the CPU into a texture: domain-warped value-noise
/// fbm whose ridges are the bright filaments. Same hash, octaves and offsets as the shader.
enum Plasma {

    /// The surface radiance of `look` at `time`, as an equirectangular map for a sphere, already
    /// at the `0.75` limb factor of the disc's centre and times `gain`; and its area-weighted
    /// mean before both, for `GlowBuilder.limb`.
    static func surface(_ look: PlasmaLook, time: Float, gain: Float = 1, width: Int = 1024, height: Int = 512)
        -> (image: GlowImage, mean: SIMD3<Float>) {
        var image = GlowImage(width: width, height: height, cell: 1)
        var rowSums = [SIMD4<Float>](repeating: .zero, count: height)
        let t = time * look.flow
        let scale = 0.75 * gain
        image.pixels.withUnsafeMutableBufferPointer { pixelBuffer in
            rowSums.withUnsafeMutableBufferPointer { sumBuffer in
                // Each row writes only its own pixels and its own sum.
                let rows = RowOutput(pixels: pixelBuffer.baseAddress!, sums: sumBuffer.baseAddress!)
                DispatchQueue.concurrentPerform(iterations: height) { y in
                    let pixels = rows.pixels
                    let sums = rows.sums
                    let lat = Float.pi / 2 - (Float(y) + 0.5) / Float(height) * .pi
                    var sum = SIMD4<Float>.zero
                    for x in 0..<width {
                        let lon = (Float(x) + 0.5) / Float(width) * 2 * .pi
                        let p = SIMD3(cos(lat) * sin(lon), sin(lat), cos(lat) * cos(lon)) * look.noiseScale
                        let warp = SIMD3(
                            fbm(p + SIMD3(0, t * 0.9, 0)),
                            fbm(p + SIMD3(5.2, 1.3 - t * 0.7, 2.8)),
                            fbm(p + SIMD3(t * 0.6, 3.7, 8.1))
                        )
                        let n = fbm(p + 2.4 * warp + SIMD3(0, 0, t * 0.5))
                        var ridge = 1 - abs(2 * n - 1)
                        ridge = ridge * ridge * ridge * ridge
                        let heat = smoothstep(0.3, 0.75, n)
                        let radiance = simd_mix(look.deep, look.hot, SIMD3(repeating: heat)) + look.hot * (ridge * 0.9)
                        let shown = look.exposure.x > 0
                            ? GlowBuilder.shoulder(radiance * scale, exposure: look.exposure)
                            : GlowBuilder.filmic(radiance * scale)
                        let i = (y * width + x) * 4
                        pixels[i] = shown.x
                        pixels[i + 1] = shown.y
                        pixels[i + 2] = shown.z
                        pixels[i + 3] = 1
                        sum += SIMD4(radiance, 1)
                    }
                    sums[y] = sum * cos(lat)
                }
            }
        }
        let total = rowSums.reduce(SIMD4<Float>.zero, +)
        return (image, SIMD3(total.x, total.y, total.z) / max(total.w, 1e-6))
    }

    /// Where the parallel rows write; rows never overlap, hence the unchecked `Sendable`.
    private struct RowOutput: @unchecked Sendable {
        let pixels: UnsafeMutablePointer<Float>
        let sums: UnsafeMutablePointer<SIMD4<Float>>
    }

    private static func hash(_ q: SIMD3<Float>) -> Float {
        var p = q * 0.3183099 + 0.1
        p -= floor(p)
        p *= 17
        let v = p.x * p.y * p.z * (p.x + p.y + p.z)
        return v - floor(v)
    }

    private static func noise(_ x: SIMD3<Float>) -> Float {
        let i = floor(x)
        var f = x - i
        f = f * f * (3 - 2 * f)
        func h(_ dx: Float, _ dy: Float, _ dz: Float) -> Float { hash(i + SIMD3(dx, dy, dz)) }
        let x00 = h(0, 0, 0) + (h(1, 0, 0) - h(0, 0, 0)) * f.x
        let x10 = h(0, 1, 0) + (h(1, 1, 0) - h(0, 1, 0)) * f.x
        let x01 = h(0, 0, 1) + (h(1, 0, 1) - h(0, 0, 1)) * f.x
        let x11 = h(0, 1, 1) + (h(1, 1, 1) - h(0, 1, 1)) * f.x
        let y0 = x00 + (x10 - x00) * f.y
        let y1 = x01 + (x11 - x01) * f.y
        return y0 + (y1 - y0) * f.z
    }

    private static func fbm(_ q: SIMD3<Float>) -> Float {
        var p = q
        var sum: Float = 0
        var amplitude: Float = 0.5
        for _ in 0..<5 {
            sum += amplitude * noise(p)
            p = p * 2.03 + SIMD3(1.7, 9.2, 3.1)
            amplitude *= 0.5
        }
        return sum
    }

    private static func smoothstep(_ e0: Float, _ e1: Float, _ x: Float) -> Float {
        let t = min(max((x - e0) / (e1 - e0), 0), 1)
        return t * t * (3 - 2 * t)
    }
}

// MARK: - RealityKit side

/// A glowing layer on screen: the entity, its additive material and, for ribbons, the reveal.
@MainActor
final class GlowEntity {
    let entity: ModelEntity
    /// One material per twinkle group (a single one for everything else).
    private var materials: [UnlitMaterial]
    private let mesh: LowLevelMesh
    private let revealKeys: [Float]
    private let bounds: BoundingBox
    private let twinkle: Float
    private let dashSpeed: Float?
    private var drawnSegments = -1
    private var tint: Float = -1
    private var tints: [Float]
    private var scroll: Float = -1

    init(_ layer: GlowLayer, program: UnlitMaterial.Program) async throws {
        let texture = try await Self.texture(layer.image)
        var material = UnlitMaterial(program: program)
        let sampler = MTLSamplerDescriptor()
        sampler.minFilter = .linear
        sampler.magFilter = .linear
        sampler.mipFilter = .linear
        // Dashed ribbons repeat one dash cycle along u.
        sampler.sAddressMode = layer.dashSpeed == nil ? .clampToEdge : .repeat
        sampler.tAddressMode = .clampToEdge
        material.color = .init(tint: .white, texture: .init(texture, sampler: .init(sampler)))
        material.blending = .transparent(opacity: .init(floatLiteral: 1))
        material.writesDepth = false
        material.faceCulling = .none
        let groups = max(layer.quads.groups.count, 1)
        materials = Array(repeating: material, count: groups)
        tints = Array(repeating: -1, count: groups)
        twinkle = layer.twinkle
        dashSpeed = layer.dashSpeed
        revealKeys = layer.quads.revealKeys
        bounds = BoundingBox(min: layer.quads.boundsMin, max: layer.quads.boundsMax)
        mesh = try Self.upload(layer.quads)
        entity = ModelEntity(mesh: try await MeshResource(from: mesh), materials: materials)
    }

    /// Scales the whole layer's radiance, 0...1.
    func setIntensity(_ value: Float) {
        let clamped = min(max(value, 0), 1)
        guard abs(clamped - tint) > 1e-4 else { return }
        tint = clamped
        for index in materials.indices { applyTint(clamped, to: index) }
        entity.model?.materials = materials
    }

    /// Scales the layer's radiance, 0...1, with each twinkle group flickering at `time` as
    /// `cosmos_sprite.mat` does: `1 + twinkle · sin(2.7 t + 2π phase)`, over the baked headroom.
    func setIntensity(_ value: Float, time: Float) {
        guard twinkle > 0, materials.count > 1 else { return setIntensity(value) }
        let clamped = min(max(value, 0), 1)
        var changed = false
        for index in materials.indices {
            let phase = (Float(index) + 0.5) / Float(materials.count)
            let flicker = (1 + twinkle * sin(time * 2.7 + phase * 2 * .pi)) / (1 + twinkle)
            let value = clamped * flicker
            guard abs(value - tints[index]) > 1e-4 else { continue }
            applyTint(value, to: index)
            changed = true
        }
        tint = -1
        if changed { entity.model?.materials = materials }
    }

    /// Moves a dashed ribbon's dashes to where they are at `time`.
    func scrollDashes(time: Float) {
        guard let dashSpeed else { return }
        let cycles = time * dashSpeed / (2 * .pi)
        let offset = -(cycles - floor(cycles))
        guard abs(offset - scroll) > 1e-5 else { return }
        scroll = offset
        for index in materials.indices {
            materials[index].textureCoordinateTransform = .init(offset: SIMD2(offset, 0))
        }
        entity.model?.materials = materials
    }

    /// A linear tint, so 0.5 halves the radiance instead of dimming it by sRGB's curve.
    private func applyTint(_ value: Float, to index: Int) {
        tints[index] = value
        let v = CGFloat(value)
        materials[index].color.tint = GlowEntity.linearTint(v)
    }

    /// For an alpha-blended layer (dust): how much of its alpha applies, 0...1.
    func setOpacity(_ value: Float) {
        let clamped = min(max(value, 0), 1)
        guard abs(clamped - tint) > 1e-4 else { return }
        tint = clamped
        for index in materials.indices {
            materials[index].blending = .transparent(opacity: .init(floatLiteral: clamped))
        }
        entity.model?.materials = materials
    }

    /// Draws after every layer with a lower `order` in the same scene, whatever their depth —
    /// dust has to land on the stars it dims.
    func drawOrder(_ order: Int32, in group: ModelSortGroup) {
        entity.components.set(ModelSortGroupComponent(group: group, order: order))
    }

    /// Draws the ribbon segments whose reveal key is below `head` (the burst's growth).
    func reveal(upTo head: Float) {
        guard !revealKeys.isEmpty else { return }
        var low = 0
        var high = revealKeys.count
        while low < high {
            let mid = (low + high) / 2
            if revealKeys[mid] < head { low = mid + 1 } else { high = mid }
        }
        guard low != drawnSegments else { return }
        drawnSegments = low
        mesh.parts.replaceAll([
            LowLevelMesh.Part(indexCount: low * 6, topology: .triangle, bounds: bounds),
        ])
    }

    /// The additive, unlit, tone-mapped program every glow layer shares.
    static func additiveProgram() async -> UnlitMaterial.Program {
        var descriptor = UnlitMaterial.Program.Descriptor()
        descriptor.blendMode = .add
        descriptor.applyPostProcessToneMap = true
        return await UnlitMaterial.Program(descriptor: descriptor)
    }

    /// The alpha-blended program of the dust lanes: they darken what is behind them.
    static func alphaProgram() async -> UnlitMaterial.Program {
        var descriptor = UnlitMaterial.Program.Descriptor()
        descriptor.blendMode = .alpha
        descriptor.applyPostProcessToneMap = true
        return await UnlitMaterial.Program(descriptor: descriptor)
    }

    private static func upload(_ quads: GlowQuads) throws -> LowLevelMesh {
        let vertexCount = quads.vertices.count / GlowQuads.stride
        let descriptor = LowLevelMesh.Descriptor(
            vertexCapacity: vertexCount,
            vertexAttributes: [
                .init(semantic: .position, format: .float3, offset: 0),
                .init(semantic: .uv0, format: .float2, offset: 12),
            ],
            vertexLayouts: [.init(bufferIndex: 0, bufferStride: GlowQuads.stride * MemoryLayout<Float>.stride)],
            indexCapacity: max(quads.indices.count, 1),
            indexType: .uint32
        )
        let mesh = try LowLevelMesh(descriptor: descriptor)
        mesh.withUnsafeMutableBytes(bufferIndex: 0) { target in
            quads.vertices.withUnsafeBytes { target.copyMemory(from: $0) }
        }
        mesh.withUnsafeMutableIndices { target in
            quads.indices.withUnsafeBytes { target.copyMemory(from: $0) }
        }
        let bounds = BoundingBox(min: quads.boundsMin, max: quads.boundsMax)
        if quads.groups.count > 1 {
            // One part per twinkle group, each drawn with its own material.
            var offset = 0
            var parts: [LowLevelMesh.Part] = []
            for (group, count) in quads.groups.enumerated() where count > 0 {
                parts.append(LowLevelMesh.Part(indexOffset: offset * MemoryLayout<UInt32>.stride, indexCount: count,
                                               topology: .triangle, materialIndex: group, bounds: bounds))
                offset += count
            }
            mesh.parts.replaceAll(parts)
        } else {
            mesh.parts.replaceAll([
                LowLevelMesh.Part(indexCount: quads.indices.count, topology: .triangle, bounds: bounds),
            ])
        }
        return mesh
    }

    static func texture(_ image: GlowImage) async throws -> TextureResource {
        guard let metal = GlowMetal.shared else { throw GlowError.noMetal }
        // Float32 → float16 with vImage, off the main thread: the flow's atlas is 50 MB.
        let staging = try await Task.detached(priority: .userInitiated) {
            try GlowStaging(image, device: metal.device)
        }.value
        guard let commandBuffer = metal.queue.makeCommandBuffer() else { throw GlowError.noMetal }
        // No mip chain: sprites are sized to at least `minPixels` on screen already, and a
        // mip would average a one-pixel star with its cell's dark margin and dim it ~8x.
        let descriptor = LowLevelTexture.Descriptor(
            pixelFormat: .rgba16Float,
            width: image.width,
            height: image.height,
            mipmapLevelCount: 1,
            textureUsage: [.shaderRead]
        )
        let texture = try LowLevelTexture(descriptor: descriptor)
        let target = texture.replace(using: commandBuffer)
        guard let blit = commandBuffer.makeBlitCommandEncoder() else { throw GlowError.noMetal }
        blit.copy(from: staging.buffer, sourceOffset: 0, sourceBytesPerRow: image.width * 8,
                  sourceBytesPerImage: image.width * image.height * 8,
                  sourceSize: MTLSize(width: image.width, height: image.height, depth: 1),
                  to: target, destinationSlice: 0, destinationLevel: 0,
                  destinationOrigin: MTLOrigin(x: 0, y: 0, z: 0))
        blit.endEncoding()
        commandBuffer.commit()
        return try await TextureResource(from: texture)
    }

    enum GlowError: Error { case noMetal }

    fileprivate static let linear = CGColorSpace(name: CGColorSpace.extendedLinearSRGB)!

    /// A grey in linear extended sRGB. `NSColor(cgColor:)` is failable where `UIColor`'s is not.
    fileprivate static func linearTint(_ v: CGFloat) -> UIColor {
        let cg = CGColor(colorSpace: linear, components: [v, v, v, 1])!
        #if canImport(UIKit)
        return UIColor(cgColor: cg)
        #else
        return NSColor(cgColor: cg) ?? .white
        #endif
    }
}

/// The one Metal device and command queue every atlas upload goes through.
final class GlowMetal: @unchecked Sendable {
    let device: MTLDevice
    let queue: MTLCommandQueue

    static let shared: GlowMetal? = {
        guard let device = MTLCreateSystemDefaultDevice(), let queue = device.makeCommandQueue() else { return nil }
        return GlowMetal(device: device, queue: queue)
    }()

    private init(device: MTLDevice, queue: MTLCommandQueue) {
        self.device = device
        self.queue = queue
    }
}

/// An atlas converted to float16 in a shared Metal buffer, ready to blit.
struct GlowStaging: @unchecked Sendable {
    let buffer: MTLBuffer

    init(_ image: GlowImage, device: MTLDevice) throws {
        let count = image.pixels.count
        guard let buffer = device.makeBuffer(length: count * 2, options: .storageModeShared) else {
            throw GlowEntity.GlowError.noMetal
        }
        image.pixels.withUnsafeBufferPointer { source in
            var src = vImage_Buffer(data: UnsafeMutableRawPointer(mutating: source.baseAddress!),
                                    height: 1, width: vImagePixelCount(count), rowBytes: count * 4)
            var dst = vImage_Buffer(data: buffer.contents(), height: 1,
                                    width: vImagePixelCount(count), rowBytes: count * 2)
            vImageConvert_PlanarFtoPlanar16F(&src, &dst, 0)
        }
        self.buffer = buffer
    }
}

/// A plasma ball: an opaque sphere wearing `Plasma.surface`, occluding what is behind it.
///
/// With a `churn` program it can also cross-fade to a later surface: a hair-larger additive
/// sphere carries the next frame, and the two tints trade places — `a · (1 − f) + b · f`.
@MainActor
final class PlasmaEntity {
    let entity: ModelEntity
    private var material: UnlitMaterial
    private var overlay: ModelEntity?
    private var overlayMaterial: UnlitMaterial?
    private var shown: (a: ObjectIdentifier?, b: ObjectIdentifier?) = (nil, nil)
    private var tints: SIMD2<Float> = [-1, -1]

    init(texture: TextureResource, radius: Float, churn program: UnlitMaterial.Program? = nil) {
        var material = UnlitMaterial(applyPostProcessToneMap: true)
        material.color = .init(tint: .white, texture: .init(texture, sampler: Self.sampler))
        self.material = material
        entity = ModelEntity(mesh: .generateSphere(radius: radius), materials: [material])
        if let program {
            var fade = UnlitMaterial(program: program)
            fade.color = .init(tint: .black, texture: .init(texture, sampler: Self.sampler))
            fade.blending = .transparent(opacity: .init(floatLiteral: 1))
            fade.writesDepth = false
            let sphere = ModelEntity(mesh: .generateSphere(radius: radius * 1.0005), materials: [fade])
            entity.addChild(sphere)
            overlay = sphere
            overlayMaterial = fade
        }
    }

    /// Scales the surface radiance, 0...1.
    func setIntensity(_ value: Float) {
        let clamped = min(max(value, 0), 1)
        guard abs(clamped - tints.x) > 1e-4 else { return }
        tints.x = clamped
        material.color.tint = Self.tint(clamped)
        entity.model?.materials = [material]
    }

    /// Shows `a` blended towards `b` by `mix`, at `value` overall (needs a `churn` program).
    func show(_ a: TextureResource, _ b: TextureResource, mix: Float, intensity value: Float) {
        guard let overlay, var fade = overlayMaterial else { return }
        let clamped = min(max(value, 0), 1)
        let f = min(max(mix, 0), 1)
        let ids = (ObjectIdentifier(a), ObjectIdentifier(b))
        let target = SIMD2(clamped * (1 - f), clamped * f)
        if ids.0 != shown.a || abs(target.x - tints.x) > 1e-4 {
            material.color = .init(tint: Self.tint(target.x), texture: .init(a, sampler: Self.sampler))
            entity.model?.materials = [material]
        }
        if ids.1 != shown.b || abs(target.y - tints.y) > 1e-4 {
            fade.color = .init(tint: Self.tint(target.y), texture: .init(b, sampler: Self.sampler))
            overlay.model?.materials = [fade]
            overlayMaterial = fade
        }
        shown = (ids.0, ids.1)
        tints = target
    }

    private static let sampler: MaterialParameters.Texture.Sampler = {
        let descriptor = MTLSamplerDescriptor()
        descriptor.minFilter = .linear
        descriptor.magFilter = .linear
        descriptor.sAddressMode = .repeat
        descriptor.tAddressMode = .clampToEdge
        return .init(descriptor)
    }()

    private static func tint(_ value: Float) -> UIColor {
        let v = CGFloat(value)
        return GlowEntity.linearTint(v)
    }
}
