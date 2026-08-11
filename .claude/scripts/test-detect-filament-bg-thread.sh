#!/usr/bin/env bash
#
# test-detect-filament-bg-thread.sh — self-test for
# lib/detect-filament-bg-thread.py, the Filament-on-a-background-thread check
# behind `quality-gate.sh`'s "No Filament calls on background thread".
#
# The inline grep this replaced failed in both directions at once: it reported
# THREADING VIOLATION on every clean local diff (`grep -c` prints `0` *and*
# exits 1, so `|| echo "0"` made the value `0\n0` and the comparison died), and
# it matched nothing on the multi-line `withContext(Dispatchers.IO) { … }` shape
# the rule exists to catch. A gate that can only ever fail gets read as noise
# and then ignored, which is how the real violation would have walked through.
#
# So both directions are pinned here by fixture: silence on clean diffs,
# non-zero on each violating shape. Feeds diffs on stdin — no git repo, no
# network, no Gradle.
#
# Exit: 0 all scenarios hold · 1 a scenario failed.

set -uo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
DETECT="$REPO_ROOT/.claude/scripts/lib/detect-filament-bg-thread.py"

if [ ! -f "$DETECT" ]; then
    echo "[FAIL] detector not found at $DETECT"
    exit 1
fi

FAILURES=0

scenario() { # $1 = name, $2 = expected exit (0|1), stdin = the diff
    local name="$1" expected="$2" out actual
    out=$(python3 "$DETECT" 2>&1) && actual=0 || actual=$?
    if [ "$actual" -eq "$expected" ]; then
        echo "[PASS] $name"
    else
        echo "[FAIL] $name — expected exit $expected, got $actual"
        while IFS= read -r line; do echo "       | $line"; done <<< "$out"
        FAILURES=$((FAILURES + 1))
    fi
}

