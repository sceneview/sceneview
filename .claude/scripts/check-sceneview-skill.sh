#!/usr/bin/env bash
# check-sceneview-skill.sh — Validate the SceneView agent skills content
# against the actual library source. Wired into the quality gate so that
# `agents/sceneview/`, `agents/sceneview-ios/` and `agents/sceneview-web/`
# cannot drift away from `sceneview/`, `arsceneview/`, `SceneViewSwift/`,
# `sceneview-web/`, and the platform demo apps.
#
# What it catches:
#   1. Hallucinated APIs — every identifier in a Kotlin code block of
#      `agents/sceneview/**/*.md` that matches one of the SceneView
#      composable shapes (`SomethingNode`, `rememberSomething`,
#      `materialLoader.createXxx`) must exist somewhere under
#      sceneview/, arsceneview/, sceneview-core/, samples/common/, or
#      llms.txt.
#   2. Dead demo refs — every `samples/android-demo/.../demos/*.kt`
#      filename mentioned in recipes.md must exist on disk.
#   3. YAML frontmatter — every SKILL.md (Android, iOS, web) must parse,
#      with `name`, `description`, `metadata.last-updated` fields present.
#   4. Staleness — `last-updated` must not be in the future and not
#      older than 180 days from today (warns).
#   5. iOS/web demo refs — every `*Demo.swift` / `*.kt` demo filename
#      cited in the iOS / web skills must exist on disk.
#
# Usage:
#   bash .claude/scripts/check-sceneview-skill.sh            # full check
#   bash .claude/scripts/check-sceneview-skill.sh --quiet    # only print failures
#
# Exit codes:
#   0  every check passed (warnings OK)
#   1  one or more hard failures
#   2  bad invocation / missing dependency (python3)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

QUIET=0
for arg in "$@"; do
  case "$arg" in
    --quiet) QUIET=1 ;;
    -h|--help) sed -n '2,28p' "$0"; exit 0 ;;
    *) echo "Unknown arg: $arg" >&2; exit 2 ;;
  esac
done

if ! command -v python3 >/dev/null 2>&1; then
  echo "[check-sceneview-skill] python3 required" >&2
  exit 2
fi

SKILL_DIR="agents/sceneview"
if [[ ! -d "$SKILL_DIR" ]]; then
  echo "[check-sceneview-skill] $SKILL_DIR not found — wrong worktree?" >&2
  exit 2
fi

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

FAILURES=0
WARNINGS=0

pass() {
  [[ "$QUIET" -eq 1 ]] && return 0
  printf "  ${GREEN}✓${NC} %s\n" "$1"
}
fail() {
  printf "  ${RED}✗${NC} %s\n" "$1" >&2
  FAILURES=$((FAILURES + 1))
}
warn() {
  printf "  ${YELLOW}!${NC} %s\n" "$1" >&2
  WARNINGS=$((WARNINGS + 1))
}
section() {
  [[ "$QUIET" -eq 1 ]] && return 0
  printf "\n${CYAN}-- %s --${NC}\n" "$1"
}

section "1. Frontmatter (all skills)"

# Validate every SceneView skill's SKILL.md frontmatter: the Android skill plus
# the iOS and web skills, if present. Each must carry name / description /
# metadata.last-updated, and last-updated must be sane.
for SKILL in agents/sceneview agents/sceneview-ios agents/sceneview-web; do
  [[ -f "$SKILL/SKILL.md" ]] || continue
  python3 - "$SKILL/SKILL.md" <<'PY' || FAILURES=$((FAILURES + 1))
import sys, re, datetime
path = sys.argv[1]
text = open(path, encoding="utf-8").read()
m = re.match(r"---\n(.*?)\n---\n", text, re.DOTALL)
if not m:
    print(f"  ✗ no YAML frontmatter delimited by --- in {path}")
    sys.exit(1)
fm = m.group(1)
required = ["name:", "description:", "metadata:"]
for key in required:
    if key not in fm:
        print(f"  ✗ {path}: frontmatter missing '{key}'")
        sys.exit(1)
last_updated_match = re.search(r"last-updated:\s*['\"]?(\d{4}-\d{2}-\d{2})", fm)
if not last_updated_match:
    print(f"  ✗ {path}: frontmatter missing metadata.last-updated (YYYY-MM-DD)")
    sys.exit(1)
last_updated = datetime.date.fromisoformat(last_updated_match.group(1))
today = datetime.date.today()
if last_updated > today:
    print(f"  ✗ {path}: last-updated {last_updated} is in the future")
    sys.exit(1)
age = (today - last_updated).days
if age > 180:
    print(f"  ! {path}: last-updated {last_updated} is {age} days old (consider refresh)")
print(f"  ✓ {path}: frontmatter valid, last-updated {last_updated} ({age}d ago)")
PY
done

section "2. API identifiers exist in source"

python3 - "$SKILL_DIR" <<'PY'
import sys, re, subprocess, pathlib, os
skill_dir = pathlib.Path(sys.argv[1])

