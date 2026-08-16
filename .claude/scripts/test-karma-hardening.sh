#!/usr/bin/env bash
#
# test-karma-hardening.sh — hermetic self-test for the Karma hardening gate.
#
# The gate exists because a Kotlin/JS browser module shipped with no Karma
# hardening at all and its blocking CI leg died five times with no evidence
# (#3192 workstream 1). A gate for that class has to be pinned in BOTH
# directions: one that only ever says no blocks a legitimate tree, and one that
# only ever says yes is decoration. Every case below therefore has its opposite.
#
# Fixtures are built under a scratch --root, never against the real tree: a
# suite that can only assert the PASSING direction on the live repo proves
# nothing about the failing one.
#
# Every case here was mutation-verified — the guarded line was broken and the
# case confirmed to go red. Case 8 exists only because that pass caught it
# asserting nothing.

set -uo pipefail
ROOT="$(git rev-parse --show-toplevel)"
CHECK="$ROOT/.claude/scripts/check-karma-hardening.sh"
PASS=0; FAIL=0
ok()  { printf '  ✓ %s\n' "$1"; PASS=$((PASS+1)); }
bad() { printf '  ✗ %s\n' "$1"; FAIL=$((FAIL+1)); }

echo "test-karma-hardening.sh"

SCRATCH="$(mktemp -d)"
trap 'rm -rf "$SCRATCH"' EXIT

# A fixture tree with TWO Kotlin/JS browser modules, both hardened and both
# calling useChromeHeadless() — i.e. the shape the real repo is in. Individual
# cases then break exactly one thing. $1 = fixture name.
fixture() {
    local dir="$SCRATCH/$1"; rm -rf "$dir"
    local m
    for m in mod-a mod-b; do
        mkdir -p "$dir/$m/karma.config.d"
        # The real build-script shape: a nested js target whose browser block
        # configures the test task.
        cat > "$dir/$m/build.gradle.kts" <<'GRADLE'
kotlin {
    js(IR) {
        browser {
            testTask {
                inputs.dir(layout.projectDirectory.dir("karma.config.d"))
                    .withPropertyName("karmaConfigD")
                useKarma {
                    useChromeHeadless()
                }
            }
        }
        binaries.library()
    }
}
GRADLE
        # Carries every token the gate asserts by name.
        cat > "$dir/$m/karma.config.d/browser-hardening.js" <<'KARMA'
(function (config) {
    config.customLaunchers = {
        Hardened: {
            base: "ChromeHeadless",
            flags: ["--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu"],
        },
    };
    config.browsers = ["Hardened"];
    config.captureTimeout = 120000;
    config.browserNoActivityTimeout = 300000;
    config.browserDisconnectTimeout = 30000;
    config.browserDisconnectTolerance = 2;
    config.retryLimit = 2;
    config.client = { captureConsole: true };
    config.browserConsoleLogOptions = { terminal: true };
})(config);
KARMA
    done
    printf '%s' "$dir"
}

run() { bash "$CHECK" --root "$1" 2>&1; }

# 1. A conforming tree passes. Without this every case below could be green
#    because the gate always says no.
D="$(fixture clean)"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -eq 0 ]]; } \
  && ok "two hardened, identical modules → allowed" \
  || bad "a conforming tree must pass (rc=$RC): $OUT"

# 2. A Kotlin/JS browser module with no hardening file at all fails, and is
#    NAMED. This is precisely the state sceneview-core was in.
D="$(fixture missing)"
rm -rf "$D/mod-b/karma.config.d"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -eq 1 ]] && grep -q 'mod-b' <<<"$OUT"; } \
  && ok "a JS browser module with no hardening → fail, module named" \
  || bad "an unhardened module must fail and be named (rc=$RC): $OUT"

# 3. Two copies that have drifted fail. Duplication is the chosen design
#    (a shared file would need a fragile relative require from the GENERATED
#    karma.conf.js), so drift is the risk that design creates and this is the
#    case that pays for it.
D="$(fixture drift)"
printf '\n// a local edit that was never copied across\n' >> "$D/mod-b/karma.config.d/browser-hardening.js"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -eq 1 ]] && grep -qi 'drifted' <<<"$OUT"; } \
  && ok "drifted copies → fail, drift named" \
  || bad "drifted copies must fail (rc=$RC): $OUT"

# 4. Losing a launcher flag fails. The file being PRESENT is not the property
#    that matters — `--no-sandbox` is, and a present-but-gutted file would pass
#    a mere existence check while restoring the exact failure mode.
D="$(fixture noflag)"
for m in mod-a mod-b; do
    sed -i.bak 's/"--no-sandbox", //' "$D/$m/karma.config.d/browser-hardening.js"
    rm -f "$D/$m/karma.config.d/browser-hardening.js.bak"
done
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -eq 1 ]] && grep -q -- '--no-sandbox' <<<"$OUT"; } \
  && ok "a dropped launcher flag → fail, flag named" \
  || bad "a dropped --no-sandbox must fail (rc=$RC): $OUT"

# 5. Same for a dropped timeout. The flags and the timeouts guard two different
#    failure modes — a launch that never happens, and a launch that hangs — and
#    case 4 alone would stay green while every timeout went back to default.
D="$(fixture notimeout)"
for m in mod-a mod-b; do
    sed -i.bak '/browserNoActivityTimeout/d' "$D/$m/karma.config.d/browser-hardening.js"
    rm -f "$D/$m/karma.config.d/browser-hardening.js.bak"
