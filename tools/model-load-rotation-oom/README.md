# Model load out of memory on rotation: emulator repro

Reproduces the report behind #5271: on SceneView 4.72.0, rotating an Android 8.0 device with a
16 MiB ART heap while a model loads ends in `OutOfMemoryError` (in `FileLoader.readFully` or
`GltfMaterialCompatibility.prepare`), while 4.16.10 renders.

The app is a minimal copy of the reporter's scene: `rememberEngine` + `rememberModelLoader` + one
`rememberModelInstance("models/drone.glb")`, in an activity recreated on rotation.

## Model

`drone.glb` (5,794,580 bytes) is a third-party asset from the public report
(`Labs-Ascend/MAVLab` at `843fefe3fe58345609282ec4c548c811a44ac659`). It is fetched at run time and
never committed (`app/src/main/assets/models/` is ignored):

    curl -L -o app/src/main/assets/models/drone.glb https://raw.githubusercontent.com/Labs-Ascend/MAVLab/843fefe3fe58345609282ec4c548c811a44ac659/mavlab-android/app/src/main/assets/models/drone.glb

## Build

    ./gradlew :app:assembleDebug -Psv=4.72.0          # from Maven Central
    # the fix: from the repo root on the #5271 branch
    ./gradlew :sceneview:publishToMavenLocal -PVERSION_NAME=4.72.0-oomfix   # needs a signing key, see the workflow
    ./gradlew :app:assembleDebug -Psv=4.72.0-oomfix

Only `io.github.sceneview:sceneview` takes `-Psv`; the other SceneView artifacts stay on 4.72.0.

## Run

    ./run-trial.sh <apk> <budgetMiB> <out.txt> [waitSeconds]

`budgetMiB > 0` holds ballast so that only that much Java heap is left, to simulate a small heap on
any emulator. `0` uses the emulator's own limit (`adb root; adb shell setprop dalvik.vm.heapgrowthlimit 16m`,
then `adb shell stop; adb shell start`, as `ci-trials.sh` does). The script
prints one line: OOM count, first app frame of the OOM, drain time of the old engine, when the model
became visible for each activity, and peak heap.

## CI

`.github/workflows/model-load-rotation-oom.yml` builds the three APKs, then runs three trials per
version on x86_64 emulators (API 26 to 29, 16 MiB heap; API 26 at 24 and 32 MiB with 4.72.0 to find
the threshold). Results are in each job's summary and in the `oom-api*-heap*` artifacts. An
out-of-memory error is a result, not a job failure.
