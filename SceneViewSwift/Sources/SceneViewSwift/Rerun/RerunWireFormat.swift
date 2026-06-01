import Foundation
#if os(iOS)
import ARKit
#endif

/// JSON-lines wire format shared between the Kotlin and Swift Rerun bridges.
///
/// Every event is one JSON object on a single line, terminated by `\n`, and
/// consumed by a Python sidecar that re-logs it via the `rerun-sdk` into the
/// Rerun viewer. The same wire format is emitted on Android (ARCore) and
/// iOS (ARKit), so a single sidecar script handles both platforms.
///
/// Format:
/// ```
/// {"t": 123456789, "type": "camera_pose", "entity": "world/camera", "translation": [x,y,z], "quaternion": [x,y,z,w]}
/// {"t": 123456789, "type": "plane", "entity": "world/planes/<id>", "polygon": [[x,y,z], ...], "kind": "horizontal"}
/// {"t": 123456789, "type": "point_cloud", "entity": "world/points", "positions": [[x,y,z], ...], "confidences": [f, ...]}
/// {"t": 123456789, "type": "anchor", "entity": "world/anchors/<id>", "translation": [x,y,z], "quaternion": [x,y,z,w]}
/// {"t": 123456789, "type": "hit_result", "entity": "world/hits/<id>", "translation": [x,y,z], "distance": f}
/// ```
///
/// This module is pure: no I/O, no threading, no ARKit instances needed by
/// the testable `*Json` overloads — the string-in / string-out nature lets
/// the unit tests assert exact golden JSON output on plain Swift.
///
/// JSON is hand-rolled instead of using `JSONEncoder` so Kotlin and Swift
/// emit byte-identical output for the same input — the Python sidecar
/// doesn't care about whitespace but the cross-platform golden tests do.
public enum RerunWireFormat {

    // MARK: - Testable overloads (primitives only)

    /// Camera pose event — takes the pose as primitives so tests don't need
    /// a real ARKit frame.
    public static func cameraPoseJson(
        timestampNanos: Int64,
        tx: Float, ty: Float, tz: Float,
        qx: Float, qy: Float, qz: Float, qw: Float,
        entity: String = "world/camera"
    ) -> String {
        var s = ""
        s.reserveCapacity(192)
        s.append("{")
        appendCommonHeader(&s, timestampNanos: timestampNanos, type: "camera_pose", entity: entity)
        s.append(",\"translation\":[")
        appendFloat(&s, tx); s.append(",")
        appendFloat(&s, ty); s.append(",")
        appendFloat(&s, tz)
        s.append("],\"quaternion\":[")
        appendFloat(&s, qx); s.append(",")
        appendFloat(&s, qy); s.append(",")
        appendFloat(&s, qz); s.append(",")
        appendFloat(&s, qw)
        s.append("]}\n")
        return s
    }

    /// Anchor event — takes pose as primitives.
    public static func anchorJson(
        timestampNanos: Int64,
        id: Int,
        tx: Float, ty: Float, tz: Float,
        qx: Float, qy: Float, qz: Float, qw: Float
    ) -> String {
        var s = ""
        s.reserveCapacity(192)
        s.append("{")
        appendCommonHeader(&s, timestampNanos: timestampNanos, type: "anchor", entity: "world/anchors/\(id)")
        s.append(",\"translation\":[")
        appendFloat(&s, tx); s.append(",")
        appendFloat(&s, ty); s.append(",")
        appendFloat(&s, tz)
        s.append("],\"quaternion\":[")
        appendFloat(&s, qx); s.append(",")
        appendFloat(&s, qy); s.append(",")
        appendFloat(&s, qz); s.append(",")
        appendFloat(&s, qw)
        s.append("]}\n")
        return s
    }

    /// Hit result event — takes position + distance.
    public static func hitResultJson(
        timestampNanos: Int64,
        id: Int,
        tx: Float, ty: Float, tz: Float,
        distance: Float
    ) -> String {
        var s = ""
        s.reserveCapacity(160)
        s.append("{")
        appendCommonHeader(&s, timestampNanos: timestampNanos, type: "hit_result", entity: "world/hits/\(id)")
        s.append(",\"translation\":[")
        appendFloat(&s, tx); s.append(",")
        appendFloat(&s, ty); s.append(",")
        appendFloat(&s, tz)
        s.append("],\"distance\":")
        appendFloat(&s, distance)
        s.append("}\n")
        return s
    }