# Identifiers we expect to find in the SceneView source. We accept a hit in
# any of these roots — the public surface is split across modules.
SEARCH_ROOTS = [
    "sceneview/src", "arsceneview/src", "sceneview-core/src",
    "samples/common/src", "samples/android-demo/src",
    "llms.txt",
]

# Patterns we extract from skill markdown code blocks:
#   - XxxNode               (a node composable / class)
#   - rememberXxx           (a remember helper)
#   - LightManager.Type.X   (Filament enum reference)
#   - SomeLoader.createXxx  (loader factory method)
NODE_RE        = re.compile(r"\b([A-Z][A-Za-z0-9]*Node)\b")
REMEMBER_RE    = re.compile(r"\b(remember[A-Z][A-Za-z0-9]+)\b")
LIGHT_TYPE_RE  = re.compile(r"\bLightManager\.Type\.([A-Z][A-Z_]+)\b")
LOADER_FN_RE   = re.compile(r"\b(\w+Loader)\.(create[A-Z]\w+|load[A-Z]\w+)\b")

# Known false positives — identifiers that exist as concepts in the skill but
# not in source. Whitelist conservatively.
WHITELIST = {
    # Composables/helpers that don't follow the *Node naming but still appear
    # in code as a callable. Empty for now — re-evaluate if a real false-positive shows.
}

# Extract all fenced code blocks (any language) from all .md under skill dir.
code_blobs = []
for md in skill_dir.rglob("*.md"):
    text = md.read_text(encoding="utf-8")
    for m in re.finditer(r"```[a-zA-Z]*\n(.*?)```", text, re.DOTALL):
        code_blobs.append((md, m.group(1)))

if not code_blobs:
    print("  ! no fenced code blocks found in skill (unusual but not fatal)")
    sys.exit(0)

# Collect (identifier, source_file) pairs.
checks = set()
for md, blob in code_blobs:
    for pat, ident_group in [
        (NODE_RE, 1),
        (REMEMBER_RE, 1),
        (LIGHT_TYPE_RE, 1),
        (LOADER_FN_RE, 2),  # we check the createXxx method name on its own
    ]:
        for hit in pat.finditer(blob):
            ident = hit.group(ident_group)
            if ident in WHITELIST: continue
            # Filter out Kotlin built-ins / Compose primitives the user knows about.
            if ident in {"Modifier", "Composable", "Box", "Row", "Column", "Surface",
                         "Text", "Icon", "Slider", "Switch", "Card",
                         "MaterialTheme", "LocalLifecycleOwner", "Lifecycle"}:
                continue
            # Skip stdlib Float/Int/etc.
            if ident in {"Boolean", "Float", "Int", "Double", "String", "Long",
                         "Object", "Unit", "Any"}:
                continue
            checks.add((ident, str(md.relative_to(skill_dir.parent.parent)) if skill_dir.parent.parent in md.parents else str(md)))

if not checks:
    print("  ✓ no API identifiers to verify (no matching patterns in code blocks)")
    sys.exit(0)

# For each identifier, grep across the search roots.
missing = []
for ident, where in sorted(checks):
    pat = ident.replace(".", r"\.")
    found = False
    for root in SEARCH_ROOTS:
        if not os.path.exists(root): continue
        try:
            result = subprocess.run(
                ["grep", "-rqE", f"\\b{pat}\\b", root, "--include=*.kt", "--include=llms.txt", "--include=*.txt"],
                capture_output=True, timeout=15,
            )
            if result.returncode == 0:
                found = True
                break
        except subprocess.TimeoutExpired:
            pass
        # Fallback for llms.txt file (not a directory)
        if root.endswith(".txt") and os.path.isfile(root):
            try:
                result = subprocess.run(
                    ["grep", "-qE", f"\\b{pat}\\b", root],
                    capture_output=True, timeout=10,
                )
                if result.returncode == 0:
                    found = True
                    break
            except subprocess.TimeoutExpired:
                pass
    if not found:
        missing.append((ident, where))

if not missing:
    print(f"  ✓ all {len(checks)} API identifiers exist in source")
    sys.exit(0)

print(f"  ✗ {len(missing)}/{len(checks)} API identifiers not found in any of: {', '.join(SEARCH_ROOTS)}")
for ident, where in missing:
    print(f"    - {ident:35s}  cited in {where}")
sys.exit(1)
PY
GREP_RC=$?
if [[ "$GREP_RC" -ne 0 ]]; then
  FAILURES=$((FAILURES + 1))
fi

section "3. Demo file references resolve"

python3 - "$SKILL_DIR" <<'PY'
import sys, re, pathlib, os
skill_dir = pathlib.Path(sys.argv[1])

