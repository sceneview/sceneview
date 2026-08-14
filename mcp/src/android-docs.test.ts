import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// ─── execFile mock ───────────────────────────────────────────────────────────
//
// android-docs.ts wraps `node:child_process` execFile. We mock it so the test
// suite never shells out to a real `android` CLI (which is absent on CI).
// `execFileMock` is the per-test programmable double.

const execFileMock = vi.fn();

vi.mock("node:child_process", () => ({
  execFile: (
    file: string,
    args: readonly string[],
    _opts: unknown,
    cb: (err: unknown, stdout: string, stderr: string) => void
  ) => execFileMock(file, args, cb),
}));

// Imported AFTER the mock is registered.
const {
  searchAndroidDocs,
  fetchAndroidDoc,
  isAndroidCliAvailable,
  __resetAndroidCliCache,
  formatAndroidDocsSearch,
  formatAndroidDocsFetch,
} = await import("./android-docs.js");

// ─── Mock programming helpers ────────────────────────────────────────────────

/** Make every `execFile` call behave as if the `android` binary is missing. */
function programCliMissing(): void {
  execFileMock.mockImplementation((_file, _args, cb) => {
    const err = new Error("spawn android ENOENT") as Error & { code: string };
    err.code = "ENOENT";
    cb(err, "", "");
  });
}

/**
 * Make the `--version` probe succeed and `docs` calls return `stdout`.
 * `docsImpl` lets a test override the `docs` branch (e.g. to fail it).
 */
function programCliPresent(
  docsImpl: (subcommand: string, arg: string) => { err: unknown; stdout: string; stderr: string }
): void {
  execFileMock.mockImplementation((_file, args: readonly string[], cb) => {
    if (args.includes("--version")) {
      cb(null, "android 0.7.15411012\n", "");
      return;
    }
    // args: ["--no-metrics", "docs", <sub>, <arg>]
    const docsIdx = args.indexOf("docs");
    const sub = args[docsIdx + 1];
    const arg = args[docsIdx + 2];
    const { err, stdout, stderr } = docsImpl(sub, arg);
    cb(err, stdout, stderr);
  });
}

beforeEach(() => {
  execFileMock.mockReset();
  __resetAndroidCliCache();
});

afterEach(() => {
  __resetAndroidCliCache();
});

// ─── CLI detection ───────────────────────────────────────────────────────────

describe("isAndroidCliAvailable", () => {
  it("returns false when the binary is not on PATH (ENOENT)", async () => {
    programCliMissing();
    expect(await isAndroidCliAvailable()).toBe(false);
  });

  it("returns true when the binary spawns", async () => {
    programCliPresent(() => ({ err: null, stdout: "", stderr: "" }));
    expect(await isAndroidCliAvailable()).toBe(true);
  });

  it("caches the result — only probes once per process", async () => {
    programCliPresent(() => ({ err: null, stdout: "", stderr: "" }));
    await isAndroidCliAvailable();
    await isAndroidCliAvailable();
    const versionProbes = execFileMock.mock.calls.filter((c) =>
      (c[1] as string[]).includes("--version")
    );
    expect(versionProbes.length).toBe(1);
  });
});

// ─── search_android_docs ─────────────────────────────────────────────────────

