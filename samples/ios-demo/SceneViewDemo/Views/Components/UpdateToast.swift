import SwiftUI

/// The pending App Store update, as a toast at the bottom of a root tab — the iOS twin
/// of the Android demo's "Update available · Update" snackbar.
///
/// Attach it with ``SwiftUI/View/updateToast()`` to a tab's content: it then sits just
/// above the tab bar and is never drawn over a demo, which is a full-screen cover.
/// **Update** opens the App Store page (``AppStoreUpdater/openAppStore()``); the close
/// button snoozes this version (``AppStoreUpdater/snooze()``) until a newer one ships.
struct UpdateToast: View {
    @EnvironmentObject private var updater: AppStoreUpdater

    var body: some View {
        ZStack {
            if updater.showsUpdatePrompt {
                UpdateToastContent(onUpdate: updater.openAppStore, onDismiss: updater.snooze)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(SceneViewTokens.Spring.animation, value: updater.showsUpdatePrompt)
    }
}

/// The toast itself, without the updater — so previews and layout checks can build it.
///
/// It never clips or truncates. Message, **Update** and close share one row while they
/// fit; when they do not (an iPhone SE at an accessibility text size), the message wraps
/// on its own row and **Update** moves under it, centred.
struct UpdateToastContent: View {
    let onUpdate: () -> Void
    let onDismiss: () -> Void

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: SceneViewTokens.Space.sm) {
                icon
                message
                Spacer(minLength: SceneViewTokens.Space.sm)
                updateButton
                closeButton
            }
            VStack(alignment: .leading, spacing: SceneViewTokens.Space.sm) {
                HStack(alignment: .top, spacing: SceneViewTokens.Space.sm) {
                    icon
                    message
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    closeButton
                }
                updateButton
                    .frame(maxWidth: .infinity)
            }
        }
        .padding(.leading, SceneViewTokens.Space.md)
        .padding(.trailing, SceneViewTokens.Space.xs)
        .padding(.vertical, SceneViewTokens.Space.xs)
        .glassBackground(in: RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg, style: .continuous))
        .padding(.horizontal, SceneViewTokens.Space.md)
        .padding(.bottom, SceneViewTokens.Space.sm)
        .accessibilityElement(children: .contain)
    }

    /// Decorative, and dropped at accessibility sizes so the words get the width.
    @ViewBuilder
    private var icon: some View {
        if !dynamicTypeSize.isAccessibilitySize {
            Image(systemName: "arrow.down.circle.fill")
                .font(.title3)
                .foregroundStyle(.tint)
                .accessibilityHidden(true)
        }
    }

    private var message: some View {
        Text("Update available")
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.primary)
            .padding(.vertical, SceneViewTokens.Space.sm)
    }

    private var updateButton: some View {
        Button(action: onUpdate) {
            Text("Update")
                .font(.subheadline.weight(.semibold))
                .padding(.horizontal, SceneViewTokens.Space.xs)
        }
        .buttonStyle(.borderedProminent)
        .buttonBorderShape(.capsule)
        .accessibilityHint("Opens the App Store")
    }

    private var closeButton: some View {
        Button(action: onDismiss) {
            Image(systemName: "xmark")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.secondary)
                .frame(
                    width: SceneViewTokens.Glass.iconButtonSize,
                    height: SceneViewTokens.Glass.iconButtonSize
                )
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Not now")
    }
}

extension View {
    /// Shows the App Store update toast at the bottom of this view, above the tab bar
    /// when the view is a tab's content. Needs an ``AppStoreUpdater`` in the environment.
    func updateToast() -> some View {
        overlay(alignment: .bottom) { UpdateToast() }
    }
}

#Preview("Update toast") {
    VStack {
        Spacer()
        UpdateToastContent(onUpdate: {}, onDismiss: {})
    }
    .background(.background.secondary)
}

#Preview("Update toast · AX5") {
    VStack {
        Spacer()
        UpdateToastContent(onUpdate: {}, onDismiss: {})
    }
    .background(.background.secondary)
    .dynamicTypeSize(.accessibility5)
}
