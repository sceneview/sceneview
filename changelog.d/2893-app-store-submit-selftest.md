<!-- category: Tests -->
- **The App Store submit step now has a hermetic self-test
  (`.claude/scripts/test-app-store-submit.py`, in `repo-hygiene`).** ~670 lines
  of Python inside a YAML heredoc stand between a green tag build and an App
  Store submission, and they had no test seam: exercising them meant dispatching
  `app-store.yml`, which archives, signs and uploads a real TestFlight build —
  so every fix landed in production, on a release, after it broke one (#2731,
  #2885, #2893). The test extracts the real heredoc (never a copy, so it cannot
  drift) and runs it against a stubbed App Store Connect. Stdlib only: no
  network, no secrets, no Apple call.
