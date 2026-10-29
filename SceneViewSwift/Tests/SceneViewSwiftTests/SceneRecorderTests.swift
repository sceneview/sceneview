#if os(iOS)
import AVFoundation
import RealityKit
import UIKit
import XCTest
@testable import SceneViewSwift

/// ``SceneRecorder`` against a synthetic frame source: no camera, no RealityKit render, real
/// MP4 files written by the real encoder.
@MainActor
final class SceneRecorderTests: XCTestCase {

    private var directory: URL!

    override func setUp() async throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("SceneRecorderTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: directory)
    }

    private func file(_ name: String) -> URL { directory.appendingPathComponent(name) }

    private func waitUntil(_ timeout: TimeInterval = 5, _ condition: () -> Bool) async {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition(), Date() < deadline {
            try? await Task.sleep(nanoseconds: 20_000_000)
        }
    }

    // MARK: - Not attached

    func testNothingCanBeCapturedBeforeASceneIsAttached() async {
        let recorder = SceneRecorder()
        XCTAssertFalse(recorder.isAttached)
        XCTAssertFalse(recorder.startRecording(to: file("a.mp4")))
        XCTAssertEqual(recorder.lastError as? SceneRecorderError, .notAttached)
        XCTAssertFalse(recorder.isRecording)
        let photo = await recorder.takePhoto()
        XCTAssertNil(photo)
        XCTAssertEqual(recorder.lastError as? SceneRecorderError, .notAttached)
    }

    func testInvalidSettingsAreRefusedWithAReason() {
        let recorder = SceneRecorder()
        recorder.attach(SyntheticSource(width: 1080, height: 1920))
        XCTAssertFalse(recorder.startRecording(to: file("a.mp4"), settings: VideoSettings(frameRate: 0)))
        XCTAssertEqual(recorder.lastError as? SceneRecorderError, .invalidSettings("frameRate must be positive, got 0"))
    }

    func testAViewNotLaidOutYetHasNoVideoSize() {
        let recorder = SceneRecorder()
        recorder.attach(SyntheticSource(width: 0, height: 0))
        XCTAssertFalse(recorder.startRecording(to: file("a.mp4")))
        XCTAssertEqual(recorder.lastError as? SceneRecorderError, .noVideoSize(width: 0, height: 0))
    }

    // MARK: - Video

    func testRecordsAnMP4AtTheChosenSizeFromRenderedFrames() async throws {
        let recorder = SceneRecorder()
        let source = SyntheticSource(width: 1179, height: 2556)
        recorder.attach(source)
        var finished: [SceneRecording?] = []
        recorder.onRecordingFinished = { finished.append($0) }

        XCTAssertTrue(recorder.startRecording(to: file("video.mp4")))
        XCTAssertTrue(recorder.isRecording)
        XCTAssertNil(recorder.lastError)
        try await Task.sleep(nanoseconds: 800_000_000)
        XCTAssertGreaterThan(recorder.elapsedTime, 0.4, "the elapsed time ticks while recording")
        let recordingResult = await recorder.stopRecording()
        let recording = try XCTUnwrap(recordingResult)

        XCTAssertFalse(recorder.isRecording)
        XCTAssertEqual(recorder.elapsedTime, 0)
        XCTAssertEqual(recording.url, file("video.mp4"))
        XCTAssertEqual(recording.width, 884)
        XCTAssertEqual(recording.height, 1920)
        XCTAssertFalse(recording.hasAudio)
        XCTAssertFalse(recording.reachedMaxDuration)
        XCTAssertFalse(recording.hasOverlay)
        XCTAssertGreaterThan(recording.duration, 0.5)
        XCTAssertEqual(recorder.lastRecording, recording)
        XCTAssertEqual(finished, [recording], "onRecordingFinished fires on a manual stop too")
        XCTAssertFalse(source.isStreaming, "the source stops handing frames over")

        let asset = AVURLAsset(url: recording.url)
        let tracks = try await asset.loadTracks(withMediaType: .video)
        let track = try XCTUnwrap(tracks.first)
        let naturalSize = try await track.load(.naturalSize)
        XCTAssertEqual(naturalSize, CGSize(width: 884, height: 1920))
        let duration = try await asset.load(.duration).seconds
        XCTAssertEqual(duration, recording.duration, accuracy: 0.1)
        let frameRate = try await track.load(.nominalFrameRate)
        XCTAssertLessThanOrEqual(frameRate, 31, "a 60 Hz source is sampled down to 30 fps")
        XCTAssertGreaterThan(frameRate, 20)
        let audio = try await asset.loadTracks(withMediaType: .audio)
        XCTAssertTrue(audio.isEmpty)
    }

