import { PACKAGE_VERSION } from "./generated/version.js";

interface GitHubIssue {
  number: number;
  title: string;
  html_url: string;
  body: string | null;
  labels: Array<{ name: string }>;
  created_at: string;
  updated_at: string;
  user: { login: string };
}

/** Well-known issues and their solutions — always available even when GitHub API is down */
export interface CommonIssue {
  id: string;
  title: string;
  symptom: string;
  cause: string;
  solution: string;
  category: string;
}

export const COMMON_ISSUES: CommonIssue[] = [
  {
    id: "threading-sigabrt",
    title: "SIGABRT when loading model on background thread",
    symptom: "App crashes with SIGABRT / `Filament::FEngine::assertThread` in logcat when loading a model in a coroutine.",
    cause: "Filament JNI calls (modelLoader, materialLoader, Texture.Builder, engine.create*) must run on the main thread. Using `Dispatchers.IO` or `Dispatchers.Default` causes a native assertion failure.",
    solution: "Use `rememberModelInstance(modelLoader, path)` in composables (it handles threading internally). For imperative code, use `modelLoader.loadModelInstanceAsync(path)` or wrap in `withContext(Dispatchers.Main) { ... }`.",
    category: "crash",
  },
  {
    id: "null-model-instance",
    title: "rememberModelInstance returns null — model never appears",
    symptom: "Model never shows up. `rememberModelInstance` always returns `null`.",
    cause: "Either the asset path is wrong (file not in `src/main/assets/`), the GLB file is corrupt, or the code doesn't handle the null loading state. `rememberModelInstance` returns `null` while loading AND if the file fails.",
    solution: "1) Verify the file exists in `src/main/assets/models/`. 2) Test the GLB in https://gltf-viewer.donmccurdy.com/. 3) Add `Log.d(\"SV\", \"model: $modelInstance\")` to see if it's null vs loading. 4) Show a loading indicator while null.",
    category: "model-loading",
  },
  {
    id: "lightnode-trailing-lambda",
    title: "LightNode configuration silently ignored",
    symptom: "Light has no effect. Model is completely dark despite having a LightNode in the scene.",
    cause: "`LightNode`'s `apply` is a **named parameter**, not a trailing lambda. Writing `LightNode(...) { intensity(100_000f) }` silently ignores the block — Kotlin treats it as a trailing lambda to a different parameter.",
    solution: "Change `LightNode(type) { intensity(...) }` to `LightNode(type = ..., apply = { intensity(100_000f) })`.",
    category: "api-misuse",
  },
  {
    id: "destroy-order-sigabrt",
    title: "SIGABRT on texture/material cleanup",
    symptom: "Crash when the scene is disposed or when manually destroying resources.",
    cause: "Filament requires materials to be destroyed before their textures. Destroying in the wrong order triggers a native assertion.",
    solution: "Destroy MaterialInstance first, then Texture: `materialLoader.destroyMaterialInstance(mi)` then `engine.safeDestroyTexture(tex)`. Better: use `rememberEngine()` which handles cleanup order automatically.",
    category: "crash",
  },
  {
    id: "double-engine-destroy",
    title: "SIGABRT from double engine.destroy()",
    symptom: "Crash on Activity/Fragment destroy with `rememberEngine()` + manual `engine.destroy()` in DisposableEffect.",
    cause: "`rememberEngine()` already destroys the Engine on composition disposal. Calling `engine.destroy()` manually causes a double-free.",
    solution: "Remove manual `engine.destroy()` calls when using `rememberEngine()`. The composable handles the full lifecycle.",
    category: "crash",
  },
  {
    id: "ar-materials-crash-filament170",
    title: "AR crash with Filament 1.70.0 materials (issue #713)",
    symptom: "ARScene crashes on startup or when placing an object, with a native Filament error.",
    cause: "Filament 1.70.0 changed internal material format. Pre-compiled `.filamat` files from older versions are incompatible.",
    solution: "Upgrade to SceneView 3.3.0+ which includes compatible materials. If using custom `.filamat` files, recompile them with the `matc` tool from the matching Filament version.",
    category: "crash",
  },
  {
    id: "meshnode-boundingbox",
    title: "MeshNode bounding box incorrect (issue #711)",
    symptom: "Hit testing on MeshNode doesn't work, or the bounding box visual doesn't match the mesh.",
    cause: "MeshNode's bounding box was not recalculated after mesh creation in earlier versions.",
    solution: "Update to SceneView 3.3.0+ where MeshNode correctly computes its bounding box from vertex data.",
    category: "api-misuse",
  },
  {
    id: "ar-worldposition-drift",
    title: "AR objects drift / slide when moving phone",
    symptom: "Objects placed in AR slowly drift or jump to different positions as the user walks around.",
    cause: "Using `node.worldPosition = ...` instead of `AnchorNode`. ARCore remaps its coordinate system during tracking, and plain nodes don't compensate.",
    solution: "Always use `AnchorNode(anchor = hitResult.createAnchor())` to place AR objects. Anchors automatically compensate for coordinate system changes.",
    category: "ar",
  },
  {
    id: "scene-missing-engine",
    title: "SceneView composable crashes — missing engine parameter",
    symptom: "Compilation error or runtime crash because `SceneView(...)` doesn't have an engine.",
    cause: "In SceneView 3.0, the engine is explicit. `SceneView()` requires `engine = rememberEngine()`.",
    solution: "Add `val engine = rememberEngine()` and pass it: `SceneView(engine = engine, modifier = Modifier.fillMaxSize())`.",
    category: "api-misuse",
  },
  {
    id: "multiple-engines-oom",
    title: "Out of memory with multiple Engine instances",
    symptom: "App uses excessive memory, eventually crashes with OOM. GPU memory grows continuously.",
    cause: "Each `Engine.create()` or `rememberEngine()` allocates GPU resources. Multiple engines in different composables waste GPU memory.",
    solution: "Use a single `rememberEngine()` at the top level and pass it down to all SceneView composables. Never create more than one Engine per app.",
    category: "performance",
  },
  {
    id: "glb-not-gltf",
    title: "Model fails to load — using .gltf instead of .glb",
    symptom: "Model returns null. No error in logcat but the model never appears.",
    cause: "`.gltf` is a multi-file format that references external `.bin` and texture files. If those files aren't at the expected relative paths, loading silently fails.",
    solution: "Use `.glb` (binary glTF) which bundles geometry, textures, and animations into a single file. Convert in Blender: File > Export > glTF Binary (.glb).",
    category: "model-loading",
  },
  {
    id: "ios-glb-not-usdz",
    title: "iOS: Model won't load — using GLB instead of USDZ",
    symptom: "RealityKit/SceneViewSwift: model loading fails or throws.",
    cause: "RealityKit uses USDZ or .reality format, NOT GLB/glTF. GLB is Filament-only (Android/Web).",
    solution: "Convert models to USDZ for iOS: use Apple's Reality Converter app, or Blender with the USDZ exporter. Each platform needs its own model format.",
    category: "ios",
  },
];

