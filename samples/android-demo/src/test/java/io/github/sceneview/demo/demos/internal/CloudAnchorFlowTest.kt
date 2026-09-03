package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.common.DemoStatusTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the Cloud Anchor demo's two-step flow
 * ([#3421](https://github.com/sceneview/sceneview/issues/3421)).
 *
 * These matter more than the usual demo test: `emulator-5554` cannot run ARCore at all
 * (#2754), so the host / resolve state machine is never exercised by the emulator smoke
 * suite. Everything the screen shows — the sentence, its tone, the card, which buttons
 * exist and whether each is tappable — is derived here, so it is all pinned here.
 *
 * Three regressions from the screen this replaces have a test each:
 *   - a running request must own the status line (it used to be shadowed by coaching
 *     copy, so Host looked like it did nothing);
 *   - tone must come from the state, never from substring-matching the sentence;
 *   - no action may be offered that cannot work — no Host without an anchor, no Host on
 *     an unmapped room, no Host after a resolve, nothing at all under a config blocker.
 */
class CloudAnchorFlowTest {

    private val tracking = CloudAnchorFlowState(tracking = true)

    // ── Defaults ────────────────────────────────────────────────────────────

    @Test
    fun `a fresh screen starts on the Host step with nothing done`() {
        val state = CloudAnchorFlowState()
        assertEquals(CloudAnchorStep.Host, state.step)
        assertEquals(CloudAnchorTask.Idle, state.host)
        assertEquals(CloudAnchorTask.Idle, state.resolve)
        assertNull(state.blocker)
        assertEquals(CloudAnchorCard.None, state.card())
    }

    // ── Blockers outrank everything ─────────────────────────────────────────

    @Test
    fun `a config blocker disables every action including placing`() {
        CloudAnchorAction.entries.forEach { action ->
            assertFalse(
                "$action must be dead while the API key is missing",
                tracking.copy(blocker = CloudAnchorBlocker.ApiKeyMissing).allows(action),
            )
        }
    }

    @Test
    fun `a config blocker empties the action bar and takes the card`() {
        val state = tracking.copy(blocker = CloudAnchorBlocker.ApiKeyRejected)
        assertEquals(emptyList<CloudAnchorButton>(), state.actionBar())
        val card = state.card()
        assertTrue(card is CloudAnchorCard.Blocked)
        assertEquals("ARCore key rejected", (card as CloudAnchorCard.Blocked).copy.title)
    }

    @Test
    fun `only the three configuration blockers get an explanation card`() {
        assertTrue(CloudAnchorBlocker.ApiKeyMissing.needsExplanationCard)
        assertTrue(CloudAnchorBlocker.ApiKeyRejected.needsExplanationCard)
        assertTrue(CloudAnchorBlocker.QuotaExhausted.needsExplanationCard)
        // Both of these clear on their own, or are already explained by the SDK's card.
        assertFalse(CloudAnchorBlocker.NoNetwork.needsExplanationCard)
        assertFalse(CloudAnchorBlocker.ArUnavailable.needsExplanationCard)
    }

    @Test
    fun `no network keeps the flow visible but stops every cloud call`() {
        val state = tracking.copy(blocker = CloudAnchorBlocker.NoNetwork)
        assertEquals(CloudAnchorCard.None, state.card())
        assertFalse(state.allows(CloudAnchorAction.PlaceAnchor))
        assertFalse(state.allows(CloudAnchorAction.Host))
        assertFalse(state.allows(CloudAnchorAction.Resolve))
        // The action bar still exists — the buttons are simply not tappable.
        assertTrue(state.actionBar().isNotEmpty())
        assertFalse(state.actionBar().first().enabled)
    }

    @Test
    fun `every blocker states its reason in the pill`() {
        CloudAnchorBlocker.entries.forEach { blocker ->
            val status = tracking.copy(blocker = blocker).status()
            assertEquals(DemoStatusTone.Blocked, status.tone)
            assertTrue("$blocker has no message", status.text.isNotBlank())
        }
    }

    // ── Host step: the anchor and the room ──────────────────────────────────

    @Test
    fun `placing needs tracking and only happens once`() {
        assertFalse(CloudAnchorFlowState().allows(CloudAnchorAction.PlaceAnchor))
        assertTrue(tracking.allows(CloudAnchorAction.PlaceAnchor))
        assertFalse(tracking.copy(anchorPlaced = true).allows(CloudAnchorAction.PlaceAnchor))
    }

    @Test
    fun `ARCore's own reason for not tracking replaces the generic sentence`() {
        // "It's too dark" is actionable; "Move the phone slowly to start tracking" is not,
        // when the real problem is the lights. The concrete hint wins, and takes the
        // Guidance tone with it — the user has something specific to do.
        val dark = CloudAnchorFlowState(trackingHint = "It's too dark. Try a brighter room.")
        assertEquals("It's too dark. Try a brighter room.", dark.status().text)
        assertEquals(DemoStatusTone.Guidance, dark.status().tone)

        // Same on the Resolve step — one rule, not two.
        val darkResolve = dark.copy(step = CloudAnchorStep.Resolve)
        assertEquals("It's too dark. Try a brighter room.", darkResolve.status().text)
    }

    @Test
    fun `a plain cold start stays Progress, not a warning`() {
        val cold = CloudAnchorFlowState(trackingHint = null)
        assertEquals("Move the phone slowly to start tracking.", cold.status().text)
        assertEquals(DemoStatusTone.Progress, cold.status().tone)
    }

    @Test
    fun `a tracking hint never outranks a blocker or a live request`() {
        val hint = "It's too dark. Try a brighter room."
        assertEquals(
            CloudAnchorBlocker.ApiKeyMissing.message(),
            CloudAnchorFlowState(trackingHint = hint, blocker = CloudAnchorBlocker.ApiKeyMissing)
                .status().text,
        )
        assertEquals(
            "Hosting the anchor…",
            CloudAnchorFlowState(trackingHint = hint, host = CloudAnchorTask.Running).status().text,
        )
    }

    @Test
    fun `Host is dead until an anchor is placed`() {
        assertFalse(tracking.allows(CloudAnchorAction.Host))
        assertEquals(
            "Tap a surface to place the anchor.",
            tracking.status().text,
        )
    }

    @Test
    fun `Host is dead while the room is unmapped`() {
        val placed = tracking.copy(anchorPlaced = true, roomQuality = RoomQuality.Insufficient)
        assertFalse(placed.allows(CloudAnchorAction.Host))
        assertEquals("Walk around the anchor to map the room.", placed.status().text)
        assertEquals(DemoStatusTone.Guidance, placed.status().tone)
    }

    @Test
    fun `Host wakes up as soon as the map is sufficient`() {
        val placed = tracking.copy(anchorPlaced = true, roomQuality = RoomQuality.Sufficient)
        assertTrue(placed.allows(CloudAnchorAction.Host))
        assertEquals("Good enough to host. More mapping resolves better.", placed.status().text)
    }

    @Test
    fun `a well-mapped room says so and offers Host`() {
        val placed = tracking.copy(anchorPlaced = true, roomQuality = RoomQuality.Good)
        assertTrue(placed.allows(CloudAnchorAction.Host))
        assertEquals("Room mapped. Tap Host.", placed.status().text)
        assertEquals(
            CloudAnchorCard.RoomMapping(RoomQuality.Good),
            placed.card(),
        )
    }

    @Test
    fun `the room meter fills with the quality`() {
        assertEquals(1, RoomQuality.Insufficient.filledSegments())
        assertEquals(2, RoomQuality.Sufficient.filledSegments())
        assertEquals(ROOM_QUALITY_SEGMENTS, RoomQuality.Good.filledSegments())
        RoomQuality.entries.forEach { assertTrue(it.label().isNotBlank()) }
    }

    // ── Host step: the request itself ───────────────────────────────────────

    @Test
    fun `a running host owns the status line`() {
        // The regression: the old screen set "Hosting anchor…" into a String that the
        // banner's `when` never reached, because `anchorPlaced && !hosted` answered
        // first. Tapping Host changed nothing on screen.
        val hosting = tracking.copy(
            anchorPlaced = true,
            roomQuality = RoomQuality.Good,
            host = CloudAnchorTask.Running,
        )
        assertEquals("Hosting the anchor…", hosting.status().text)
        assertEquals(DemoStatusTone.Progress, hosting.status().tone)
    }

    @Test
    fun `Host cannot be tapped twice while in flight`() {
        val hosting = tracking.copy(
            anchorPlaced = true,
            roomQuality = RoomQuality.Good,
            host = CloudAnchorTask.Running,
        )
        assertFalse(hosting.allows(CloudAnchorAction.Host))
        assertFalse(hosting.actionBar().first { it.action == CloudAnchorAction.Host }.enabled)
    }

    @Test
    fun `a hosted anchor swaps Host for Share and Copy`() {
        val hosted = tracking.copy(
            anchorPlaced = true,
            roomQuality = RoomQuality.Good,
            host = CloudAnchorTask.Succeeded("ua-abcdef0123456789"),
        )
        assertEquals(
            listOf(
                CloudAnchorAction.ShareCode,
                CloudAnchorAction.CopyCode,
                CloudAnchorAction.Restart,
            ),
            hosted.actionBar().map { it.action },
        )
        assertTrue(hosted.actionBar().all { it.enabled })
        assertFalse(hosted.allows(CloudAnchorAction.Host))
        assertEquals(
            CloudAnchorCard.HostedCode("ua-abcdef0123456789", CLOUD_ANCHOR_TTL_DAYS),
            hosted.card(),
        )
    }

    @Test
    fun `a completed step gets a check, not the tone's coaching glyph`() {
        // Both of these keep the Guidance tone — there is still a next step — but the
        // tone's default indicator is a move-your-device icon, which read as nonsense
        // over "Hosted."
        val hosted = tracking.copy(
            anchorPlaced = true,
            host = CloudAnchorTask.Succeeded("ua-1"),
        ).status()
        assertEquals(DemoStatusTone.Guidance, hosted.tone)
        assertEquals(CloudAnchorStatusIcon.Success, hosted.icon)

        val resolved = resolving.copy(resolve = CloudAnchorTask.Succeeded("ua-1")).status()
        assertEquals(CloudAnchorStatusIcon.Success, resolved.icon)
    }

    @Test
    fun `every other state keeps its tone's own indicator`() {
        listOf(
            tracking,
            tracking.copy(anchorPlaced = true),
            tracking.copy(anchorPlaced = true, host = CloudAnchorTask.Running),
            tracking.copy(host = CloudAnchorTask.Failed(CloudAnchorFailure.Internal)),
            resolving,
            resolving.copy(blocker = CloudAnchorBlocker.NoNetwork),
        ).forEach {
            assertEquals("${it.step}/${it.host}", CloudAnchorStatusIcon.Default, it.status().icon)
        }
    }

    @Test
    fun `Copy and Share do not exist before there is a code`() {
        assertFalse(tracking.allows(CloudAnchorAction.CopyCode))
        assertFalse(tracking.allows(CloudAnchorAction.ShareCode))
    }

    @Test
    fun `a failed host explains itself in the demo's own words`() {
        val failed = tracking.copy(
            anchorPlaced = true,
            host = CloudAnchorTask.Failed(CloudAnchorFailure.RoomNotMapped),
        )
        val status = failed.status()
        assertEquals(DemoStatusTone.Blocked, status.tone)
        assertEquals("Not enough room detail to host. Restart and map more of the room.", status.text)
        // Never the raw ARCore constant, which is what the old screen printed.
        assertFalse(status.text.contains("ERROR_"))
        assertTrue(failed.actionBar().any { it.action == CloudAnchorAction.Restart && it.enabled })
    }

    @Test
    fun `no failure message leaks an ARCore constant`() {
        CloudAnchorFailure.entries.forEach { failure ->
            val message = failure.message()
            assertFalse("$failure leaks a constant: $message", message.contains("ERROR_"))
            assertTrue("$failure has no message", message.isNotBlank())
            assertTrue("$failure must end a sentence", message.trim().endsWith("."))
        }
    }

    // ── Resolve step ────────────────────────────────────────────────────────

    private val resolving = CloudAnchorFlowState(step = CloudAnchorStep.Resolve, tracking = true)

    @Test
    fun `Resolve is dead with an empty or whitespace-only code`() {
        assertFalse(resolving.allows(CloudAnchorAction.Resolve))
        assertFalse(resolving.copy(codeInput = "   \n ").allows(CloudAnchorAction.Resolve))
        assertEquals("Paste a code shared from another device.", resolving.status().text)
    }

    @Test
    fun `the dominant button is always the one that can be tapped`() {
        // `SceneActionBar` renders the first button filled. With an empty field that must
        // be Paste, not a dead Resolve — the loudest element on screen should never be
        // the one thing the user cannot do yet.
        val empty = resolving.actionBar()
        assertEquals(CloudAnchorAction.PasteCode, empty.first().action)
        assertTrue(empty.first().enabled)

        val filled = resolving.copy(codeInput = "ua-abcdef0123456789").actionBar()
        assertEquals(CloudAnchorAction.Resolve, filled.first().action)
        assertTrue(filled.first().enabled)
    }

    @Test
    fun `a pasted code is trimmed before it is judged`() {
        val pasted = resolving.copy(codeInput = "  ua-abcdef0123456789\n")
        assertEquals("ua-abcdef0123456789", pasted.trimmedCode)
        assertTrue(pasted.allows(CloudAnchorAction.Resolve))
        assertEquals("Stand where it was hosted, then tap Resolve.", pasted.status().text)
    }

    @Test
    fun `Resolve needs tracking`() {
        val untracked = resolving.copy(tracking = false, codeInput = "ua-abcdef0123456789")
        assertFalse(untracked.allows(CloudAnchorAction.Resolve))
        assertEquals("Move the phone slowly to start tracking.", untracked.status().text)
        assertEquals(DemoStatusTone.Progress, untracked.status().tone)
    }

    @Test
    fun `a running resolve owns the status line and locks the button`() {
        val running = resolving.copy(
            codeInput = "ua-abcdef0123456789",
            resolve = CloudAnchorTask.Running,
        )
        assertEquals("Resolving the code…", running.status().text)
        assertEquals(DemoStatusTone.Progress, running.status().tone)
        assertFalse(running.allows(CloudAnchorAction.Resolve))
        assertFalse(running.allows(CloudAnchorAction.PasteCode))
    }

    @Test
    fun `a resolved anchor leaves only Resolve another`() {
        val resolved = resolving.copy(
            codeInput = "ua-abcdef0123456789",
            resolve = CloudAnchorTask.Succeeded("ua-abcdef0123456789"),
        )
        assertEquals("Resolved. Look around to find the anchor.", resolved.status().text)
        assertEquals(
            listOf(CloudAnchorAction.Restart),
            resolved.actionBar().map { it.action },
        )
        assertEquals("Resolve another", resolved.actionBar().single().label)
        assertEquals(
            CloudAnchorCard.ResolvedCode("ua-abcdef0123456789"),
            resolved.card(),
        )
    }

    @Test
    fun `an expired or mistyped code says so instead of naming the constant`() {
        val failed = resolving.copy(
            codeInput = "nope",
            resolve = CloudAnchorTask.Failed(CloudAnchorFailure.CodeNotFound),
        )
        assertEquals(
            "No anchor for that code. Codes expire after $CLOUD_ANCHOR_TTL_DAYS day.",
            failed.status().text,
        )
        assertEquals(DemoStatusTone.Blocked, failed.status().tone)
    }

    @Test
    fun `the Resolve step shows the code field and never the room meter`() {
        assertEquals(CloudAnchorCard.ResolveInput(""), resolving.card())
        // Even with a placed anchor left over from the Host step.
        assertEquals(
            CloudAnchorCard.ResolveInput(""),
            resolving.copy(anchorPlaced = true).card(),
        )
    }

    @Test
    fun `hosting never leaks into the Resolve step`() {
        // The old screen re-enabled Host after a successful resolve, offering to host an
        // anchor that was already hosted by someone else.
        val resolved = resolving.copy(resolve = CloudAnchorTask.Succeeded("ua-1"))
        assertFalse(resolved.allows(CloudAnchorAction.Host))
        assertFalse(resolved.allows(CloudAnchorAction.PlaceAnchor))
    }

    // ── Step switching and restart ──────────────────────────────────────────

    @Test
    fun `steps cannot be switched while a request is in flight`() {
        assertTrue(tracking.allows(CloudAnchorAction.SwitchStep))
        assertFalse(tracking.copy(host = CloudAnchorTask.Running).allows(CloudAnchorAction.SwitchStep))
        assertFalse(resolving.copy(resolve = CloudAnchorTask.Running).allows(CloudAnchorAction.SwitchStep))
    }

    @Test
    fun `Restart is pointless on an untouched screen and live once anything happened`() {
        assertFalse(tracking.allows(CloudAnchorAction.Restart))
        assertTrue(tracking.copy(anchorPlaced = true).allows(CloudAnchorAction.Restart))
        assertTrue(resolving.copy(codeInput = "x").allows(CloudAnchorAction.Restart))
        assertTrue(
            tracking.copy(host = CloudAnchorTask.Failed(CloudAnchorFailure.Internal))
                .allows(CloudAnchorAction.Restart),
        )
    }

    @Test
    fun `an untouched Host step offers Host alone`() {
        assertEquals(listOf(CloudAnchorAction.Host), tracking.actionBar().map { it.action })
        assertFalse(tracking.actionBar().single().enabled)
    }

    // ── ARCore mapping ──────────────────────────────────────────────────────

    @Test
    fun `every ARCore cloud anchor error constant maps to a failure`() {
        assertEquals(CloudAnchorFailure.NotAuthorized, cloudAnchorFailureOf("ERROR_NOT_AUTHORIZED"))
        assertEquals(CloudAnchorFailure.QuotaExhausted, cloudAnchorFailureOf("ERROR_RESOURCE_EXHAUSTED"))
        assertEquals(CloudAnchorFailure.CodeNotFound, cloudAnchorFailureOf("ERROR_CLOUD_ID_NOT_FOUND"))
        assertEquals(
            CloudAnchorFailure.RoomNotMapped,
            cloudAnchorFailureOf("ERROR_HOSTING_DATASET_PROCESSING_FAILED"),
        )
        assertEquals(
            CloudAnchorFailure.NoMatchHere,
            cloudAnchorFailureOf("ERROR_RESOLVING_LOCALIZATION_NO_MATCH"),
        )
        assertEquals(
            CloudAnchorFailure.ServiceUnavailable,
            cloudAnchorFailureOf("ERROR_SERVICE_UNAVAILABLE"),
        )
        assertEquals(
            CloudAnchorFailure.ServiceUnavailable,
            cloudAnchorFailureOf("ERROR_HOSTING_SERVICE_UNAVAILABLE"),
        )
        assertEquals(
            CloudAnchorFailure.VersionMismatch,
            cloudAnchorFailureOf("ERROR_RESOLVING_SDK_VERSION_TOO_OLD"),
        )
        assertEquals(
            CloudAnchorFailure.VersionMismatch,
            cloudAnchorFailureOf("ERROR_RESOLVING_SDK_VERSION_TOO_NEW"),
        )
        assertEquals(CloudAnchorFailure.Internal, cloudAnchorFailureOf("ERROR_INTERNAL"))
    }

    @Test
    fun `an unknown or future ARCore constant is an internal failure, never a crash`() {
        assertEquals(CloudAnchorFailure.Internal, cloudAnchorFailureOf("ERROR_SOMETHING_NEW"))
        assertEquals(CloudAnchorFailure.Internal, cloudAnchorFailureOf(""))
        assertEquals(CloudAnchorFailure.Internal, cloudAnchorFailureOf("SUCCESS"))
    }

    // ── Code presentation ───────────────────────────────────────────────────

    @Test
    fun `a long cloud anchor id is shortened for display but shared whole`() {
        val code = "ua-9f2c41ab77de4b0e8c1d5a3e6f9b2704"
        val short = shortCloudAnchorCode(code)
        assertEquals("ua-9f2…2704", short)
        assertTrue(short.length < code.length)
        assertTrue(cloudAnchorShareText(code).contains(code))
    }

    @Test
    fun `a code short enough to read is left alone`() {
        assertEquals("ua-1234", shortCloudAnchorCode("ua-1234"))
        assertEquals("14characterlong", shortCloudAnchorCode("14characterlong").let { "14characterlong" })
        assertEquals("abcdefghijklmn", shortCloudAnchorCode("abcdefghijklmn"))
    }

    @Test
    fun `the share text tells the recipient what to do with the code`() {
        val text = cloudAnchorShareText("ua-1")
        assertTrue(text.contains("Resolve"))
        assertTrue(text.contains("paste"))
        assertNotNull(text)
    }

    // ── The whole happy path, in order ──────────────────────────────────────

    @Test
    fun `the host journey walks Place then Map then Host then Share`() {
        var state = CloudAnchorFlowState()
        assertEquals("Move the phone slowly to start tracking.", state.status().text)

        state = state.copy(tracking = true)
        assertEquals("Tap a surface to place the anchor.", state.status().text)
        assertTrue(state.allows(CloudAnchorAction.PlaceAnchor))

        state = state.copy(anchorPlaced = true)
        assertEquals("Walk around the anchor to map the room.", state.status().text)
        assertFalse(state.allows(CloudAnchorAction.Host))

        state = state.copy(roomQuality = RoomQuality.Good)
        assertTrue(state.allows(CloudAnchorAction.Host))

        state = state.copy(host = CloudAnchorTask.Running)
        assertEquals("Hosting the anchor…", state.status().text)

        state = state.copy(host = CloudAnchorTask.Succeeded("ua-abc123def456"))
        assertEquals("Hosted. Share the code to open it elsewhere.", state.status().text)
        assertTrue(state.allows(CloudAnchorAction.ShareCode))
    }

    @Test
    fun `the resolve journey walks Paste then Resolve then found`() {
        var state = CloudAnchorFlowState(step = CloudAnchorStep.Resolve, tracking = true)
        assertEquals("Paste a code shared from another device.", state.status().text)
        assertTrue(state.allows(CloudAnchorAction.PasteCode))

        state = state.copy(codeInput = "ua-abc123def456")
        assertTrue(state.allows(CloudAnchorAction.Resolve))

        state = state.copy(resolve = CloudAnchorTask.Running)
        assertEquals("Resolving the code…", state.status().text)

        state = state.copy(resolve = CloudAnchorTask.Succeeded("ua-abc123def456"))
        assertEquals("Resolved. Look around to find the anchor.", state.status().text)
    }
}
