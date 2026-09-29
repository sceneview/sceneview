// @sceneId     cosmos
// @title       Cosmos
// @subtitle    Watch a galaxy, a star and particle storms glow
// @category    advanced
// @section     create
// @available   true
// @icon        sparkles
// @order       38
// @tags        bloom,emissive,particles,procedural,shader,galaxy,space,custom material
import SwiftUI

enum CosmosScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(CosmosDemo()) }
}