export function getCommonIssuesSummary(): string {
  const lines: string[] = ["# SceneView — Common Issues & Solutions\n"];
  lines.push(`*${COMMON_ISSUES.length} well-known issues with solutions. Always available offline.*\n`);

  const categories = [...new Set(COMMON_ISSUES.map((i) => i.category))];
  for (const cat of categories) {
    const catIssues = COMMON_ISSUES.filter((i) => i.category === cat);
    lines.push(`## ${cat}\n`);
    for (const issue of catIssues) {
      lines.push(`### ${issue.title}`);
      lines.push(`**Symptom:** ${issue.symptom}`);
      lines.push(`**Cause:** ${issue.cause}`);
      lines.push(`**Solution:** ${issue.solution}\n`);
    }
  }
  return lines.join("\n");
}

export function searchCommonIssues(query: string): CommonIssue[] {
  const lower = query.toLowerCase();
  return COMMON_ISSUES.filter(
    (issue) =>
      issue.title.toLowerCase().includes(lower) ||
      issue.symptom.toLowerCase().includes(lower) ||
      issue.cause.toLowerCase().includes(lower) ||
      issue.solution.toLowerCase().includes(lower) ||
      issue.id.includes(lower)
  );
}

interface CacheEntry {
  data: string;
  fetchedAt: number;
}

// Cache for 10 minutes — avoids hammering the GitHub API on every tool call
const CACHE_TTL_MS = 10 * 60 * 1000;
let cache: CacheEntry | null = null;

