#if os(iOS)
import CoreVideo
import XCTest
@testable import SceneViewSwift

/// The ``MonocularDepthEstimator`` contract: never two calls at once, even
/// when a view replaces its depth source while the old worker is still busy.
final class MLDepthWorkerTests: XCTestCase {
    final class SlowEstimator: MonocularDepthEstimator, @unchecked Sendable {
        let identifier = "slow"
        let inputSize = (width: 4, height: 3)
        let inputPixelFormat = kCVPixelFormatType_32BGRA
        let outputKind = MonocularDepthOutputKind.affineInverse
        private let lock = NSLock()
        private var running = 0
        private(set) var maxConcurrent = 0
        private(set) var calls = 0

        func warmUp() throws {
            lock.withLock {
                running += 1
                calls += 1
                maxConcurrent = max(maxConcurrent, running)
            }
            Thread.sleep(forTimeInterval: 0.1)
            lock.withLock { running -= 1 }
        }

        func estimate(_ pixelBuffer: CVPixelBuffer) throws -> MonocularDepthEstimate {
            MonocularDepthEstimate(width: 4, height: 3, values: [Float](repeating: 1, count: 12))
        }
    }

    func testWorkersOfOneEstimatorShareOneSerialQueue() {
        let a = SlowEstimator(), b = SlowEstimator()
        XCTAssertTrue(MLDepthWorker.queue(for: a) === MLDepthWorker.queue(for: a))
        XCTAssertFalse(MLDepthWorker.queue(for: a) === MLDepthWorker.queue(for: b))
    }

    func testAReplacedWorkerNeverOverlapsTheOldOne() {
        let estimator = SlowEstimator()
        let old = MLDepthWorker(estimator: estimator, benchmark: false)
        let new = MLDepthWorker(estimator: estimator, benchmark: false)
        old.prepare()
        new.prepare()   // what `setSource` does while the old warm-up still runs
        let done = expectation(description: "both warm-ups finished")
        DispatchQueue.global().async {
            while old.phase != .ready || new.phase != .ready { Thread.sleep(forTimeInterval: 0.01) }
            done.fulfill()
        }
        wait(for: [done], timeout: 5)
        XCTAssertEqual(estimator.calls, 2)
        XCTAssertEqual(estimator.maxConcurrent, 1)
    }

    func testACancelledWorkerDoesNotWarmUp() {
        let estimator = SlowEstimator()
        let busy = MLDepthWorker(estimator: estimator, benchmark: false)
        let cancelled = MLDepthWorker(estimator: estimator, benchmark: false)
        busy.prepare()
        cancelled.prepare()
        cancelled.cancel()
        let done = expectation(description: "queue drained")
        MLDepthWorker.queue(for: estimator).async { done.fulfill() }
        wait(for: [done], timeout: 5)
        XCTAssertEqual(estimator.calls, 1)
    }
}
#endif
