#if os(iOS)
import AVFoundation
import CoreVideo
import Foundation
import QuartzCore

/// Where a capture source hands rendered frames to be encoded. Callable from any thread
/// (a render thread, a Metal completion handler).
protocol VideoFrameSink: AnyObject, Sendable {
    /// A pixel buffer to render the frame at `hostTime` into, or `nil` to skip this frame
    /// (too soon for the frame rate, encoder busy, recording ending).
    func reserveFrame(at hostTime: CFTimeInterval) -> CVPixelBuffer?
    /// Encodes a buffer from ``reserveFrame(at:)`` once the frame is in it.
    func append(_ buffer: CVPixelBuffer, at hostTime: CFTimeInterval)
    /// Gives back a buffer from ``reserveFrame(at:)`` that could not be filled.
    func discardFrame()
}

/// Writes frames (and the microphone, when asked for and allowed) to an H.264 MP4.
///
/// Timestamps are host time (`CACurrentMediaTime()`), the clock microphone sample buffers
/// carry too, so both tracks line up. The session starts at the first frame; a recording
/// that never got one is cancelled and its file removed.
final class VideoFileWriter: VideoFrameSink, @unchecked Sendable {
    let url: URL
    let size: VideoSize
    let hasOverlay: Bool
    /// Whether a microphone sample made it into the file.
    var hasAudio: Bool {
        lock.lock()
        defer { lock.unlock() }
        return wroteAudio
    }

    private let overlay: CaptureOverlay?
    private let frameInterval: CFTimeInterval
    private let maxDuration: CFTimeInterval?
    private let writer: AVAssetWriter
    private let videoInput: AVAssetWriterInput
    private let adaptor: AVAssetWriterInputPixelBufferAdaptor
    private var audioInput: AVAssetWriterInput?
    private var microphone: MicrophoneCapture?

    /// Encoding queue: everything below the lock is only touched on it.
    private let queue = DispatchQueue(label: "io.github.sceneview.capture.writer")
    private var sessionStart: CMTime?
    private var lastFrameTime: CMTime?
    private var frameCount = 0

    private let lock = NSLock()
    private var accepting = true
    private var lastReserved: CFTimeInterval?
    private var firstReserved: CFTimeInterval?
    private var inFlight = 0
    private var wroteAudio = false

    /// Frames reserved but not yet appended; more are skipped so a slow encoder never piles
    /// up buffers.
    private static let maxInFlight = 3

