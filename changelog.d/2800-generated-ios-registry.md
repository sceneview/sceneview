<!-- category: Changed -->
- iOS demo: the deep-link registry is now generated. `collate-ios-demos.sh`
  emits `GeneratedScenes.allowedIds` and `GeneratedScenes.destination(for:)`
  from the same `@sceneId` directives that already drive the Samples tab, so
  the three deep-link surfaces (list, `allowedIds` gate, id→view resolver) can
  no longer drift apart — the root cause that silently dropped 12 ids (#2769).
  `DemoDeepLinkRegistry` shrinks from a hand-maintained 66-id `allowedIds` +
  43-case `switch` to a generated union plus a ~15-id residual (AR ids without
  a Scene file yet, and legacy aliases). Adding a demo is now one Scene file.
  All 66 pre-existing deep-link ids still resolve identically. A well-formed
  `sceneview://demo/<id>` whose id is unknown now surfaces a placeholder
  instead of being silently dropped (#2800).
