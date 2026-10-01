import SwiftUI

extension EnvironmentValues {
    /// The id of the sample on screen, set by `DemoCover`. Controls deep inside a demo
    /// (the settings FAB, a dock item) read it to tag `sample_interaction`.
    @Entry var analyticsSampleId: String? = nil
}

extension View {
    /// Logs `outbound_link` for every `Link` / `openURL` below this view, then lets the
    /// system open the URL as before.
    func trackOutboundLinks(sampleId: String? = nil) -> some View {
        environment(\.openURL, OpenURLAction { url in
            if let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https" || scheme == "itms-apps" {
                DemoAnalytics.shared.log(.outboundLink(target: OutboundTarget.classify(url), sampleId: sampleId))
            }
            return .systemAction
        })
    }

    /// Logs `screen_view` when this screen appears.
    func trackScreen(_ name: String, screenClass: String) -> some View {
        onAppear { DemoAnalytics.shared.log(.screenView(name: name, screenClass: screenClass)) }
    }
}
