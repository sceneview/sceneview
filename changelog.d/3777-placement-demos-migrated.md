<!-- category: Changed -->

Migrate AR depth/people occlusion, image stabilization, recording, and local Cloud Anchor setup to shared automatic placement. Occlusion comparisons use the same bundled 0.3 m helmet preview and retain placement when toggled. Host and Record remain explicit actions; measurement now commits its visible center candidate with Add point. Reject unsupported comparisons before exposing controls, and consolidate iOS placement into the single AR Placement entry.
