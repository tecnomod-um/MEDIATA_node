package org.taniwha.util;

import java.security.SecureRandom;

public class ColorUtil {

    private static final SecureRandom RANDOM = new SecureRandom();

    private ColorUtil() {
    }

    public static String generateRandomColor() {
        float hue = RANDOM.nextFloat();
        float saturation = 0.7f + RANDOM.nextFloat() * 0.3f;
        float lightness = 0.4f + RANDOM.nextFloat() * 0.4f;
        return hslToRgb(hue, saturation, lightness);
    }

    private static String hslToRgb(float hue, float saturation, float lightness) {
        float r, g, b;
        if (saturation == 0)
            r = g = b = lightness;
        else {
            float q = lightness < 0.5 ? lightness * (1 + saturation) : lightness + saturation - lightness * saturation;
            float p = 2 * lightness - q;
            r = hueToRgb(p, q, hue + 1.0f / 3.0f);
            g = hueToRgb(p, q, hue);
            b = hueToRgb(p, q, hue - 1.0f / 3.0f);
        }
        return String.format("#%02x%02x%02x", Math.round(r * 255), Math.round(g * 255), Math.round(b * 255));
    }

    private static float hueToRgb(float p, float q, float t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1.0f / 6.0f) return p + (q - p) * 6 * t;
        if (t < 1.0f / 2.0f) return q;
        if (t < 2.0f / 3.0f) return p + (q - p) * (2.0f / 3.0f - t) * 6;
        return p;
    }
}
