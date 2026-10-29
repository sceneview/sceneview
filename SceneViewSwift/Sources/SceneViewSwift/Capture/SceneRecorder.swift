#if os(iOS)
import Combine
import CoreGraphics
import Foundation
import QuartzCore
import UIKit

/// Something whose rendered frames a ``SceneRecorder`` captures: the `ARView` behind a
/// ``SceneView`` or an ``ARSceneView``, or a synthetic source in tests.
@MainActor
protocol SceneCaptureSource: AnyObject {
    /// Size of a rendered frame, in pixels. Zero before the view is laid out.
    var pixelSize: CGSize { get }
    /// The current frame at full resolution, without the overlay.
    func snapshot() async -> UIImage?
    /// Starts handing every rendered frame to `sink`.
    func startStreaming(into sink: any VideoFrameSink)
    /// Stops handing frames over.
    func stopStreaming()
}

/// Photos and MP4 videos of exactly what a ``SceneView`` or an ``ARSceneView`` renders — in AR
/// the camera feed and the virtual content together — never the SwiftUI controls drawn over
/// it, with no ReplayKit consent prompt.
///
/// Create one per screen, attach it to the scene with `.sceneRecorder(_:)`, and call it from
/// your buttons. It is an `ObservableObject`: ``isRecording``, ``elapsedTime``,
/// ``lastRecording`` and ``lastError`` drive the UI.
///
/// ```swift
/// @StateObject private var recorder = SceneRecorder()
///
/// var body: some View {
///     SceneView { root in … }
///         .sceneRecorder(recorder)
///         .overlay(alignment: .bottom) {
///             Button("Take photo") { Task { await recorder.savePhoto() } }
///             Button(recorder.isRecording ? "Stop recording" : "Record video") {
///                 if recorder.isRecording {
///                     Task { await recorder.stopRecording() }
///                 } else {
///                     recorder.startRecording(settings: VideoSettings(maxDuration: 60))
///                 }
///             }
///         }
/// }
/// ```
///
/// A recording also ends, its file finished, when the app goes to the background or the scene
/// leaves the screen; ``onRecordingFinished`` reports those stops too.
///
/// For a recording of the whole screen, SwiftUI controls included, use ``ARRecorder``
/// (ReplayKit) instead.
@MainActor
public final class SceneRecorder: ObservableObject {
    /// Whether a video is being recorded.
    @Published public private(set) var isRecording = false
    /// Seconds since the current recording started, updated four times a second; `0` when
    /// not recording.
    @Published public private(set) var elapsedTime: TimeInterval = 0
    /// The last finished video.
    @Published public private(set) var lastRecording: SceneRecording?
    /// Why the last call failed, `nil` after one that succeeded.
    @Published public private(set) var lastError: Error?

    /// Drawn over every photo and every video frame, never on screen. Read when a capture
    /// starts: changing it mid-recording takes effect on the next recording.
    public var overlay: CaptureOverlay?

    /// Called on the main actor each time a recording ends — by ``stopRecording()``, by
    /// ``VideoSettings/maxDuration``, by the app going to the background or by the scene
    /// leaving the screen — with the video, or `nil` when nothing usable was written (see
    /// ``lastError``).
    public var onRecordingFinished: ((SceneRecording?) -> Void)?

    /// Whether a scene uses this recorder, so photos and videos can be taken.
    @Published public private(set) var isAttached = false

    private var source: (any SceneCaptureSource)?
    private var active: ActiveRecording?
    private var backgroundObserver: NSObjectProtocol?

    private struct ActiveRecording {
        let writer: VideoFileWriter
        let source: any SceneCaptureSource
        let startedAt: CFTimeInterval
        let timers: Task<Void, Never>
    }

