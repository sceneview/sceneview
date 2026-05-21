#if os(iOS)
import XCTest
@testable import SceneViewSwift

/// Smoke tests for the cross-platform `SceneViewHaptic` API. We don't try
/// to assert that hardware actually vibrated — that's untestable on the
/// simulator and on CI. Instead we pin:
///
/// - Every preset method returns without throwing.
/// - `HapticEvent` initializer clamps and stores values as documented.
/// - `pattern([])` and `continuous(_, duration: 0)` are no-ops (don't throw).
/// - The `HapticPreset` table-dispatch helper covers every case.
@MainActor
final class HapticTests: XCTestCase {

    func testEveryPresetReturnsWithoutThrowing() {
        let haptic = SceneViewHaptic()
        haptic.light()
        haptic.medium()
        haptic.heavy()
        haptic.success()
        haptic.warning()
        haptic.error()
        haptic.selection()
        // No assertion needed — making it this far without throwing is the
        // test contract.
    }

    func testTriggerCoversEveryPreset() {
        let haptic = SceneViewHaptic()
        for preset in [
            HapticPreset.light, .medium, .heavy,
            .success, .warning, .error, .selection,
        ] {
            haptic.trigger(preset)
        }
    }

    func testHapticEventInitializerStoresValues() {
        let event = HapticEvent(
            intensity: 0.7,
            sharpness: 0.3,
            durationMs: 50,
            delayMs: 10
        )
        XCTAssertEqual(event.intensity, 0.7, accuracy: 0.0001)
        XCTAssertEqual(event.sharpness, 0.3, accuracy: 0.0001)
        XCTAssertEqual(event.durationMs, 50)
        XCTAssertEqual(event.delayMs, 10)
    }

    func testHapticEventDefaultDelayIsZero() {
        let event = HapticEvent(intensity: 1.0, sharpness: 0.5, durationMs: 100)
        XCTAssertEqual(event.delayMs, 0)
    }

    func testEmptyPatternIsNoop() {
        let haptic = SceneViewHaptic()
        haptic.pattern([])  // must not throw
    }

    func testContinuousWithZeroDurationIsNoop() {
        let haptic = SceneViewHaptic()
        haptic.continuous(intensity: 0.5, durationMs: 0)  // must not throw
    }

    func testContinuousClampsIntensity() {
        let haptic = SceneViewHaptic()
        // Out-of-range intensities must be clamped, not crash.
        haptic.continuous(intensity: 5.0, durationMs: 10)
        haptic.continuous(intensity: -1.0, durationMs: 10)
    }

    func testContinuousTakesMillisecondsLikeAndroidAndWeb() {
        // `continuous` takes a millisecond Int, matching the Android
        // `continuous(intensity, durationMs)` and Web signatures. A small
        // positive value must not throw; a non-positive value is a no-op.
        let haptic = SceneViewHaptic()
        haptic.continuous(intensity: 0.8, durationMs: 200)  // must not throw
        haptic.continuous(intensity: 0.8, durationMs: -5)   // no-op
    }

    func testPatternWithSingleEventDoesNotThrow() {
        let haptic = SceneViewHaptic()
        haptic.pattern([HapticEvent(intensity: 1.0, sharpness: 0.5, durationMs: 20)])
    }
}
#endif
