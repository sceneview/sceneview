# SceneView on Android Auto — Night Garage

A prototype: SceneView as an Android Auto **parked app**. One full-screen scene, a car on a
turntable in a dark garage — drag to orbit, pinch to zoom, three large controls to step through
the cars, their finishes and the lighting.

> **Status: prototype, not verified in a car.** It is proven on an emulator resized to head-unit
> dimensions (800x480 and 1920x1080 at 160 dpi). Showing it on a head unit needs a phone running
> Android 15 or later connected to Android Auto or to the Desktop Head Unit — that run has not
> been done.

## How it reaches the car

Parked apps are plain activities: Android Auto shows the phone's activity on the car's screen
while the car is parked. No Car App Library, no template, no host. Two manifest declarations,
both from [Build parked apps for Android Auto](https://developer.android.com/training/cars/parked/auto):

```xml
<application android:appCategory="game" …>
    <activity android:name=".GarageActivity" android:exported="true" android:resizeableActivity="true">
        <intent-filter>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
            <category android:name="android.intent.category.CAR_LAUNCHER" />
        </intent-filter>
    </activity>
</application>
```

Games are the only parked category Android Auto supports today, which is why the category is
`game`. The activity has no orientation lock and handles size changes in place: head units are
landscape, wide or portrait, and a pillarboxed app is rejected.

The application id is `io.github.sceneview.demo.auto`, deliberately not the Play demo's: a car
declaration in the store app would put each of its updates through car review.

## Build and run

```bash
./gradlew :samples:android-auto-demo:assembleDebug
adb install -r samples/android-auto-demo/build/outputs/apk/debug/android-auto-demo-debug.apk
```

On a phone or an emulator it runs as an ordinary app. To see it at a head unit's size on an
emulator:

```bash
adb shell wm size 800x480 && adb shell wm density 160      # small landscape head unit
adb shell wm size 1920x1080 && adb shell wm density 160    # wide head unit
adb shell wm size reset && adb shell wm density reset
```

## Where things are

| File | What it holds |
|---|---|
| `GarageActivity.kt` | The screen: loading, reveal, turntable and camera loop, gestures |
| `GarageScene.kt` | `GarageFloor`, `Turntable { }`, `ParkedCar` — the 3D scene |
| `GarageStage.kt` | Stage dimensions, in metres |
| `OrbitCamera.kt` | Eased orbit camera, clamped above the floor, aspect-aware |
| `GarageCatalog.kt` | Cars, finishes, lightings, and how a finish is applied |
| `GarageSelection.kt` | What is selected, and its persistence across launches |
| `GarageChrome.kt` | Title, controls, loading cover |
| `AutoTokens.kt` | The `DESIGN.md` tokens the chrome uses — the only place with literals |

`Turntable { }` is the seam for what comes next. Its content lives in turntable space; a driving
mini-game replaces the turntable's rotation with a vehicle node driven by input and keeps the
children as they are.

## Assets

Nothing is added to the repository for this sample. `stageGarageAssets` picks six files that
`assets/manifest.json` already registers into `build/generated/garageAssets`; licences and
authors are in [`assets/CREDITS.md`](../../assets/CREDITS.md).

| Asset | Author | Licence |
|---|---|---|
| Car Concept (`CarConcept.glb`) | Darmstadt Graphics Group GmbH | CC BY 4.0 |
| Ferrari F40 (`ferrari_f40.glb`) | Black Snow | CC BY 4.0 |
| Toy Car (`khronos_toy_car.glb`) | Guido Odendahl, Eric Chadwick | CC0 1.0 |
| `studio_2k.hdr`, `studio_warm_2k.hdr`, `rooftop_night_2k.hdr` | Poly Haven | CC0 1.0 |

The two CC BY cars are credited on screen, next to the car's name.

The Car Concept's finishes are the model's own `KHR_materials_variants`. The F40's are tints of
its single untextured body material. The Toy Car's body is textured, so it keeps its factory
finish and the Paint control is shown without being tappable.
