# Ask in AR live handwriting

## Architecture in ten lines

1. `StreamingAnswerProvider` emits `Text`, `Done`, or `Failed` through a cold Flow.
2. The existing scripted answers remain the default whenever the proxy URL is empty.
3. `ScriptedStreamingAnswerProvider` emits the same answer at 180 ms per word.
4. `ProxyStreamingAnswerProvider` posts question/context with the public client tag.
5. OkHttp reads SSE on `Dispatchers.IO`; cancellation aborts headers and body reads.
6. The Worker owns the Anthropic secret, calls Haiku 5.5 and forwards text deltas.
7. Fallback starts after 2.5 s without text, or a failure in the first 2.5 s.
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

Also added: `ask-ar-live.init.gradle`, `res/values/ask_ar_live.xml`, four `Ask*Test.kt`
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
Keep the existing `flowJob` cancellation and `state.clearAnswer()` on new questions,
clear, microphone actions and disposal; cancellation propagates through playback,
the fallback producer and the network socket.

`Failed` ends an attempt. If a replacement `Text` follows, `AskStreamingInk` clears
the prior ink and starts the script at the current frame time. This can visibly
replace an early partial reply; it never writes a script onto that reply. A failure
after 2.5 s with text already received is terminal: retain completed ink, leave any
partial word hidden and show the notice. A failed attempt never produces a doodle.
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

Observed on 2026-11-25 in this worktree:

- With `ANDROID_HOME` pointing at the installed SDK and a writable, isolated
  `GRADLE_USER_HOME`, the test command with the
  init file reported `BUILD SUCCESSFUL in 24s`: **98 tests, 0 failures, 0 errors,
  0 skipped**, including all 19 new tests. No new-source Kotlin warnings appeared.
- `npm test`: `Test Files 1 passed (1)` and `Tests 12 passed (12)`.
- `npm run typecheck`: exit 0.
- `generateDebugResValues` with an example `-PaskArProxyUrl` reported
  `BUILD SUCCESSFUL in 11s`; the generated resource contained that exact origin.
- All 22 added files passed a trailing-whitespace check; existing tracked files
  remained unchanged. Full Android lint was not run with only about 1 GB free.
- Git staging was denied at the worktree's external `index.lock`; files remain
  uncommitted. Live-model, deployment, APK installation and visual AR checks remain
  unperformed until the screen owner applies the wiring and Thomas supplies the URL.

```sh
./gradlew :samples:android-demo:testDebugUnitTest --tests '*Ask*'
# Also verify the opt-in Gradle configuration:
./gradlew -I samples/android-demo/ask-ar-live.init.gradle \
  :samples:android-demo:testDebugUnitTest --tests '*Ask*'
cd tools/ask-ar-proxy
npm test
npm run typecheck
```

The tests use local streams and a mock SDK/server, never the real key. After wiring,
check scripted mode with an empty URL, live word arrival, stable ink while a later
line wraps, a slow/disconnected proxy, cancellation on clear/new question, and final
doodles on the actual device. A green unit suite is not visual AR validation.