    init(url: URL, size: VideoSize, settings: VideoSettings, overlay: CaptureOverlay?) throws {
        self.url = url
        self.size = size
        self.overlay = overlay
        hasOverlay = overlay != nil
        frameInterval = 1.0 / Double(settings.frameRate)
        maxDuration = settings.maxDuration

        let fileManager = FileManager.default
        try fileManager.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        if fileManager.fileExists(atPath: url.path) { try fileManager.removeItem(at: url) }

        writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        writer.shouldOptimizeForNetworkUse = true
        videoInput = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: size.width,
            AVVideoHeightKey: size.height,
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: settings.bitRate,
                AVVideoExpectedSourceFrameRateKey: settings.frameRate,
                AVVideoMaxKeyFrameIntervalKey: settings.frameRate,
                AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel,
            ],
            AVVideoColorPropertiesKey: [
                AVVideoColorPrimariesKey: AVVideoColorPrimaries_ITU_R_709_2,
                AVVideoTransferFunctionKey: AVVideoTransferFunction_ITU_R_709_2,
                AVVideoYCbCrMatrixKey: AVVideoYCbCrMatrix_ITU_R_709_2,
            ],
        ])
        videoInput.expectsMediaDataInRealTime = true
        adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: videoInput, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: size.width,
            kCVPixelBufferHeightKey as String: size.height,
            kCVPixelBufferMetalCompatibilityKey as String: true,
            kCVPixelBufferCGBitmapContextCompatibilityKey as String: true,
            kCVPixelBufferIOSurfacePropertiesKey as String: [String: Any](),
        ])
        guard writer.canAdd(videoInput) else {
            throw SceneRecorderError.writeFailed("The MP4 writer refused a \(size) H.264 track")
        }
        writer.add(videoInput)

        if settings.recordAudio, AVCaptureDevice.authorizationStatus(for: .audio) == .authorized {
            let input = AVAssetWriterInput(mediaType: .audio, outputSettings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVNumberOfChannelsKey: 1,
                AVSampleRateKey: 44_100,
                AVEncoderBitRateKey: 128_000,
            ])
            input.expectsMediaDataInRealTime = true
            // A microphone another app holds (a call) records a silent video instead.
            if writer.canAdd(input), let microphone = MicrophoneCapture(queue: queue, onSample: { [weak self] sample in
                self?.appendAudio(sample)
            }) {
                writer.add(input)
                audioInput = input
                self.microphone = microphone
            }
        }

        guard writer.startWriting() else {
            throw SceneRecorderError.writeFailed(writer.error?.localizedDescription ?? "The MP4 writer did not start")
        }
        microphone?.start()
    }

    // MARK: VideoFrameSink

    func reserveFrame(at hostTime: CFTimeInterval) -> CVPixelBuffer? {
        lock.lock()
        defer { lock.unlock() }
        guard accepting, inFlight < Self.maxInFlight else { return nil }
        // 10 % slack so a 60 Hz scene keeps every other frame instead of drifting.
        if let lastReserved, hostTime - lastReserved < frameInterval * 0.9 { return nil }
        // A frame that would end past `maxDuration` is refused, so the video stops at the
        // limit even when the stop itself comes a little later.
        if let maxDuration, let firstReserved, hostTime - firstReserved + frameInterval > maxDuration + 0.001 {
            return nil
        }
        guard let pool = adaptor.pixelBufferPool else { return nil }
        var buffer: CVPixelBuffer?
        guard CVPixelBufferPoolCreatePixelBuffer(nil, pool, &buffer) == kCVReturnSuccess, let buffer else { return nil }
        lastReserved = hostTime
        if firstReserved == nil { firstReserved = hostTime }
        inFlight += 1
        return buffer
    }

    func append(_ buffer: CVPixelBuffer, at hostTime: CFTimeInterval) {
        queue.async { [self] in
            defer {
                lock.lock()
                inFlight -= 1
                lock.unlock()
            }
            guard writer.status == .writing else { return }
            let time = CMTime(seconds: hostTime, preferredTimescale: 1_000_000_000)
            if let lastFrameTime, time <= lastFrameTime { return }
            if sessionStart == nil {
                writer.startSession(atSourceTime: time)
                sessionStart = time
            }
            if let overlay { draw(overlay, on: buffer) }
            guard videoInput.isReadyForMoreMediaData, adaptor.append(buffer, withPresentationTime: time) else { return }
            frameCount += 1
            lastFrameTime = time
        }
    }

    func discardFrame() {
        lock.lock()
        inFlight -= 1
        lock.unlock()
    }

    private func draw(_ overlay: CaptureOverlay, on buffer: CVPixelBuffer) {
        CVPixelBufferLockBaseAddress(buffer, [])
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
        guard let context = CGContext(
            data: CVPixelBufferGetBaseAddress(buffer),
            width: CVPixelBufferGetWidth(buffer),
            height: CVPixelBufferGetHeight(buffer),
            bitsPerComponent: 8,
            bytesPerRow: CVPixelBufferGetBytesPerRow(buffer),
            space: CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
        ) else { return }
        overlay.drawFlipped(in: context, size: CGSize(width: context.width, height: context.height))
    }

    /// Runs on `queue` (the microphone delivers there).
    private func appendAudio(_ sample: CMSampleBuffer) {
        guard writer.status == .writing, let audioInput, let sessionStart,
              CMSampleBufferGetPresentationTimeStamp(sample) >= sessionStart,
              audioInput.isReadyForMoreMediaData, audioInput.append(sample) else { return }
        lock.lock()
        wroteAudio = true
        lock.unlock()
    }

    // MARK: Finish

    /// Ends the file. Returns its duration in seconds, or `nil` when no frame was written
    /// (the file is then removed).
    func finish() async -> TimeInterval? {
        lock.lock()
        accepting = false
        lock.unlock()
        microphone?.stop()
        // Let frames already on the GPU land, bounded so a stalled renderer cannot hang us.
        for _ in 0..<50 {
            lock.lock()
            let pending = inFlight
            lock.unlock()
            if pending == 0 { break }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        let ending: (start: CMTime, end: CMTime)? = await withCheckedContinuation { continuation in
            queue.async { [self] in
                guard frameCount > 0, let sessionStart, let lastFrameTime, writer.status == .writing else {
                    if writer.status == .writing { writer.cancelWriting() }
                    continuation.resume(returning: nil)
                    return
                }
                let end = lastFrameTime + CMTime(seconds: frameInterval, preferredTimescale: 1_000_000_000)
                videoInput.markAsFinished()
                audioInput?.markAsFinished()
                writer.endSession(atSourceTime: end)
                continuation.resume(returning: (sessionStart, end))
            }
        }
        guard let ending else {
            try? FileManager.default.removeItem(at: url)
            return nil
        }
        await writer.finishWriting()
        guard writer.status == .completed else {
            try? FileManager.default.removeItem(at: url)
            return nil
        }
        return (ending.end - ending.start).seconds
    }

    /// Why the writer failed, if it did.
    var failure: Error? { writer.error }
}

