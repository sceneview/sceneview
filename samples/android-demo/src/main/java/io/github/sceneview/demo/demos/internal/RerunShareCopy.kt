package io.github.sceneview.demo.demos.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/**
 * The copy of a recording the share sheet sends: the stored capture as it is, or the same scan
 * without its camera photos. The stored recording is only ever read. Pure Kotlin and java.io.
 */
internal object RerunShareCopy {
    /**
     * Writes [capture] as `<title>.svscan` in a fresh directory of [shareRoot], which is emptied
     * first: one shared copy exists at a time, so opening the sheet or flipping its switch never
     * piles scans of several megabytes up in the cache. Call it off the main thread, one at a time.
     */
    fun write(capture: RerunCapturePack, title: String, shareRoot: File, includePhotos: Boolean): File {
        val shared = if (includePhotos) capture else withoutPhotos(capture)
        shareRoot.deleteRecursively()
        val dir = File(shareRoot, UUID.randomUUID().toString()).apply { check(mkdirs()) }
        return File(dir, RerunScanFile.fileName(title)).apply {
            try {
                writeBytes(RerunScanFile.write(shared))
            } catch (failure: Exception) {
                dir.deleteRecursively()
                throw failure
            }
        }
    }

    /**
     * [capture] without its camera photos: the archive keeps the baked plane textures and the
     * dense cloud, repacked from offset 0, and the log loses its `image` events. What stays is
     * still made of camera pixels — the textures are the photos laid on the surfaces, the cloud
     * carries their colours — which is what the sheet says of a file shared this way.
     */
    fun withoutPhotos(capture: RerunCapturePack): RerunCapturePack {
        val parsed = requireNotNull(ReplayManifest.parse(String(capture.manifest))) { "Unreadable manifest" }
        // A whitelist: anything else in the archive is a camera image, including one the log no
        // longer points at. The path counts too: a scan opened from a file could name a photo
        // as a texture, and only what the recorder writes under these two directories stays.
        val kept = (parsed.textures.map { it.path } + listOfNotNull(parsed.dense?.path))
            .filter { path -> KEPT_DIRECTORIES.any(path::startsWith) }
            .toSet()
        val (media, spans) = ScanArchive.pack(
            parsed.media.filterKeys { it in kept }.mapNotNull { (path, span) ->
                if (span.offset + span.length > capture.media.size) return@mapNotNull null
                path to capture.media.copyOfRange(span.offset, span.offset + span.length)
            },
        )
        // Rewritten on the parsed tree, so a key this version does not know survives the share.
        val root = Json.parseToJsonElement(String(capture.manifest)) as JsonObject
        val manifest = JsonObject(
            root + mapOf(
                "frames" to JsonPrimitive(0),
                "media" to JsonArray(
                    spans.map { (path, span) ->
                        buildJsonObject {
                            put("path", path)
                            put("offset", span.offset)
                            put("length", span.length)
                        }
                    },
                ),
            ),
        )
        val log = String(capture.log).lineSequence().filterNot(::isImageEvent).joinToString("\n")
        return RerunCapturePack(manifest.toString().toByteArray(), log.toByteArray(), media)
    }

    /** Only a line that could be an `image` event is parsed: a point cloud line runs to kilobytes. */
    private fun isImageEvent(line: String): Boolean {
        if (IMAGE_TYPE !in line) return false
        val event = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
        return (event?.get("type") as? JsonPrimitive)?.content == IMAGE_TYPE
    }

    private const val IMAGE_TYPE = "image"

    /** Where the recorder writes the baked plane textures and the dense cloud. */
    private val KEPT_DIRECTORIES = listOf("planes/", "dense/")
}
