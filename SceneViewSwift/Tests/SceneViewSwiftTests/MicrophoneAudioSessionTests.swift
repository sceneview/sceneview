#if os(iOS)
import AVFoundation
import XCTest
@testable import SceneViewSwift

/// ``MicrophoneAudioSession`` against a scripted audio session: what a recording switches,
/// activates, deactivates and puts back.
final class MicrophoneAudioSessionTests: XCTestCase {

    func testAnInactiveSessionUnderAnotherAppsMusicIsDeactivatedThenPutBack() {
        // Music from another app plays over .soloAmbient: this app's session is inactive.
        let fake = FakeAudioSession(category: .soloAmbient)
        fake.secondaryAudioShouldBeSilencedHint = true
        let audio = MicrophoneAudioSession(fake)

        XCTAssertTrue(audio.begin())
        XCTAssertEqual(fake.category, .playAndRecord)
        XCTAssertEqual(fake.categoryOptions, MicrophoneAudioSession.recordingOptions)

        audio.end()
        XCTAssertEqual(fake.calls, [
            .setCategory(.playAndRecord), .setActive(true, []),
            .setActive(false, .notifyOthersOnDeactivation), .setCategory(.soloAmbient),
        ], "deactivated before the category is put back, so other apps' audio resumes")
        XCTAssertEqual(fake.category, .soloAmbient)
        XCTAssertEqual(fake.categoryOptions, [])
    }

    func testAnActiveSessionIsPutBackWithoutDeactivating() {
        // No other audio: the app's own sound (RealityKit audio, an AVPlayer) may be playing
        // in its session, which deactivating would stop.
        let fake = FakeAudioSession(category: .soloAmbient)
        let audio = MicrophoneAudioSession(fake)

        XCTAssertTrue(audio.begin())
        audio.end()
        XCTAssertEqual(fake.calls, [.setCategory(.playAndRecord), .setActive(true, []), .setCategory(.soloAmbient)])
    }

    func testTheAppsOwnSoundUnderAnotherAppsMixingAudioIsPutBackWithoutDeactivating() {
        // The app plays over .soloAmbient while another app's navigation prompt mixes in:
        // other audio is playing, but it does not ask for this app to be silenced, so this
        // app's session may be active and deactivating it would stop its sound.
        for category in [AVAudioSession.Category.soloAmbient, .playback] {
            let fake = FakeAudioSession(category: category)
            fake.secondaryAudioShouldBeSilencedHint = false
            let audio = MicrophoneAudioSession(fake)

            XCTAssertTrue(audio.begin())
            audio.end()
            XCTAssertEqual(fake.calls, [.setCategory(.playAndRecord), .setActive(true, []), .setCategory(category)],
                           category.rawValue)
        }
    }

    func testAMixingCategoryIsPutBackWithoutDeactivatingEvenUnderOtherAudio() {
        // Other audio playing says nothing about a session that mixes with it.
        for (category, options) in [(AVAudioSession.Category.ambient, AVAudioSession.CategoryOptions()),
                                    (.playback, [.mixWithOthers]), (.playback, [.duckOthers])] {
            let fake = FakeAudioSession(category: category, options: options)
            fake.secondaryAudioShouldBeSilencedHint = true
            let audio = MicrophoneAudioSession(fake)

            XCTAssertTrue(audio.begin())
            audio.end()
            XCTAssertEqual(fake.calls, [.setCategory(.playAndRecord), .setActive(true, []), .setCategory(category)],
                           "\(category.rawValue) \(options.rawValue)")
            XCTAssertEqual(fake.categoryOptions, options)
        }
    }

    func testAFailedSwitchActivatesNothingAndPutsNothingBack() {
        let fake = FakeAudioSession(category: .soloAmbient)
        fake.failSetCategory = true
        let audio = MicrophoneAudioSession(fake)

        XCTAssertFalse(audio.begin(), "the microphone cannot record")
        audio.end()
        XCTAssertEqual(fake.calls, [.setCategory(.playAndRecord)], "one failed attempt, nothing else")
        XCTAssertEqual(fake.category, .soloAmbient)
    }

