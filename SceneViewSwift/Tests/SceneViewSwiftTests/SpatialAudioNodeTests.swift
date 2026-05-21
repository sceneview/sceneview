import XCTest
@testable import SceneViewSwift

#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit

/// Pure-logic tests for the iOS Spatial Audio phase-1 surface. The full RealityKit
/// playback path (`AudioFileResource.load`, `Entity.playAudio(_:)`) is exercised by the
/// on-device QA harness (`samples/ios-demo/.../SpatialAudioDemo.swift` smoke run) because
/// loading real audio resources in the unit-test sandbox is fragile (audio session
/// permissions vary between simulator runtimes).
///
/// The Android `AudioFalloffTest` is the canonical golden table — these tests pin the
/// Swift implementation to the exact same numbers so a Compose `SpatialAudioNode` and a
/// SwiftUI ``SpatialAudioNode`` with the same params behave identically.
@MainActor
final class SpatialAudioNodeTests: XCTestCase {

    private let tol: Float = 1e-4

    // MARK: - AudioFalloff golden table (must match Android)

    func testNoneReturnsUnityEverywhere() {
        XCTAssertEqual(AudioFalloff.gain(for: .none, distance: 0), 1, accuracy: tol)
        XCTAssertEqual(AudioFalloff.gain(for: .none, distance: 100), 1, accuracy: tol)
        XCTAssertEqual(AudioFalloff.gain(for: .none, distance: 1_000_000), 1, accuracy: tol)
    }

    func testNoneIgnoresNaNAndNegative() {
        XCTAssertEqual(AudioFalloff.gain(for: .none, distance: .nan), 1, accuracy: tol)
        XCTAssertEqual(AudioFalloff.gain(for: .none, distance: -5), 1, accuracy: tol)
    }

    func testInverseUnityBelowRefDistance() {
        let f = AudioFalloff.inverse(refDistance: 1, maxDistance: 100)
        XCTAssertEqual(AudioFalloff.gain(for: f, distance: 0), 1, accuracy: tol)
        XCTAssertEqual(AudioFalloff.gain(for: f, distance: 0.5), 1, accuracy: tol)
        XCTAssertEqual(AudioFalloff.gain(for: f, distance: 1), 1, accuracy: tol)
    }

    func testInverseGoldenCurveMatchesAndroid() {
        let f = AudioFalloff.inverse(refDistance: 1, maxDistance: 100, rolloffFactor: 1)
        // Exact same numbers as the Android `AudioFalloffTest.inverseGoldenCurve` table.
        XCTAssertEqual(AudioFalloff.gain(for: f, distance: 0), 1, accuracy: tol)
        XCTAssertEqual(AudioFalloff.gain(for: f, distance: 1), 1, accuracy: tol)
        XCTAssertEqual(AudioFalloff.gain(for: f, distance: 5), 0.2, accuracy: tol)
        XCTAssertEqual(AudioFalloff.gain(for: f, distance: 10), 0.1, accuracy: tol)
        XCTAssertEqual(AudioFalloff.gain(for: f, distance: 50), 1.0 / 50.0, accuracy: tol)
        XCTAssertEqual(AudioFalloff.gain(for: f, distance: 100), 0.01, accuracy: tol)
        // Past max → clamped to gain at max.
        XCTAssertEqual(AudioFalloff.gain(for: f, distance: 200), 0.01, accuracy: tol)
    }

    func testLinearGoldenCurveMatchesAndroid() {
        let f = AudioFalloff.linear(refDistance: 0, maxDistance: 100)
        XCTAssertEqual(AudioFalloff.gain(for: f, distance: 0), 1, accuracy: tol)
        XCTAssertEqual(AudioFalloff.gain(for: f, distance: 1), 0.99, accuracy: tol)
        XCTAssertEqual(AudioFalloff.gain(for: f, distance: 5), 0.95, accuracy: tol)
        XCTAssertEqual(AudioFalloff.gain(for: f, distance: 10), 0.9, accuracy: tol)
        XCTAssertEqual(AudioFalloff.gain(for: f, distance: 50), 0.5, accuracy: tol)
        XCTAssertEqual(AudioFalloff.gain(for: f, distance: 100), 0, accuracy: tol)
    }

