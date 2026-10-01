import Foundation

/// What `PushPrePromptPolicy` remembers between launches.
protocol PushPrePromptStore: AnyObject {
    /// Returns to Home after a sample, counted since the prompt was last shown.
    var homeReturns: Int { get set }
    /// How many times the pre-prompt has been shown, ever.
    var timesShown: Int { get set }
    /// The prompt stays hidden before this date ("Not now"); `nil` when not snoozed.
    var snoozedUntil: Date? { get set }
    /// Set once the user answered the system prompt, either way.
    var settled: Bool { get set }
}

/// `UserDefaults`-backed store (the app's own defaults, CA92.1 in the privacy manifest).
final class DefaultsPushPrePromptStore: PushPrePromptStore {
    private let defaults: UserDefaults
    init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    var homeReturns: Int {
        get { defaults.integer(forKey: "push.prePrompt.homeReturns") }
        set { defaults.set(newValue, forKey: "push.prePrompt.homeReturns") }
    }
    var timesShown: Int {
        get { defaults.integer(forKey: "push.prePrompt.timesShown") }
        set { defaults.set(newValue, forKey: "push.prePrompt.timesShown") }
    }
    var snoozedUntil: Date? {
        get { defaults.object(forKey: "push.prePrompt.snoozedUntil") as? Date }
        set { defaults.set(newValue, forKey: "push.prePrompt.snoozedUntil") }
    }
    var settled: Bool {
        get { defaults.bool(forKey: "push.prePrompt.settled") }
        set { defaults.set(newValue, forKey: "push.prePrompt.settled") }
    }
}

/// When to show the "Get notified when new samples land" pre-prompt — the same rules as
/// the Android demo's `PushPromptPolicy`. Pure logic; the caller says whether the prompt
/// could matter at all (`eligible`: push available, Notifications setting on, system
/// permission not yet asked).
///
/// - Never on first launch: it waits for the 2nd return to Home after a sample.
/// - "Not now" hides it for 7 days.
/// - Shown at most 3 times, then never again.
/// - An answer at the system prompt settles it for good; the Notifications switch in
///   About stays the way back.
final class PushPrePromptPolicy {
    static let returnsBeforePrompt = 2
    static let maxShows = 3
    static let snooze: TimeInterval = 7 * 24 * 60 * 60

    private let store: PushPrePromptStore
    private let now: () -> Date

    init(store: PushPrePromptStore, now: @escaping () -> Date = { Date() }) {
        self.store = store
        self.now = now
    }

    /// Home is back on screen after a sample was open.
    func onReturnedHome() {
        store.homeReturns += 1
    }

    func shouldShow(eligible: Bool) -> Bool {
        guard eligible, !store.settled, store.timesShown < Self.maxShows,
              store.homeReturns >= Self.returnsBeforePrompt else { return false }
        if let until = store.snoozedUntil, now() < until { return false }
        return true
    }

    /// The sheet appeared.
    func onShown() {
        store.timesShown += 1
        store.homeReturns = 0
    }

    /// "Not now", or the sheet dismissed without an answer.
    func onLater() {
        store.snoozedUntil = now().addingTimeInterval(Self.snooze)
    }

    /// The system prompt was answered, either way.
    func onAnswered() {
        store.settled = true
    }
}
