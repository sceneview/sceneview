import { describe, it, expect } from "vitest";
import { isRateLimited, adminAttemptLimited } from "../src/rate-limit.js";
import { createMockKV } from "./helpers/mock-kv.js";

describe("rate-limit IP hashing", () => {
  it("hashes the IP into a SHA-256 hex bucket key (not raw, not FNV)", async () => {
    const kv = createMockKV();
    await isRateLimited("203.0.113.7", kv);
    const keys = [...kv._store.keys()];
    expect(keys).toHaveLength(1);
    const key = keys[0];
    // rl:<24-hex-prefix>:<minutebucket>
    expect(key).toMatch(/^rl:[0-9a-f]{24}:/);
    // The raw IP must never appear in the KV key.
    expect(key).not.toContain("203.0.113.7");
  });

  it("gives unrelated IPs distinct rate buckets", async () => {
    const kv = createMockKV();
    await isRateLimited("198.51.100.1", kv);
    await isRateLimited("198.51.100.2", kv);
    const buckets = new Set(
      [...kv._store.keys()].map((k) => k.split(":")[1]),
    );
    expect(buckets.size).toBe(2);
  });

  it("the same IP always maps to the same bucket", async () => {
    const kvA = createMockKV();
    const kvB = createMockKV();
    await isRateLimited("192.0.2.55", kvA);
    await isRateLimited("192.0.2.55", kvB);
    const a = [...kvA._store.keys()][0].split(":")[1];
    const b = [...kvB._store.keys()][0].split(":")[1];
    expect(a).toBe(b);
  });

  it("isRateLimited caps after 5 requests, then fails open on KV error", async () => {
    const kv = createMockKV();
    for (let i = 0; i < 5; i++) {
      expect((await isRateLimited("10.0.0.1", kv)).limited).toBe(false);
    }
    expect((await isRateLimited("10.0.0.1", kv)).limited).toBe(true);

    kv.put = async () => {
      throw new Error("KV write quota exhausted");
    };
    // Fresh IP — a put failure must fail open, never throw.
    expect((await isRateLimited("10.0.0.2", kv)).limited).toBe(false);
  });

  it("adminAttemptLimited caps at 10 attempts per IP per minute", async () => {
    const kv = createMockKV();
    for (let i = 0; i < 10; i++) {
      expect(await adminAttemptLimited("172.16.0.9", kv)).toBe(false);
    }
    expect(await adminAttemptLimited("172.16.0.9", kv)).toBe(true);
    // A different IP is unaffected.
    expect(await adminAttemptLimited("172.16.0.10", kv)).toBe(false);
  });

  it("adminAttemptLimited fails open on a KV error", async () => {
    const kv = createMockKV();
    kv.get = async () => {
      throw new Error("KV unavailable");
    };
    expect(await adminAttemptLimited("172.16.0.11", kv)).toBe(false);
  });
});
