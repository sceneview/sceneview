plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.publish)
    alias(libs.plugins.dokka)
    id("filament-tools-plugin")
}

android {
    namespace = "io.github.sceneview"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        consumerProguardFiles("consumer-rules.pro")

        buildConfigField("String", "VERSION_NAME", "\"${project.properties["VERSION_NAME"]}\"")
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        getByName("debug") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // Preserve compression of filament files
    androidResources {
        noCompress.add("filamat")
        noCompress.add("ktx")
    }
}

dependencies {
    // Android KTX
    implementation(libs.androidx.fragment.ktx)

    // Compose
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)

    // kotlin-math
    api(libs.kotlin.math)

    // Filament
    api(libs.filament.android)
    api(libs.filament.gltfio)
    api(libs.filament.utils)

    // Fuel
    implementation(libs.fuel)

    testImplementation(libs.junit)
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
}

dokka {
    moduleName.set("SceneView Android - 3D")
    dokkaSourceSets.main {
        includes.from("../README.md")
        reportUndocumented.set(true)
        skipEmptyPackages.set(true)
        skipDeprecated.set(true)
        jdkVersion.set(21)

        // Do not create a link to the online Android SDK documentation
        enableAndroidDocumentationLink.set(false)

        // AndroidX + Compose docs
        externalDocumentationLinks {
            register("developer.android.com/reference") {
                url("https://developer.android.com/reference/")
                packageListUrl("https://developer.android.com/reference/androidx/package-list")
            }
            register("developer.android.com/reference/kotlin") {
                url("https://developer.android.com/reference/kotlin/")
                packageListUrl("https://developer.android.com/reference/kotlin/androidx/package-list")
            }
        }

        sourceLink {
            localDirectory.set(project.file("src/main/java"))
            // URL showing where the source code can be accessed through the web browser
            remoteUrl("https://github.com/sceneview/sceneview-android/tree/main/${project.name}/src/main/java")
            // Suffix which is used to append the line number to the URL. Use #L for GitHub
            remoteLineSuffix.set("#L")
        }
    }
}

filamentTools {
    // Material generation: .mat -> .filamat
    materialInputDir.set(project.layout.projectDirectory.dir("src/main/materials"))
    materialOutputDir.set(project.layout.projectDirectory.dir("src/main/assets/materials"))
    // IBL and Skybox generation: .hdr -> _ibl.ktx and _skybox.ktx
    iblInputDir.set(project.layout.projectDirectory.dir("src/main/environments"))
    iblOutputDir.set(project.layout.projectDirectory.dir("src/main/assets/environments"))
    iblFormat = "ktx"
}

tasks.named("clean") {
    doFirst {
        delete("src/main/assets/materials")
        delete("src/main/assets/environments")
    }
}