    /// Point cloud event — takes already-split positions and confidences.
    /// Positions is a flat `[x0, y0, z0, x1, y1, z1, ...]` buffer; each 3
    /// floats form one point. Confidences is parallel, one per point.
    public static func pointCloudJson(
        timestampNanos: Int64,
        positions: [Float],
        confidences: [Float] = [],
        entity: String = "world/points"
    ) -> String {
        let n = positions.count / 3
        return pointCloudLine(
            timestampNanos: timestampNanos,
            pointCount: n,
            entity: entity,
            confidences: confidences,
            appendPoint: { s, i in
                appendFloat(&s, positions[i * 3]); s.append(",")
                appendFloat(&s, positions[i * 3 + 1]); s.append(",")
                appendFloat(&s, positions[i * 3 + 2])
            }
        )
    }

    /// SIMD-buffer point-cloud serializer — the exact code path the ARKit
    /// `pointCloud(_:)` production overload uses, minus the (unconstructible
    /// in a unit test) `ARPointCloud`. Reads each `SIMD3<Float>`'s x/y/z in
    /// the same order the flat-`[Float]` overload indexes its buffer, so the
    /// two produce byte-identical JSON. Exposed `internal` purely so a golden
    /// test can assert that equivalence without an AR session.
    internal static func pointCloudJson(
        timestampNanos: Int64,
        simdPositions: [SIMD3<Float>],
        confidences: [Float] = [],
        entity: String = "world/points"
    ) -> String {
        return pointCloudLine(
            timestampNanos: timestampNanos,
            pointCount: simdPositions.count,
            entity: entity,
            confidences: confidences,
            appendPoint: { s, i in
                let p = simdPositions[i]
                appendFloat(&s, p.x); s.append(",")
                appendFloat(&s, p.y); s.append(",")
                appendFloat(&s, p.z)
            }
        )
    }

    /// Shared point-cloud serializer. Both the flat-`[Float]` overload and
    /// the ARKit `[simd_float3]` production path funnel through this single
    /// implementation so they emit byte-identical JSON — the `appendPoint`
    /// closure is the only thing that varies (index into a flat buffer vs.
    /// read a SIMD vector), and it writes the same `x,y,z` sequence either
    /// way. Keeping one serializer also means a format change can never drift
    /// between the two entry points.
    private static func pointCloudLine(
        timestampNanos: Int64,
        pointCount n: Int,
        entity: String,
        confidences: [Float],
        appendPoint: (inout String, Int) -> Void
    ) -> String {
        var s = ""
        s.reserveCapacity(64 + n * 48)
        s.append("{")
        appendCommonHeader(&s, timestampNanos: timestampNanos, type: "point_cloud", entity: entity)
        s.append(",\"positions\":[")
        for i in 0..<n {
            if i > 0 { s.append(",") }
            s.append("[")
            appendPoint(&s, i)
            s.append("]")
        }
        s.append("],\"confidences\":[")
        for i in 0..<confidences.count {
            if i > 0 { s.append(",") }
            appendFloat(&s, confidences[i])
        }
        s.append("]}\n")
        return s
    }

    /// Plane event — takes a world-space polygon as a list of 3-float
    /// arrays. ARKit gives us a `simd_float3` array from the plane anchor
    /// geometry; the production `plane(_:timestamp:)` overload flattens
    /// that and calls this helper.
    public static func planeJson(
        timestampNanos: Int64,
        id: Int,
        kind: String,
        worldPolygon: [[Float]]
    ) -> String {
        var s = ""
        s.reserveCapacity(256 + worldPolygon.count * 48)
        s.append("{")
        appendCommonHeader(&s, timestampNanos: timestampNanos, type: "plane", entity: "world/planes/\(id)")
        s.append(",\"kind\":\"")
        s.append(kind)
        s.append("\",\"polygon\":[")
        for (idx, p) in worldPolygon.enumerated() {
            if idx > 0 { s.append(",") }
            s.append("[")
            if p.count >= 3 {
                appendFloat(&s, p[0]); s.append(",")
                appendFloat(&s, p[1]); s.append(",")
                appendFloat(&s, p[2])
            }
            s.append("]")
        }
        s.append("]}\n")
        return s
    }

