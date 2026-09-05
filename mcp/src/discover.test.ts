import { SUPPORTED_PROTOCOL_VERSIONS } from "@modelcontextprotocol/sdk/types.js";
import { describe, expect, it } from "vitest";
import {
  buildDiscoverResult,
  DISCOVER_METHOD,
  DISCOVER_TTL_MS,
  DiscoverRequestSchema,
  SERVER_CAPABILITIES,
  SERVER_INFO,
} from "./discover.js";
import { PACKAGE_VERSION } from "./generated/version.js";
import { MCP_APP_MIME_TYPE, UI_EXTENSION_ID } from "./widgets.js";

// The conformance suite (@hasmcp/mcp-spec-test) checks these five fields on the
// `server/discover` result; `serverInfo` is expected on top of them. Keep this
// list in sync with `spec/2026-07-28/schema.json` → `DiscoverResult.required`.
const REQUIRED_FIELDS = ["cacheScope", "capabilities", "resultType", "supportedVersions", "ttlMs"];

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

describe("server/discover request schema", () => {
  it("accepts a version-less, session-less request", () => {
    const parsed = DiscoverRequestSchema.parse({
      method: DISCOVER_METHOD,
      params: {},
    });
    expect(parsed.method).toBe("server/discover");
  });

  it("rejects any other method (so the SDK routes on the literal)", () => {
    expect(() => DiscoverRequestSchema.parse({ method: "server/discovery", params: {} })).toThrow();
  });
});

describe("buildDiscoverResult", () => {
  it("carries every field the 2026-07-28 schema requires", () => {
    const result = buildDiscoverResult();
    for (const field of REQUIRED_FIELDS) {
      expect(result, `missing required field: ${field}`).toHaveProperty(field);
    }
    expect(result.resultType).toBe("complete");
  });

  it("advertises only revisions the SDK can actually serve", () => {
    const result = buildDiscoverResult();
    expect(result.supportedVersions.length).toBeGreaterThan(0);
    expect(result.supportedVersions).toEqual([...SUPPORTED_PROTOCOL_VERSIONS]);
    for (const version of result.supportedVersions) {
      expect(version).toMatch(ISO_DATE);
    }
  });

  it("returns a copy, so a caller cannot mutate the SDK constant", () => {
    const result = buildDiscoverResult();
    result.supportedVersions.push("1999-01-01");
    expect(buildDiscoverResult().supportedVersions).toEqual([...SUPPORTED_PROTOCOL_VERSIONS]);
  });

  it("gives usable cache hints", () => {
    const result = buildDiscoverResult();
    expect(result.cacheScope).toBe("public");
    expect(result.ttlMs).toBe(DISCOVER_TTL_MS);
    expect(result.ttlMs).toBeGreaterThan(0);
  });

  it("stays stable across calls, as anything cacheable must", () => {
    expect(buildDiscoverResult()).toEqual(buildDiscoverResult());
  });

  it("reports the same identity and capabilities as the handshake", () => {
    const result = buildDiscoverResult();
    // `index.ts` builds the `Server` from these very constants, so equality
    // here is what proves discover and `initialize` cannot drift apart.
    expect(result.serverInfo).toEqual({ ...SERVER_INFO });
    expect(result.serverInfo).toEqual({ name: "sceneview-mcp", version: PACKAGE_VERSION });
    expect(result.capabilities).toEqual({ ...SERVER_CAPABILITIES });
    expect(result.capabilities).toHaveProperty("tools");
    expect(result.capabilities).toHaveProperty("resources");
  });

  it("declares the MCP Apps extension, with the mime type it serves", () => {
    // #3192: the widget resource, its mime type and the `_meta.ui` pointers
    // all shipped while the extension itself was named nowhere, so a host
    // following the negotiation rules had nothing to switch on. A modern
    // client never sends `initialize`, so `server/discover` is the ONLY place
    // it can read this.
    const extensions = buildDiscoverResult().capabilities.extensions as Record<string, unknown>;
    expect(extensions).toBeDefined();
    expect(extensions[UI_EXTENSION_ID]).toEqual({ mimeTypes: [MCP_APP_MIME_TYPE] });
    expect(MCP_APP_MIME_TYPE).toBe("text/html;profile=mcp-app");
  });

  it("includes instructions that point at the API resource", () => {
    expect(buildDiscoverResult().instructions).toContain("sceneview://api");
  });
});
