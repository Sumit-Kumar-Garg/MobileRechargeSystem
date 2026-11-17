package com.app.recharge.feature.auth

import com.app.recharge.core.network.ApiResult
import com.app.recharge.core.security.TokenStore
import com.app.recharge.feature.recharge.data.DemoTokenRequest
import com.app.recharge.feature.recharge.data.RechargeApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Demo sign-in against the backend's demo-profile token endpoint. The user id is persisted so a renewed
 * token belongs to the same user (otherwise linked numbers and orders would "disappear").
 * Production: replace with the company IdP (OIDC/PKCE) behind this same class.
 */
class AuthRepository(
    private val api: RechargeApi,
    private val tokens: TokenStore,
) {
    private val refreshLock = Mutex()

    suspend fun ensureLoggedIn(): Boolean = tokens.accessToken != null || loginDemo() != null

    suspend fun loginDemo(): String? = when (val result = api.demoToken(DemoTokenRequest(tokens.userId))) {
        is ApiResult.Success -> {
            tokens.userId = result.value.userId
            tokens.accessToken = result.value.accessToken
            result.value.accessToken
        }
        is ApiResult.Failure -> null
    }

    /**
     * Called by the API client after a 401. Several calls can fail at once; only the first one fetches a
     * new token, the others reuse it.
     */
    suspend fun refreshAfterUnauthorized(rejectedToken: String?): String? = refreshLock.withLock {
        val current = tokens.accessToken
        if (current != null && current != rejectedToken) current else loginDemo()
    }
}
