/**
 * android-docs — `search_android_docs` / `fetch_android_doc` MCP tools.
 *
 * Google's `android` CLI (https://developer.android.com/tools/agents/android-cli)
 * ships a `docs` subcommand backed by a knowledge base of ~4 800 stock Android
 * documentation entries: Jetpack Compose, Camera2, ARCore SDK, Kotlin APIs,
 * platform guides, and more. Wrapping it as MCP tools lets any MCP-aware
 * assistant cross-reference stock Android docs with SceneView code without
 * leaving the SceneView chat (issue #1083).
 *
 *   - `search_android_docs(query)`  → `android docs search <query>`
 *   - `fetch_android_doc(uri)`      → `android docs fetch kb://<path>`
 *
 * Runtime dependency: the `android` CLI must be on the consumer's PATH. It is
 * NOT a hard dependency of `sceneview-mcp` — most MCP hosts won't have it
 * installed. Every code path here detects the binary up front and returns a
 * structured, friendly error (never throws / crashes the MCP server) when it
 * is absent.
 *
 * Hygiene ported from `.claude/scripts/lib/android-cli.sh`:
 *   - `--no-metrics` is passed on every invocation (keeps telemetry off and
 *     output clean).
 *   - The CLI prints a one-time terms-of-service blurb to stderr on its first
 *     invocation; we run a throwaway `--version` once per process to consume
 *     it so the real `docs` call returns clean stdout.
 */

import { execFile } from "node:child_process";

// ─── Configuration ──────────────────────────────────────────────────────────

/** Global flags applied to every `android` invocation (telemetry off). */
const ANDROID_CLI_GLOBAL_FLAGS = ["--no-metrics"] as const;

/** Hard cap on a single `android docs` call so a hung CLI can't wedge MCP. */
const ANDROID_CLI_TIMEOUT_MS = 20_000;

/** Cap the captured stdout/stderr so a pathological response can't blow memory. */
const ANDROID_CLI_MAX_BUFFER = 4 * 1024 * 1024; // 4 MB

const INSTALL_URL = "https://developer.android.com/tools/agents/android-cli";

// ─── Public types ───────────────────────────────────────────────────────────

export type AndroidDocsErrorCode = "cli_missing" | "invalid_input" | "cli_error" | "timeout";

export interface AndroidDocsSuccess {
  ok: true;
  /** The raw text the `android docs` subcommand printed to stdout. */
  output: string;
}

export interface AndroidDocsError {
  ok: false;
  code: AndroidDocsErrorCode;
  message: string;
}

export type AndroidDocsResult = AndroidDocsSuccess | AndroidDocsError;

// ─── CLI detection ───────────────────────────────────────────────────────────

/**
 * Whether the first-run ToS blurb has already been consumed for this process.
 * The `android` CLI prints its terms-of-service notice to stderr exactly once
 * per host; running any command absorbs it. We do it lazily, once.
 */
let tosConsumed = false;

/** Cached binary-presence result for the lifetime of the MCP process. */
let cliPresenceCache: boolean | undefined;

/**
 * Promisified `execFile` returning stdout + stderr, never rejecting.
 * `error` is non-null when the process exits non-zero, is killed, or cannot
 * be spawned (`ENOENT` when the binary is absent).
 */
function run(
  file: string,
  args: readonly string[]
): Promise<{ error: (Error & { code?: string | number }) | null; stdout: string; stderr: string }> {
  return new Promise((resolve) => {
    execFile(
      file,
      args,
      { timeout: ANDROID_CLI_TIMEOUT_MS, maxBuffer: ANDROID_CLI_MAX_BUFFER, encoding: "utf8" },
      (error, stdout, stderr) => {
        resolve({
          error: (error as (Error & { code?: string | number }) | null) ?? null,
          stdout: stdout ?? "",
          stderr: stderr ?? "",
        });
      }
    );
  });
}

/**
 * Detect the `android` CLI on PATH. Result is cached for the process lifetime
 * and also consumes the one-time ToS blurb on first success.
 *
 * Exported for tests; production callers go through `runAndroidDocs`.
 */
export async function isAndroidCliAvailable(): Promise<boolean> {
  if (cliPresenceCache !== undefined) return cliPresenceCache;
  const { error } = await run("android", [...ANDROID_CLI_GLOBAL_FLAGS, "--version"]);
  // ENOENT (binary not found) ⇒ unavailable. Any other exit still means the
  // binary spawned, so it IS present — and that --version run has now consumed
  // the first-run ToS notice.
  const spawnFailed = error?.code === "ENOENT";
  cliPresenceCache = !spawnFailed;
  if (cliPresenceCache) tosConsumed = true;
  return cliPresenceCache;
}

/** Test-only: reset the process-scoped CLI detection / ToS caches. */
export function __resetAndroidCliCache(): void {
  cliPresenceCache = undefined;
  tosConsumed = false;
}

