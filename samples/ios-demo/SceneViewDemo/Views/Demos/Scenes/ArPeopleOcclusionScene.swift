// @sceneId     ar-people-occlusion
// @title       People Occlusion
// @subtitle    Virtual objects hide behind real people
// @category    ar
// @available   true
// @icon        person.fill.viewfinder
// @iosOnly     true
import SwiftUI

enum ArPeopleOcclusionScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(ARPeopleOcclusionDemo())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
