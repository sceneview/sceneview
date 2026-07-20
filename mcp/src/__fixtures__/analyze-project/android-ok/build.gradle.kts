// Minimal Android fixture — latest SceneView, no anti-patterns.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.ok"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.ok"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    // Pinned to the current LATEST_SCENEVIEW_RELEASE so the
    // "Android project is up-to-date" assertion in analyze-project.test.ts
    // doesn't break every time the SDK ships a new patch. The fixture's
    // whole purpose is to represent a happy-path consumer project — by
    // definition that means using the current version.
    implementation("io.github.sceneview:sceneview:4.23.0")
}
