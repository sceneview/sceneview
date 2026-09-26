import Foundation
import SwiftUI

#if canImport(UIKit)
import UIKit
#endif

#if os(macOS)
import AppKit
#endif

/// Checks the App Store for a newer version of the SceneView demo on every
/// `ScenePhase.active` transition and surfaces a toast inviting the user to
/// open the App Store update sheet.
///
/// **Why this lives in the sample, not the SDK.** Apple does not expose a
/// programmatic install API à la Google Play `AppUpdateManager.startUpdateFlow`
/// — the best an iOS app can do is detect a newer version and `open` an
/// `itms-apps://` URL that lands on its own product page with the "UPDATE"
/// button visible. That's exactly what Instagram, Reddit, X, … do. Bundling
/// this into the SceneView SDK proper would force every consumer to ship an
/// App-Store-lookup dependency for a non-3D concern, so the pattern stays in
/// `samples/ios-demo/` as a reference for developers building atop SceneView.
///
/// **Endpoint.** `https://itunes.apple.com/lookup?id=<appStoreId>` — public,
/// unauthenticated, no rate limit for normal app use. CDN-cached.
///
/// **Throttle.** Two `UserDefaults` keys keep the network footprint reasonable:
/// - `sceneview.update.lastCheckAt` — skip lookup if <12h since the last one.
/// - `sceneview.update.snoozedVersion` — if the user tapped "Later", the
///   dismissed version string is stored here so the toast stays hidden for
///   *that* version only. A newer version on the App Store invalidates the
///   snooze automatically (version-keyed, matching Web/Flutter/RN).
@MainActor
final class AppStoreUpdater: ObservableObject {

    /// App Store track id for the SceneView demo (`apps.apple.com/.../id6761329763`).
    static let appStoreId = "6761329763"

    /// Bundle identifier that must match the production app signed for
    /// distribution. Used as a sanity check against the lookup response so the
    /// updater doesn't mistake a similarly-named app for ours.
    static let bundleId = "io.github.sceneview.demo"

    enum UpdateState: Equatable {
        case idle
        case checking
        case upToDate
        case updateAvailable(version: String, notes: String?)
    }

    @Published private(set) var state: UpdateState = .idle

    private let session: URLSession
    private let defaults: UserDefaults
    private let now: () -> Date
    private let currentVersionProvider: @MainActor () -> String?
    private let forcedVersion: String?

    private static let lastCheckKey = "sceneview.update.lastCheckAt"
    private static let snoozedVersionKey = "sceneview.update.snoozedVersion"
    /// Legacy time-based snooze key (≤4.3.5). Removed on read so a stale
    /// 7-day TTL from an older build can't keep a toast hidden.
    private static let legacySnoozedUntilKey = "sceneview.update.snoozedUntil"
    private static let throttle: TimeInterval = 12 * 60 * 60   // 12 hours

    /// - Parameter currentVersion: Closure returning the running app's
    ///   `CFBundleShortVersionString`. Defaults to `Bundle.main` — XCTest
    ///   should pass a stub closure since the test runner's `Bundle.main` is
    ///   the test harness bundle, not `SceneViewDemo`.
    /// - Parameter forcedVersion: A version to report as available without
    ///   asking the App Store — `nil` in production. Debug builds pass
    ///   ``launchArgForcedVersion`` so the update toast can be driven on a
    ///   simulator, whose build is never older than the App Store's.
    init(
        session: URLSession = .shared,
        defaults: UserDefaults = .standard,
        now: @escaping () -> Date = Date.init,
        currentVersion: @MainActor @escaping () -> String? = AppStoreUpdater.bundleVersion,
        forcedVersion: String? = nil
    ) {
        self.session = session
        self.defaults = defaults
        self.now = now
        self.currentVersionProvider = currentVersion
        self.forcedVersion = forcedVersion
    }

    /// `-update_qa available` on a DEBUG build: the version the QA launch
    /// pretends the App Store has. Always `nil` in a release build.
    nonisolated static var launchArgForcedVersion: String? {
        #if DEBUG
        let args = CommandLine.arguments
        guard let index = args.firstIndex(of: "-update_qa"), index + 1 < args.count,
              args[index + 1] == "available" else { return nil }
        return "99.0.0"
        #else
        return nil
        #endif
    }

    /// `true` while the update toast belongs on screen: a newer version is on
    /// the App Store and the user has not dismissed that version.
    var showsUpdatePrompt: Bool {
        guard case .updateAvailable = state else { return false }
        return !isSnoozed
    }

    /// Run a lookup against the App Store unless the throttle / snooze window
    /// says otherwise. Pass `force = true` to bypass both gates (e.g. a manual
    /// "Check now" button in About).
    func checkForUpdate(force: Bool = false) async {
        if let forcedVersion {
            state = .updateAvailable(version: forcedVersion, notes: nil)
            return
        }
        if !force, !shouldCheck() { return }
        state = .checking
        defaults.set(now().timeIntervalSince1970, forKey: Self.lastCheckKey)

        do {
            let latest = try await fetchLatestVersion()
            guard let current = currentVersionProvider() else {
                state = .idle
                return
            }
            if Self.isNewer(latest: latest.version, current: current) {
                state = .updateAvailable(version: latest.version, notes: latest.notes)
            } else {
                state = .upToDate
            }
        } catch {
            // Silent failure on network / decode errors — the toast just
            // doesn't appear this resume. Don't escalate to the user.
            state = .idle
        }
    }