    func testASecondStartIsRefusedAndTheFirstRecordingKeepsGoing() async {
        let recorder = SceneRecorder()
        recorder.attach(SyntheticSource(width: 720, height: 1280))
        XCTAssertTrue(recorder.startRecording(to: file("first.mp4")))
        XCTAssertFalse(recorder.startRecording(to: file("second.mp4")))
        XCTAssertNil(recorder.lastError, "a refused second start leaves lastError alone")
        XCTAssertTrue(recorder.isRecording)
        try? await Task.sleep(nanoseconds: 300_000_000)
        let recording = await recorder.stopRecording()
        XCTAssertEqual(recording?.url, file("first.mp4"))
        XCTAssertFalse(FileManager.default.fileExists(atPath: file("second.mp4").path))
        let again = await recorder.stopRecording()
        XCTAssertNil(again, "nothing left to stop")
    }

    func testTheMaximumDurationStopsTheRecordingByItself() async throws {
        let recorder = SceneRecorder()
        recorder.attach(SyntheticSource(width: 1080, height: 1920))
        var finished: [SceneRecording?] = []
        recorder.onRecordingFinished = { finished.append($0) }

        XCTAssertTrue(recorder.startRecording(to: file("capped.mp4"), settings: VideoSettings(maxDuration: 0.6)))
        await waitUntil { !finished.isEmpty }

        let recording = try XCTUnwrap(finished.first ?? nil)
        XCTAssertTrue(recording.reachedMaxDuration)
        XCTAssertFalse(recorder.isRecording)
        XCTAssertEqual(recording.duration, 0.6, accuracy: 0.35)
        XCTAssertTrue(FileManager.default.fileExists(atPath: recording.url.path))
    }

    func testARecordingWithoutAnyFrameReturnsNilAndRemovesTheFile() async {
        let recorder = SceneRecorder()
        let source = SyntheticSource(width: 1080, height: 1920)
        source.deliversFrames = false
        recorder.attach(source)
        var finished: [SceneRecording?] = []
        recorder.onRecordingFinished = { finished.append($0) }

        XCTAssertTrue(recorder.startRecording(to: file("empty.mp4")))
        let recording = await recorder.stopRecording()

        XCTAssertNil(recording)
        XCTAssertEqual(recorder.lastError as? SceneRecorderError, .noFrames)
        XCTAssertEqual(finished.count, 1)
        XCTAssertFalse(FileManager.default.fileExists(atPath: file("empty.mp4").path))
    }

    func testDetachingTheSceneFinishesTheRecording() async {
        let recorder = SceneRecorder()
        let source = SyntheticSource(width: 1080, height: 1920)
        recorder.attach(source)
        var finished: [SceneRecording?] = []
        recorder.onRecordingFinished = { finished.append($0) }
        XCTAssertTrue(recorder.startRecording(to: file("detached.mp4")))
        try? await Task.sleep(nanoseconds: 300_000_000)

        recorder.detach(source)

        XCTAssertFalse(recorder.isAttached)
        XCTAssertFalse(source.isStreaming, "the source is released at once")
        await waitUntil { !finished.isEmpty }
        XCTAssertFalse(recorder.isRecording)
        XCTAssertNotNil(finished.first ?? nil)
    }

    // MARK: - Overlay

