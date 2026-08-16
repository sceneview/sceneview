<!-- category: Fixed -->
- **The CREDITS file inside the Play Store APK was neither generated nor gated ([#2941](https://github.com/sceneview/sceneview/issues/2941)).**
  `generate-credits.py` wrote one file, `assets/CREDITS.md`, and `ci.yml` → `repo-hygiene`
  plus `pre-push-check.sh` checked that same one — while the repository tracked five
  `CREDITS.md` files. The copy bundled in the APK had last been touched on 2026-06-04
  against 2026-07-28 for the generated one, and 6 of the 19 assets it ships alongside
  (`audio/bell.wav`, `augmented_images/qrcode.png`, `mediapipe/pose_landmarker_lite.task`,
  `splats/rainbow_sphere.ply`, `textures/sceneview_logo.png`, `videos/sample.mp4`) had no
  attribution line anywhere in it. Two CC-BY-4.0 models it did list — `shiba.glb` and
  `threejs_soldier.glb` — were filed under a heading with no author at all, which is the
  clause CC-BY 4.0 §3(a) actually requires. The generator now owns every shipped copy:
  `assets/CREDITS.md` from the full catalogue, the APK copy from the files genuinely
  present in `samples/android-demo/src/main/assets/`, and the two demo audio credits as
  byte-for-byte mirrors of `assets/audio/CREDITS.md` (hand-written on purpose — `bell.wav`
  is ffmpeg-generated and is not a catalogue asset). A bundled file that matches neither a
  catalogue entry nor an explicit declaration in the script now fails the gate instead of
  shipping uncredited, and `test-generate-credits.sh` — new, wired into `repo-hygiene`, so
  `pre-push-check.sh` leg 19 discovers it too — pins all of that by mutation, including a
  case that fails if a sixth `CREDITS.md` is added to the repository without being named
  in the generator.
