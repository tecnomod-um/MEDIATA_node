package org.taniwha.util;

import org.junit.jupiter.api.Test;

import java.text.ParseException;

import static org.assertj.core.api.Assertions.*;

class NumberUtilTest {

    @Test
    void parseDouble_standardDotDecimal() throws Exception {
        assertThat(NumberUtil.parseDouble("123.45")).isEqualTo(123.45);
        assertThat(NumberUtil.parseDouble("1000")).isEqualTo(1000.0);
        assertThat(NumberUtil.parseDouble("-12.34")).isEqualTo(-12.34);
    }

    @Test
    void parseDouble_commaDecimal() throws Exception {
        assertThat(NumberUtil.parseDouble("123,45")).isEqualTo(123.45);
        assertThat(NumberUtil.parseDouble("1.234,56")).isEqualTo(1234.56);
        assertThat(NumberUtil.parseDouble("12.345.678,90")).isEqualTo(12345678.90);
    }

    @Test
    void parseDouble_completelyInvalid_throwsParseException() {
        assertThatThrownBy(() -> NumberUtil.parseDouble("abc"))
                .isInstanceOf(ParseException.class);
    }

    @Test
    void parseDouble_malformedButPartialParse() throws Exception {
        assertThat(NumberUtil.parseDouble("1,2.3")).isEqualTo(1.2);
    }
}
