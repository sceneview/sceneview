# Ask in AR live handwriting

## Architecture in ten lines

1. `StreamingAnswerProvider` emits `Text`, `Done`, or `Failed` through a cold Flow.
2. The existing scripted answers remain the default whenever the proxy URL is empty.
3. `ScriptedStreamingAnswerProvider` emits the same answer at 180 ms per word.
4. `ProxyStreamingAnswerProvider` posts question/context with the public client tag.
5. OkHttp reads SSE on `Dispatchers.IO`; cancellation aborts headers and body reads.
6. The Worker owns the Anthropic secret, calls Haiku 5.5 and forwards text deltas.
7. Fallback starts after 2.5 s without text after upload, or an early failure.
8. `IncrementalHandwriting` releases complete words and calls the existing layout.
9. Ink positions, stroke IDs, timing, framing bounds and scale stay stable as words arrive.
10. `playAskStream` collects and animates concurrently; `Done` flushes text and adds doodles.

## Status and files

These are additive integration pieces. **The existing screen is deliberately not
wired**, to avoid conflicting with the parallel demo branch. No APK was installed
or visually validated, and no Worker was deployed. Apply the following changes in
the branch that owns `AskInArDemo.kt`, then build/install and inspect it on a phone.

New Android source files under `demos/internal/`:

- `StreamingAnswerProvider.kt`: chunks, scripted adapter, timeout/failure fallback.
- `ProxyStreamingAnswerProvider.kt`: cancellable SSE transport using existing OkHttp.
- `AskSseParser.kt`: incremental CR/LF, multiline data and keep-alive parsing.
- `IncrementalHandwriting.kt`: append-only layout and `AskStreamingInk` replacement state.
- `AskStreamingPlayback.kt`: frame clock and concurrent collection.
- `AskArLiveConfig.kt`: optional resource URL, debug intent override and provider selection.
- `AskFrameCapture.kt`: synchronous YUV plane copy, then background JPEG encoding.
- `AskLiveContext.kt`: question-time facts, pure plane classification and ARCore adapter.

Also added: `ask-ar-live.init.gradle`, `res/values/ask_ar_live.xml`, seven streaming/context/capture `Ask*Test.kt`
files, `tools/ask-ar-proxy/` and a changelog fragment. No SDK public API changes.

## Wiring diff for the screen owner

The diff targets the original prototype. Keep the existing stage/AR placement
block between the two hunks in `answer()`. Existing `AnswerInk`/`InkTube` needs no
change: each incremental snapshot has the **same reserved bounds**, so its current
centering transform remains constant. Do not later replace those bounds with the
extent of the currently visible ink, or recompute blockScale per delta.

```diff
-import io.github.sceneview.demo.demos.internal.AnswerProvider
-import io.github.sceneview.demo.demos.internal.ScriptedAnswerProvider
+import io.github.sceneview.demo.demos.internal.AskArLiveConfig
+import io.github.sceneview.demo.demos.internal.IncrementalHandwriting
+import io.github.sceneview.demo.demos.internal.StreamingAnswerProvider
+import io.github.sceneview.demo.demos.internal.playAskStream

 // In AskInArDemo(), after the inspection-mode early return:
-    AskInArScene(onBack, ScriptedAnswerProvider)
+    val activity = LocalActivity.current
+    val context = LocalContext.current
+    val proxyUrl = AskArLiveConfig.proxyUrl(context, activity?.intent)
+    val provider = remember(proxyUrl) { AskArLiveConfig.provider(proxyUrl) }
+    AskInArScene(onBack, provider)

-private fun AskInArScene(onBack: () -> Unit, provider: AnswerProvider) {
+private fun AskInArScene(onBack: () -> Unit, provider: StreamingAnswerProvider) {

     suspend fun answer(question: String) {
         state.phase = AskPhase.Thinking
         state.level = 0f
-        val reply = provider.answer(question, AskContext(distanceAheadMeters = state.distanceAhead))
-        delay(THINK_MILLIS)
-        state.handwriting = HandwritingLayout.layout(reply.text, reply.doodle)
+        val context = AskContext(distanceAheadMeters = state.distanceAhead)
+        state.handwriting = IncrementalHandwriting().snapshot()
         // Keep the existing if (stage) { ... } else { ... } placement block here.
         // Its fitScale() now uses the fixed four-line frame, once per answer.
         state.elapsed = 0f
         state.phase = AskPhase.Writing
-        val total = state.handwriting?.totalSeconds ?: 0f
-        val start = withFrameNanos { it }
-        while (state.elapsed < total) {
-            withFrameNanos { now -> state.elapsed = (now - start) / NANOS_PER_SECOND }
-        }
+        val failure = playAskStream(provider, question, context, onFrame = { ink, elapsed ->
+            state.handwriting = ink
+            state.elapsed = elapsed
+        })
+        if (failure != null) state.notice = R.string.demo_ask_ar_live_unavailable
         state.phase = AskPhase.Done
     }
```

