// @sceneId     reflection-probes
// @title       Reflection Probes
// @subtitle    Local cubemap reflections
// @category    advanced
// @available   true
// @icon        circle.lefthalf.filled
// @order       71
import SwiftUI

enum ReflectionProbesScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(ReflectionProbesDemo()) }
}
