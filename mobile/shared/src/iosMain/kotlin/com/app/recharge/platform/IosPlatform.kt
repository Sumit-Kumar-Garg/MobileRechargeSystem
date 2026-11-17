package com.app.recharge.platform

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.app.recharge.core.network.NetworkMonitor
import com.app.recharge.db.TelemetryDatabase
import com.russhwolf.settings.ExperimentalSettingsImplementation
import com.russhwolf.settings.KeychainSettings
import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSLocale
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterCurrencyStyle
import platform.Foundation.NSUserDefaults
import platform.Foundation.preferredLanguages

/** iOS's answers to what the shared code needs from the operating system. */
class IosPlatform : PlatformServices {

    override val name = "ios"

    /** Tokens go to the Keychain, the iOS counterpart of Android's encrypted preferences. */
    @OptIn(ExperimentalSettingsImplementation::class)
    override val secureSettings: Settings by lazy { KeychainSettings(service = "com.app.recharge.session") }

    override val plainSettings: Settings by lazy { NSUserDefaultsSettings(NSUserDefaults.standardUserDefaults) }

    override val httpEngine: HttpClientEngine by lazy { Darwin.create() }

    override val telemetryDriver: SqlDriver by lazy { NativeSqliteDriver(TelemetryDatabase.Schema, "telemetry.db") }

    override val networkMonitor: NetworkMonitor by lazy { IosNetworkMonitor() }

    override fun languageTag(): String = (NSLocale.preferredLanguages.firstOrNull() as? String) ?: "en"

    override fun formatMoney(amountMinor: Int, currencyCode: String): String {
        val formatter = NSNumberFormatter()
        formatter.numberStyle = NSNumberFormatterCurrencyStyle
        formatter.currencyCode = currencyCode
        // After the currency is set, the formatter knows its digits (2 for EUR, 0 for JPY).
        var divisor = 1.0
        repeat(formatter.maximumFractionDigits.toInt()) { divisor *= 10.0 }
        return formatter.stringFromNumber(NSNumber(double = amountMinor / divisor)) ?: "$amountMinor $currencyCode"
    }
}
