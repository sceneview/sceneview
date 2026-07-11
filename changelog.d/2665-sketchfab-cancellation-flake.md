<!-- category: Fixed -->
- Explore/Sketchfab: a search cancelled mid-body-read now reliably surfaces `CancellationException` instead of the socket-abort `SocketException`, so a query superseded by fast typing can no longer flash the "Sketchfab unavailable" error banner. Fixes the flaky `SketchfabServiceTest` cancellation test. (#2665)