    func testTheOverlayIsDrawnOnEveryVideoFrame() async throws {
        let recorder = SceneRecorder()
        recorder.attach(SyntheticSource(width: 1080, height: 1920))
        let calls = Counter()
        recorder.overlay = CaptureOverlay { context, size in
            calls.increment(size)
            context.setFillColor(UIColor.red.cgColor)
            context.fill(CGRect(x: 0, y: 0, width: size.width / 2, height: size.height / 2))
        }

        XCTAssertTrue(recorder.startRecording(to: file("overlay.mp4")))
        try await Task.sleep(nanoseconds: 500_000_000)
        let recordingResult = await recorder.stopRecording()
        let recording = try XCTUnwrap(recordingResult)

        XCTAssertTrue(recording.hasOverlay)
        XCTAssertGreaterThan(calls.count, 5)
        XCTAssertEqual(calls.lastSize, CGSize(width: 1080, height: 1920), "drawn at the video size")
    }

    func testTheOverlayIsDrawnOnPhotosInTopLeftCoordinates() async throws {
        let recorder = SceneRecorder()
        recorder.attach(SyntheticSource(width: 400, height: 300))
        let calls = Counter()
        recorder.overlay = CaptureOverlay { context, size in
            calls.increment(size)
            context.setFillColor(UIColor.red.cgColor)
            context.fill(CGRect(x: 0, y: 0, width: 100, height: 100))
        }

        let photoResult = await recorder.takePhoto()
        let photo = try XCTUnwrap(photoResult)

        XCTAssertEqual(calls.count, 1)
        XCTAssertEqual(calls.lastSize, CGSize(width: 400, height: 300))
        let cgImage = try XCTUnwrap(photo.cgImage)
        XCTAssertEqual(cgImage.width, 400)
        XCTAssertEqual(cgImage.height, 300)
        XCTAssertEqual(pixel(cgImage, x: 10, y: 10).red, 255, "top-left is the overlay")
        XCTAssertEqual(pixel(cgImage, x: 390, y: 290).red, 0, "bottom-right is the scene")
    }

    // MARK: - Photo files

    func testSavePhotoWritesJPEGAndPNGAtFullResolution() async throws {
        let recorder = SceneRecorder()
        recorder.attach(SyntheticSource(width: 600, height: 800))

        let jpegResult = await recorder.savePhoto(to: file("photo.jpg"))
        let jpeg = try XCTUnwrap(jpegResult)
        let pngResult = await recorder.savePhoto(to: file("photo.png"), format: .png)
        let png = try XCTUnwrap(pngResult)

        for url in [jpeg, png] {
            let image = try XCTUnwrap(UIImage(contentsOfFile: url.path)?.cgImage, url.lastPathComponent)
            XCTAssertEqual(image.width, 600)
            XCTAssertEqual(image.height, 800)
        }
        let pngData = try Data(contentsOf: png)
        XCTAssertEqual(Array(pngData.prefix(4)), [0x89, 0x50, 0x4E, 0x47])
        let jpegData = try Data(contentsOf: jpeg)
        XCTAssertEqual(Array(jpegData.prefix(2)), [0xFF, 0xD8])
        XCTAssertNil(recorder.lastError)
    }

    func testNewCaptureFilesAreTimestampedUnderCaptures() {
        let url = SceneRecorder.newCaptureFile(extension: "mp4")
        XCTAssertEqual(url.deletingLastPathComponent().lastPathComponent, "captures")
        XCTAssertEqual(url.pathExtension, "mp4")
        XCTAssertNotNil(url.lastPathComponent.range(
            of: #"^scene_\d{8}_\d{6}_\d{3}\.mp4$"#, options: .regularExpression
        ), url.lastPathComponent)
    }

    // MARK: - Views

    func testARSceneViewAttachesAndDetachesItsRecorder() {
        let recorder = SceneRecorder()
        let arView = ARView(frame: CGRect(x: 0, y: 0, width: 100, height: 200))
        let coordinator = ARSceneView.Coordinator(configuration: ARSessionConfiguration())

        coordinator.syncRecorder(recorder, on: arView)
        XCTAssertTrue(recorder.isAttached)
        let source = coordinator.captureSource
        coordinator.syncRecorder(recorder, on: arView)
        XCTAssertTrue(coordinator.captureSource === source, "a later render keeps the same source")

        coordinator.syncRecorder(nil, on: arView)
        XCTAssertFalse(recorder.isAttached)
        XCTAssertNil(coordinator.captureSource)
    }