`LocalActivity`, `LocalContext` and `remember` already exist in the prototype imports.
Remove imports/constants made unused by the replacement (`withFrameNanos`,
`THINK_MILLIS`, `NANOS_PER_SECOND`) if the parallel branch does not use them elsewhere.
Keep `HandwritingLayout` for placement `fitScale()` and the rendering KDoc.
Keep the existing `flow` job cancellation and `state.clearAnswer()` on new questions,
clear, microphone actions and disposal; cancellation propagates through playback,
the fallback producer and the network socket.

`Failed` ends an attempt. If a replacement `Text` follows, `AskStreamingInk` clears
the prior ink and starts the script at the current frame time. This can visibly
replace an early partial reply; it never writes a script onto that reply. A failure
after 2.5 s with text already received is terminal: retain completed ink, leave any
partial word hidden and show the notice. A failed attempt never produces a doodle.
The startup window begins only after OkHttp writes and flushes the request body.
No startup timeout is imposed on the rest of a healthy stream; socket idle timeout
is 5 s, connect timeout 2 s and total network-call timeout 20 s.

Words wait for whitespace or sentence punctuation; apostrophes and hyphens stay
inside words, and a period after a digit waits one character to distinguish decimals.
`Done` releases the final word. Asterisks never become ink; the final emphasis phrase
adds a doodle on each line it intersects. Layout stays left aligned within a fixed
four-line envelope instead of recentering every partial answer. An unusually long
word/answer can overflow that envelope; the Worker prompt asks for at most 14 words.
Existing strokes and timings are reused, including when the model pauses. Per-word
timing uses the existing nominal handwriting speed rather than imposing the old
whole-answer minimum duration on every word.

## Pass 2 wiring: one real snapshot per live question

Apply these additions together with the pass 1 diff above. These are exact insertion
points in the original `AskInArDemo.kt`, not edits made on this branch. Keep the
existing `AskPlacement.pose` and `visibleSize` calls for ink placement only: their
synthetic fallback distance is never a measurement. `askLiveContextFromFrame` uses
the same screen-centre `hitTest` as `distanceAhead`, but returns the actual hit and
its plane from the question frame. It uses all tracked, unsubsumed planes to find
the lowest upward plane; extents are detected bounds, not complete room dimensions.

Add these imports (all helpers are in `demos.internal`, distinct from the existing
`demo.ai.AskFrameCapture` used by Point and Ask):

```kotlin
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.view.Surface
import androidx.compose.ui.platform.LocalView
import io.github.sceneview.demo.demos.internal.AskFrameCapture
import io.github.sceneview.demo.demos.internal.AskLiveContext
import io.github.sceneview.demo.demos.internal.AskYuvFrame
import io.github.sceneview.demo.demos.internal.askCameraRotationDegrees
import io.github.sceneview.demo.demos.internal.askLiveContextFromFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
```

Pass the live-mode flag when choosing the provider, including in any stage caller:

```diff
-    AskInArScene(onBack, provider)
+    AskInArScene(onBack, provider, liveEnabled = proxyUrl.isNotBlank())

-private fun AskInArScene(onBack: () -> Unit, provider: StreamingAnswerProvider) {
+private fun AskInArScene(
+    onBack: () -> Unit,
+    provider: StreamingAnswerProvider,
+    liveEnabled: Boolean,
+) {
```

Immediately after `val scope = rememberCoroutineScope()` in `AskInArScene`, add:

```kotlin
val displayView = LocalView.current
val cameraManager = remember(context) { context.getSystemService(CameraManager::class.java) }
var captureRequest by remember {
    mutableStateOf<CompletableDeferred<Triple<AskLiveContext, AskYuvFrame?, Int>>?>(null)
}
```

In `answer()`, replace the pass 1 `val context = AskContext(...)` line with this
block, immediately after `state.level = 0f` and before placement/writing. There is
no extra frame capture in `simulate()`, listening callbacks or every-frame polling.
The existing outer `flow` cancellation cancels this wait on clear/new question:

