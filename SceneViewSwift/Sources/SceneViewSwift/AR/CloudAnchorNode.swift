import Foundation

/// Cancellable host / resolve handles for ARCore Cloud Anchors on Apple
/// platforms — the iOS port of SceneView Android's
/// `CloudAnchorNode.host` / `.resolve` **Future + cancel-on-dispose** pattern.
///
/// ### Why a Future that you cancel
///
/// Hosting (and resolving) a Cloud Anchor is a **billed network round-trip** to
/// Google's ARCore Cloud service. On Android, `CloudAnchorNode.host(session,
/// ttlDays = 1)` returns a `HostCloudAnchorFuture` and the recommended hygiene
/// is to cancel it when the owning composable leaves the tree
/// (`DisposableEffect(node) { onDispose { future.cancel() } }`, #1768) so a
/// screen the user navigated away from does not keep running the request — and
/// accruing Google Cloud charges — in the background.
///
/// `CloudAnchorFuture` is the Swift-idiomatic equivalent: a `@MainActor`
/// cancellable handle whose completion fires **at most once** and **never after
/// `cancel()`** (or after the handle is deallocated). Wire it to SwiftUI with
/// the direct analogue of Android's `onDispose`:
///
/// ```swift
/// // host returns synchronously; cancel it when the view disappears
/// @State private var hostFuture: CloudAnchorFuture?
///
/// someView
///     .onDisappear { hostFuture?.cancel() }
/// ```
///
/// ### Dependency-free by design
///
/// SceneViewSwift deliberately does **not** vendor Google's `arcore-ios-sdk`
/// Swift Package (`GARSession`) — pulling it into the core library would force
/// the Cloud Anchor SDK, and its binary weight, onto every consumer including
/// pure-3D apps. Instead, the actual `GARSession.hostCloudAnchor` /
/// `resolveCloudAnchor` call is supplied by the app through the `operation`
/// closure, and `CloudAnchorFuture` owns only the portable, fully testable
/// part: the cancellation lifecycle. The app adds `arcore-ios-sdk` itself and
/// plugs it in:
///
/// ```swift
/// let future = try CloudAnchorNode.host(ttlDays: 1) { result in
///     // Android's `onHosted(cloudAnchorId, state)` analogue
///     if let id = result.cloudAnchorId, !result.state.isError {
///         registry.add(name: "Living room marker", cloudAnchorId: id)
///     }
/// } operation: { report in
///     // `garSession` comes from the app's `arcore-ios-sdk` dependency.
///     let garFuture = try? garSession.hostCloudAnchor(anchor, ttlDays: 1) { cloudId, garState in
///         report(CloudAnchorResult(cloudAnchorId: cloudId,
///                                  state: CloudAnchorState(garCloudAnchorState: garState)))
///     }
///     // Returned hook is invoked by `CloudAnchorFuture.cancel()`.
///     return { garFuture?.cancel() }
/// }
/// ```
///
/// ### Threading
///
/// `CloudAnchorFuture` and the `CloudAnchorNode` entry points are `@MainActor`:
/// ARKit / `GARSession` deliver their callbacks on the main thread and SwiftUI
/// `onDisappear` runs there, so confining the handle to the main actor keeps the
/// gating race-free without locks.
///
/// ### Device verification
///
/// The cancellation lifecycle here is unit-tested headlessly. The *actual*
/// Cloud Anchor round-trip cannot be exercised off-device — it needs a real
/// ARKit world-tracking session, a configured ARCore Cloud API key, and the
/// network. That live path lives in the app-supplied `operation`.
///
/// ### Lineage
///
/// - `arsceneview/src/main/java/io/github/sceneview/ar/node/CloudAnchorNode.kt`
///   — Android `host` / `resolve` returning `HostCloudAnchorFuture` /
///   `ResolveCloudAnchorFuture`.
/// - GitHub issue [#1859](https://github.com/sceneview/sceneview/issues/1859)
///   — iOS parity port (tracked from the cross-platform umbrella #1813).
/// - [#1768](https://github.com/sceneview/sceneview/issues/1768) — the original
///   Android Future + cancel-on-dispose hygiene this mirrors.
@MainActor
public enum CloudAnchorNode {

    /// Valid `ttlDays` range for `host`, inclusive — mirrors Android's
    /// `CloudAnchorNode.TTL_DAYS_RANGE` (= `1..365`, the ARCore persistence
    /// limit). Values outside this range are rejected.
    public static let ttlDaysRange: ClosedRange<Int> = 1...365

