#!/usr/bin/env bash
#
# check-deprecated-api.sh — block commits that reintroduce deprecated
# SceneView API names (Scene{}, ARScene{}) outside the historical whitelist.
#
# The rename from Scene/ARScene to SceneView/ARSceneView landed in v3.6
# (commit e6a26a06) for cross-platform consistency with SceneViewSwift.
# The old names are kept as @Deprecated aliases but must NOT appear in
# new code, KDoc, docs, or MCP-generated templates, otherwise AI clients
# reading the codebase will generate code against the deprecated API.
#
# This check runs on staged files (before commit) and scans for the
# deprecated patterns. It excludes files that must legitimately reference
# the old names: migration guides, changelog, the deprecated alias
# definitions themselves, the Filament framework class, SwiftUI's
# unrelated `Scene` protocol, and the MCP validators/tests that intentionally
# pattern-match the old name as a detector.
#
# Usage:
#   bash .claude/scripts/check-deprecated-api.sh          # check staged files
#   bash .claude/scripts/check-deprecated-api.sh --all    # check entire tree
#
# Exit code: 0 if clean, 1 if violations found.

set -u

ROOT=$(git rev-parse --show-toplevel 2>/dev/null)
if [ -z "$ROOT" ]; then
  echo "Not inside a git repo." >&2
  exit 0
fi
cd "$ROOT"

# ─── Files that may legitimately mention Scene{}/ARScene{} ──────────────────
#
# These are either historical docs (migration guides, changelog, roadmap),
# the deprecated alias definitions themselves, KDoc that references the
# Filament framework `Scene` class (not the composable), SwiftUI's own
# `Scene` protocol, or validator files that pattern-match Scene{} as a
# detector.

