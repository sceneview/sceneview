package io.github.sceneview.demo.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.android.filament.Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home leaves the composition on every demo it opens. Its hero must find the same engine, so the
 * same EGL context, on every return: a new context per return used up the KGSL draw contexts of
 * an Adreno 618 after about 200 returns (#5202).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeHeroEngineTest {

    @get:Rule
    val rule = createComposeRule()

    private var made = 0
    private var released = 0

    /** A Filament [Engine] without JNI: only its identity is used. */
    private fun fakeEngine(): Engine {
        val constructor = Engine::class.java.getDeclaredConstructor(
            Long::class.javaPrimitiveType,
            Engine.Config::class.java,
        )
        constructor.isAccessible = true
        return constructor.newInstance(1L + made, null)
    }

    private val countingEngine: @Composable () -> Engine = {
        val engine = remember { made++; fakeEngine() }
        DisposableEffect(engine) { onDispose { released++ } }
        engine
    }

    @Test
    fun `every return to Home renders on the engine of the first visit`() {
        var home by mutableStateOf(true)
        var app by mutableStateOf(true)
        val seen = mutableListOf<Engine>()
        rule.setContent {
            if (app) {
                ProvideHomeHeroEngine(rememberHeroEngine = countingEngine) {
                    if (home) {
                        val engine = rememberHomeHeroEngine()
                        if (engine != null) DisposableEffect(engine) { seen += engine; onDispose { } }
                    }
                }
            }
        }
        rule.waitForIdle()
        repeat(50) {
            home = false
            rule.waitForIdle()
            home = true
            rule.waitForIdle()
        }

        assertEquals("one engine for 51 visits", 1, made)
        assertEquals("the hero composed on each visit", 51, seen.size)
        seen.forEach { assertSame(seen.first(), it) }
        assertEquals("kept while the app lives", 0, released)

        app = false
        rule.waitForIdle()
        assertEquals("released with the app", 1, released)
    }

    @Test
    fun `no engine is made before the hero asks for one`() {
        var home by mutableStateOf(false)
        var engine: Engine? = null
        rule.setContent {
            ProvideHomeHeroEngine(rememberHeroEngine = countingEngine) {
                if (home) engine = rememberHomeHeroEngine()
            }
        }
        rule.waitForIdle()
        assertEquals(0, made)
        assertNull(engine)

        home = true
        rule.waitForIdle()
        assertEquals(1, made)
        assertNotNull(engine)
    }
}
