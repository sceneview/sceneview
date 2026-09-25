<!-- category: Fixed -->

- **iOS: `testSearchReturnsResults` hit the live Sketchfab API and could fail unrelated PRs on a transient TLS error ([#3811](https://github.com/sceneview/sceneview/issues/3811)).** The test now stubs the transport with a `URLProtocol` and a recorded search-response fixture, so it stays hermetic and keeps its parsing/mapping coverage with no network or API key involved. The live round-trip moved to a separate `testLiveSearchReturnsResults`, opt-in behind `SKETCHFAB_LIVE_INTEGRATION_TEST=1` and never set in CI.
