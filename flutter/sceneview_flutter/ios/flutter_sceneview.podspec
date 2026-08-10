Pod::Spec.new do |s|
  s.name             = 'flutter_sceneview'
  s.version          = '4.27.0'
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
  # too. It is NOT on the CocoaPods trunk, so the host app's Podfile must say
  # where it comes from. From a pub.dev install (no monorepo clone):
  #   pod 'SceneViewSwift',
  #       :podspec => 'https://raw.githubusercontent.com/sceneview/sceneview/main/SceneViewSwift.podspec'
  # From a checkout of this repo:
  #   pod 'SceneViewSwift', :path => '<repo-root>'
  #
  # Not `:git => …, :tag => 'vX.Y.Z'`: CocoaPods looks for the podspec at the
  # root of the checked-out tag, and the root podspec landed after v4.26.0 was
  # cut, so every tag that currently exists resolves to "Unable to find a
  # specification". Switch to a tagged coordinate once a release carries it.
  #
  # The version is pinned rather than left open. An unversioned dependency on a
  # name nobody has reserved on the trunk is a dependency-confusion foothold:
  # if the Podfile line above is omitted or mistyped, CocoaPods falls through to
  # the trunk and resolves whatever anyone has published under this name. The
  # constraint at least bounds what an unexpected resolution can pull in.
  #
  # The floor tracks VERSION_NAME's MAJOR.MINOR and is enforced by
  # `sync-versions.sh` (row "flutter/.../ios/... SceneViewSwift floor"). It sat at
  # '~> 4.26' while the SDK shipped 4.27.0 because nothing watched this line —
  # only `s.version` above was registered. A stale floor is not inert: it lets an
  # older SceneViewSwift satisfy the dependency, so the bridge can link against a
  # runtime that predates the APIs it calls.
  s.dependency 'SceneViewSwift', '~> 4.27'
  # Must match SceneViewSwift/Package.swift's `.iOS("18.0")`. This said 17.0
  # while the package it bridges to required 18.0 — a host app that believed
  # the podspec and targeted 17.0 got availability errors from RealityKit's
  # per-entity light/shadow APIs at link time, not a clear version error.
  s.platform         = :ios, '18.0'
  s.swift_version    = '5.9'

  s.frameworks = 'RealityKit', 'ARKit'
end
