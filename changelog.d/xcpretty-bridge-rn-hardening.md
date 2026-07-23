<!-- category: Fixed -->
- The iOS bridge compile-check workflows (`bridge-ios-compile.yml`,
  `rn-ios-compile.yml`) no longer fail — or, worse, pass while skipping their
  `.swiftmodule` guard — when `xcpretty` is absent or crashes. Piping xcodebuild
  into a missing `xcpretty` gave the producer a SIGPIPE (exit 141), and the
  inline `|| exit ${PIPESTATUS[0]}` ran *inside* the pipe, short-circuiting the
  post-build module check. Both workflows now detect `xcpretty` first, run
  xcodebuild raw when it is missing, capture xcodebuild's real exit code in a
  variable, and test it — never `exit` inline — so a good build stays green
  (even on the self-hosted `sceneview-mac` runner without the gem) and a broken
  one fails loudly. Same hardening #2878/#2865 gave `ios.yml`, extended to the
  two bridge workflows an advisory cross-vendor review flagged.
