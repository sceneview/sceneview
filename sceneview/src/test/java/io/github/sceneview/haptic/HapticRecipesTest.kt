package io.github.sceneview.haptic

import android.os.VibrationEffect
import android.os.VibrationEffect.Composition
import android.view.HapticFeedbackConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the tier chain behind every preset and [ARHapticEvent]: view constant → composition →
 * predefined → legacy view constant → (presets only) legacy buzz. Pure JVM: the platform ids
 * are inlined constants and the view / vibrator are recording fakes.
 */
class HapticRecipesTest {

    private fun resolve(
        recipe: HapticRecipe,
        sdk: Int,
        view: Boolean = true,
        vibrate: Boolean = true,
        primitives: Boolean = true,
    ) = recipe.resolve(sdk, view, vibrate) { primitives }

    // ── Tier order ─────────────────────────────────────────────────────────────────────────

    @Test
    fun placed_prefersTheViewConfirmConstant_onApi30Plus() {
        assertEquals(HapticPlan.View(HapticFeedbackConstants.CONFIRM), resolve(HapticRecipes.placed, 34))
        assertEquals(HapticPlan.View(HapticFeedbackConstants.CONFIRM), resolve(HapticRecipes.placed, 30))
    }

    @Test
    fun placed_withoutView_composesThudThenTick_onApi31() {
        val plan = resolve(HapticRecipes.placed, 31, view = false) as HapticPlan.Composed
        assertEquals(
            listOf(Composition.PRIMITIVE_THUD, Composition.PRIMITIVE_TICK),
            plan.primitives.map { it.id },
        )
    }

    @Test
    fun placed_onApi30_skipsTheThudComposition_whichNeeds31() {
        assertEquals(
            HapticPlan.Predefined(VibrationEffect.EFFECT_HEAVY_CLICK),
            resolve(HapticRecipes.placed, 30, view = false),
        )
    }

    @Test
    fun placed_onApi29_withView_usesThePredefinedEffect_beforeTheLegacyConstant() {
        assertEquals(
            HapticPlan.Predefined(VibrationEffect.EFFECT_HEAVY_CLICK),
            resolve(HapticRecipes.placed, 29),
        )
    }

    @Test
    fun placed_onApi29_withoutPermission_fallsBackToTheLegacyViewConstant() {
        assertEquals(
            HapticPlan.View(HapticFeedbackConstants.LONG_PRESS),
            resolve(HapticRecipes.placed, 29, vibrate = false),
        )
    }

    @Test
    fun arEvents_onApi24_withoutView_neverBuzz() {
        for (event in ARHapticEvent.entries) {
            assertEquals("$event", HapticPlan.None, resolve(HapticRecipes.of(event), 24, view = false))
            assertEquals("$event", HapticPlan.None, resolve(HapticRecipes.of(event), 26, view = false))
        }
    }

    @Test
    fun arEvents_onApi24_withView_allPlayAViewConstant() {
        for (event in ARHapticEvent.entries) {
            val plan = resolve(HapticRecipes.of(event), 24, vibrate = false)
            if (event == ARHapticEvent.Selected) {
                // CONTEXT_CLICK is API 23: still a view constant on 24.
                assertEquals(HapticPlan.View(HapticFeedbackConstants.CONTEXT_CLICK), plan)
            } else {
                assertTrue("$event → $plan", plan is HapticPlan.View)
            }
        }
    }

    @Test
    fun scaleSnapped_usesSegmentTick_onApi34_andAClickComposition_below() {
        assertEquals(HapticPlan.View(HapticFeedbackConstants.SEGMENT_TICK), resolve(HapticRecipes.scaleSnapped, 34))
        val plan = resolve(HapticRecipes.scaleSnapped, 33) as HapticPlan.Composed
        assertEquals(Composition.PRIMITIVE_CLICK, plan.primitives.single().id)
        assertEquals(
            HapticPlan.View(HapticFeedbackConstants.CLOCK_TICK),
            resolve(HapticRecipes.scaleSnapped, 28),
        )
    }

    @Test
    fun trackingLost_prefersItsComposition_overTheRejectConstant() {
        assertTrue(resolve(HapticRecipes.trackingLost, 34) is HapticPlan.Composed)
        assertEquals(
            HapticPlan.Predefined(VibrationEffect.EFFECT_DOUBLE_CLICK),
            resolve(HapticRecipes.trackingLost, 34, primitives = false),
        )
        assertEquals(
            HapticPlan.View(HapticFeedbackConstants.REJECT),
            resolve(HapticRecipes.trackingLost, 34, vibrate = false),
        )
    }

    @Test
    fun selection_usesSegmentFrequentTick_onApi34_andClockTick_below() {
        assertEquals(
            HapticPlan.View(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK),
            resolve(HapticRecipes.selection, 34),
        )
        assertEquals(HapticPlan.View(HapticFeedbackConstants.CLOCK_TICK), resolve(HapticRecipes.selection, 29))
    }

