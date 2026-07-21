#!/usr/bin/env node
/**
 * Extract the ```kotlin snippets embedded in the AI-first doc surfaces
 * (`llms.txt` + `agents/sceneview/references/*.md`) into per-snippet Kotlin
 * source files that the `:snippets-check` Gradle module compiles in CI.
 *
 * WHY THIS EXISTS (issue #2759)
 * -----------------------------
 * `llms.txt` is the surface an AI reads to generate user code. It embeds
 * ~190 Kotlin snippets that NOTHING ever compiles: the existing guards are
 * an advisory grep (`check-doc-drift.sh`, exit 0) and a weekly LLM prose
 * audit — neither catches a renamed parameter deterministically. A drifted
 * snippet makes every AI assistant emit broken code (the `centerOrigin`
 * drift class, #2622/#2623). This script turns every documented snippet
 * into a compile-time invariant: an API change that breaks documented code
 * becomes a deterministic CI red.
 *
 * WHAT IT DOES
 * ------------
 * Parses the ```kotlin fenced blocks of each source, classifies every block:
 *
 *   - `compile`  — emitted as its own .kt file (own package, so blocks can
 *                  never collide). Fragments that are not top-level
 *                  declarations are wrapped in a `@Composable private fun`
 *                  whose body runs inside `with(arSceneScope)` — node
 *                  composables (ModelNode, HitResultNode, …) are
 *                  SceneScope/ARSceneScope members, so the wrapper reproduces
 *                  the documented trailing-lambda context. A CONTEXT_PRELUDE
 *                  injects the canonical names the doc's prose establishes
 *                  (engine, modelLoader, arSession, camera, frame, …) when a
 *                  fragment uses them without declaring them. Signature
 *                  listings are made compilable by stubbing: bodyless `fun`
 *                  gets ` = TODO()` (`: Unit = TODO()` when no return type),
 *                  uninitialized `val/var` gets ` = TODO()` (extension
 *                  properties get ` get() = TODO()`); interface blocks are
 *                  left alone (bodyless members are their legal form).
 *                  Leading `import` lines are hoisted above the wrapper and
 *                  evict a same-simple-name explicit preamble import.
 *   - `notest`   — explicitly opted out in the doc with ```kotlin notest
 *                  (for intentionally non-standalone fragments). Every
 *                  `notest` MUST carry a reason: ```kotlin notest <reason>.
 *   - `ellipsis` — contains a `...` or `…` placeholder outside `//` comments:
 *                  structural pseudo-code by convention, never compilable.
 *                  Skipped automatically so docs keep the freedom to elide.
 *   - `nonkotlin`— Gradle/Groovy blocks mistagged as kotlin (heuristic:
 *                  dependency/plugins DSL in the first lines).
 *   - `otherplatform` — blocks under the "SceneView Web" / "SceneViewSwift"
 *                  sections of llms.txt (or a `### Web (...)` sub-heading of a
 *                  cross-platform section): Kotlin/JS (@JsExport) or
 *                  Swift-bridge API that can never compile on this Android
 *                  classpath. A JS-side guard is an honest follow-up (#2759).
 *
 * A JSON manifest with per-category counts and per-block provenance
 * (source file + line) is written next to the generated sources, so a
 * compile error maps straight back to the doc line that caused it.
 *
 * USAGE
 * -----
 *   node tools/extract-doc-snippets.js                # write into tools/snippets-check/build/generated
 *   node tools/extract-doc-snippets.js --out <dir>    # custom output dir
 *   node tools/extract-doc-snippets.js --list         # human-readable classification report, no writes
 *
 * Pure Node, zero dependencies. Deterministic: same inputs → same outputs.
 */

"use strict";

const { readFileSync, writeFileSync, mkdirSync, rmSync } = require("node:fs");
const { resolve, join } = require("node:path");

const toolsDir = __dirname;
const repoRoot = resolve(toolsDir, "..");

