package io.github.sceneview.node

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import com.google.android.filament.RenderableManager
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.utils.TextureType
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.EngineDestroyQueue
import io.github.sceneview.geometries.Plane
import io.github.sceneview.geometries.UvScale
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.texture.ImageTexture
import io.github.sceneview.texture.TextureSampler2D
import io.github.sceneview.texture.setBitmap

/**
 * A node that renders a [Bitmap] image on a flat textured plane in 3D space.
 *
 * The plane is auto-sized from the image's aspect ratio (longest edge normalised to 1.0 world
 * unit) unless an explicit [size] is given. Assigning a new [bitmap] re-uploads the texture to the
 * GPU and optionally re-sizes the geometry.
 *
 * Multiple constructors are provided for loading from a [Bitmap], an asset file path, or a
 * drawable resource ID.
 *
 * @see PlaneNode
 * @see io.github.sceneview.texture.ImageTexture
 */
open class ImageNode private constructor(
    val materialLoader: MaterialLoader,
    bitmap: Bitmap,
    val texture: Texture,
    textureSampler: TextureSampler = TextureSampler2D(),
    /**
     * `null` to adjust size on the normalized image size
     */
    val size: Size? = null,
    center: Position = Plane.DEFAULT_CENTER,
    normal: Direction = Plane.DEFAULT_NORMAL,
    uvScale: UvScale = UvScale(1.0f),
    builderApply: RenderableManager.Builder.() -> Unit = {}
) : PlaneNode(
    engine = materialLoader.engine,
    size = size ?: normalize(Size(bitmap.width.toFloat(), bitmap.height.toFloat())),
    center = center,
    normal = normal,
    uvScale = uvScale,
    materialInstance = materialLoader.createImageInstance(texture, textureSampler),
    builderApply = builderApply
) {
    var bitmap = bitmap
        set(value) {
            field = value
            texture.setBitmap(engine, value)
            if (size == null) {
                updateGeometry(
                    size = normalize(Size(value.width.toFloat(), value.height.toFloat()))
                )
            }
        }

    constructor(
        materialLoader: MaterialLoader,
        bitmap: Bitmap,
        /**
         * `null` to adjust size on the normalized image size
         */
        size: Size? = null,
        center: Position = Plane.DEFAULT_CENTER,
        normal: Direction = Plane.DEFAULT_NORMAL,
        uvScale: UvScale = UvScale(1.0f),
        type: TextureType = ImageTexture.DEFAULT_TYPE,
        textureSampler: TextureSampler = TextureSampler2D(),
        builderApply: RenderableManager.Builder.() -> Unit = {},
        textureBuilderApply: ImageTexture.Builder.() -> Unit = {}
    ) : this(
        materialLoader = materialLoader,
        bitmap = bitmap,
        texture = ImageTexture.Builder()
            .bitmap(bitmap)
            .type(type)
            .apply(textureBuilderApply)
            .build(materialLoader.engine),
        textureSampler = textureSampler,
        size = size,
        center = center,
        normal = normal,
        uvScale = uvScale,
        builderApply = builderApply
    )

    constructor(
        materialLoader: MaterialLoader,
        /**
         * `null` to adjust size on the normalized image size
         */
        size: Size? = null,
        center: Position = Plane.DEFAULT_CENTER,
        normal: Direction = Plane.DEFAULT_NORMAL,
        uvScale: UvScale = UvScale(1.0f),
        imageFileLocation: String,
        type: TextureType = ImageTexture.DEFAULT_TYPE,
        textureSampler: TextureSampler = TextureSampler2D(),
        builderApply: RenderableManager.Builder.() -> Unit = {},
        textureBuilderApply: ImageTexture.Builder.() -> Unit = {}
    ) : this(
        materialLoader = materialLoader,
        bitmap = ImageTexture.getBitmap(materialLoader.assets, imageFileLocation),
        size = size,
        center = center,
        normal = normal,
        uvScale = uvScale,
        type = type,
        textureSampler = textureSampler,
        builderApply = builderApply,
        textureBuilderApply = textureBuilderApply
    )

    constructor(
        materialLoader: MaterialLoader,
        /**
         * `null` to adjust size on the normalized image size
         */
        size: Size? = null,
        center: Position = Plane.DEFAULT_CENTER,
        normal: Direction = Plane.DEFAULT_NORMAL,
        uvScale: UvScale = UvScale(1.0f),
        @DrawableRes imageResId: Int,
        type: TextureType = ImageTexture.DEFAULT_TYPE,
        textureSampler: TextureSampler = TextureSampler2D(),
        builderApply: RenderableManager.Builder.() -> Unit = {},
        textureBuilderApply: ImageTexture.Builder.() -> Unit = {}
    ) : this(
        materialLoader = materialLoader,
        bitmap = ImageTexture.getBitmap(materialLoader.context, imageResId),
        size = size,
        center = center,
        normal = normal,
        uvScale = uvScale,
        type = type,
        textureSampler = textureSampler,
        builderApply = builderApply,
        textureBuilderApply = textureBuilderApply
    )

    fun setBitmap(fileLocation: String, type: TextureType = ImageTexture.DEFAULT_TYPE) {
        bitmap = ImageTexture.getBitmap(materialLoader.assets, fileLocation, type)
    }

    fun setBitmap(@DrawableRes drawableResId: Int, type: TextureType = ImageTexture.DEFAULT_TYPE) {
        bitmap = ImageTexture.getBitmap(materialLoader.context, drawableResId, type)
    }

    /**
     * Releases the [MaterialInstance] and frame-defers destruction of the GPU [Texture].
     *
     * Destroying the texture eagerly — before Filament has reclaimed the bound
     * [MaterialInstance] — triggers a native SIGABRT (`Invalid texture still bound to
     * MaterialInstance`), because MI reclamation is coupled to the render loop, not to this
     * call. To avoid both that crash and a GPU-memory leak, the texture is handed to the
     * per-`Engine` [io.github.sceneview.EngineDestroyQueue], which destroys it a few rendered
     * frames later (and immediately at Engine teardown). This makes high-churn UIs (feeds,
     * infinite scrollers, particle emitters) safe — see sceneview/sceneview#874.
     */
    override fun destroy() {
        val mi = materialInstance
        super.destroy()
        materialLoader.destroyMaterialInstance(mi)
        // Frame-defer the texture destroy so Filament reclaims the MI first (see KDoc, #874).
        EngineDestroyQueue.of(engine).enqueueTexture(texture)
    }
}