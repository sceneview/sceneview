<!-- category: Changed -->

<!-- RELEASE NOTE: the release carrying this fragment must NOT be a patch.
     `publish-rn` takes the npm version straight from the git tag
     (`VERSION=${GITHUB_REF_NAME#v}`, .github/workflows/release.yml), so tagging
     v4.26.1 would ship a source-breaking type change to
     @sceneview-sdk/react-native as a patch. Every recent tag has been a minor
     (v4.22.0 … v4.26.0), so the default path is already correct — this note
     exists because nothing enforces it. Maintainer sign-off for the break was
     given on PR #3037. -->


- **`TapEvent.nodeName` is now typed `string | null`** (React Native, [#3037](https://github.com/sceneview/sceneview/pull/3037)). Android has always emitted `null` at runtime via `putNull`, so the old `string | undefined` type was unsound: a consumer trusting it met a `null` it was never told to expect. Under `strictNullChecks` this is source-breaking for code assigning `nodeName` to a `string | undefined` binding or narrowing with `!== undefined`; truthy checks and `?.` are unaffected. **Guard `nodeName` for `null`.**
- **A React Native Android model tap now carries a name.** `nodeName` went from *always* `null` to the model's file base name. An app that read `nodeName == null` as "the tap missed every model" (for instance to place an object at that point) will now see model taps stop matching that test.
