Pod::Spec.new do |s|
  s.name             = 'flutter_sceneview'
  s.version          = '4.24.0'
  s.summary          = 'Flutter plugin for SceneView 3D and AR.'
  s.description      = <<-DESC
  Flutter plugin bridging to SceneViewSwift (RealityKit) for 3D and AR scenes on iOS.
                       DESC
  s.homepage         = 'https://github.com/sceneview/sceneview'
  s.license          = { :type => 'Apache-2.0' }
  s.author           = { 'SceneView' => 'contact@sceneview.github.io' }
  s.source           = { :path => '.' }
  s.source_files     = 'Classes/**/*'
  s.dependency 'Flutter'
  s.platform         = :ios, '17.0'
  s.swift_version    = '5.9'

  # SceneViewSwift is consumed via SPM — the host app must add it to their Xcode project.
  # CocoaPods doesn't natively support SPM dependencies, so we declare it as a framework.
  s.frameworks = 'RealityKit', 'ARKit'
end