is_whitelisted() {
  case "$1" in
    # Historical migration / changelog docs
    MIGRATION.md | CHANGELOG.md | ROADMAP.md) return 0 ;;
    docs/docs/migration.md | docs/docs/migration-v4.md) return 0 ;;
    docs/docs/changelog.md | docs/docs/comparison.md) return 0 ;;
    docs/archive/v3.6.0-roadmap.md | docs/ios-swift-package-design.md) return 0 ;;
    # SceneView agent skill migration guide — same role as the public
    # docs/docs/migration.md: necessarily quotes the deprecated names.
    agents/sceneview/references/migration.md) return 0 ;;
    # nodes.md line 149 legitimately mentions the Filament class Scene;
    # we allow the whole file since the composable refs were already fixed
    docs/docs/nodes.md) return 0 ;;

    # cheatsheet-ios.md is the Android-vs-iOS migration mapping table — by
    # definition each row lists the deprecated Android API on the LHS so the
    # iOS-curious reader can find the equivalent on the RHS (#1138, #1136).
    docs/docs/cheatsheet-ios.md) return 0 ;;

    # Sceneform → SceneView positioning text (#1736 / PR #1742). These four
    # AI-first surfaces explain the rename mapping (`ArFragment` → `ARScene { }`,
    # `ModelRenderable` → `rememberModelInstance`, …) so AI clients can guide
    # Sceneform users to SceneView. The deprecated names appear on the LHS of
    # the mapping by construction — they are the SEO/AI-recommendation value,
    # not new code references.
    README.md) return 0 ;;
    docs/docs/index.md) return 0 ;;
    docs/docs/llms.txt) return 0 ;;
    llms.txt) return 0 ;;

    # PlacementScene — the Sceneform `ArFragment` parity bundle (#1765). Its
    # KDoc, demo prose, JVM test header and changelog fragment all reference
    # `ArFragment` purely as positioning text ("Sceneform `ArFragment` parity")
    # — the same SEO/AI-recommendation value as README.md/llms.txt above, not
    # new code that uses the deprecated Sceneform class. The composable itself
    # is `PlacementScene`, built on `ARSceneView`.
    arsceneview/src/main/java/io/github/sceneview/ar/PlacementScene.kt) return 0 ;;
    arsceneview/src/test/java/io/github/sceneview/ar/PlacementSceneHitFilterTest.kt) return 0 ;;
    samples/android-demo/src/main/java/io/github/sceneview/demo/demos/PlacementSceneDemo.kt) return 0 ;;
    changelog.d/1765-placement-scene.md) return 0 ;;

    # The deprecated alias definitions themselves
    sceneview/src/main/java/io/github/sceneview/Scene.kt) return 0 ;;
    arsceneview/src/main/java/io/github/sceneview/ar/ARScene.kt) return 0 ;;

    # Regression test that intentionally references the deprecated alias
    # signature by name (regex assertion on the ARScene.kt body that the
    # `fun ARScene(...)` forwarder still includes `fillLightNode`, per #1136).
    arsceneview/src/test/java/io/github/sceneview/ar/ARCompletenessDefaultsTest.kt) return 0 ;;

    # Filament framework class bindings
    sceneview-web/src/jsMain/kotlin/io/github/sceneview/web/bindings/Filament.kt) return 0 ;;

    # SwiftUI.Scene is a protocol unrelated to SceneView
    samples/ios-demo/SceneViewDemo/SceneViewDemoApp.swift) return 0 ;;
    SceneViewSwift/Examples/SceneViewDemo/SceneViewDemo/SceneViewDemoApp.swift) return 0 ;;
    # environment-lighting.md's visionOS snippet declares `var body: some Scene`
    # — that is SwiftUI's own `App.body` Scene protocol (WindowGroup /
    # ImmersiveSpace), not the deprecated SceneView `Scene{}` composable (#1235).
    samples/recipes/environment-lighting.md) return 0 ;;

    # CLAUDE.md describes the rename rule itself in prose
    # (e.g. "validator catches the `Scene { } → SceneView { }` rename") —
    # the file is metadata about the detector, not consumer-facing code.
    CLAUDE.md) return 0 ;;

    # Session notes, historical reports — not user-facing
    .claude/branding.md | .claude/handoff.md | .claude/website-review-report.md) return 0 ;;
    .claude/active-branches.md) return 0 ;;
    .claude/reports/*.md) return 0 ;;
    .claude/plans/*.md) return 0 ;;

    # The detector script documents the patterns it matches; the
    # consolidated ci.yml workflow file has explanatory header comments
    # that may contain "Scene{}/ARScene{}" as prose — since #1370 it
    # carries the repo-hygiene job that runs this detector, formerly
    # split across quality-gate.yml and pr-check.yml.
    .claude/scripts/check-deprecated-api.sh) return 0 ;;
    .github/workflows/ci.yml) return 0 ;;

    # Marketing drafts — intentionally reference third-party APIs (ArFragment etc.)
    marketing/*.md | marketing/**/*.md) return 0 ;;

    # Demo recordings — historical marketing artifacts
    mcp/demo/sceneview-3.0-linkedin-video.html) return 0 ;;
    mcp/demo/sceneview-mcp-linkedin-video.html) return 0 ;;
    mcp/demo/sceneview-mcp-linkedin-video-capture.html) return 0 ;;
    mcp/demo/record-terminal.sh) return 0 ;;
  esac

  # MCP validators + migrator + analyzer intentionally match the deprecated
  # patterns — the whole point of those files is to detect or rewrite them.
  case "$1" in
    mcp/src/validator.ts | mcp/src/validator.test.ts) return 0 ;;
    mcp/packages/interior/src/validator.ts | mcp/packages/interior/src/validator.test.ts) return 0 ;;
    mcp/packages/gaming/src/validator.ts | mcp/packages/gaming/src/validator.test.ts) return 0 ;;
    mcp/packages/healthcare/src/validator.ts | mcp/packages/healthcare/src/validator.test.ts) return 0 ;;
    mcp/packages/automotive/src/validator.ts | mcp/packages/automotive/src/validator.test.ts) return 0 ;;
    mcp-interior/src/validator.ts | mcp-interior/src/validator.test.ts) return 0 ;;
    mcp-gaming/src/validator.ts | mcp-gaming/src/validator.test.ts) return 0 ;;
    # Shared deprecated-api module + per-package copies from session 34a
    # validator-dedup refactor (commit df9e62c7).
    mcp/packages/shared/src/deprecated-api-check.ts) return 0 ;;
    mcp/packages/shared/src/deprecated-api-check.test.ts) return 0 ;;
    mcp/packages/shared/README.md) return 0 ;;
    mcp/packages/automotive/src/deprecated-api-check.ts) return 0 ;;
    mcp/packages/gaming/src/deprecated-api-check.ts) return 0 ;;
    mcp/packages/healthcare/src/deprecated-api-check.ts) return 0 ;;
    mcp/packages/interior/src/deprecated-api-check.ts) return 0 ;;
    # CI workflow that documents what this detector catches
    .github/workflows/quality-gate.yml) return 0 ;;
    # MCP tools that document or migrate away from deprecated APIs by
    # construction.
    mcp/src/analyze-project.ts) return 0 ;;
    mcp/src/migration.ts | mcp/src/migration.test.ts) return 0 ;;
    mcp/src/migrate-code.ts | mcp/src/migrate-code.test.ts) return 0 ;;
    mcp/src/debug-issue.ts) return 0 ;;
    mcp/src/explain-api.ts) return 0 ;;
    mcp/src/issues.ts) return 0 ;;
    mcp/src/artifact.ts) return 0 ;;
    mcp/src/tools/definitions.ts) return 0 ;;
    mcp/src/__fixtures__/*) return 0 ;;
    mcp/README.md) return 0 ;;
    # GPT knowledge base — migration guide for the GPT assistant
    gpt/knowledge-practices.md) return 0 ;;
    # Slash commands that teach users what NOT to do
    .claude/commands/review.md) return 0 ;;
  esac

  # Library public imperative APIs that legitimately wrap Filament low-level
  # calls — these are the canonical homes of Engine.create / loadModelAsync.
  case "$1" in
    sceneview/src/main/java/io/github/sceneview/SceneFactories.kt) return 0 ;;
    sceneview/src/main/java/io/github/sceneview/loaders/ModelLoader.kt) return 0 ;;
  esac

  # Generated / vendored
  case "$1" in
    */node_modules/*) return 0 ;;
    */build/*) return 0 ;;
    */dist/*) return 0 ;;
    */generated/*) return 0 ;;
  esac

  return 1
}

# ─── File extensions we scan ────────────────────────────────────────────────

is_scannable() {
  case "$1" in
    *.kt | *.java | *.swift | *.dart) return 0 ;;
    *.ts | *.tsx | *.js | *.jsx) return 0 ;;
    *.md | *.txt | *.html | *.yml | *.yaml | *.json) return 0 ;;
    *.svg) return 0 ;;
  esac
  return 1
}

# ─── Select files to scan ───────────────────────────────────────────────────

if [ "${1:-}" = "--all" ]; then
  FILES=$(git ls-files)
  MODE="all tracked files"
else
  FILES=$(git diff --cached --name-only --diff-filter=ACMR)
  MODE="staged files"
fi

if [ -z "$FILES" ]; then
  # Nothing staged — OK
  exit 0
fi

# ─── Scan ───────────────────────────────────────────────────────────────────
#
# Pattern: a word boundary followed by "Scene" or "ARScene", optional whitespace,
# then { or (. The negative lookbehind (?<![\w.]) is not portable across greps,
# so we use a pre-filter in awk instead.
#
# We need to skip:
# - SceneView, ARSceneView, SceneScope, ARSceneScope, SceneNodeManager, SceneRenderer,
#   SceneEnvironment, SceneEntities (substrings that start with "Scene")
# - XRScene, FilamentScene (substrings that end with "Scene")
# - SwiftUI.Scene (followed by a dot, not a brace)
# - Filament.Scene, rememberScene (same)

VIOLATIONS=0
VIOLATION_LINES=""

for file in $FILES; do
  [ -f "$file" ] || continue
  is_scannable "$file" || continue
  is_whitelisted "$file" && continue

  # Each awk pattern below is the "not preceded by a word char or dot" form
  # of a deprecated identifier, followed by the right-hand context that
  # disambiguates it from longer (valid) names like SceneView, ARSceneView,
  # SceneScope, XRScene, rememberScene, SwiftUI.Scene, Filament.Scene.
  matches=$(awk '
    {
      line = $0

      # 1. Scene{} / Scene() composable (renamed to SceneView in v3.6)
      if (match(line, /(^|[^a-zA-Z0-9_.])Scene[[:space:]]*[{(]/)) {
        print FILENAME ":" NR ": [Scene{}]    " line
      }
      # 2. ARScene{} / ARScene() composable (renamed to ARSceneView in v3.6)
      if (match(line, /(^|[^a-zA-Z0-9_.])ARScene[[:space:]]*[{(]/)) {
        print FILENAME ":" NR ": [ARScene{}]  " line
      }
      # 3. Sceneform framework imports — never valid in SceneView 3.x
      if (match(line, /import[[:space:]]+com\.google\.ar\.sceneform/)) {
        print FILENAME ":" NR ": [sceneform-import]  " line
      }
      # 4. ArFragment — Sceneform class, replaced by ARSceneView composable
      if (match(line, /(^|[^a-zA-Z0-9_.])ArFragment([^a-zA-Z0-9_]|$)/)) {
        print FILENAME ":" NR ": [ArFragment]  " line
      }
      # 5. ViewRenderable — replaced by ViewNode
      if (match(line, /(^|[^a-zA-Z0-9_.])ViewRenderable([^a-zA-Z0-9_]|$)/)) {
        print FILENAME ":" NR ": [ViewRenderable]  " line
      }
      # 6. ModelRenderable — replaced by ModelNode + ModelInstance
      if (match(line, /(^|[^a-zA-Z0-9_.])ModelRenderable([^a-zA-Z0-9_]|$)/)) {
        print FILENAME ":" NR ": [ModelRenderable]  " line
      }
      # 7. PlacementNode — removed in 3.0, use AnchorNode + HitResultNode
      if (match(line, /(^|[^a-zA-Z0-9_.])PlacementNode([^a-zA-Z0-9_]|$)/)) {
        print FILENAME ":" NR ": [PlacementNode]  " line
      }
      # 8. childNodes = rememberNodes { — pre-3.0 node declaration pattern
      if (match(line, /childNodes[[:space:]]*=[[:space:]]*rememberNodes/)) {
        print FILENAME ":" NR ": [childNodes=rememberNodes]  " line
      }
      # 9. Import of the deprecated composable alias (anchored end-of-line
      #    so .SceneView / .ARSceneView / .SceneScope imports do not match).
      if (match(line, /^[[:space:]]*import[[:space:]]+io\.github\.sceneview\.Scene[[:space:]]*$/)) {
        print FILENAME ":" NR ": [deprecated-scene-import]  " line
      }
      if (match(line, /^[[:space:]]*import[[:space:]]+io\.github\.sceneview\.ar\.ARScene[[:space:]]*$/)) {
        print FILENAME ":" NR ": [deprecated-arscene-import]  " line
      }
    }
  ' FILENAME="$file" "$file" 2>/dev/null)

  if [ -n "$matches" ]; then
    VIOLATIONS=$((VIOLATIONS + 1))
    VIOLATION_LINES="${VIOLATION_LINES}${matches}
"
  fi
done

if [ "$VIOLATIONS" -gt 0 ]; then
  echo ""
  echo "  ╭──────────────────────────────────────────────────────────────────╮"
  echo "  │  DEPRECATED API DETECTED                                         │"
  echo "  ├──────────────────────────────────────────────────────────────────┤"
  echo "  │  \`Scene { }\` and \`ARScene { }\` were renamed to \`SceneView { }\`   │"
  echo "  │  and \`ARSceneView { }\` in v3.6 (commit e6a26a06).                │"
  echo "  │                                                                  │"
  echo "  │  The old names are kept as @Deprecated aliases but must NOT     │"
  echo "  │  appear in new code, KDoc, or MCP-generated templates.          │"
  echo "  │                                                                  │"
  echo "  │  Fix:                                                            │"
  echo "  │    \`Scene(\`    ->  \`SceneView(\`                                  │"
  echo "  │    \`Scene {\`    ->  \`SceneView {\`                                │"
  echo "  │    \`ARScene(\`  ->  \`ARSceneView(\`                                │"
  echo "  │    \`ARScene {\`  ->  \`ARSceneView {\`                              │"
  echo "  │                                                                  │"
  echo "  │  If the reference is historical (migration doc, changelog,      │"
  echo "  │  deprecated alias body, Filament framework class, SwiftUI's     │"
  echo "  │  own Scene protocol, or a validator pattern), add the file to   │"
  echo "  │  the whitelist in .claude/scripts/check-deprecated-api.sh.       │"
  echo "  ╰──────────────────────────────────────────────────────────────────╯"
  echo ""
  echo "  $MODE with deprecated refs ($VIOLATIONS file$([ "$VIOLATIONS" -gt 1 ] && echo 's')):"
  echo ""
  printf "%s" "$VIOLATION_LINES" | while IFS= read -r line; do
    [ -z "$line" ] && continue
    echo "    $line"
  done
  echo ""
  exit 1
fi

exit 0
