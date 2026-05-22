<!-- category: Fixed -->
- **iOS demo (SketchfabService):** `downloadBinary` now surfaces real download progress instead of always emitting `1.0` at completion. Replaced `URLSession.download(from:)` (no intermediate callbacks) with a `URLSessionDownloadDelegate` that reports per-byte progress, so the model viewer's progress bar animates smoothly on slow connections. (#982)
