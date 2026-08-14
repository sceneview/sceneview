import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  formatSearchResults,
  type SearchModelsError,
  type SearchModelsSuccess,
  searchModels,
} from "./search-models.js";

// ─── Test helpers ───────────────────────────────────────────────────────────

const API_KEY_ENV = "SKETCHFAB_API_KEY";

function mockSketchfabResponse(
  body: unknown,
  init: Partial<{ status: number; statusText: string; ok: boolean }> = {}
) {
  return {
    ok: init.ok ?? true,
    status: init.status ?? 200,
    statusText: init.statusText ?? "OK",
    json: async () => body,
  } as unknown as Response;
}

const SAMPLE_PAYLOAD = {
  results: [
    {
      uid: "abc123",
      name: "Low Poly Red Sports Car",
      viewerUrl: "https://sketchfab.com/3d-models/abc123",
      embedUrl: "https://sketchfab.com/models/abc123/embed",
      isDownloadable: true,
      faceCount: 12_345,
      vertexCount: 6_789,
      user: { username: "artist1", displayName: "Artist One" },
      thumbnails: {
        images: [
          { url: "https://img.sketchfab.com/thumb-small.jpg", width: 200, height: 200 },
          { url: "https://img.sketchfab.com/thumb-large.jpg", width: 1024, height: 1024 },
        ],
      },
      license: { label: "CC Attribution", slug: "by" },
    },
    {
      uid: "def456",
      name: "Classic Convertible",
      viewerUrl: "https://sketchfab.com/3d-models/def456",
      embedUrl: "https://sketchfab.com/models/def456/embed",
      isDownloadable: false,
      faceCount: 88_000,
      user: { displayName: "Vintage Studio" },
      thumbnails: {
        images: [{ url: "https://img.sketchfab.com/convertible.jpg", width: 512, height: 512 }],
      },
      license: { label: "CC Attribution-NonCommercial" },
    },
  ],
};

// ─── Environment management ─────────────────────────────────────────────────

let originalKey: string | undefined;

beforeEach(() => {
  originalKey = process.env[API_KEY_ENV];
  delete process.env[API_KEY_ENV];
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  if (originalKey === undefined) {
    delete process.env[API_KEY_ENV];
  } else {
    process.env[API_KEY_ENV] = originalKey;
  }
});

// ─── Happy path ─────────────────────────────────────────────────────────────

