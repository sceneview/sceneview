import XCTest
@testable import SceneViewSwift

/// Verifies the Cloud Anchor host/resolve **Future + cancel-on-dispose**
/// lifecycle (#1859, iOS parity with Android #1813 / #1768).
///
/// These tests exercise the portable cancellation gate headlessly — they do
/// **not** (and cannot) drive a live ARCore Cloud round-trip, which needs a real
/// ARKit session, a Cloud API key, and the network. The app-supplied
/// `operation` closure is stubbed here with a manual reporter so we can fire a
/// late callback and assert it is dropped — exactly the post-teardown leak the
/// pattern guards against.
///
/// Mirrors the weak-ref reclamation style of `GestureSystemTests`
/// (`testEntityWithHandlerIsDeallocatedWhenReleased`).
@MainActor
final class CloudAnchorNodeTests: XCTestCase {

    /// Captures the `report` closure the future hands the operation, plus a flag
    /// recording whether the underlying cancel hook ran — the stand-in for a
    /// real `GARSession` host/resolve request.
    private final class StubOperation {
        var report: ((CloudAnchorResult) -> Void)?
        private(set) var cancelHookFired = 0

        /// The closure passed as `operation:` — stashes the reporter and returns
        /// a cancel hook that bumps `cancelHookFired`.
        func make() -> (_ report: @escaping (CloudAnchorResult) -> Void) -> (() -> Void)? {
            return { [weak self] report in
                self?.report = report
                return { self?.cancelHookFired += 1 }
            }
        }
    }

    // MARK: - Happy path

    func testHostDeliversResultExactlyOnce() throws {
        let op = StubOperation()
        var received: [CloudAnchorResult] = []

        let future = try CloudAnchorNode.host(ttlDays: 1) { result in
            received.append(result)
        } operation: { op.make()($0) }

        XCTAssertEqual(future.status, .pending)

        // ARCore Cloud reports success.
        op.report?(CloudAnchorResult(cloudAnchorId: "abc-123", state: .success))
        XCTAssertEqual(future.status, .completed)
        XCTAssertEqual(received.count, 1)
        XCTAssertEqual(received.first?.cloudAnchorId, "abc-123")
        XCTAssertEqual(received.first?.state, .success)

        // A second (spurious) report must be dropped — single-fire.
        op.report?(CloudAnchorResult(cloudAnchorId: "def-456", state: .success))
        XCTAssertEqual(received.count, 1, "completion must fire at most once")
    }

    // MARK: - Cancellation (core acceptance #1859)

    func testCancelShortCircuitsCompletion() throws {
        let op = StubOperation()
        var fired = false

        let future = try CloudAnchorNode.host(ttlDays: 7) { _ in
            fired = true
        } operation: { op.make()($0) }

        future.cancel()
        XCTAssertEqual(future.status, .cancelled)
        XCTAssertEqual(op.cancelHookFired, 1,
                       "cancel() must invoke the underlying GARSession cancel hook")

        // A callback that arrives after cancel (in-flight round-trip resolving
        // late) must NOT reach the completion handler.
        op.report?(CloudAnchorResult(cloudAnchorId: "late", state: .success))
        XCTAssertFalse(fired, "completion must not fire after cancel()")
        XCTAssertEqual(future.status, .cancelled)
    }

    func testCancelIsIdempotentAndNoOpAfterCompletion() throws {
        let op = StubOperation()

        let future = try CloudAnchorNode.host(ttlDays: 1) { _ in
        } operation: { op.make()($0) }

        future.cancel()
        future.cancel()
        XCTAssertEqual(op.cancelHookFired, 1, "cancel hook must run at most once")

        // Completing-then-cancelling must also be inert.
        let op2 = StubOperation()
        var fired = false
        let future2 = try CloudAnchorNode.host(ttlDays: 1) { _ in
            fired = true
        } operation: { op2.make()($0) }
        op2.report?(CloudAnchorResult(cloudAnchorId: "x", state: .success))
        XCTAssertTrue(fired)
        future2.cancel()
        XCTAssertEqual(future2.status, .completed, "cancel after completion is a no-op")
        XCTAssertEqual(op2.cancelHookFired, 0, "cancel hook must not fire once already completed")
    }

    // MARK: - ttlDays validation (mirrors Android IllegalArgumentException)

    func testHostRejectsTTLOutsideRange() {
        let op = StubOperation()
        for bad in [0, -1, 366, 1000] {
            XCTAssertThrowsError(
                try CloudAnchorNode.host(ttlDays: bad) { _ in } operation: { op.make()($0) },
                "ttlDays \(bad) must be rejected"
            ) { error in
                XCTAssertEqual(error as? CloudAnchorNode.CloudAnchorError, .ttlOutOfRange(bad))
            }
        }
    }

    func testHostAcceptsTTLRangeBoundaries() throws {
        for good in [1, 7, 365] {
            let op = StubOperation()
            XCTAssertNoThrow(
                try CloudAnchorNode.host(ttlDays: good) { _ in } operation: { op.make()($0) }
            )
        }
    }

    // MARK: - resolve

    func testResolveCancelShortCircuitsCompletion() {
        let op = StubOperation()
        var fired = false

        let future = CloudAnchorNode.resolve(cloudAnchorId: "abc-123") { _ in
            fired = true
        } operation: { op.make()($0) }

        future.cancel()
        op.report?(CloudAnchorResult(cloudAnchorId: "abc-123", state: .success))
        XCTAssertFalse(fired, "resolve completion must not fire after cancel()")
        XCTAssertEqual(op.cancelHookFired, 1)
    }

    // MARK: - Deinit safety net

    func testDeinitCancelsStillPendingOperation() throws {
        let op = StubOperation()
        weak var weakFuture: CloudAnchorFuture?

        try autoreleasepool {
            let future = try CloudAnchorNode.host(ttlDays: 1) { _ in
            } operation: { op.make()($0) }
            weakFuture = future
            XCTAssertNotNil(weakFuture)
            // Drop the only strong reference without an explicit cancel().
        }

        XCTAssertNil(weakFuture, "future must deallocate once the strong ref is dropped")
        XCTAssertEqual(op.cancelHookFired, 1,
                       "deinit must tear down the still-pending operation (#1768 hygiene)")

        // A reporter that fires after dealloc is an inert no-op (weak self).
        op.report?(CloudAnchorResult(cloudAnchorId: "ghost", state: .success))
    }

    func testIsErrorMatchesAndroidSemantics() {
        XCTAssertFalse(CloudAnchorState.none.isError)
        XCTAssertFalse(CloudAnchorState.taskInProgress.isError)
        XCTAssertFalse(CloudAnchorState.success.isError)
        XCTAssertTrue(CloudAnchorState.errorInternal.isError)
        XCTAssertTrue(CloudAnchorState.errorNotAuthorized.isError)
        XCTAssertTrue(CloudAnchorState.errorResolvingCloudIdNotFound.isError)
    }
}