    /// Errors thrown when starting a Cloud Anchor operation.
    public enum CloudAnchorError: Swift.Error, CustomStringConvertible, Sendable, Equatable {
        /// `ttlDays` fell outside ``ttlDaysRange``. Mirrors the
        /// `IllegalArgumentException` Android throws for the same input.
        case ttlOutOfRange(Int)

        public var description: String {
            switch self {
            case .ttlOutOfRange(let value):
                return "CloudAnchorNode.host: ttlDays \(value) is outside the "
                    + "allowed range 1...365 (ARCore persistence limit)"
            }
        }
    }

    /// Starts hosting a Cloud Anchor and returns a cancellable handle.
    ///
    /// Mirrors Android's `cloudAnchorNode.host(session, ttlDays) { cloudAnchorId,
    /// state -> }` returning a `HostCloudAnchorFuture`.
    ///
    /// - Parameters:
    ///   - ttlDays: How many days Google's ARCore Cloud should persist the
    ///     anchor. Must be in ``ttlDaysRange`` (`1...365`). Default `1`.
    ///   - completion: Delivered once with the hosting result, on the main
    ///     actor. Not called if the returned future is cancelled first.
    ///   - operation: Starts the underlying `GARSession.hostCloudAnchor` call.
    ///     It receives a `report` closure to deliver the result and returns an
    ///     optional cancel hook that ``CloudAnchorFuture/cancel()`` invokes
    ///     (e.g. `{ garFuture.cancel() }`), or `nil` if there is nothing to
    ///     cancel.
    /// - Returns: A ``CloudAnchorFuture`` — keep it and call
    ///   ``CloudAnchorFuture/cancel()`` from `onDisappear`.
    /// - Throws: ``CloudAnchorError/ttlOutOfRange(_:)`` when `ttlDays` is out of
    ///   range.
    @discardableResult
    public static func host(
        ttlDays: Int = 1,
        completion: @escaping (CloudAnchorResult) -> Void,
        operation: (_ report: @escaping (CloudAnchorResult) -> Void) -> (() -> Void)?
    ) throws -> CloudAnchorFuture {
        guard ttlDaysRange.contains(ttlDays) else {
            throw CloudAnchorError.ttlOutOfRange(ttlDays)
        }
        return CloudAnchorFuture(completion: completion, start: operation)
    }

    /// Starts resolving a previously hosted Cloud Anchor and returns a
    /// cancellable handle.
    ///
    /// Mirrors Android's `CloudAnchorNode.resolve(engine, session,
    /// cloudAnchorId) { state, node -> }` returning a `ResolveCloudAnchorFuture`.
    ///
    /// - Parameters:
    ///   - cloudAnchorId: The id returned by a prior successful `host`.
    ///   - completion: Delivered once with the resolve result, on the main
    ///     actor. Not called if the returned future is cancelled first.
    ///   - operation: Starts the underlying `GARSession.resolveCloudAnchor`
    ///     call. Same contract as ``host(ttlDays:completion:operation:)``.
    /// - Returns: A ``CloudAnchorFuture`` — cancel it on `onDisappear`.
    @discardableResult
    public static func resolve(
        cloudAnchorId: String,
        completion: @escaping (CloudAnchorResult) -> Void,
        operation: (_ report: @escaping (CloudAnchorResult) -> Void) -> (() -> Void)?
    ) -> CloudAnchorFuture {
        CloudAnchorFuture(completion: completion, start: operation)
    }
}

/// The outcome of a Cloud Anchor host or resolve operation.
///
/// Carries Android's `onHosted(cloudAnchorId, state)` payload: the cloud anchor
/// id (the freshly hosted id, or the resolved id echoed back) and the
/// ``CloudAnchorState``. `cloudAnchorId` is `nil` on an error state.
public struct CloudAnchorResult: Sendable, Equatable {
    /// The cloud anchor id, or `nil` when `state.isError`.
    public let cloudAnchorId: String?

    /// The terminal state reported by ARCore Cloud.
    public let state: CloudAnchorState

    public init(cloudAnchorId: String?, state: CloudAnchorState) {
        self.cloudAnchorId = cloudAnchorId
        self.state = state
    }
}

