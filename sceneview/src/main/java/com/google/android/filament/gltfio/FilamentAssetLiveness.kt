@file:JvmName("FilamentAssetLiveness")

package com.google.android.filament.gltfio

/**
 * `true` once [AssetLoader.destroyAsset] ran on this asset.
 *
 * gltfio zeroes the asset's handle when it destroys it, and only the asset's: its
 * [FilamentInstance]s and their [Animator] keep pointing at freed memory, and the asset's own
 * methods hand the zero to native code. Every call into either after the destroy is a `SIGSEGV`
 * that no `try` catches, so the callers have to check before the call, and this is the only
 * trace of the destroy left on the Java side.
 *
 * The handle getter is package-private in gltfio, hence this file's package.
 */
internal val FilamentAsset.isReleased: Boolean
    get() = nativeObject == 0L

/**
 * [FilamentAsset.releaseSourceData], skipped once the asset is destroyed: gltfio would hand the
 * zeroed handle to native code. A resource resolver can destroy the model in the middle of its own
 * synchronous load (`ModelLoader.clear` from the callback), and the release comes right after.
 */
internal fun FilamentAsset.releaseSourceDataIfAlive() {
    if (!isReleased) releaseSourceData()
}
