package io.github.sceneview.demo.sketchfab

import io.github.sceneview.demo.AssetSourceState
import java.io.File

/**
 * The one rule every demo uses to decide what its asset-source pill says.
 *
 * The pill must report what was RESOLVED, never what was CONFIGURED. "An API key is
 * configured" says nothing about whether the download succeeded: every failure path in
 * [SketchfabAssetResolver.resolve] — no network, aeroplane mode, a stale or 4xx-rejected
 * key, the WAF, a bounds-drifted asset, exhausted retries — ends at the bundled fallback,
 * so a keyed build renders the offline stand-in under whatever the pill claims. That is
 * measured, not theoretical: on the QA emulator (2026-07-28, key configured) all four
 * Multi-Model slots staged out of `cache/sketchfab/fallback/`, the download endpoint
 * answering 429 (#2933).
 *
 * The rule lived inline at four call sites, expressed three different ways, and was fixed
 * one site at a time — Multi-Model (#2933/#2934), then Gallery (#2936/#2938), then the two
 * AR demos (#2953). Extracting it is what makes it *testable*: the inline copies sat inside
 * `@Composable` bodies where no unit test could reach them, which is why three consecutive
 * fixes shipped with nothing pinning the rule. **All four call sites now route through
 * here** (#2989) — do not add a fifth inline copy, call this instead.
 *
 * ### Why the key survives at all
 *
 * While the demo is not yet `loaded` there may be nothing to ask, and the key is then the
 * best available signal: with none we already know where this ends, with one the download is
 * genuinely in flight. It is read on that not-yet-loaded path only, and it cannot overrule a
 * measurement — [SketchfabAssetResolver.resolve] short-circuits to `fallbackBundle` when the
 * key is null, so any file that exists in a keyless build is a fallback and the measured
 * branch has already claimed it before the key is reached.
 */
internal object AssetSourceProbe {

    /**
     * The pill for a demo showing a SINGLE streamed slot.
     *
     * @param resolvedFile the file the resolver handed back, or `null` while it is still
     *   working — the only thing worth measuring.
     * @param hasApiKey whether a Sketchfab key is configured. Read only when [loaded] is
     *   `false` — NOT when [resolvedFile] is `null`. The two coincide for AR Placement, which
     *   defines `loaded` as "the file arrived", but not for a caller that defines it as "the
     *   model parsed": there, a resolved-but-still-parsing streamed file reaches the key
     *   branch. That is harmless (a resolved *fallback* is claimed by the measured branch
     *   first, whatever `loaded` says), but do not restate this gate as a test on
     *   [resolvedFile] — see the class KDoc.
     * @param loaded whether the demo has finished doing what it considers "loaded". Each
     *   caller owns that definition — Gallery waits for the parsed `ModelInstance` so the
     *   chip and the centre `LoadingScrim` stay in lockstep (#1465), AR Placement waits only
     *   for the file, since a tap places whatever has resolved.
     */
    fun of(resolvedFile: File?, hasApiKey: Boolean, loaded: Boolean): AssetSourceState =
        ofAll(listOf(resolvedFile), hasApiKey, loaded)

    /**
     * The pill for a demo showing SEVERAL streamed slots at once.
     *
     * A WHOLE-SCENE verdict: one fallen-back slot out of four reads "Offline model" for all
     * of them, and the pill never says which slot swapped. Pessimistic on purpose — of the
     * two imprecise answers, the optimistic one is the one that misleads. It is also a
     * MOVING verdict during load, since [loaded] and the fallback probe watch different
     * signals: a slot that falls back last flips the pill Streaming → Offline after the fact.
     *
     * @param resolvedFiles one entry per streamed slot; `null` for a slot still resolving.
     *   Slots the demo renders straight from `assets/` have no origin question to answer and
     *   must not be passed in.
     */
    fun ofAll(
        resolvedFiles: List<File?>,
        hasApiKey: Boolean,
        loaded: Boolean,
    ): AssetSourceState = when {
        // MEASURED. Beats every other branch, including a `loaded` demo with a key.
        resolvedFiles.any { it != null && SketchfabAssetResolver.isBundledFallback(it) } ->
            AssetSourceState.Bundled
        // Nothing to measure yet — the pre-resolve guess, and the only place the key is read.
        !loaded -> if (hasApiKey) AssetSourceState.Streaming else AssetSourceState.Bundled
        // Everything resolved, nothing came from `fallback/`: genuinely streamed.
        else -> AssetSourceState.Streamed
    }
}
