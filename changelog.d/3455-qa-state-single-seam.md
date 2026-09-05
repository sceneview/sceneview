<!-- category: Changed -->
- **One QA state-pin extra instead of two ([#3455](https://github.com/sceneview/sceneview/issues/3455)).**
  #3449 and #3451 each landed an intent extra that pins an Android demo into a named UI
  state so the emulator, which has neither ARCore nor AICore (#2754), can capture every
  state: `qa_ask_state` for Point & Ask, ungated, and `qa_state` for Cloud Anchors, gated
  on `qa_mode`. They are now the same seam. `--ez qa_mode true --es qa_state <id>` carries
  every demo's vocabulary — Point & Ask reads its eleven card ids (`checking` … `failed-persistent`)
  through it, Cloud Anchors its fourteen scenarios — and each demo ignores the ids it does
  not know. The gate lives in one pure function, `DeepLinkRouter.resolveQaState`, which
  returns `null` unless `qa_mode` is on; a unit test pins both the accepted and the ignored
  path, and Point & Ask now also drops its pin at runtime when QA mode is toggled off from
  the sheet, as Cloud Anchors already did. State ids are unchanged, so an existing capture
  command only renames the extra. `qa_ask_state` is gone; the demo README lists both id
  sets next to the one command that uses them.