    // MARK: - Production overloads (ARKit types) — iOS only

    #if os(iOS)

    /// Camera pose event — derives translation and quaternion from an
    /// ARFrame's camera `transform` (`simd_float4x4`).
    public static func cameraPose(
        timestampNanos: Int64,
        camera: ARCamera,
        entity: String = "world/camera"
    ) -> String {
        let t = camera.transform
        // Translation is the 4th column of the 4x4 transform.
        let tx = t.columns.3.x
        let ty = t.columns.3.y
        let tz = t.columns.3.z
        // Rotation quaternion from the upper-left 3x3.
        let q = simd_quatf(
            simd_float3x3(
                SIMD3(t.columns.0.x, t.columns.0.y, t.columns.0.z),
                SIMD3(t.columns.1.x, t.columns.1.y, t.columns.1.z),
                SIMD3(t.columns.2.x, t.columns.2.y, t.columns.2.z)
            )
        )
        return cameraPoseJson(
            timestampNanos: timestampNanos,
            tx: tx, ty: ty, tz: tz,
            qx: q.imag.x, qy: q.imag.y, qz: q.imag.z, qw: q.real,
            entity: entity
        )
    }

    /// Plane event — derives the world-space polygon from an ARPlaneAnchor.
    public static func plane(
        timestampNanos: Int64,
        anchor: ARPlaneAnchor
    ) -> String {
        // ARKit's ARPlaneGeometry.boundaryVertices is in the anchor's local
        // space. Lift each vertex to world space via anchor.transform.
        let xform = anchor.transform
        let verts = anchor.geometry.boundaryVertices
        var worldPoly: [[Float]] = []
        worldPoly.reserveCapacity(verts.count)
        for v in verts {
            let local = SIMD4<Float>(v.x, v.y, v.z, 1.0)
            let world = xform * local
            worldPoly.append([world.x, world.y, world.z])
        }
        let kind: String
        switch anchor.alignment {
        case .horizontal: kind = "horizontal_upward"
        case .vertical: kind = "vertical"
        @unknown default: kind = "unknown"
        }
        return planeJson(
            timestampNanos: timestampNanos,
            id: anchor.identifier.hashValue,
            kind: kind,
            worldPolygon: worldPoly
        )
    }

    /// Point cloud event — derives positions from an ARFrame's raw feature
    /// points. ARKit's `ARPointCloud.points` is `[simd_float3]`, which we
    /// flatten into the `[x, y, z, x, y, z, ...]` shape our testable
    /// overload expects. Confidences are not exposed by ARKit so we emit
    /// an empty list.
    public static func pointCloud(
        timestampNanos: Int64,
        cloud: ARPointCloud,
        entity: String = "world/points"
    ) -> String {
        // Serialize straight from the `[simd_float3]` buffer — no intermediate
        // flat `[Float]` reflatten per emit. `ARPointCloud.points` is already
        // `[SIMD3<Float>]`; the internal SIMD overload reads each vector's
        // x/y/z in the same order the flat-buffer overload does, so the
        // emitted JSON is byte-identical (covered by a golden test).
        return pointCloudJson(
            timestampNanos: timestampNanos,
            simdPositions: cloud.points,
            confidences: [],
            entity: entity
        )
    }

    /// Anchor event — takes any ARAnchor (ARPlaneAnchor is handled by
    /// `plane(_:timestamp:)` instead, this overload is for user-placed
    /// AnchorEntity-backed anchors).
    public static func anchor(
        timestampNanos: Int64,
        arAnchor: ARAnchor
    ) -> String {
        let t = arAnchor.transform
        let tx = t.columns.3.x
        let ty = t.columns.3.y
        let tz = t.columns.3.z
        let q = simd_quatf(
            simd_float3x3(
                SIMD3(t.columns.0.x, t.columns.0.y, t.columns.0.z),
                SIMD3(t.columns.1.x, t.columns.1.y, t.columns.1.z),
                SIMD3(t.columns.2.x, t.columns.2.y, t.columns.2.z)
            )
        )
        return anchorJson(
            timestampNanos: timestampNanos,
            id: arAnchor.identifier.hashValue,
            tx: tx, ty: ty, tz: tz,
            qx: q.imag.x, qy: q.imag.y, qz: q.imag.z, qw: q.real
        )
    }

