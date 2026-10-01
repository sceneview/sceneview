// @sceneId     ar-people-occlusion
// @title       People Occlusion
// @subtitle    Virtual objects hide behind real people
// @category    ar
// @section     understand
// @available   true
// @icon        person.fill.viewfinder
// @iosOnly     true
// @order       42
// @tags        ar,occlusion,people,segmentation,depth
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