// ─── Sources ────────────────────────────────────────────────────────────────
// slug → repo-relative path. The slug becomes part of the generated package.
const SOURCES = [
  { slug: "llms", path: "llms.txt" },
  { slug: "cheatsheet", path: "agents/sceneview/references/cheatsheet.md" },
  { slug: "migration", path: "agents/sceneview/references/migration.md" },
  { slug: "recipes", path: "agents/sceneview/references/recipes.md" },
];

// ─── Preamble imports ───────────────────────────────────────────────────────
// Injected into every generated file. Snippets are documentation fragments:
// they omit imports the way every Kotlin doc does, so the harness supplies
// the SDK surface wholesale. Wildcards keep this list short; a snippet's own
// explicit imports are hoisted AFTER these and win any ambiguity.
const PREAMBLE_IMPORTS = [
  "android.content.Context",
  "android.graphics.Bitmap",
  "android.media.Image",
  "android.media.MediaPlayer",
  "android.media.MediaRecorder",
  "android.net.Uri",
  "android.view.Display",
  "androidx.annotation.DrawableRes",
  "androidx.annotation.RawRes",
  "android.view.MotionEvent",
  "android.view.SurfaceView",
  "android.view.TextureView",
  "androidx.activity.ComponentActivity",
  "androidx.compose.foundation.*",
  "androidx.compose.foundation.layout.*",
  "androidx.compose.material3.*",
  "androidx.compose.runtime.*",
  "androidx.compose.ui.Alignment",
  "androidx.compose.ui.Modifier",
  "androidx.compose.ui.graphics.Color",
  "androidx.compose.ui.platform.LocalContext",
  "androidx.compose.ui.unit.dp",
  "androidx.lifecycle.Lifecycle",
  "androidx.lifecycle.compose.LocalLifecycleOwner",
  "com.google.android.filament.*",
  // llms.txt's ground-shadow snippets use the doc-established `FilamentBox`
  // alias to disambiguate from io.github.sceneview.collision.Box.
  "com.google.android.filament.Box as FilamentBox",
  // Same repo-established alias SceneScope.kt itself uses for ReflectionProbeNode.
  "com.google.android.filament.Scene as FilamentScene",
  // Aliasing a FQN hides its original simple name from the wildcard — re-import
  // the bare names the signature listings still use (MeshNode's boundingBox: Box,
  // SceneView's scene: Scene).
  "com.google.android.filament.Box",
  "com.google.android.filament.Scene",
  "com.google.ar.core.*",
  // Explicit: `Plane` alone is ambiguous (ar.core.* vs geometries.*) and the
  // AR usage dominates the doc. The one geometry block that means
  // io.github.sceneview.geometries.Plane hoists its own explicit import,
  // which evicts this one (same-simple-name rule in emit()).
  "com.google.ar.core.Plane",
  // Explicit: `Camera` is ambiguous (ar.core.* vs filament.*); the AR blocks
  // that spell the type out (ReticleNode's minCameraDistanceFromPlane) mean
  // ar.core — same import ARSceneScope.kt itself makes.
  "com.google.ar.core.Camera",
  // Nested enums spelled bare in the AR snippets.
  "com.google.ar.core.AugmentedImage.TrackingMethod",
  "com.google.ar.core.Anchor.TerrainAnchorState",
  "com.google.ar.core.Anchor.RooftopAnchorState",
  "dev.romainguy.kotlin.math.Float2",
  "dev.romainguy.kotlin.math.Float3",
  "dev.romainguy.kotlin.math.Float4",
  // CollisionSystem.hitTest(ray:) takes the kotlin-math Ray (the legacy
  // collision.Ray is a different, non-wildcarded type).
  "dev.romainguy.kotlin.math.Ray",
  // Explicit: bare `Quaternion` must resolve to kotlin-math (the SDK's math
  // type) — com.google.ar.core.* also ships a (package-private) Quaternion.
  "dev.romainguy.kotlin.math.Quaternion",
  "dev.romainguy.kotlin.math.RotationsOrder",
  "java.io.File",
  "java.nio.Buffer",
  "java.nio.ByteBuffer",
  "java.nio.FloatBuffer",
  "java.util.UUID",
  // The module's own BuildConfig — llms.txt debug-gating snippets read .DEBUG.
  "io.github.sceneview.docsnippets.BuildConfig",
  "io.github.sceneview.*",
  "io.github.sceneview.gesture.*",
  "io.github.sceneview.animation.*",
  "io.github.sceneview.ar.*",
  "io.github.sceneview.ar.arcore.*",
  "io.github.sceneview.ar.collaborative.*",
  "io.github.sceneview.ar.camera.*",
  "io.github.sceneview.ar.node.*",
  "io.github.sceneview.ar.physics.*",
  "io.github.sceneview.ar.postprocessing.*",
  "io.github.sceneview.ar.recording.*",
  "io.github.sceneview.ar.rerun.*",
  "io.github.sceneview.ar.scene.*",
  "io.github.sceneview.audio.*",
  "io.github.sceneview.core.splat.*",
  // NOTE: io.github.sceneview.collision.* is deliberately NOT wildcarded —
  // collision.Sphere/Box/Plane/HitResult collide with geometries.* and
  // com.google.ar.core.*. Collision-system snippets carry their own explicit
  // imports in the doc (which also teaches the reader which type is meant).
  // CollisionSystem itself has a unique simple name — safe to import.
  "io.github.sceneview.collision.CollisionSystem",
  "io.github.sceneview.components.*",
  "io.github.sceneview.environment.*",
  "io.github.sceneview.geometries.*",
  "io.github.sceneview.loaders.*",
  "io.github.sceneview.material.*",
  "io.github.sceneview.math.*",
  "io.github.sceneview.model.*",
  "io.github.sceneview.node.*",
  "io.github.sceneview.texture.*",
  "io.github.sceneview.utils.*",
  "kotlinx.coroutines.*",
  "kotlinx.coroutines.flow.*",
];

