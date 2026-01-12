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
        // German locale uses comma as decimal separator
        assertThat(NumberUtil.parseDouble("123,45")).isCloseTo(123.45, within(0.001));
        // With dot as decimal, comma is treated as thousand separator in German locale
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
        // Test that the synchronized COMMA_FORMAT doesn't cause issues
        Thread[] threads = new Thread[10];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    try {
                        NumberUtil.parseDouble("123,45");
                    } catch (ParseException e) {
                        fail("Should not throw exception");
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