    /// Open the App Store product page so the user can tap "UPDATE".
    /// On iOS `itms-apps://` opens directly inside the App Store app (no
    /// browser bounce); on macOS `macappstore://` opens the Mac App Store app.
    func openAppStore() {
        #if canImport(UIKit) && os(iOS)
        guard let url = URL(string: "itms-apps://itunes.apple.com/app/id\(Self.appStoreId)") else { return }
        UIApplication.shared.open(url)
        #elseif os(macOS)
        guard let url = URL(string: "macappstore://itunes.apple.com/app/id\(Self.appStoreId)") else { return }
        NSWorkspace.shared.open(url)
        #endif
    }

    /// Hide the toast for the version the user just dismissed. The next
    /// `checkForUpdate` will still hit the network if the throttle window has
    /// elapsed; [`isSnoozed`] keeps the toast hidden only while the App Store's
    /// latest version still equals the dismissed one. A newer version
    /// invalidates the snooze automatically — matching the version-keyed
    /// semantics of the Web/Flutter/RN samples.
    func snooze() {
        if case let .updateAvailable(version, _) = state {
            defaults.set(version, forKey: Self.snoozedVersionKey)
        }
        defaults.removeObject(forKey: Self.legacySnoozedUntilKey)
        state = .upToDate
    }

    /// `true` when the version the App Store currently advertises is the same
    /// one the user already dismissed. A new release surfaces the toast again.
    /// Views should branch on this *and* the published `state` to decide
    /// whether to surface the toast.
    var isSnoozed: Bool {
        guard case let .updateAvailable(version, _) = state else { return false }
        return defaults.string(forKey: Self.snoozedVersionKey) == version
    }

    // MARK: - Private

    private func shouldCheck() -> Bool {
        // Note: the snooze is intentionally NOT consulted here. A version-keyed
        // snooze must still let the lookup run so a *newer* App Store release
        // can be detected and re-surface the toast. The 12h throttle below is
        // the only network gate; [`isSnoozed`] only governs toast visibility.
        let nowEpoch = now().timeIntervalSince1970
        // Clamp `last` against the current time: if the system clock rolled
        // backward (or `lastCheckAt` was somehow stamped in the future),
        // `now - last` would be negative and stay below the throttle forever,
        // hard-locking out all future checks. A future `lastCheckAt` is itself
        // the bug — clamping to `nowEpoch` repairs it on read.
        let last = min(defaults.double(forKey: Self.lastCheckKey), nowEpoch)
        return nowEpoch - last >= Self.throttle
    }

    private struct LookupResult {
        let version: String
        let notes: String?
    }

    private func fetchLatestVersion() async throws -> LookupResult {
        var components = URLComponents(string: "https://itunes.apple.com/lookup")!
        components.queryItems = [
            URLQueryItem(name: "id", value: Self.appStoreId),
            URLQueryItem(name: "country", value: Locale.current.region?.identifier.lowercased() ?? "us")
        ]
        let (data, response) = try await session.data(from: components.url!)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw URLError(.badServerResponse)
        }
        let decoded = try JSONDecoder().decode(LookupResponse.self, from: data)
        guard let first = decoded.results.first, first.bundleId == Self.bundleId else {
            throw URLError(.zeroByteResource)
        }
        return LookupResult(version: first.version, notes: first.releaseNotes)
    }

    private struct LookupResponse: Decodable {
        let results: [Entry]
        struct Entry: Decodable {
            let version: String
            let bundleId: String
            let releaseNotes: String?
        }
    }

    /// Reads `CFBundleShortVersionString` from `Bundle.main` — the
    /// production default for the `currentVersion` injection point.
    /// `nonisolated` so the enclosing `@MainActor` class doesn't bleed
    /// MainActor isolation into the `init`'s default expression, which
    /// Swift 6 rejects with "loses global actor 'MainActor'" when the
    /// param type is the un-isolated `() -> String?`.
    nonisolated static func bundleVersion() -> String? {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String
    }

    /// Component-wise version comparison (`1.2.10` > `1.2.9`). Trailing zeros
    /// don't matter (`1.2` == `1.2.0`). Non-numeric components compare as `0`.
    /// `nonisolated` for the same reason as `bundleVersion()` — a pure
    /// string-parsing function with zero MainActor dependencies; marking it
    /// explicitly keeps the two static helpers consistent and pre-empts the
    /// Swift 6 isolation error if it's ever used as an init default.
    nonisolated static func isNewer(latest: String, current: String) -> Bool {
        let lhs = latest.split(separator: ".").map { Int($0) ?? 0 }
        let rhs = current.split(separator: ".").map { Int($0) ?? 0 }
        let count = max(lhs.count, rhs.count)
        for i in 0..<count {
            let l = i < lhs.count ? lhs[i] : 0
            let r = i < rhs.count ? rhs[i] : 0
            if l > r { return true }
            if l < r { return false }
        }
        return false
    }
}