# Match `XxxDemo.kt` and `samples/android-demo/.../*.kt` references.
DEMO_KT_RE = re.compile(r"\b([A-Z][A-Za-z0-9]*Demo\.kt)\b")
DEMO_PATH_RE = re.compile(r"samples/android-demo/src/main/java/io/github/sceneview/demo/demos/([A-Z][A-Za-z0-9]+\.kt)")

ROOT = pathlib.Path("samples/android-demo/src/main/java/io/github/sceneview/demo/demos")
if not ROOT.exists():
    print(f"  ! demos directory {ROOT} not found — skipping check")
    sys.exit(0)

available = {p.name for p in ROOT.glob("*.kt")}
referenced = set()
for md in skill_dir.rglob("*.md"):
    text = md.read_text(encoding="utf-8")
    for m in DEMO_KT_RE.finditer(text):
        referenced.add(m.group(1))
    for m in DEMO_PATH_RE.finditer(text):
        referenced.add(m.group(1))

missing = sorted(referenced - available)
if missing:
    print(f"  ✗ {len(missing)} demo file(s) referenced but missing from {ROOT}")
    for name in missing:
        print(f"    - {name}")
    sys.exit(1)

print(f"  ✓ all {len(referenced)} referenced demo files exist")
PY
DEMO_RC=$?
if [[ "$DEMO_RC" -ne 0 ]]; then
  FAILURES=$((FAILURES + 1))
fi

section "4. Skill is installable"

for INSTALLER in \
  "install-sceneview-skill.sh:agents/sceneview" \
  "install-sceneview-ios-skill.sh:agents/sceneview-ios" \
  "install-sceneview-web-skill.sh:agents/sceneview-web"; do
  script="${INSTALLER%%:*}"
  skilldir="${INSTALLER##*:}"
  [[ -d "$skilldir" ]] || continue
  if [[ -x ".claude/scripts/$script" ]]; then
    pass "$script exists and is executable"
  else
    fail "$script missing or not executable (skill at $skilldir)"
  fi
done

section "5. iOS / web demo references resolve"

# The iOS skill cites *Demo.swift files under samples/ios-demo; the web skill
# cites *.kt under sceneview-web/src/jsMain. Verify every cited filename
# exists so the skills never point an agent at a dead file.
python3 - <<'PY'
import re, pathlib

failures = 0

# --- iOS: *Demo.swift cited in agents/sceneview-ios ---
ios_skill = pathlib.Path("agents/sceneview-ios")
ios_demos = pathlib.Path("samples/ios-demo/SceneViewDemo/Views/Demos")
if ios_skill.is_dir() and ios_demos.is_dir():
    available = {p.name for p in ios_demos.glob("*.swift")}
    referenced = set()
    swift_re = re.compile(r"\b([A-Z][A-Za-z0-9]*Demo\.swift)\b")
    for md in ios_skill.rglob("*.md"):
        for m in swift_re.finditer(md.read_text(encoding="utf-8")):
            referenced.add(m.group(1))
    missing = sorted(referenced - available)
    if missing:
        print(f"  ✗ {len(missing)} iOS demo file(s) referenced but missing from {ios_demos}")
        for name in missing:
            print(f"    - {name}")
        failures += 1
    else:
        print(f"  ✓ all {len(referenced)} referenced iOS demo files exist")

# --- web: sceneview-web/src/.../*.kt cited in agents/sceneview-web ---
web_skill = pathlib.Path("agents/sceneview-web")
web_src = pathlib.Path("sceneview-web/src/jsMain")
if web_skill.is_dir() and web_src.is_dir():
    available = {p.name for p in web_src.rglob("*.kt")}
    referenced = set()
    # only match explicit `Name.kt` source-file citations (e.g. `SceneView.kt`)
    kt_re = re.compile(r"`([A-Za-z][A-Za-z0-9]*\.kt)`")
    for md in web_skill.rglob("*.md"):
        for m in kt_re.finditer(md.read_text(encoding="utf-8")):
            referenced.add(m.group(1))
    missing = sorted(referenced - available)
    if missing:
        print(f"  ✗ {len(missing)} web source file(s) referenced but missing from {web_src}")
        for name in missing:
            print(f"    - {name}")
        failures += 1
    else:
        print(f"  ✓ all {len(referenced)} referenced web source files exist")

import sys
sys.exit(1 if failures else 0)
PY
IOSWEB_RC=$?
if [[ "$IOSWEB_RC" -ne 0 ]]; then
  FAILURES=$((FAILURES + 1))
fi

# Print summary
if [[ "$QUIET" -eq 0 ]]; then
  echo ""
  if [[ "$FAILURES" -eq 0 ]]; then
    printf "${GREEN}✓ Skill drift check passed${NC}"
    [[ "$WARNINGS" -gt 0 ]] && printf " (with %d warning(s))" "$WARNINGS"
    echo ""
  else
    printf "${RED}✗ Skill drift check failed: %d failure(s), %d warning(s)${NC}\n" "$FAILURES" "$WARNINGS"
  fi
fi

[[ "$FAILURES" -eq 0 ]]
