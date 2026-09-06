import Foundation

/// A 3D file format SceneViewSwift can open.
///
/// The raw value is the canonical lowercase file extension, so
/// `ModelFormat(rawValue: url.pathExtension.lowercased())` works, and
/// ``sniff(contentsOf:)`` is the entry point that decides for you — by content first,
/// extension second, because a file that arrived through Files, AirDrop or a share
/// sheet routinely has the wrong extension or none at all.
///
/// ```swift
/// switch try ModelFormat.sniff(contentsOf: url) {
/// case .stl, .obj, .ply: print("mesh file, needs a unit")
/// case .threeMF:         print("3MF — carries its own unit")
/// default:               print("USD/Reality — already in metres")
/// }
/// ```
public enum ModelFormat: String, Sendable, CaseIterable, Codable {

    // MARK: Handled by RealityKit itself

    /// Apple's zipped USD package. Read by RealityKit, already in metres.
    case usdz
    /// Reality Composer scene bundle. Read by RealityKit.
    case reality
    /// USD ASCII (`.usda`).
    case usda
    /// USD crate — binary USD (`.usdc`).
    case usdc
    /// Extension-agnostic USD (`.usd`) — ASCII or crate, RealityKit sniffs it.
    case usd

    // MARK: Mesh formats read through ModelIO

    /// Stereolithography — the 3D-printing lingua franca. ASCII or binary, no unit,
    /// no materials, per-facet normals. Defaults to ``ModelUnit/millimeters``.
    case stl
    /// Wavefront OBJ, with its sidecar `.mtl` when one sits next to it. No unit.
    case obj
    /// Polygon File Format (Stanford). ASCII or binary, optional per-vertex colours.
    /// No unit.
    case ply

    // MARK: Parsed by SceneViewSwift

    /// 3D Manufacturing Format — a zip container around an XML mesh description.
    /// The only mesh format in this list that carries its own unit, and the only one
    /// neither RealityKit nor ModelIO reads. See ``ThreeMFDocument``.
    case threeMF = "3mf"

    /// The canonical lowercase file extension, without a dot.
    public var fileExtension: String { rawValue }

    /// Which code path opens this format.
    public enum Loader: Sendable, Equatable {
        /// `Entity(contentsOf:)` — RealityKit's own USD/Reality reader.
        case realityKit
        /// `MDLAsset` — Apple's ModelIO importer.
        case modelIO
        /// SceneViewSwift's own parser.
        case sceneView
    }

    /// The reader used for this format.
    public var loader: Loader {
        switch self {
        case .usdz, .reality, .usda, .usdc, .usd: return .realityKit
        case .stl, .obj, .ply: return .modelIO
        case .threeMF: return .sceneView
        }
    }

    /// The unit assumed when the caller does not pass one and the file itself does not
    /// say.
    ///
    /// - `.stl` → ``ModelUnit/millimeters``: STL has no unit field, and essentially every
    ///   STL in circulation comes out of a CAD/slicer pipeline that means millimetres.
    /// - `.obj`, `.ply` → ``ModelUnit/meters``: also unitless, but their common sources
    ///   (photogrammetry, scanners, DCC exports) author in metres. Pass `unit:`
    ///   explicitly when you know better — that is what the parameter is for.
    /// - `.threeMF` → ``ModelUnit/millimeters``: the 3MF core spec's own default for a
    ///   missing `unit` attribute. A 3MF that *declares* a unit overrides this.
    /// - USD / Reality → ``ModelUnit/meters``: RealityKit has already applied the
    ///   asset's `metersPerUnit`.
    public var defaultUnit: ModelUnit {
        switch self {
        case .stl, .threeMF: return .millimeters
        case .obj, .ply, .usdz, .reality, .usda, .usdc, .usd: return .meters
        }
    }

    /// Whether the format carries a real-world unit of its own, making a caller-supplied
    /// ``ModelUnit`` a correction rather than a guess.
    ///
    /// Drives the "what unit is this?" prompt a viewer should show: ask for `.stl`,
    /// `.obj` and `.ply`; stay quiet for `.3mf` and USD.
    public var carriesUnit: Bool {
        switch self {
        case .threeMF, .usdz, .reality, .usda, .usdc, .usd: return true
        case .stl, .obj, .ply: return false
        }
    }

    /// Creates a format from a file extension, with or without a leading dot, in any
    /// case. Returns `nil` for anything not in this enum.
    public init?(fileExtension: String) {
        var ext = fileExtension.lowercased()
        if ext.hasPrefix(".") { ext.removeFirst() }
        // `.gltf`/`.glb` deliberately absent: neither RealityKit nor ModelIO reads
        // them, so claiming the extension would produce a load that always fails.
        guard let format = ModelFormat(rawValue: ext) else { return nil }
        self = format
    }

    // MARK: - Sniffing

