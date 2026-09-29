package io.github.sceneview.demo

import androidx.test.platform.app.InstrumentationRegistry

/**
 * Application id of the demo under test, read from the instrumentation instead of
 * hardcoded. Debug builds carry `applicationIdSuffix ".qa"` so they install next to
 * the Play Store app: the id is `io.github.sceneview.demo.qa` there, while the Kotlin
 * namespace — every class name — stays `io.github.sceneview.demo`.
 */
internal val demoPackage: String
    get() = InstrumentationRegistry.getInstrumentation().targetContext.packageName

/**
 * `am start -n` component of [MainActivity]. Spelled with the fully qualified class
 * name: the `<applicationId>/.MainActivity` shorthand resolves the class against the
 * application id, i.e. `io.github.sceneview.demo.qa.MainActivity`, which does not exist.
 */
internal val mainActivityComponent: String
    get() = "$demoPackage/${MainActivity::class.java.name}"
