require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "react-native-sceneview"
  s.version      = package["version"]
  s.summary      = package["description"]
  # `package["repository"]` is an OBJECT ({type, url, directory}), and CocoaPods
  # rejects a Hash here ("Unacceptable type `Hash` for `homepage`"), which made
  # `pod install` fail outright for any host app consuming this module. Use the
  # string `homepage` field instead.
  s.homepage     = package["homepage"]
  s.license      = package["license"]
  s.authors      = "SceneView contributors"

  # Must match `SceneViewSwift/Package.swift`'s `.iOS("18.0")` floor — the
  # bridge's `ios/*.swift` imports SceneViewSwift, so a lower value here is a
  # lie CocoaPods cannot catch until a host app fails to link.
  s.platforms    = { :ios => "18.0" }
  s.source       = { :git => "https://github.com/sceneview/sceneview.git", :tag => "v#{s.version}" }
  s.source_files = "ios/**/*.{swift,m}"

  s.dependency "React-Core"

  # `SceneViewSwift` is distributed via Swift Package Manager only — there is
  # no published CocoaPods spec for it — so it CANNOT be declared as a
  # `s.dependency` here (CocoaPods would fail `pod install` resolving it).
  # The host app must add it once via Xcode's SwiftPM integration:
  #   File ▸ Add Package Dependencies… ▸ https://github.com/sceneview/SceneViewSwift
  # The module's `ios/*.swift` `import SceneViewSwift` then resolves at the
  # app build, exactly like any RN native module with a SwiftPM dependency.
  # See this module's README "iOS" section.

  s.swift_version = "5.9"
end