/// Mirror of Android's `Anchor.CloudAnchorState`.
///
/// Map ARCore iOS's `GARCloudAnchorState` into this in the app's `operation`
/// closure so completion handlers are written once against a platform-neutral
/// enum.
public enum CloudAnchorState: Sendable, Equatable {
    /// No hosting / resolving has been requested, or it is not yet running.
    case none
    /// The request is in flight.
    case taskInProgress
    /// The anchor was hosted / resolved successfully.
    case success
    /// An internal error occurred.
    case errorInternal
    /// The ARCore Cloud API key is missing or not authorized.
    case errorNotAuthorized
    /// The ARCore Cloud service was unavailable.
    case errorServiceUnavailable
    /// The app exhausted its ARCore Cloud request quota.
    case errorResourceExhausted
    /// The captured feature map was insufficient to host the anchor.
    case errorHostingDatasetProcessingFailed
    /// The requested cloud anchor id was not found when resolving.
    case errorResolvingCloudIdNotFound
    /// The hosting SDK version was too old to resolve this anchor.
    case errorResolvingSDKVersionTooOld
    /// The hosting SDK version was too new to resolve this anchor.
    case errorResolvingSDKVersionTooNew
    /// The hosting service was unavailable.
    case errorHostingServiceUnavailable

    /// `true` for any of the `error…` cases — matches Android's
    /// `Anchor.CloudAnchorState.isError`.
    public var isError: Bool {
        switch self {
        case .none, .taskInProgress, .success:
            return false
        case .errorInternal,
             .errorNotAuthorized,
             .errorServiceUnavailable,
             .errorResourceExhausted,
             .errorHostingDatasetProcessingFailed,
             .errorResolvingCloudIdNotFound,
             .errorResolvingSDKVersionTooOld,
             .errorResolvingSDKVersionTooNew,
             .errorHostingServiceUnavailable:
            return true
        }
    }
}

/// A cancellable handle to an in-flight Cloud Anchor host or resolve operation —
/// the iOS counterpart of Android's `HostCloudAnchorFuture` /
/// `ResolveCloudAnchorFuture`.
///
/// The completion supplied to ``CloudAnchorNode/host(ttlDays:completion:operation:)``
/// / ``CloudAnchorNode/resolve(cloudAnchorId:completion:operation:)`` fires
/// **at most once**, and **never** after ``cancel()`` or after this handle is
/// deallocated. See ``CloudAnchorNode`` for the full rationale and usage.
@MainActor
public final class CloudAnchorFuture {

    /// Lifecycle of the handle.
    public enum Status: Sendable, Equatable {
        /// Started, no result delivered yet.
        case pending
        /// The completion handler fired with a result.
        case completed
        /// ``cancel()`` ran before any result — the completion will not fire.
        case cancelled
    }

    /// Current lifecycle state. Read on the main actor.
    public private(set) var status: Status = .pending

    /// The app's completion handler. Cleared the moment it fires or is cancelled
    /// so the result can be delivered only once.
    private var completion: ((CloudAnchorResult) -> Void)?

    /// The hook that tears down the underlying `GARSession` request. Cleared
    /// once it runs (on completion or cancellation) so it runs at most once.
    private var cancelHandler: (() -> Void)?

    /// Starts `operation`, wiring its `report` callback through this handle's
    /// single-fire gate.
    ///
    /// - Parameters:
    ///   - completion: Delivered at most once with the gated result.
    ///   - start: The operation that kicks off the underlying request and
    ///     returns an optional cancel hook.
    init(
        completion: @escaping (CloudAnchorResult) -> Void,
        start operation: (_ report: @escaping (CloudAnchorResult) -> Void) -> (() -> Void)?
    ) {
        self.completion = completion
        // The reported closure is what the app's GARSession callback invokes.
        // It captures `self` weakly so a callback that fires after this handle
        // is gone (the exact post-teardown leak #1768 guards against) is an
        // inert no-op rather than a use-after-free or a late side effect.
        self.cancelHandler = operation { [weak self] result in
            self?.deliver(result)
        }
    }

    /// Gated delivery of a reported result — fires the completion exactly once.
    private func deliver(_ result: CloudAnchorResult) {
        guard status == .pending else { return }
        status = .completed
        let handler = completion
        completion = nil
        cancelHandler = nil
        handler?(result)
    }

    /// Cancels the pending operation: invokes the underlying cancel hook and
    /// guarantees the completion handler will not fire.
    ///
    /// Idempotent and safe to call after completion — a second call, or a call
    /// once the result already arrived, is a no-op. This is the direct analogue
    /// of Android's `future.cancel()` inside `DisposableEffect { onDispose { … } }`.
    public func cancel() {
        guard status == .pending else { return }
        status = .cancelled
        let hook = cancelHandler
        completion = nil
        cancelHandler = nil
        hook?()
    }

    deinit {
        // Safety net: a handle deallocated while still pending (e.g. the owning
        // view vanished without an explicit `cancel()`) must still tear down the
        // underlying request so it does not keep running — and billing — in the
        // background. `cancelHandler` is non-nil only while pending, so this
        // runs at most once and never double-fires after `cancel()`/`deliver`.
        cancelHandler?()
    }
}
