import Foundation
import StoreKit

/// "Support SceneView": three one-time tips sold as consumable in-app purchases, and
/// the occasional prompt that offers them.
///
/// App Review rejected 4.48.0 for a donation *link* (guideline 3.1.1), so on Apple
/// platforms a tip goes through StoreKit. A tip unlocks nothing — every feature of the
/// app stays free — and the prompt follows `SupportPromptPolicy`.
@MainActor
final class SupportStore: ObservableObject {
    static let shared = SupportStore()

    /// The three tiers, smallest first. The ids are the App Store Connect products
    /// (and the ones in `SceneViewDemo.storekit` for local testing).
    static let productIds = [
        "io.github.sceneview.demo.support.small",
        "io.github.sceneview.demo.support.medium",
        "io.github.sceneview.demo.support.large",
    ]

    enum LoadState: Equatable {
        case loading
        case loaded
        /// The store did not answer, or returned none of the tiers.
        case unavailable
    }

    enum PurchaseState: Equatable {
        case idle
        /// The Apple purchase sheet is up for this product id.
        case purchasing(String)
        /// A tip went through: the sheet says thank you.
        case thanked
        /// Ask to Buy: a parent has to approve it.
        case pending
        case failed
    }

    /// Why the sheet is up.
    enum Presentation: String, Identifiable {
        /// Offered by the policy, at a moment of value: "Not now" and "Don't ask again".
        case prompt
        /// Opened from About.
        case about
        var id: String { rawValue }
    }

    @Published private(set) var products: [Product] = []
    @Published private(set) var loadState: LoadState = .loading
    @Published private(set) var purchaseState: PurchaseState = .idle
    /// The policy asks for the prompt; `ContentView` presents it when the screen is free.
    @Published var promptRequested = false

    private let policy: SupportPromptPolicy
    private var updatesTask: Task<Void, Never>?

    init(policy: SupportPromptPolicy = SupportPromptPolicy(store: DefaultsSupportPromptStore())) {
        self.policy = policy
        #if DEBUG
        if Self.launchArgument == "thanks" { purchaseState = .thanked }
        #endif
    }

    // MARK: Launch arguments

    /// `-support_prompt off` (UI tests, scripted captures): the prompt never shows.
    /// `-support_prompt show` (Debug only): the prompt comes up once Home is on screen,
    /// whatever the policy says — for QA captures. `-support_prompt thanks` (Debug only)
    /// brings it up in its "Thank you!" state, which a simulator cannot reach without a
    /// signed-in sandbox account.
    static let launchArgument: String? = {
        let args = CommandLine.arguments
        guard let index = args.firstIndex(of: "-support_prompt"), index + 1 < args.count else {
            return nil
        }
        return args[index + 1].lowercased()
    }()

    static var promptDisabledByLaunchArgument: Bool {
        ["off", "false", "0"].contains(launchArgument ?? "")
    }

    static var promptForcedByLaunchArgument: Bool {
        #if DEBUG
        return launchArgument == "show" || launchArgument == "thanks"
        #else
        return false
        #endif
    }

    // MARK: Lifecycle

    /// From `SceneViewDemoApp.init()`: listens for transactions finished elsewhere
    /// (Ask to Buy approved later, a purchase interrupted by a crash) so every one is
    /// finished, and counts the launch as a session. Idempotent.
    func start() {
        guard updatesTask == nil else { return }
        updatesTask = Task { [weak self] in
            for await update in Transaction.updates {
                await self?.handle(update)
            }
        }
        Task { await finishUnfinished() }
        policy.onLaunch()
    }

    /// Every `.active`: a return from the background may be a new session.
    func appBecameActive() {
        start()
        policy.onBecameActive()
    }

    func appEnteredBackground() {
        policy.onEnteredBackground()
    }

    // MARK: Products

    func loadProducts() async {
        guard products.isEmpty else { return }
        loadState = .loading
        do {
            let found = try await Product.products(for: Self.productIds)
            products = found.sorted { $0.price < $1.price }
            loadState = products.isEmpty ? .unavailable : .loaded
        } catch {
            loadState = .unavailable
        }
    }

    // MARK: Purchase

    func purchase(_ product: Product) async {
        guard purchaseState != .purchasing(product.id) else { return }
        purchaseState = .purchasing(product.id)
        do {
            let result = try await product.purchase()
            switch result {
            case .success(let verification):
                switch verification {
                case .verified(let transaction):
                    await transaction.finish()
                    tipped()
                case .unverified(let transaction, _):
                    // Finished so it is not delivered again, but never thanked.
                    await transaction.finish()
                    purchaseState = .failed
                }
            case .pending:
                purchaseState = .pending
            case .userCancelled:
                purchaseState = .idle
            @unknown default:
                purchaseState = .idle
            }
        } catch {
            purchaseState = .failed
        }
    }

    /// The sheet went away: the next opening starts fresh.
    func sheetDismissed() {
        if purchaseState != .pending { purchaseState = .idle }
    }

    private func tipped() {
        policy.onSupported()
        purchaseState = .thanked
    }

    private func handle(_ update: VerificationResult<Transaction>) async {
        let transaction: Transaction
        switch update {
        case .verified(let verified):
            transaction = verified
        case .unverified(let unverified, _):
            // Finished so StoreKit stops delivering it; it thanks nobody.
            if Self.productIds.contains(unverified.productID) { await unverified.finish() }
            if purchaseState == .pending { purchaseState = .failed }
            return
        }
        guard Self.productIds.contains(transaction.productID) else { return }
        await transaction.finish()
        // An Ask to Buy approved while the sheet still waits on it.
        if purchaseState == .pending { tipped() } else { policy.onSupported() }
    }

    private func finishUnfinished() async {
        for await unfinished in Transaction.unfinished {
            await handle(unfinished)
        }
    }

    // MARK: Prompt

    /// Home is back after a sample — the moment of value. Asks for the prompt when the
    /// policy allows; `ContentView` shows it only if nothing else is on screen.
    func homeReturnedAfterSample() {
        guard !Self.promptDisabledByLaunchArgument else { return }
        guard policy.shouldShow else { return }
        Task {
            // After the cover's dismissal, and after the push pre-prompt (450 ms) had
            // its chance: that one goes first, this one waits for another return.
            try? await Task.sleep(for: .milliseconds(700))
            promptRequested = true
        }
    }

    /// The prompt went up.
    func promptShown() {
        promptRequested = false
        policy.onShown()
    }

    /// The prompt could not show (another sheet, a demo): drop the request, so it is
    /// asked again at the next moment of value.
    func promptSkipped() {
        promptRequested = false
    }

    /// "Don't ask again".
    func neverAskAgain() {
        policy.onNeverAsk()
    }
}
