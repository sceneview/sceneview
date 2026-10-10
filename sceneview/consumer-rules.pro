# Filament — JNI bridge classes must not be renamed or stripped
-keep class com.google.android.filament.** { *; }

# Kotlin-Math — used reflectively for transform operations
-keep class dev.romainguy.kotlin.math.** { *; }

# SceneView collision system — uses reflection for shape intersection
-keep class io.github.sceneview.collision.** { *; }

# Render-on-demand reads, once per Node class, whether the class overrides `isFrameActive`
# (#3724). Renaming the getter does not break anything — the lookup then falls back to asking
# every node of that class in full — but keeping the name keeps the fast path.
-keepclassmembernames class io.github.sceneview.node.Node { boolean isFrameActive(); }
-keepclassmembernames class * extends io.github.sceneview.node.Node { boolean isFrameActive(); }
