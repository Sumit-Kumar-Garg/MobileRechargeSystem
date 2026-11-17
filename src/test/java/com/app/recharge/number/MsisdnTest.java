package com.app.recharge.number;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.recharge.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MsisdnTest {

    @Test
    void normalizesSeparators() {
        assertThat(Msisdn.normalize("+49 (170) 123-4567")).isEqualTo("+491701234567");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "01701234567", "+0491701234567", "+49abc", "+4917", "+49170123456789012"})
    void rejectsInvalidNumbers(String raw) {
        assertThatThrownBy(() -> Msisdn.normalize(raw)).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> Msisdn.normalize(null)).isInstanceOf(BusinessException.class);
    }

    @Test
    void mapsCountryPrefixToMarket() {
        assertThat(Msisdn.market("+491701234567")).isEqualTo("DE");
        assertThat(Msisdn.market("+48501234567")).isEqualTo("PL");
        assertThat(Msisdn.market("+420601234567")).isEqualTo("CZ");
        assertThatThrownBy(() -> Msisdn.market("+14155550123")).isInstanceOf(BusinessException.class);
    }

    @Test
    void maskHidesTheMiddle() {
        assertThat(Msisdn.mask("+491701234567")).isEqualTo("+49 *** ***4567");
    }
}
