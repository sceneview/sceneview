package io.github.sceneview.demo.hdpack

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The HD pack: demo content too heavy for the APK, downloaded once in the background and kept
 * for good (2026-09-29). The contract is shared with the iOS demo, byte for byte where it can be:
 *
 * - **Hosting** — the GitHub Release [RELEASE_TAG] of `sceneview/sceneview`. Its files are
 *   immutable and content-addressed: `<sha256>.<ext>`, so a URL never changes meaning.
 * - **Manifest** — `assets/hd-pack/android.json` at the repo root, bundled into the APK as
 *   [MANIFEST_ASSET] at build time (iOS reads `assets/hd-pack/ios.json`). The `id` values are
 *   shared across platforms; the files are not (a GLB here, a USDZ there).
 * - **Storage** — `filesDir/hd-pack/<sha256>.<ext>`. Never evicted by an LRU: only a hash that
 *   is no longer in the manifest is deleted.
 */
@Serializable
data class HdPackManifest(
    val version: Int,
    val assets: List<HdAsset>,
) {
    /** Sum of every asset's size — what the Settings row and the "Download now" dialog quote. */
    val totalBytes: Long get() = assets.sumOf { it.bytes }

    /**
     * What "Download now" still has to fetch: the assets not in [readyIds]. An app update that
     * adds models to a pack already on the device asks for the new files only, not the whole pack.
     */
    fun missingBytes(readyIds: Set<String>): Long = assets.filter { it.id !in readyIds }.sumOf { it.bytes }

    fun asset(id: String): HdAsset? = assets.firstOrNull { it.id == id }

    companion object {
        /** The one schema version this build reads. A newer manifest is ignored, not guessed at. */
        const val SCHEMA_VERSION: Int = 1

        /** GitHub Release that hosts the pack's files. Runtime content, not an SDK release. */
        const val RELEASE_TAG: String = "hd-pack-v1"

        /** Where every file of the pack is downloaded from: `<BASE_URL>/<sha256>.<ext>`. */
        const val BASE_URL: String =
            "https://github.com/sceneview/sceneview/releases/download/$RELEASE_TAG"

        /** Path of the bundled manifest inside the APK's `assets/`. */
        const val MANIFEST_ASSET: String = "hd-pack/android.json"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parses and validates a manifest. Every entry must be content-addressed — `file` is
         * exactly `<sha256>.<ext>` with a 64-hex lowercase hash — because the storage layer
         * relies on the file name alone to tell a current file from a stale one.
         *
         * @throws IllegalArgumentException on a malformed or unsupported manifest.
         */
        fun parse(text: String): HdPackManifest {
            val manifest = json.decodeFromString(serializer(), text)
            require(manifest.version == SCHEMA_VERSION) {
                "Unsupported HD pack manifest version ${manifest.version}"
            }
            val ids = HashSet<String>()
            manifest.assets.forEach { asset ->
                require(ids.add(asset.id)) { "Duplicate HD asset id ${asset.id}" }
                require(SHA256.matches(asset.sha256)) { "Bad sha256 for ${asset.id}" }
                require(asset.file.substringBeforeLast('.') == asset.sha256 && '.' in asset.file) {
                    "HD asset ${asset.id}: file must be <sha256>.<ext>, was ${asset.file}"
                }
                require(asset.bytes > 0) { "HD asset ${asset.id}: bytes must be positive" }
                require(asset.scale.isFinite() && asset.scale > 0f) {
                    "HD asset ${asset.id}: scale must be a positive number, was ${asset.scale}"
                }
            }
            return manifest
        }

        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

/**
 * One file of the HD pack. Field names are the shared contract — do not rename.
 *
 * @property scale metres per model unit: the factor that brings the file to its real-world size.
 *   `0.01` for a centimetre-authored scan (the Smithsonian Apollo 11 files), `1` — the default,
 *   and what a manifest without the field means — for a file already in metres.
 */
@Serializable
data class HdAsset(
    val id: String,
    val title: String,
    val file: String,
    val sha256: String,
    val bytes: Long,
    val license: String,
    val author: String,
    val source: String,
    val scale: Float = 1f,
) {
    /** The immutable download URL of this file. */
    val url: String get() = "${HdPackManifest.BASE_URL}/$file"
}
