package com.app.recharge.feature.auth

import com.app.recharge.FakeTokens
import com.app.recharge.core.FakeServer
import com.app.recharge.core.apiClient
import com.app.recharge.feature.recharge.data.RechargeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class AuthRepositoryTest {

    private val server = FakeServer()
    private val tokens = FakeTokens()
    private val auth = AuthRepository(RechargeApi(apiClient(server, tokens = tokens)), tokens)

    private fun tokenJson(token: String) = """{"accessToken":"$token","tokenType":"Bearer","expiresInSeconds":900,"userId":"u1"}"""

    @Test fun firstStartLogsInAndStoresTokenAndUser() = runTest {
        server.enqueue(body = tokenJson("t1"))

        assertTrue(auth.ensureLoggedIn())

        assertEquals("t1", tokens.accessToken)
        assertEquals("u1", tokens.userId)
    }

    @Test fun alreadyLoggedInMeansNoRequest() = runTest {
        tokens.accessToken = "t1"
        assertTrue(auth.ensureLoggedIn())
        assertTrue(server.requests.isEmpty())
    }

    @Test fun aNewTokenIsRequestedForTheSameUserSoOrdersDoNotDisappear() = runTest {
        tokens.userId = "u1"
        server.enqueue(body = tokenJson("t2"))

        auth.loginDemo()

        assertEquals("""{"userId":"u1"}""", server.last.body)
    }

    @Test fun loginFailureIsReportedNotThrown() = runTest {
        server.enqueue(status = 503)
        assertFalse(auth.ensureLoggedIn())
        assertNull(tokens.accessToken)
    }

    @Test fun afterA401AFreshTokenIsFetched() = runTest {
        tokens.accessToken = "expired"
        server.enqueue(body = tokenJson("fresh"))

        assertEquals("fresh", auth.refreshAfterUnauthorized("expired"))
        assertEquals("fresh", tokens.accessToken)
    }

    @Test fun whenAnotherCallAlreadyRefreshedTheTokenIsReusedWithoutASecondLogin() = runTest {
        tokens.accessToken = "fresh"                      // someone else refreshed while this call was failing

        assertEquals("fresh", auth.refreshAfterUnauthorized("expired"))
        assertTrue(server.requests.isEmpty())
    }
}