/// The microphone, delivered as sample buffers on the writer's queue.
///
/// The capture session leaves the app's audio session alone (no automatic configuration);
/// ``MicrophoneAudioSession`` makes it recordable for the length of the recording. Start and
/// stop run in order on one serial queue shared by every recording, off the caller's thread
/// since `startRunning` blocks, so one recording's stop and the next one's start never
/// interleave.
private final class MicrophoneCapture: NSObject, AVCaptureAudioDataOutputSampleBufferDelegate, @unchecked Sendable {
    private static let control = DispatchQueue(label: "io.github.sceneview.capture.microphone")

    private let session = AVCaptureSession()
    private let output = AVCaptureAudioDataOutput()
    private let onSample: (CMSampleBuffer) -> Void
    /// Only touched on `control`.
    private let audio = MicrophoneAudioSession(AVAudioSession.sharedInstance())

    init?(queue: DispatchQueue, onSample: @escaping (CMSampleBuffer) -> Void) {
        self.onSample = onSample
        super.init()
        session.automaticallyConfiguresApplicationAudioSession = false
        guard let device = AVCaptureDevice.default(for: .audio),
              let input = try? AVCaptureDeviceInput(device: device) else { return nil }
        session.beginConfiguration()
        guard session.canAddInput(input), session.canAddOutput(output) else {
            session.commitConfiguration()
            return nil
        }
        session.addInput(input)
        session.addOutput(output)
        session.commitConfiguration()
        output.setSampleBufferDelegate(self, queue: queue)
    }

    func start() {
        Self.control.async { [self] in
            // An audio session that cannot record leaves the track silent.
            guard audio.begin() else { return }
            session.startRunning()
        }
    }

    func stop() {
        Self.control.async { [self] in
            session.stopRunning()
            audio.end()
        }
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        onSample(sampleBuffer)
    }
}

/// The calls ``MicrophoneAudioSession`` makes on the app's audio session.
protocol AudioSessionControlling: AnyObject {
    var category: AVAudioSession.Category { get }
    var mode: AVAudioSession.Mode { get }
    var categoryOptions: AVAudioSession.CategoryOptions { get }
    func setCategory(_ category: AVAudioSession.Category, mode: AVAudioSession.Mode,
                     options: AVAudioSession.CategoryOptions) throws
    func setActive(_ active: Bool, options: AVAudioSession.SetActiveOptions) throws
}

extension AVAudioSession: AudioSessionControlling {}

/// Makes the app's audio session recordable for one recording, then hands it back.
///
/// A category that already records (`.playAndRecord`, `.record`) is the app's own setup: it
/// is activated and otherwise left alone. Any other category is switched to
/// `.playAndRecord`, mixing with other audio, and activated; if the switch fails nothing is
/// activated and the recording stays silent. At the end, a switched session is deactivated
/// (other apps' audio is told it may resume) and its category put back, but only while it
/// is still exactly the setup the recorder made: a category the app changed in the
/// meantime (a voice chat, a switch to `.playback`) is the app's and is kept, active.
final class MicrophoneAudioSession {
    typealias Setup = (category: AVAudioSession.Category, mode: AVAudioSession.Mode,
                       options: AVAudioSession.CategoryOptions)

    static let recordingOptions: AVAudioSession.CategoryOptions = [.mixWithOthers, .defaultToSpeaker, .allowBluetoothA2DP]

    private let session: AudioSessionControlling
    /// The app's setup before the switch, and the setup the switch left, while switched.
    private var switched: (previous: Setup, applied: Setup)?
    private var activated = false

    init(_ session: AudioSessionControlling) {
        self.session = session
    }

    /// Readies the session for the microphone. `false` when it cannot record.
    func begin() -> Bool {
        if session.category != .playAndRecord, session.category != .record {
            let previous = current
            do {
                try session.setCategory(.playAndRecord, mode: .default, options: Self.recordingOptions)
            } catch {
                return false
            }
            // As read back: the system may normalise the options it was given.
            switched = (previous, current)
        }
        activated = (try? session.setActive(true, options: [])) != nil
        return true
    }

    /// Hands the session back. Call after the capture session stopped running.
    func end() {
        defer {
            self.switched = nil
            activated = false
        }
        guard let switched, Self.same(current, switched.applied) else { return }
        if activated { try? session.setActive(false, options: .notifyOthersOnDeactivation) }
        try? session.setCategory(switched.previous.category, mode: switched.previous.mode,
                                 options: switched.previous.options)
    }

    private var current: Setup {
        (session.category, session.mode, session.categoryOptions)
    }

    private static func same(_ a: Setup, _ b: Setup) -> Bool {
        a.category == b.category && a.mode == b.mode && a.options == b.options
    }
}
#endif
