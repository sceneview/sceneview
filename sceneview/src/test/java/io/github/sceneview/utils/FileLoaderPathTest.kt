package io.github.sceneview.utils

import android.net.Uri
import org.junit.Assert.assertArrayEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/** Which source [FileLoader.readFileBuffer] reads a location from (Discussion #2571). */
@RunWith(RobolectricTestRunner::class)
class FileLoaderPathTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context get() = RuntimeEnvironment.getApplication()
    private val bytes = byteArrayOf(1, 2, 3, 4, 5)

    private fun modelFile(): File = tempFolder.newFile("model.glb").apply { writeBytes(bytes) }

    private fun read(location: String): ByteArray {
        val buffer = FileLoader.readFileBuffer(context, location)!!
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    @Test
    fun `a bare absolute path reads the file`() {
        assertArrayEquals(bytes, read(modelFile().absolutePath))
    }

    @Test
    fun `a file uri reads the file`() {
        assertArrayEquals(bytes, read(Uri.fromFile(modelFile()).toString()))
    }
}
