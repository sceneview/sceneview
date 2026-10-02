import SwiftUI
import SceneViewSwift

/// The shared **Record** action of every demo (samples audit, step 0): the
/// settings sheet starts a screen recording, a stop button stays in the
/// identity row while it runs, and the sheet then offers the clip.
///
/// This is the former `ar-record-playback` card's content (`ARRecorderDemo`),
/// lifted out of one AR screen into the scaffold. iOS records screen video
/// through ReplayKit (`ARRecorder`); unlike ARCore, ARKit has no session
/// dataset to replay, so there is no "Session MP4" mode on iOS — `ar-rerun`
/// is where a recorded session is replayed.
///
/// One instance for the app: a recording outlives a mode switch inside an
/// umbrella card (the child scaffold is replaced), and stops when the last
/// demo screen goes away (``DemoScaffold`` calls ``screenDisappeared()``).
@MainActor
final class DemoScreenRecording: ObservableObject {
    static let shared = DemoScreenRecording()

    @Published private(set) var isRecording = false
    @Published private(set) var isBusy = false
    @Published private(set) var isSaving = false
    @Published private(set) var statusMessage: String?
    /// The last finished clip, while it is still on disk here. Saving to
    /// Photos *moves* the file, after which it is no longer shareable.
    @Published private(set) var lastClip: URL?

    #if os(iOS)
    private let recorder = ARRecorder()
    #endif
    private var visibleScreens = 0

    var isAvailable: Bool {
        #if os(iOS)
        recorder.isAvailable || isRecording
        #else
        false
        #endif
    }

    func toggle() {
        #if os(iOS)
        guard !isBusy else { return }
        isBusy = true
        Task {
            defer { isBusy = false }
            if recorder.isRecording {
                await stop()
            } else {
                statusMessage = nil
                do {
                    try await recorder.startRecording()
                    isRecording = recorder.isRecording
                } catch {
                    statusMessage = "Couldn\u{2019}t start recording: \(error.localizedDescription)"
                }
            }
        }
        #endif
    }

    func saveToPhotos() {
        #if os(iOS)
        guard let url = lastClip, !isSaving else { return }
        isSaving = true
        Task {
            defer { isSaving = false }
            do {
                _ = try await ARRecorder.saveToPhotoLibrary(url)
                lastClip = nil
                statusMessage = "Saved to Photos."
            } catch {
                statusMessage = "Couldn\u{2019}t save: \(error.localizedDescription)"
            }
        }
        #endif
    }

    func screenAppeared() { visibleScreens += 1 }

    /// A recording belongs to the demos: leaving the last one stops it, after a
    /// beat so a mode switch (old screen out, new one in) does not.
    func screenDisappeared() {
        visibleScreens = max(0, visibleScreens - 1)
        Task {
            try? await Task.sleep(for: .milliseconds(500))
            guard visibleScreens == 0, isRecording, !isBusy else { return }
            isBusy = true
            defer { isBusy = false }
            await stop()
        }
    }

    private func stop() async {
        #if os(iOS)
        do {
            let url = try await recorder.stopRecording()
            let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int) ?? 0
            lastClip = url
            statusMessage = "Recording saved (\(ByteCountFormatter.string(fromByteCount: Int64(size), countStyle: .file)))."
        } catch {
            statusMessage = "Couldn\u{2019}t stop recording: \(error.localizedDescription)"
        }
        isRecording = recorder.isRecording
        #endif
    }
}

/// The Record rows of the settings sheet: start or stop, then share or save
/// the last clip.
struct DemoRecordRows: View {
    @ObservedObject private var recording = DemoScreenRecording.shared
    @Environment(\.dismiss) private var dismiss
    @Environment(\.analyticsSampleId) private var analyticsSampleId

    private typealias Palette = SceneViewTokens.HomeColor

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            DemoSheetRow(icon: recording.isRecording ? "stop.circle.fill" : "record.circle",
                         title: recording.isRecording ? "Stop recording" : "Record screen") {
                if let analyticsSampleId {
                    DemoAnalytics.shared.interaction(analyticsSampleId, recording.isRecording ? "record_stop" : "record")
                }
                let starting = !recording.isRecording
                recording.toggle()
                // Out of the way of the scene being recorded.
                if starting { dismiss() }
            }
            .disabled(recording.isBusy || !recording.isAvailable)
            .accessibilityIdentifier("demo-record")

            if let clip = recording.lastClip {
                ShareLink(item: clip) {
                    rowLabel("Share recording", icon: "square.and.arrow.up")
                }
                .buttonStyle(.plain)
                DemoSheetRow(icon: "photo.on.rectangle.angled",
                             title: recording.isSaving ? "Saving\u{2026}" : "Save to Photos") {
                    recording.saveToPhotos()
                }
                .disabled(recording.isSaving)
            }
            if let message = recording.statusMessage {
                Text(message)
                    .font(SceneViewTokens.TypeScale.caption)
                    .foregroundStyle(Palette.onSurfaceDim)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.bottom, SceneViewTokens.Space.sm)
            }
        }
    }

    private func rowLabel(_ title: String, icon: String) -> some View {
        Label(title, systemImage: icon)
            .labelStyle(DemoSheetRowLabelStyle())
            .font(.body)
            .foregroundStyle(Palette.onSurface)
            .frame(maxWidth: .infinity, minHeight: SceneViewTokens.Layout.touchTarget, alignment: .leading)
            .contentShape(Rectangle())
    }
}

/// The stop control the identity row carries while a recording runs: the
/// sheet that started it is closed, and the scene is what is being filmed.
struct DemoRecordingStopButton: View {
    @ObservedObject private var recording = DemoScreenRecording.shared

    var body: some View {
        if recording.isRecording {
            Button {
                recording.toggle()
            } label: {
                GlassPill {
                    // `danger` is a glyph colour (DESIGN.md Status): the dot, never the text.
                    Circle().fill(SceneViewTokens.HomeColor.danger)
                        .frame(width: 8, height: 8)
                        .accessibilityHidden(true)
                    Text("Stop")
                        .font(SceneViewTokens.TypeScale.chromeCaption.weight(.semibold))
                        .foregroundStyle(SceneViewTokens.Glass.onGlass)
                }
                .frame(minHeight: SceneViewTokens.Layout.touchTarget)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(recording.isBusy)
            .accessibilityLabel("Stop recording")
            .accessibilityIdentifier("demo-record-stop")
        }
    }
}
