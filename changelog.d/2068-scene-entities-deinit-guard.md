<!-- category: Fixed -->
- **iOS (SceneViewSwift):** `SceneEntities.deinit` no longer traps if the instance is released off the main thread. Replaced `MainActor.assumeIsolated` with an explicit `Thread.isMainThread` guard + `DispatchQueue.main.sync` fallback so an off-main release degrades gracefully instead of crashing. (#2068)