// Suppressed diagnostics: snippets illustrate one API each — unused locals,
// shadowed names and missing usages are inherent, not defects.
const FILE_SUPPRESS =
  '@file:Suppress("unused", "UNUSED_VARIABLE", "UNUSED_PARAMETER", ' +
  '"UNUSED_ANONYMOUS_PARAMETER", "UNUSED_EXPRESSION", "RedundantSuppression", ' +
  // UNREACHABLE_CODE: the context prelude pins types with `TODO()` placeholders
  // (never executed — this module is compile-only), which marks the rest of the
  // wrapper body unreachable.
  '"LocalVariableName", "FunctionName", "SpellCheckingInspection", "UNREACHABLE_CODE")';

// A block whose first meaningful line starts a top-level declaration is
// emitted verbatim; anything else is a fragment and gets wrapped in a
// @Composable function body (composable context is a superset: plain
// statements compile there too, and most fragments use remember*/Composable
// APIs that REQUIRE it).
const TOP_LEVEL_RE =
  /^(@\w[\w.]*(\(.*\))?\s*$|(private\s+|internal\s+|public\s+|abstract\s+|sealed\s+|open\s+)*(fun|class|interface|object|enum\s+class|data\s+class|annotation\s+class|typealias)\b|@Composable\b)/;

