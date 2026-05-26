package org.taniwha.util;

import org.junit.jupiter.api.Test;

import java.text.ParseException;

import static org.assertj.core.api.Assertions.*;

class NumberUtilExtendedTest {

    @Test
    void parseDouble_withValidDoubleStrings_shouldParse() throws ParseException {
        assertThat(NumberUtil.parseDouble("123.45")).isCloseTo(123.45, within(0.001));
        assertThat(NumberUtil.parseDouble("123")).isCloseTo(123.0, within(0.001));
        assertThat(NumberUtil.parseDouble("-123.45")).isCloseTo(-123.45, within(0.001));
        assertThat(NumberUtil.parseDouble("0.0")).isCloseTo(0.0, within(0.001));
    }

    @Test
    void parseDouble_withCommaDecimalSeparator_shouldParse() throws ParseException {
        // NumberUtil supports German locale format where comma is decimal separator
        // and dot is thousand separator (e.g., 1.234,56 = 1234.56)
        assertThat(NumberUtil.parseDouble("123,45")).isCloseTo(123.45, within(0.001));
        assertThat(NumberUtil.parseDouble("1.234,56")).isCloseTo(1234.56, within(0.001));
    }

    @Test
    void parseDouble_withInvalidAlphabeticStrings_shouldThrowParseException() {
        assertThatThrownBy(() -> NumberUtil.parseDouble("abc"))
                .isInstanceOf(ParseException.class);
    }

    @Test
    void parseDouble_withScientificNotation_shouldParse() throws ParseException {
        assertThat(NumberUtil.parseDouble("1e10")).isCloseTo(1e10, within(0.001));
        assertThat(NumberUtil.parseDouble("1.5E-3")).isCloseTo(0.0015, within(0.00001));
    }

    @Test
    void parseDouble_isThreadSafe() throws InterruptedException {
        Thread[] threads = new Thread[10];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    try {
                        NumberUtil.parseDouble("123,45");
                    } catch (ParseException e) {
                        fail("parseDouble raised an exception in concurrent parsing", e);
                    }
                }
            });
            threads[i].start();
        }
        
        for (Thread thread : threads) {
            thread.join();
        }
    }
}