describe("searchModels — happy path", () => {
  it("returns normalized results when the API key is set and Sketchfab responds OK", async () => {
    process.env[API_KEY_ENV] = "fake-key-xyz";
    const fetchMock = vi.fn().mockResolvedValue(mockSketchfabResponse(SAMPLE_PAYLOAD));
    vi.stubGlobal("fetch", fetchMock);

    const result = await searchModels({ query: "red sports car" });

    expect(result.ok).toBe(true);
    const success = result as SearchModelsSuccess;
    expect(success.results).toHaveLength(2);
    expect(success.query).toBe("red sports car");
    expect(success.totalReturned).toBe(2);

    const first = success.results[0]!;
    expect(first.uid).toBe("abc123");
    expect(first.name).toBe("Low Poly Red Sports Car");
    expect(first.author).toBe("Artist One");
    expect(first.license).toBe("CC Attribution");
    expect(first.downloadable).toBe(true);
    expect(first.triangleCount).toBe(12_345);
    // Picks the largest thumbnail
    expect(first.thumbnailUrl).toBe("https://img.sketchfab.com/thumb-large.jpg");
    expect(first.embedUrl).toBe("https://sketchfab.com/models/abc123/embed");

    // Verify the fetch call shape
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(String(url)).toContain("https://api.sketchfab.com/v3/search");
    expect(String(url)).toContain("type=models");
    expect(String(url)).toContain("q=red+sports+car");
    expect(String(url)).toContain("downloadable=true");
    expect((init as RequestInit).headers).toMatchObject({
      Authorization: "Token fake-key-xyz",
    });
  });

  it("honors maxResults and clamps it to [1, 10]", async () => {
    process.env[API_KEY_ENV] = "fake-key";
    const fetchMock = vi.fn().mockResolvedValue(mockSketchfabResponse(SAMPLE_PAYLOAD));
    vi.stubGlobal("fetch", fetchMock);

    await searchModels({ query: "car", maxResults: 999 });
    expect(String(fetchMock.mock.calls[0]![0])).toContain("count=10");

    fetchMock.mockClear();
    await searchModels({ query: "car", maxResults: -5 });
    expect(String(fetchMock.mock.calls[0]![0])).toContain("count=1");

    fetchMock.mockClear();
    await searchModels({ query: "car", maxResults: 4 });
    expect(String(fetchMock.mock.calls[0]![0])).toContain("count=4");
  });

  it("passes category and downloadable=false through to the query string", async () => {
    process.env[API_KEY_ENV] = "fake-key";
    const fetchMock = vi.fn().mockResolvedValue(mockSketchfabResponse({ results: [] }));
    vi.stubGlobal("fetch", fetchMock);

    await searchModels({
      query: "bmw",
      category: "cars-vehicles",
      downloadable: false,
    });

    const url = String(fetchMock.mock.calls[0]![0]);
    expect(url).toContain("categories=cars-vehicles");
    expect(url).not.toContain("downloadable=true");
  });

  it("slices results to the requested count even if the API over-returns", async () => {
    process.env[API_KEY_ENV] = "fake-key";
    const many = {
      results: Array.from({ length: 12 }, (_, i) => ({
        uid: `uid${i}`,
        name: `Model ${i}`,
        user: { username: `user${i}` },
        thumbnails: { images: [{ url: "https://img/x.jpg", width: 10, height: 10 }] },
        license: { label: "CC0" },
        isDownloadable: true,
        faceCount: 1000,
        viewerUrl: "https://sketchfab.com/x",
        embedUrl: "https://sketchfab.com/x/embed",
      })),
    };
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(mockSketchfabResponse(many)));

    const result = await searchModels({ query: "car", maxResults: 3 });
    expect(result.ok).toBe(true);
    expect((result as SearchModelsSuccess).results).toHaveLength(3);
  });
});

// ─── Missing key ────────────────────────────────────────────────────────────

describe("searchModels — missing API key", () => {
  it("returns a friendly error when SKETCHFAB_API_KEY is not set", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const result = await searchModels({ query: "robot" });

    expect(result.ok).toBe(false);
    const error = result as SearchModelsError;
    expect(error.code).toBe("missing_key");
    expect(error.message).toContain("sketchfab.com/register");
    expect(error.message).toContain("SKETCHFAB_API_KEY");
    // No network call should be made.
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("returns missing_key when the env var is an empty string", async () => {
    process.env[API_KEY_ENV] = "   ";
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const result = await searchModels({ query: "robot" });
    expect(result.ok).toBe(false);
    expect((result as SearchModelsError).code).toBe("missing_key");
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

// ─── Error responses ────────────────────────────────────────────────────────

describe("searchModels — API error responses", () => {
  it("maps HTTP 401 to an `unauthorized` error", async () => {
    process.env[API_KEY_ENV] = "bad-key";
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          mockSketchfabResponse({}, { ok: false, status: 401, statusText: "Unauthorized" })
        )
    );

    const result = await searchModels({ query: "robot" });
    expect(result.ok).toBe(false);
    const err = result as SearchModelsError;
    expect(err.code).toBe("unauthorized");
    expect(err.message).toMatch(/401/);
    expect(err.message).toContain("sketchfab.com");
  });

  it("also maps HTTP 403 to `unauthorized`", async () => {
    process.env[API_KEY_ENV] = "scoped-key";
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          mockSketchfabResponse({}, { ok: false, status: 403, statusText: "Forbidden" })
        )
    );

    const result = await searchModels({ query: "robot" });
    expect((result as SearchModelsError).code).toBe("unauthorized");
  });

  it("maps HTTP 429 to a `rate_limited` error", async () => {
    process.env[API_KEY_ENV] = "fake-key";
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          mockSketchfabResponse({}, { ok: false, status: 429, statusText: "Too Many Requests" })
        )
    );

    const result = await searchModels({ query: "robot" });
    expect(result.ok).toBe(false);
    const err = result as SearchModelsError;
    expect(err.code).toBe("rate_limited");
    expect(err.message).toMatch(/rate limit/i);
  });

  it("maps other HTTP errors to `bad_response`", async () => {
    process.env[API_KEY_ENV] = "fake-key";
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          mockSketchfabResponse({}, { ok: false, status: 500, statusText: "Server Error" })
        )
    );

    const result = await searchModels({ query: "robot" });
    expect(result.ok).toBe(false);
    expect((result as SearchModelsError).code).toBe("bad_response");
  });

  it("maps fetch rejections (network errors) to `network`", async () => {
    process.env[API_KEY_ENV] = "fake-key";
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("ECONNREFUSED")));

    const result = await searchModels({ query: "robot" });
    expect(result.ok).toBe(false);
    const err = result as SearchModelsError;
    expect(err.code).toBe("network");
    expect(err.message).toContain("ECONNREFUSED");
  });

  it("maps JSON parse failures to `bad_response`", async () => {
    process.env[API_KEY_ENV] = "fake-key";
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        statusText: "OK",
        json: async () => {
          throw new Error("Unexpected token");
        },
      } as unknown as Response)
    );

    const result = await searchModels({ query: "robot" });
    expect(result.ok).toBe(false);
    expect((result as SearchModelsError).code).toBe("bad_response");
  });
});

