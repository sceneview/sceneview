<!-- category: Fixed -->
- **Android demo — in-app screen recording restored on Android 14+.** Re-added
  `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission and
  `android:foregroundServiceType="mediaProjection"` on `FeedbackRecordingService`
  (temporarily removed in #2120 to unblock a Play Console catch-22). The Play Console
  foreground service type declaration must be completed before the next Play release —
  see PR body for the console step. (#2188)
