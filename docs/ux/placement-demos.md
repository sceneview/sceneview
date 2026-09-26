# Placement-bearing demos

Depth occlusion, people occlusion, image stabilization, iOS recording and Android
local Cloud Anchor setup use the same automatic placement policy as AR Placement.
A loaded subject receives the first usable horizontal plane; empty-space taps do
not place anything. Reset arms a new placement without restarting the camera.

Android feature sessions use the app's `FeaturePlacementScene` adapter because
these demos require ARCore session and camera-stream configuration hooks. It
consumes the SDK's `AutoPlacementState`, `findAutoPlacementSurface` and
`AutoPlacementModel`; it does not add a public SDK API or another placement policy.
iOS feature sessions use `ARPlacementExperience` and `ARPlacementController`.

Both occlusion comparisons use the bundled Damaged Helmet with a 0.3 m longest
axis preview, grounded after the same authored-axis correction. The toggle changes
renderer options/materials while perception, session, anchor, scale and rotation
remain in place. Capability checks run before exposing comparison controls.
Android checks its ARCore session; iOS routes pass through `ARExperienceContainer`.

The providers differ: Android depth uses Depth API pixels and people occlusion uses
outdoor-trained Scene Semantics; iOS depth uses reconstructed LiDAR mesh occlusion
and people occlusion uses `personSegmentationWithDepth`. Their masks, edge quality
and response to fast movement cannot be described as identical. Unsupported
features are explained honestly. No substitute geometry or simulated camera is
used to demonstrate them.

Host, Resolve and Record require explicit actions. Moving a locally hosted Cloud
Anchor invalidates its former code and any upload in flight. Cloud codes share
anchor localization, not local model scale or pivot rotation; the resolving device
starts with the default lantern appearance. iOS records screen video, not an AR
session that can be replayed for tracking.

Measurement keeps its visible center target and exposes one **Add point** action.
The action consumes the exact candidate last computed for that target, without
running a different touch ray. Tracking loss, viewport changes and changing the
surface-source option invalidate the candidate.

`ar-placement` is the single iOS placement entry. The duplicate Automatic Placement
and One-Call AR scenes and their project/registry inputs have been removed. The
cross-platform catalog ledger is `parity-manifest.yml` at the repository root.

Device verification still needs light/dark camera captures, repeated effect toggles
with a moved/scaled subject, Host/Record idle after placement, and measurement
candidate/marker agreement. Simulator launch captures cannot establish AR correctness.
