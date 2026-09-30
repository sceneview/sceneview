package io.github.sceneview.demo.ui.home

/** Which of the two Home row anatomies a demo is drawn with. */
enum class HomeRowStyle {
    /** `home-row`: picture on the leading half, dissolving sideways into the row. */
    Fused,

    /** `home-banner`: picture across the full width, dissolving down into its caption. */
    Banner,
}
