<!-- category: Tests -->
- **Every guard added here is mutation-tested individually.** Dropping the
  byte-length comparison makes the re-stage test return the stale bytes;
  dropping the last-resort branch makes the degradation test throw
  `FallbackUnavailable`; and a staged copy corrupted after the bundled asset
  vanishes must still throw rather than be served. Each mutation was run on its
  own, after a first attempt that mutated two guards at once turned only one
  test red — the first mutation masked the second.
