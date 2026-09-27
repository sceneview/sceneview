package io.github.sceneview

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins where `rememberSplatCloud(fileLocation)` reads each documented kind of location from
 * (#4023). The read itself is plain `AssetManager` / `File` / `URL` / `ContentResolver` I/O.
 */
class SplatSourceKindTest {

    private fun kind(location: String): SplatSourceKind =
        splatSourceKind(java.net.URI(location).scheme, location)

    @Test
    fun bareRelativePathIsAnAsset() {
        assertEquals(SplatSourceKind.Asset, kind("splats/raccoon_family.spz"))
        assertEquals(SplatSourceKind.Asset, kind("scan.ply"))
    }

    @Test
    fun absolutePathIsAFile() {
        assertEquals(SplatSourceKind.File, kind("/data/user/0/app/files/scan.spz"))
    }

    @Test
    fun httpAndHttpsAreDownloaded() {
        assertEquals(SplatSourceKind.Http, kind("https://example.com/scan.spz"))
        assertEquals(SplatSourceKind.Http, kind("HTTP://example.com/scan.spz"))
    }

    @Test
    fun otherSchemesGoThroughTheContentResolver() {
        assertEquals(SplatSourceKind.ContentResolver, kind("content://media/external/file/42"))
        assertEquals(SplatSourceKind.ContentResolver, kind("file:///sdcard/Download/scan.spz"))
        assertEquals(SplatSourceKind.ContentResolver, kind("android.resource://pkg/raw/scan"))
    }
}