```kotlin
val snapshot = if (liveEnabled && !stage) {
    val request = CompletableDeferred<Triple<AskLiveContext, AskYuvFrame?, Int>>()
    captureRequest = request
    try {
        withTimeoutOrNull(750L) { request.await() }
    } finally {
        if (captureRequest === request) captureRequest = null
        request.cancel()
    }
} else null
// No tracked frame within the wait: send time/locale only, never a stale distance.
val facts = snapshot?.first ?: AskLiveContext()
val image = snapshot?.second?.let { copied ->
    try {
        AskFrameCapture.jpeg(copied, snapshot.third)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null // An unavailable frame must not prevent a text/measurement answer.
    }
}
val liveContext = facts.copy(image = image)
val answerContext = AskContext(
    distanceAheadMeters = if (liveEnabled) liveContext.distanceAheadMeters else state.distanceAhead,
)
```

Inside `onSessionUpdated`, immediately after `if (tracking) {` and **before** the
existing `if (state.frameCount++ % DISTANCE_EVERY_FRAMES == 0)` block, insert:

```kotlin
captureRequest?.let { request ->
    captureRequest = null // Consume once, even if no camera image is available.
    if (request.isActive) {
        val facts = askLiveContextFromFrame(
            frame,
            session.getAllTrackables(Plane::class.java),
            currentViewSize.width,
            currentViewSize.height,
        )
        val displayDegrees = when (displayView.display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val sensorDegrees = cameraManager.getCameraCharacteristics(session.cameraConfig.cameraId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val rotation = askCameraRotationDegrees(sensorDegrees, displayDegrees)
        // Acquire, copy all strided planes and close the Image right HERE.
        // Never launch a coroutine holding frame or Image, or retain either one.
        val copied = try { AskFrameCapture.copy(frame) } catch (_: Exception) { null }
        request.complete(Triple(facts, copied, rotation))
    }
}
```

`Plane` is already imported by the screen. `sensorDegrees` is for the rear ARCore
camera; `rotation` is clockwise degrees from sensor image to display orientation,
not a `Surface.ROTATION_*` enum. `AskFrameCapture.jpeg` dispatches conversion,
rotation, downscaling (long side ≤768) and quality-70 JPEG to `Dispatchers.Default`.
No ARCore image survives the callback. `NotYetAvailableException` yields no image;
measurement/time facts can still be sent. Stage mode sends no synthetic room facts
or stage backdrop image. Empty proxy URL never requests/copies a frame or uploads.

Finally replace the pass 1 playback call with:

```kotlin
val failure = playAskStream(
    provider = provider,
    question = question,
    context = answerContext, // Script fallback receives the same measured distance.
    liveContext = liveContext.takeIf { liveEnabled }, // Optional JPEG, never Frame or Image.
    onFrame = { ink, elapsed ->
        state.handwriting = ink
        state.elapsed = elapsed
    },
)
if (failure != null) state.notice = R.string.demo_ask_ar_live_unavailable
```

`StreamingAnswerProvider` retains the `AskContext` overload for pass 1/script
callers. Live overloads preserve the full snapshot through the fallback wrapper;
the script adapter consumes only distance. The proxy signals upload completion
from `RequestBody.writeTo`, after flushing the body; only then does the 2.5 s
first-text window begin. The 20 s network deadline still bounds stalled uploads.
The numeric-facts rule is in the model prompt; raw streaming does not guarantee
model compliance. The existing scripted fallback retains its offline examples.

## What is real

| Fact | Source | When absent / limits |
|---|---|---|
| Distance ahead | ARCore first centre-ray hit in the question callback | No tracking, zero viewport or no hit; never `AskPlacement`'s fallback |
| Surface kind | Hit plane type; lowest tracked upward plane → floor, higher → table | Non-plane hit, subsumed/untracked plane or hit outside polygon; classification is an estimate |
| Width / height | Hit plane `extentX` / `extentZ` in metres | No valid plane or nonpositive extent; detected bounds only, horizontal “height” is plane depth |
| Camera height | Camera world Y minus lowest tracked upward plane Y | No floor, invalid pose, or camera below estimated floor |
| Local time | `ZonedDateTime.now()`, ISO offset datetime | Sent by the phone; depends on its clock/time zone |
| Locale | `Locale.getDefault().toLanguageTag()` | Sent by the phone; question language controls the answer |
| Camera frame | ARCore YUV image copied once for this live question | No tracked frame, `NotYetAvailableException` or conversion failure; JPEG ≤768 px, quality 70 |
| City / country | Cloudflare `request.cf` approximate IP location | Cloudflare has no value; VPN/mobile gateways can differ from phone location |
| Weather | Open-Meteo current conditions at IP coordinates rounded to 0.1°, cached 10 min | Unrelated question, missing coordinates, malformed result, error or 800 ms timeout; outdoor current conditions, not forecast |

