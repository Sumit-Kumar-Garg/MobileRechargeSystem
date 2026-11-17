package com.app.recharge

/** What each platform shell tells the shared code at start. */
data class AppConfig(
    /** Backend root, ending in "/". Android emulator: http://10.0.2.2:8080/ · iOS simulator: http://localhost:8080/ */
    val baseUrl: String,
    val appVersion: String,
)
