import Foundation
import CryptoKit
import Network
import SwiftUI

/// The HD pack: heavy demo assets the app downloads once and keeps.
///
/// The store build stays small; a scene that wants a 50 MB model shows its
/// bundled stand-in instantly and swaps to the HD asset when the file is on
/// disk. Shared contract with Android (`assets/hd-pack/android.json`):
///
/// - **Hosting.** GitHub Release `hd-pack-v1`, files immutable and named
///   `<sha256>.<ext>` — see ``HDPackManifest/releaseBase``.
/// - **Manifest.** `assets/hd-pack/ios.json`, bundled in the app as `ios.json`.
///   Asset `id`s are shared across platforms.
/// - **Storage.** `Application Support/HDPack/<sha256>.<ext>`, excluded from
///   iCloud backup. Never evicted on use; only hashes that left the manifest
///   are deleted (``HDPackStore/pruneStale()``).
/// - **Download.** After first launch, on unmetered networks only: a background
///   `URLSession` that refuses expensive (cellular, hotspot) and constrained
///   (Low Data Mode) paths. On such a path nothing starts on its own; the
///   Settings row's "Download now", which states the size first (App Review
///   4.2.3(ii)), uses a second session that may use them.
/// - **Integrity.** Every file is hashed before it is renamed into place;
///   a mismatch is discarded, never kept.

// MARK: - Manifest

struct HDPackAsset: Decodable, Identifiable, Hashable, Sendable {
    let id: String
    let title: String
    let file: String
    let sha256: String
    let bytes: Int64
    let license: String
    let author: String
    let source: String

    /// Where the release serves this file.
    var remoteURL: URL { HDPackManifest.releaseBase.appendingPathComponent(file) }
}

struct HDPackManifest: Decodable, Sendable {
    let version: Int
    let assets: [HDPackAsset]

    /// Content-addressed files of the `hd-pack-v1` release on sceneview/sceneview.
    static let releaseBase = URL(string: "https://github.com/sceneview/sceneview/releases/download/hd-pack-v1/")!

    /// The bundled resource name: `assets/hd-pack/ios.json` is added to the
    /// target by reference, so it lands in the bundle under its own file name.
    static let resourceName = "ios"

    static func loadBundled(bundle: Bundle = .main) -> HDPackManifest {
        guard
            let url = bundle.url(forResource: resourceName, withExtension: "json"),
            let data = try? Data(contentsOf: url),
            let manifest = try? JSONDecoder().decode(HDPackManifest.self, from: data)
        else {
            return HDPackManifest(version: 1, assets: [])
        }
        return manifest
    }

    func asset(id: String) -> HDPackAsset? { assets.first { $0.id == id } }

    var totalBytes: Int64 { assets.reduce(0) { $0 + $1.bytes } }
}
// MARK: - State

enum HDAssetState: Equatable, Sendable {
    /// Not on disk and nothing scheduled (never fetched, or removed by the user).
    case missing
    /// Automatic prefetch queued, no byte yet: it waits for an unmetered network.
    case waitingForWiFi
    /// "Download now" queued, no byte yet: any network will do.
    case waitingForNetwork
    /// Receiving bytes; the value is 0...1.
    case downloading(Double)
    /// Verified and on disk.
    case ready
    /// The last attempt failed. A network error resumes on the next launch;
    /// a checksum mismatch backs off (see ``HDPackStore``).
    case failed
}

/// Sizes as the shared copy spells them ("52 MB"): whole megabytes, SI units,
/// the same rounding Android uses for its own file size.
enum HDPackFormat {
    static func size(_ bytes: Int64) -> String {
        // No-break space: "52 MB" never splits across two lines.
        "\(max(1, Int((Double(bytes) / 1_000_000).rounded())))\u{00A0}MB"
    }
}

// MARK: - Files