export async function fetchKnownIssues(): Promise<string> {
  const now = Date.now();
  if (cache && now - cache.fetchedAt < CACHE_TTL_MS) {
    return cache.data;
  }

  let issues: GitHubIssue[] = [];
  let fetchError: string | null = null;

  try {
    const response = await fetch(
      "https://api.github.com/repos/sceneview/sceneview/issues?state=open&per_page=30",
      {
        headers: {
          Accept: "application/vnd.github+json",
          // Plumb the actual MCP package version so GitHub abuse-watch
          // logs show which MCP build is hitting them — was hardcoded
          // to "3.5.4" pre-#941, far behind the current release.
          "User-Agent": `sceneview-mcp/${PACKAGE_VERSION}`,
          "X-GitHub-Api-Version": "2022-11-28",
        },
      }
    );

    if (!response.ok) {
      fetchError = `GitHub API returned ${response.status}: ${response.statusText}`;
    } else {
      const json = await response.json();
      if (!Array.isArray(json)) {
        fetchError = "GitHub API returned unexpected response format (expected array).";
      } else {
        issues = json
          .filter(
            (item: unknown): item is Record<string, unknown> =>
              typeof item === "object" &&
              item !== null &&
              typeof (item as Record<string, unknown>).number === "number" &&
              typeof (item as Record<string, unknown>).title === "string"
          )
          .map(normalizeIssue);
      }
    }
  } catch (err) {
    fetchError = `Failed to fetch issues: ${err instanceof Error ? err.message : String(err)}`;
  }

  const result = formatIssues(issues, fetchError);
  cache = { data: result, fetchedAt: now };
  return result;
}

/**
 * Coerce a loosely-validated GitHub API item into a complete {@link GitHubIssue}.
 *
 * The upstream type guard only asserts `number` + `title`. A malformed API
 * response — or a partial item served during a GitHub incident — can be
 * missing `user`, `labels` or `updated_at`. Reading those unconditionally in
 * `formatIssues` throws a `TypeError` and takes down the whole
 * `sceneview://known-issues` resource. Default every optional field here so
 * formatting can never crash.
 */
function normalizeIssue(item: Record<string, unknown>): GitHubIssue {
  const rawLabels = item.labels;
  const labels: Array<{ name: string }> = Array.isArray(rawLabels)
    ? rawLabels
        .map((l): { name: string } | null => {
          if (typeof l === "string") return { name: l };
          if (l !== null && typeof l === "object") {
            const name = (l as Record<string, unknown>).name;
            if (typeof name === "string") return { name };
          }
          return null;
        })
        .filter((l): l is { name: string } => l !== null)
    : [];

  const rawUser = item.user;
  const login =
    rawUser !== null &&
    typeof rawUser === "object" &&
    typeof (rawUser as Record<string, unknown>).login === "string"
      ? ((rawUser as Record<string, unknown>).login as string)
      : "unknown";

  const updatedAt = typeof item.updated_at === "string" ? item.updated_at : "";
  const createdAt = typeof item.created_at === "string" ? item.created_at : "";

  return {
    number: item.number as number,
    title: item.title as string,
    html_url: typeof item.html_url === "string" ? item.html_url : "",
    body: typeof item.body === "string" ? item.body : null,
    labels,
    created_at: createdAt,
    updated_at: updatedAt,
    user: { login },
  };
}

function formatIssues(issues: GitHubIssue[], fetchError: string | null): string {
  const lines: string[] = ["# SceneView — Open GitHub Issues\n"];

  if (fetchError) {
    lines.push(`> ⚠️ ${fetchError}. Showing cached data if available.\n`);
  } else {
    lines.push(`*Fetched live from GitHub — ${issues.length} open issue(s). Cached for 10 minutes.*\n`);
  }

  if (issues.length === 0) {
    lines.push("No open issues. 🎉");
    return lines.join("\n");
  }

  // Group by label: bugs first, then questions/other
  const bugs = issues.filter((i) => i.labels.some((l) => l.name === "bug"));
  const others = issues.filter((i) => !i.labels.some((l) => l.name === "bug"));

  const renderGroup = (title: string, group: GitHubIssue[]) => {
    if (group.length === 0) return;
    lines.push(`## ${title}\n`);
    for (const issue of group) {
      const labelStr =
        issue.labels.length > 0 ? ` [${issue.labels.map((l) => l.name).join(", ")}]` : "";
      lines.push(`### #${issue.number} — ${issue.title}${labelStr}`);
      lines.push(`*Opened by @${issue.user.login} · ${issue.updated_at.slice(0, 10)}*`);
      lines.push(issue.html_url);

      // Include first 300 chars of body as context
      if (issue.body) {
        const excerpt = issue.body
          .replace(/\r\n/g, "\n")
          .replace(/```[\s\S]*?```/g, "[code block]") // strip code blocks for brevity
          .trim()
          .slice(0, 300);
        lines.push(`\n> ${excerpt.replace(/\n/g, "\n> ")}${issue.body.length > 300 ? "…" : ""}`);
      }
      lines.push("");
    }
  };

  renderGroup("🐛 Bug Reports", bugs);
  renderGroup("📋 Other Issues", others);

  return lines.join("\n");
}
