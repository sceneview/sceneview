<!-- category: Changed -->
<!-- breaking: false -->

Add AR visuals to the Play Store and App Store listings
([#2844](https://github.com/sceneview/sceneview/issues/2844)). The listing
text sells AR but no image showed any. Slot 1 of every screenshot class
(`phone`, `tablet7`, `tablet10`, `iphone-6.9`, `ipad-13`) and the Play feature
graphic are now AI-generated marketing visuals — Gemini image-to-image from the
committed hero-model reference, the sci-fi helmet anchored in a real
photographed room per DESIGN.md's AR art direction — because a real AR capture
needs a device camera: ARCore's recording/playback path fails on the QA
emulators (session creation probes camera HAL id 0 before consulting the
playback dataset, and the arm64 AVDs have none). The existing real captures
were shifted down one slot, not replaced. Committing is not uploading — the
release workflows sync both listings from the repository, so the visuals reach
the stores with the next minor release.
