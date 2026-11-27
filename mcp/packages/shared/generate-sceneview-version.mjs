import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const packageName = process.argv[2];
if (!packageName || !/^[a-z-]+$/.test(packageName)) {
  throw new Error("Usage: node generate-sceneview-version.mjs <package-name>");
}

const repositoryRoot = new URL("../../../", import.meta.url);
const properties = readFileSync(new URL("gradle.properties", repositoryRoot), "utf8");
const version = properties.match(/^VERSION_NAME=(.+)$/m)?.[1];
if (!version) {
  throw new Error("VERSION_NAME is missing from gradle.properties");
}

const output = new URL(`../${packageName}/src/sceneview-version.ts`, import.meta.url);
writeFileSync(
  fileURLToPath(output),
  `// Generated from the repository VERSION_NAME by mcp/packages/shared/generate-sceneview-version.mjs.\n` +
    `export const SCENEVIEW_VERSION = ${JSON.stringify(version)};\n`,
);