// Heuristic for Gradle DSL blocks mistagged ```kotlin.
const GRADLE_RE =
  /^\s*(dependencies\s*\{|plugins\s*\{|implementation\s*\(|api\s*\(|classpath\s*\()/m;

// ─── Fence parsing ──────────────────────────────────────────────────────────

// llms.txt is multi-platform: its "SceneView Web" section documents the
// Kotlin/JS API (@JsExport surface of sceneview-web) — those blocks can never
// compile on this module's Android classpath. They are classified out with
// their own manifest category so the gap stays visible (a JS-side compile
// guard is an honest follow-up, tracked on #2759).
const NON_ANDROID_SECTIONS = [
  { prefix: "## SceneView Web", platform: "web" },
  { prefix: "## SceneViewSwift", platform: "swift" },
];

/** @returns {{info:string, line:number, code:string, platform:string}[]} */
function parseKotlinBlocks(text) {
  const lines = text.split("\n");
  const blocks = [];
  let inBlock = false;
  let info = "";
  let start = 0;
  let buf = [];
  let sectionPlatform = "android"; // platform of the enclosing ## section
  let platform = "android"; // effective platform (a ### sub-heading can override)
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (!inBlock && line.startsWith("## ") && !line.startsWith("### ")) {
      const hit = NON_ANDROID_SECTIONS.find((s) => line.startsWith(s.prefix));
      sectionPlatform = hit ? hit.platform : "android";
      platform = sectionPlatform;
    } else if (!inBlock && line.startsWith("### ")) {
      // Cross-platform sections carry per-platform ### sub-headings; a
      // "### Web (...)" block is Kotlin/JS and can't compile on this classpath.
      platform = /^###\s+Web\b/.test(line) ? "web" : sectionPlatform;
    }
    if (!inBlock) {
      const m = line.match(/^```kotlin\b(.*)$/);
      if (m) {
        inBlock = true;
        info = m[1].trim();
        start = i + 2; // 1-indexed line of the first code line
        buf = [];
      }
    } else if (line.startsWith("```")) {
      inBlock = false;
      blocks.push({ info, line: start, code: buf.join("\n"), platform });
    } else {
      buf.push(line);
    }
  }
  return blocks;
}

// ─── Classification ─────────────────────────────────────────────────────────

function classify(block) {
  if (block.platform !== "android") {
    return { category: "otherplatform", reason: `${block.platform} API section` };
  }
  if (/^notest\b/.test(block.info)) {
    const reason = block.info.replace(/^notest\s*/, "").trim();
    return { category: "notest", reason: reason || "(MISSING REASON)" };
  }
  // Placeholder test ignores `//` comments: an ellipsis in prose commentary
  // doesn't make the code un-compilable. Both ASCII `...` and U+2026 `…` count.
  const codeOnly = block.code
    .split("\n")
    .map((l) => l.replace(/\/\/.*$/, ""))
    .join("\n");
  if (codeOnly.includes("...") || codeOnly.includes("…")) {
    return { category: "ellipsis", reason: "contains ellipsis placeholder" };
  }
  if (GRADLE_RE.test(block.code)) {
    return { category: "nonkotlin", reason: "Gradle DSL mistagged as kotlin" };
  }
  return { category: "compile" };
}

// ─── Emission ───────────────────────────────────────────────────────────────

// Signature-reference blocks reproduce API declarations without bodies
// (```kotlin\n@Composable fun SceneView(\n  modifier: Modifier = Modifier,\n…\n)```).
// Compiling them verifies every referenced TYPE and every documented DEFAULT
// EXPRESSION still exists (that the signature itself matches the real one is
// #2760's symbol-index job, not this harness's). A bodyless `fun` is not
// valid Kotlin, so append a `= TODO()` stub where the parens balance and no
// `{`/`=` body follows.
// Trailing `// …` prose must not count parens or swallow the appended stub.
function splitComment(line) {
  const at = line.indexOf("//");
  return at === -1
    ? { code: line, comment: "" }
    : { code: line.slice(0, at).replace(/\s+$/, ""), comment: " " + line.slice(at) };
}

function stubBodylessFuns(lines) {
  const out = [...lines];
  let i = 0;
  while (i < out.length) {
    if (/^\s*(@[\w.]+(\(.*\))?\s+)*((private|internal|public|protected|open|override|inline|suspend|operator|infix)\s+)*fun\s/.test(out[i])) {
      let depth = 0;
      let seenParen = false;
      let j = i;
      let closePos = -1; // index (in the closing line) of the paren that ends the PARAMETER LIST
      for (; j < out.length; j++) {
        const lineCode = splitComment(out[j]).code;
        for (let k = 0; k < lineCode.length; k++) {
          const ch = lineCode[k];
          if (ch === "(") { depth++; seenParen = true; }
          else if (ch === ")") {
            depth--;
            if (seenParen && depth === 0) { closePos = k; break; }
          }
        }
        if (closePos !== -1) break;
      }
      if (j < out.length && closePos !== -1) {
        const { code, comment } = splitComment(out[j]);
        // Everything after the param-list close is the return type — which may
        // itself contain parens (`: (Session) -> CameraConfig`), so never use
        // lastIndexOf(")") here.
        const tail = code.slice(closePos + 1);
        const next = out.slice(j + 1).find((l) => l.trim() !== "");
        const hasBody =
          /[{=]/.test(tail) || (next !== undefined && /^\s*[{=]/.test(next));
        // No declared return type → the stub must pin Unit explicitly,
        // otherwise `= TODO()` infers Nothing (a compile error top-level).
        const hasReturnType = /:\s*\S/.test(tail);
        if (!hasBody) out[j] = code + (hasReturnType ? " = TODO()" : ": Unit = TODO()") + comment;
        i = j + 1;
        continue;
      }
    }
    i++;
  }
  return out;
}

// Reference listings also show `val isAttached: Boolean` / `var onSurfaceReady:
// ((…) -> Unit)?` without initializers — legal in an interface, a compile error
// in a class or statement context. `= TODO()` is valid in all stub positions
// (member, local, even a data-class parameter default), so append it wherever a
// typed property has no initializer, no accessor, and no trailing comma.
// Interface listings are left alone (bodyless members are their legal form).
function stubUninitializedProps(lines) {
  if (lines.some((l) => /^\s*(public\s+|private\s+|internal\s+)?interface\s/.test(l))) return lines;
  return lines.map((line) => {
    const { code, comment } = splitComment(line);
    const isExtension =
      /^\s*((override|open|public|internal|protected|private|lateinit)\s+)*(val|var)\s+[\w`]+(<[^>]*>)?\.[\w`]+\s*:/.test(code);
    const isPlain =
      /^\s*((override|open|public|internal|protected|private|lateinit)\s+)*(val|var)\s+[\w`]+\s*:/.test(code);
    if (!isExtension && !isPlain) return line;
    if (/[=,{(]\s*$/.test(code) || /\bget\(|\bset\(/.test(code) || /=/.test(code)) return line;
    // A type annotation that hasn't closed its generics/parens yet continues on
    // the next line — don't stub mid-declaration.
    const opens = (code.match(/[<(]/g) || []).length;
    const closes = (code.match(/[>)]/g) || []).length;
    if (opens > closes) return line;
    // Extension properties cannot have initializers — stub the getter instead.
    return code + (isExtension ? " get() = TODO()" : " = TODO()") + comment;
  });
}

// The canonical composition context llms.txt itself establishes everywhere
// ("val engine = rememberEngine()" …). Fragments routinely reference these
// four names without re-declaring them — inject the missing ones into the
// wrapper, in dependency order, never shadowing a declaration the fragment
// already makes. Anything beyond this quartet stays the doc's problem: the
// fix is a clearer snippet (or a reasoned `notest`), not more harness magic.
const CONTEXT_PRELUDE = [
  ["engine", "val engine = rememberEngine()"],
  ["modelLoader", "val modelLoader = rememberModelLoader(engine)"],
  ["materialLoader", "val materialLoader = rememberMaterialLoader(engine)"],
  ["environmentLoader", "val environmentLoader = rememberEnvironmentLoader(engine)"],
  ["node", "val node = Node(engine)"],
  // Screen-space dimensions the AR hit-test snippets take as given.
  ["viewWidth", "val viewWidth = 1080f"],
  ["viewHeight", "val viewHeight = 1920f"],
  ["xPx", "val xPx = 540f"],
  ["yPx", "val yPx = 960f"],
  ["deltaSeconds", "val deltaSeconds = 0.016f"],
  // Type-pinned placeholders (TODO() is never executed — compile-only module).
  // These are the names llms.txt's surrounding prose establishes before the
  // fragment: the camera/model handles, a touch event, the collision system.
  ["motionEvent", "val motionEvent: MotionEvent = TODO()"],
  ["cameraNode", "val cameraNode: CameraNode = TODO()"],
  ["modelNode", "val modelNode: ModelNode = TODO()"],
  ["modelInstance", "val modelInstance: ModelInstance = TODO()"],
  ["instance", "val instance: ModelInstance = TODO()"],
  ["shadowMaterial", "val shadowMaterial: MaterialInstance = TODO()"],
  ["collisionSystem", "val collisionSystem: io.github.sceneview.collision.CollisionSystem = TODO()"],
  ["arSession", "var arSession: com.google.ar.core.Session? = null"],
  ["session", "val session: com.google.ar.core.Session = TODO()"],
  ["camera", "val camera: com.google.ar.core.Camera = TODO()"],
  ["hitResult", "val hitResult: com.google.ar.core.HitResult = TODO()"],
  ["scope", "val scope = rememberCoroutineScope()"],
  ["frame", "val frame: com.google.ar.core.Frame = TODO()"],
  ["view", "val view = rememberView(engine)"],
  ["renderer", "val renderer = rememberRenderer(engine)"],
  ["bridge", "val bridge = rememberRerunBridge()"],
  ["trailFloats", "val trailFloats = floatArrayOf()"],
  ["trackingQuality", "val trackingQuality = 1f"],
  ["cameraStream", "val cameraStream: ARCameraStream = TODO()"],
  ["cloud", "val cloud: SplatCloud = TODO()"],
  ["depthCollider", "val depthCollider: DepthCollider = TODO()"],
  ["file", 'val file = File("session-recording.mp4")'],
  ["semanticTexture", "val semanticTexture: Texture = TODO()"],
  ["blendSlider", "val blendSlider = 0.5f"],
  ["targetTransform", "val targetTransform = Transform(position = Position(x = 1f))"],
  ["recorder", "val recorder = rememberARRecorder()"],
  ["occlusionAvailable", "var occlusionAvailable = true"],
  ["context", "val context = LocalContext.current"],
  ["contentRoot", "val contentRoot = Node(engine)"],
];

function contextPrelude(bodyText) {
  const declares = (name) =>
    new RegExp(`\\b(val|var)\\s+${name}\\b`).test(bodyText);
  const uses = (name) => new RegExp(`\\b${name}\\b`).test(bodyText);
  const picked = CONTEXT_PRELUDE.filter(
    ([name]) => uses(name) && !declares(name)
  );
  if (picked.some(([name]) => name !== "engine") && !picked.some(([n]) => n === "engine") && !declares("engine")) {
    picked.unshift(CONTEXT_PRELUDE[0]);
  }
  return picked.map(([, decl]) => decl);
}

function emit(block, slug, index) {
  const pkg = `docsnippets.${slug}.b${String(index).padStart(3, "0")}`;
  const codeLines = block.code.split("\n");

  // Hoist the block's own leading imports (they may appear anywhere in a
  // fragment's first lines, before any code).
  const hoisted = [];
  const body = [];
  let inHeader = true;
  for (const l of codeLines) {
    if (inHeader && /^import\s+/.test(l.trim())) {
      hoisted.push(l.trim());
    } else {
      if (l.trim() !== "" || !inHeader) {
        if (l.trim() !== "") inHeader = false;
        body.push(l);
      }
    }
  }

  const stubbed = stubUninitializedProps(stubBodylessFuns(body));
  const firstCode = stubbed.find((l) => l.trim() !== "" && !/^\/\//.test(l.trim()));
  const topLevel = firstCode !== undefined && TOP_LEVEL_RE.test(firstCode.trim());

  // A block's own explicit import evicts a same-simple-name explicit import
  // from the preamble (two explicit imports of one simple name is an error;
  // the doc's choice must win).
  const hoistedSimple = new Set(
    hoisted.map((h) =>
      h.replace(/^import\s+/, "").replace(/\/\/.*$/, "").trim().split(".").pop()
    )
  );
  const preambleImports = PREAMBLE_IMPORTS.filter(
    (imp) => imp.endsWith("*") || !hoistedSimple.has(imp.split(".").pop())
  );

  const header = [
    FILE_SUPPRESS,
    "",
    `package ${pkg}`,
    "",
    ...preambleImports.map((i) => `import ${i}`),
    ...hoisted.map((h) => (h.startsWith("import") ? h : `import ${h}`)),
    "",
    `// GENERATED by tools/extract-doc-snippets.js — DO NOT EDIT.`,
    `// Source: ${block.sourcePath}:${block.line}`,
  ];

  const prelude = topLevel ? [] : contextPrelude(stubbed.join("\n"));
  // Node composables (ModelNode, CubeNode, HitResultNode, …) are MEMBERS of
  // SceneScope/ARSceneScope — llms.txt shows them as the caller writes them
  // inside a SceneView/ARSceneView trailing lambda. Wrapping every fragment in
  // `with(arSceneScope)` reproduces that documented context (ARSceneScope
  // extends SceneScope, so both families resolve). TODO() placeholder — this
  // module is compile-only, nothing ever runs.
  const content = topLevel
    ? stubbed.join("\n")
    : [
        "@Composable",
        `private fun Snippet_${slug}_${index}() {`,
        "    val arSceneScope: ARSceneScope = TODO()",
        ...prelude.map((l) => `    ${l}`),
        "    with(arSceneScope) {",
        ...stubbed.map((l) => (l === "" ? l : `        ${l}`)),
        "    }",
        "}",
      ].join("\n");

  return {
    file: `${pkg.replace(/\./g, "/")}/Snippet.kt`,
    text: header.join("\n") + "\n" + content + "\n",
    wrapped: !topLevel,
  };
}

// ─── Main ───────────────────────────────────────────────────────────────────

function main() {
  const args = process.argv.slice(2);
  const listOnly = args.includes("--list");
  const outIdx = args.indexOf("--out");
  const outDir =
    outIdx !== -1
      ? resolve(args[outIdx + 1])
      : join(repoRoot, "tools/snippets-check/build/generated/snippets");

  const manifest = { generatedBy: "tools/extract-doc-snippets.js", sources: {}, counts: {} };
  const emitted = [];
  const report = [];

  for (const src of SOURCES) {
    const text = readFileSync(join(repoRoot, src.path), "utf8");
    const blocks = parseKotlinBlocks(text);
    const perSource = { total: blocks.length, compile: 0, notest: 0, ellipsis: 0, nonkotlin: 0, otherplatform: 0, blocks: [] };

    blocks.forEach((block, i) => {
      block.sourcePath = src.path;
      const cls = classify(block);
      perSource[cls.category]++;
      perSource.blocks.push({ index: i, line: block.line, ...cls });
      report.push(
        `${src.path}:${block.line}\t${cls.category}${cls.reason ? `\t(${cls.reason})` : ""}`
      );
      if (cls.category === "compile") {
        emitted.push(emit(block, src.slug, i));
      }
      if (cls.category === "notest" && cls.reason === "(MISSING REASON)") {
        console.error(
          `ERROR: ${src.path}:${block.line} — \`\`\`kotlin notest without a reason. ` +
            "Every opt-out must say why: ```kotlin notest <reason>"
        );
        process.exitCode = 1;
      }
    });
    manifest.sources[src.path] = perSource;
  }

  for (const cat of ["compile", "notest", "ellipsis", "nonkotlin", "otherplatform"]) {
    manifest.counts[cat] = Object.values(manifest.sources).reduce((a, s) => a + s[cat], 0);
  }

  if (listOnly) {
    console.log(report.join("\n"));
    console.log(`\nTotals: ${JSON.stringify(manifest.counts)}`);
    return;
  }

  if (manifest.counts.compile === 0) {
    console.error("ERROR: zero compilable snippets extracted — parser or docs broken.");
    process.exit(1);
  }

  rmSync(outDir, { recursive: true, force: true });
  for (const e of emitted) {
    const path = join(outDir, e.file);
    mkdirSync(resolve(path, ".."), { recursive: true });
    writeFileSync(path, e.text);
  }
  writeFileSync(join(outDir, "manifest.json"), JSON.stringify(manifest, null, 2) + "\n");
  console.log(
    `Extracted ${manifest.counts.compile} compilable snippets ` +
      `(${manifest.counts.ellipsis} ellipsis, ${manifest.counts.notest} notest, ` +
      `${manifest.counts.nonkotlin} non-kotlin, ` +
      `${manifest.counts.otherplatform} other-platform) → ${outDir}`
  );
}

main();
