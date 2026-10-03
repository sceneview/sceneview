<!-- category: Fixed -->
- **Demo app: camera-image labels and body landmarks now stay aligned in portrait, landscape, and while moving.** The Android ML demos use an exact ARCore image-to-view mapping, stable object tracking, stale-pose rejection, and nearest-surface placement; 2D-in-3D cards also face the camera from their authored world positions.

<!-- category: Added -->
- **Android AR camera-image coordinate mapping.** `CameraImageToViewMapping` snapshots ARCore display geometry and maps rotated detector pixels or normalized landmarks into view pixels with the correct center crop.