describe("searchAndroidDocs", () => {
  it("rejects an empty query without touching the CLI", async () => {
    const result = await searchAndroidDocs("   ");
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.code).toBe("invalid_input");
    expect(execFileMock).not.toHaveBeenCalled();
  });

  it("returns a graceful cli_missing error when the android CLI is absent", async () => {
    programCliMissing();
    const result = await searchAndroidDocs("LazyColumn paging");
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.code).toBe("cli_missing");
      expect(result.message).toMatch(/android.*CLI/i);
      expect(result.message).toMatch(/developer\.android\.com/);
    }
  });

  it("passes --no-metrics and the trimmed query to `android docs search`", async () => {
    let seenArgs: string[] = [];
    programCliPresent((sub, arg) => {
      if (sub === "search") seenArgs = ["docs", sub, arg];
      return { err: null, stdout: "kb://compose/lists/lazy-column — LazyColumn", stderr: "" };
    });
    const result = await searchAndroidDocs("  LazyColumn paging  ");
    expect(result.ok).toBe(true);
    if (result.ok) expect(result.output).toContain("kb://compose/lists/lazy-column");
    // Verify the actual invocation carried --no-metrics + trimmed query.
    const docsCall = execFileMock.mock.calls.find((c) => (c[1] as string[]).includes("docs"));
    expect(docsCall?.[1]).toEqual(["--no-metrics", "docs", "search", "LazyColumn paging"]);
  });

  it("maps a non-zero CLI exit to a cli_error result (no throw)", async () => {
    programCliPresent((sub) => {
      if (sub === "search") {
        return { err: new Error("exit 1"), stdout: "", stderr: "knowledge base unavailable" };
      }
      return { err: null, stdout: "", stderr: "" };
    });
    const result = await searchAndroidDocs("anything");
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.code).toBe("cli_error");
      expect(result.message).toContain("knowledge base unavailable");
    }
  });

  it("maps a timeout kill to a timeout result", async () => {
    programCliPresent((sub) => {
      if (sub === "search") {
        const err = new Error("killed") as Error & { killed: boolean };
        err.killed = true;
        return { err, stdout: "", stderr: "" };
      }
      return { err: null, stdout: "", stderr: "" };
    });
    const result = await searchAndroidDocs("slow query");
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.code).toBe("timeout");
  });
});

// ─── fetch_android_doc ───────────────────────────────────────────────────────

describe("fetchAndroidDoc", () => {
  it("rejects an empty uri without touching the CLI", async () => {
    const result = await fetchAndroidDoc("");
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.code).toBe("invalid_input");
    expect(execFileMock).not.toHaveBeenCalled();
  });

  it("returns cli_missing when the CLI is absent", async () => {
    programCliMissing();
    const result = await fetchAndroidDoc("kb://compose/lists/lazy-column");
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.code).toBe("cli_missing");
  });

  it("passes a kb:// uri through verbatim", async () => {
    programCliPresent(() => ({ err: null, stdout: "# LazyColumn\nFull entry text.", stderr: "" }));
    const result = await fetchAndroidDoc("kb://compose/lists/lazy-column");
    expect(result.ok).toBe(true);
    const docsCall = execFileMock.mock.calls.find((c) => (c[1] as string[]).includes("docs"));
    expect(docsCall?.[1]).toEqual([
      "--no-metrics",
      "docs",
      "fetch",
      "kb://compose/lists/lazy-column",
    ]);
  });

  it("normalises a bare path to a kb:// uri", async () => {
    programCliPresent(() => ({ err: null, stdout: "entry", stderr: "" }));
    await fetchAndroidDoc("/compose/lists/lazy-column");
    const docsCall = execFileMock.mock.calls.find((c) => (c[1] as string[]).includes("docs"));
    expect(docsCall?.[1]?.[3]).toBe("kb://compose/lists/lazy-column");
  });
});

// ─── formatting ──────────────────────────────────────────────────────────────

describe("formatAndroidDocsSearch / formatAndroidDocsFetch", () => {
  it("renders a successful search as markdown with a fetch hint", () => {
    const text = formatAndroidDocsSearch("lazycolumn", {
      ok: true,
      output: "kb://compose/lists/lazy-column — LazyColumn",
    });
    expect(text).toContain("## Android docs");
    expect(text).toContain("kb://compose/lists/lazy-column");
    expect(text).toContain("fetch_android_doc");
  });

  it("renders an empty search result clearly", () => {
    const text = formatAndroidDocsSearch("zzzqqq", { ok: true, output: "" });
    expect(text).toMatch(/No Android documentation entries found/i);
  });

  it("passes an error message through unchanged", () => {
    const text = formatAndroidDocsSearch("x", {
      ok: false,
      code: "cli_missing",
      message: "android CLI not installed",
    });
    expect(text).toBe("android CLI not installed");
  });

  it("renders a fetched entry with its uri as a heading", () => {
    const text = formatAndroidDocsFetch("kb://compose/foo", {
      ok: true,
      output: "Full doc body.",
    });
    expect(text).toContain("kb://compose/foo");
    expect(text).toContain("Full doc body.");
  });
});
