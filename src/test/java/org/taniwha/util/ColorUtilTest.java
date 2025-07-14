package org.taniwha.util;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.*;

class ColorUtilTest {

    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-f]{6}$");

    @Test
    void generateRandomColor_alwaysValidHex() {
        for (int i = 0; i < 20; i++) {
            String color = ColorUtil.generateRandomColor();
            assertThat(color)
                    .as("Generated color [%s] should match #RRGGBB", color)
                    .matches(HEX_COLOR);
        }
    }

    @Test
    void hslToRgb_pureRed() throws Exception {
        String red = invokeHslToRgb(0f, 1f, 0.5f);
        assertThat(red).isEqualTo("#ff0000");
    }

    @Test
    void hslToRgb_pureGreen() throws Exception {
        String green = invokeHslToRgb(1f/3f, 1f, 0.5f);
        assertThat(green).isEqualTo("#00ff00");
    }

    @Test
    void hslToRgb_pureBlue() throws Exception {
        String blue = invokeHslToRgb(2f/3f, 1f, 0.5f);
        assertThat(blue).isEqualTo("#0000ff");
    }

    @Test
    void hslToRgb_grayscaleWhenSaturationZero() throws Exception {
        String gray = invokeHslToRgb(0.7f, 0f, 0.2f);
        assertThat(gray).isEqualTo("#333333");
    }

    private String invokeHslToRgb(float h, float s, float l) throws Exception {
        Method m = ColorUtil.class.getDeclaredMethod("hslToRgb", float.class, float.class, float.class);
        m.setAccessible(true);
        return (String)m.invoke(null, h, s, l);
    }
}
