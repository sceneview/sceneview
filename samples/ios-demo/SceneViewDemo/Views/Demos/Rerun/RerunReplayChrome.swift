#if os(iOS)
import SwiftUI
import UIKit

// The replay's chrome, the iOS twin of Android's `RerunReplayUi.kt`: the HUD card, the corner
// card that swaps between the camera and the 3D view, the full camera view and the filmstrip.
// Opaque themed cards keep labels legible over both the stage and recorded photographs.

private typealias Space = SceneViewTokens.Space
private typealias ARChrome = SceneViewTokens.ARChrome

/// Tokens of the replay chrome that are not already in `SceneViewTokens`.
enum RerunChromeMetrics {
    /// Opaque DESIGN.md container, shared with the scaffold.
    static let scrim = SceneViewTokens.RoomScan.card
    static let border = SceneViewTokens.RoomScan.border
    /// The same scrim at 55 %: the part of the filmstrip still to come, and the camera
    /// view's blurred backdrop.
    static let dimAlpha: Double = 0.55
    /// The HUD's colour dots.
    static let dot: CGFloat = Space.xs + Space.xs / 2
    /// The filmstrip's frames are portrait camera frames.
    static let frameAspect: CGFloat = 3.0 / 4.0
    static let stripHeight: CGFloat = SceneViewTokens.Layout.touchTarget + Space.sm
    static let playheadWidth: CGFloat = Space.xs - Space.xs / 4
    /// `ArOverlay.maxWidth`.
    static let maxWidth: CGFloat = 480
}

private extension View {
    /// The AR Overlay Card ground: scrim, `radius-lg`, hairline.
    func rerunCard(radius: CGFloat = SceneViewTokens.Radius.lg) -> some View {
        background(RoundedRectangle(cornerRadius: radius, style: .continuous).fill(RerunChromeMetrics.scrim))
            .overlay(
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .strokeBorder(RerunChromeMetrics.border, lineWidth: ARChrome.borderWidth)
            )
    }
}

// MARK: - HUD

/// Tracking state, clock and fps, then the four figures — each one a toggle for its group.
struct RerunReplayHud: View {
    let session: RerunReplaySession

    var body: some View {
        let stats = session.stats
        VStack(alignment: .leading, spacing: Space.sm) {
            HStack(spacing: Space.sm) {
                Circle()
                    .fill(stats.tracking ? SceneViewTokens.RoomScan.point : SceneViewTokens.RoomScan.secondaryText)
                    .frame(width: RerunChromeMetrics.dot, height: RerunChromeMetrics.dot)
                    .accessibilityHidden(true)
                Text(stats.tracking ? "Tracking" : "Initializing")
                    .font(SceneViewTokens.TypeScale.captionSemibold)
                    .foregroundStyle(SceneViewTokens.RoomScan.text)
                Spacer(minLength: Space.sm)
                Text("\(RerunFormat.clock(stats.time)) · \(session.fps) fps")
                    .font(SceneViewTokens.TypeScale.caption)
                    .monospacedDigit()
                    .foregroundStyle(SceneViewTokens.RoomScan.secondaryText)
            }
            .accessibilityElement(children: .combine)
            HStack(alignment: .top, spacing: Space.sm) {
                figure("Path", RerunFormat.distance(stats.pathMetres), SceneViewTokens.HomeColor.primary, .trail)
                figure("Planes", "\(stats.planes)", SceneViewTokens.HomeColor.primary, .planes)
                figure("Points", RerunFormat.compactCount(stats.mapPoints), SceneViewTokens.RoomScan.point, .points)
                figure("Anchors", "\(stats.anchors)", SceneViewTokens.HomeColor.primary, .anchors)
            }
        }
        .padding(.horizontal, Space.md)
        .padding(.vertical, Space.sm + Space.xs)
        .rerunCard()
        .accessibilityIdentifier("rerun-hud")
    }

    private func figure(_ label: String, _ value: String, _ color: Color, _ group: RerunGroup) -> some View {
        let on = session.isVisible(group)
        return Button {
            withAnimation(SceneViewTokens.Motion.expressive(SceneViewTokens.Motion.short)) { session.toggle(group) }
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(value)
                    .font(SceneViewTokens.TypeScale.card)
                    .monospacedDigit()
                    .foregroundStyle(SceneViewTokens.RoomScan.text)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                HStack(spacing: Space.xs) {
                    Circle()
                        .fill(on ? color : SceneViewTokens.RoomScan.secondaryText)
                        .frame(width: RerunChromeMetrics.dot, height: RerunChromeMetrics.dot)
                    Text(label)
                        .font(SceneViewTokens.TypeScale.caption)
                        .foregroundStyle(SceneViewTokens.RoomScan.secondaryText)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(label) \(value)")
        .accessibilityValue(on ? "Shown" : "Hidden")
        .accessibilityHint("Shows or hides this layer")
    }
}

// MARK: - Corner cards

/// A caption pill at a card's bottom-leading corner.
private struct RerunCardLabel: View {
    let text: String
    var icon: String?

    var body: some View {
        HStack(spacing: Space.xs) {
            Text(text)
            if let icon {
                Image(systemName: icon).imageScale(.small)
            }
        }
        .font(SceneViewTokens.TypeScale.captionSemibold)
        .foregroundStyle(SceneViewTokens.RoomScan.text)
        .padding(.horizontal, Space.sm)
        .padding(.vertical, Space.xs / 2)
        .background(Capsule().fill(RerunChromeMetrics.scrim))
        .padding(Space.sm)
        .accessibilityHidden(true)
    }
}

/// The camera frame at the playhead, in the corner of the 3D view. Tapping opens the camera view.
struct RerunCameraCard: View {
    let session: RerunReplaySession
    let onOpen: () -> Void

    var body: some View {
        let size = SceneViewTokens.DebugView.pipSize
        Button(action: onOpen) {
            ZStack(alignment: .bottomLeading) {
                SceneViewTokens.RoomScan.background
                if let image = session.thumbnail(session.currentImagePath) {
                    Color.clear.overlay {
                        Image(decorative: image, scale: 1)
                            .resizable()
                            .scaledToFill()
                    }
                    .clipped()
                }
                RerunCardLabel(text: "Camera")
            }
            .frame(width: size.width, height: size.height)
            .clipShape(RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg, style: .continuous)
                    .strokeBorder(RerunChromeMetrics.border, lineWidth: ARChrome.borderWidth)
            )
        }
        .buttonStyle(PressScaleButtonStyle())
        .accessibilityLabel("Open the camera view")
        .accessibilityIdentifier("rerun-camera-card")
    }
}

/// The camera view's corner: the 3D room, small and untouchable. Tapping goes back to it.
struct RerunPipCard: View {
    let session: RerunReplaySession
    var drift = true
    let onExpand: () -> Void

