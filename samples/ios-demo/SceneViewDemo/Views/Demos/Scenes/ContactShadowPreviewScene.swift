// @sceneId     contact-shadow-preview
// @title       Contact Shadow Preview
// @subtitle    Compare objects with and without a contact shadow
// @category    lighting
// @section     create
// @available   true
// @icon        circle.lefthalf.filled
// @status      inReview
// @order       18
// @tags        shadow,contact-shadow,procedural,grounding,no-camera
// @addedIn     4.52.0
import SwiftUI

enum ContactShadowPreviewScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(ContactShadowPreviewDemo()) }
}
