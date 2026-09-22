package io.github.sceneview.demo.ui.home

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * Hands the "Generate a 3D world" card over to **AR Model Viewer**, the companion
 * app that actually runs the World Labs generation.
 *
 * The feature is not in this app and is not meant to be: generation spends paid
 * credits, and the demo catalogue is a free showcase of the SDK. So this file is
 * the whole integration — one card, one redirect, no account, no billing code.
 *
 * **Why a chain and not a single intent.** Three states have to land somewhere
 * sensible, and only one of them is the happy path:
 *  - the companion app is installed *and* already handles [DEEP_LINK] — open it
 *    straight on the generation screen;
 *  - it is installed but predates the deep link — its launcher intent is still a
 *    better destination than the store listing of an app the user already has;
 *  - it is not installed — the Play Store listing, with the store app preferred
 *    over the web listing because it keeps the user on-device.
 *
 * Every step is attempted inside `runCatching`, so an `ActivityNotFoundException`
 * (or a resolver that lies) falls through to the next one instead of crashing the
 * catalogue, exactly as the browser openers in this module do (#1208).
 *
 * The decision itself is [plan], a pure function over "is it installed", so the
 * order can be asserted without a device.
 *
 * **Package visibility.** API 30+ hides other packages from `queryIntentActivities`
 * unless they are declared, which is why `AndroidManifest.xml` lists [PACKAGE] in
 * its `<queries>` block. Without it `getLaunchIntentForPackage` returns `null` and
 * `setPackage` resolves to nothing, and every installed user would be sent to the
 * store listing of an app they already have.
 */
object GenerateWorldRedirect {

    /** AR Model Viewer on Google Play. Mirrored in the manifest's `<queries>`. */
    const val PACKAGE = "com.gorisse.thomas.arcamera"

    /**
     * Deep-link contract AR Model Viewer will implement; today it may not resolve,
     * and the chain below copes.
     */
    const val DEEP_LINK = "armodelviewer://generate-world?source=sceneview-demo"

    /**
     * Install attribution carried by both store links. Pre-encoded: the whole
     * `referrer` value is one Play Store query parameter, so its own `=` and `&`
     * are `%3D` / `%26` — spelled out rather than built, because a `referrer`
     * that arrives half-decoded is a report that silently reads zero.
     */
    private const val REFERRER =
        "referrer=utm_source%3Dsceneview-demo" +
            "%26utm_medium%3Dhome-card" +
            "%26utm_campaign%3Dgenerate-world"

    /** Play Store listing through the store app — keeps the user on-device. */
    const val PLAY_STORE_MARKET = "market://details?id=$PACKAGE&$REFERRER"

    /** Play Store listing in a browser — the fallback where no store app exists. */
    const val PLAY_STORE_WEB =
        "https://play.google.com/store/apps/details?id=$PACKAGE&$REFERRER"

    /** One destination the redirect may try, in the order [plan] returns them. */
    enum class Step {
        /** The companion app's own generation screen. */
        DeepLink,

        /** The companion app's launcher screen — installed, but no deep link yet. */
        Launcher,

        /** The Play Store listing, opened in the store app. */
        PlayStoreApp,

        /** The Play Store listing, opened in a browser. */
        PlayStoreWeb,
    }

    /**
     * The destinations to try, best first.
     *
     * When the app is absent the two in-app steps are dropped rather than attempted
     * and caught: a `setPackage` intent for a package that is not there cannot
     * succeed, and skipping it keeps the observable behaviour ("not installed goes
     * to the store") a property of this function rather than of exception order.
     */
    fun plan(installed: Boolean): List<Step> = if (installed) {
        listOf(Step.DeepLink, Step.Launcher, Step.PlayStoreApp, Step.PlayStoreWeb)
    } else {
        listOf(Step.PlayStoreApp, Step.PlayStoreWeb)
    }

    /** Walks [plan] until one destination opens, and says so when none does. */
    fun open(context: Context) {
        // Doubles as the installed check: a package with no launcher activity is
        // not somewhere this card can send anyone anyway.
        val launchIntent: Intent? = runCatching {
            context.packageManager.getLaunchIntentForPackage(PACKAGE)
        }.getOrNull()

        plan(installed = launchIntent != null).forEach { step ->
            val intent = when (step) {
                Step.DeepLink -> Intent(Intent.ACTION_VIEW, DEEP_LINK.toUri()).setPackage(PACKAGE)
                Step.Launcher -> launchIntent
                Step.PlayStoreApp -> Intent(Intent.ACTION_VIEW, PLAY_STORE_MARKET.toUri())
                Step.PlayStoreWeb -> Intent(Intent.ACTION_VIEW, PLAY_STORE_WEB.toUri())
            } ?: return@forEach
            // No browser / no store / a resolver that lied throws
            // ActivityNotFoundException → the app would crash. Same guard as the
            // credits and bug-report openers (#1208).
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }

        android.widget.Toast.makeText(
            context,
            "No app available to open AR Model Viewer",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}
