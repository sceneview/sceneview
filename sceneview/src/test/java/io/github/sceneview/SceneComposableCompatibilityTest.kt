package io.github.sceneview

import androidx.compose.runtime.Composable
import io.github.sceneview.compat.LegacySceneCaller
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.reflect.jvm.kotlinFunction

/** Pins the old JVM entry points and the source overload resolution introduced for #3898. */
class SceneComposableCompatibilityTest {
    @Test
    fun legacySceneViewCallerStillLinks() = assertLegacyCallLinks("SceneView") {
        LegacySceneCaller.callSceneView()
    }

    @Test
    fun legacySceneCallerStillLinks() = assertLegacyCallLinks("Scene") {
        LegacySceneCaller.callScene()
    }

    @Test
    fun optInRequiresAnExplicitArgumentOnBothNewOverloads() {
        val facade = Class.forName("io.github.sceneview.SceneKt")
        for (name in listOf("SceneView", "Scene")) {
            val overloads = facade.declaredMethods.filter { it.name == name }
                .mapNotNull { it.kotlinFunction }
            assertTrue("$name must retain its original overload", overloads.any { fn ->
                fn.parameters.none { it.name == "disallowParentIntercept" }
            })
            val option = overloads.flatMap { it.parameters }
                .single { it.name == "disallowParentIntercept" }
            assertTrue("$name opt-in must be explicit to avoid overload ambiguity", !option.isOptional)
        }
    }

    private fun assertLegacyCallLinks(name: String, call: () -> Unit) {
        try {
            call()
            fail("The legacy fixture passes null resources and cannot render a scene")
        } catch (expected: NullPointerException) {
            // Reaching the real entry point with intentionally invalid resources proves that
            // invokestatic resolved. A removed JVM descriptor throws NoSuchMethodError instead.
            assertTrue(expected.stackTrace.any {
                it.className == "io.github.sceneview.SceneKt" && it.methodName == name
            })
        }
    }
}

/** Compiled by the test task: old default/named/trailing calls and explicit opt-in stay valid. */
@Suppress("DEPRECATION", "unused")
@Composable
private fun sceneOverloadSourceCompatibility() {
    SceneView()
    SceneView {}
    SceneView(content = {})
    SceneView(disallowParentIntercept = true) {}
    SceneView(disallowParentIntercept = false)
    Scene()
    Scene {}
    Scene(content = {})
    Scene(disallowParentIntercept = true) {}
    Scene(disallowParentIntercept = false)
}
