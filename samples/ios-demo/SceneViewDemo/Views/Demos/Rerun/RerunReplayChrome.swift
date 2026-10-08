#if os(iOS)
import SwiftUI
import UIKit

// The replay's chrome, the iOS twin of Android's `RerunReplayUi.kt` since #4379: the scene keeps
// the screen. One glass timeline bar floats over it (above the dock in portrait, beside the title
// in landscape), the camera view is one dock cell away, and everything read once — the layers
// and their figures, the room's size — is a row of the settings sheet. A tap on the stage puts
// all of it away.

private typealias Space = SceneViewTokens.Space
private typealias Glass = SceneViewTokens.Glass

/// Tokens of the replay chrome that are not already in `SceneViewTokens`.
enum RerunChromeMetrics {
    /// The part of the filmstrip still to come, and the camera view's blurred backdrop.
    static let dimAlpha: Double = 0.55
    /// A layer's colour dot in the settings sheet.
    static let dot: CGFloat = Space.sm + Space.xs / 2
    /// The filmstrip's frames are portrait camera frames.
    static let frameAspect: CGFloat = 3.0 / 4.0
    /// The timeline is one touch target tall; its strip keeps `space-xs` of glass above and below.
    static let barHeight: CGFloat = SceneViewTokens.Layout.touchTarget
    static let stripHeight: CGFloat = barHeight - Space.sm
    static let playheadWidth: CGFloat = Space.xs - Space.xs / 4
    /// `ArOverlay.maxWidth`.
    static let maxWidth: CGFloat = 480
    /// The figures of the settings sheet refresh four times a second.
    static let statsInterval: Double = 0.25
}

// MARK: - Camera view

/// The recorded frame at the playhead, whole, over a blurred copy of itself.
///
/// No 3D stage renders while it is up, so it runs the session's clock itself.
struct RerunCameraView: View {
    let session: RerunReplaySession
    var chromeHidden = false
    /// A tap anywhere on the frame: the host hides or shows its chrome.
    var onTap: () -> Void = {}
    /// The last full-size frame; kept until the next one has decoded so the view never blinks.
    @State private var full: CGImage?
    /// What the settings sheet hides of the view's bottom, 0 while it is closed.
    @Environment(\.demoControlsCover) private var sheetCover

    var body: some View {
        let path = session.currentImagePath
        let thumbnail = session.thumbnail(path)
        ZStack {
            SceneViewTokens.Stage.background
            if let thumbnail {
                Color.clear.overlay {
                    Image(decorative: thumbnail, scale: 1)
                        .resizable()
                        .scaledToFill()
                        .blur(radius: Space.lg + Space.sm)
                }
                .clipped()
                .opacity(RerunChromeMetrics.dimAlpha)
            }
            if let image = full ?? thumbnail {
                Image(decorative: image, scale: 1)
                    .resizable()
                    .scaledToFit()
            }
        }
        .ignoresSafeArea()
        .contentShape(Rectangle())
        .onTapGesture { if sheetCover == 0 { onTap() } }
        .task(id: path) {
            guard let path, let image = await session.fullFrame(path), !Task.isCancelled else { return }
            full = image
        }
        .task { await runClock() }
        .accessibilityElement()
        .accessibilityLabel("The camera frame recorded at this moment")
        .accessibilityHint(chromeHidden ? "Double-tap to show the controls." : "Double-tap to hide the controls.")
        .accessibilityAddTraits(.isImage)
        .accessibilityAction(.default, onTap)
    }

    /// The playhead and the sheet's figures, kept moving the way the 3D stage's render loop does.
    private func runClock() async {
        var last = CACurrentMediaTime()
        var statsAt = last
        while !Task.isCancelled {
            try? await Task.sleep(for: .milliseconds(16))
            let now = CACurrentMediaTime()
            session.tick(Float(now - last))
            last = now
            if now - statsAt >= RerunChromeMetrics.statsInterval {
                statsAt = now
                session.count(session.pack.trace.frameAt(session.time))
            }
        }
    }
}

// MARK: - Timeline

/// The replay's one bar: play / pause, a strip of the session's frames that scrubs under the
/// finger, and the clock — one touch target tall, on glass.
struct RerunTimelineBar: View {
    let session: RerunReplaySession

