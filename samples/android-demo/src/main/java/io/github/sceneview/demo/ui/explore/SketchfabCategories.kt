@file:Suppress("MatchingDeclarationName") // plural file name, singular enum name — intentional

package io.github.sceneview.demo.ui.explore

/**
 * The 18 official Sketchfab categories returned by `GET /v3/categories`
 * (snapshot 2026-05-11). The `slug` is exactly what the Sketchfab Data
 * API expects in `?categories=`; `displayName` is what users see.
 *
 * Kept in sync with the iOS `SketchfabCategory` enum
 * (`samples/ios-demo/SceneViewDemo/Views/Tabs/ExploreTab.swift`).
 */
enum class SketchfabCategory(
    val slug: String,
    val displayName: String,
) {
    AnimalsPets("animals-pets", "Animals & Pets"),
    Architecture("architecture", "Architecture"),
    ArtAbstract("art-abstract", "Art & Abstract"),
    CarsVehicles("cars-vehicles", "Cars & Vehicles"),
    CharactersCreatures("characters-creatures", "Characters & Creatures"),
    CulturalHeritageHistory("cultural-heritage-history", "Cultural Heritage"),
    ElectronicsGadgets("electronics-gadgets", "Electronics"),
    FashionStyle("fashion-style", "Fashion & Style"),
    FoodDrink("food-drink", "Food & Drink"),
    FurnitureHome("furniture-home", "Furniture & Home"),
    Music("music", "Music"),
    NaturePlants("nature-plants", "Nature & Plants"),
    NewsPolitics("news-politics", "News & Politics"),
    People("people", "People"),
    PlacesTravel("places-travel", "Places & Travel"),
    ScienceTechnology("science-technology", "Science & Tech"),
    SportsFitness("sports-fitness", "Sports & Fitness"),
    WeaponsMilitary("weapons-military", "Weapons & Military"),
}
