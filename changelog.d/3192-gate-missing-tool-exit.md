<!-- category: Fixed -->
- **Repo guards now say "could not run" instead of faking a verdict when a tool is
  missing ([#3192](https://github.com/sceneview/sceneview/issues/3192)).** Five checks
  reported a result they had not actually verified when an interpreter or CLI was absent:
  `check-web-filamat-abi.sh` exited `1` with two bogus `MISMATCH` blocks and an empty
  hash when neither `shasum` nor `sha256sum` existed; `sync-versions.sh` printed a green
  116-check report without `python3`, which is what reads and rewrites every JSON version
  file; `verify-published-version.sh` turned a missing `curl` (or `python3` for pub.dev)
  into a claim about the publish; `web-bundle-smoke.sh` died with a bare `npm: command
  not found` (127) that reads like a broken web bundle; and `validate-release-artifact.sh`
  blocked a Play Store upload with "could not parse 'package' from artifact manifest"
  when the missing piece was `python3`, not the artifact. Each now resolves its tooling
  up front and exits `2` (`0` WARN + SKIP for the release guard, whose own contract is
  that missing validation tooling must never veto a release), so `1` keeps meaning "the
  thing under test is wrong".
