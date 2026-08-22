<!-- category: Fixed -->
**android-demo**: `ar-point-cloud` no longer renders a silent black screen when tracking
fails or the cloud stays empty. Unlike its `ar-raw-depth-point-cloud` / `ar-scene-semantics`
siblings, it had no `onTrackingFailureChanged` wiring at all — a lost-tracking session (or a
cold-start still resolving its first feature points) rendered nothing, with no on-screen
explanation, which read to users as "nothing rendered" (#3270). It now shows the same
tracking-failure banner as the other AR demos, plus a "still scanning" hint once the cloud has
sat at zero points, while tracking, for more than two seconds. The stuck-at-zero gate is a
pure function pinned by a JVM test (`PointCloudFeedbackTest`).
