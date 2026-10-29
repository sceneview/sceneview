package io.github.sceneview.demo.feedback

import java.util.Collections

/**
 * Masking of the app log before a bug report leaves the device (#4526).
 *
 * The report carries the app's own logcat into a share and into a pre-filled
 * GitHub issue, which is public. Some of its lines can name something the user
 * chose: `rememberSplatCloud: cannot load content://…`, a
 * `FileNotFoundException: /storage/emulated/0/Download/kitchen.glb: open failed`,
 * the display name of a file opened with "Open with SceneView". Nobody reads
 * four hundred log lines before pressing Send, so the report masks them itself.
 *
 * What is masked, each replaced by a `<redacted …>` placeholder:
 * - `content://` URIs past their authority, and `file:` URIs (app assets excepted);
 * - absolute paths under the user's storage and the per-user data directories
 *   (`/storage`, `/sdcard`, `/mnt`, `/data/user`, `/data/user_de`, `/data/data`,
 *   `/data/media`);
 * - Storage Access Framework document ids (`primary:Download/…`, `raw:/…`,
 *   `msf:42`, an SD card's `1A2B-3C4D:…`);
 * - e-mail addresses;
 * - credentials, query and fragment of `http(s)` URLs (the host and path stay);
 * - every name the user gave the app through a picker or a share
 *   ([UserChosenNames]).
 *
 * What stays: the logcat columns (date, time, pid, tid, level, tag), class and
 * method names, `File.kt:77` frames, exception types and messages around the
 * masked part, and the file extension of a masked path, which says which loader
 * failed without saying which file.
 */
internal object LogRedaction {

    /** Placeholder base. Angle brackets: no path, URI or class name contains them. */
    const val REDACTED = "<redacted>"
    const val REDACTED_EMAIL = "<redacted email>"
    const val REDACTED_DOCUMENT = "<redacted document>"
    const val REDACTED_NAME = "<redacted name>"
    const val REDACTED_QUERY = "?<redacted>"

    /** Names shorter than this are not masked on their own: too likely to be an ordinary word. */
    const val MIN_NAME_LENGTH = 3

    /**
     * The rest of a location, up to the first character that cannot be part of it
     * in a log message: `": "` (how `FileNotFoundException` and most log messages
     * continue after a path), a double quote, a tab, or the end of the line.
     * Spaces and parentheses are taken in: `Download/scan (1).glb` is one file name.
     */
    private const val TAIL = """(?:(?!: |["\t]).)*"""

    private val CONTENT_URI = Regex("""\bcontent://([A-Za-z0-9._\-]+)(/$TAIL)?""")

    /** `file:` URIs, except the app's own bundled assets. */
    private val FILE_URI = Regex("""\bfile:(?!/*android_asset/)/+$TAIL""")

    private val ABSOLUTE_PATH = Regex(
        """(?<![\w.%/\-])/(storage|sdcard|mnt|data/user_de|data/user|data/data|data/media)(?=/|$|[\s":])$TAIL""",
    )

    private val DOCUMENT_ID = Regex(
        """(?<![\w%])(?:primary|home|raw|msf|msd|[0-9A-F]{4}-[0-9A-F]{4})(?::|%3A)(?=\S)$TAIL""",
    )

    private val EMAIL = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)*\.[A-Za-z]{2,}""")

    private val HTTP_URL = Regex("""\b(https?://)(?:[^\s/@"?#]+@)?([^\s/?#"]+)([^\s?#"]*)([?#][^\s"]*)?""")

    private val EXTENSION = Regex("""\.([A-Za-z0-9]{1,5})$""")

    /** [lines] with [redactLine] applied to each, same order and count. */
    fun redactLines(lines: List<String>, userNames: Collection<String> = emptyList()): List<String> {
        val names = usableNames(userNames)
        return lines.map { redactLine(it, names) }
    }

    /** One log line with every location, address and user-chosen name masked. */
    fun redactLine(line: String, userNames: Collection<String> = emptyList()): String {
        var out = line
        // http(s) first: its host and path stay, and must not be read as a path.
        out = HTTP_URL.replace(out) { match ->
            val kept = match.groupValues[1] + match.groupValues[2] + match.groupValues[3]
            if (match.groupValues[4].isEmpty()) kept else kept + REDACTED_QUERY
        }
        out = CONTENT_URI.replace(out) { match ->
            val authority = match.groupValues[1]
            val rest = match.groupValues[2]
            if (rest.isEmpty()) match.value else "content://$authority/$REDACTED${extensionOf(rest)}"
        }
        out = FILE_URI.replace(out) { match -> "file://$REDACTED${extensionOf(match.value)}" }
        out = ABSOLUTE_PATH.replace(out) { match ->
            "/${match.groupValues[1]}/$REDACTED${extensionOf(match.value)}"
        }
        out = DOCUMENT_ID.replace(out) { match -> REDACTED_DOCUMENT + extensionOf(match.value) }
        out = EMAIL.replace(out, REDACTED_EMAIL)
        for (name in usableNames(userNames)) {
            out = out.replace(name, REDACTED_NAME + extensionOf(name), ignoreCase = true)
        }
        return out
    }

    /** `.glb` for `…/kitchen.glb`, nothing when the masked part does not end on an extension. */
    private fun extensionOf(location: String): String =
        EXTENSION.find(location.trimEnd())?.value.orEmpty()

    /** Longest first, so a name is never half-masked by a shorter one it contains. */
    private fun usableNames(names: Collection<String>): List<String> = names
        .map { it.trim() }
        .filter { it.length >= MIN_NAME_LENGTH }
        .distinct()
        .sortedByDescending { it.length }
}

/**
 * Names the user handed the app this process — the display name of a file
 * opened with "Open with SceneView" — so [LogRedaction] can mask them wherever
 * a log line repeats them, even outside a path (#4526). In memory only, never
 * written anywhere.
 */
internal object UserChosenNames {
    private const val MAX_NAMES = 32
    private val names: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    fun remember(name: String?) {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.length < LogRedaction.MIN_NAME_LENGTH) return
        synchronized(names) {
            names.add(trimmed)
            while (names.size > MAX_NAMES) names.remove(names.first())
        }
    }

    fun snapshot(): List<String> = synchronized(names) { names.toList() }

    fun clear() = names.clear()
}
