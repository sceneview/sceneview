package io.github.sceneview

import android.app.Activity

/**
 * Empty activity whose only job is to give instrumented tests a real window: a [android.view.TextureView]
 * has no `SurfaceTexture` until it is attached to a hardware-accelerated window, and a
 * [android.view.SurfaceView] has no surface at all. Tests fill it with `setContentView`.
 */
class SurfaceHostActivity : Activity()