    var body: some View {
        let size = SceneViewTokens.DebugView.pipSize
        ZStack(alignment: .bottomLeading) {
            RerunReplayStage(session: session, compact: true, drift: drift)
            RerunCardLabel(text: "3D", icon: "arrow.up.left.and.arrow.down.right")
        }
        .frame(width: size.width, height: size.height)
        .clipShape(RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg, style: .continuous)
                .strokeBorder(RerunChromeMetrics.border, lineWidth: ARChrome.borderWidth)
        )
        .contentShape(RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg, style: .continuous))
        .onTapGesture(perform: onExpand)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Open the 3D view")
        .accessibilityAddTraits(.isButton)
        .accessibilityAction(.default, onExpand)
        .accessibilityIdentifier("rerun-pip")
    }
}

// MARK: - Camera view

/// The recorded frame at the playhead, whole, over a blurred copy of itself.
struct RerunCameraView: View {
    let session: RerunReplaySession
    /// The last full-size frame; kept until the next one has decoded so the view never blinks.
    @State private var full: CGImage?

    var body: some View {
        let path = session.currentImagePath
        let thumbnail = session.thumbnail(path)
        ZStack {
            SceneViewTokens.RoomScan.background
            if let thumbnail {
                Color.clear.overlay {
                    Image(decorative: thumbnail, scale: 1)
                        .resizable()
                        .scaledToFill()
                        .blur(radius: SceneViewTokens.Space.lg + SceneViewTokens.Space.sm)
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
        .task(id: path) {
            guard let path, let image = await session.fullFrame(path), !Task.isCancelled else { return }
            full = image
        }
        .accessibilityElement()
        .accessibilityLabel("The camera frame recorded at this moment")
        .accessibilityAddTraits(.isImage)
    }
}

// MARK: - Filmstrip

/// Play / pause, what is on screen and the clock, over a strip of the session's frames that
/// scrubs under the finger.
struct RerunFilmstripCard: View {
    let session: RerunReplaySession
    let title: String
    let caption: String

    @State private var scrubbing = false
    @State private var resumeAfterScrub = false

    var body: some View {
        VStack(spacing: Space.sm) {
            HStack(spacing: Space.sm) {
                Button { session.togglePlay() } label: {
                    Image(systemName: session.playing ? "pause.fill" : "play.fill")
                        .font(SceneViewTokens.TypeScale.card)
                        .foregroundStyle(SceneViewTokens.RoomScan.text)
                        .frame(width: SceneViewTokens.Layout.touchTarget, height: SceneViewTokens.Layout.touchTarget)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(session.playing ? "Pause" : "Play")
                .accessibilityIdentifier("rerun-play")
                .padding(.leading, -Space.sm)
                VStack(alignment: .leading, spacing: 0) {
                    Text(title)
                        .font(SceneViewTokens.TypeScale.bodySemibold)
                        .foregroundStyle(SceneViewTokens.RoomScan.text)
                        .lineLimit(1)
                    Text(caption)
                        .font(SceneViewTokens.TypeScale.captionRegular)
                        .foregroundStyle(SceneViewTokens.RoomScan.secondaryText)
                        .lineLimit(1)
                }
                Spacer(minLength: Space.sm)
                Text("\(RerunFormat.clock(session.time)) / \(RerunFormat.clock(session.duration))")
                    .font(SceneViewTokens.TypeScale.caption)
                    .monospacedDigit()
                    .foregroundStyle(SceneViewTokens.RoomScan.secondaryText)
                    .accessibilityHidden(true)
            }
            strip
        }
        .padding(.horizontal, Space.md)
        .padding(.top, Space.xs)
        .padding(.bottom, Space.md)
        .frame(maxWidth: RerunChromeMetrics.maxWidth)
        .rerunCard()
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
                SceneViewTokens.RoomScan.secondaryText
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
                SceneViewTokens.Chrome.scrim.opacity(RerunChromeMetrics.dimAlpha)
                    .frame(width: width * (1 - min(max(progress, 0), 1)))
                    .frame(maxWidth: .infinity, alignment: .trailing)
                SceneViewTokens.RoomScan.text
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

// MARK: - Loading

struct RerunReplayLoading: View {
    var body: some View {
        ZStack {
            SceneViewTokens.RoomScan.background
            VStack(spacing: Space.md) {
                ProgressView().tint(SceneViewTokens.RoomScan.text)
                Text("Loading the recorded session…")
                    .font(SceneViewTokens.TypeScale.body)
                    .foregroundStyle(SceneViewTokens.RoomScan.secondaryText)
            }
        }
        .ignoresSafeArea()
    }
}
#endif
