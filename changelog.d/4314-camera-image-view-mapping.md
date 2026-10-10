<!-- category: Fixed -->
- **Demo app: camera-image labels and body landmarks are built to stay aligned in portrait and landscape.** The Android ML demos use an exact ARCore image-to-view mapping and stable object tracking. A label is now placed by casting a world ray from the camera pose and intrinsics captured with the analysed image, onto the depth map when the device has one, so it lands on the object instead of the surface behind it and no longer depends on how far the phone moved during detection.

<!-- category: Added -->
- **Android AR camera-image coordinate mapping.** `Frame.cameraImageToViewMapping(imageSize, inputRotationDegrees)` snapshots ARCore display geometry into a `CameraImageToViewMapping` that maps rotated detector pixels (`mapPixel`, `mapNormalized`) or points of the unrotated image (`mapImagePixel`, `mapImageNormalized`) into view pixels with the correct center crop; `detectorPixelToImagePixel` returns the pixel the camera intrinsics describe.
