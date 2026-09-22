#if os(iOS)
import SwiftUI
import RealityKit
import ARKit
import SceneViewSwift

/// AR session recording demo (v4.3.0+) — mirrors Android `ARRecordPlaybackDemo.kt`.
///
/// Records the screen via ReplayKit (`RPScreenRecorder`) while an AR
/// session is live. iOS is record-only because ARKit, unlike ARCore,
/// does not expose a deterministic playback dataset — the resulting
/// MP4 plays back in the Photos app but cannot be fed back into
/// `ARSession` for replay. See `docs/docs/cheatsheet-ios.md` parity
/// table (#1036) for the rationale.
struct ARRecorderDemo: View {
    @StateObject private var recorder = ARRecorder()
    @State private var statusMessage: String? = nil
    @State private var lastFileSize: Int? = nil
    /// Holds the currently-running record/stop Task so the view can
    /// cancel it on disappear and avoid mutating `statusMessage` on a
    /// disposed view. Closes Agent A MAJOR finding on PR #1042.
    @State private var activeTask: Task<Void, Never>? = nil
    /// Disables the "Save to Photos" button while a save is in flight
    /// — `PHPhotoLibrary.performChanges` can't be cancelled mid-call so
    /// a double-tap would enqueue two concurrent saves of the same URL
    /// (two duplicate assets in Photos). Closes reviewer MAJOR on PR #1048.
    @State private var isSavingToPhotos: Bool = false
    /// The recording that has already been **moved** into the photo library.
    /// `ARRecorder.saveToPhotoLibrary` moves the source file, and
    /// `ARRecorder.lastOutputURL` is `private(set)`, so the demo remembers the
    /// move itself: Share and Save both pointed at a URL with nothing behind
    /// it for the rest of the session otherwise.
    @State private var movedToPhotos: URL? = nil
    /// Serialises start/stop. `recorder.isRecording` only flips once ReplayKit
    /// answers, so a second tap arriving while a start was still pending read
    /// "not recording" and started a second one.
    @State private var isTogglingRecording: Bool = false
    /// The in-flight "Save to Photos" hop, cancelled on dismissal.
    @State private var saveTask: Task<Void, Never>? = nil

    /// Whether `url` is still a file this screen can share or save.
    private func isExportable(_ url: URL) -> Bool {
        url != movedToPhotos && FileManager.default.fileExists(atPath: url.path)
    }

    var body: some View {
        ZStack {
            #if !targetEnvironment(simulator)
            arSceneView
                .ignoresSafeArea()
            #else
            simulatorPlaceholder
            #endif

            VStack {
                Spacer()
                statusBanner
                controlsPanel
                    .padding(.bottom, 30)
                    .padding(.horizontal, 24)
            }
        }
        .background(Color.black)
        .onDisappear(perform: teardown)
    }

    // MARK: - AR view

    #if !targetEnvironment(simulator)
    private var arSceneView: some View {
        ARSceneView(
            planeDetection: .horizontal,
            showPlaneOverlay: true,
            showCoachingOverlay: true,
            onTapOnPlane: { position, arView in
                // Drop a small unlit cube where the user taps so the
                // recording has something tracking.
                let marker = GeometryNode.cube(
                    size: 0.08,
                    material: .pbr(color: .systemTeal, metallic: 0.0, roughness: 0.4),
                    cornerRadius: 0.01
                )
                let anchor = AnchorNode.world(position: position)
                anchor.add(marker.entity)
                arView.scene.addAnchor(anchor.entity)
            }
        )
    }
    #endif

