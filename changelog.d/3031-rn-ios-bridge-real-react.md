<!-- category: Changed -->

- **React Native (iOS): the module's minimum iOS version is now 18.0, up from a declared 17.0.** The 17.0 figure was never real — `SceneViewSwift` has required iOS 18.0 since #719, so an iOS 17 host app resolved the pod and then failed later at build time with a confusing error. Declaring the true floor moves that failure to `pod install`, where it names its own cause. Host apps must set `platform :ios, '18.0'` in their `Podfile` and build with Xcode 16+.

<!-- category: Fixed -->

- **React Native (iOS): the `pod install` of a host app no longer fails on this module's podspec.** `s.homepage` was fed `package["repository"]` — an object, which CocoaPods rejects outright (`Unacceptable type 'Hash' for 'homepage'`) — and `s.platforms` claimed iOS 17.0 while `SceneViewSwift` requires iOS 18.0, so CocoaPods could not resolve the module at all. Both are corrected, and `samples/react-native-demo`'s `Podfile`, Xcode deployment target and READMEs now state the real iOS 18.0 floor.

<!-- category: Tests -->

- **The React Native iOS bridge is now type-checked against the real React API, not a hand-written stub.** `rn-ios-compile.yml` used to synthesise a Swift shim redeclaring the four React symbols the bridge touches; a stub like that silently drifts from the API it stands in for. The job now runs `npm ci` + `pod install` on the demo and imports CocoaPods' own generated `React-Core.modulemap` over React Native's real headers. Two negative controls run before the real check on every invocation — the same `swiftc` command without the SceneViewSwift module, and without the React modulemap — and each must fail with `no such module`, so the job can never report green on a check it did not actually perform.
