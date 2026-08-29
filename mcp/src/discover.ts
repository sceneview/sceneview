/**
 * `server/discover` — handshake-free discovery (MCP 2026-07-28).
 *
 * The 2026-07-28 revision introduced `server/discover`: one request a client
 * MAY send **before** `initialize`, with no session and no negotiated version,
 * which answers "who are you, what can you do, and which protocol revisions
 * can you actually serve?". It is a `CacheableResult`, so the answer carries
 * its own cache hints (`ttlMs`, `cacheScope`) and must stay stable inside that
 * TTL.
 *
 * Two things are worth stating plainly, because they drove the design here
 * (see issue #3349):
 *
 *  1. **We do not serve 2026-07-28.** `@modelcontextprotocol/sdk` 1.30.0 — the
 *     newest published release — tops out at `2025-11-25`
 *     (`SUPPORTED_PROTOCOL_VERSIONS`) and ships no `server/discover` at all.
 *     So `supportedVersions` below is read straight from the SDK rather than
 *     hardcoded: it advertises exactly what the handshake will really settle
 *     on, never a revision we cannot honour. Announcing a revision we don't
 *     serve would be the actual bug.
 *
 *  2. **Answering it anyway is still correct and useful.** `server/discover`
 *     is how a 2026-07-28-aware client learns, in one round trip, that this
 *     server speaks `2025-11-25` — the alternative is a `-32601 Method not
 *     found` that tells the client nothing and leaves it guessing. The method
 *     is cheap, purely informational, and requires no SDK upgrade.
 *
 * TODO(#3349): when `@modelcontextprotocol/sdk` ships 2026-07-28 support
 * (result envelope with `resultType`, per-request `_meta` version negotiation,
 * `subscriptions/listen`), bump the SDK and drop `SUPPORTED_PROTOCOL_VERSIONS`
 * here for whatever the new release exposes. This file needs no other change:
 * the advertised list is derived, not written down.
 */

import { RequestSchema, SUPPORTED_PROTOCOL_VERSIONS } from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod";
import { PACKAGE_VERSION } from "./generated/version.js";

/** JSON-RPC method name defined by MCP 2026-07-28. */
export const DISCOVER_METHOD = "server/discover";

/**
 * Zod schema for the incoming request, in the shape the SDK's
 * `setRequestHandler` expects (a `method` literal it can route on).
 */
export const DiscoverRequestSchema = RequestSchema.extend({
  method: z.literal(DISCOVER_METHOD),
});

/**
 * Server capabilities, declared once and shared between the `Server`
 * constructor and `server/discover` so the two can never drift apart.
 */
export const SERVER_CAPABILITIES = { resources: {}, tools: {} } as const;

/** Server identity, likewise shared with the `Server` constructor. */
export const SERVER_INFO = { name: "sceneview-mcp", version: PACKAGE_VERSION } as const;

/**
 * How long a client may cache the discover answer. Everything in it —
 * identity, capabilities, the SDK's revision list — is fixed at build time and
 * cannot change while the process lives, so an hour is conservative rather
 * than optimistic.
 */
export const DISCOVER_TTL_MS = 3_600_000;

/**
 * Natural-language guidance, mirrored from the package description. Kept short
 * on purpose: per the spec it should not duplicate tool descriptions.
 */
const DISCOVER_INSTRUCTIONS =
  "SceneView MCP — the full SceneView SDK (Android/Compose + Filament, iOS/SwiftUI + RealityKit, AR via ARCore/ARKit) " +
  "as tools and resources, so an AI writes correct, compilable SceneView code. Read the `sceneview://api` resource " +
  "before writing any SceneView code.";

export interface DiscoverResult {
  // The SDK types a request handler's return as an open JSON-RPC result, so the
  // index signature is what lets this shape be returned from one directly.
  [key: string]: unknown;
  resultType: "complete";
  ttlMs: number;
  cacheScope: "public" | "private";
  supportedVersions: string[];
  capabilities: Record<string, unknown>;
  serverInfo: { name: string; version: string };
  instructions: string;
}

/**
 * Builds the `DiscoverResult`.
 *
 * Every field is derived from a build-time constant, which is what makes the
 * result stable across calls inside its TTL — a requirement, since a client
 * that caches for `ttlMs` would otherwise be handed a moving target.
 *
 * `cacheScope` is `"public"`: nothing here is user-specific. The payload is
 * identical whether or not a Pro API key is configured (the key only affects
 * how individual tool *calls* are routed, never identity or capabilities), so
 * a shared proxy may serve one cached copy to everyone.
 */
export function buildDiscoverResult(): DiscoverResult {
  return {
    resultType: "complete",
    ttlMs: DISCOVER_TTL_MS,
    cacheScope: "public",
    // Copied so a caller mutating the array cannot corrupt the SDK's constant
    // — and so two successive calls stay `deepEqual` rather than aliased.
    supportedVersions: [...SUPPORTED_PROTOCOL_VERSIONS],
    capabilities: { ...SERVER_CAPABILITIES },
    serverInfo: { ...SERVER_INFO },
    instructions: DISCOVER_INSTRUCTIONS,
  };
}