    #endif

    // MARK: - Tier-S event types: camera trail + tracking-quality scalar

    /// Camera trail event — one polyline through every accumulated camera
    /// position so far. The Python sidecar maps this to `rr.LineStrips3D`,
    /// giving Rerun viewers a 3D trace of how the operator moved their
    /// phone during the session.
    ///
    /// `positions` is a flat `[x0, y0, z0, x1, y1, z1, …]` buffer; the
    /// caller is responsible for keeping it bounded (e.g. ring-buffer the
    /// last N samples) so the JSON line doesn't blow up over long sessions.
    public static func cameraTrailJson(
        timestampNanos: Int64,
        positions: [Float],
        entity: String = "world/camera/trail"
    ) -> String {
        let n = positions.count / 3
        var s = ""
        s.reserveCapacity(64 + n * 28)
        s.append("{")
        appendCommonHeader(&s, timestampNanos: timestampNanos, type: "camera_trail", entity: entity)
        s.append(",\"positions\":[")
        for i in 0..<n {
            if i > 0 { s.append(",") }
            s.append("[")
            appendFloat(&s, positions[i * 3]); s.append(",")
            appendFloat(&s, positions[i * 3 + 1]); s.append(",")
            appendFloat(&s, positions[i * 3 + 2])
            s.append("]")
        }
        s.append("]}\n")
        return s
    }

    /// Generic scalar timeseries event. The Python sidecar maps this to
    /// `rr.Scalars` so the value appears as a graph in the viewer's
    /// timeline panel. Use it for ARKit `trackingState` quality, feature
    /// point count, or any per-frame health metric.
    public static func scalarJson(
        timestampNanos: Int64,
        value: Float,
        entity: String
    ) -> String {
        var s = ""
        s.reserveCapacity(96)
        s.append("{")
        appendCommonHeader(&s, timestampNanos: timestampNanos, type: "scalar", entity: entity)
        s.append(",\"value\":")
        appendFloat(&s, value)
        s.append("}\n")
        return s
    }

    // MARK: - Control protocol
    //
    // In addition to event lines (camera_pose / plane / point_cloud / …) the
    // bridge can send "control" lines to ask the Python sidecar to perform
    // an action. The sidecar acknowledges by writing a single JSON line back
    // on the same socket.
    //
    // Wire format:
    //   client -> sidecar : {"type":"control","cmd":"save_now"}
    //   sidecar -> client : {"type":"control","ack":"saved","path":"…","viewerUrl":"…","events":N}
    //                       {"type":"control","ack":"save_unsupported","reason":"…"}
    //
    // Mirrors `RerunWireFormat.controlSaveNow()` and `parseControlAck` on the
    // Kotlin side — adding a new command requires touching both files plus
    // the Python sidecar generator (`mcp/packages/rerun/src/python-sidecar.ts`).

    /// "Save the current recording and reply with the resulting path + URL."
    public static func controlSaveNow() -> String {
        return "{\"type\":\"control\",\"cmd\":\"save_now\"}\n"
    }

    /// Parsed acknowledgment from the sidecar after a `save_now` command.
    public struct ControlAck: Equatable {
        /// `true` iff the sidecar wrote a file successfully.
        public let success: Bool
        /// Local path of the .rrd on the sidecar host (dev machine).
        public let path: String?
        /// URL into the SceneView Rerun viewer that already encodes the
        /// recording's location. Useful as a copy-paste hint — file:// URLs
        /// won't be fetched from an HTTPS page, so re-host first.
        public let viewerUrl: String?
        /// Number of events that made it into the file.
        public let events: Int
        /// Human-readable error reason on failure (e.g. live-mode sidecar).
        public let reason: String?
    }

