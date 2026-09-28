package io.github.sceneview.demo.demos

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import androidx.test.core.app.ApplicationProvider
import io.github.sceneview.demo.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Locks the FileProvider config that powers [ARRecordPlaybackDemo]'s native
 * Share button. Two things have to line up or `FileProvider.getUriForFile`
 * throws `IllegalArgumentException` at runtime — exactly the kind of
 * "looks-fine-in-Android-Studio, crashes on first share" trap a unit test
 * catches without needing an emulator.
 *
 * 1. `<provider android:authorities="${applicationId}.fileprovider">` in
 *    `AndroidManifest.xml` — verified via `PackageManager.getProviderInfo`.
 * 2. The provider points at `@xml/file_paths` — verified via the metadata
 *    on the registered ProviderInfo. The actual content of file_paths.xml
 *    (the `external-files-path` entry covering `ar-recordings/`) cannot be
 *    sanely tested under Robolectric — its filesystem mock layout doesn't
 *    match the real `getExternalFilesDir(null)` prefix the runtime
 *    FileProvider expects, so we instead exercise the URI generation in an
 *    instrumented `androidTest` once a device is connected.
 */
@RunWith(RobolectricTestRunner::class)
class ARRecordPlaybackShareTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `share authority is the package id plus fileprovider suffix`() {
        // Mirror of the runtime assembly in `shareRecording(...)`.
        val authority = "${context.packageName}.fileprovider"
        // Debug builds carry `applicationIdSuffix ".qa"` (they install next to the Play
        // Store app), so the id is the build's own, never a literal: a hardcoded
        // `io.github.sceneview.demo.fileprovider` would collide between the two installs.
        assertEquals("${BuildConfig.APPLICATION_ID}.fileprovider", authority)
        assertTrue(
            "Application id should be the demo's, got '${context.packageName}'",
            context.packageName.startsWith("io.github.sceneview.demo"),
        )
    }

    @Test
    fun `FileProvider is registered in the merged manifest under the expected authority`() {
        val authority = "${context.packageName}.fileprovider"
        val info: ProviderInfo? = context.packageManager.resolveContentProvider(
            authority, /* flags = */ PackageManager.GET_META_DATA,
        )
        assertNotNull(
            "FileProvider not registered for authority '$authority' — " +
                "check <provider android:authorities=...> in AndroidManifest.xml",
            info,
        )
        assertEquals(
            "FileProvider should be the standard androidx core implementation",
            "androidx.core.content.FileProvider",
            info!!.name,
        )
        assertEquals(
            "FileProvider must NOT be exported (security)",
            false,
            info.exported,
        )
        assertTrue(
            "FileProvider must grant per-URI read permissions for ACTION_SEND",
            info.grantUriPermissions,
        )
    }

    @Test
    fun `FileProvider points at the file_paths xml resource`() {
        val authority = "${context.packageName}.fileprovider"
        val info: ProviderInfo = context.packageManager.resolveContentProvider(
            authority, PackageManager.GET_META_DATA,
        ) ?: error("provider not registered — see other test")
        // The FILE_PROVIDER_PATHS metadata key carries the resource id of
        // file_paths.xml. If the resource is missing the metadata is absent;
        // if it points elsewhere, the int won't equal R.xml.file_paths.
        val pathsResId = info.metaData?.getInt(
            "android.support.FILE_PROVIDER_PATHS",
            /* defaultValue = */ 0,
        ) ?: 0
        assertTrue(
            "FileProvider must declare <meta-data android:name=" +
                "\"android.support.FILE_PROVIDER_PATHS\" android:resource=\"@xml/file_paths\"/>",
            pathsResId != 0,
        )
        // Confirm the resource is named file_paths.xml — not a stale rename.
        val resName = context.resources.getResourceEntryName(pathsResId)
        assertEquals("file_paths", resName)
    }
}
