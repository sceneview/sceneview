package io.github.sceneview.demo.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.getSystemService

/**
 * The two ways a demo hands a piece of text to the rest of the phone.
 *
 * Both already existed, three times over and `private` each time — `ARRerunDemo` had its
 * own `copyToClipboard` plus a fire-and-forget `ACTION_SEND`, `ARRecordPlaybackDemo` a
 * second share, `BugReportSheet` a third that was the only one to notice when no app
 * could handle the intent (#3263). Cloud Anchors is the fourth caller and the first that
 * *has* to work — the whole demo is "get this code onto another device" — so the two
 * helpers live here now, with the `BugReportSheet` failure handling as the shape.
 */

/**
 * Puts [text] on the clipboard under [label].
 *
 * @return `false` when the platform has no clipboard service, so a caller can say so
 *   instead of silently doing nothing. Callers should confirm a success on screen:
 *   Android only shows its own "Copied" confirmation from API 33, and this app's
 *   `minSdk` is 28.
 */
fun copyToClipboard(context: Context, label: String, text: String): Boolean {
    val manager = context.getSystemService<ClipboardManager>() ?: return false
    return runCatching {
        manager.setPrimaryClip(ClipData.newPlainText(label, text))
    }.isSuccess
}

/**
 * Reads plain text off the clipboard, or `null` when it holds nothing usable.
 *
 * Coerced to text rather than requiring a plain-text item: a code copied out of a chat
 * app often arrives as styled text or a URI, and refusing it would send the user back to
 * retype 40 opaque characters.
 */
fun clipboardText(context: Context): String? {
    val manager = context.getSystemService<ClipboardManager>() ?: return null
    val clip = manager.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0)?.coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
}

/**
 * Opens the system share sheet on [text].
 *
 * @param chooserTitle the chooser's own title.
 * @param subject optional subject line, used by mail and note targets.
 * @return `false` when nothing on the device can handle the intent, so the caller shows
 *   the failure inline rather than appearing to have done nothing (#3263).
 */
fun shareText(
    context: Context,
    text: String,
    chooserTitle: String,
    subject: String? = null,
): Boolean {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject)
    }
    return runCatching {
        context.startActivity(Intent.createChooser(send, chooserTitle))
    }.isSuccess
}
