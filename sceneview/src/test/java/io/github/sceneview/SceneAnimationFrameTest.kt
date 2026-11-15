package io.github.sceneview

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.jvm.kotlinFunction

@OptIn(ExperimentalCoroutinesApi::class)
class SceneAnimationFrameTest {

    private fun active(driver: ((Long) -> Unit)?) = isSceneFrameActive(
        gestureInFlight = false,
        cameraMoved = false,
        cameraPending = false,
        hasActiveNode = false,
        isLoading = false,
        isMirroring = false,
        framingPending = false,
        hasAnimationFrame = driver != null
    )

    @Test
    fun animationDriverAloneKeepsOnDemandRenderingUntilCleared() {
        val gate = FrameRateGate()
        var driver: ((Long) -> Unit)? = {}
        val period = vsyncPeriodNanos(60f)

        // Ten seconds, well beyond the settle tail, with no node activity or requestRender().
        repeat(600) { tick ->
            val now = (tick + 1) * period
            assertTrue(gate.shouldRender(active(driver), now))
            gate.didRender(now)
        }
        assertFalse(gate.isSettled)

        driver = null
        repeat(120) { tick ->
            val now = (tick + 601) * period
            if (gate.shouldRender(active(driver), now)) gate.didRender(now)
        }
        assertTrue("clearing the driver must let the scene park again", gate.isSettled)
    }

    @Test
    fun frameObserverAloneDoesNotKeepOnDemandRendering() {
        val gate = FrameRateGate()
        var observations = 0
        val observer: (Long) -> Unit = { observations++ }

        repeat(300) { tick ->
            val now = (tick + 1) * vsyncPeriodNanos(60f)
            if (gate.shouldRender(active(driver = null), now)) {
                observer(now)
                gate.didRender(now)
            }
        }

        assertTrue("the observer sees the initial settle frames", observations > 0)
        assertTrue("observing frames must not pin the cadence", gate.isSettled)
        assertFalse(gate.shouldRender(active(driver = null), 6_000_000_000L))
    }

    @Test
    fun subscribingWakesAParkedSceneAndUnsubscribingLetsItPark() = runTest {
        val driver = mutableStateOf<((Long) -> Unit)?>(null)
        val shouldRender = derivedStateOf {
            isSceneRenderingEnabled(
                policy = FrameRatePolicy.OnDemand(),
                isDirty = false,
                hasAnimationFrame = driver.value != null
            )
        }
        var resumed = false
        val firstWait = launch {
            awaitRenderingEnabled(shouldRender)
            resumed = true
        }
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(resumed)

        driver.value = {}
        Snapshot.sendApplyNotifications()
        runCurrent()
        assertTrue("subscription must wake the loop without any other invalidation", resumed)
        assertTrue(firstWait.isCompleted)

        driver.value = null
        Snapshot.sendApplyNotifications()
        var resumedAgain = false
        val secondWait = launch {
            awaitRenderingEnabled(shouldRender)
            resumedAgain = true
        }
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse("a removed driver must not keep the wake condition true", resumedAgain)
        assertTrue(secondWait.isActive)
        secondWait.cancel()
    }

    @Test
    fun sceneViewDeclaresAnOptionalAnimationDriverAlongsideTheObserver() {
        val overloads = Class.forName("io.github.sceneview.SceneKt").declaredMethods
            .filter { it.name == "SceneView" }
            .mapNotNull { it.kotlinFunction }

        assertTrue(
            "SceneView must expose an optional, nullable onAnimationFrame driver",
            overloads.any { function ->
                function.parameters.any {
                    it.name == "onAnimationFrame" && it.isOptional && it.type.isMarkedNullable
                } && function.parameters.any { it.name == "onFrame" }
            }
        )
    }

    @Test
    fun compatibilityOverloadRemainsInBytecodeButIsHiddenFromKotlinCalls() {
        val overloads = Class.forName("io.github.sceneview.SceneKt").declaredMethods
            .filter { it.name == "SceneView" }
        assertEquals("both JVM descriptors must remain available", 2, overloads.size)

        val compatibilityOverload = overloads.minBy { it.parameterCount }
        assertEquals(
            DeprecationLevel.HIDDEN,
            compatibilityOverload.getAnnotation(Deprecated::class.java)?.level
        )
        assertTrue("the compatibility overload must be binary-only", compatibilityOverload.isSynthetic)
        assertFalse(
            "source calls must use the overload with the animation driver and full KDoc",
            overloads.maxBy { it.parameterCount }.isSynthetic
        )
    }
}
