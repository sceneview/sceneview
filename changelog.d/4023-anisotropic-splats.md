<!-- category: Changed -->
- **SplatNode (Android):** each gaussian now draws as an oriented ellipse, built from its rotation
  and 3-axis scale (screen-space 2D covariance). Before, it was a round blob sized by its largest
  axis, so scans looked hazy. Colours now show as they were captured, where the View's tone mapping
  used to wash them out. On a re-sort only a 4-byte-per-splat order texture is re-uploaded. The
  4-texture `MaterialLoader.createSplatInstance` overload is new; the 2-texture one is deprecated
  (#4023). The web port is tracked in #4046.