    public init() {
        backgroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.finishInBackground(keepAppAlive: true) }
        }
    }

    deinit {
        if let backgroundObserver { NotificationCenter.default.removeObserver(backgroundObserver) }
    }

    // MARK: - Photo

    /// The scene as it is now, at the full resolution of the view, with ``overlay`` drawn on
    /// it. `nil` — and ``lastError`` set — when no scene is attached or it returned no image.
    public func takePhoto() async -> UIImage? {
        guard let source else {
            lastError = SceneRecorderError.notAttached
            return nil
        }
        guard let image = await source.snapshot() else {
            lastError = SceneRecorderError.captureFailed
            return nil
        }
        lastError = nil
        guard let overlay else { return image }
        return Self.draw(overlay, over: image)
    }

    /// Takes a photo (see ``takePhoto()``) and writes it to `url`.
    ///
    /// - Parameters:
    ///   - url: Where to write; a new file under `captures/` by default
    ///     (``newCaptureFile(extension:)``). An existing file is replaced.
    ///   - format: JPEG (default) or PNG.
    ///   - quality: JPEG quality, `0...1`; ignored for PNG.
    /// - Returns: The file, or `nil` with ``lastError`` set.
    @discardableResult
    public func savePhoto(to url: URL? = nil, format: PhotoFormat = .jpeg, quality: Double = 0.95) async -> URL? {
        guard let image = await takePhoto() else { return nil }
        let data: Data? = switch format {
        case .jpeg: SceneSnapshot.jpegData(image, quality: CGFloat(min(max(quality, 0), 1)))
        case .png: SceneSnapshot.pngData(image)
        }
        guard let data else {
            lastError = SceneRecorderError.encodingFailed(format)
            return nil
        }
        let file = url ?? Self.newCaptureFile(extension: format.fileExtension)
        do {
            try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try data.write(to: file, options: .atomic)
            lastError = nil
            return file
        } catch {
            lastError = SceneRecorderError.writeFailed(error.localizedDescription)
            return nil
        }
    }

    // MARK: - Video

    /// Starts recording the scene to an MP4.
    ///
    /// Returns `false` — and sets ``lastError`` — when no scene is attached, the settings are
    /// invalid or the file cannot be created. Returns `false` too while a recording is
    /// already running; that one keeps going and ``lastError`` is left alone.
    ///
    /// - Parameters:
    ///   - url: The MP4 to write; a new file under `captures/` by default. An existing file
    ///     is replaced.
    ///   - settings: Size, frame rate, bit rate, microphone and time limit.
    @discardableResult
    public func startRecording(to url: URL? = nil, settings: VideoSettings = VideoSettings()) -> Bool {
        guard active == nil else { return false }
        do {
            try settings.validate()
        } catch {
            lastError = error
            return false
        }
        guard let source else {
            lastError = SceneRecorderError.notAttached
            return false
        }
        let pixels = source.pixelSize
        let width = Int(pixels.width.rounded()), height = Int(pixels.height.rounded())
        guard let size = chooseVideoSize(
            sourceWidth: width,
            sourceHeight: height,
            maxLongEdge: settings.maxLongEdge,
            maxShortEdge: settings.maxShortEdge,
            isSupported: isH264SizeSupported
        ) else {
            lastError = SceneRecorderError.noVideoSize(width: width, height: height)
            return false
        }
        let writer: VideoFileWriter
        do {
            writer = try VideoFileWriter(
                url: url ?? Self.newCaptureFile(extension: "mp4"),
                size: size,
                settings: settings,
                overlay: overlay
            )
        } catch {
            lastError = (error as? SceneRecorderError) ?? SceneRecorderError.writeFailed(error.localizedDescription)
            return false
        }
        let startedAt = CACurrentMediaTime()
        let maxDuration = settings.maxDuration
        let timers = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 250_000_000)
                guard !Task.isCancelled, let self else { return }
                let elapsed = CACurrentMediaTime() - startedAt
                self.elapsedTime = elapsed
                if let maxDuration, elapsed >= maxDuration {
                    await self.finish(reachedMaxDuration: true)
                    return
                }
            }
        }
        active = ActiveRecording(writer: writer, source: source, startedAt: startedAt, timers: timers)
        source.startStreaming(into: writer)
        isRecording = true
        elapsedTime = 0
        lastError = nil
        return true
    }

    /// Stops the recording and finishes the file.
    ///
    /// - Returns: The video, or `nil` when nothing was recording or nothing usable was
    ///   written (``lastError`` then says why).
    @discardableResult
    public func stopRecording() async -> SceneRecording? {
        await finish(reachedMaxDuration: false)
    }

    private func finish(reachedMaxDuration: Bool) async -> SceneRecording? {
        guard let recording = active else { return nil }
        active = nil
        recording.timers.cancel()
        recording.source.stopStreaming()
        isRecording = false
        // Reset before the wait, so observers never see the previous recording's state
        // next to `isRecording == false`.
        elapsedTime = 0
        lastError = nil
        lastRecording = nil
        let duration = await recording.writer.finish()
        let result: SceneRecording?
        if let duration {
            result = SceneRecording(
                url: recording.writer.url,
                width: recording.writer.size.width,
                height: recording.writer.size.height,
                duration: duration,
                hasAudio: recording.writer.hasAudio,
                reachedMaxDuration: reachedMaxDuration,
                hasOverlay: recording.writer.hasOverlay
            )
            lastRecording = result
        } else {
            result = nil
            lastError = recording.writer.failure.map { SceneRecorderError.writeFailed($0.localizedDescription) }
                ?? SceneRecorderError.noFrames
        }
        onRecordingFinished?(result)
        return result
    }

    /// An automatic stop (app in the background, scene gone): the frames stop at once, the
    /// file is finished in a task.
    private func finishInBackground(keepAppAlive: Bool) {
        guard let active else { return }
        // Give the render callback back now, before the view that owns it is torn down.
        active.source.stopStreaming()
        guard keepAppAlive else {
            Task { await finish(reachedMaxDuration: false) }
            return
        }
        // Keep the app alive long enough to close the MP4 once it is in the background.
        let task = BackgroundTask()
        task.begin()
        Task {
            await finish(reachedMaxDuration: false)
            task.end()
        }
    }

    // MARK: - Files

    /// A new, not yet existing file named `scene_<date>_<time>_<millis>.<extension>` in the
    /// app's `Documents/captures/` folder.
    nonisolated public static func newCaptureFile(extension fileExtension: String) -> URL {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd_HHmmss_SSS"
        let root = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return root
            .appendingPathComponent("captures", isDirectory: true)
            .appendingPathComponent("scene_\(formatter.string(from: Date())).\(fileExtension)")
    }

    // MARK: - Attachment

    /// Uses `source` for the next captures. A recording on another source is finished.
    func attach(_ source: any SceneCaptureSource) {
        guard self.source !== source else { return }
        if let active, active.source !== source { finishInBackground(keepAppAlive: false) }
        self.source = source
        isAttached = true
    }

    /// Whether captures use `source` now. A scene the recorder left for another one checks
    /// it to take the recorder back.
    func isUsing(_ source: any SceneCaptureSource) -> Bool {
        self.source === source
    }

    /// Forgets `source` if it is the attached one; a recording on it is finished.
    func detach(_ source: any SceneCaptureSource) {
        if let active, active.source === source { finishInBackground(keepAppAlive: false) }
        if self.source === source {
            self.source = nil
            isAttached = false
        }
    }

    /// A background task ended exactly once: by the finished file or by its expiration.
    @MainActor
    private final class BackgroundTask {
        private var id = UIBackgroundTaskIdentifier.invalid

        func begin() {
            id = UIApplication.shared.beginBackgroundTask(withName: "SceneRecorder.finish") { [self] in
                MainActor.assumeIsolated { end() }
            }
        }

        func end() {
            guard id != .invalid else { return }
            UIApplication.shared.endBackgroundTask(id)
            id = .invalid
        }
    }

    // MARK: - Overlay

    /// `image` with `overlay` drawn over it, at the image's pixel size.
    static func draw(_ overlay: CaptureOverlay, over image: UIImage) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        let size = CGSize(width: image.size.width * image.scale, height: image.size.height * image.scale)
        return UIGraphicsImageRenderer(size: size, format: format).image { context in
            image.draw(in: CGRect(origin: .zero, size: size))
            overlay.draw(in: context.cgContext, size: size)
        }
    }
}
#endif
