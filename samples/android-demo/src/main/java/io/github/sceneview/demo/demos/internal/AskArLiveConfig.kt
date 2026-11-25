package io.github.sceneview.demo.demos.internal

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import io.github.sceneview.demo.BuildConfig

/** Optional generated resource, with a debug-only intent override. Neither contains a key. */
internal object AskArLiveConfig {
    const val CLIENT_TAG = "sceneview-ask-ar-v1"

    @SuppressLint("DiscouragedApi") // The opt-in init file may not have generated this resource.
    fun proxyUrl(context: Context, intent: Intent?): String {
        if (BuildConfig.DEBUG && intent?.hasExtra("ask_proxy") == true) {
            return intent.getStringExtra("ask_proxy").orEmpty().trim()
        }
        val id = context.resources.getIdentifier("ask_ar_proxy_url", "string", context.packageName)
        return if (id == 0) "" else context.getString(id).trim()
    }

    fun provider(proxyUrl: String): StreamingAnswerProvider {
        val scripted = ScriptedStreamingAnswerProvider()
        if (proxyUrl.isBlank()) return scripted
        return try {
            FallbackStreamingAnswerProvider(ProxyStreamingAnswerProvider(proxyUrl, CLIENT_TAG), scripted)
        } catch (_: IllegalArgumentException) {
            scripted
        }
    }
}
