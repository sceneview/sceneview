/**
 * Simple sliding-window rate limiter using KV — mirrors telemetry-worker.
 *
 * Feedback submissions are heavy (media upload) and infrequent, so the cap is
 * deliberately low: a real user submits a handful per hour at most, while the
 * cap still blunts a scripted flood of issue-creating requests.
 *
 * NOTE — TOCTOU / non-atomic: the KV get and put are two separate operations.
 * A burst of concurrent requests can each read the same count. This is
 * acceptable here — KV is eventually consistent and an approximate cap is
 * enough to stop abuse. Use Durable Objects for strict atomicity.
 */

const MAX_REQUESTS_PER_MINUTE = 5;
const BUCKET_TTL_SECONDS = 120;

/** Non-cryptographic salt — only makes KV keys non-reversible at a glance. */
const HASH_SALT = "sv-fb-2026:";

function hashIp(ip: string): string {
  const salted = HASH_SALT + ip;
  let hash = 0x811c9dc5;
  for (let i = 0; i < salted.length; i++) {
    hash ^= salted.charCodeAt(i);
    hash = (hash * 0x01000193) >>> 0;
  }
  return hash.toString(36);
}

function minuteBucket(): string {
  return Math.floor(Date.now() / 60_000).toString(36);
}

export async function isRateLimited(
  ip: string,
  kv: KVNamespace,
): Promise<{ limited: boolean; remaining: number; limit: number }> {
  const key = `rl:${hashIp(ip)}:${minuteBucket()}`;

  const current = await kv.get(key);
  const count = current ? parseInt(current, 10) : 0;

  if (count >= MAX_REQUESTS_PER_MINUTE) {
    return { limited: true, remaining: 0, limit: MAX_REQUESTS_PER_MINUTE };
  }

  await kv.put(key, String(count + 1), { expirationTtl: BUCKET_TTL_SECONDS });

  return {
    limited: false,
    remaining: MAX_REQUESTS_PER_MINUTE - (count + 1),
    limit: MAX_REQUESTS_PER_MINUTE,
  };
}

/** Repo-wide cap on GitHub issue creation per hour. */
const MAX_ISSUES_PER_HOUR = 30;

function hourBucket(): string {
  return Math.floor(Date.now() / 3_600_000).toString(36);
}

/**
 * Repo-wide backstop against an issue-creation flood that gets past the per-IP
 * limiter (e.g. a botnet rotating IPs). Same approximate KV semantics as
 * `isRateLimited` — an exact count is not needed, only a usable ceiling.
 * Returns true (and does NOT consume a slot) once the hourly cap is reached.
 */
export async function issueQuotaExceeded(kv: KVNamespace): Promise<boolean> {
  const key = `issuequota:${hourBucket()}`;
  const current = await kv.get(key);
  const count = current ? parseInt(current, 10) : 0;
  if (count >= MAX_ISSUES_PER_HOUR) return true;
  await kv.put(key, String(count + 1), { expirationTtl: 7200 });
  return false;
}