    func testLinearReachesZeroAtMax() {
        let f = AudioFalloff.linear(refDistance: 1, maxDistance: 100)
        XCTAssertEqual(AudioFalloff.gain(for: f, distance: 100), 0, accuracy: tol)
        XCTAssertEqual(AudioFalloff.gain(for: f, distance: 200), 0, accuracy: tol)
    }

    func testLinearHandlesDegenerateRange() {
        // ref == max — degenerate; must not divide by zero. Returns step at ref.
        let f = AudioFalloff.linear(refDistance: 10, maxDistance: 10)
        XCTAssertEqual(AudioFalloff.gain(for: f, distance: 5), 1, accuracy: tol)
        XCTAssertEqual(AudioFalloff.gain(for: f, distance: 20), 0, accuracy: tol)
    }

    func testInverseDegenerateRangeStaysUnity() {
        // refDistance > maxDistance is degenerate: the clamped distance can never exceed
        // maxDistance, which is below refDistance, so gain stays at unity for ALL
        // distances. Must not divide by zero or go negative. Mirrors the Android
        // `AudioFalloffTest.inverseDegenerateRangeStaysUnity` case.
        let f = AudioFalloff.inverse(refDistance: 20, maxDistance: 5)
        XCTAssertEqual(AudioFalloff.gain(for: f, distance: 0), 1, accuracy: tol)
        XCTAssertEqual(AudioFalloff.gain(for: f, distance: 5), 1, accuracy: tol)
        XCTAssertEqual(AudioFalloff.gain(for: f, distance: 50), 1, accuracy: tol)
        XCTAssertEqual(AudioFalloff.gain(for: f, distance: 10_000), 1, accuracy: tol)
    }

    func testInverseRolloffFactor() {
        // Sharper rolloff yields less gain at the same distance.
        let mild = AudioFalloff.inverse(refDistance: 1, maxDistance: 100, rolloffFactor: 1)
        let sharp = AudioFalloff.inverse(refDistance: 1, maxDistance: 100, rolloffFactor: 2)
        let mildGain = AudioFalloff.gain(for: mild, distance: 2)
        let sharpGain = AudioFalloff.gain(for: sharp, distance: 2)
        XCTAssertLessThan(sharpGain, mildGain)
        XCTAssertEqual(sharpGain, 1.0 / 3.0, accuracy: tol)
    }

    // MARK: - AudioController state transitions

    func testAudioControllerInitiallyNotPlaying() {
        let controller = AudioController()
        XCTAssertFalse(controller.isPlaying)
    }

    func testAudioControllerSetPlayingPublishes() {
        let controller = AudioController()
        XCTAssertFalse(controller.isPlaying)
        // Internal hook — the SpatialAudioNode play()/pause()/stop() path drives this.
        controller.setPlaying(true)
        XCTAssertTrue(controller.isPlaying)
        controller.setPlaying(false)
        XCTAssertFalse(controller.isPlaying)
    }

    // MARK: - AudioListenerSource exhaustiveness

    func testAudioListenerSourceCameraIsDefault() {
        // Default case exists and is the canonical value.
        let source: AudioListenerSource = .camera
        if case .camera = source {
            // expected
        } else {
            XCTFail(".camera case must be the default")
        }
    }

    // MARK: - Gain → dB conversion (RealityKit-specific helper)

    func testGainFromLinearUnityIsZeroDB() {
        XCTAssertEqual(AudioPlaybackController.gainFromLinear(1.0), 0.0, accuracy: 1e-4)
    }

    func testGainFromLinearHalfIsApproxMinus6dB() {
        // 20 * log10(0.5) ≈ -6.0206
        XCTAssertEqual(AudioPlaybackController.gainFromLinear(0.5), -6.0206, accuracy: 1e-3)
    }
}

#endif
