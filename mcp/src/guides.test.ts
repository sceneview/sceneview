import { describe, expect, it } from "vitest";
import { LATEST_SCENEVIEW_RELEASE } from "./generated/version.js";
import {
  AR_SETUP_GUIDE,
  BEST_PRACTICES,
  PLATFORM_ROADMAP,
  TROUBLESHOOTING_GUIDE,
} from "./guides.js";

// A `v` prefix is a word character, so a leading \b never matches "v4.0.0" — the
// spelling the roadmap actually uses. Match the prefix explicitly and capture the
// numeric part.
const SEMVER = /(?<![\w.])v?(\d+\.\d+\.\d+(?:-[\da-zA-Z.-]+)?(?:\+[\da-zA-Z.-]+)?)(?![\w.])/g;

function compareSemver(left: string, right: string): number {
  const parse = (version: string) => {
    const [core, prerelease] = version.split("+")[0].split(/-(.*)/);
    return { core: core.split(".").map(Number), prerelease: prerelease?.split(".") };
  };
  const a = parse(left);
  const b = parse(right);
  for (let i = 0; i < 3; i++) {
    if (a.core[i] !== b.core[i]) return a.core[i] - b.core[i];
  }
  if (!a.prerelease) return b.prerelease ? 1 : 0;
  if (!b.prerelease) return -1;
  for (let i = 0; i < Math.max(a.prerelease.length, b.prerelease.length); i++) {
    const x = a.prerelease[i];
    const y = b.prerelease[i];
    if (x === y) continue;
    if (x === undefined) return -1;
    if (y === undefined) return 1;
    const xNumeric = /^\d+$/.test(x);
    const yNumeric = /^\d+$/.test(y);
    if (xNumeric && yNumeric) return Number(x) - Number(y);
    if (xNumeric !== yNumeric) return xNumeric ? -1 : 1;
    return x < y ? -1 : 1;
  }
  return 0;
}

function versionChecks(guides: Record<string, string>) {
  // Only a SCREAMING_SNAKE placeholder can be an escaped module constant that was
  // never interpolated. `${arcoreApiKey}` in the AR setup guide is deliberate: it is
  // the literal Gradle manifest placeholder the reader must copy.
  it("leaks no uninterpolated module constant", () => {
    for (const [name, guide] of Object.entries(guides)) {
      expect(guide.match(/\$\{[A-Z][A-Z0-9_]*\}/g), name).toBeNull();
    }
  });

  it("only advertises Upcoming versions newer than the latest release", () => {
    for (const [name, guide] of Object.entries(guides)) {
      // An Upcoming heading owns its subsections until a peer or parent heading.
      let upcomingLevel: number | undefined;
      for (const line of guide.split("\n")) {
        const heading = /^(#{1,6})\s+(.+)$/.exec(line);
        if (heading && upcomingLevel !== undefined && heading[1].length <= upcomingLevel) {
          upcomingLevel = undefined;
        }
        if (heading && /\bUpcoming\b/i.test(heading[2])) {
          upcomingLevel = heading[1].length;
        }
        if (upcomingLevel === undefined && !/\bUpcoming\b/i.test(line)) continue;
        for (const [, version] of line.matchAll(SEMVER)) {
          expect(
            compareSemver(version, LATEST_SCENEVIEW_RELEASE),
            `${name}: Upcoming ${version} must be newer than ${LATEST_SCENEVIEW_RELEASE}`
          ).toBeGreaterThan(0);
        }
      }
    }
  });
}

describe("BEST_PRACTICES", () => {
  it("has exactly the supported topic keys", () => {
    expect(Object.keys(BEST_PRACTICES).sort()).toEqual([
      "all",
      "architecture",
      "memory",
      "performance",
      "threading",
    ]);
  });

  it("contains non-empty strings starting with the best practices heading", () => {
    for (const value of Object.values(BEST_PRACTICES)) {
      expect(typeof value).toBe("string");
      expect(value.trim().length).toBeGreaterThan(0);
      expect(value).toMatch(/^# SceneView Best Practices(?:\r?\n|$)/);
    }
  });

  it("includes every topic body in all", () => {
    for (const [topic, value] of Object.entries(BEST_PRACTICES)) {
      if (topic === "all") continue;
      const body = value.replace(/^# SceneView Best Practices\r?\n\s*/, "");
      expect(body.trim().length, topic).toBeGreaterThan(0);
      expect(BEST_PRACTICES.all, topic).toContain(body);
    }
  });

  versionChecks(BEST_PRACTICES);
});

describe("PLATFORM_ROADMAP", () => {
  it("uses the latest release in every Maven and SPM coordinate", () => {
    const maven = [...PLATFORM_ROADMAP.matchAll(/\bio\.github\.sceneview:[\w.-]+:([^\s`"']+)/g)];
    const spm = [...PLATFORM_ROADMAP.matchAll(/\b(?:from|exact)\s*:\s*"([^"]+)"/g)];
    expect(maven.length).toBeGreaterThan(0);
    expect(spm.length).toBeGreaterThan(0);
    for (const [coordinate, version] of [...maven, ...spm]) {
      expect(version, coordinate).toBe(LATEST_SCENEVIEW_RELEASE);
    }
  });

  it("names every shipped platform row", () => {
    for (const platform of [
      "Android (Compose)",
      "Android (AR)",
      "iOS (SwiftUI)",
      "macOS (SwiftUI)",
      "visionOS (SwiftUI)",
      "KMP Core",
    ]) {
      expect(PLATFORM_ROADMAP).toContain(`| **${platform}** |`);
    }
  });

  it("documents Filament on Android and RealityKit on Apple", () => {
    expect(PLATFORM_ROADMAP).toMatch(/Android:\s+Filament\b/);
    expect(PLATFORM_ROADMAP).toMatch(/Apple:\s+RealityKit\b/);
  });

  versionChecks({ PLATFORM_ROADMAP });
});

describe("TROUBLESHOOTING_GUIDE", () => {
  it("is non-empty and starts with an H1", () => {
    expect(TROUBLESHOOTING_GUIDE.trim().length).toBeGreaterThan(0);
    expect(TROUBLESHOOTING_GUIDE).toMatch(/^# [^\r\n]+/);
  });

  it("has balanced code fences", () => {
    expect((TROUBLESHOOTING_GUIDE.match(/```/g) ?? []).length % 2).toBe(0);
  });

  versionChecks({ TROUBLESHOOTING_GUIDE });
});

describe("AR_SETUP_GUIDE", () => {
  it("is non-empty and starts with an H1", () => {
    expect(AR_SETUP_GUIDE.trim().length).toBeGreaterThan(0);
    expect(AR_SETUP_GUIDE).toMatch(/^# [^\r\n]+/);
  });

  it("has balanced code fences", () => {
    expect((AR_SETUP_GUIDE.match(/```/g) ?? []).length % 2).toBe(0);
  });

  versionChecks({ AR_SETUP_GUIDE });
});
