<!-- category: Fixed -->
<!-- breaking: false -->
A model created while its load was being cancelled no longer leaks. `ModelLoader.loadModel`
and `loadInstancedModel` hop to the main thread to build the Filament asset, and
`withContext` drops that result when the caller is cancelled mid-hop — which is exactly
what `SceneViewer` does on every source swap. The asset is now destroyed on that path, and
a model cancelled during resource loading is freed instead of sitting in the loader until
`destroy()`. `SceneViewerError`'s constructor is public, so an app can unit-test its
`onError` handler without a renderer. The self-hosted runner installer gives CI its own
`GRADLE_USER_HOME`.
