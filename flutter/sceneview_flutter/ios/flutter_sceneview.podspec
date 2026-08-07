Pod::Spec.new do |s|
  s.name             = 'flutter_sceneview'
  s.version          = '4.26.0'
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
  # The bridge compiles inside Pods.xcodeproj, which cannot see a Swift package
  # added to the host app's project — so SceneViewSwift has to arrive as a pod
  # too. The host app's Podfile supplies the path:
  #   pod 'SceneViewSwift', :path => '<repo>/SceneViewSwift'
  s.dependency 'SceneViewSwift'
  # Must match SceneViewSwift/Package.swift's `.iOS("18.0")`. This said 17.0
  # while the package it bridges to required 18.0 — a host app that believed
  # the podspec and targeted 17.0 got availability errors from RealityKit's
  # per-entity light/shadow APIs at link time, not a clear version error.
  s.platform         = :ios, '18.0'
  s.swift_version    = '5.9'

  # SceneViewSwift is consumed via SPM — the host app must add it to their Xcode project.
  # CocoaPods doesn't natively support SPM dependencies, so we declare it as a framework.
  s.frameworks = 'RealityKit', 'ARKit'
end
