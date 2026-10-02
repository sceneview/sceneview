import SwiftUI

/// About → "Privacy & notifications": the two switches of the Android About group.
///
/// Only in a build that carries a Firebase config (`FirebaseTelemetry.hasBundledConfig`):
/// without one nothing is collected and no push can arrive, so a switch would promise
/// something false.
///
/// "Share usage statistics" is the consent's withdrawal point: OFF while it is unknown or
/// refused, ON grants it (timestamped), OFF again stops collection, resets the analytics
/// ID and deletes unsent crash reports (`TelemetryConsent.setUsageStats`).
struct PrivacySettingsSection: View {
    @ObservedObject private var consent = TelemetryConsent.shared
    #if os(iOS)
    @ObservedObject private var push = PushCenter.shared
    @Environment(\.openURL) private var openURL
    #endif

    var body: some View {
        VStack(alignment: .leading, spacing: SceneViewTokens.Space.sm) {
            Text("Privacy & notifications")
                .font(SceneViewTokens.TypeScale.captionSemibold)
                .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                .textCase(.uppercase)
                .padding(.leading, SceneViewTokens.Space.xs)
                .accessibilityAddTraits(.isHeader)

            VStack(spacing: 0) {
                SettingsToggleRow(
                    icon: "chart.bar.fill",
                    title: "Share usage statistics",
                    subtitle: "Which samples are opened, and crash reports. No ads, no advertising ID. Turning it off resets the ID and deletes unsent crash reports.",
                    isOn: Binding(
                        get: { consent.collectionAllowed },
                        set: { consent.setUsageStats($0) }
                    ),
                    identifier: "settings-usage-stats"
                )
                #if os(iOS)
                Divider()
                    .overlay(SceneViewTokens.HomeColor.outlineSubtle)
                    .padding(.leading, 14 + 44 + 14)
                SettingsToggleRow(
                    icon: "bell.badge.fill",
                    title: "Notifications",
                    subtitle: notificationsSubtitle,
                    isOn: Binding(
                        get: { push.notificationsEnabled },
                        set: { push.setNotificationsEnabled($0) }
                    ),
                    identifier: "settings-notifications"
                )
                if push.notificationsEnabled, push.authorization == .denied {
                    Button("Allow in iOS Settings") {
                        if let url = URL(string: UIApplication.openNotificationSettingsURLString) {
                            openURL(url)
                        }
                    }
                    .font(SceneViewTokens.TypeScale.captionSemibold)
                    .foregroundStyle(SceneViewTokens.HomeColor.primary)
                    .frame(maxWidth: .infinity, minHeight: SceneViewTokens.Layout.touchTarget, alignment: .leading)
                    .padding(.leading, 14 + 44 + 14)
                    .accessibilityIdentifier("settings-notifications-open-settings")
                }
                #endif
            }
            .materialGlassBackground(in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .strokeBorder(Color.primary.opacity(0.06), lineWidth: 0.5)
            )
        }
        #if os(iOS)
        .task { await push.refreshAuthorization() }
        #endif
    }

    #if os(iOS)
    private var notificationsSubtitle: String {
        let base = "New samples and releases, a few times a month at most."
        guard push.notificationsEnabled, push.authorization == .denied else { return base }
        return base + " Notifications are off for SceneView in iOS Settings."
    }
    #endif
}

private struct SettingsToggleRow: View {
    let icon: String
    let title: String
    let subtitle: String
    @Binding var isOn: Bool
    let identifier: String

    var body: some View {
        Toggle(isOn: $isOn) {
            HStack(spacing: 14) {
                ZStack {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(SceneViewTokens.HomeColor.primary.opacity(0.18))
                    Image(systemName: icon)
                        .font(.title3)
                        .foregroundStyle(SceneViewTokens.HomeColor.primary)
                }
                .frame(width: 44, height: 44)
                .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(SceneViewTokens.HomeColor.onSurface)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .tint(SceneViewTokens.HomeColor.primary)
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .accessibilityIdentifier(identifier)
    }
}