// ─── Empty results ──────────────────────────────────────────────────────────

describe("searchModels — empty results", () => {
  it("returns an ok result with zero items when Sketchfab returns no matches", async () => {
    process.env[API_KEY_ENV] = "fake-key";
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(mockSketchfabResponse({ results: [] })));

    const result = await searchModels({ query: "nonexistentxyz" });
    expect(result.ok).toBe(true);
    expect((result as SearchModelsSuccess).results).toEqual([]);
    expect((result as SearchModelsSuccess).totalReturned).toBe(0);
  });

  it("tolerates a missing `results` field in the response", async () => {
    process.env[API_KEY_ENV] = "fake-key";
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(mockSketchfabResponse({})));

    const result = await searchModels({ query: "anything" });
    expect(result.ok).toBe(true);
    expect((result as SearchModelsSuccess).results).toHaveLength(0);
  });
});

// ─── Input validation ──────────────────────────────────────────────────────

describe("searchModels — input validation", () => {
  it("rejects an empty query without hitting the network", async () => {
    process.env[API_KEY_ENV] = "fake-key";
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const result = await searchModels({ query: "   " });
    expect(result.ok).toBe(false);
    expect((result as SearchModelsError).code).toBe("invalid_input");
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

// ─── formatSearchResults ────────────────────────────────────────────────────

describe("formatSearchResults", () => {
  it("renders a markdown block for a successful search", () => {
    const ok: SearchModelsSuccess = {
      ok: true,
      query: "red sports car",
      totalReturned: 1,
      results: [
        {
          uid: "abc123",
          name: "Low Poly Red Sports Car",
          author: "Artist One",
          viewerUrl: "https://sketchfab.com/3d-models/abc123",
          thumbnailUrl: "https://img.sketchfab.com/thumb-large.jpg",
          license: "CC Attribution",
          downloadable: true,
          triangleCount: 12_345,
          embedUrl: "https://sketchfab.com/models/abc123/embed",
        },
      ],
    };
    const text = formatSearchResults(ok);
    expect(text).toContain('Sketchfab results for "red sports car"');
    expect(text).toContain("Low Poly Red Sports Car");
    expect(text).toContain("Artist One");
    expect(text).toContain("CC Attribution");
    expect(text).toContain("12,345 triangles");
    expect(text).toContain("downloadable");
    expect(text).toContain("abc123");
    expect(text).toContain("rememberModelInstance");
  });

  it("renders a friendly 'no results' message when the search returned zero items", () => {
    const ok: SearchModelsSuccess = {
      ok: true,
      query: "xyz",
      totalReturned: 0,
      results: [],
    };
    const text = formatSearchResults(ok);
    expect(text).toContain("No Sketchfab models found");
    expect(text).toContain("broader query");
  });

  it("passes through error messages untouched", () => {
    const err: SearchModelsError = {
      ok: false,
      code: "unauthorized",
      message: "Sketchfab rejected the API key.",
    };
    expect(formatSearchResults(err)).toBe("Sketchfab rejected the API key.");
  });
});
