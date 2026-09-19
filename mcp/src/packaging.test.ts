import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

// Two packaging descriptors sit outside the TypeScript build and therefore outside
// `tsc`'s reach: `mcp/manifest.json` (MCP Bundle spec 0.3) and the repository's root
// `gemini-extension.json`. Both restate facts that live in `mcp/package.json` — the
// version, the published npm name, the entry point. A restated fact drifts silently,
// and the symptom is an extension that installs and then fails to start. These
// assertions are the only thing keeping the three files in step, since the MCP server
// is on its own release track and nothing bumps it automatically.

const MCP_DIR = join(import.meta.dirname, "..");
const REPO_ROOT = join(MCP_DIR, "..");

const readJson = (path: string) => JSON.parse(readFileSync(path, "utf8"));

const packageJson = readJson(join(MCP_DIR, "package.json"));
const manifest = readJson(join(MCP_DIR, "manifest.json"));
const geminiExtension = readJson(join(REPO_ROOT, "gemini-extension.json"));

describe("mcp/manifest.json (MCP Bundle)", () => {
  it("declares the spec version the fields below conform to", () => {
    expect(manifest.manifest_version).toBe("0.3");
  });

  it("carries every field the spec marks required", () => {
    for (const field of ["name", "version", "description", "author", "server"]) {
      expect(manifest[field], `missing required field: ${field}`).toBeDefined();
    }
    expect(manifest.author.name).toBeTruthy();
  });

  it("names the same package, version and licence as package.json", () => {
    expect(manifest.name).toBe(packageJson.name);
    expect(manifest.version).toBe(packageJson.version);
    expect(manifest.license).toBe(packageJson.license);
  });

  it("points at the file package.json publishes as the binary", () => {
    expect(manifest.server.type).toBe("node");
    expect(manifest.server.entry_point).toBe(packageJson.bin[packageJson.name]);
    expect(manifest.server.mcp_config.command).toBe("node");
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