done
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -eq 1 ]] && grep -q 'browserNoActivityTimeout' <<<"$OUT"; } \
  && ok "a dropped timeout → fail, setting named" \
  || bad "a dropped browserNoActivityTimeout must fail (rc=$RC): $OUT"

# 6. Same for the evidence capture. This is the half of the fix that makes the
#    NEXT crash diagnosable, and it is the half a reader is most likely to
#    delete as noise, so it is asserted separately.
D="$(fixture nolog)"
for m in mod-a mod-b; do
    sed -i.bak '/browserConsoleLogOptions/d' "$D/$m/karma.config.d/browser-hardening.js"
    rm -f "$D/$m/karma.config.d/browser-hardening.js.bak"
done
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -eq 1 ]] && grep -q 'browserConsoleLogOptions' <<<"$OUT"; } \
  && ok "dropped browser-console capture → fail, setting named" \
  || bad "dropped browserConsoleLogOptions must fail (rc=$RC): $OUT"

# 7. A module that does not call useChromeHeadless() fails: `base:
#    "ChromeHeadless"` resolves through karma-chrome-launcher, and it is that
#    call which puts the launcher on the test runtime. Without it the hardened
#    launcher is a name pointing at nothing — the config is present, identical,
#    complete, and inert.
D="$(fixture nolauncher)"
sed -i.bak '/useChromeHeadless/d' "$D/mod-b/build.gradle.kts"
rm -f "$D/mod-b/build.gradle.kts.bak"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -eq 1 ]] && grep -q 'useChromeHeadless' <<<"$OUT"; } \
  && ok "a module without useChromeHeadless() → fail" \
  || bad "a missing useChromeHeadless() must fail (rc=$RC): $OUT"

# 8. A module with NO Kotlin/JS target is out of scope and must not be demanded
#    to carry a Karma config.
#
#    The Android fixture depends on `androidx.browser` — Custom Tabs, a real and
#    common Android dependency — ON PURPOSE. Discovery is two conditions, a
#    Kotlin/JS target AND a `browser` mention, and a fixture that satisfies
#    NEITHER pins only whichever one runs first. Mutation-verified: with a plain
#    `android { }` fixture, deleting the `js(` condition from the gate broke no
#    case at all — the case read as coverage of a line nothing tested. Spelling
#    `browser` here is what makes the `js(` condition the only thing keeping
#    this module out of scope, so removing that line now turns this case red.
D="$(fixture outofscope)"
mkdir -p "$D/mod-android"
cat > "$D/mod-android/build.gradle.kts" <<'GRADLE'
android {
    defaultConfig { minSdk = 24 }
}
dependencies {
    implementation("androidx.browser:browser:1.8.0")
}
GRADLE
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -eq 0 ]]; } \
  && ok "a non-JS module → out of scope, not demanded to harden" \
  || bad "a module with no js target must be ignored (rc=$RC): $OUT"

# 9. A discovery that found NOTHING must refuse, never pass. A gate whose scope
#    silently narrows to zero reports success on a tree it never looked at —
#    the same shape as `tiers.ts` parsing to zero and `.cursorrules` sitting
#    outside every glob.
D="$(fixture nomodules)"
rm -f "$D"/mod-a/build.gradle.kts "$D"/mod-b/build.gradle.kts
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -eq 2 ]] && grep -qi 'refusing\|0 Kotlin/JS' <<<"$OUT"; } \
  && ok "zero discovered modules → exit 2 (refuses), not a silent pass" \
  || bad "an empty discovery must exit 2, never 0 (rc=$RC): $OUT"

# 10. A js target WITHOUT a browser block is out of scope too — a nodejs-only
#     Kotlin/JS module has no jsBrowserTest task and no Karma to harden.
D="$(fixture nodejsonly)"
cat > "$D/mod-b/build.gradle.kts" <<'GRADLE'
kotlin {
    js(IR) {
        nodejs()
        binaries.library()
    }
}
GRADLE
rm -rf "$D/mod-b/karma.config.d"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -eq 0 ]]; } \
  && ok "a nodejs-only JS module → out of scope" \
  || bad "a js target with no browser block must be ignored (rc=$RC): $OUT"

# 11. A module that does not declare `karma.config.d` as a task input fails.
#     This is the case cases 1–10 cannot see: the file is present, identical and
#     complete, and Gradle still never re-runs the task when it changes. Measured
#     on the real repo before the fix — breaking `config.browsers` and re-running
#     gave `BUILD SUCCESSFUL` / `jsBrowserTest UP-TO-DATE` against a stale
#     generated karma.conf.js, while the identical break under `--rerun-tasks`
#     failed in 4 s. The blast radius is LOCAL, not CI: `jsBrowserTest` is not
#     cacheable, so no stale result crosses runs — but local Gradle is the only
#     place this config is ever verified, which is the whole reason #3192
#     workstream 1 could not be done from a cloud container.
D="$(fixture noinput)"
sed -i.bak '/inputs\.dir/d; /withPropertyName/d' "$D/mod-b/build.gradle.kts"
rm -f "$D/mod-b/build.gradle.kts.bak"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -eq 1 ]] && grep -q 'karma.config.d as a task input' <<<"$OUT"; } \
  && ok "a module not declaring karma.config.d as an input → fail" \
  || bad "an undeclared karma.config.d input must fail (rc=$RC): $OUT"

echo "  → $PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]]
