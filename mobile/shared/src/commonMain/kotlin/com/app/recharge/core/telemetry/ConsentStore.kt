package com.app.recharge.core.telemetry

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Synchronous view of the user's privacy choices (the recorder checks it on every event). */
interface ConsentState {
    val analyticsGranted: Boolean
    val personalizationGranted: Boolean
}

data class ConsentSnapshot(
    val personalization: Boolean = false,
    val analytics: Boolean = false,
    val decided: Boolean = false,
)

/** Opt-in by default: until the user decides, nothing behavioral is recorded. */
class ConsentStore(private val settings: Settings) : ConsentState {

    private val _snapshot = MutableStateFlow(read())
    val snapshot: StateFlow<ConsentSnapshot> = _snapshot.asStateFlow()

    override val analyticsGranted: Boolean get() = _snapshot.value.analytics
    override val personalizationGranted: Boolean get() = _snapshot.value.personalization

    fun save(personalization: Boolean, analytics: Boolean) {
        settings.putBoolean(PERSONALIZATION, personalization)
        settings.putBoolean(ANALYTICS, analytics)
        settings.putBoolean(DECIDED, true)
        _snapshot.value = read()
    }

    private fun read() = ConsentSnapshot(
        personalization = settings.getBoolean(PERSONALIZATION, false),
        analytics = settings.getBoolean(ANALYTICS, false),
        decided = settings.getBoolean(DECIDED, false),
    )

    private companion object {
        const val PERSONALIZATION = "consent_personalization"
        const val ANALYTICS = "consent_analytics"
        const val DECIDED = "consent_decided"
    }
}
