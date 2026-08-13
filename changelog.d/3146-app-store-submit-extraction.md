<!-- category: Changed -->
<!-- RELEASE NOTE (maintainer-only):
     Pure move, proven byte-identical: the 932-line program extracted from the
     old YAML diffs clean against the new file, and the 52-assertion self-test
     returns the same verdicts line for line before and after. No check name
     changes — app-store.yml keeps the same three jobs. -->
- **The App Store submit program now lives in `.github/scripts/app_store_submit.py`.** It was a 932-line Python heredoc inside `.github/workflows/app-store.yml`, which made one step 65% of the workflow and forced its self-test to regex-carve the code back out of the YAML. Behaviour is unchanged; the workflow file drops from 1444 to 519 lines.

<!-- category: Tests -->
- **`test-app-store-submit.py` now asserts the workflow still invokes the program it tests.** Reading the program from a file instead of from the workflow opened a drift hole the heredoc could not have — the step could be renamed or dropped while the suite stayed green and no release reached App Review (#2731). The new guard runs first and is mutation-tested in both directions.