    @Test
    fun unsupportedPrimitives_fallToPredefined() {
        assertEquals(
            HapticPlan.Predefined(VibrationEffect.EFFECT_TICK),
            resolve(HapticRecipes.limitReached, 34, view = false, primitives = false),
        )
    }

    @Test
    fun presets_belowApi29_withoutView_keepTheirLegacyVibration() {
        assertEquals(
            HapticPlan.Legacy(LegacyVibration.OneShot(20)),
            resolve(HapticRecipes.medium, 24, view = false),
        )
        assertEquals(HapticPlan.None, resolve(HapticRecipes.medium, 24, view = false, vibrate = false))
    }

    // ── Table invariants (Android composition guidance) ─────────────────────────────────────

    private val allRecipes: List<HapticRecipe> =
        HapticPreset.entries.map(HapticRecipes::of) + ARHapticEvent.entries.map(HapticRecipes::of)

    @Test
    fun compositions_useGuidanceScales_andAtLeast50msGaps() {
        for (recipe in allRecipes) {
            recipe.primitives.forEachIndexed { i, p ->
                assertTrue("scale ${p.scale}", p.scale in setOf(0.5f, 0.7f, 1.0f))
                if (i > 0) assertTrue("gap ${p.delayMs}", p.delayMs >= 50)
            }
        }
    }

    @Test
    fun api31Primitives_areTaggedApi31() {
        val api31 = setOf(Composition.PRIMITIVE_THUD, Composition.PRIMITIVE_LOW_TICK, Composition.PRIMITIVE_SPIN)
        for (p in allRecipes.flatMap { it.primitives }) {
            assertEquals("primitive ${p.id}", if (p.id in api31) 31 else 30, p.minSdk)
        }
    }

    @Test
    fun viewConstants_carryTheirPlatformApiLevel() {
        val expected = mapOf(
            HapticFeedbackConstants.CONFIRM to 30,
            HapticFeedbackConstants.REJECT to 30,
            HapticFeedbackConstants.SEGMENT_TICK to 34,
            HapticFeedbackConstants.SEGMENT_FREQUENT_TICK to 34,
            HapticFeedbackConstants.CONTEXT_CLICK to 23,
        )
        for (c in allRecipes.flatMap { it.preferredView + it.fallbackView }) {
            expected[c.id]?.let { assertEquals("constant ${c.id}", it, c.minSdk) }
        }
    }

    @Test
    fun arEvents_haveNoLegacyBuzz() {
        for (event in ARHapticEvent.entries) assertNull("$event", HapticRecipes.of(event).legacy)
    }

    // ── AndroidSceneViewHaptic wiring ──────────────────────────────────────────────────────

    @Test
    fun viewAnsweringFalse_isNotRetriedOnTheVibrator() {
        // Before API 33, performHapticFeedback returns false when Touch feedback is off.
        val engine = RecordingHapticEngine(34, primitivesSupported = true)
        val view = RecordingViewPerformer(answer = false)
        val haptic = AndroidSceneViewHaptic(engine = engine, hasVibratePermission = true, view = view)
        haptic.play(ARHapticEvent.Placed)
        assertEquals(listOf(HapticFeedbackConstants.CONFIRM), view.constants)
        assertEquals(emptyList<HapticCall>(), engine.calls)
    }

    @Test
    fun withoutPermission_viewTierStillPlays() {
        val engine = RecordingHapticEngine(34, primitivesSupported = true)
        val view = RecordingViewPerformer()
        val haptic = AndroidSceneViewHaptic(engine = engine, hasVibratePermission = false, view = view)
        haptic.play(ARHapticEvent.TrackingLost)
        haptic.medium()
        assertEquals(
            listOf(HapticFeedbackConstants.REJECT, HapticFeedbackConstants.VIRTUAL_KEY),
            view.constants,
        )
        assertEquals(emptyList<HapticCall>(), engine.calls)
    }

    @Test
    fun touchFeedbackOff_belowApi33_onlyTheViewIsAsked() {
        val engine = RecordingHapticEngine(31, primitivesSupported = true, touchFeedbackEnabled = false)
        val view = RecordingViewPerformer(answer = false)
        val haptic = AndroidSceneViewHaptic(engine = engine, hasVibratePermission = true, view = view)
        for (event in ARHapticEvent.entries) haptic.play(event)
        assertEquals(emptyList<HapticCall>(), engine.calls)
    }

    @Test
    fun customImplementation_mapsEventsToPresets() {
        val played = mutableListOf<String>()
        val custom = object : SceneViewHaptic {
            override fun light() { played += "light" }
            override fun medium() { played += "medium" }
            override fun heavy() { played += "heavy" }
            override fun success() { played += "success" }
            override fun warning() { played += "warning" }
            override fun error() { played += "error" }
            override fun selection() { played += "selection" }
            override fun continuous(intensity: Float, durationMs: Long) = Unit
            override fun pattern(events: List<HapticEvent>) = Unit
            override fun cancel() = Unit
        }
        ARHapticEvent.entries.forEach(custom::play)
        assertEquals(
            listOf("medium", "selection", "selection", "light", "light", "warning", "success", "warning"),
            played,
        )
    }
}
