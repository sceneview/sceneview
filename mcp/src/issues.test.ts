import { beforeEach, describe, expect, it, vi } from "vitest";
import { COMMON_ISSUES, getCommonIssuesSummary, searchCommonIssues } from "./issues.js";

// We test the private formatIssues logic by calling fetchKnownIssues with a
// mocked global fetch so we never hit the real GitHub API in tests.

const mockIssues = [
  {
    number: 123,
    title: "SIGABRT on dispose",
    html_url: "https://github.com/sceneview/sceneview/issues/123",
    body: "Calling destroy() causes a native crash.",
    labels: [{ name: "bug" }],
    created_at: "2026-01-01T00:00:00Z",
    updated_at: "2026-01-15T00:00:00Z",
    user: { login: "testuser" },
  },
  {
    number: 124,
    title: "How do I add shadows?",
    html_url: "https://github.com/sceneview/sceneview/issues/124",
    body: "I want to enable shadows for my AR scene.",
    labels: [{ name: "question" }],
    created_at: "2026-01-02T00:00:00Z",
    updated_at: "2026-01-16T00:00:00Z",
    user: { login: "anotheruser" },
  },
];

beforeEach(() => {
  // Reset module cache so the in-memory cache is cleared between tests
  vi.resetModules();
});

describe("fetchKnownIssues", () => {
  it("returns markdown with issue titles when GitHub API succeeds", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => mockIssues,
      })
    );

    const { fetchKnownIssues } = await import("./issues.js");
    const result = await fetchKnownIssues();

    expect(result).toContain("SIGABRT on dispose");
    expect(result).toContain("#123");
    expect(result).toContain("How do I add shadows?");
    expect(result).toContain("#124");
    vi.unstubAllGlobals();
  });

  it("groups bug-labelled issues under Bug Reports", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => mockIssues,
      })
    );

    const { fetchKnownIssues } = await import("./issues.js");
    const result = await fetchKnownIssues();

    expect(result).toContain("Bug Reports");
    expect(result).toContain("SIGABRT on dispose");
    vi.unstubAllGlobals();
  });

  it("groups non-bug issues under Other Issues", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => mockIssues,
      })
    );

    const { fetchKnownIssues } = await import("./issues.js");
    const result = await fetchKnownIssues();

    expect(result).toContain("Other Issues");
    expect(result).toContain("How do I add shadows?");
    vi.unstubAllGlobals();
  });

  it("includes github URLs", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => mockIssues,
      })
    );

    const { fetchKnownIssues } = await import("./issues.js");
    const result = await fetchKnownIssues();

    expect(result).toContain("https://github.com/sceneview/sceneview/issues/123");
    vi.unstubAllGlobals();
  });

  it("includes body excerpt", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => mockIssues,
      })
    );

    const { fetchKnownIssues } = await import("./issues.js");
    const result = await fetchKnownIssues();

    expect(result).toContain("native crash");
    vi.unstubAllGlobals();
  });

  it("shows warning message when GitHub API fails", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: false,
        status: 403,
        statusText: "Forbidden",
      })
    );

    const { fetchKnownIssues } = await import("./issues.js");
    const result = await fetchKnownIssues();

    expect(result).toContain("403");
    vi.unstubAllGlobals();
  });

  it("shows warning message when fetch throws (network error)", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("Network error")));

    const { fetchKnownIssues } = await import("./issues.js");
    const result = await fetchKnownIssues();

    expect(result).toContain("Network error");
    vi.unstubAllGlobals();
  });

  it("shows celebration when there are no open issues", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => [],
      })
    );

    const { fetchKnownIssues } = await import("./issues.js");
    const result = await fetchKnownIssues();

    expect(result).toContain("No open issues");
    vi.unstubAllGlobals();
  });

  it("does not crash on items missing user / labels / updated_at", async () => {
    // A malformed API item — valid `number` + `title` so it passes the type
    // guard, but `user`, `labels` and `updated_at` are absent. Previously
    // formatIssues read `issue.user.login` / `issue.labels.some` and threw a
    // TypeError, killing the whole sceneview://known-issues resource.
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => [
          {
            number: 999,
            title: "Malformed issue with no user or labels",
          },
        ],
      })
    );

    const { fetchKnownIssues } = await import("./issues.js");
    const result = await fetchKnownIssues();

    expect(result).toContain("#999");
    expect(result).toContain("Malformed issue with no user or labels");
    expect(result).toContain("@unknown");
    vi.unstubAllGlobals();
  });

  it("normalizes string-form labels and unexpected label shapes", async () => {
    // GitHub's classic API can return labels as bare strings; defensively
    // accept both that and object form, dropping anything unrecognised.
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => [
          {
            number: 1000,
            title: "Issue with mixed label shapes",
            labels: ["bug", { name: "priority" }, 42, null],
            user: { login: "tester" },
            updated_at: "2026-02-01T00:00:00Z",
          },
        ],
      })
    );

    const { fetchKnownIssues } = await import("./issues.js");
    const result = await fetchKnownIssues();

    expect(result).toContain("Bug Reports");
    expect(result).toContain("[bug, priority]");
    vi.unstubAllGlobals();
  });
});

