package com.app.recharge.core.telemetry

/** What about the device configuration can change under a running app. Filled in by each platform shell. */
data class ConfigSnapshot(
    val orientation: String,
    val locale: String,
    val darkMode: Boolean,
    val fontScale: Float,
    val windowSize: String,
)

object WindowBuckets {
    fun of(widthDp: Int): String = when {
        widthDp < 600 -> "compact"
        widthDp < 840 -> "medium"
        else -> "expanded"
    }
}

object ConfigDiff {
    fun diff(prev: ConfigSnapshot, next: ConfigSnapshot): List<String> = buildList {
        if (prev.orientation != next.orientation) add("orientation")
        if (prev.locale != next.locale) add("locale")
        if (prev.darkMode != next.darkMode) add("dark_mode")
        if (prev.fontScale != next.fontScale) add("font_scale")
        if (prev.windowSize != next.windowSize) add("window_size")
    }
}
