package com.app.recharge.feature.recharge

import com.app.recharge.feature.recharge.domain.OrderStatus
import com.app.recharge.feature.recharge.domain.PhoneNumberValidator
import com.app.recharge.feature.recharge.domain.PhoneValidation
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class PhoneNumberValidatorTest {

    private fun valid(raw: String) = (PhoneNumberValidator.validate(raw) as PhoneValidation.Valid).e164
    private fun invalid(raw: String) = (PhoneNumberValidator.validate(raw) as PhoneValidation.Invalid).reason

    @Test fun acceptsInternationalFormatWithSeparators() = assertEquals("+491701234567", valid("+49 (170) 123-4567"))
    @Test fun readsLeadingZeroAsGermanNationalNumber() = assertEquals("+491701234567", valid("0170 1234567"))
    @Test fun readsDoubleZeroAsPlus() = assertEquals("+48501234567", valid("0048 501 234 567"))
    @Test fun trimsWhitespace() = assertEquals("+491701234567", valid("  +491701234567  "))
    @Test fun acceptsOtherSupportedMarkets() {
        assertEquals("+43664123456", valid("+43 664 123456"))
        assertEquals("+420601234567", valid("+420 601 234 567"))
    }

    @Test fun rejectsGarbage() {
        listOf("", "abc", "1234567", "+49abc", "+49 1", "++491701234567").forEach {
            assertEquals(PhoneValidation.Reason.FORMAT, invalid(it), it)
        }
    }

    @Test fun rejectsTooLongNumbers() = assertEquals(PhoneValidation.Reason.FORMAT, invalid("+4917012345678901234"))

    @Test fun rejectsUnsupportedCountriesWithADistinctReason() =
        assertEquals(PhoneValidation.Reason.UNSUPPORTED, invalid("+1 415 555 0123"))
}

class OrderStatusTest {
    @Test fun settledStatesStopPolling() {
        listOf(OrderStatus.COMPLETED, OrderStatus.REFUNDED, OrderStatus.EXPIRED, OrderStatus.PAYMENT_FAILED)
            .forEach { assertTrue(it.settled, it.name) }
    }

    @Test fun inFlightStatesKeepPolling() {
        listOf(OrderStatus.CREATED, OrderStatus.PAYMENT_PENDING, OrderStatus.PAID, OrderStatus.PROVISIONING,
            OrderStatus.PROVISIONING_FAILED, OrderStatus.REFUND_INITIATED, OrderStatus.UNKNOWN)
            .forEach { assertFalse(it.settled, it.name) }
    }

    @Test fun onlyCreatedAndFailedOrdersAcceptPayment() {
        assertTrue(OrderStatus.CREATED.payable)
        assertTrue(OrderStatus.PAYMENT_FAILED.payable)
        OrderStatus.entries.filter { it != OrderStatus.CREATED && it != OrderStatus.PAYMENT_FAILED }
            .forEach { assertFalse(it.payable, it.name) }
    }

    @Test fun unknownApiValuesMapToUnknown() {
        assertEquals(OrderStatus.COMPLETED, OrderStatus.fromApi("COMPLETED"))
        assertEquals(OrderStatus.UNKNOWN, OrderStatus.fromApi("SOMETHING_NEW"))
    }
}
