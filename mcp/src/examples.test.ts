import { describe, expect, it } from "vitest";
import { DEMO_WITH_SETTINGS_EXAMPLE, SKETCHFAB_STREAMING_EXAMPLE } from "./examples.js";

// These resources surface examples in the MCP client's context window, so we
// pin their shape so we don't accidentally ship empty / dramatically-resized
// strings. Resource budget = ~4 KB each (well under any client's truncation
// threshold).

describe("examples module — inline MCP resource bodies", () => {
  describe("DEMO_WITH_SETTINGS_EXAMPLE", () => {
    it("starts with the expected H1 so MCP clients render it correctly", () => {
      expect(DEMO_WITH_SETTINGS_EXAMPLE.startsWith("# Example — DemoScaffold v2")).toBe(true);
    });

    it("references DemoScaffold + ModalBottomSheet so AI agents recognize the pattern", () => {
      expect(DEMO_WITH_SETTINGS_EXAMPLE).toContain("DemoScaffold(");
      expect(DEMO_WITH_SETTINGS_EXAMPLE).toContain("ModalBottomSheet");
    });

    it("references the issue + PR that delivered the pattern", () => {
      expect(DEMO_WITH_SETTINGS_EXAMPLE).toContain("#1154");
      expect(DEMO_WITH_SETTINGS_EXAMPLE).toContain("#1169");
    });

    it("stays under 4 KB so the resource list doesn't bloat client context", () => {
      expect(DEMO_WITH_SETTINGS_EXAMPLE.length).toBeLessThan(4_096);
    });

    it("points at the full recipe in docs/", () => {
      expect(DEMO_WITH_SETTINGS_EXAMPLE).toContain("docs/docs/recipes/demo-settings-sheet.md");
    });
  });

  describe("SKETCHFAB_STREAMING_EXAMPLE", () => {
    it("starts with the expected H1 so MCP clients render it correctly", () => {
      expect(SKETCHFAB_STREAMING_EXAMPLE.startsWith("# Example — Stream Sketchfab")).toBe(true);
    });

    it("references SketchfabAssetResolver + SampleAssets so AI agents recognize the pattern", () => {
      expect(SKETCHFAB_STREAMING_EXAMPLE).toContain("SketchfabAssetResolver");
      expect(SKETCHFAB_STREAMING_EXAMPLE).toContain("SampleAssets.byCategory");
    });

    it("calls out the CC-BY 4.0 license constraint", () => {
      expect(SKETCHFAB_STREAMING_EXAMPLE).toContain("CC-BY 4.0");
    });

    it("calls out the no-WebView hard rule", () => {
      expect(SKETCHFAB_STREAMING_EXAMPLE).toContain("No Sketchfab WebView");
    });

    it("calls out the per-slug bundled fallback contract", () => {
      expect(SKETCHFAB_STREAMING_EXAMPLE).toContain("bundled fallback");
    });

    it("references the umbrella issue", () => {
      expect(SKETCHFAB_STREAMING_EXAMPLE).toContain("#1152");
    });

    it("stays under 4 KB so the resource list doesn't bloat client context", () => {
      expect(SKETCHFAB_STREAMING_EXAMPLE.length).toBeLessThan(4_096);
    });

    it("points at the full recipe in docs/", () => {
      expect(SKETCHFAB_STREAMING_EXAMPLE).toContain("docs/docs/recipes/sketchfab-streaming.md");
    });
  });
});
