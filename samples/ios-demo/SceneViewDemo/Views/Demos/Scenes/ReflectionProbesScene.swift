// @sceneId     reflection-probes
// @title       Reflection Probes
// @subtitle    Local cubemap reflections
// @category    advanced
// @section     create
// @available   true
// @icon        circle.lefthalf.filled
// @order       28
import SwiftUI

enum ReflectionProbesScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(ReflectionProbesDemo()) }
}
