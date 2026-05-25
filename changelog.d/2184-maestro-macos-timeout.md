<!-- category: Fixed -->
- Fix `device-qa.sh` crash on macOS (`timeout: command not found`): `lib/maestro.sh` now falls back to `gtimeout` (homebrew coreutils) or runs unbounded when neither GNU `timeout` variant is available (#2184).
