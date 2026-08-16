<!-- category: Fixed -->
An iOS release cut while the previous one is still in App Review now defers with
a readable message naming the blocking version and its state, instead of dying on
a raw traceback. App Store Connect allows one non-live version at a time, so the
`POST /v1/appStoreVersions` 409 is Apple's normal answer — not a broken run. The
submission stops there and touches nothing: continuing would have reached the
stale-submission cleanup, whose open states include `IN_REVIEW`, and withdrawn the
previous release from review.
