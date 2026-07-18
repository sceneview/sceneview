<!-- category: Fixed -->
- AR Body Tracker demo (Android): the `PoseLandmarker` init was wrapped in `runCatching { … }.getOrNull()`, silently swallowing any failure. Added an `onFailure` that logs the exception so a corrupt/missing pose model is diagnosable instead of vanishing.
