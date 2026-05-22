import Foundation

/// Parses an incoming `URL` from `.onOpenURL { url in … }` and returns
/// the demo id to open, or `nil` if the URL is not a valid SceneView
/// deep link.
///
/// Supported URL shapes — mirror exactly the Android `DeepLinkRouter.kt`:
///
/// 1. **Custom scheme** — `sceneview://demo/<id>` (CFBundleURLTypes in
///    `Info.plist`, no Universal Links verification needed). The id is
///    the last path component.
///
/// 2. **Verified Universal Links** (future) —
///    `https://sceneview.github.io/open?demo=<id>`. Pulled from the
///    `demo` query parameter. Will become live once
///    `/.well-known/apple-app-site-association` ships on github.io with
///    the published TEAM_ID + bundle id.
///
/// The id must match an entry in `allowedDemos` — we don't blindly
/// route to user-provided strings (closed registry, prevents trivial
/// fuzzing of the navigation graph from a hostile QR code). Unknown ids
/// return `nil` and the caller falls back to the normal app launch path.
///
/// ### QA mode
///
/// Append `?qa_mode=1` to any deep-link URL to freeze auto-rotation for
/// deterministic QA screenshots — mirrors Android's `qa_mode` extra.
/// Example: `sceneview://demo/animation?qa_mode=1`
/// The parsed result is stored in `UserDefaults` under the key `"qa_mode"`
/// so any demo can read it via `@AppStorage("qa_mode")`.
enum DeepLinkRouter {

    /// Custom URL scheme registered in `Info.plist > CFBundleURLTypes`.
    static let schemeCustom: String = "sceneview"

    /// Custom URL host. Only `demo` is supported today.
    static let hostCustom: String = "demo"

    /// Hostname for verified Universal Links (future).
    static let hostHttps: String = "sceneview.github.io"

    /// Path prefix on the Universal Links host.
    static let pathHttps: String = "/open"

    /// Query parameter name carrying the demo id on the Universal Links host.
    static let queryParam: String = "demo"

    /// Query parameter that activates QA mode (freeze auto-rotation, etc.).
    static let qaModeParam: String = "qa_mode"

    /// `UserDefaults` key written when `?qa_mode=1` is present in the URL.
    /// Read via `@AppStorage("qa_mode") var qaMode: Bool` in any demo view.
    static let qaModeDefaultsKey: String = "qa_mode"

    /// Parses the URL and returns the validated demo id, or `nil`.
    /// As a side-effect, writes `UserDefaults["qa_mode"]` when the
    /// `?qa_mode=1` parameter is present.
    static func parse(_ url: URL?, allowedDemos: Set<String>) -> String? {
        guard let url = url, let candidate = extractCandidate(url) else { return nil }
        applyQAModeIfPresent(url)
        return allowedDemos.contains(candidate) ? candidate : nil
    }

    /// Extracts the raw id token from a URL without validating it
    /// against a registry. Exposed so tests can verify the URL parser
    /// separately from the registry lookup.
    static func extractCandidate(_ url: URL) -> String? {
        guard let scheme = url.scheme?.lowercased() else { return nil }
        switch scheme {
        case schemeCustom:
            guard url.host?.lowercased() == hostCustom else { return nil }
            // `lastPathComponent` is "/" when no segment is present — guard against it.
            let last = url.lastPathComponent
            return (last.isEmpty || last == "/") ? nil : last
        case "https", "http":
            guard url.host?.lowercased() == hostHttps else { return nil }
            guard url.path.hasPrefix(pathHttps) else { return nil }
            let comps = URLComponents(url: url, resolvingAgainstBaseURL: false)
            let demo = comps?.queryItems?.first { $0.name == queryParam }?.value
            return (demo?.isEmpty ?? true) ? nil : demo
        default:
            return nil
        }
    }

    /// Reads `?qa_mode=` from the URL's query items and writes the result
    /// to `UserDefaults` so all demo views can pick it up via
    /// `@AppStorage("qa_mode")`. Mirrors the Android `qa_mode` intent extra.
    private static func applyQAModeIfPresent(_ url: URL) {
        guard let comps = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let items = comps.queryItems else { return }
        let enabled = items.first { $0.name == qaModeParam }?.value == "1"
        UserDefaults.standard.set(enabled, forKey: qaModeDefaultsKey)
    }
}