    /// Identifies the format of a file, by content first and file extension second.
    ///
    /// Content wins because the extension is the least reliable thing about a file that
    /// reached the app through a share sheet, a download, or a messaging app — and
    /// because two of these formats share a container: **USDZ and 3MF are both zip
    /// archives**, told apart here by the name of the archive's first entry.
    ///
    /// The signatures that are treated as decisive:
    ///
    /// | Format | Evidence |
    /// |---|---|
    /// | `.usdz` / `.3mf` | `PK\x03\x04`, then the first entry's name (`*.usd*` → USDZ, else 3MF) |
    /// | `.stl` (binary) | `84 + 50 × triangleCount == fileSize` — checked **before** the ASCII test, because binary STLs routinely start with the word `solid` too |
    /// | `.stl` (ASCII) | starts with `solid` and the header is printable text |
    /// | `.ply` | starts with `ply` + newline |
    /// | `.usda` | starts with `#usda` |
    /// | `.usdc` | starts with `PXR-USDC` |
    ///
    /// OBJ has no signature and is therefore only ever identified by its extension.
    ///
    /// - Parameter url: A local file URL.
    /// - Returns: The detected format.
    /// - Throws: ``ModelLoadingError/unreadableFile(_:)`` when the file cannot be read,
    ///   ``ModelLoadingError/unsupportedFormat(fileExtension:)`` when neither the content
    ///   nor the extension identifies a supported format.
    public static func sniff(contentsOf url: URL) throws -> ModelFormat {
        let handle: FileHandle
        do {
            handle = try FileHandle(forReadingFrom: url)
        } catch {
            throw ModelLoadingError.unreadableFile(url)
        }
        defer { try? handle.close() }

        let size = (try? FileManager.default
            .attributesOfItem(atPath: url.path)[.size] as? Int) ?? nil
        let header = (try? handle.read(upToCount: signatureProbeBytes)) ?? Data()

        if let sniffed = sniff(header: header, fileSize: size ?? header.count) {
            return sniffed
        }
        if let byExtension = ModelFormat(fileExtension: url.pathExtension) {
            return byExtension
        }
        throw ModelLoadingError.unsupportedFormat(fileExtension: url.pathExtension)
    }

    /// How much of the file ``sniff(contentsOf:)`` reads. Enough for a zip local header
    /// plus a long first entry name, and for a text header to be recognisably text.
    static let signatureProbeBytes = 512

    /// Content-only detection, split out so it is testable from raw bytes.
    ///
    /// - Parameters:
    ///   - header: The first ``signatureProbeBytes`` bytes (fewer for a short file).
    ///   - fileSize: The total file size — load-bearing for the binary-STL test, which
    ///     is a size arithmetic check, not a magic number.
    /// - Returns: The format, or `nil` when the content is not decisive.
    static func sniff(header: Data, fileSize: Int) -> ModelFormat? {
        guard !header.isEmpty else { return nil }
        let bytes = [UInt8](header)

        // --- Zip container: USDZ and 3MF both live here ---------------------------
        // USDZ stores its `.usdc` first and uncompressed; a 3MF's first entry is
        // `[Content_Types].xml` or a `_rels`/`3D` part. Neither has a magic number of its
        // own, so the first entry's name is the only evidence inside the probe window.
        if bytes.starts(with: [0x50, 0x4B, 0x03, 0x04]) {  // "PK\3\4"
            return zipFirstEntryName(bytes).map { name in
                (name as NSString).pathExtension.lowercased().hasPrefix("usd") ? .usdz : .threeMF
            } ?? .threeMF
        }

        // --- Binary STL: arithmetic, not a magic number ------------------------
        //
        // 80-byte header + UInt32 triangle count + 50 bytes per triangle. This must be
        // tested BEFORE the ASCII "solid" prefix: exporters write arbitrary text into
        // the 80-byte header and a great many of them start it with "solid", so the
        // prefix test alone mis-reads a binary STL as ASCII and yields an empty mesh.
        if fileSize >= 84, bytes.count >= 84 {
            let count = UInt32(bytes[80])
                | UInt32(bytes[81]) << 8
                | UInt32(bytes[82]) << 16
                | UInt32(bytes[83]) << 24
            if count > 0, 84 + 50 * Int(count) == fileSize {
                return .stl
            }
        }

        // --- Text signatures ----------------------------------------------------
        if bytes.starts(with: Array("PXR-USDC".utf8)) { return .usdc }
        if bytes.starts(with: Array("#usda".utf8)) { return .usda }
        if bytes.starts(with: Array("ply".utf8)),
           bytes.count > 3, bytes[3] == 0x0A || bytes[3] == 0x0D {
            return .ply
        }
        if bytes.starts(with: Array("solid".utf8)), isPrintableText(bytes) {
            return .stl
        }
        return nil
    }

    /// Reads the file name of a zip archive's first local file header.
    ///
    /// Only the fixed 30-byte header is needed: bytes 26–27 are the name length and the
    /// name follows the header. Returns `nil` if the probe window does not contain the
    /// whole name — in which case ``sniff(header:fileSize:)`` falls back to 3MF, the
    /// format for which a long first entry name is plausible.
    private static func zipFirstEntryName(_ bytes: [UInt8]) -> String? {
        guard bytes.count >= 30 else { return nil }
        let nameLength = Int(bytes[26]) | Int(bytes[27]) << 8
        guard nameLength > 0, 30 + nameLength <= bytes.count else { return nil }
        return String(bytes: bytes[30..<(30 + nameLength)], encoding: .utf8)
    }

    /// Whether a header looks like text rather than a binary blob — tab, newline,
    /// carriage return and printable ASCII only. Used to separate an ASCII STL from a
    /// binary one whose 80-byte header happens to begin with `solid`.
    private static func isPrintableText(_ bytes: [UInt8]) -> Bool {
        for byte in bytes.prefix(128) {
            let isPrintable = (0x20...0x7E).contains(byte)
                || byte == 0x09 || byte == 0x0A || byte == 0x0D
            if !isPrintable { return false }
        }
        return true
    }
}
