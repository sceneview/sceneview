import Foundation
import os.log

/// Configuration for the Sketchfab Data API v3.
///
/// The API key is resolved in two stages:
///
/// 1. `Info.plist` — the canonical lookup. The `SketchfabAPIKey` entry is
///    declared with the `$(SKETCHFAB_API_KEY)` build-setting placeholder so
///    that `xcodebuild archive` substitutes the value into the shipped
///    `Info.plist` at build time (CI passes the secret as a `SKETCHFAB_API_KEY`
///    user-defined build setting — see `.github/workflows/app-store.yml` and
///    `.github/workflows/ios.yml`). This is the only path that works for
///    TestFlight + App Store builds because environment variables set in the
///    CI job don't survive `xcodebuild archive` into the `.ipa`.
/// 2. `ProcessInfo` — fallback for Xcode local development. The legacy
///    `SKETCHFAB_API_KEY` scheme env var still works when running under
///    Xcode's "Run" scheme so contributors don't have to edit `Info.plist`.
///
/// TODO V1.1: move to backend proxy via mcp-gateway to avoid bundling key
/// directly in the iOS app binary. End-users should authenticate against the
/// proxy (which holds the master key server-side) so we don't ship a long-lived
/// token that can be extracted from `.ipa` files.
enum SketchfabConfig {
    /// Base URL of the Sketchfab Data API v3.
    static let baseURL = URL(string: "https://api.sketchfab.com/v3/")!

    /// Guards the `missingApiKey` warn log to once-per-process (#1909).
    /// `nonisolated(unsafe)` because there's no critical section across
    /// threads — at worst the WARN is emitted twice in a debug build race,
    /// which is fine. Swift 6 strict concurrency would otherwise flag the
    /// `static var` as a sendability violation.
    nonisolated(unsafe) private static var missingKeyLogged = false

    /// API key, resolved from `Info.plist` first then `ProcessInfo` fallback.
    ///
    /// Returns `nil` when neither source provides a non-empty value; callers
    /// should surface `SketchfabError.missingApiKey` in that case rather than
    /// firing unauthenticated requests.
    ///
    /// Side-effect: the first read on a debug build with a missing key emits a
    /// WARN log via `os.Logger` pointing at the scheme env-var workaround
    /// (#1909). Release builds suppress the log — the user-visible
    /// `sketchfabDisabledBanner` in `ExploreTab.swift` is the visible signal
    /// there.
    static var apiKey: String? {
        // Primary lookup: `Info.plist` (works for `xcodebuild archive` →
        // TestFlight + App Store builds).
        if let fromPlist = Bundle.main.object(forInfoDictionaryKey: "SketchfabAPIKey") as? String,
           !fromPlist.isEmpty,
           // Guard against an unsubstituted `$(SKETCHFAB_API_KEY)` xcconfig
           // token leaking through as a string literal — happens when the
           // build setting wasn't passed to `xcodebuild` (e.g. forks without
           // the secret, manual Archive without -PSKETCHFAB_API_KEY=...).
           fromPlist != "$(SKETCHFAB_API_KEY)" {
            return fromPlist
        }
        // Fallback: legacy `ProcessInfo` env var. Works under Xcode "Run"
        // schemes (Edit Scheme → Run → Arguments → Environment Variables)
        // but NOT for archived builds.
        let fromEnv = ProcessInfo.processInfo.environment["SKETCHFAB_API_KEY"]
        if let fromEnv, !fromEnv.isEmpty {
            return fromEnv
        }
        #if DEBUG
        if !missingKeyLogged {
            missingKeyLogged = true
            let logger = Logger(subsystem: "io.github.sceneview.demo", category: "SketchfabConfig")
            logger.warning("""
                SKETCHFAB_API_KEY is not set — Sketchfab features (carousels, search, streamed demos) are disabled. \
                For Xcode local runs, edit your scheme → Run → Arguments → Environment Variables and add \
                SKETCHFAB_API_KEY=<your-token>. For xcodebuild from CLI, pass SKETCHFAB_API_KEY=<your-token> as \
                a user-defined build setting. Grab a token at Sketchfab → Settings → Password & API → API Token. \
                See issue #1909.
                """)
        }
        #endif
        return nil
    }

    /// Maximum cache size on disk, in bytes (500 MB).
    static let maxCacheBytes: Int64 = 500 * 1024 * 1024

    /// Per-model streaming ceiling, in bytes (512 MB). A single model above this
    /// is either hostile or a mistake — refuse it before streaming rather than
    /// fill the disk. Mirrors the Android `NetworkModelDownloader.MAX_MODEL_BYTES`
    /// guard (#2645 / #2700).
    static let maxModelBytes: Int = 512 * 1024 * 1024

    /// Subdirectory under `Caches/` where downloaded GLB files live.
    static let cacheDirectoryName = "sketchfab"
}
