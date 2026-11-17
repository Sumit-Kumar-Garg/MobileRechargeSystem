package com.app.recharge

import com.app.recharge.core.network.ApiClient
import com.app.recharge.core.security.SettingsTokenStore
import com.app.recharge.core.security.TokenStore
import com.app.recharge.core.telemetry.ConsentStore
import com.app.recharge.core.telemetry.CoroutineTelemetryFlusher
import com.app.recharge.core.telemetry.QueueTelemetryRecorder
import com.app.recharge.core.telemetry.SessionManager
import com.app.recharge.core.telemetry.SqlTelemetryQueue
import com.app.recharge.core.telemetry.TelemetryRecorder
import com.app.recharge.core.telemetry.TelemetryUploader
import com.app.recharge.feature.auth.AuthRepository
import com.app.recharge.feature.recharge.data.DeviceRequest
import com.app.recharge.feature.recharge.data.RechargeApi
import com.app.recharge.feature.recharge.data.RechargeRepository
import com.app.recharge.feature.recharge.data.RechargeRepositoryImpl
import com.app.recharge.feature.recharge.domain.ObserveOrderUseCase
import com.app.recharge.feature.recharge.domain.PayForPlanUseCase
import com.app.recharge.platform.PlatformServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Builds and holds every long-lived object of the app, once per process. This is the app's dependency
 * injection: plain constructors, wired in one place, the same on Android and iOS. Each platform shell
 * creates one AppGraph with its own [PlatformServices].
 */
class AppGraph(val platform: PlatformServices, val config: AppConfig) {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    val session = SessionManager()
    val tokens: TokenStore = SettingsTokenStore(platform.secureSettings)
    val consent = ConsentStore(platform.plainSettings)
    val networkMonitor = platform.networkMonitor

    private val apiClient = ApiClient(
        engine = platform.httpEngine,
        config = config,
        tokens = tokens,
        session = session,
        network = networkMonitor,
        languageTag = platform::languageTag,
        json = json,
    )
    val api = RechargeApi(apiClient)
    val auth = AuthRepository(api, tokens)

    private val flusher = CoroutineTelemetryFlusher(appScope)
    private val telemetryQueue = SqlTelemetryQueue(platform.telemetryDriver)
    val recorder: TelemetryRecorder = QueueTelemetryRecorder(
        queue = telemetryQueue,
        consent = consent,
        session = session,
        flusher = flusher,
        scope = appScope,
        platform = platform.name,
    )
    private val uploader = TelemetryUploader(telemetryQueue, api::telemetry, session, config.appVersion, json)

    val repository: RechargeRepository = RechargeRepositoryImpl(api)
    val payForPlan = PayForPlanUseCase(repository)
    val observeOrder = ObserveOrderUseCase(repository)

    init {
        // Close the two dependency loops: client -> recorder -> uploader -> client, and client -> auth -> client.
        apiClient.recorder = recorder
        apiClient.refreshToken = { rejected -> auth.refreshAfterUnauthorized(rejected) }
        flusher.upload = { uploader.uploadAll() }
    }

    /** Sends events left over from the last run. Called when the app comes to the foreground. */
    fun flushTelemetry() = flusher.flushNow()

    /** Called by the platform shell when the push service hands out a (new) device token. */
    fun registerPushToken(token: String) {
        tokens.pushToken = token
        appScope.launch {
            if (auth.ensureLoggedIn()) api.registerDevice(DeviceRequest(token))
        }
    }
}
