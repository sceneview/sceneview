<!-- category: Fixed -->
Denying the camera permission no longer throws the user out of the app (#3308). `ARSceneView`
used to react to a denial by opening the system App Info page with a toast, backgrounding the
activity with no explanation. It now shows an in-app explanation over the scene — the new
`ARCameraPermissionOverlay`, with a "Grant camera access" button that re-requests — and only
offers "Open settings" once the system has stopped asking (`shouldShowRequestPermissionRationale`
is `false` after a denial), and only on that explicit tap. `ARCore` exposes the state
(`onCameraPermissionDenied`, `isCameraPermissionDenied`, `retryCameraPermission()`,
`openAppSettings()`) and `ARSceneView` gains a `cameraPermissionOverlay` slot (`null` to draw
nothing); every existing call site compiles unchanged.
