<!-- category: Tests -->
- device-QA android: launchability gate in `qa-android-demos.sh` — a stale/partial install residue (package listed but launcher activity unresolvable) is now detected before the Maestro flow, remediated by one clean uninstall+reinstall, and otherwise fails fast with a diagnostic instead of burning the whole 49-demo catalog (#2725).
