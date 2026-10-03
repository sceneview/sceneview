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
///
/// ### Camera distance
///
/// The `-camera_distance <float>` launch argument (parsed in
/// `SceneViewDemoApp`) lets the App Store screenshot pipeline frame the hero
/// demo tighter than the interactive auto-fit default — mirrors Android's
/// `camera_distance` intent extra (`DeepLinkRouter.kt`,
/// `coerceCameraDistanceExtra`, #2652). [validateCameraDistance] applies the
/// identical clamp so both platforms accept the same value range (#2785).
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

    /// Query parameter naming a view inside the opened demo, e.g.
    /// `sceneview://demo/cosmos?tab=spacetime` opens Cosmos on the Star scene's
    /// Spacetime mode. Mirrors Android's `tab` deep-link parameter.
    static let tabParam: String = "tab"

    /// UserDefaults key the `tab` parameter is written to, as `<demo id>:<tab>`.
    /// The demo takes it once when it appears (`consumeTab(for:)`), so a later
    /// plain visit opens on its default view.
    static let tabDefaultsKey: String = "demo_tab"

    /// Records the view `demo` should open on, or clears any left over.
    static func setTab(_ tab: String?, for demo: String) {
        if let tab, !tab.isEmpty {
            UserDefaults.standard.set("\(demo):\(tab)", forKey: tabDefaultsKey)
        } else {
            UserDefaults.standard.removeObject(forKey: tabDefaultsKey)
        }
    }

    /// The view `demo` was asked to open on, lower-cased, if any — read without taking it.
    /// For a view's `init`: the catalogue builds every card's view eagerly (`DemoItem`,
    /// `GeneratedScenes.all()`, called again by `DemoDeepLinkRegistry.cover(for:)` before
    /// the linked screen), so an `init` that consumed the tab lost it to a copy that never
    /// reaches the screen. The view that does appear consumes it in `onAppear`.
    static func peekTab(for demo: String) -> String? {
        let prefix = demo + ":"
        guard let stored = UserDefaults.standard.string(forKey: tabDefaultsKey),
              stored.hasPrefix(prefix) else { return nil }
        return String(stored.dropFirst(prefix.count)).lowercased()
    }

    /// The view `demo` was asked to open on, lower-cased, if any — read once. A tab meant for
    /// another demo is left for it: a demo on screen watches the key (`@AppStorage`) and must
    /// not swallow a link that opens a different one.
    static func consumeTab(for demo: String) -> String? {
        let prefix = demo + ":"
        guard let stored = UserDefaults.standard.string(forKey: tabDefaultsKey),
              stored.hasPrefix(prefix) else { return nil }
        UserDefaults.standard.removeObject(forKey: tabDefaultsKey)
        return String(stored.dropFirst(prefix.count)).lowercased()
    }

    /// Peeks at the pending tab so analytics can record the initial mode before the demo consumes it.
    static func pendingTab(for demo: String) -> String? {
        let prefix = demo + ":"
        guard let stored = UserDefaults.standard.string(forKey: tabDefaultsKey),
              stored.hasPrefix(prefix) else { return nil }
        return String(stored.dropFirst(prefix.count)).lowercased()
    }

    /// `true` when the process was launched by a script rather than by a human
    /// — the App Store screenshot pipeline, or the XCUITest suite. Both route
    /// straight to a demo with `-demo <id>`, which no interactive launch ever
    /// carries, so the argument is the signal.
    ///
    /// **Why the app needs to know.** Scripted passes also set `-qa_mode 1` to
    /// freeze auto-rotation, and QA mode paints an on-screen "QA ×" affordance
    /// (`DemoSheet`) so a human who toggled it can toggle it back. On a capture
    /// pass there is no human, and the frame becomes a published App Store
    /// asset. The chip arrived with the redesign (#3308), after the last store
    /// capture, so it never shipped — but it would have been baked into the
    /// #3384 refresh and every one after it. Suppress *chrome that exists only
    /// to serve a human*, never the determinism (frozen pose, framing) the
    /// pipeline actually relies on.
    static let isScriptedCapture: Bool = CommandLine.arguments.contains("-demo")

    /// Smallest accepted camera-distance / framing value. Matches Android's
    /// `DeepLinkRouter.CAMERA_DISTANCE_MIN` exactly, so a value rejected on
    /// one platform is rejected on the other — see [validateCameraDistance].
    static let cameraDistanceMin: Float = 0.05

    /// Largest accepted camera-distance / framing value. Matches Android's
    /// `DeepLinkRouter.CAMERA_DISTANCE_MAX` exactly.
    static let cameraDistanceMax: Float = 100

    /// `UserDefaults` key written when a valid `-camera_distance <float>`
    /// launch argument is parsed (`SceneViewDemoApp`). Read via
    /// `@AppStorage("camera_distance") var cameraDistanceRaw: Double = 0` in
    /// any demo view — `0` (the default) means "no override": it is outside
    /// `[cameraDistanceMin, cameraDistanceMax]`, so it is never a value
    /// [validateCameraDistance] would accept. `AppStorage` has no native
    /// `Optional<Double>` support, hence the sentinel (mirrors Android's
    /// nullable `DemoSettings.cameraDistance`, which needs no such trick).
    static let cameraDistanceDefaultsKey: String = "camera_distance"

    /// Validates an already-parsed camera-distance value against the
    /// accepted range. Mirrors Android's `DeepLinkRouter.validateCameraDistance`
    /// exactly: returns [value] iff it is non-nil, finite, and within
    /// `[cameraDistanceMin, cameraDistanceMax]`; otherwise `nil`. Never throws.
    static func validateCameraDistance(_ value: Float?) -> Float? {
        guard let value, value.isFinite else { return nil }
        return (value >= cameraDistanceMin && value <= cameraDistanceMax) ? value : nil
    }

    /// Parses the URL and returns the validated demo id, or `nil`.
    /// As a side-effect, writes `UserDefaults["qa_mode"]` when the
    /// `?qa_mode=1` parameter is present.
    static func parse(_ url: URL?, allowedDemos: Set<String>) -> String? {
        guard let url = url, let candidate = extractCandidate(url) else { return nil }
        applyQAModeIfPresent(url)
        let demo = allowedDemos.contains(candidate) ? candidate : nil
        if let demo { applyTab(url, for: demo) }
        return demo
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

    /// Reads `?tab=` from the URL's query items for the opened demo to pick up
    /// once (`consumeTab(for:)`); a link without one clears any left over.
    private static func applyTab(_ url: URL, for demo: String) {
        let comps = URLComponents(url: url, resolvingAgainstBaseURL: false)
        setTab(comps?.queryItems?.first(where: { $0.name == tabParam })?.value, for: demo)
    }
}
