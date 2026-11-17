package com.app.recharge.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class ApiErrorKind { OFFLINE, TIMEOUT, NETWORK, UNAUTHORIZED, CLIENT, SERVER, UNKNOWN }

data class ApiError(
    val kind: ApiErrorKind,
    val httpStatus: Int? = null,
    val code: String? = null,
    val message: String? = null,
    val correlationId: String? = null,
) {
    /**
     * True when the request may have reached the server and had an effect (lost response, 5xx).
     * For payments this means "check the order status", never "try again blindly".
     */
    val outcomeUnknown: Boolean
        get() = kind == ApiErrorKind.NETWORK || kind == ApiErrorKind.TIMEOUT || kind == ApiErrorKind.SERVER
}

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val error: ApiError) : ApiResult<Nothing>
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(value))
    is ApiResult.Failure -> this
}

/** RFC 7807 body from the backend. Only stable fields are read. */
@Serializable
data class ProblemDto(
    val status: Int? = null,
    val detail: String? = null,
    val code: String? = null,
    val correlationId: String? = null,
)

private val problemJson = Json { ignoreUnknownKeys = true }

/** Turns a non-2xx answer into a typed error. A body that is not problem+json is simply ignored. */
fun httpError(status: Int, body: String?): ApiError {
    val problem = body?.takeIf { it.isNotBlank() }?.let {
        runCatching { problemJson.decodeFromString(ProblemDto.serializer(), it) }.getOrNull()
    }
    val kind = when {
        status == 401 -> ApiErrorKind.UNAUTHORIZED
        status >= 500 -> ApiErrorKind.SERVER
        status in 400..499 -> ApiErrorKind.CLIENT
        else -> ApiErrorKind.UNKNOWN
    }
    return ApiError(kind, status, problem?.code, problem?.detail, problem?.correlationId)
}
