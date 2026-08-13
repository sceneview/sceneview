<!-- category: Changed -->
<!-- RELEASE NOTE (maintainer-only):
     The move is byte-identical in CODE — the 932-line program extracted from
     the old YAML diffs clean against the new file — but not in behaviour on
     one dormant branch, and the PR body says so rather than rounding it off.
     No check name changes: app-store.yml keeps the same three jobs and still
     triggers only on a version tag. -->
- **The App Store submit program now lives in `.github/scripts/app_store_submit.py`.** It was a 932-line Python heredoc inside `.github/workflows/app-store.yml`, which made one step 65% of the workflow and forced its self-test to regex-carve the code back out of the YAML. The workflow file drops from 1444 to 519 lines.

<!-- category: Fixed -->
- **`workflow_dispatch` App Store submissions no longer crash resolving the version.** With no tag to read, the program falls back to `VERSION_NAME` in `gradle.properties` — but that branch opened with `os.environ.get("GITHUB_WORKSPACE", os.path.join(os.path.dirname(__file__), ...))`, and Python evaluates a `.get()` default eagerly. Fed to `python3` on stdin as a heredoc, `__file__` was undefined, so every manual dispatch raised `NameError` there even though `GITHUB_WORKSPACE` was set. Running the program as a file gives it a `__file__` and makes the documented fallback work.

<!-- category: Tests -->
- **`test-app-store-submit.py` gained two guards.** It now asserts the workflow still invokes the program it tests — reading the program from a file instead of from its caller opened a drift hole the heredoc could not have, where a renamed or dropped step would leave the suite green while no release reached App Review (#2731). And it covers the dispatch-path version fallback above, which nothing had exercised.
