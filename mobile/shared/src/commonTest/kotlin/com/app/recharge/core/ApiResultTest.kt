package com.app.recharge.core

import com.app.recharge.core.network.ApiError
import com.app.recharge.core.network.ApiErrorKind
import com.app.recharge.core.network.ApiResult
import com.app.recharge.core.network.httpError
import com.app.recharge.core.network.map
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiResultTest {

    @Test fun problemJsonIsParsedIntoCodeAndCorrelationId() {
        val error = httpError(
            422, """{"status":422,"detail":"Key reused","code":"IDEMPOTENCY_KEY_REUSED","correlationId":"abc-123","extra":1}""",
        )
        assertEquals(ApiErrorKind.CLIENT, error.kind)
        assertEquals(422, error.httpStatus)
        assertEquals("IDEMPOTENCY_KEY_REUSED", error.code)
        assertEquals("Key reused", error.message)
        assertEquals("abc-123", error.correlationId)
    }

    @Test fun unauthorizedIsItsOwnKind() {
        assertEquals(ApiErrorKind.UNAUTHORIZED, httpError(401, "{}").kind)
    }

    @Test fun serverErrorsAreServerKind() {
        assertEquals(ApiErrorKind.SERVER, httpError(503, "{}").kind)
        assertEquals(ApiErrorKind.SERVER, httpError(500, null).kind)
    }

    @Test fun garbageOrEmptyErrorBodyDoesNotCrash() {
        val html = httpError(400, "<html>oops</html>")
        assertEquals(ApiErrorKind.CLIENT, html.kind)
        assertNull(html.code)
        assertNull(httpError(404, "").code)
        assertNull(httpError(404, null).code)
    }

    @Test fun unexpectedStatusesAreUnknown() {
        assertEquals(ApiErrorKind.UNKNOWN, httpError(302, null).kind)
    }

    @Test fun outcomeUnknownCoversLostResponsesAndServerErrorsOnly() {
        assertTrue(ApiError(ApiErrorKind.NETWORK).outcomeUnknown)
        assertTrue(ApiError(ApiErrorKind.TIMEOUT).outcomeUnknown)
        assertTrue(ApiError(ApiErrorKind.SERVER).outcomeUnknown)
        assertFalse(ApiError(ApiErrorKind.OFFLINE).outcomeUnknown)       // never sent
        assertFalse(ApiError(ApiErrorKind.CLIENT).outcomeUnknown)
        assertFalse(ApiError(ApiErrorKind.UNAUTHORIZED).outcomeUnknown)
    }

    @Test fun mapTransformsSuccessAndKeepsFailure() {
        assertEquals(ApiResult.Success(4), ApiResult.Success(2).map { it * 2 })
        val failure: ApiResult<Int> = ApiResult.Failure(ApiError(ApiErrorKind.NETWORK))
        assertEquals(failure, failure.map { it * 2 })
    }
}
