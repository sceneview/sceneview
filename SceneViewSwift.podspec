Pod::Spec.new do |s|
  s.name             = 'SceneViewSwift'
  s.version          = '4.27.0'
  s.summary          = '3D and AR as declarative SwiftUI — RealityKit renderer.'
  s.description      = <<-DESC
  SceneView for Apple platforms: `SceneView` and `ARSceneView` SwiftUI views
  backed by RealityKit and ARKit, mirroring the Android (Filament) API.
                       DESC
  s.homepage         = 'https://github.com/sceneview/sceneview'
  s.license          = { :type => 'Apache-2.0', :file => 'LICENSE' }
  s.author           = { 'SceneView' => 'contact@sceneview.github.io' }
  s.source           = {
    :git => 'https://github.com/sceneview/sceneview.git',
    :tag => "v#{s.version}"
  }
  s.source_files     = 'SceneViewSwift/Sources/SceneViewSwift/**/*.swift'

  # Must match Package.swift's `platforms:` — see the note there on why v1 needs
  # iOS 18 / visionOS 2 (RealityKit's per-entity light and shadow APIs).
  s.platform         = :ios, '18.0'
  s.swift_version    = '5.10'

  s.frameworks = 'RealityKit', 'ARKit', 'SwiftUI', 'Combine'

  # ── Why this podspec exists alongside Package.swift ────────────────────────
  #
  # SPM is the primary distribution channel for Apple consumers and stays the
  # source of truth for the platform floor. This podspec exists because the
  # Flutter and React Native bridges are *CocoaPods pods*: they compile inside
  # the generated Pods.xcodeproj, which cannot see a Swift package added to the
  # host app's project. Declaring SceneViewSwift as a local package on the pod
  # target does not help either — the pod's Swift compile does not pick up the
  # package's .swiftmodule search path, and the build fails with
  # "Unable to find module dependency: 'SceneViewSwift'". Shipping the sources
  # as a pod is what actually makes `import SceneViewSwift` resolve from a
  # bridge, and it is why the Flutter demo's iOS path could not build at all.
  #
  # ── Why it lives at the repo ROOT, not in SceneViewSwift/ ──────────────────
  #
  # `flutter_sceneview` is published to pub.dev and declares
  # `s.dependency 'SceneViewSwift'`. A consumer who installs the plugin from
  # pub.dev has no clone of this monorepo, so a `:path` Podfile line is not
  # reachable for them — and this pod is deliberately NOT on the CocoaPods
  # trunk. The clone-free, publish-free routes are `:git =>` and `:podspec =>`,
  # and BOTH need this file at the repository root: `:git` because CocoaPods
  # resolves it by looking for `<name>.podspec` at the root of the checked-out
  # ref, `:podspec` because it fetches that same root path over raw HTTPS.
  # Sitting in `SceneViewSwift/` made both lookups fail, which would have turned
  # every downstream `pod install` into "Unable to find a specification for
  # 'SceneViewSwift'".
  #
  # Today only `:podspec =>` (against `main`) actually resolves: no tag carries
  # this file yet — verified with `git cat-file -e vX.Y.Z:SceneViewSwift.podspec`
  # on v4.26.0 and v4.27.0, the two most recent. `:git =>, :tag =>` becomes the
  # reproducible route the moment a release is cut with this file in it, and the
  # install docs move to it then. Do not document `:git =>` as working before
  # that: the plugin README and quickstart-flutter.md both say `:podspec =>`,
  # and a podspec comment contradicting them is how a reader picks the dead one.
  #
  # Consequences of the location, both deliberate:
  #   - `source_files` is repo-relative (`SceneViewSwift/Sources/...`).
  #   - `:file => 'LICENSE'` resolves to the repo's own Apache-2.0 file. The
  #     previous `'../LICENSE'` escaped the pod root: it worked for a local
  #     `:path` install and would have shipped a packaged pod with no licence
  #     at all, which `pod spec lint` rejects and licence compliance does not
  #     forgive.
  #
  # Keep `s.version`, `s.platform` and `s.swift_version` in sync with
  # Package.swift and with the bridges' own podspecs. `s.version` is checked by
  # `.claude/scripts/sync-versions.sh` — that check is the enforcement; this
  # comment is not.
end