    func testACategoryTheAppChangedDuringTheRecordingIsKept() {
        let fake = FakeAudioSession(category: .soloAmbient)
        fake.secondaryAudioShouldBeSilencedHint = true
        let audio = MicrophoneAudioSession(fake)
        XCTAssertTrue(audio.begin())

        // The app starts a voice chat mid-recording.
        try? fake.setCategory(.playAndRecord, mode: .voiceChat, options: [.duckOthers])
        fake.calls.removeAll()

        audio.end()
        XCTAssertEqual(fake.calls, [], "neither deactivated nor switched back")
        XCTAssertEqual(fake.mode, .voiceChat)
    }

    func testAnOptionTheAppChangedDuringTheRecordingIsKept() {
        let fake = FakeAudioSession(category: .ambient)
        let audio = MicrophoneAudioSession(fake)
        XCTAssertTrue(audio.begin())

        try? fake.setCategory(.playAndRecord, mode: .default, options: [.mixWithOthers])
        fake.calls.removeAll()

        audio.end()
        XCTAssertEqual(fake.calls, [])
        XCTAssertEqual(fake.categoryOptions, [.mixWithOthers])
    }

    func testTheSetupIsComparedAsTheSystemReportsIt() {
        // The system may report other options than the ones asked for.
        let fake = FakeAudioSession(category: .playback)
        fake.normalise = { $0.subtracting(.allowBluetoothA2DP) }
        let audio = MicrophoneAudioSession(fake)

        XCTAssertTrue(audio.begin())
        audio.end()
        XCTAssertEqual(fake.category, .playback, "still put back")
    }

    func testARecordingCategoryIsTheAppsOwnAndLeftAlone() {
        let fake = FakeAudioSession(category: .playAndRecord, mode: .videoRecording)
        let audio = MicrophoneAudioSession(fake)

        XCTAssertTrue(audio.begin())
        audio.end()
        XCTAssertEqual(fake.calls, [.setActive(true, [])], "activated for the microphone, never switched")
        XCTAssertEqual(fake.mode, .videoRecording)
    }

    func testAFailedActivationIsNotUndone() {
        let fake = FakeAudioSession(category: .soloAmbient)
        fake.secondaryAudioShouldBeSilencedHint = true
        fake.failSetActive = true
        let audio = MicrophoneAudioSession(fake)

        XCTAssertTrue(audio.begin())
        audio.end()
        XCTAssertEqual(fake.calls, [
            .setCategory(.playAndRecord), .setActive(true, []), .setCategory(.soloAmbient),
        ], "no deactivation of a session it never activated")
    }
}

private final class FakeAudioSession: AudioSessionControlling {
    enum Call: Equatable {
        case setCategory(AVAudioSession.Category)
        case setActive(Bool, AVAudioSession.SetActiveOptions)
    }

    private(set) var category: AVAudioSession.Category
    private(set) var mode: AVAudioSession.Mode
    private(set) var categoryOptions: AVAudioSession.CategoryOptions
    var secondaryAudioShouldBeSilencedHint = false
    var calls: [Call] = []
    var failSetCategory = false
    var failSetActive = false
    var normalise: (AVAudioSession.CategoryOptions) -> AVAudioSession.CategoryOptions = { $0 }

    init(category: AVAudioSession.Category, mode: AVAudioSession.Mode = .default,
         options: AVAudioSession.CategoryOptions = []) {
        self.category = category
        self.mode = mode
        categoryOptions = options
    }

    func setCategory(_ category: AVAudioSession.Category, mode: AVAudioSession.Mode,
                     options: AVAudioSession.CategoryOptions) throws {
        calls.append(.setCategory(category))
        if failSetCategory { throw NSError(domain: NSOSStatusErrorDomain, code: -50) }
        self.category = category
        self.mode = mode
        categoryOptions = normalise(options)
    }

    func setActive(_ active: Bool, options: AVAudioSession.SetActiveOptions) throws {
        calls.append(.setActive(active, options))
        if failSetActive { throw NSError(domain: NSOSStatusErrorDomain, code: -50) }
    }
}
#endif