/// Pure file-system side of the pack. `nonisolated` and stateless so the
/// download delegate can call it from URLSession's queue — the downloaded
/// temporary file must be moved before the delegate method returns.
enum HDPackFiles {
    static var directory: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("HDPack", isDirectory: true)
    }

    /// Resume data of interrupted transfers. Caches: losing it only means
    /// restarting a transfer from zero.
    static var resumeDirectory: URL {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("HDPackResume", isDirectory: true)
    }

    static func url(forFile file: String) -> URL {
        directory.appendingPathComponent(file, isDirectory: false)
    }

    /// Creates the directory and flags it (and so everything in it) as
    /// excluded from iCloud backup: the pack is re-downloadable.
    static func ensureDirectory() throws {
        var dir = directory
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try dir.setResourceValues(values)
    }

    static func sha256(of url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while let chunk = try handle.read(upToCount: 4 << 20), !chunk.isEmpty {
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    enum InstallError: Error { case checksumMismatch, badName }

    /// Verifies `temp` against the hash in `file` (`<sha256>.<ext>`), then
    /// renames it into place atomically: a reader sees either no file or the
    /// whole verified file, never a partial one.
    static func install(downloaded temp: URL, as file: String) throws {
        guard let expected = file.split(separator: ".").first.map(String.init), expected.count == 64 else {
            throw InstallError.badName
        }
        try ensureDirectory()
        // Same volume as the destination, so the final step is a rename(2).
        let staging = directory.appendingPathComponent(".\(file).partial")
        try? FileManager.default.removeItem(at: staging)
        try FileManager.default.moveItem(at: temp, to: staging)
        guard try sha256(of: staging) == expected else {
            try? FileManager.default.removeItem(at: staging)
            throw InstallError.checksumMismatch
        }
        var destination = url(forFile: file)
        guard rename(staging.path, destination.path) == 0 else {
            try? FileManager.default.removeItem(at: staging)
            throw CocoaError(.fileWriteUnknown)
        }
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? destination.setResourceValues(values)
    }

    /// Bytes currently used by the pack on disk.
    static func usedBytes() -> Int64 {
        guard let items = try? FileManager.default.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: [.fileSizeKey]) else { return 0 }
        return items.reduce(0) { total, url in
            total + Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
    }

    static func saveResumeData(_ data: Data, for file: String) {
        try? FileManager.default.createDirectory(at: resumeDirectory, withIntermediateDirectories: true)
        try? data.write(to: resumeDirectory.appendingPathComponent(file + ".resume"), options: .atomic)
    }

    /// Returns and forgets the resume data of `file`: it is valid once.
    static func takeResumeData(for file: String) -> Data? {
        let url = resumeDirectory.appendingPathComponent(file + ".resume")
        defer { try? FileManager.default.removeItem(at: url) }
        return try? Data(contentsOf: url)
    }
}

// MARK: - Store

/// App-wide owner of the pack: states, the two download sessions, removal.
@MainActor
final class HDPackStore: ObservableObject {
    static let shared = HDPackStore()

    let manifest: HDPackManifest

    @Published private(set) var states: [String: HDAssetState] = [:]
    /// Bytes the pack occupies on disk right now.
    @Published private(set) var usedBytes: Int64 = 0
    /// Cellular or a personal hotspot: "Download now" says it uses mobile data.
    @Published private(set) var isExpensive = false
    /// Low Data Mode: the automatic prefetch is paused, not "waiting for Wi-Fi".
    @Published private(set) var isConstrained = false
    /// "HD scenes removed · 52 MB freed", shown for a few seconds after Remove.
    @Published private(set) var removalNotice: String?

    /// Set by "Remove": no automatic download until the user asks again.
    private static let userRemovedKey = "hd_pack_user_removed"
    /// `[file: [count, lastFailure]]` of checksum mismatches, for the back-off.
    private static let mismatchKey = "hd_pack_checksum_mismatches"

    /// Automatic prefetch — unmetered networks only.
    static let autoSessionID = "dev.sceneview.demo.hdpack.auto"
    /// "Download now" — the user accepted the size, any network.
    static let userSessionID = "dev.sceneview.demo.hdpack.user"

    private lazy var autoSession: URLSession = Self.makeSession(id: Self.autoSessionID, allowMetered: false)
    private lazy var userSession: URLSession = Self.makeSession(id: Self.userSessionID, allowMetered: true)
    private let pathMonitor = NWPathMonitor()
    private var bootstrapped = false

    /// iOS hands this over when it relaunches the app for finished background
    /// transfers; called once both sessions reported their events.
    var backgroundEventsCompletion: [String: () -> Void] = [:]

    /// Unit tests host the app: no 52 MB transfer on CI and no prune racing
    /// the install tests.
    private static let isHostingUnitTests =
        ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil

    init(manifest: HDPackManifest = .loadBundled()) {
        self.manifest = manifest
        for asset in manifest.assets {
            states[asset.id] = Self.isOnDisk(asset) ? .ready : .missing
        }
        usedBytes = HDPackFiles.usedBytes()
    }

    private static func makeSession(id: String, allowMetered: Bool) -> URLSession {
        let config = URLSessionConfiguration.background(withIdentifier: id)
        config.allowsExpensiveNetworkAccess = allowMetered
        config.allowsConstrainedNetworkAccess = allowMetered
        config.allowsCellularAccess = allowMetered
        config.isDiscretionary = false
        config.sessionSendsLaunchEvents = true
        return URLSession(configuration: config, delegate: HDPackDownloadDelegate(), delegateQueue: nil)
    }

    private static func isOnDisk(_ asset: HDPackAsset) -> Bool {
        let url = HDPackFiles.url(forFile: asset.file)
        guard let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize else { return false }
        return Int64(size) == asset.bytes
    }

    // MARK: Queries

    func state(for id: String) -> HDAssetState { states[id] ?? .missing }

    /// The verified local file, or `nil` while it is not on disk.
    func localURL(for id: String) -> URL? {
        guard let asset = manifest.asset(id: id), state(for: id) == .ready else { return nil }
        return HDPackFiles.url(forFile: asset.file)
    }

    var totalBytes: Int64 { manifest.totalBytes }

    var isComplete: Bool { !manifest.assets.isEmpty && manifest.assets.allSatisfy { state(for: $0.id) == .ready } }

    /// Aggregate state of the whole pack, for the About row.
    var packState: HDAssetState {
        let all = manifest.assets.map { state(for: $0.id) }
        if all.isEmpty { return .missing }
        if all.allSatisfy({ $0 == .ready }) { return .ready }
        let received = manifest.assets.reduce(Double(0)) { sum, asset in
            switch state(for: asset.id) {
            case .ready: return sum + Double(asset.bytes)
            case .downloading(let f): return sum + f * Double(asset.bytes)
            default: return sum
            }
        }
        if all.contains(where: { if case .downloading = $0 { return true } else { return false } }) {
            return .downloading(received / Double(max(totalBytes, 1)))
        }
        if all.contains(.waitingForNetwork) { return .waitingForNetwork }
        if all.contains(.waitingForWiFi) { return .waitingForWiFi }
        if all.contains(.failed) { return .failed }
        return .missing
    }

    // MARK: Lifecycle

    /// Once per launch: drop files that left the manifest, re-attach to the
    /// transfers iOS kept running while the app was gone, then prefetch what
    /// is missing — unless the user removed the pack.
    func bootstrap() {
        guard !bootstrapped, !Self.isHostingUnitTests else { return }
        bootstrapped = true
        pruneStale()
        pathMonitor.pathUpdateHandler = { path in
            let expensive = path.isExpensive
            let constrained = path.isConstrained
            Task { @MainActor in
                HDPackStore.shared.isExpensive = expensive
                HDPackStore.shared.isConstrained = constrained
            }
        }
        pathMonitor.start(queue: DispatchQueue(label: "dev.sceneview.demo.hdpack.path"))
        #if DEBUG
        // QA captures only: the simulator shares the Mac's network, so
        // `-hdpack_offline 1` stands for "no Wi-Fi yet": nothing is scheduled
        // and every missing asset shows the state a queued prefetch shows.
        if let i = CommandLine.arguments.firstIndex(of: "-hdpack_offline"),
           i + 1 < CommandLine.arguments.count, CommandLine.arguments[i + 1] == "1" {
            for asset in manifest.assets where state(for: asset.id) != .ready { states[asset.id] = .waitingForWiFi }
            return
        }
        #endif
        Task {
            await reattach()
            if !UserDefaults.standard.bool(forKey: Self.userRemovedKey) {
                await schedule(on: autoSession, userRequested: false)
            }
        }
    }

    /// Deletes every file in the pack directory whose name is not a file of
    /// the current manifest. Nothing else is ever evicted.
    func pruneStale() {
        let keep = Set(manifest.assets.map(\.file))
        let dir = HDPackFiles.directory
        guard let names = try? FileManager.default.contentsOfDirectory(atPath: dir.path) else { return }
        for name in names where !keep.contains(name) {
            try? FileManager.default.removeItem(at: dir.appendingPathComponent(name))
        }
        usedBytes = HDPackFiles.usedBytes()
    }

    /// The user accepted the size: download everything missing now, on any network.
    func downloadNow() {
        UserDefaults.standard.set(false, forKey: Self.userRemovedKey)
        Task {
            // A prefetch still waiting for Wi-Fi would duplicate the transfer;
            // what it already received carries over as resume data.
            for task in await autoSession.allTasks {
                guard let download = task as? URLSessionDownloadTask, let file = task.taskDescription else {
                    task.cancel()
                    continue
                }
                if let data = await download.cancelByProducingResumeData() {
                    HDPackFiles.saveResumeData(data, for: file)
                }
            }
            await schedule(on: userSession, userRequested: true)
        }
    }

    /// Cancels every transfer, waits until the sessions let go of them, then
    /// deletes the pack and keeps it that way until "Download now".
    func remove() {
        UserDefaults.standard.set(true, forKey: Self.userRemovedKey)
        let freed = HDPackFiles.usedBytes()
        Task {
            let tasks = await autoSession.allTasks + userSession.allTasks
            for task in tasks { task.cancel() }
            // A transfer finishing mid-cancel would install its file after the
            // delete; `finished` also drops a file that lands after Remove.
            for _ in 0..<30 {
                let live = await autoSession.allTasks + userSession.allTasks
                if live.allSatisfy({ $0.state == .completed }) { break }
                try? await Task.sleep(nanoseconds: 100_000_000)
            }
            try? FileManager.default.removeItem(at: HDPackFiles.directory)
            try? FileManager.default.removeItem(at: HDPackFiles.resumeDirectory)
            for asset in manifest.assets { states[asset.id] = .missing }
            usedBytes = HDPackFiles.usedBytes()
            let notice = "HD scenes removed · \(HDPackFormat.size(freed)) freed"
            removalNotice = notice
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            if removalNotice == notice { removalNotice = nil }
        }
    }

    /// Rebuilds in-flight states from both sessions after a relaunch.
    private func reattach() async {
        for (session, user) in [(autoSession, false), (userSession, true)] {
            for task in await session.allTasks {
                guard let file = task.taskDescription,
                      let asset = manifest.assets.first(where: { $0.file == file }),
                      state(for: asset.id) != .ready else { continue }
                let received = task.countOfBytesReceived
                states[asset.id] = received > 0
                    ? .downloading(Double(received) / Double(asset.bytes))
                    : (user ? .waitingForNetwork : .waitingForWiFi)
            }
        }
    }

    private func schedule(on session: URLSession, userRequested: Bool) async {
        var inFlight = Set<String>()
        for s in [autoSession, userSession] {
            for task in await s.allTasks where task.state == .running || task.state == .suspended {
                if let file = task.taskDescription { inFlight.insert(file) }
            }
        }
        for asset in manifest.assets where state(for: asset.id) != .ready && !inFlight.contains(asset.file) {
            // A file that failed its checksum is not fetched again on its own
            // at every launch; "Download now" always tries.
            if !userRequested, isBackingOff(asset.file) {
                states[asset.id] = .failed
                continue
            }
            let task = HDPackFiles.takeResumeData(for: asset.file).map { session.downloadTask(withResumeData: $0) }
                ?? session.downloadTask(with: asset.remoteURL)
            task.taskDescription = asset.file
            task.countOfBytesClientExpectsToReceive = asset.bytes
            task.resume()
            states[asset.id] = userRequested ? .waitingForNetwork : .waitingForWiFi
        }
    }

    // MARK: Checksum back-off

    /// One day after the first mismatch, doubling each time, capped at a week.
    private func isBackingOff(_ file: String) -> Bool {
        guard let entry = (UserDefaults.standard.dictionary(forKey: Self.mismatchKey) ?? [:])[file] as? [Double],
              entry.count == 2 else { return false }
        let delay = min(86_400 * pow(2, entry[0] - 1), 7 * 86_400)
        return Date().timeIntervalSince1970 < entry[1] + delay
    }

    fileprivate func checksumMismatch(file: String) {
        var all = UserDefaults.standard.dictionary(forKey: Self.mismatchKey) ?? [:]
        let count = ((all[file] as? [Double])?.first ?? 0) + 1
        all[file] = [count, Date().timeIntervalSince1970]
        UserDefaults.standard.set(all, forKey: Self.mismatchKey)
    }

    // MARK: Delegate callbacks (main actor)

    fileprivate func progress(file: String, received: Int64) {
        guard let asset = manifest.assets.first(where: { $0.file == file }), state(for: asset.id) != .ready else { return }
        let fraction = min(1, Double(received) / Double(max(asset.bytes, 1)))
        // Publish whole-percent steps only: a 50 MB file reports hundreds of chunks.
        if case .downloading(let old) = state(for: asset.id), Int(old * 100) == Int(fraction * 100) { return }
        states[asset.id] = .downloading(fraction)
    }

    fileprivate func finished(file: String, success: Bool) {
        guard let asset = manifest.assets.first(where: { $0.file == file }) else { return }
        if success, UserDefaults.standard.bool(forKey: Self.userRemovedKey) {
            // Landed after Remove: the user asked for the space back.
            try? FileManager.default.removeItem(at: HDPackFiles.url(forFile: file))
            states[asset.id] = .missing
        } else {
            states[asset.id] = success ? .ready : (Self.isOnDisk(asset) ? .ready : .failed)
        }
        if success {
            var all = UserDefaults.standard.dictionary(forKey: Self.mismatchKey) ?? [:]
            all[file] = nil
            UserDefaults.standard.set(all, forKey: Self.mismatchKey)
        }
        usedBytes = HDPackFiles.usedBytes()
    }

    fileprivate func cancelled(file: String) async {
        guard let asset = manifest.assets.first(where: { $0.file == file }), state(for: asset.id) != .ready else { return }
        // "Download now" cancels the waiting prefetch and starts its own
        // transfer of the same file: that one still owns the state.
        for session in [autoSession, userSession] {
            for task in await session.allTasks where task.taskDescription == file && task.state == .running {
                return
            }
        }
        states[asset.id] = .missing
    }

    fileprivate func sessionFinishedEvents(_ id: String?) {
        guard let id, let completion = backgroundEventsCompletion.removeValue(forKey: id) else { return }
        completion()
    }
}

// MARK: - URLSession delegate

/// Stateless: the task's `taskDescription` is the manifest file name
/// (`<sha256>.<ext>`), which is all the install step needs.
private final class HDPackDownloadDelegate: NSObject, URLSessionDownloadDelegate, Sendable {
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didWriteData bytesWritten: Int64, totalBytesWritten: Int64,
                    totalBytesExpectedToWrite: Int64) {
        guard let file = downloadTask.taskDescription else { return }
        Task { @MainActor in HDPackStore.shared.progress(file: file, received: totalBytesWritten) }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didFinishDownloadingTo location: URL) {
        guard let file = downloadTask.taskDescription else { return }
        let status = (downloadTask.response as? HTTPURLResponse)?.statusCode ?? 200
        var ok = false
        var mismatch = false
        if (200..<300).contains(status) {
            // Must happen before returning: URLSession deletes `location` afterwards.
            do {
                try HDPackFiles.install(downloaded: location, as: file)
                ok = true
            } catch HDPackFiles.InstallError.checksumMismatch {
                mismatch = true
            } catch {}
        }
        Task { @MainActor in
            if mismatch { HDPackStore.shared.checksumMismatch(file: file) }
            HDPackStore.shared.finished(file: file, success: ok)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error, let file = task.taskDescription else { return }
        let nsError = error as NSError
        let cancelled = nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorCancelled
        // An interrupted transfer resumes where it stopped on the next try.
        if !cancelled, let data = nsError.userInfo[NSURLSessionDownloadTaskResumeData] as? Data {
            HDPackFiles.saveResumeData(data, for: file)
        }
        Task { @MainActor in
            if cancelled {
                await HDPackStore.shared.cancelled(file: file)
            } else {
                HDPackStore.shared.finished(file: file, success: false)
            }
        }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        let id = session.configuration.identifier
        Task { @MainActor in HDPackStore.shared.sessionFinishedEvents(id) }
    }
}

#if os(iOS)
/// Receives the completion handler iOS passes when it relaunches the app for
/// finished background transfers of the HD pack.
final class HDPackAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     handleEventsForBackgroundURLSession identifier: String,
                     completionHandler: @escaping () -> Void) {
        guard identifier == HDPackStore.autoSessionID || identifier == HDPackStore.userSessionID else {
            completionHandler()
            return
        }
        HDPackStore.shared.backgroundEventsCompletion[identifier] = completionHandler
        HDPackStore.shared.bootstrap()
    }
}
#endif

#if DEBUG
/// Resident footprint of this process (`phys_footprint`, what Xcode's memory
/// gauge shows) — the HD pack's load-time guardrail logs it.
enum MemoryFootprint {
    static func current() -> UInt64 {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
        let result = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        return result == KERN_SUCCESS ? info.phys_footprint : 0
    }
}
#endif
