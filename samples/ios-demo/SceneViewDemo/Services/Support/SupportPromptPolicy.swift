import Foundation

/// What `SupportPromptPolicy` remembers between launches.
protocol SupportPromptStore: AnyObject {
    /// App sessions counted so far (`SupportPromptPolicy.onBecameActive`).
    var sessions: Int { get set }
    /// When the app last went to the background; `nil` before the first time.
    var lastBackground: Date? { get set }
    /// When the "Support SceneView" prompt was last shown; `nil` when never.
    var lastShown: Date? { get set }
    /// "Don't ask again" — the prompt never comes back. About keeps the way in.
    var neverAsk: Bool { get set }
    /// A tip went through — the prompt has done its job and never comes back.
    var supported: Bool { get set }
}

/// `UserDefaults`-backed store (the app's own defaults, CA92.1 in the privacy manifest).
final class DefaultsSupportPromptStore: SupportPromptStore {
    private let defaults: UserDefaults
    init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    var sessions: Int {
        get { defaults.integer(forKey: "support.prompt.sessions") }
        set { defaults.set(newValue, forKey: "support.prompt.sessions") }
    }
    var lastBackground: Date? {
        get { defaults.object(forKey: "support.prompt.lastBackground") as? Date }
        set { defaults.set(newValue, forKey: "support.prompt.lastBackground") }
    }
    var lastShown: Date? {
        get { defaults.object(forKey: "support.prompt.lastShown") as? Date }
        set { defaults.set(newValue, forKey: "support.prompt.lastShown") }
    }
    var neverAsk: Bool {
        get { defaults.bool(forKey: "support.prompt.neverAsk") }
        set { defaults.set(newValue, forKey: "support.prompt.neverAsk") }
    }
    var supported: Bool {
        get { defaults.bool(forKey: "support.prompt.supported") }
        set { defaults.set(newValue, forKey: "support.prompt.supported") }
    }
}

/// When to show the "Support SceneView" prompt — asked, not pushed.
///
/// Pure logic; the caller decides what a *moment of value* is (Home is back after a
/// sample) and whether the screen is free (no AR session, no other sheet).
///
/// - Never in the first two sessions: it waits for the 3rd.
/// - Only at a moment of value, never at launch.
/// - At most once every 14 days, whatever the answer.
/// - "Don't ask again", or a tip that went through, ends it for good. The About tab
///   keeps the way in.
final class SupportPromptPolicy {
    static let sessionsBeforePrompt = 3
    static let interval: TimeInterval = 14 * 24 * 60 * 60
    /// Back from the background after this long counts as a new session.
    static let sessionGap: TimeInterval = 30 * 60

    private let store: SupportPromptStore
    private let now: () -> Date
    /// The process counted its own launch already.
    private var countedLaunch = false

    init(store: SupportPromptStore, now: @escaping () -> Date = { Date() }) {
        self.store = store
        self.now = now
    }

    /// The process started: one more session. A background time left over from the
    /// previous process is dropped — its return never happened.
    func onLaunch() {
        guard !countedLaunch else { return }
        countedLaunch = true
        store.sessions += 1
        store.lastBackground = nil
    }

    /// The app became active. Only a return from the background counts, once, and only
    /// after `sessionGap`: an inactive → active (a system alert, Control Center, a
    /// Cmd-Tab) leaves no background time behind, so it never counts.
    func onBecameActive() {
        onLaunch()
        guard let left = store.lastBackground else { return }
        store.lastBackground = nil
        if now().timeIntervalSince(left) >= Self.sessionGap {
            store.sessions += 1
        }
    }

    func onEnteredBackground() {
        store.lastBackground = now()
    }

    /// At a moment of value: may the prompt show now?
    var shouldShow: Bool {
        guard !store.neverAsk, !store.supported,
              store.sessions >= Self.sessionsBeforePrompt else { return false }
        if let last = store.lastShown, now().timeIntervalSince(last) < Self.interval { return false }
        return true
    }

    /// The prompt went up.
    func onShown() { store.lastShown = now() }

    /// "Don't ask again".
    func onNeverAsk() { store.neverAsk = true }

    /// A tip went through, from the prompt or from About.
    func onSupported() { store.supported = true }
}
