package io.github.sceneview.demo.demos.internal.feel

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.Random

class MatterDetailCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun cache() = MatterDetailCache(temporary.root)
    private val directory get() = File(temporary.root, "matter")
    private fun file(kind: FeelMaterial) = File(directory, "${kind.name}-${MatterDetailAtlas.CACHE_VERSION}.bin")
    private val families = FeelMaterial.entries.filter { it != FeelMaterial.Gel }

    @Test fun missDoesNotCreateFiles() {
        assertNull(cache().read(FeelMaterial.MemoryFoam))
        assertFalse(directory.exists())
    }

    @Test fun everyBakedAtlasSurvivesANewCacheInstanceWithinFiveMiB() {
        for (kind in families) {
            val pixels = MatterDetailAtlas.bake(kind)
            assertTrue("$kind must fit its disk slot", cache().write(kind, pixels))
            assertArrayEquals(pixels, cache().read(kind))
            assertTrue(file(kind).length() <= MatterDetailCache.MAX_FILE_BYTES)
        }
        assertEquals(5, directory.listFiles()!!.size)
        assertTrue(directory.listFiles()!!.sumOf { it.length() } <= 5L * MatterDetailCache.MAX_FILE_BYTES)
    }

    @Test fun truncatedOversizedAndSameLengthCorruptFilesAreMissesAndCanBeReplaced() {
        val kind = FeelMaterial.MemoryFoam
        val pixels = MatterDetailAtlas.bake(kind)
        assertTrue(cache().write(kind, pixels))
        val valid = file(kind).readBytes()
        val corruptions = listOf(
            byteArrayOf(), valid.copyOf(8), valid.copyOf(valid.size - 1), valid + byteArrayOf(1),
            valid.copyOf().apply { this[12] = (this[12].toInt() xor 1).toByte() }, // CRC header
            valid.copyOf().apply { this[size / 2] = (this[size / 2].toInt() xor 64).toByte() },
            valid.copyOf().apply { this[0] = 0 },
            valid.copyOf().apply { this[5] = 0 }, // wrong decoded size
        )
        for (corruption in corruptions) {
            file(kind).writeBytes(corruption)
            assertNull(cache().read(kind))
        }
        RandomAccessFile(file(kind), "rw").use { it.setLength(MatterDetailCache.MAX_FILE_BYTES + 1L) }
        assertNull(cache().read(kind))
        assertTrue(cache().write(kind, pixels))
        assertArrayEquals(pixels, cache().read(kind))
    }

    @Test fun oldVersionsAndInterruptedWritesArePrunedAndReplacementLeavesOnlyFinalFiles() {
        directory.mkdirs()
        File(directory, "MemoryFoam-0.bin").writeBytes(ByteArray(500))
        File(directory, "Marble-${MatterDetailAtlas.CACHE_VERSION}.bin.tmp").writeBytes(ByteArray(500))
        val pixels = MatterDetailAtlas.bake(FeelMaterial.Marble)
        assertTrue(cache().write(FeelMaterial.Marble, pixels))
        assertTrue(cache().write(FeelMaterial.Marble, pixels))
        assertEquals(listOf(file(FeelMaterial.Marble).name), directory.listFiles()!!.map { it.name })
    }

    @Test fun unavailableDirectoryAndIncompressiblePayloadNeverBlockTheScene() {
        directory.writeText("Cannot create a directory here")
        val pixels = ByteArray(MatterDetailCache.MAX_FILE_BYTES)
        assertFalse(cache().write(FeelMaterial.MemoryFoam, pixels))
        assertNull(cache().read(FeelMaterial.MemoryFoam))
        directory.delete()
        Random(42).nextBytes(pixels)
        assertFalse(cache().write(FeelMaterial.MemoryFoam, pixels))
        assertNull(cache().read(FeelMaterial.MemoryFoam))
        assertEquals(0, directory.listFiles()!!.size)
    }
}
