/**
 * Public types for the SceneView MCP tool library.
 *
 * These are the contract shared with the `mcp-gateway` package. Any change here
 * MUST be reflected in `mcp-gateway/src/mcp/types.ts` (or that file should
 * import from this one once workspaces are set up).
 */

/** An MCP content block — always text for SceneView tools. */
export interface ToolTextContent {
  type: "text";
  text: string;
}

/** The shape returned by every SceneView MCP tool handler. */
export interface ToolResult {
  content: ToolTextContent[];
  structuredContent?: Record<string, unknown>;
  isError?: boolean;
}

/**
 * Behaviour annotations forwarded verbatim to MCP clients via `tools/list`.
 *
 * These are the standard hints from the MCP spec
 * (https://modelcontextprotocol.io/specification/2025-03-26/server/tools#tool):
 *
 * - `readOnlyHint`: the tool only reads / returns data, never mutates state.
 *   For SceneView tools this is true everywhere — we generate code, return
 *   docs, validate snippets, but never touch the user's filesystem or any
 *   remote state.
 * - `openWorldHint`: the tool reaches out to systems beyond the local server.
 *   False for the vast majority of SceneView tools (they're pure functions of
 *   bundled docs/templates). True ONLY for the few that talk to a third
 *   party — e.g. `search_models` queries Sketchfab.
 * - `destructiveHint`: the tool can have destructive side effects (delete,
 *   overwrite, etc.). Always false for SceneView — every tool is additive
 *   (returns text the AI can then choose to write). Meaningful only when
 *   `readOnlyHint == false`, but we set it explicitly so OpenAI / Smithery
 *   reviewers don't have to write a justification per tool.
 *
 * Tools that omit annotations are treated as `read=false / openWorld=true /
 * destructive=true` by reviewers, which forces a free-form justification per
 * tool. With 63 multiplexed tools that's unmanageable, so every entry MUST
 * declare its annotations.
 */
export interface ToolAnnotations {
  readOnlyHint: boolean;
  openWorldHint: boolean;
  destructiveHint: boolean;
  /** Optional human-readable title surfaced in some clients' tool pickers. */
  title?: string;
}

/**
 * JSONSchema-ish input schema attached to a tool definition.
 *
 * We intentionally type this loosely (`unknown` arguments) because the real
 * validation happens inside each handler. The MCP SDK exposes these schemas
 * verbatim to clients via `ListToolsRequestSchema`.
 */
export interface ToolDefinition {
  name: string;
  description: string;
  inputSchema: {
    type: "object";
    properties?: Record<string, unknown>;
    required?: string[];
    additionalProperties?: boolean;
  };
  outputSchema?: {
    type: "object";
    properties?: Record<string, unknown>;
    required?: string[];
    additionalProperties?: boolean;
  };
  /**
   * Behaviour hints for the MCP client. Typed as optional for the migration
   * but enforced at runtime by a contract test
   * (`tools/annotations.test.ts` here, mirrored in
   * `mcp-gateway/test/registry.test.ts`) — every shipped tool MUST set them.
   * The default sane combo is
   * `{ readOnlyHint: true, openWorldHint: false, destructiveHint: false }`.
   */
  annotations?: ToolAnnotations;
}

/**
 * Per-request context passed to handlers by the dispatcher.
 *
 * Handlers running in the stdio npm package get an empty context. The gateway
 * populates fields like `userId`, `apiKeyId`, and `tier` once it has
 * authenticated the caller.
 */
export interface DispatchContext {
  /** Authenticated user id. Set by the gateway, `undefined` in stdio. */
  userId?: string;
  /** API key row id. Set by the gateway, `undefined` in stdio. */
  apiKeyId?: string;
  /** Resolved subscription tier. Defaults to `"free"` in stdio. */
  tier?: "free" | "pro" | "team";
  /** Free-form key/value bag for future extensibility (request id, headers). */
  extras?: Record<string, unknown>;
}
