require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "react-native-sceneview"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["repository"]
  s.license      = package["license"]
  s.authors      = "SceneView contributors"

  s.platforms    = { :ios => "17.0" }
  s.source       = { :git => "https://github.com/sceneview/sceneview.git", :tag => s.version }
  s.source_files = "ios/**/*.{swift,m}"

  s.dependency "React-Core"
  s.dependency "SceneViewSwift", "~> 4.9"

  s.swift_version = "5.9"
end
