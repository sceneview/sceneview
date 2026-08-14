/**
 * Tests for the sponsor CTA appended to tool responses every N calls.
 *
 * The CTA is a non-intrusive monetization hook: every 10 tool calls, a single
 * line pointing to GitHub Sponsors is appended after the standard disclaimer.
 * Users can opt out with `SCENEVIEW_SPONSOR_CTA=0`.
 */

import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { __resetSponsorCounter, dispatchTool } from "./index.js";

const SPONSOR_MARKER = "github.com/sponsors/sceneview";

async function callStaticTool(): Promise<string> {
  // `get_platform_roadmap` is one of the simplest tools — no args, no side
  // effects, wraps a static markdown through `withDisclaimer`. Perfect to
  // exercise the counter without touching other concerns.
  const result = await dispatchTool("get_platform_roadmap", {});
  expect(result.isError).toBeFalsy();
  expect(result.content.length).toBeGreaterThan(0);
  return result.content[result.content.length - 1].text;
}

describe("sponsor CTA counter", () => {
  beforeEach(() => {
    __resetSponsorCounter();
    delete process.env.SCENEVIEW_SPONSOR_CTA;
  });

  afterEach(() => {
    __resetSponsorCounter();
    delete process.env.SCENEVIEW_SPONSOR_CTA;
  });

  it("does not show the CTA on the first 9 calls", async () => {
    for (let i = 1; i <= 9; i++) {
      const text = await callStaticTool();
      expect(text, `call #${i}`).not.toContain(SPONSOR_MARKER);
    }
  });

  it("shows the CTA on the 10th call", async () => {
    for (let i = 1; i <= 9; i++) await callStaticTool();
    const text = await callStaticTool();
    expect(text).toContain(SPONSOR_MARKER);
  });

  it("shows the CTA again on the 20th call (not just once)", async () => {
    for (let i = 1; i <= 19; i++) await callStaticTool();
    const text = await callStaticTool();
    expect(text).toContain(SPONSOR_MARKER);
  });

  it("does not show the CTA on calls 11-19 (between triggers)", async () => {
    for (let i = 1; i <= 10; i++) await callStaticTool();
    for (let i = 11; i <= 19; i++) {
      const text = await callStaticTool();
      expect(text, `call #${i}`).not.toContain(SPONSOR_MARKER);
    }
  });

  it("never shows the CTA when SCENEVIEW_SPONSOR_CTA=0", async () => {
    process.env.SCENEVIEW_SPONSOR_CTA = "0";
    for (let i = 1; i <= 25; i++) {
      const text = await callStaticTool();
      expect(text, `call #${i}`).not.toContain(SPONSOR_MARKER);
    }
  });

  it("still appends the legal disclaimer on every call, regardless of CTA", async () => {
    const first = await callStaticTool();
    expect(first).toContain("TERMS.md");
    // Force a CTA on call 10
    for (let i = 2; i <= 10; i++) await callStaticTool();
    const tenth = await callStaticTool(); // now call 11 with counter at 11
    // The 11th call should still have disclaimer even though it won't have CTA
    expect(tenth).toContain("TERMS.md");
  });

  it("counter is independent per process (module-scoped)", async () => {
    // After reset, a single call should NOT trigger the CTA
    __resetSponsorCounter();
    const text = await callStaticTool();
    expect(text).not.toContain(SPONSOR_MARKER);
  });
});
