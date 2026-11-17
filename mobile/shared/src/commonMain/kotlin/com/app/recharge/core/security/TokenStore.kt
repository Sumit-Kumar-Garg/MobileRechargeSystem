package com.app.recharge.core.security

import com.russhwolf.settings.Settings

/** Secrets and identifiers that must never sit in plain text on disk. */
interface TokenStore {
    var accessToken: String?
    var userId: String?
    var pushToken: String?
}

/** Backed by the platform's encrypted store (see PlatformServices.secureSettings). */
class SettingsTokenStore(private val settings: Settings) : TokenStore {

    override var accessToken: String?
        get() = settings.getStringOrNull(KEY_ACCESS)
        set(value) = put(KEY_ACCESS, value)

    override var userId: String?
        get() = settings.getStringOrNull(KEY_USER)
        set(value) = put(KEY_USER, value)

    override var pushToken: String?
        get() = settings.getStringOrNull(KEY_PUSH)
        set(value) = put(KEY_PUSH, value)

    private fun put(key: String, value: String?) {
        if (value == null) settings.remove(key) else settings.putString(key, value)
    }

    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_USER = "user_id"
        const val KEY_PUSH = "push_token"
    }
}
