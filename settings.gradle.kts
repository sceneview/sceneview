@file:Suppress("UnstableApiUsage")

import java.io.File
import java.io.File.separator

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://jitpack.io") }
        google()
        mavenCentral()
    }
}

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Without an explicit name the root project takes the name of the checkout directory. Since the
// repository is called "sceneview", that is the name of the :sceneview module as well, and the
// type-safe project accessors then generate `getSceneview()` twice and fail to compile.
rootProject.name = "sceneview-android"

/**
 * This file is responsible for loading and including all submodules which declare a
 * build.gradle.kts file in their root. No need to use include("path-to-folder") anymore
 */

val ignored = listOf("build", ".gradle", ".idea", ".kotlin", ".git", ".github", "scripts", "build-logic", "assets")
val projectNames = mutableListOf<String>()
rootDir.walk().onEnter { !ignored.contains(it.name) }.filter { it.isDirectory }
    .map { it.path.replace(rootDir.path, "") }.forEach { path ->
        val buildGradleFile = File("$rootDir/$path", "build.gradle.kts")
        if (buildGradleFile.exists()) {
            val pathString = path.replace(separator, ":")

            projectNames.add(pathString)
            include(pathString)
        }
    }
