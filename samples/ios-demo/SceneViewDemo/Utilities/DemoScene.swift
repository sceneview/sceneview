import SwiftUI

/// Append-only demo registry protocol — iOS counterpart of Android's `DemoFragment`.
///
/// To add a new iOS demo:
/// 1. Create `Views/Demos/Scenes/<YourId>Scene.swift` declaring an `enum`
///    conforming to `DemoScene`.
/// 2. Run `bash samples/ios-demo/scripts/collate-ios-demos.sh` to regenerate
///    `GeneratedScenes.swift`.
/// 3. The demo appears in `SamplesTab` **and** becomes deep-linkable via
///    `sceneview://demo/<sceneId>` automatically — the collator generates
///    the Samples list, the `allowedIds` gate, and the id→view resolver from
///    the same `@sceneId`, so **no other file needs to be edited**.
///
/// # Rules
///
/// - The `sceneId` must be a stable slug matching the Android `DemoRegistry`
///   entry (e.g. `"model-viewer"`). It is used as the deep-link token.
/// - `sceneId` must be unique across all conforming types.
/// - The file must be named exactly `<PascalCaseId>Scene.swift` under
///   `Views/Demos/Scenes/` so the collator can discover it.
///
/// # How the collator works
///
/// `samples/ios-demo/scripts/collate-ios-demos.sh` discovers every
/// `*Scene.swift` file, reads the `// @sceneId`, `// @title`,
/// `// @subtitle`, `// @category`, and `// @available` directives from
/// each file's header comments, and regenerates `GeneratedScenes.swift`.
/// That generated file is `.gitignore`d (like Android's `GeneratedDemos.kt`)
/// and re-created before each Xcode build by a Run Script phase.
///
/// Using structured header comments (instead of parsing Swift syntax)
/// keeps the collator a simple line-grepping shell script that works
/// without a Swift parser or SPM plugins.
///
/// # Directives (all required in each `*Scene.swift` file)
///
/// ```
/// // @sceneId     model-viewer
/// // @title       Model Viewer
/// // @subtitle    Load and display 3D models
/// // @category    basics3D          (one of: basics3D|lighting|content|interaction|advanced|ar)
/// // @available   true              (true = shows destination view; false = "Coming soon")
/// ```
///
/// The conforming type must also provide:
/// - `static var destination: AnyView { get }` — SwiftUI view (ignored when
///   `@available false`).
///
/// Conforming with `enum` (no cases) is idiomatic Swift for a namespace:
///
/// ```swift
/// // @sceneId     model-viewer
/// // @title       Model Viewer
/// // @subtitle    Load and display 3D models
/// // @category    basics3D
/// // @available   true
/// enum ModelViewerScene: DemoScene {
///     static var destination: AnyView { AnyView(ModelViewerDemo()) }
/// }
/// ```
public protocol DemoScene {
    /// The SwiftUI `View` presented when the user taps the demo card.
    ///
    /// Ignored for coming-soon demos (collator sets `available = false` in
    /// the generated `DemoItem`).
    @MainActor static var destination: AnyView { get }
}
