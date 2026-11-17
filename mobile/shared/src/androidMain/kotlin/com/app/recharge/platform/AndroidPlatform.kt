package com.app.recharge.platform

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.app.recharge.core.network.NetworkMonitor
import com.app.recharge.db.TelemetryDatabase
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** Android's answers to what the shared code needs from the operating system. */
class AndroidPlatform(context: Context) : PlatformServices {

    private val appContext = context.applicationContext

    override val name = "android"

    override val secureSettings: Settings by lazy {
        SharedPreferencesSettings(
            EncryptedSharedPreferences.create(
                appContext,
                "secure_session",
                MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ),
        )
    }

    override val plainSettings: Settings by lazy {
        SharedPreferencesSettings(appContext.getSharedPreferences("recharge_prefs", Context.MODE_PRIVATE))
    }

    override val httpEngine: HttpClientEngine by lazy { OkHttp.create() }

    override val telemetryDriver: SqlDriver by lazy {
        AndroidSqliteDriver(TelemetryDatabase.Schema, appContext, "telemetry.db")
    }

    override val networkMonitor: NetworkMonitor by lazy { AndroidNetworkMonitor(appContext) }

    override fun languageTag(): String = Locale.getDefault().toLanguageTag()

    override fun formatMoney(amountMinor: Int, currencyCode: String): String =
        AndroidMoney.format(amountMinor, currencyCode, Locale.getDefault())
}

object AndroidMoney {
    /** Amounts travel as minor units; the number of fraction digits comes from the currency (JPY has none). */
    fun format(amountMinor: Int, currencyCode: String, locale: Locale): String {
        val currency = Currency.getInstance(currencyCode)
        val format = NumberFormat.getCurrencyInstance(locale)
        format.currency = currency
        return format.format(BigDecimal.valueOf(amountMinor.toLong(), currency.defaultFractionDigits))
    }
}