    /// Best-effort parser for one JSON-lines string emitted by the sidecar.
    ///
    /// Returns `nil` if the line is not a control acknowledgment we
    /// recognise — callers should ignore it and keep reading. Hand-parsed
    /// (no `JSONDecoder`) to mirror the Kotlin parser byte-for-byte.
    public static func parseControlAck(_ line: String) -> ControlAck? {
        if !line.contains("\"type\":\"control\"") { return nil }
        if !line.contains("\"ack\"") { return nil }

        guard let ack = extractStringField(line, name: "ack") else { return nil }
        switch ack {
        case "saved":
            return ControlAck(
                success: true,
                path: extractStringField(line, name: "path"),
                viewerUrl: extractStringField(line, name: "viewerUrl"),
                events: extractIntField(line, name: "events") ?? 0,
                reason: nil
            )
        default:
            return ControlAck(
                success: false,
                path: nil,
                viewerUrl: nil,
                events: 0,
                reason: extractStringField(line, name: "reason") ?? ack
            )
        }
    }

    private static func extractStringField(_ line: String, name: String) -> String? {
        let key = "\"\(name)\":\""
        guard let keyRange = line.range(of: key) else { return nil }
        var i = keyRange.upperBound
        var out = ""
        while i < line.endIndex {
            let c = line[i]
            if c == "\\" {
                let next = line.index(after: i)
                guard next < line.endIndex else { return nil }
                let esc = line[next]
                switch esc {
                case "\"", "\\", "/": out.append(esc)
                case "n": out.append("\n")
                case "r": out.append("\r")
                case "t": out.append("\t")
                case "u":
                    let hexStart = line.index(after: next)
                    guard let hexEnd = line.index(hexStart, offsetBy: 4, limitedBy: line.endIndex) else { return nil }
                    if let code = UInt32(line[hexStart..<hexEnd], radix: 16),
                       let scalar = Unicode.Scalar(code) {
                        out.append(Character(scalar))
                    }
                    i = hexEnd
                    continue
                default: out.append(esc)
                }
                i = line.index(after: next)
                continue
            }
            if c == "\"" { return out }
            out.append(c)
            i = line.index(after: i)
        }
        return nil
    }

    private static func extractIntField(_ line: String, name: String) -> Int? {
        let key = "\"\(name)\":"
        guard let keyRange = line.range(of: key) else { return nil }
        var i = keyRange.upperBound
        // Skip whitespace.
        while i < line.endIndex, line[i].isWhitespace { i = line.index(after: i) }
        let numStart = i
        if i < line.endIndex, line[i] == "-" || line[i] == "+" {
            i = line.index(after: i)
        }
        while i < line.endIndex,
              let av = line[i].asciiValue,
              av >= 0x30, av <= 0x39 {
            i = line.index(after: i)
        }
        if numStart == i { return nil }
        return Int(line[numStart..<i])
    }

    // MARK: - Helpers

    private static func appendCommonHeader(
        _ s: inout String,
        timestampNanos t: Int64,
        type: String,
        entity: String
    ) {
        s.append("\"t\":")
        s.append(String(t))
        s.append(",\"type\":\"")
        s.append(type)
        s.append("\",\"entity\":\"")
        appendEscaped(&s, entity)
        s.append("\"")
    }

    /// Writes a float as JSON number. Non-finite values (NaN/Infinity)
    /// would break the JSON line; we emit `0` instead so the line stays
    /// parseable — matching the Kotlin bridge's behaviour byte-for-byte.
    ///
    /// Finite floats are rendered by `String(describing:)` which matches
    /// Kotlin's `Float.toString()` output for the same input, so the
    /// golden-JSON tests are directly comparable across the two bridges.
    private static func appendFloat(_ s: inout String, _ f: Float) {
        if !f.isFinite {
            s.append("0")
            return
        }
        s.append(String(describing: f))
    }

    /// Minimal JSON string escaper. Entity paths may legitimately contain
    /// `/` but we escape defensively against quotes and control chars.
    private static func appendEscaped(_ s: inout String, _ str: String) {
        for c in str {
            switch c {
            case "\"": s.append("\\\"")
            case "\\": s.append("\\\\")
            case "\n": s.append("\\n")
            case "\r": s.append("\\r")
            case "\t": s.append("\\t")
            default:
                if let ascii = c.asciiValue, ascii < 0x20 {
                    s.append(String(format: "\\u%04x", ascii))
                } else {
                    s.append(c)
                }
            }
        }
    }
}