    @State private var scrubbing = false
    @State private var resumeAfterScrub = false

    private var shape: RoundedRectangle {
        RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg, style: .continuous)
    }

    var body: some View {
        HStack(spacing: 0) {
            Button { session.togglePlay() } label: {
                Image(systemName: session.playing ? "pause.fill" : "play.fill")
                    .font(SceneViewTokens.TypeScale.card)
                    .foregroundStyle(Glass.onGlass)
                    .contentTransition(.symbolEffect(.replace))
                    .frame(width: RerunChromeMetrics.barHeight, height: RerunChromeMetrics.barHeight)
                    .contentShape(Rectangle())
            }
            .buttonStyle(PressScaleButtonStyle(scale: SceneViewTokens.Spring.chromePressScale))
            .accessibilityLabel(session.playing ? "Pause" : "Play")
            .accessibilityIdentifier("rerun-play")
            strip
            Text("\(RerunFormat.clock(session.time)) / \(RerunFormat.clock(session.duration))")
                .font(SceneViewTokens.TypeScale.caption)
                .monospacedDigit()
                .foregroundStyle(Glass.onGlassMuted)
                .lineLimit(1)
                .fixedSize()
                .padding(.leading, Space.sm)
                .accessibilityHidden(true)
        }
        .padding(.trailing, Space.md)
        .frame(height: RerunChromeMetrics.barHeight)
        .frame(maxWidth: RerunChromeMetrics.maxWidth)
        .glassBackground(in: shape, id: "rerun-timeline")
        // The glass around the strip is the bar's, not the stage's: a tap there hides nothing.
        .contentShape(shape)
        .onTapGesture {}
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("rerun-timeline")
    }

    private var strip: some View {
        GeometryReader { proxy in
            let width = max(proxy.size.width, 1)
            let height = proxy.size.height
            let slots = max(1, Int(width / (height * RerunChromeMetrics.frameAspect)))
            let frames = rerunFilmstripFrames(count: session.pack.trace.imageCount, slots: slots)
            let progress = session.duration > 0 ? CGFloat(session.time / session.duration) : 0
            let slotWidth = width / CGFloat(max(frames.count, 1))
            ZStack(alignment: .leading) {
                SceneViewTokens.ARChrome.meterTrack
                HStack(spacing: 0) {
                    ForEach(Array(frames.enumerated()), id: \.offset) { _, index in
                        Color.clear
                            .frame(width: slotWidth, height: height)
                            .overlay {
                                if let image = session.thumbnail(session.pack.trace.imagePaths[index]) {
                                    Image(decorative: image, scale: 1).resizable().scaledToFill()
                                }
                            }
                            .clipped()
                    }
                }
                SceneViewTokens.Stage.background.opacity(RerunChromeMetrics.dimAlpha)
                    .frame(width: width * (1 - min(max(progress, 0), 1)))
                    .frame(maxWidth: .infinity, alignment: .trailing)
                Glass.onGlass
                    .frame(width: RerunChromeMetrics.playheadWidth, height: height)
                    .offset(x: min(max(progress * width - RerunChromeMetrics.playheadWidth / 2, 0),
                                   width - RerunChromeMetrics.playheadWidth))
            }
            .clipShape(RoundedRectangle(cornerRadius: SceneViewTokens.Radius.xs, style: .continuous))
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        if !scrubbing {
                            scrubbing = true
                            resumeAfterScrub = session.playing
                        }
                        session.scrub(to: Float(min(max(value.location.x / width, 0), 1)) * session.duration)
                    }
                    .onEnded { _ in
                        scrubbing = false
                        if resumeAfterScrub { session.resume() }
                    }
            )
        }
        .frame(height: RerunChromeMetrics.stripHeight)
        .accessibilityElement()
        .accessibilityLabel("Session timeline")
        .accessibilityValue("\(RerunFormat.clock(session.time)) of \(RerunFormat.clock(session.duration))")
        .accessibilityAdjustableAction { direction in
            let step = max(session.duration / 10, 1)
            switch direction {
            case .increment: session.scrub(to: session.time + step)
            case .decrement: session.scrub(to: session.time - step)
            @unknown default: break
            }
        }
        .accessibilityIdentifier("rerun-filmstrip")
    }
}

