/** Type-safe Cloudflare bindings for the telemetry worker. */
export interface Env {
  DB: D1Database;
  RL_KV: KVNamespace;
  ENVIRONMENT: string;
  /**
   * Bearer token required by every read route (`/v1/stats`, `/v1/timeseries`,
   * `/v1/top-clients`, `/v1/funnel`, `/v1/export`). Set via
   * `wrangler secret put STATS_TOKEN`. When unset, those routes answer 401 to
   * every request, as for a wrong token, and the worker logs the missing secret.
   */
  STATS_TOKEN?: string;
}