    @available(iOS 15.0, *)
    func testTheRecordingTapGivesThePostProcessSlotBack() {
        let arView = ARView(frame: CGRect(x: 0, y: 0, width: 100, height: 200))
        let source = ARViewCaptureSource(arView: arView)
        let sink = NullSink()

        source.startStreaming(into: sink)
        XCTAssertNotNil(arView.renderCallbacks.postProcess, "the tap is installed while recording")
        source.stopStreaming()
        XCTAssertNil(arView.renderCallbacks.postProcess, "an empty slot is left empty")

        arView.renderCallbacks.postProcess = { _ in }
        source.startStreaming(into: sink)
        source.stopStreaming()
        XCTAssertNotNil(arView.renderCallbacks.postProcess, "another owner's callback is put back")
    }

    func testSceneViewModifierStoresTheRecorder() {
        let recorder = SceneRecorder()
        let view = SceneView { _ in }.sceneRecorder(recorder)
        XCTAssertTrue(view.sceneRecorder === recorder)
        XCTAssertNil(view.sceneRecorder(nil).sceneRecorder)
    }

    // MARK: - Helpers

    private func pixel(_ image: CGImage, x: Int, y: Int) -> (red: UInt8, green: UInt8, blue: UInt8) {
        var data = [UInt8](repeating: 0, count: 4)
        let context = CGContext(
            data: &data, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        )!
        context.draw(image, in: CGRect(x: -x, y: -(image.height - 1 - y), width: image.width, height: image.height))
        return (data[0], data[1], data[2])
    }
}

/// Thread-safe call counter for overlay closures, which run on the encoder queue.
private final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var calls = 0
    private var size: CGSize = .zero

    func increment(_ size: CGSize) {
        lock.lock()
        calls += 1
        self.size = size
        lock.unlock()
    }

    var count: Int { lock.lock(); defer { lock.unlock() }; return calls }
    var lastSize: CGSize { lock.lock(); defer { lock.unlock() }; return size }
}

private final class NullSink: VideoFrameSink, @unchecked Sendable {
    func reserveFrame(at hostTime: CFTimeInterval) -> CVPixelBuffer? { nil }
    func append(_ buffer: CVPixelBuffer, at hostTime: CFTimeInterval) {}
    func discardFrame() {}
}

/// A scene that "renders" a solid blue frame at 60 Hz.
@MainActor
private final class SyntheticSource: SceneCaptureSource {
    let pixelSize: CGSize
    var deliversFrames = true
    private var loop: Task<Void, Never>?
    var isStreaming: Bool { loop != nil }

    init(width: Int, height: Int) {
        pixelSize = CGSize(width: width, height: height)
    }

    func snapshot() async -> UIImage? {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: pixelSize, format: format).image { context in
            UIColor.blue.setFill()
            context.fill(CGRect(origin: .zero, size: pixelSize))
        }
    }

    func startStreaming(into sink: any VideoFrameSink) {
        guard deliversFrames else { loop = Task {}; return }
        loop = Task.detached {
            while !Task.isCancelled {
                let time = CACurrentMediaTime()
                if let buffer = sink.reserveFrame(at: time) {
                    CVPixelBufferLockBaseAddress(buffer, [])
                    if let base = CVPixelBufferGetBaseAddress(buffer) {
                        var blue: UInt32 = 0xFF00_00FF   // BGRA little-endian: B=FF, A=FF
                        memset_pattern4(base, &blue, CVPixelBufferGetDataSize(buffer))
                    }
                    CVPixelBufferUnlockBaseAddress(buffer, [])
                    sink.append(buffer, at: time)
                }
                try? await Task.sleep(nanoseconds: 16_000_000)
            }
        }
    }

    func stopStreaming() {
        loop?.cancel()
        loop = nil
    }
}
#endif
