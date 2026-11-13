import { describe, expect, it } from "vitest";
import {
  buildSpatialAnswerSpec,
  encodeSpatialAnswer,
  type SpatialAnswerSpec,
  spatialAnswerUrl,
} from "./spatial-answer-link.js";
import { TOOL_DEFINITIONS } from "./tools/definitions.js";
import { dispatchTool } from "./tools/handler.js";

const desk = { widthCm: 80, heightCm: 75, depthCm: 40, label: "Desk" };
const expected: SpatialAnswerSpec = {
  v: 1,
  object: { kind: "box", sizeM: [0.8, 0.75, 0.4] },
  dimensionSource: "user",
  placement: "floor",
  label: "Desk",
};
const decode = (encoded: string) => JSON.parse(Buffer.from(encoded, "base64url").toString("utf8"));

describe("spatial answer links", () => {
  it("round-trips the desk in width, height, depth order", () => {
    const spec = buildSpatialAnswerSpec(desk);
    expect(spec).toEqual(expected);
    const url = new URL(spatialAnswerUrl(spec));
    expect(url.origin + url.pathname).toBe("https://sceneview.github.io/open");
    expect(url.searchParams.get("demo")).toBe("spatial-answer");
    expect(decode(url.searchParams.get("answer")!)).toEqual(expected);
  });

  it("uses unpadded base64url and preserves unicode", () => {
    for (const label of ["D", "De", "Desk", "🪑 Café 東京", "🪑".repeat(60)]) {
      const spec = buildSpatialAnswerSpec({ ...desk, label });
      expect(encodeSpatialAnswer(spec)).toMatch(/^[A-Za-z0-9_-]+$/);
      expect(decode(encodeSpatialAnswer(spec))).toEqual(spec);
    }
  });

  it.each([0, 1, -1, 1000.01, NaN, Infinity, -Infinity, "80", undefined, null])(
    "rejects invalid dimensions %s on every axis",
    (value) => {
      for (const axis of ["widthCm", "heightCm", "depthCm"]) {
        expect(() => buildSpatialAnswerSpec({ ...desk, [axis]: value })).toThrow(/finite number/);
      }
    }
  );

  it("accepts the upper bound and values above the exclusive lower bound", () => {
    const sizes = buildSpatialAnswerSpec({ ...desk, widthCm: 1000, heightCm: 1.001 }).object.sizeM;
    expect(sizes[0]).toBe(10);
    expect(sizes[1]).toBeCloseTo(0.01001);
  });

  it("limits labels to 60 characters and requires a string", () => {
    expect(() => buildSpatialAnswerSpec({ ...desk, label: "a".repeat(60) })).not.toThrow();
    for (const label of ["a".repeat(61), "🪑".repeat(61), undefined, 42]) {
      expect(() => buildSpatialAnswerSpec({ ...desk, label })).toThrow(/label/);
    }
  });

  it("supports table placement and estimated dimensions with strict enums", () => {
    expect(
      buildSpatialAnswerSpec({ ...desk, placement: "table", dimensionSource: "estimated" })
    ).toMatchObject({ placement: "table", dimensionSource: "estimated" });
    expect(() => buildSpatialAnswerSpec({ ...desk, placement: null })).toThrow(/placement/);
    for (const dimensionSource of ["measured", "guessed", null]) {
      expect(() => buildSpatialAnswerSpec({ ...desk, dimensionSource })).toThrow(/dimensionSource/);
    }
  });

  it("validates direct specs including v1, metre bounds and measured provenance", () => {
    expect(() =>
      encodeSpatialAnswer({ ...expected, v: 2 } as unknown as SpatialAnswerSpec)
    ).toThrow(/v must be 1/);
    for (const size of [0.01, 10.001, NaN]) {
      expect(() =>
        encodeSpatialAnswer({ ...expected, object: { kind: "box", sizeM: [size, 1, 1] } })
      ).toThrow(/metres/);
    }
    expect(decode(encodeSpatialAnswer({ ...expected, dimensionSource: "measured" }))).toMatchObject(
      { dimensionSource: "measured" }
    );
  });

  it("rejects encoded payloads over 2048 characters", () => {
    const oversized = { ...expected, extra: "x".repeat(2048) };
    expect(() => encodeSpatialAnswer(oversized)).toThrow(/2048/);
    expect(() => spatialAnswerUrl(oversized)).toThrow(/2048/);
  });
});

describe("show_in_space tool", () => {
  it("declares a closed output schema and read-only annotations", () => {
    const def = TOOL_DEFINITIONS.find((tool) => tool.name === "show_in_space");
    expect(def?.outputSchema?.additionalProperties).toBe(false);
    expect(def?.annotations).toMatchObject({
      readOnlyHint: true,
      destructiveHint: false,
      openWorldHint: false,
    });
  });

  it("returns the requested text and structured content without a disclaimer", async () => {
    const url = spatialAnswerUrl(expected);
    expect(await dispatchTool("show_in_space", desk)).toEqual({
      content: [
        {
          type: "text",
          text: `Desk: 80 × 75 × 40 cm — ${url}\nOpens at real scale in the SceneView app on Android; without the app, the page shows the size.`,
        },
      ],
      structuredContent: { url, label: "Desk", sizeCm: [80, 75, 40], placement: "floor" },
    });
  });

  it("propagates placement and provenance to the link", async () => {
    const result = await dispatchTool("show_in_space", {
      ...desk,
      placement: "table",
      dimensionSource: "estimated",
    });
    const url = new URL(result.structuredContent!.url as string);
    expect(decode(url.searchParams.get("answer")!)).toMatchObject({
      placement: "table",
      dimensionSource: "estimated",
    });
    expect(result.structuredContent?.placement).toBe("table");
  });

  it.each([
    {},
    { ...desk, widthCm: "80" },
    { ...desk, label: undefined },
    { ...desk, placement: "wall" },
    { ...desk, dimensionSource: "measured" },
  ])("returns a clear validation error for %j", async (args) => {
    const result = await dispatchTool("show_in_space", args);
    expect(result.isError).toBe(true);
    expect(result.structuredContent).toBeUndefined();
    expect(result.content[0]).toMatchObject({
      type: "text",
      text: expect.stringMatching(/^show_in_space: /),
    });
  });
});
