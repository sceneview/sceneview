// @sceneId     cosmos
// @title       Cosmos
// @subtitle    Watch a galaxy, a star and particle storms glow
// @category    advanced
// @section     create
// @available   true
// @icon        sparkles
// @order       1
// @tags        bloom,emissive,particles,procedural,shader,galaxy,space,custom material
// @addedIn     4.49.0
// @updatedIn   4.52.0
import SwiftUI

enum CosmosScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(CosmosDemo()) }
}
