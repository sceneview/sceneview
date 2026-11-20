<!-- category: Fixed -->
- **Demo app: 3D Scan keeps up with the camera on long room scans.** Fusing a depth frame no longer rebuilds the whole live preview, so fewer frames are skipped as the scan grows. The preview is now built in short slices between depth frames and stays at most two seconds behind the map, even when the depth sensor stops updating.
