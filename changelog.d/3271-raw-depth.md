<!-- category: Fixed -->
**android-demo**: `ar-raw-depth-point-cloud` no longer scatters its points across the wrong
part of the screen in portrait. ARCore's raw-depth image is handed back in the camera-sensor
frame, which does not follow the display — `RawDepthCloud.buildCloud` mapped `x`/`y` straight
onto the screen-space `Canvas` overlay with no rotation correction, so in portrait the cloud
landed 90° off and most points fell outside the visible view (reported as "rotation error...
points are not visible enough", #3271). This is the same class of bug already fixed for the
false-color depth visualization in #3184; `buildCloud` now takes a `rotationDegrees` parameter
(derived from the live display rotation, same as `ARDepthVisualizationDemo`) and re-indexes
each point into the rotated output frame. Pinned by four new JVM tests in
`RawDepthCloudTest` (0°/90°/180°/270° plus the invalid-rotation guard).
