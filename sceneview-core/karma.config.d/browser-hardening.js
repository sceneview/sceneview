// Karma / ChromeHeadless hardening for a Kotlin/JS `jsBrowserTest` suite.
//
// WHY THIS EXISTS
// ---------------
// The blocking `Build web targets` leg went red five times on #3189 with the
// BROWSER PROCESS dying rather than a test failing:
//
//     > Task :sceneview-core:jsTest FAILED
//     > Failed to execute all tests:
//       :sceneview-core:jsBrowserTest: Test running process exited unexpectedly.
//
// Zero test results were produced — no class, no method, no assertion — and the
// ~4500-line job log carried no Karma and no Chrome output anywhere. Gradle
// reported the `jsBrowserTest` inputs BYTE-IDENTICAL between the branch and
// `main`, so branch content was ruled out mechanically rather than by
// inspection; the same signature then hit the unrelated
// `fix/3180-pipefail-epipe-membership` and went green on re-run with no change.
// Locally the same suite is 1104 tests / 0 failures across three runs.
//
// So the failure is a launch failure on a shared runner, and TWO things were
// wrong. This file fixes both:
//
//   1. NO HARDENING. `sceneview-core` was a bare `browser()` — no
//      `--no-sandbox`, no `--disable-dev-shm-usage`, no activity timeout.
//      Those are the flags a containerised Chrome dies without: the sandbox
//      needs kernel namespaces a CI container may not grant, and `/dev/shm` is
//      64 MB in a default container, which Chrome exhausts and then aborts.
//   2. NO EVIDENCE. A launch failure produced nothing to diagnose, so the next
//      occurrence starts the same investigation from zero. Browser console
//      output is now captured to the terminal AND to a file under `build/`,
//      which CI uploads on failure.
//
// WHAT THIS DELIBERATELY IS NOT
// -----------------------------
// It is not a test retry. `retryLimit` re-attempts browser CAPTURE only — that
// is Karma's own distinction between "the browser never came up" and "the suite
// ran and something failed". A retry of the suite itself would hide the flake
// this file exists to make visible, which is the `false green` class this repo
// treats as its most expensive bug.
//
// It is not a claim that the crash is now understood. The root cause was never
// observed, because nothing captured it. This makes the NEXT one diagnosable
// and makes the launch itself less likely to fail; it does not close the
// question. → issue #3192 workstream 1.
//
// KEEP IN SYNC: every Kotlin/JS browser module carries a BYTE-IDENTICAL copy of
// this file — `sceneview-core` and `sceneview-web` today. Both run on the same
// runner behind the same blocking leg, so hardening one and not the other just
// moves which module dies. `.claude/scripts/test-karma-hardening.sh` fails if
// the copies drift, or if a new Kotlin/JS browser module ships without one.
(function (config) {
    var path = require("path");

    // ── 1. A launcher that survives a CI container ──────────────────────────
    // `base: "ChromeHeadless"` requires karma-chrome-launcher, which the Kotlin
    // Gradle plugin installs for `useChromeHeadless()` — hence the matching
    // `testTask { useKarma { useChromeHeadless() } }` in build.gradle.kts.
    config.customLaunchers = config.customLaunchers || {};
    config.customLaunchers.SceneViewChromeHeadless = {
        base: "ChromeHeadless",
        flags: [
            // The Chrome sandbox needs user namespaces; CI containers routinely
            // run without them and Chrome exits before serving a single test.
            "--no-sandbox",
            "--disable-setuid-sandbox",
            // Default container /dev/shm is 64 MB. Chrome exhausts it and
            // aborts — the single most common "exited unexpectedly" on CI.
            "--disable-dev-shm-usage",
            // No GPU on the runner; the fallback path is a source of hangs and
            // these tests are pure logic (no WebGL context is created).
            "--disable-gpu",
            // Background throttling suspends timers in a headless tab, which
            // reads to Karma as "no activity" and trips the timeout below.
            "--disable-background-timer-throttling",
            "--disable-backgrounding-occluded-windows",
            "--disable-renderer-backgrounding",
        ],
    };
    config.browsers = ["SceneViewChromeHeadless"];

    // ── 2. Timeouts sized for a loaded shared runner ────────────────────────
    // Every default below is tuned for a developer laptop, not for a runner
    // executing several jobs at once. The observed crashes produced no output
    // at all, which is what a capture timeout looks like from the outside.
    config.captureTimeout = 120000; // default 60000 — browser must connect
    config.browserNoActivityTimeout = 300000; // default 30000 — suite is ~1100 tests
    config.browserDisconnectTimeout = 30000; // default 2000
    config.browserDisconnectTolerance = 2; // default 0 — reconnect instead of dying
    config.processKillTimeout = 30000; // default 2000 — let Chrome exit cleanly

    // Re-attempt CAPTURE only (see "WHAT THIS DELIBERATELY IS NOT" above).
    config.retryLimit = 2;

    // ── 3. Evidence, so the next crash is diagnosable ───────────────────────
    config.client = config.client || {};
    config.client.captureConsole = true;

    config.browserConsoleLogOptions = {
        level: "debug",
        format: "%b %T: %m",
        terminal: true,
        // Written next to the generated karma.conf.js, i.e. under build/ — CI
        // uploads it with `if: failure()`.
        path: path.resolve(__dirname, "karma-browser.log"),
    };
})(config);
