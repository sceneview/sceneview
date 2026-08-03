<!-- category: Tests -->
- **Every guard added here is mutation-tested individually.** Dropping the
  byte-length comparison makes the re-stage test return the stale bytes;
  dropping the last-resort branch makes the degradation test throw
  `FallbackUnavailable`; and a staged copy corrupted after the bundled asset
  vanishes must still throw rather than be served. Each mutation was run on its
  own, after a first attempt that mutated two guards at once turned only one
  test red — the first mutation masked the second.
- **The assumption under the freshness check is tested against the real
  `AssetManager`, not a fake.** Everything else here injects bytes, which
  cannot prove that `AssetInputStream.available()` equals what a copy of the
  same asset writes to disk. If those disagree nothing fails — the fast path
  simply never matches and every `resolve` re-copies megabytes on a hot demo
  path. Mutation-tested too: `+1` on the expected length turns it red, which is
  what proves it measured rather than skipped.
