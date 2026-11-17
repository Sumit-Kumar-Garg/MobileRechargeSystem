package com.app.recharge.feature.recharge.domain

sealed interface PhoneValidation {
    data class Valid(val e164: String) : PhoneValidation
    data class Invalid(val reason: Reason) : PhoneValidation
    enum class Reason { FORMAT, UNSUPPORTED }
}

/**
 * Client-side check for fast feedback; the server validates again and stays authoritative.
 * A leading 0 is read as a German national number (the launch market).
 */
object PhoneNumberValidator {
    private val e164 = Regex("^\\+[1-9]\\d{7,14}$")
    private val separators = Regex("[\\s\\-().]")
    private val supportedPrefixes = listOf("+49", "+48", "+43", "+31", "+420")

    fun validate(raw: String): PhoneValidation {
        val cleaned = raw.trim().replace(separators, "")
        val normalized = when {
            cleaned.startsWith("+") -> cleaned
            cleaned.startsWith("00") -> "+" + cleaned.drop(2)
            cleaned.startsWith("0") -> "+49" + cleaned.drop(1)
            else -> return PhoneValidation.Invalid(PhoneValidation.Reason.FORMAT)
        }
        if (!e164.matches(normalized)) return PhoneValidation.Invalid(PhoneValidation.Reason.FORMAT)
        if (supportedPrefixes.none { normalized.startsWith(it) }) {
            return PhoneValidation.Invalid(PhoneValidation.Reason.UNSUPPORTED)
        }
        return PhoneValidation.Valid(normalized)
    }
}
