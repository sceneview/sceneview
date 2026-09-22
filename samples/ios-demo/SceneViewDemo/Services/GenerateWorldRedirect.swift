import Foundation

#if canImport(UIKit)
import UIKit
#endif

#if os(macOS)
import AppKit
#endif

/// Where the Showcase grid's "Generate a 3D world" card sends the user.
///
/// The feature itself — World Labs turning a video, a photo or a sentence into
/// a walkable Gaussian-splat world — lives in the companion **AR Model Viewer**
/// app, not in this demo. Today that app ships on Android only
/// (`com.gorisse.thomas.arcamera`) and has no App Store id, so the iOS
/// destination is *optional by construction*. Three candidates, in order:
///
/// 1. ``deepLink`` — AR Model Viewer is installed and has registered its custom
///    scheme. Requires `armodelviewer` in `Info.plist`'s
///    `LSApplicationQueriesSchemes`, without which `canOpenURL` always answers
///    `false` and this branch can never be taken.
/// 2. The App Store product page — only once ``appStoreId`` is filled in.
/// 3. ``docsURL`` — the honest fallback while no iOS app exists. It describes
///    the feature instead of dead-ending on a store page that would 404.
///
/// ``target(canOpenDeepLink:appStoreId:)`` is that decision, kept pure so it is
/// unit-tested without a device (`GenerateWorldRedirectTests`); ``open()`` is
/// the thin platform shell around it, branching exactly like
/// ``AppStoreUpdater/openAppStore()``.
enum GenerateWorldRedirect {

    /// Custom URL scheme AR Model Viewer will register on iOS. Whitelisted in
    /// `Info.plist` (`LSApplicationQueriesSchemes`).
    static let deepLink = URL(string: "armodelviewer://generate-world?source=sceneview-demo")!

    /// App Store id of AR Model Viewer for iOS. `nil` until the iOS app ships —
    /// the card then points at the docs page. Filling this in is the single
    /// edit that flips both the redirect and the card's copy.
    static let appStoreId: String? = nil

    /// Feature page on the SceneView site — the destination that always exists.
    static let docsURL = URL(string: "https://sceneview.github.io/ai-development/")!

    enum Target: Equatable {
        case deepLink
        case appStore(id: String)
        case docs
    }

    /// Pure decision: the installed app wins, then the store when there is an
    /// id to open, then the docs page.
    ///
    /// An empty `appStoreId` counts as no id — `itms-apps://…/app/id` with
    /// nothing after it is a broken product page, not a listing.
    static func target(canOpenDeepLink: Bool, appStoreId: String?) -> Target {
        if canOpenDeepLink { return .deepLink }
        if let appStoreId, !appStoreId.isEmpty { return .appStore(id: appStoreId) }
        return .docs
    }

    /// Send the user to whichever destination
    /// ``target(canOpenDeepLink:appStoreId:)`` picks.
    @MainActor
    static func open() {
        #if canImport(UIKit) && os(iOS)
        switch target(canOpenDeepLink: UIApplication.shared.canOpenURL(deepLink),
                      appStoreId: appStoreId) {
        case .deepLink:
            UIApplication.shared.open(deepLink)
        case .appStore(let id):
            guard let url = URL(string: "itms-apps://itunes.apple.com/app/id\(id)") else { return }
            UIApplication.shared.open(url)
        case .docs:
            UIApplication.shared.open(docsURL)
        }
        #elseif os(macOS)
        // The deep link is never attempted on macOS: AR Model Viewer is a phone
        // app, and `NSWorkspace` has no `canOpenURL` counterpart that could
        // answer for it — `open` on an unhandled scheme fails silently, so the
        // tap would do nothing at all instead of landing somewhere useful.
        switch target(canOpenDeepLink: false, appStoreId: appStoreId) {
        case .appStore(let id):
            guard let url = URL(string: "macappstore://itunes.apple.com/app/id\(id)") else { return }
            NSWorkspace.shared.open(url)
        case .deepLink, .docs:
            NSWorkspace.shared.open(docsURL)
        }
        #endif
    }
}
