<!-- category: Fixed -->
- **Sending a bug report from the demo app no longer pops a confirmation
  snackbar ([#3398](https://github.com/sceneview/sceneview/issues/3398)).**
  Handing a report off to GitHub or the share sheet used to greet the user
  back with a "GitHub opened — finish and submit there" / "Report shared"
  snackbar (#3263). The hand-off itself is already the acknowledgement — the
  sheet dismisses and the browser opens on the pre-filled issue (or the
  system share sheet takes over) — so the snackbar was redundant, and it
  could not be truthful anyway: with no GitHub API call on device, "sent"
  only ever meant "intent launched". The failure path is untouched: when no
  app can handle the intent, the sheet still stays open and shows the error
  inline. `BugReportSheet` loses its `onSent` callback, `MainActivity` its
  snackbar host, and the two now-unused strings are gone.
