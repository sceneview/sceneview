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

  # This module's `ios/*.swift` files `import SceneViewSwift`, so the module has
  # to arrive as a POD, not as a Swift package on the host app's project: this
  # source compiles inside the generated `Pods.xcodeproj`, which does not see
  # the host project's SwiftPM packages ("Unable to find module dependency:
  # 'SceneViewSwift'"). The SwiftPM route this spec used to document was never
  # measured — the demo app was scaffolded and never built — and the first real
  # `xcodebuild` against it failed exactly that way. Closes the RN half of #3072.
  #
  # `SceneViewSwift` is NOT on the CocoaPods trunk, so a host app must give the
  # dependency a coordinate of its own — one of:
  #   pod 'SceneViewSwift',
  #       :podspec => 'https://raw.githubusercontent.com/sceneview/sceneview/main/SceneViewSwift.podspec'
  #   pod 'SceneViewSwift', :path => '<repo-root>'   # in-repo consumers
  # `main`, not a tag, and not `:git =>, :tag =>`: the root podspec landed after
  # v4.26.0 was cut, so no existing tag carries it and a tagged coordinate 404s
  # (`git cat-file -e v4.27.0:SceneViewSwift.podspec`). `flutter_sceneview.podspec`
  # says `main` for the same reason; both move to a tag once a release carries
  # the file. Supply the coordinate always — the name is unclaimed on the trunk,
  # so a Podfile that leaves it out does not fail closed, it resolves to
  # whatever someone else publishes under that name.
  # `samples/react-native-demo/ios/Podfile` takes the `:path` route, exactly as
  # `samples/flutter-demo/ios/Podfile` does. See this module's README "iOS".
  #
  # Loose `~> 4.27` rather than `= s.version`: an app pinned to an older
  # SceneViewSwift still resolves, mirroring `flutter_sceneview.podspec`.
  s.dependency "SceneViewSwift", "~> 4.28"

  s.swift_version = "5.9"
end
