package io.github.sceneview

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import com.google.android.filament.Engine
import com.google.android.filament.Renderer
import com.google.android.filament.View
import io.github.sceneview.collision.CollisionSystem
import io.github.sceneview.environment.Environment
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.gesture.GestureDetector
import io.github.sceneview.loaders.EnvironmentLoader
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.model.AnimationEnvelope
import io.github.sceneview.node.CameraNode
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.ViewNode
import io.github.sceneview.utils.SurfaceMirrorer
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.reflect.jvm.kotlinFunction

/**
 * Pins the `SceneView` JVM entry points and the source overload resolution of #3898:
 * one visible overload with `disallowParentIntercept: Boolean = false`, the previous
 * descriptors kept as hidden (synthetic) binary-compatibility shims, `Scene` untouched.
 */
class SceneComposableCompatibilityTest {

    private val facade = Class.forName("io.github.sceneview.SceneKt")

    @Test
    fun everyPublishedSceneViewDescriptorStillLinks() {
        val current = facade.getDeclaredMethod("SceneView", *sceneViewDescriptor(true, true))
        assertFalse("the current overload must stay callable from source", current.isSynthetic)
        for ((label, types) in listOf(
            "pre-onAnimationFrame" to sceneViewDescriptor(false, false),
            "pre-disallowParentIntercept" to sceneViewDescriptor(true, false),
        )) {
            val shim = try {
                facade.getDeclaredMethod("SceneView", *types)
            } catch (missing: NoSuchMethodException) {
                throw AssertionError("The $label SceneView descriptor was removed", missing)
            }
            assertTrue("The $label shim must be hidden from source", shim.isSynthetic)
            assertReachesTheRealEntryPoint(shim)
        }
    }

    @Test
    fun deprecatedSceneAliasKeepsItsOriginalDescriptor() {
        val scenes = facade.declaredMethods.filter { it.name == "Scene" }
        assertEquals("Scene gains no overload for #3898", 1, scenes.size)
        assertTrue(scenes.single().kotlinFunction!!.parameters.none {
            it.name == "disallowParentIntercept"
        })
    }

    @Test
    fun theSingleVisibleOverloadDefaultsTheOptInToOff() {
        val visible = facade.declaredMethods
            .filter { it.name == "SceneView" && !it.isSynthetic }
        assertEquals("exactly one SceneView overload resolves from source", 1, visible.size)
        val option = visible.single().kotlinFunction!!.parameters
            .single { it.name == "disallowParentIntercept" }
        assertTrue("existing calls must compile without naming the option", option.isOptional)
        val names = visible.single().kotlinFunction!!.parameters.map { it.name }
        assertEquals(
            "the option sits after onAnimationFrame, before the trailing content lambda",
            listOf("onFrame", "onAnimationFrame", "disallowParentIntercept", "content"),
            names.takeLast(4),
        )
    }

    /**
     * Invokes [method] with null resources: reaching `SceneKt` and failing there on the null
     * Composer proves the descriptor dispatches to real code (a missing one would not resolve).
     */
    private fun assertReachesTheRealEntryPoint(method: Method) {
        val args = method.parameterTypes.map<Class<*>, Any?> { type ->
            when (type) {
                java.lang.Boolean.TYPE -> false
                java.lang.Float.TYPE -> 0f
                java.lang.Integer.TYPE -> 0
                else -> null
            }
        }.toTypedArray()
        try {
            method.invoke(null, *args)
            fail("The fixture passes null resources and cannot render a scene")
        } catch (thrown: InvocationTargetException) {
            val cause = thrown.targetException
            assertTrue("expected an NPE, got $cause", cause is NullPointerException)
            assertTrue(cause.stackTrace.any {
                it.className == "io.github.sceneview.SceneKt" && it.methodName == "SceneView"
            })
        }
    }

    /** JVM parameter list of `SceneView`, after the Compose compiler transform. */
    private fun sceneViewDescriptor(
        withAnimationFrame: Boolean,
        withDisallowParentIntercept: Boolean,
    ): Array<Class<*>> = buildList {
        add(Modifier::class.java)
        add(SurfaceType::class.java)
        add(Engine::class.java)
        add(ModelLoader::class.java)
        add(MaterialLoader::class.java)
        add(EnvironmentLoader::class.java)
        add(View::class.java)
        add(java.lang.Boolean.TYPE) // isOpaque
        add(FrameRatePolicy::class.java)
        add(RenderInvalidator::class.java)
        add(RenderQuality::class.java)
        add(java.lang.Boolean.TYPE) // autoCenterContent
        add(java.lang.Boolean.TYPE) // autoFitContent
        add(java.lang.Float.TYPE) // framingPadding
        add(FitMode::class.java)
        add(AnimationEnvelope::class.java)
        add(PaddingValues::class.java)
        add(java.lang.Boolean.TYPE) // clipToContentPadding
        add(Renderer::class.java)
        add(com.google.android.filament.Scene::class.java)
        add(Environment::class.java)
        add(LightNode::class.java)
        add(LightNode::class.java)
        add(CameraNode::class.java)
        add(CollisionSystem::class.java)
        add(CameraGestureDetector.CameraManipulator::class.java)
        add(ViewNode.WindowManager::class.java)
        add(SurfaceMirrorer::class.java)
        add(GestureDetector.OnGestureListener::class.java)
        add(Function2::class.java) // onTouchEvent
        add(ComponentActivity::class.java)
        add(Lifecycle::class.java)
        add(Function1::class.java) // onFrame
        if (withAnimationFrame) add(Function1::class.java)
        if (withDisallowParentIntercept) add(java.lang.Boolean.TYPE)
        add(Function3::class.java) // content
        add(Composer::class.java)
        repeat(6) { add(java.lang.Integer.TYPE) } // 4 `$changed` + 2 `$default` masks
    }.toTypedArray()
}

/**
 * Compiled by the test task: named, positional and trailing-lambda calls written before #3898
 * keep compiling and resolve to the single visible overload, with or without the opt-in.
 */
@Suppress("DEPRECATION", "unused")
@Composable
private fun sceneOverloadSourceCompatibility() {
    SceneView()
    SceneView {}
    SceneView(content = {})
    SceneView(Modifier)
    SceneView(Modifier, SurfaceType.TextureSurface) {}
    SceneView(modifier = Modifier, onFrame = {}, onAnimationFrame = null) {}
    SceneView(disallowParentIntercept = true) {}
    SceneView(Modifier, disallowParentIntercept = true, content = {})
    SceneView(disallowParentIntercept = false)
    Scene()
    Scene {}
    Scene(content = {})
    Scene(Modifier, SurfaceType.TextureSurface) {}
}
