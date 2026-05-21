/** Type-safe Cloudflare bindings for the feedback worker. */
export interface Env {
  /** Feedback records + transcripts. */
  DB: D1Database;
  /** Rate-limit counters. */
  RL_KV: KVNamespace;
  /** Private media bucket — screen recordings + audio. */
  MEDIA: R2Bucket;
  /** Workers AI — Whisper transcription. */
  AI: Ai;
  ENVIRONMENT: string;
  /** Target repo for created issues, e.g. "sceneview/sceneview". */
  GITHUB_REPO: string;
  /** GitHub App id. Secret — `wrangler secret put GITHUB_APP_ID`. */
  GITHUB_APP_ID: string;
  /** GitHub App installation id on the target repo. Secret. */
  GITHUB_INSTALLATION_ID: string;
  /** GitHub App private key, PKCS#8 PEM. Secret. */
  GITHUB_PRIVATE_KEY: string;
  /** Bearer token gating media playback in the /feedback/<id> viewer. Secret. */
  ADMIN_TOKEN: string;
}
