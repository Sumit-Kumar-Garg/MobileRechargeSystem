package com.app.recharge.platform

import app.cash.sqldelight.db.SqlDriver
import com.app.recharge.core.network.NetworkMonitor
import com.russhwolf.settings.Settings
import io.ktor.client.engine.HttpClientEngine

/**
 * Everything the shared code needs from the operating system. Android and iOS each provide one
 * implementation (AndroidPlatform, IosPlatform); nothing else in the shared code is platform-specific.
 */
interface PlatformServices {
    /** "android" or "ios": sent with telemetry so the two can be compared. */
    val name: String

    /** Encrypted key-value store for tokens: EncryptedSharedPreferences on Android, the Keychain on iOS. */
    val secureSettings: Settings

    /** Plain key-value store for non-secret choices (consent): SharedPreferences / NSUserDefaults. */
    val plainSettings: Settings

    /** HTTP engine: OkHttp on Android, URLSession (Darwin) on iOS. */
    val httpEngine: HttpClientEngine

    /** SQLite driver for the offline telemetry queue. */
    val telemetryDriver: SqlDriver

    val networkMonitor: NetworkMonitor

    /** The user's language, for example "de-DE". Sent as Accept-Language. */
    fun languageTag(): String

    /** Amounts travel as minor units; the platform knows each currency's digits and the local format. */
    fun formatMoney(amountMinor: Int, currencyCode: String): String
}
