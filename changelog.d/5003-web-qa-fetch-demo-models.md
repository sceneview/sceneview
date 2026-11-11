<!-- category: Fixed -->
- **Web device-QA**: the web leg fetches the web demo's models (assets scope `web-demo`) before running Playwright, so the release gate no longer screenshots the "3D preview unavailable" overlay instead of the scene. The shadow bench also clears a stale load-failure overlay (#5003).
