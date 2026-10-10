# SceneView Drive — SceneView on Android Auto

SceneView as an Android Auto **parked app**. One full-screen scene, two ways to be in it:

- **Night Garage**, the landing: a car on a turntable in a dark garage — drag to orbit, pinch to
  zoom, three large controls to step through the cars, their finishes and the lighting.
- **Drive**, behind the fourth control: the same car comes off the podium onto the garage floor.
  Steer with the left thumb, throttle and brake with the right, a chase camera behind the car.
  In the Night lighting the car lights the floor with its own headlights. "Garage" or Back
  returns to the turntable.

> **Status: not verified in a car.** It is proven on an emulator resized to head-unit dimensions
> (800x480 and 1920x1080 at 160 dpi), debug and release builds. Showing it on a head unit needs
> a phone running Android 15 or later connected to Android Auto or to the Desktop Head Unit —
> that run has not been done.

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

## Release build

```bash
./gradlew :samples:android-auto-demo:bundleRelease -PversionCode=2 -PversionName=0.1.1
# → samples/android-auto-demo/build/outputs/bundle/release/android-auto-demo-release.aab
```

The release build is shrunk with R8. `versionCode` and `versionName` default to `1` and `0.1.0`;
the app has its own version track, independent of the SDK's. The bundle is signed when these four
environment variables are set, and built unsigned when they are not:

| Variable | Holds |
|---|---|
| `SCENEVIEW_AUTO_KEYSTORE_FILE` | Path to the upload keystore |
| `SCENEVIEW_AUTO_KEYSTORE_PASSWORD` | Its password |
| `SCENEVIEW_AUTO_KEY_ALIAS` | The key's alias |
| `SCENEVIEW_AUTO_KEY_PASSWORD` | The key's password |

Nothing publishes this app: no workflow, no script. The bundle is uploaded by hand.

## Where things are

| File | What it holds |
|---|---|
| `GarageActivity.kt` | The screen: loading, reveal, the showroom/Drive switch, the frame loop, gestures |
| `GarageScene.kt` | `GarageFloor`, `Podium`, `CarMount { }`, `GarageCar` and its headlights — the 3D scene |
| `GarageStage.kt` | Stage dimensions, in metres |
| `DriveModel.kt` | The car on the floor: a kinematic bicycle model, kept between the podium and the edge line |
| `ChaseCamera.kt` | The camera that trails the car in Drive |
| `OrbitCamera.kt` | The showroom's eased orbit camera, clamped above the floor, aspect-aware |
| `GarageCatalog.kt` | Cars, finishes, lightings, and how a finish is applied |
| `GarageSelection.kt` | What is selected, and its persistence across launches |
| `GarageChrome.kt` | Title, controls, drive pads, loading cover |
| `AutoTokens.kt` | The `DESIGN.md` tokens the chrome uses — the only place with literals |

`CarMount { }` is the one node every car hangs from. Its content is in car space — the model, its
contact shadow and its headlights are declared once — and the frame loop writes the mount's pose:
the turntable's angle in the showroom, `DriveModel`'s position and heading on the road.

`DriveModel` has no Android or Filament dependency and is covered by `DriveModelTest`.

## Assets

Nothing is added to the repository for this sample. `stageGarageAssets` picks five files that
`assets/manifest.json` already registers into `build/generated/garageAssets`; licences and
authors are in [`assets/CREDITS.md`](../../assets/CREDITS.md).

| Asset | Author | Licence |
|---|---|---|
| Car Concept (`CarConcept.glb`) | Darmstadt Graphics Group GmbH | CC BY 4.0 |
| Toy Car (`khronos_toy_car.glb`) | Guido Odendahl, Eric Chadwick | CC0 1.0 |
| `studio_2k.hdr` (Poly Haven `christmas_photo_studio_07`) | Sergej Majboroda | CC0 1.0 |
| `studio_warm_2k.hdr` (Poly Haven `studio_small_08`) | Sergej Majboroda | CC0 1.0 |
| `rooftop_night_2k.hdr` (Poly Haven `rooftop_night`) | Greg Zaal | CC0 1.0 |

The CC BY car is credited on screen, next to its name.

The Car Concept's finishes are the model's own `KHR_materials_variants`. The Toy Car's body is
textured, so it keeps its factory finish and the Paint control is shown without being tappable;
its display cloth stays in the showroom when the car goes on the road.
