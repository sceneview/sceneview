// @sceneId     ar-people-occlusion
// @title       People Occlusion
// @subtitle    Virtual objects hide behind real people
// @category    ar
// @section     understand
// @available   true
// @icon        person.fill.viewfinder
// @iosOnly     true
// @order       43
// @tags        ar,occlusion,people,segmentation,depth
// @addedIn     4.15.2
// @updatedIn   4.40.0
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