function cliMissingError(): AndroidDocsError {
  return {
    ok: false,
    code: "cli_missing",
    message: [
      "This tool needs Google's `android` CLI, which is not installed on this MCP host.",
      "",
      "`android docs` is an optional runtime dependency — `sceneview-mcp` works fine without it,",
      "but `search_android_docs` / `fetch_android_doc` cannot run until the CLI is on PATH.",
      "",
      `Install it from ${INSTALL_URL} (or run \`bash .claude/scripts/android-env-check.sh --fix\``,
      "from a SceneView checkout), then retry.",
    ].join("\n"),
  };
}

// ─── Core runner ─────────────────────────────────────────────────────────────

/**
 * Run an `android docs <subcommand> <arg>` invocation and return its stdout
 * as a structured result. Never throws.
 */
async function runAndroidDocs(
  subcommand: "search" | "fetch",
  arg: string
): Promise<AndroidDocsResult> {
  if (!(await isAndroidCliAvailable())) {
    return cliMissingError();
  }

  // Belt-and-braces: ensure the first-run ToS notice is consumed. Normally
  // `isAndroidCliAvailable()` already did this via its `--version` probe, but
  // a caller could have populated `cliPresenceCache` some other way.
  if (!tosConsumed) {
    await run("android", [...ANDROID_CLI_GLOBAL_FLAGS, "--version"]);
    tosConsumed = true;
  }

  const { error, stdout, stderr } = await run("android", [
    ...ANDROID_CLI_GLOBAL_FLAGS,
    "docs",
    subcommand,
    arg,
  ]);

  if (error) {
    // `execFile` sets `killed` + `signal` on a timeout kill.
    const killedByTimeout =
      (error as Error & { killed?: boolean }).killed === true ||
      (error as Error & { signal?: string }).signal === "SIGTERM";
    if (killedByTimeout) {
      return {
        ok: false,
        code: "timeout",
        message: `\`android docs ${subcommand}\` did not finish within ${ANDROID_CLI_TIMEOUT_MS / 1000}s.`,
      };
    }
    const detail = (stderr || stdout || error.message).trim();
    return {
      ok: false,
      code: "cli_error",
      message: `\`android docs ${subcommand}\` failed: ${detail}`,
    };
  }

  return { ok: true, output: stdout.trim() };
}

// ─── Public API: search ──────────────────────────────────────────────────────

/**
 * Search the stock Android documentation knowledge base.
 *
 * @param query Free-text query, e.g. `"LazyColumn paging"`.
 */
export async function searchAndroidDocs(query: string): Promise<AndroidDocsResult> {
  if (typeof query !== "string" || query.trim().length === 0) {
    return {
      ok: false,
      code: "invalid_input",
      message: "Missing required parameter: `query` must be a non-empty string.",
    };
  }
  return runAndroidDocs("search", query.trim());
}

// ─── Public API: fetch ───────────────────────────────────────────────────────

/**
 * Fetch a single Android documentation entry by its knowledge-base URI.
 *
 * @param uri A `kb://...` URI as returned by `search_android_docs`. A bare
 *            path (no scheme) is tolerated and normalised to `kb://`.
 */
export async function fetchAndroidDoc(uri: string): Promise<AndroidDocsResult> {
  if (typeof uri !== "string" || uri.trim().length === 0) {
    return {
      ok: false,
      code: "invalid_input",
      message: "Missing required parameter: `uri` must be a non-empty `kb://...` string.",
    };
  }
  let normalised = uri.trim();
  if (!normalised.startsWith("kb://")) {
    // Tolerate a bare path or a leading slash — normalise to a kb:// URI so an
    // assistant that drops the scheme still gets a result.
    normalised = `kb://${normalised.replace(/^\/+/, "")}`;
  }
  return runAndroidDocs("fetch", normalised);
}

// ─── Formatting ──────────────────────────────────────────────────────────────

/** Render a search result as the markdown text block the MCP dispatcher returns. */
export function formatAndroidDocsSearch(query: string, result: AndroidDocsResult): string {
  if (!result.ok) return result.message;
  if (result.output.length === 0) {
    return `No Android documentation entries found for "${query}". Try a broader query.`;
  }
  return [
    `## Android docs — search results for "${query}"`,
    "",
    result.output,
    "",
    "---",
    "Use `fetch_android_doc` with a `kb://...` URI above to read a full entry.",
  ].join("\n");
}

/** Render a fetch result as the markdown text block the MCP dispatcher returns. */
export function formatAndroidDocsFetch(uri: string, result: AndroidDocsResult): string {
  if (!result.ok) return result.message;
  if (result.output.length === 0) {
    return `No Android documentation entry found at \`${uri}\`.`;
  }
  return [`## Android docs — \`${uri}\``, "", result.output].join("\n");
}
