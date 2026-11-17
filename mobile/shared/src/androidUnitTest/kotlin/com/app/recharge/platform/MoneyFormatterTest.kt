package com.app.recharge.platform

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Android's money format (java.text). The iOS format uses NSNumberFormatter and is checked on a simulator. */
class MoneyFormatterTest {
    @Test fun formatsEuroFromMinorUnits() = assertEquals("€15.00", AndroidMoney.format(1500, "EUR", Locale.US))
    @Test fun usesLocaleSeparators() = assertTrue(AndroidMoney.format(1550, "EUR", Locale.GERMANY).contains("15,50"))
    @Test fun respectsCurrenciesWithoutMinorUnits() = assertTrue(AndroidMoney.format(1500, "JPY", Locale.US).contains("1,500"))
    @Test fun formatsZloty() = assertTrue(AndroidMoney.format(3500, "PLN", Locale.forLanguageTag("pl-PL")).contains("35,00"))
}
