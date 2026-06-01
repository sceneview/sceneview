#if os(iOS) || os(macOS) || os(visionOS)
import Foundation
import simd

/// Canonical 2D preset polygons for the Shape Extrude gallery.
///
/// These are the **single source of truth** for the polygons rendered by the
/// iOS demo's `ShapeExtrudeDemo` shape picker. The `SceneViewSwift`
/// triangulation tests consume the very same constants, so retuning a preset
/// here — e.g. the star's inner/outer radius — updates the demo *and* the test
/// that guards its geometry together. Neither can silently drift from the
/// other (the duplication hazard called out after #2354, where the test had
/// hand-copied byte-identical polygons that would not track a demo retune).
///
/// Each value is an ordered list of XY vertices; the polygon auto-closes (last
/// vertex connects to the first). Feed one straight into a ``ShapeNode``:
///
/// ```swift
/// let star = ShapeNode(points: ShapePresets.star, extrusionDepth: 0.15, color: .systemYellow)
/// content.addChild(star.entity)
/// ```
public enum ShapePresets {
    /// Upward-pointing triangle.
    public static let triangle: [SIMD2<Float>] = [
        .init(0, 0.5),
        .init(-0.5, -0.4),
        .init(0.5, -0.4),
    ]

    /// 5-pointed star, outer radius 0.5 / inner radius 0.22 — the gallery's
    /// default preset and the canonical concave (reflex inner-ring) fixture.
    public static let star: [SIMD2<Float>] =
        ShapeNode.starPoints(pointCount: 5, outerRadius: 0.5, innerRadius: 0.22)

    /// Regular pentagon, radius 0.5.
    public static let pentagon: [SIMD2<Float>] =
        ShapeNode.regularPolygonPoints(sides: 5, radius: 0.5)

    /// Regular hexagon, radius 0.5. Uses a **+X-first** orientation (start angle
    /// 0) to preserve the gallery's original hexagon look — intentionally
    /// different from ``ShapeNode/regularPolygonPoints(sides:radius:)``'s
    /// `-.pi / 2` start angle used by ``pentagon``. (This is why the hexagon is
    /// a literal rather than `regularPolygonPoints(sides: 6)`.)
    public static let hexagon: [SIMD2<Float>] = (0..<6).map { i in
        let angle = Float(i) / 6 * 2 * .pi
        return .init(0.5 * cos(angle), 0.5 * sin(angle))
    }

    /// Concave L-shape (one interior reflex vertex).
    public static let lShape: [SIMD2<Float>] = [
        .init(-0.4, -0.4),
        .init(0.1, -0.4),
        .init(0.1, 0.0),
        .init(-0.1, 0.0),
        .init(-0.1, 0.4),
        .init(-0.4, 0.4),
    ]

    /// Concave arrow (shaft + arrowhead).
    public static let arrow: [SIMD2<Float>] = [
        // Shaft
        .init(-0.35, -0.1),
        .init(0.05, -0.1),
        .init(0.05, -0.3),
        // Arrowhead
        .init(0.4,  0.0),
        .init(0.05, 0.3),
        .init(0.05, 0.1),
        .init(-0.35, 0.1),
    ]
}
#endif // os(iOS) || os(macOS) || os(visionOS)
