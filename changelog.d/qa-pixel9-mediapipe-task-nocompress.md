<!-- category: Fixed -->
- AR Body Tracker demo (Android): added `task` to the demo APK's `noCompress` set. The MediaPipe `.task` bundle is a ZIP that MediaPipe memory-maps at runtime; re-compressing it in the APK made `PoseLandmarker.createFromOptions` fail to load the pose model.
