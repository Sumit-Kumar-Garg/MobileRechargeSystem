package com.app.recharge.feature.recharge

import com.app.recharge.feature.recharge.ui.formatDate
import com.app.recharge.feature.recharge.ui.gigabytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.TimeZone

/** Small text helpers written in shared code because String.format and java.time do not exist on iOS. */
class FormattingTest {

    @Test fun wholeGigabytesHaveNoDecimals() {
        assertEquals("10", gigabytes(10_000))
        assertEquals("1", gigabytes(1_000))
    }

    @Test fun partsOfAGigabyteAreRoundedToOneDecimal() {
        assertEquals("1.5", gigabytes(1_500))
        assertEquals("0.5", gigabytes(500))
        assertEquals("2.3", gigabytes(2_250))            // rounds half up
        assertEquals("3", gigabytes(2_960))
    }

    @Test fun datesAreShownAsDayMonthYearInTheGivenZone() {
        assertEquals("05.12.2026", formatDate("2026-12-05T10:00:00Z", TimeZone.UTC))
        // 23:30 UTC is already the next day in Berlin
        assertEquals("06.12.2026", formatDate("2026-12-05T23:30:00Z", TimeZone.of("Europe/Berlin")))
    }

    @Test fun missingOrBrokenDatesBecomeEmptyTextNotACrash() {
        assertEquals("", formatDate(null, TimeZone.UTC))
        assertEquals("", formatDate("yesterday", TimeZone.UTC))
    }
}
