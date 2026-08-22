// Karma / ChromeHeadless hardening for a Kotlin/JS `jsBrowserTest` suite.
//
// The blocking `Build web targets` CI leg has died several times with the
// BROWSER process exiting rather than a test failing:
//
//     :sceneview-core:jsBrowserTest: Test running process exited unexpectedly.
//
// Zero test results, and no Karma or Chrome output anywhere in the job log —
// the same suite passes locally (1104 tests) and on re-run. This file does two
// things about that: it gives Chrome the flags a CI container needs to launch
// at all, and it captures the browser console so the next crash leaves
// evidence (CI uploads `karma-browser.log` on failure).
//
// It is NOT a test retry. `retryLimit` re-attempts browser CAPTURE only —
// Karma's own distinction between "the browser never came up" and "the suite
// ran and something failed". Retrying the suite would hide the flake this
// file exists to make visible.
//
// KEEP IN SYNC: `sceneview-core` and `sceneview-web` carry a byte-identical
// copy. Both run on the same runner behind the same blocking leg, so hardening
// one and not the other only moves which module dies. Issue #3192.
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
    // The defaults are tuned for a developer laptop, not a runner executing
    // several jobs at once. The observed crashes produced no output at all,
    // which is what a capture timeout looks like from the outside.
    config.captureTimeout = 120000; // default 60000 — browser must connect
    config.browserNoActivityTimeout = 300000; // default 30000 — suite is ~1100 tests
    config.browserDisconnectTimeout = 30000; // default 2000
    config.browserDisconnectTolerance = 2; // default 0 — reconnect instead of dying
    config.processKillTimeout = 30000; // default 2000 — let Chrome exit cleanly

    // Re-attempt CAPTURE only (see the header).
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
