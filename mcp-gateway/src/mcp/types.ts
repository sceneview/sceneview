/**
 * Shared MCP types for the gateway.
 *
 * These types are intentionally kept identical to the per-package
 * `ToolDefinition` / `ToolResult` / `DispatchContext` declarations so the
 * registry can treat every upstream lib uniformly without cross-package
 * type imports (the repo has no npm workspaces, so structural compatibility
 * is the glue).
 *
 * Keep this file in sync with:
 *   - mcp/src/tools/types.ts
 *   - mcp/packages/automotive/src/tools.ts (inline types)
 *   - mcp/packages/gaming/src/tools.ts
 *   - mcp/packages/healthcare/src/tools.ts
 *   - mcp/packages/interior/src/tools.ts
 */

/** An MCP content block — always text for SceneView tools. */
export interface ToolTextContent {
  type: "text";
  text: string;
}

/** Result returned by every SceneView MCP tool handler. */
export interface ToolResult {
  content: ToolTextContent[];
  structuredContent?: Record<string, unknown>;
  isError?: boolean;
  /** Result-level metadata (MCP Apps `_meta.ui.resourceUri`), forwarded verbatim. */
  _meta?: Record<string, unknown>;
}

/**
 * Behaviour annotations forwarded verbatim to MCP clients via `tools/list`.
 * Mirror of the same interface in `mcp/src/tools/types.ts` — see that file
 * for the full rationale (OpenAI / Smithery review demand these so they
 * don't have to ask for free-form per-tool justifications).
 */
export interface ToolAnnotations {
  readOnlyHint: boolean;
  openWorldHint: boolean;
  destructiveHint: boolean;
  title?: string;
}

/** Tool metadata exposed to MCP clients via `tools/list`. */
export interface ToolDefinition {
  name: string;
  description: string;
  inputSchema: {
    type: "object";
    properties?: Record<string, unknown>;
    required?: string[];
    additionalProperties?: boolean;
  };
  /** JSON Schema for the machine-readable `structuredContent` result. */
  outputSchema?: {
    type: "object";
    properties?: Record<string, unknown>;
    required?: string[];
    additionalProperties?: boolean;
  };
  /**
   * MCP behaviour hints — enforced at runtime by the registry contract test
   * (`test/registry.test.ts`). Optional in the type only to keep the
   * migration easy for any future library that hasn't been updated yet;
   * the runtime test refuses to ship a registry where any tool omits them.
   */
  annotations?: ToolAnnotations;
  /**
   * Declaration-level metadata forwarded verbatim in `tools/list` — the MCP
   * Apps widget pointer (`ui.resourceUri`) and the OpenAI Apps SDK keys.
   * Mirror of `mcp/src/tools/types.ts`.
   */
  _meta?: Record<string, unknown>;
}

/**
 * Per-request context injected by the gateway before calling a handler.
 *
 * Handlers are pure — they read this bag but do not authenticate, rate
 * limit, or write billing events themselves.
 */
export interface DispatchContext {
  /** Authenticated user id from the D1 `users` table. */
  userId?: string;
  /** API key row id from the D1 `api_keys` table. */
  apiKeyId?: string;
  /** Resolved subscription tier. */
  tier?: "free" | "pro" | "team";
  /** Opaque extension bag (request id, headers, ...) reserved for future use. */
  extras?: Record<string, unknown>;
}

/**
 * The shape exposed by every upstream tool library. All five MCP packages
 * satisfy this structurally, which lets the registry import them without
 * any shared package or workspace.
 */
export interface ToolLibrary {
  /** Identifier used for logging / error messages and for duplicate detection. */
  id: string;
  /** Human name shown in error messages when collisions are reported. */
  label: string;
  /** Static tool metadata (schemas + descriptions). */
  definitions: readonly ToolDefinition[];
  /** Pure dispatcher — enforces NO auth/rate-limit/billing itself. */
  dispatch: (
    toolName: string,
    args: Record<string, unknown> | undefined,
    ctx?: DispatchContext,
  ) => Promise<ToolResult>;
}
