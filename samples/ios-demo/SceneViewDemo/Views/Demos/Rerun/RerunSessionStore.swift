import Foundation
import Observation
import simd

// "Your sessions": every recording the user makes, and every scan file or .rrd they open, is
// kept on the phone until they delete it. One directory per session, holding the recorder's
// own three files — so a stored session replays through exactly the code path a fresh
// recording does, and nothing about it is second-class.

/// A session kept on the phone: what the list shows without opening it.
struct RerunStoredSession: Identifiable, Codable, Equatable, Sendable {
    /// Where the session came from.
    enum Source: String, Codable, Sendable {
        /// Recorded on this phone with Record your room.
        case recorded
        /// Opened from a SceneView scan file (`.svscan`).
        case scan
        /// Opened from a Rerun recording (`.rrd`) this app exported.
        case rrd
    }

    var id: UUID
    var title: String
    var createdAt: Date
    var source: Source
    /// Seconds from the first event to the last.
    var duration: Float
    var pathMetres: Float
    var points: Int
    var planes: Int
    var photos: Int
}

/// The sessions directory: `Application Support/RerunSessions/<id>/` with the capture's three
/// files (``RerunCapturePack``), `session.json` (``RerunStoredSession``) and, when the capture
/// has photos, `thumbnail.jpg` (its first photo, as recorded).
struct RerunSessionStore: Sendable {
    enum Failure: Error, Equatable {
        /// The file is not one this app reads: a `.svscan` or a `.rrd` it wrote.
        case unsupportedFile(String)
        /// The file has the right extension but its content does not parse.
        case unreadable(String)
        /// The file parsed but holds no camera pose, point or plane: nothing to replay.
        case empty
    }

    static let infoFileName = "session.json"
    static let thumbnailFileName = "thumbnail.jpg"

    let root: URL

    /// The app's own store.
    static var standard: RerunSessionStore {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return RerunSessionStore(root: support.appendingPathComponent("RerunSessions", isDirectory: true))
    }

    func directory(for id: UUID) -> URL {
        root.appendingPathComponent(id.uuidString, isDirectory: true)
    }

