package com.app.recharge.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.app.recharge.AppConfig
import com.app.recharge.AppGraph
import com.app.recharge.platform.AndroidPlatform

/** The Android shell: builds the shared app graph once per process. */
class RechargeApplication : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(AndroidPlatform(this), AppConfig(BuildConfig.BASE_URL, BuildConfig.VERSION_NAME))
        createReceiptChannel()
    }

    private fun createReceiptChannel() {
        val channel = NotificationChannel(
            RECEIPTS_CHANNEL,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = getString(R.string.notification_channel_description) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val RECEIPTS_CHANNEL = "receipts"
    }
}
