<!-- category: Fixed -->
- **CI: the iOS App Store submit step no longer attaches the previous release's
  binary (#2893 W1).** It selected the newest VALID iOS build, which — while
  Apple was still processing the upload from the running job — is the PREVIOUS
  release's build. The archive step now exports its `CFBundleVersion` and the
  submit step pins the selection to it: no match yet means our build is still
  processing (keep polling), and exhausting the window is a loud red naming the
  build it waited for. The `#2885` platform-resolution fallback is preserved.
- **CI: an authentication failure on the App Store Connect builds query is no
  longer misreported as an Apple processing delay (#2893 W2).** The status code
  was ignored, so a 401 read exactly like "still processing" and burned the full
  ~10-minute poll before failing with a message blaming Apple. 401/403 now fail
  immediately naming auth, 429/5xx stay retryable, other 4xx fail fast, and a
  non-JSON 200 is retried instead of raising out of the step.
- **CI: a failed submission no longer leaves an orphan `reviewSubmission` in App
  Store Connect (#2893 W5).** Every failure path after the submission was
  created exited without deleting it, accruing an empty, open, never-submitted
  record per run — the exact signature `store-preflight.sh` reports as a release
  blocker, cleared by hand after run 30269459288. The submission this run
  created is now cancelled on any post-create failure, never on success, and a
  failing cleanup can no longer mask the error that triggered it.
- **CI: the empty-`whatsNew` warning names the real state of
  `release_notes.txt`** — "No release_notes.txt" sent a reader hunting for a
  missing file that was present but blank (review nit from
  [#2908](https://github.com/sceneview/sceneview/pull/2908)).