    /// Every readable session, newest first. A directory without a readable `session.json`
    /// (a save cut short) is skipped, not shown broken.
    func list() -> [RerunStoredSession] {
        let entries = (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? []
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return entries
            .compactMap { url -> RerunStoredSession? in
                guard let data = try? Data(contentsOf: url.appendingPathComponent(Self.infoFileName)) else { return nil }
                return try? decoder.decode(RerunStoredSession.self, from: data)
            }
            .sorted { $0.createdAt > $1.createdAt }
    }

    /// Keeps `capture` as a new session. The capture files are written before `session.json`,
    /// so a session only lists once it is whole.
    @discardableResult
    func save(_ capture: RerunCapturePack, title: String, source: RerunStoredSession.Source,
              now: Date = Date()) throws -> RerunStoredSession {
        let pack: RerunPack
        do {
            pack = try RerunPack.load(manifest: capture.manifest, log: capture.log, media: capture.media, title: title)
        } catch {
            throw Failure.unreadable(title)
        }
        guard !pack.trace.isEmpty else { throw Failure.empty }
        let last = RerunStats(frame: pack.trace.frameAt(pack.trace.duration))
        // Whole seconds: `session.json` stores ISO 8601, so what `save` returns equals what `list` reads.
        let createdAt = Date(timeIntervalSince1970: now.timeIntervalSince1970.rounded(.down))
        let session = RerunStoredSession(
            id: UUID(), title: title, createdAt: createdAt, source: source,
            duration: pack.trace.duration, pathMetres: last.pathMetres, points: last.mapPoints,
            planes: last.planes, photos: pack.trace.imageCount
        )
        let directory = directory(for: session.id)
        try capture.write(to: directory)
        if let first = pack.trace.imagePaths.first, let photo = pack.bytes(for: first) {
            try? photo.write(to: directory.appendingPathComponent(Self.thumbnailFileName), options: .atomic)
        }
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try encoder.encode(session).write(to: directory.appendingPathComponent(Self.infoFileName), options: .atomic)
        return session
    }

    func capture(for id: UUID) -> RerunCapturePack? {
        RerunCapturePack.read(from: directory(for: id))
    }

    func thumbnailURL(for id: UUID) -> URL? {
        let url = directory(for: id).appendingPathComponent(Self.thumbnailFileName)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    func delete(_ id: UUID) throws {
        let directory = directory(for: id)
        guard FileManager.default.fileExists(atPath: directory.path) else { return }
        try FileManager.default.removeItem(at: directory)
    }

    /// Reads a file the user opened — a scan file or a `.rrd` this app exported — and keeps it
    /// as a new session titled after the file.
    @discardableResult
    func importFile(at url: URL, now: Date = Date()) throws -> RerunStoredSession {
        let ext = url.pathExtension.lowercased()
        let name = url.deletingPathExtension().lastPathComponent
        let source: RerunStoredSession.Source
        switch ext {
        case RerunScanFile.fileExtension: source = .scan
        case "rrd": source = .rrd
        default: throw Failure.unsupportedFile(url.lastPathComponent)
        }
        let data: Data
        do {
            data = try Data(contentsOf: url, options: .mappedIfSafe)
        } catch {
            throw Failure.unreadable(url.lastPathComponent)
        }
        let capture: RerunCapturePack
        do {
            capture = source == .scan ? try RerunScanFile.capture(from: data) : try RerunRRDReader.capturePack(from: data)
        } catch RerunRRDReader.Failure.nothingToReplay {
            throw Failure.empty
        } catch {
            throw Failure.unreadable(url.lastPathComponent)
        }
        return try save(capture, title: name.isEmpty ? "Opened session" : name, source: source, now: now)
    }

    /// The session as a scan file in a fresh temporary directory, named after its title — what
    /// Share hands to the system.
    func scanFile(for session: RerunStoredSession) throws -> URL {
        guard let capture = capture(for: session.id) else { throw Failure.unreadable(session.title) }
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("rerun-scan-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent(RerunScanFile.fileName(for: session.title))
        try RerunScanFile.data(for: capture).write(to: url, options: .atomic)
        return url
    }

    /// "Room · Sep 28, 14:32": the title a fresh recording gets.
    static func recordingTitle(at date: Date, locale: Locale = .current, timeZone: TimeZone = .current) -> String {
        var style = Date.FormatStyle(date: .abbreviated, time: .shortened)
        style.locale = locale
        style.timeZone = timeZone
        return "Room · " + date.formatted(style)
    }
}

/// The app's own recording format as one file: a stored (uncompressed) zip of the recorder's
/// three files, `capture-manifest.json`, `capture-session.jsonl` and `capture-media.bin`, under
/// those exact names. The same layout the recorder writes to disk, so any zip tool can unpack
/// it and any SceneView app can replay it.
enum RerunScanFile {
    static let fileExtension = "svscan"
    /// Exported by the app's Info.plist; conforms to `public.data`.
    static let typeIdentifier = "io.github.sceneview.scan"

    enum Failure: Error, Equatable {
        case notAScan
        case missing(String)
    }

    static func data(for capture: RerunCapturePack) -> Data {
        RerunUSDZWriter.Archive.write([
            (RerunCapturePack.manifestFileName, capture.manifest),
            (RerunCapturePack.logFileName, capture.log),
            (RerunCapturePack.mediaFileName, capture.media),
        ])
    }

    static func capture(from data: Data) throws -> RerunCapturePack {
        let files: [(path: String, data: Data)]
        do {
            files = try RerunUSDZWriter.Archive.read(data, model: "scan")
        } catch {
            throw Failure.notAScan
        }
        func file(_ name: String) throws -> Data {
            guard let found = files.first(where: { $0.path == name }) else { throw Failure.missing(name) }
            return found.data
        }
        return RerunCapturePack(
            manifest: try file(RerunCapturePack.manifestFileName),
            log: try file(RerunCapturePack.logFileName),
            media: try file(RerunCapturePack.mediaFileName)
        )
    }

    /// A file name from a session title: path separators and colons replaced, never empty.
    static func fileName(for title: String) -> String {
        let cleaned = title
            .map { "/\\:".contains($0) ? "-" : $0 }
            .reduce(into: "") { $0.append($1) }
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return (cleaned.isEmpty ? "scan" : cleaned) + "." + fileExtension
    }
}

/// A scan file or `.rrd` handed to the app — by Files, AirDrop, Mail or any share sheet, or by
/// `-open_file` — waiting for the Rerun demo to import it. The app copies the file in first, while
/// it holds the security-scoped access, so the demo never reads a URL it may have lost.
@MainActor
@Observable
final class RerunInbox {
    static let shared = RerunInbox()

    /// The copied file the demo has not imported yet.
    private(set) var pending: URL?

    /// Whether the Rerun demo, rather than the 3D file viewer, opens `url`.
    nonisolated static func handles(_ url: URL) -> Bool {
        [RerunScanFile.fileExtension, "rrd"].contains(url.pathExtension.lowercased())
    }

    /// Copies `url` into a fresh temporary directory and queues the copy. `false` when the
    /// file cannot be read.
    @discardableResult
    func accept(_ url: URL) -> Bool {
        guard let copy = Self.copyIn(url) else { return false }
        pending = copy
        return true
    }

    /// Hands the queued file over, once.
    func take() -> URL? {
        defer { pending = nil }
        return pending
    }

    /// `url` copied somewhere the app owns, keeping its name. Holds the security-scoped access
    /// for the copy only — a file picker's or an "Open in place" URL needs it.
    nonisolated static func copyIn(_ url: URL) -> URL? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("rerun-inbox-\(UUID().uuidString)", isDirectory: true)
        let copy = directory.appendingPathComponent(url.lastPathComponent)
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try FileManager.default.copyItem(at: url, to: copy)
            return copy
        } catch {
            return nil
        }
    }
}