// MARK: - Settings rows

/// What the replay's settings sheet reads out, once: the four layers with their figures at the
/// playhead — each one a switch for its layer — and the room's size.
struct RerunReplaySettings: View {
    let session: RerunReplaySession

    private typealias Palette = SceneViewTokens.HomeColor
    private typealias Debug = SceneViewTokens.DebugView

    var body: some View {
        let stats = session.stats
        VStack(alignment: .leading, spacing: 0) {
            Text("Layers")
                .font(SceneViewTokens.TypeScale.captionSemibold)
                .foregroundStyle(.secondary)
                .accessibilityAddTraits(.isHeader)
            layer("Path", RerunFormat.distance(stats.pathMetres), Debug.trailNew, .trail)
            layer("Planes", "\(stats.planes)", Debug.floorOutline, .planes)
            layer("Points", RerunFormat.count(stats.mapPoints), Debug.mapPoint, .points)
            layer("Anchors", "\(stats.anchors)", Debug.anchor, .anchors)
            if let room = session.room {
                RerunSheetFigure(label: "Room", value: room.summary) {
                    Image(systemName: "ruler").foregroundStyle(.secondary)
                }
                .accessibilityIdentifier("rerun-room-size")
            }
        }
    }

    private func layer(_ label: String, _ value: String, _ color: UInt32, _ group: RerunGroup) -> some View {
        Toggle(isOn: Binding(get: { session.isVisible(group) },
                             set: { shown in if shown != session.isVisible(group) { session.toggle(group) } })) {
            HStack(spacing: Space.md) {
                RerunLayerDot(color: color)
                Text(label)
                    .font(.body)
                    .foregroundStyle(Palette.onSurface)
                Spacer(minLength: Space.sm)
                Text(value)
                    .font(.body)
                    .monospacedDigit()
                    .contentTransition(.numericText())
                    .foregroundStyle(.secondary)
            }
        }
        .tint(Palette.primary)
        .frame(minHeight: SceneViewTokens.Layout.touchTarget)
        .accessibilityLabel("\(label), \(value)")
        .accessibilityHint("Shows or hides this layer")
        .accessibilityIdentifier("rerun-layer-\(label.lowercased())")
    }
}

/// The colour a layer is drawn in, in the sheet rows' icon column.
struct RerunLayerDot: View {
    let color: UInt32

    var body: some View {
        // The ring keeps a pale layer (the points are near white) readable on a light sheet.
        Circle()
            .fill(SceneViewTokens.DebugView.color(color))
            .overlay(Circle().strokeBorder(SceneViewTokens.HomeColor.controlOutline,
                                           lineWidth: SceneViewTokens.Glass.borderWidth))
            .frame(width: RerunChromeMetrics.dot, height: RerunChromeMetrics.dot)
            .frame(width: Space.lg)
            .accessibilityHidden(true)
    }
}

/// One figure of the settings sheet, read once: icon column, label, value.
struct RerunSheetFigure<Icon: View>: View {
    let label: String
    let value: String
    @ViewBuilder let icon: () -> Icon

    var body: some View {
        HStack(spacing: Space.md) {
            icon()
                .frame(width: Space.lg)
                .accessibilityHidden(true)
            Text(label)
                .font(.body)
                .foregroundStyle(SceneViewTokens.HomeColor.onSurface)
            Spacer(minLength: Space.sm)
            Text(value)
                .font(.body)
                .monospacedDigit()
                .contentTransition(.numericText())
                .foregroundStyle(.secondary)
        }
        .frame(minHeight: SceneViewTokens.Layout.touchTarget)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Loading

struct RerunReplayLoading: View {
    var body: some View {
        ZStack {
            SceneViewTokens.Stage.background
            VStack(spacing: Space.md) {
                ProgressView().tint(SceneViewTokens.ARChrome.onScrim)
                Text("Loading the recorded session…")
                    .font(SceneViewTokens.TypeScale.body)
                    .foregroundStyle(SceneViewTokens.ARChrome.onScrimDim)
            }
        }
        .ignoresSafeArea()
    }
}
#endif
