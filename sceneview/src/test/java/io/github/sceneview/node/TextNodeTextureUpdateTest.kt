package io.github.sceneview.node

import android.graphics.Bitmap
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import com.google.android.filament.android.TextureHelper
import io.github.sceneview.loaders.MaterialLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.nio.Buffer
import kotlin.math.floor
import kotlin.math.ln

/**
 * A new text on a [TextNode] reaches the pixels the GPU actually reads for it.
 *
 * A label is a 512 x 128 bitmap on a quad a few centimetres wide, sampled with
 * `LINEAR_MIPMAP_LINEAR`: drawn about a hundred pixels wide, the GPU reads mip levels 2 and 3, not
 * level 0. Writing the new bitmap to level 0 alone left those levels on the image the texture was
 * built with, so the label kept showing its first text while its `text` property, its bitmap and
 * level 0 all said otherwise.
 *
 * What is real here: the [TextNode] and the [ImageNode] path it updates through, the
 * [MaterialLoader] that binds the texture to the label's material instance, the sampler, and the
 * Filament Java classes. What is not: the native side, replaced by [Gpu], which keeps for every mip
 * level of every texture the bitmap that level was last made from — uploaded to it directly, or
 * downsampled from level 0 by `generateMipmaps` — and records which texture and sampler each
 * material instance samples and which material instance each renderable draws with.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    instrumentedPackages = ["com.google.android.filament"],
    shadows = [
        TextNodeTextureUpdateTest.GpuTextures::class,
        TextNodeTextureUpdateTest.GpuUploads::class,
        TextNodeTextureUpdateTest.GpuMaterials::class,
        TextNodeTextureUpdateTest.GpuMaterialInstances::class,
        TextNodeTextureUpdateTest.GpuSamplers::class,
        TextNodeTextureUpdateTest.GpuVertexBuffers::class,
        TextNodeTextureUpdateTest.GpuIndexBuffers::class,
        TextNodeTextureUpdateTest.GpuRenderables::class,
        TextNodeTextureUpdateTest.GpuEntities::class
    ]
)
class TextNodeTextureUpdateTest {

    private lateinit var materialLoader: MaterialLoader

    @Before
    fun setUp() {
        Gpu.reset()
        materialLoader = MaterialLoader(
            FilamentWithoutJni.engine(),
            RuntimeEnvironment.getApplication()
        )
    }

    /** What the GPU draws [node] with: the texture its material instance samples, and how. */
    private fun drawn(node: RenderableNode): Pair<Gpu.TextureState, TextureSampler.MinFilter> {
        val materialInstance = Gpu.renderables.getValue(node.entity).materials.getValue(0)
        val binding = Gpu.textureBindings.getValue(materialInstance to "texture")
        return Gpu.textures.getValue(binding.texture) to
            Gpu.samplerMinFilters.getValue(binding.sampler)
    }

    /**
     * The two mip levels a trilinear sampler blends for [texture] drawn [onScreenWidthPx] wide:
     * the ones around log2(texture width / on-screen width).
     */
    private fun sampledLevels(texture: Gpu.TextureState, onScreenWidthPx: Int): List<Int> {
        val lod = ln(texture.width.toDouble() / onScreenWidthPx) / ln(2.0)
        val lower = floor(lod).toInt().coerceIn(0, texture.levels - 1)
        return listOf(lower, (lower + 1).coerceAtMost(texture.levels - 1)).distinct()
    }

    private fun bitmap() = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)

    @Test
    fun `a new text reaches the mip levels a label is drawn from`() {
        val node = TextNode(materialLoader, text = "5 in")
        val (texture, minFilter) = drawn(node)

        // The label is sampled through its mip chain: what is on screen is not level 0.
        assertEquals(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, minFilter)
        assertEquals(10, texture.levels)
        val levels = sampledLevels(texture, LABEL_ON_SCREEN_PX)
        assertEquals(listOf(2, 3), levels)
        val first = node.bitmap
        levels.forEach { assertSame(first, texture.levelSource[it]) }

        node.text = "4 ft 1 in · 11 ft²"

        val second = node.bitmap
        assertNotSame(first, second)
        assertSame("level 0", second, texture.levelSource[0])
        levels.forEach { level ->
            assertSame("mip level $level, read on screen", second, texture.levelSource[level])
        }
    }

    @Test
    fun `every text of a rising label reaches the whole mip chain`() {
        val node = TextNode(materialLoader, text = "1.1 ft²")
        val (texture, _) = drawn(node)

        listOf("1.9 ft²", "3.1 ft²", "3.9 ft²").forEach { text ->
            node.text = text
            texture.levelSource.forEachIndexed { level, source ->
                assertSame("'$text', mip level $level", node.bitmap, source)
            }
        }
    }

    @Test
    fun `a bitmap set on an image node reaches its whole mip chain`() {
        val node = ImageNode(materialLoader, bitmap = bitmap())
        val (texture, _) = drawn(node)
        assertEquals(7, texture.levels)

        val next = bitmap()
        node.bitmap = next

        texture.levelSource.forEachIndexed { level, source ->
            assertSame("mip level $level", next, source)
        }
    }

    /** The native side of Filament, as far as a textured quad goes. */
    object Gpu {
        class TextureBuilderState {
            var width = 1
            var height = 1
            var levels = 1
        }

        class TextureState(val width: Int, val height: Int, requestedLevels: Int) {
            /** Filament clamps the level count to the full chain of the texture's size. */
            val levels = requestedLevels.coerceAtMost(
                (31 - Integer.numberOfLeadingZeros(maxOf(width, height))) + 1
            )

            /** For each mip level, the bitmap it was last made from. */
            val levelSource = arrayOfNulls<Bitmap>(levels)
        }

        class TextureBinding(val texture: Long, val sampler: Long)

        class RenderableState(val primitiveCount: Int) {
            /** Per primitive, the material instance it is drawn with. */
            val materials = HashMap<Int, Long>()
        }

        private var nextHandle = 1L
        private var nextEntity = 1

        val textureBuilders = HashMap<Long, TextureBuilderState>()
        val textures = HashMap<Long, TextureState>()
        val samplerMinFilters = HashMap<Long, TextureSampler.MinFilter>()
        val textureBindings = HashMap<Pair<Long, String>, TextureBinding>()
        val renderableBuilders = HashMap<Long, RenderableState>()
        val renderables = HashMap<Int, RenderableState>()

        fun handle() = nextHandle++
        fun entity() = nextEntity++

        fun reset() {
            nextHandle = 1L
            nextEntity = 1
            textureBuilders.clear()
            textures.clear()
            samplerMinFilters.clear()
            textureBindings.clear()
            renderableBuilders.clear()
            renderables.clear()
        }
    }

    // The shadows repeat the signatures of the native methods they stand in for, used or not.

    @Suppress("UnusedParameter")
    @Implements(Texture::class)
    class GpuTextures {
        companion object {
            @JvmStatic
            @Implementation
            fun nCreateBuilder(): Long = Gpu.handle().also {
                Gpu.textureBuilders[it] = Gpu.TextureBuilderState()
            }

            @JvmStatic
            @Implementation
            fun nBuilderWidth(builder: Long, width: Int) {
                Gpu.textureBuilders.getValue(builder).width = width
            }

            @JvmStatic
            @Implementation
            fun nBuilderHeight(builder: Long, height: Int) {
                Gpu.textureBuilders.getValue(builder).height = height
            }

            @JvmStatic
            @Implementation
            fun nBuilderLevels(builder: Long, levels: Int) {
                Gpu.textureBuilders.getValue(builder).levels = levels
            }

            @JvmStatic
            @Implementation
            fun nBuilderBuild(builder: Long, engine: Long): Long {
                val state = Gpu.textureBuilders.getValue(builder)
                return Gpu.handle().also {
                    Gpu.textures[it] = Gpu.TextureState(state.width, state.height, state.levels)
                }
            }

            @JvmStatic
            @Implementation
            fun nGetWidth(texture: Long, level: Int): Int =
                maxOf(1, Gpu.textures.getValue(texture).width shr level)

            @JvmStatic
            @Implementation
            fun nGetHeight(texture: Long, level: Int): Int =
                maxOf(1, Gpu.textures.getValue(texture).height shr level)

            @JvmStatic
            @Implementation
            fun nGetLevels(texture: Long): Int = Gpu.textures.getValue(texture).levels

            /** Every level above 0 is rebuilt from level 0. */
            @JvmStatic
            @Implementation
            fun nGenerateMipmaps(texture: Long, engine: Long) {
                val levels = Gpu.textures.getValue(texture).levelSource
                for (level in 1 until levels.size) levels[level] = levels[0]
            }
        }
    }

    @Suppress("UnusedParameter", "LongParameterList")
    @Implements(TextureHelper::class)
    class GpuUploads {
        companion object {
            /** One level, the one asked for, is written. */
            @JvmStatic
            @Implementation
            fun nSetBitmap(
                texture: Long,
                engine: Long,
                level: Int,
                xoffset: Int,
                yoffset: Int,
                width: Int,
                height: Int,
                bitmap: Bitmap,
                format: Int
            ) {
                Gpu.textures.getValue(texture).levelSource[level] = bitmap
            }
        }
    }

    @Suppress("UnusedParameter", "LongParameterList")
    @Implements(Material::class)
    class GpuMaterials {
        companion object {
            @JvmStatic
            @Implementation
            fun nBuilderBuild(
                engine: Long,
                buffer: Buffer,
                size: Int,
                shBandCount: Int,
                shadowQuality: Int,
                uboBatchingMode: Int
            ): Long = Gpu.handle()

            @JvmStatic
            @Implementation
            fun nCreateInstance(material: Long): Long = Gpu.handle()
        }
    }

    @Implements(MaterialInstance::class)
    class GpuMaterialInstances {
        companion object {
            @JvmStatic
            @Implementation
            fun nSetParameterTexture(
                materialInstance: Long,
                name: String,
                texture: Long,
                sampler: Long
            ) {
                Gpu.textureBindings[materialInstance to name] = Gpu.TextureBinding(texture, sampler)
            }
        }
    }

    @Suppress("UnusedParameter")
    @Implements(TextureSampler::class)
    class GpuSamplers {
        companion object {
            @JvmStatic
            @Implementation
            fun nCreateSampler(min: Int, mag: Int, s: Int, t: Int, r: Int): Long =
                Gpu.handle().also {
                    Gpu.samplerMinFilters[it] = TextureSampler.MinFilter.entries[min]
                }

            @JvmStatic
            @Implementation
            fun nGetMinFilter(sampler: Long): Int = Gpu.samplerMinFilters.getValue(sampler).ordinal
        }
    }

    @Suppress("UnusedParameter")
    @Implements(VertexBuffer::class)
    class GpuVertexBuffers {
        companion object {
            @JvmStatic
            @Implementation
            fun nBuilderBuild(builder: Long, engine: Long): Long = Gpu.handle()
        }
    }

    @Suppress("UnusedParameter")
    @Implements(IndexBuffer::class)
    class GpuIndexBuffers {
        companion object {
            @JvmStatic
            @Implementation
            fun nBuilderBuild(builder: Long, engine: Long): Long = Gpu.handle()
        }
    }

    @Suppress("UnusedParameter")
    @Implements(RenderableManager::class)
    class GpuRenderables {
        companion object {
            @JvmStatic
            @Implementation
            fun nCreateBuilder(count: Int): Long = Gpu.handle().also {
                Gpu.renderableBuilders[it] = Gpu.RenderableState(count)
            }

            @JvmStatic
            @Implementation
            fun nBuilderMaterial(builder: Long, index: Int, materialInstance: Long) {
                Gpu.renderableBuilders.getValue(builder).materials[index] = materialInstance
            }

            @JvmStatic
            @Implementation
            fun nBuilderBuild(builder: Long, engine: Long, entity: Int): Boolean {
                Gpu.renderables[entity] = Gpu.renderableBuilders.getValue(builder)
                return true
            }

            // A renderable's instance is its entity here.

            @JvmStatic
            @Implementation
            fun nHasComponent(manager: Long, entity: Int): Boolean = entity in Gpu.renderables

            @JvmStatic
            @Implementation
            fun nGetInstance(manager: Long, entity: Int): Int =
                if (entity in Gpu.renderables) entity else 0

            @JvmStatic
            @Implementation
            fun nGetPrimitiveCount(manager: Long, instance: Int): Int =
                Gpu.renderables[instance]?.primitiveCount ?: 0

            @JvmStatic
            @Implementation
            fun nGetMaterialInstanceAt(manager: Long, instance: Int, primitive: Int): Long =
                Gpu.renderables[instance]?.materials?.get(primitive) ?: 0L

            @JvmStatic
            @Implementation
            fun nSetMaterialInstanceAt(
                manager: Long,
                instance: Int,
                primitive: Int,
                materialInstance: Long
            ) {
                Gpu.renderables.getValue(instance).materials[primitive] = materialInstance
            }
        }
    }

    @Suppress("UnusedParameter")
    @Implements(EntityManager::class)
    class GpuEntities {
        companion object {
            @JvmStatic
            @Implementation
            fun nCreate(entityManager: Long): Int = Gpu.entity()
        }
    }

    private companion object {
        /** About how wide a measure label is drawn on a phone screen, in pixels. */
        const val LABEL_ON_SCREEN_PX = 100
    }
}