    private var simulatorPlaceholder: some View {
        VStack(spacing: 12) {
            Image(systemName: "arkit")
                .font(.system(size: 60))
                .foregroundStyle(.white.opacity(0.5))
            Text("AR recording is device-only")
                .font(.headline)
                .foregroundStyle(.white)
            Text("RPScreenRecorder needs ARKit hardware and screen-record permission. Run on a real iPhone or iPad to try this demo.")
                .font(.caption)
                .multilineTextAlignment(.center)
                .foregroundStyle(.white.opacity(0.7))
                .padding(.horizontal, 40)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - UI

    @ViewBuilder
    private var statusBanner: some View {
        if let statusMessage {
            Text(statusMessage)
                .font(.caption)
                .foregroundStyle(.white)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(.ultraThinMaterial)
                .clipShape(Capsule())
                .padding(.bottom, 10)
                .transition(.opacity)
        }
    }

    private var controlsPanel: some View {
        VStack(spacing: 10) {
            HStack(spacing: 16) {
                Button(action: toggleRecording) {
                    HStack(spacing: 8) {
                        Image(systemName: recorder.isRecording ? "stop.circle.fill" : "record.circle.fill")
                            .font(.title2)
                        Text(recorder.isRecording ? "Stop" : "Record")
                            .font(.body.weight(.semibold))
                    }
                    .foregroundStyle(.white)
                    .padding(.horizontal, 22)
                    .padding(.vertical, 12)
                    .background(recorder.isRecording ? Color.red : Color.accentColor)
                    .clipShape(Capsule())
                }
                .disabled(isTogglingRecording || (!recorder.isAvailable && !recorder.isRecording))
                .opacity(isTogglingRecording ? 0.6 : 1)
            }

            if let url = recorder.lastOutputURL, isExportable(url) {
                VStack(spacing: 6) {
                    Text(url.lastPathComponent)
                        .font(.caption2.monospaced())
                        .foregroundStyle(.white.opacity(0.7))
                        .lineLimit(1)
                        .truncationMode(.middle)
                    HStack(spacing: 10) {
                        Button(action: { saveToPhotos(url) }) {
                            Label(isSavingToPhotos ? "Saving…" : "Save to Photos",
                                  systemImage: "photo.on.rectangle.angled")
                                .font(.caption.weight(.medium))
                                .foregroundStyle(.white)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(isSavingToPhotos ? Color.accentColor.opacity(0.5) : Color.accentColor)
                                .clipShape(Capsule())
                        }
                        .buttonStyle(.plain)
                        .disabled(isSavingToPhotos)

                        ShareLink(item: url) {
                            Label("Share", systemImage: "square.and.arrow.up")
                                .font(.caption.weight(.medium))
                                .foregroundStyle(.white)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(.white.opacity(0.15))
                                .clipShape(Capsule())
                        }
                    }
                }
            }

            if let url = recorder.lastOutputURL, !isExportable(url) {
                Text(url == movedToPhotos
                     ? "Saved to Photos — the recording now lives in your library."
                     : "The recording file is no longer on disk.")
                    .font(.caption2)
                    .foregroundStyle(.white.opacity(0.7))
                    .multilineTextAlignment(.center)
            }

            Text("iOS records the screen only (no deterministic playback). The MP4 opens in Photos.")
                .font(.caption2)
                .foregroundStyle(.white.opacity(0.6))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 16)
        }
        .padding()
        .background(.ultraThinMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }

    // MARK: - Actions

    private func toggleRecording() {
        // One start/stop hop at a time — the button is disabled while this is
        // true, and the flag is the backstop for a tap that races the disable.
        guard !isTogglingRecording else { return }
        isTogglingRecording = true
        activeTask = Task {
            defer { isTogglingRecording = false }
            if recorder.isRecording {
                do {
                    let url = try await recorder.stopRecording()
                    if Task.isCancelled { return }
                    let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int) ?? 0
                    lastFileSize = size
                    statusMessage = "Recording saved (\(humanFileSize(size)))"
                } catch {
                    if Task.isCancelled { return }
                    statusMessage = "Stop failed: \(error.localizedDescription)"
                }
            } else {
                statusMessage = "Recording…"
                do {
                    try await recorder.startRecording()
                } catch {
                    if Task.isCancelled { return }
                    statusMessage = "Start failed: \(error.localizedDescription)"
                }
            }
        }
    }

    /// Dismissal owns the recording.
    ///
    /// Cancelling `activeTask` only detaches this view from the result — it
    /// cannot stop ReplayKit, which has no cancellation handler behind
    /// `ARRecorder.startRecording()`. Capture therefore ran on over whatever
    /// screen came next. Teardown now waits for a **pending start** to settle
    /// (awaiting the cancelled task, which resumes when ReplayKit calls back)
    /// and then stops the recorder, in an unstructured task that deliberately
    /// outlives the view.
    private func teardown() {
        saveTask?.cancel()
        saveTask = nil
        let pending = activeTask
        activeTask = nil
        let recorder = self.recorder
        Task { @MainActor in
            pending?.cancel()
            _ = await pending?.value
            guard recorder.isRecording else { return }
            _ = try? await recorder.stopRecording()
        }
    }

    private func humanFileSize(_ bytes: Int) -> String {
        ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file)
    }

    /// Wraps `ARRecorder.saveToPhotoLibrary` with a status-banner update
    /// so the user knows whether the save succeeded, was denied, or
    /// failed. Tied to the view's `activeTask` so disappear cancels it.
    /// The `isSavingToPhotos` gate prevents a double-tap from enqueueing
    /// two concurrent saves of the same URL (closes reviewer MAJOR on
    /// PR #1048 — `PHPhotoLibrary.performChanges` is uncancellable
    /// mid-flight, so the button-disable is the only safety net).
    private func saveToPhotos(_ url: URL) {
        // Defensive: extra guard in case @State race lets the action
        // fire while the button is mid-transition to disabled.
        guard !isSavingToPhotos else { return }
        // Its own task: cancelling `activeTask` here used to detach a pending
        // record/stop hop from its result mid-flight.
        saveTask?.cancel()
        isSavingToPhotos = true
        saveTask = Task {
            defer { isSavingToPhotos = false }
            statusMessage = "Saving to Photos…"
            do {
                let localID = try await ARRecorder.saveToPhotoLibrary(url)
                if Task.isCancelled { return }
                // The save MOVED the file. Retire the URL before anything can
                // offer it again.
                movedToPhotos = url
                if let localID {
                    statusMessage = "Saved to Photos (\(localID))"
                } else {
                    statusMessage = "Saved to Photos"
                }
            } catch {
                if Task.isCancelled { return }
                statusMessage = "Save failed: \(error.localizedDescription)"
            }
        }
    }
}
#endif