The phone sends question, available facts and one optional frame only in live mode.
The Worker sends these to Anthropic. Open-Meteo receives rounded IP coordinates
only on relevant questions. Neither question, image nor coordinates are logged by
the Worker; normalized weather alone is cached. No GPS fix is collected.

## What Thomas runs

1. Follow [Worker deployment](../../tools/ask-ar-proxy/README.md): `npm i`,
   `npx wrangler login`, `npx wrangler secret put ANTHROPIC_API_KEY`,
   `npx wrangler deploy`. The secret is `<set with wrangler secret put>` only.
2. Give the resulting HTTPS origin to the screen owner and apply the wiring above.
3. Supply the URL using either method below, build/install and inspect the demo.

### Gradle property, empty by default

From the repository root:

```sh
./gradlew -I samples/android-demo/ask-ar-live.init.gradle \
  -PaskArProxyUrl=https://ask-ar-proxy.<your-subdomain>.workers.dev \
  :samples:android-demo:assembleDebug
```

The opt-in init file reads `askArProxyUrl` and defines an Android string resource.
It also enables AGP's `resValues` feature for this build. It does not edit the build
file or generate a BuildConfig field. With the init file and no property, the URL
is empty; without the init file the resource is absent and the reader returns empty.
**`-PaskArProxyUrl` alone has no effect; include `-I`.** This is a public endpoint URL,
never an Anthropic key. An invalid configured URL falls back to scripted mode.

### Debug intent, no build configuration needed

After wiring and installing a debug APK:

```sh
adb shell am force-stop io.github.sceneview.demo
adb shell am start -W -n io.github.sceneview.demo/.MainActivity \
  -a android.intent.action.VIEW -d 'sceneview://demo/ask-ar' \
  --es ask_proxy 'https://ask-ar-proxy.<your-subdomain>.workers.dev' \
  --es ask_q 'How far is that wall?'
```

Use the installed package suffix if building a QA variant. A fresh activity launch
ensures the extra is read; the snippet does not subscribe to new intents while an
existing screen is open. An explicit empty `--es ask_proxy ''` selects the script
even if the APK has a URL resource. Release builds ignore intent overrides.

## Verification

Observed on 2026-11-25:

- Requested Android task: `BUILD SUCCESSFUL in 2m 5s`, then `BUILD SUCCESSFUL in 15s`
  after the final JPEG-size guard; **110 tests, 0 failures,
  0 errors, 0 skipped**, including **12 new tests** in `AskLiveContextTest`,
  `AskLiveFrameCaptureTest` and `AskLiveUploadTest`. Existing project deprecation
  warnings appeared; no warnings named the new helper sources.
- `./gradlew --stop`: `1 Daemon stopped`.
- Worker: `Test Files 2 passed (2)` / `Tests 35 passed (35)`, with SDK and weather
  mocked; `npm run typecheck` exited 0.
- The first `npm i` could not write the default npm cache; retrying with
  `--cache /tmp/ask-ar-npm-cache` succeeded (`found 0 vulnerabilities`).
- Scope and whitespace checks pass. No existing screen files were modified.
- `node_modules` was removed after testing. Staging/commit is blocked by the
  sandbox: Git cannot create the external worktree `index.lock` (`Operation not
  permitted`). All pass 2 files remain uncommitted on `feat/ask-in-ar-live`.

 Tests use mocked model/weather
services and local HTTP streams, never a real key. No Worker deployment, actual
model call, screen integration, APK installation or visual AR check is part of
this additive branch; the screen owner still needs to apply both wiring sections.

```sh
# Check at least 8 GiB free first:
df -h /
GRADLE_USER_HOME="$HOME/.gradle-codex" \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew :samples:android-demo:testDebugUnitTest --tests '*Ask*'
GRADLE_USER_HOME="$HOME/.gradle-codex" ./gradlew --stop
cd tools/ask-ar-proxy
npm i --cache /tmp/ask-ar-npm-cache
npm test
npm run typecheck
rm -rf node_modules
```

After wiring, inspect scripted mode, live word arrival, stable ink, cancellation,
a slow image upload, missing image/floor, a weather timeout and final doodles on the
actual device. A green unit suite is not visual AR validation.
