package org.taniwha.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrincipalServiceTest {

    private PrincipalService principalService;

    @BeforeEach
    void setUp() {
        principalService = new PrincipalService();
    }

    @Test
    void getKeytab_beforeSet_isNull() {
        assertThat(principalService.getKeytab()).isNull();
    }

    @Test
    void setKeytab_withValidBase64_decodesCorrectly() {
        String original = "some-binary-data";
        String b64 = Base64.getEncoder().encodeToString(original.getBytes(StandardCharsets.UTF_8));
        principalService.setKeytab(b64);

        byte[] decoded = principalService.getKeytab();
        assertThat(decoded).isNotNull()
                .containsExactly(original.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void setKeytab_withEmptyString_resultsInEmptyArray() {
        principalService.setKeytab("");
        byte[] decoded = principalService.getKeytab();
        assertThat(decoded).isNotNull()
                .isEmpty();
    }

    @Test
    void setKeytab_withNonBase64_throwsIllegalArgumentException() {
        String bad = "not-base64!!!";
        assertThatThrownBy(() -> principalService.setKeytab(bad))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
