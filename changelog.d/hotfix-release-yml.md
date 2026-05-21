<!-- category: Fixed -->
- Fixed the release pipeline: the `secrets` context is not allowed in a GitHub Actions step `if:` expression, which made `release.yml` (and `docs.yml`) invalid workflow files and blocked the v4.13.0 publish. The token-presence check is now done inside the step's `run:` script.