# ── 1. The regression that produced the false red ───────────────────────────
# A .kt diff with no dispatcher at all. The old inline check called this a
# THREADING VIOLATION.
scenario "clean Kotlin diff is silent" 0 <<'DIFF'
diff --git a/sceneview/src/main/java/io/github/sceneview/SceneView.kt b/sceneview/src/main/java/io/github/sceneview/SceneView.kt
--- a/sceneview/src/main/java/io/github/sceneview/SceneView.kt
+++ b/sceneview/src/main/java/io/github/sceneview/SceneView.kt
@@ -660,7 +660,9 @@ fun SceneView(
             while (true) {
-                if (!isResumed.get()) { delay(100); continue }
+                when {
+                    !isResumed.get() -> delay(100)
+                    else -> withFrameNanos { sceneRenderer.renderFrame(it) {} }
+                }
             }
DIFF

scenario "empty diff is silent" 0 </dev/null

# ── 2. The shape the old same-line grep could not see ───────────────────────
scenario "multi-line withContext(Dispatchers.IO) block is caught" 1 <<'DIFF'
diff --git a/sceneview/src/main/java/io/github/sceneview/Loader.kt b/sceneview/src/main/java/io/github/sceneview/Loader.kt
--- a/sceneview/src/main/java/io/github/sceneview/Loader.kt
+++ b/sceneview/src/main/java/io/github/sceneview/Loader.kt
@@ -10,6 +10,10 @@ class Loader {
+    suspend fun load(path: String) = withContext(Dispatchers.IO) {
+        val bytes = context.assets.open(path).readBytes()
+        modelLoader.createModelInstance(bytes)
+    }
DIFF

scenario "same-line dispatcher + factory call is caught" 1 <<'DIFF'
diff --git a/sceneview/src/main/java/io/github/sceneview/Loader.kt b/sceneview/src/main/java/io/github/sceneview/Loader.kt
--- a/sceneview/src/main/java/io/github/sceneview/Loader.kt
+++ b/sceneview/src/main/java/io/github/sceneview/Loader.kt
@@ -10,5 +10,6 @@ class Loader {
+    fun load() = launch(Dispatchers.Default) { materialLoader.createMaterial(bytes) }
DIFF

# ── 3. Shapes that must NOT fire ────────────────────────────────────────────
scenario "background block without a Filament call is silent" 0 <<'DIFF'
diff --git a/sceneview/src/main/java/io/github/sceneview/Loader.kt b/sceneview/src/main/java/io/github/sceneview/Loader.kt
--- a/sceneview/src/main/java/io/github/sceneview/Loader.kt
+++ b/sceneview/src/main/java/io/github/sceneview/Loader.kt
@@ -10,6 +10,9 @@ class Loader {
+    suspend fun readBytes(path: String) = withContext(Dispatchers.IO) {
+        context.assets.open(path).readBytes()
+    }
DIFF

scenario "hop back to Dispatchers.Main is the correct shape, not a violation" 0 <<'DIFF'
diff --git a/sceneview/src/main/java/io/github/sceneview/Loader.kt b/sceneview/src/main/java/io/github/sceneview/Loader.kt
--- a/sceneview/src/main/java/io/github/sceneview/Loader.kt
+++ b/sceneview/src/main/java/io/github/sceneview/Loader.kt
@@ -10,6 +10,11 @@ class Loader {
+    suspend fun load(path: String) {
+        val bytes = withContext(Dispatchers.IO) { context.assets.open(path).readBytes() }
+        withContext(Dispatchers.Main) {
+            modelLoader.createModelInstance(bytes)
+        }
+    }
DIFF

scenario "REMOVING a violation does not fail the gate" 0 <<'DIFF'
diff --git a/sceneview/src/main/java/io/github/sceneview/Loader.kt b/sceneview/src/main/java/io/github/sceneview/Loader.kt
--- a/sceneview/src/main/java/io/github/sceneview/Loader.kt
+++ b/sceneview/src/main/java/io/github/sceneview/Loader.kt
@@ -10,4 +10,1 @@ class Loader {
-    suspend fun load(path: String) = withContext(Dispatchers.IO) {
-        modelLoader.createModelInstance(bytes)
-    }
DIFF

scenario "a Filament call far below an unrelated dispatcher is silent" 0 <<'DIFF'
diff --git a/sceneview/src/main/java/io/github/sceneview/Loader.kt b/sceneview/src/main/java/io/github/sceneview/Loader.kt
--- a/sceneview/src/main/java/io/github/sceneview/Loader.kt
+++ b/sceneview/src/main/java/io/github/sceneview/Loader.kt
@@ -10,2 +10,20 @@ class Loader {
+    suspend fun readBytes(path: String) = withContext(Dispatchers.IO) {
+        context.assets.open(path).readBytes()
+    }
+
+    // 12+ added lines of unrelated code between the dispatcher and the call.
+    private val a = 1
+    private val b = 2
+    private val c = 3
+    private val d = 4
+    private val e = 5
+    private val f = 6
+    private val g = 7
+    private val h = 8
+    private val i = 9
+    private val j = 10
+
+    fun createOnMainThread(bytes: ByteArray) {
+        modelLoader.createModelInstance(bytes)
+    }
DIFF

scenario "a non-Kotlin file is out of scope" 0 <<'DIFF'
diff --git a/docs/threading.md b/docs/threading.md
--- a/docs/threading.md
+++ b/docs/threading.md
@@ -1,2 +1,3 @@
+Never write `withContext(Dispatchers.IO) { modelLoader.createModelInstance(bytes) }`.
DIFF

# ── 4. The reported location must be usable ─────────────────────────────────
LOCATION=$(python3 "$DETECT" <<'DIFF' 2>&1 || true
diff --git a/sceneview/src/main/java/io/github/sceneview/Loader.kt b/sceneview/src/main/java/io/github/sceneview/Loader.kt
--- a/sceneview/src/main/java/io/github/sceneview/Loader.kt
+++ b/sceneview/src/main/java/io/github/sceneview/Loader.kt
@@ -10,6 +100,10 @@ class Loader {
+    suspend fun load(path: String) = withContext(Dispatchers.IO) {
+        val bytes = context.assets.open(path).readBytes()
+        modelLoader.createModelInstance(bytes)
+    }
DIFF
)
if grep -q "sceneview/src/main/java/io/github/sceneview/Loader.kt:102:" <<< "$LOCATION"; then
    echo "[PASS] reports path and post-image line number (Loader.kt:102)"
else
    echo "[FAIL] reports path and post-image line number — got:"
    while IFS= read -r line; do echo "       | $line"; done <<< "$LOCATION"
    FAILURES=$((FAILURES + 1))
fi

# ── Bad usage prints usage text, not a blank line ───────────────────────────
# The detector used to print `__doc__.strip().splitlines()[-4]` here, which is
# the BLANK line above the `stdout:` paragraph: the only path whose entire job
# is to say how to call the script said nothing at all. Assert the exit code AND
# that the text names the invocation — an empty stderr must not read as a pass.
USAGE_OUT=$(python3 "$DETECT" one two 2>&1 >/dev/null) && USAGE_EXIT=0 || USAGE_EXIT=$?
if [ "$USAGE_EXIT" -eq 64 ] && grep -q 'detect-filament-bg-thread.py' <<< "$USAGE_OUT"; then
    echo "[PASS] bad usage exits 64 with usage text on stderr"
else
    echo "[FAIL] bad usage exits 64 with usage text on stderr — exit $USAGE_EXIT, stderr:"
    while IFS= read -r line; do echo "       | $line"; done <<< "$USAGE_OUT"
    FAILURES=$((FAILURES + 1))
fi

echo ""
if [ "$FAILURES" -eq 0 ]; then
    echo "detect-filament-bg-thread.py: all scenarios hold"
    exit 0
fi
echo "detect-filament-bg-thread.py: $FAILURES scenario(s) failed"
exit 1
