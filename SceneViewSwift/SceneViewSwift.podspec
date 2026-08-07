Pod::Spec.new do |s|
  s.name             = 'SceneViewSwift'
  s.version          = '4.26.0'
  s.summary          = '3D and AR as declarative SwiftUI — RealityKit renderer.'
  s.description      = <<-DESC
  SceneView for Apple platforms: `SceneView` and `ARSceneView` SwiftUI views
  backed by RealityKit and ARKit, mirroring the Android (Filament) API.
                       DESC
  s.homepage         = 'https://github.com/sceneview/sceneview'
  s.license          = { :type => 'Apache-2.0', :file => '../LICENSE' }
  s.author           = { 'SceneView' => 'contact@sceneview.github.io' }
  s.source           = { :path => '.' }
  s.source_files     = 'Sources/SceneViewSwift/**/*.swift'

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
  # Keep `s.version`, `s.platform` and `s.swift_version` in sync with
  # Package.swift and with the bridges' own podspecs.
end
