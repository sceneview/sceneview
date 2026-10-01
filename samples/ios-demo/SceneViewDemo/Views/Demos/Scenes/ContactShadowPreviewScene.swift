// @sceneId     contact-shadow-preview
// @title       Contact Shadow Preview
// @subtitle    Compare objects with and without a contact shadow
// @category    lighting
// @section     create
// @available   true
// @icon        circle.lefthalf.filled
// @order       18
// @tags        shadow,contact-shadow,procedural,grounding,no-camera
import SwiftUI

enum ContactShadowPreviewScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(ContactShadowPreviewDemo()) }
}