describe("COMMON_ISSUES", () => {
  it("has at least 10 common issues", () => {
    expect(COMMON_ISSUES.length).toBeGreaterThanOrEqual(10);
  });

  it("each issue has all required fields", () => {
    for (const issue of COMMON_ISSUES) {
      expect(issue.id).toBeTruthy();
      expect(issue.title).toBeTruthy();
      expect(issue.symptom).toBeTruthy();
      expect(issue.cause).toBeTruthy();
      expect(issue.solution).toBeTruthy();
      expect(issue.category).toBeTruthy();
    }
  });

  it("includes threading SIGABRT issue", () => {
    const threading = COMMON_ISSUES.find((i) => i.id === "threading-sigabrt");
    expect(threading).toBeDefined();
    expect(threading!.cause).toContain("main thread");
  });

  it("includes LightNode trailing lambda issue", () => {
    const light = COMMON_ISSUES.find((i) => i.id === "lightnode-trailing-lambda");
    expect(light).toBeDefined();
    expect(light!.cause).toContain("named parameter");
  });

  it("includes null model instance issue", () => {
    const nullModel = COMMON_ISSUES.find((i) => i.id === "null-model-instance");
    expect(nullModel).toBeDefined();
    expect(nullModel!.solution).toContain("src/main/assets");
  });
});

describe("getCommonIssuesSummary", () => {
  it("returns non-empty markdown", () => {
    const summary = getCommonIssuesSummary();
    expect(summary.length).toBeGreaterThan(500);
    expect(summary).toContain("Common Issues");
  });

  it("includes all issue titles", () => {
    const summary = getCommonIssuesSummary();
    for (const issue of COMMON_ISSUES) {
      expect(summary).toContain(issue.title);
    }
  });
});

describe("searchCommonIssues", () => {
  it("finds threading issues by keyword", () => {
    const results = searchCommonIssues("SIGABRT");
    expect(results.length).toBeGreaterThan(0);
    expect(results.some((r) => r.id === "threading-sigabrt")).toBe(true);
  });

  it("finds null model issues by keyword", () => {
    const results = searchCommonIssues("null");
    expect(results.length).toBeGreaterThan(0);
    expect(results.some((r) => r.id === "null-model-instance")).toBe(true);
  });

  it("finds AR drift issue", () => {
    const results = searchCommonIssues("drift");
    expect(results.length).toBeGreaterThan(0);
    expect(results.some((r) => r.id === "ar-worldposition-drift")).toBe(true);
  });

  it("returns empty for unrelated query", () => {
    const results = searchCommonIssues("xyznonexistent123");
    expect(results).toHaveLength(0);
  });
});
