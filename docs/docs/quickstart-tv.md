# Android TV Quickstart

SceneView works on Android TV using the same Filament renderer as mobile. The `SceneView { }` composable renders identically — only the input handling differs (D-pad instead of touch).

## Setup

Add SceneView to your TV app module:

```groovy
// build.gradle
dependencies {
    implementation "io.github.sceneview:sceneview:4.13.0"
    implementation "androidx.tv:tv-material:1.0.0"
}
```

## Manifest

```xml
<uses-feature android:name="android.software.leanback" android:required="true" />
<uses-feature android:name="android.hardware.touchscreen" android:required="false" />

<activity android:name=".TvActivity">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
    </intent-filter>
</activity>
```

## D-pad Controls

```kotlin
Box(modifier = Modifier
    .fillMaxSize()
    .onKeyEvent { event ->
        if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
        when (event.nativeKeyEvent.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { rotationY -= 15f; true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { rotationY += 15f; true }
            KeyEvent.KEYCODE_DPAD_UP -> { zoom = (zoom - 0.3f).coerceAtLeast(0.5f); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { zoom = (zoom + 0.3f).coerceAtMost(10f); true }
            KeyEvent.KEYCODE_DPAD_CENTER -> { nextModel(); true }
            else -> false
        }
    }
) {
    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = rememberEngine(),
        // ... same API as mobile
    ) {
        // ... same nodes as mobile
    }
}
```

## Key Differences from Mobile

| Aspect | Mobile | TV |
|---|---|---|
| Input | Touch (pinch, rotate, tap) | D-pad (arrows, select, play/pause) |
| Screen | Portrait/landscape | Landscape only (16:9) |
| Distance | Arms length | 3+ meters ("10-foot UI") |
| Focus | Touch targets | D-pad focus management |

## Sample

See `samples/android-tv-demo/` for a complete Android TV sample with D-pad orbit/zoom controls and model cycling.
