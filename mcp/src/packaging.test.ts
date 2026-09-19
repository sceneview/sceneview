import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

// Three packaging descriptors sit outside the TypeScript build and therefore outside
// `tsc`'s reach: `mcp/manifest.json` (MCP Bundle spec 0.3), `mcp/server.json` (the MCP
// registry entry) and the repository's root `gemini-extension.json`. All three restate
// facts that live in `mcp/package.json` — the version, the published npm name, the entry
// point. A restated fact drifts silently, and the symptom is an extension that installs
// and then fails to start, or a registry entry pointing at a version nobody published.
// `mcp/server.json` had already drifted four patches behind when these assertions were
// written, which is the whole argument for them: the MCP server is on its own release
// track and nothing bumps these files automatically (`.claude/scripts/sync-versions.sh`
// excludes `mcp/` by design, and `mcp-publish.yml` reads `package.json` only).
//
// Every assertion below compares two files against each other. An assertion that only
// compares a file to a literal typed in the same commit proves nothing, so there are none.

// `import.meta.dirname` needs Node >= 20.11, and `package.json` promises `>=18` — the
// test would then be the first thing to break the range it is here to protect.
const MCP_DIR = join(dirname(fileURLToPath(import.meta.url)), "..");
const REPO_ROOT = join(MCP_DIR, "..");

const readJson = (path: string) => JSON.parse(readFileSync(path, "utf8"));

const packageJson = readJson(join(MCP_DIR, "package.json"));
const manifest = readJson(join(MCP_DIR, "manifest.json"));
const serverJson = readJson(join(MCP_DIR, "server.json"));
const geminiExtension = readJson(join(REPO_ROOT, "gemini-extension.json"));

describe("mcp/manifest.json (MCP Bundle)", () => {
  it("names the same package, version and licence as package.json", () => {
    expect(manifest.name).toBe(packageJson.name);
    expect(manifest.version).toBe(packageJson.version);
    expect(manifest.license).toBe(packageJson.license);
  });

  it("points at the file package.json publishes as the binary", () => {
    expect(manifest.server.entry_point).toBe(packageJson.bin[packageJson.name]);
    // `${__dirname}` is the spec's substitution for the installed bundle's directory;
    // a relative path would resolve against the host's cwd instead.
    expect(manifest.server.mcp_config.args).toEqual([
      `\${__dirname}/${manifest.server.entry_point}`,
    ]);
  });

  it("requires no newer Node than package.json does", () => {
    expect(manifest.compatibility.runtimes.node).toBe(packageJson.engines.node);
  });
});

describe("mcp/server.json (MCP registry entry)", () => {
  it("publishes the version package.json is at", () => {
    expect(serverJson.version).toBe(packageJson.version);
  });

  it("points the npm package entry at the same name and version", () => {
    const npm = serverJson.packages.find(
      (pkg: { registryType: string }) => pkg.registryType === "npm",
    );
    expect(npm.identifier).toBe(packageJson.name);
    expect(npm.version).toBe(packageJson.version);
  });
});

describe("gemini-extension.json", () => {
  it("uses the lowercase, dash-separated name the Gemini CLI requires", () => {
    expect(geminiExtension.name).toMatch(/^[a-z0-9][a-z0-9-]*$/);
  });

  it("stays on the same version as the server it installs", () => {
    expect(geminiExtension.version).toBe(packageJson.version);
  });

  it("launches the published npm package, not a local path", () => {
    const server = geminiExtension.mcpServers.sceneview;
    expect(server.command).toBe("npx");
    // `-y` is not optional: without it npx prompts, and a prompt on stdio is a hang.
    expect(server.args).toEqual(["-y", packageJson.name]);
  });
});
