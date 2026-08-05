import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar

plugins {
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    // Artifact id defaults to the project name; a module can override it via the
    // `maven.artifactId` project property (set in its own gradle.properties). This must be
    // applied before configure() below, which finalizes the coordinates.
    val artifactId = (project.findProperty("maven.artifactId") as String?) ?: project.name
    coordinates(project.group.toString(), artifactId, project.version.toString())

    // Ship an empty -javadoc.jar for every module. API docs are hosted on GitHub Pages
    // (Dokka), and Maven Central only requires the artifact to exist — not have content. This
    // avoids bundling duplicated Dokka HTML into the jars: ~80MB across the KMP targets, plus a
    // ~5MB real javadoc jar the plain-JVM filament-ffm module would otherwise get from the
    // plugin's Dokka default. Both branches must opt in explicitly — the vanniktech default is
    // JavadocJar.Dokka whenever the Dokka plugin is present.
    when {
        pluginManager.hasPlugin("org.jetbrains.kotlin.multiplatform") ->
            configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty()))
        pluginManager.hasPlugin("org.jetbrains.kotlin.jvm") ->
            configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
        // Plain resource jars (the :java:runtime* native-runtime modules).
        pluginManager.hasPlugin("java-library") ->
            configure(JavaLibrary(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
    }

    pom {
        name.set(project.property("maven.name").toString())
        description.set(project.property("maven.description").toString())
        url.set(project.property("maven.url").toString())
        inceptionYear.set(project.property("maven.inceptionYear").toString())
        licenses {
            license {
                name.set(project.property("maven.license.name").toString())
                url.set(project.property("maven.license.url").toString())
            }
        }
        developers {
            developer {
                id.set(project.property("maven.developer.id").toString())
                name.set(project.property("maven.developer.name").toString())
                email.set(project.property("maven.developer.email").toString())
            }
        }
        scm {
            connection.set(project.property("maven.scm").toString())
            developerConnection.set(project.property("maven.scm.dev").toString())
            url.set(project.property("maven.url").toString())
        }
        issueManagement {
            system.set("GitHub")
            url.set(project.property("maven.url").toString() + "/issues")
        }
    }

    publishToMavenCentral()
    if (project.hasProperty("signingInMemoryKeyId")) {
        signAllPublications()
    }
}
