import { describe, it, expect } from "vitest";
import { buildIssue } from "../src/issue.js";

describe("buildIssue", () => {
  it("neutralizes @mentions and #refs in the transcript", () => {
    const r = buildIssue({
      id: "abc",
      category: "bug",
      transcript: "ping @octocat and #1234 both broke",
      text: "",
      context: {},
      viewerUrl: "https://w/feedback/abc",
    });
    expect(r.body).not.toContain("@octocat");
    expect(r.body).not.toContain("#1234");
    expect(r.body).toContain("@​octocat");
    expect(r.body).toContain("#​1234");
  });

  it("escapes pipe characters in context values", () => {
    const r = buildIssue({
      id: "abc",
      category: "bug",
      transcript: "t",
      text: "",
      context: { device: "Pixel | 7a" },
      viewerUrl: "u",
    });
    expect(r.body).toContain("Pixel \\| 7a");
  });

  it("builds a [Bug] title with bug + user-feedback labels", () => {
    const r = buildIssue({
      id: "abc",
      category: "bug",
      transcript: "crash on tap",
      text: "",
      context: {},
      viewerUrl: "u",
    });
    expect(r.title).toBe("[Bug] crash on tap");
    expect(r.labels).toEqual(["user-feedback", "bug"]);
  });

  it("uses the enhancement label for ideas and prefers typed text", () => {
    const r = buildIssue({
      id: "abc",
      category: "idea",
      transcript: "a long spoken thought that rambles",
      text: "Add a dark mode",
      context: {},
      viewerUrl: "u",
    });
    expect(r.title).toBe("[Idea] Add a dark mode");
    expect(r.labels).toContain("enhancement");
  });

  it("notes a missing transcript when there is no audio and no text", () => {
    const r = buildIssue({
      id: "abc",
      category: "bug",
      transcript: "",
      text: "",
      context: {},
      viewerUrl: "u",
    });
    expect(r.body).toContain("No transcript available");
  });

  it("renders the context table and the viewer link", () => {
    const r = buildIssue({
      id: "abc",
      category: "bug",
      transcript: "t",
      text: "",
      context: { appVersion: "4.11.1", demoId: "lighting" },
      viewerUrl: "https://w/feedback/abc",
    });
    // Context values are Markdown-escaped (dots in the version are escaped).
    expect(r.body).toContain("| App version | 4\\.11\\.1 |");
    expect(r.body).toContain("| Demo | lighting |");
    expect(r.body).toContain("https://w/feedback/abc");
  });

  it("renders the Whisper-detected transcript language with a friendly label", () => {
    const r = buildIssue({
      id: "abc",
      category: "bug",
      transcript: "the spinner never stops",
      text: "",
      context: { transcriptLanguage: "en" },
      viewerUrl: "https://w/feedback/abc",
    });
    expect(r.body).toContain("| Transcript language | en |");
  });

  it("renders user text inside a fenced code block (no Markdown injection)", () => {
    const r = buildIssue({
      id: "abc",
      category: "bug",
      transcript: "",
      text: "![pixel](https://evil.example/track.gif)",
      context: {},
      viewerUrl: "u",
    });
    // The image is rendered verbatim inside a fence, never as live Markdown.
    expect(r.body).toContain("````text");
    expect(r.body).toContain("![pixel](https://evil.example/track.gif)");
    // The fenced description block keeps the literal text on its own line.
    expect(r.body).toContain(
      "\n![pixel](https://evil.example/track.gif)\n",
    );
  });

  it("neutralises a fence-breakout attempt in the transcript", () => {
    const r = buildIssue({
      id: "abc",
      category: "bug",
      transcript: "ok\n```\n[phish](https://evil.example)\n```\nmore",
      text: "",
      context: {},
      viewerUrl: "u",
    });
    // A raw 3-backtick run inside the content must not survive intact —
    // it cannot terminate the 4-backtick fence early.
    expect(r.body).not.toContain("\n```\n");
    // The 4-backtick fence delimiter itself is still present.
    expect(r.body).toContain("````text");
  });

  it("escapes Markdown link syntax in context values", () => {
    const r = buildIssue({
      id: "abc",
      category: "bug",
      transcript: "t",
      text: "",
      context: { device: "[click](https://evil.example)" },
      viewerUrl: "u",
    });
    expect(r.body).not.toContain("[click](https://evil.example)");
    expect(r.body).toContain("\\[click\\]");
  });
});
