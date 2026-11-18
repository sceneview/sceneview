import { fileURLToPath } from "node:url";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { CallToolResultSchema } from "@modelcontextprotocol/sdk/types.js";
import { LIGHT_TYPES } from "./lighting-design.js";
import { dispatchTool } from "./tools.js";
const modes = [
    { mode: "3D", ar: false, artifact: "sceneview", version: "4.69.4" },
    { mode: "AR", ar: true, artifact: "arsceneview", version: "4.69.4" },
];
function expectLightingResponse(result, { mode, ar, artifact, version }) {
    const response = CallToolResultSchema.parse(result);
    expect(response.isError).toBeFalsy();
    expect(response.content).toEqual([{ type: "text", text: expect.any(String) }]);
    const [{ text }] = response.content;
    const blocks = [...text.matchAll(/```kotlin\n([\s\S]*?)\n```/g)].map((match) => match[1]);
    const dependency = `implementation("io.github.sceneview:${artifact}:${version}")`;
    expect(text).toContain(`## ${mode} Lighting Design — ${LIGHT_TYPES.join(", ")}`);
    expect(blocks).toHaveLength(2);
    expect(blocks[0]).toBe(dependency);
    expect(blocks[1]).toContain(`* Gradle: ${dependency}`);
    expect(blocks[1]).toContain("@Composable");
    expect(blocks[1].includes("restPoseBoundingBox")).toBe(!ar);
    expect(blocks[1].match(/LightNode\(/g)).toHaveLength(LIGHT_TYPES.length);
}
describe("get_lighting_design response dependencies", () => {
    it.each(modes)("dispatchTool advertises the $mode template's compatible dependency", async (mode) => {
        const result = await dispatchTool("get_lighting_design", {
            lights: LIGHT_TYPES,
            ar: mode.ar,
        });
        expectLightingResponse(result, mode);
    });
    describe("stdio server", () => {
        const client = new Client({ name: "lighting-response-test", version: "1.0.0" });
        beforeAll(async () => {
            const entrypoint = new URL(import.meta.url.endsWith(".ts") ? "./index.ts" : "./index.js", import.meta.url);
            const transport = new StdioClientTransport({
                command: process.execPath,
                args: ["--import", "tsx", fileURLToPath(entrypoint)],
                cwd: fileURLToPath(new URL("../", import.meta.url)),
            });
            await client.connect(transport);
        });
        afterAll(async () => {
            await client.close();
        });
        it.each(modes)("advertises the $mode template's compatible dependency", async (mode) => {
            const result = await client.callTool({
                name: "get_lighting_design",
                arguments: { lights: LIGHT_TYPES, ar: mode.ar },
            });
            expectLightingResponse(result, mode);
        });
    });
});
