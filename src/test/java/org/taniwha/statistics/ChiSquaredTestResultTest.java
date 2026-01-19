package org.taniwha.statistics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChiSquaredTestResultTest {

    @Test
    void constructor_shouldInitializeAllFields() {
        ChiSquaredTestResult result = new ChiSquaredTestResult("cat1", "cat2", 0.05);

        assertThat(result.getCategory1()).isEqualTo("cat1");
        assertThat(result.getCategory2()).isEqualTo("cat2");
        assertThat(result.getPValue()).isEqualTo(0.05);
    }

    @Test
    void setters_shouldUpdateFields() {
        ChiSquaredTestResult result = new ChiSquaredTestResult("initial1", "initial2", 0.1);

        result.setCategory1("updated1");
        result.setCategory2("updated2");
        result.setPValue(0.001);

        assertThat(result.getCategory1()).isEqualTo("updated1");
        assertThat(result.getCategory2()).isEqualTo("updated2");
        assertThat(result.getPValue()).isEqualTo(0.001);
    }

    @Test
    void pValue_edgeCases_shouldHandleExtremeValues() {
        ChiSquaredTestResult verySignificant = new ChiSquaredTestResult("a", "b", 0.0001);
        ChiSquaredTestResult notSignificant = new ChiSquaredTestResult("c", "d", 0.99);

        assertThat(verySignificant.getPValue()).isLessThan(0.001);
        assertThat(notSignificant.getPValue()).isGreaterThan(0.05);
    }

    @Test
    void categories_shouldHandleEmptyAndNullStrings() {
        ChiSquaredTestResult emptyCategories = new ChiSquaredTestResult("", "", 0.5);
        ChiSquaredTestResult nullCategories = new ChiSquaredTestResult(null, null, 0.5);

        assertThat(emptyCategories.getCategory1()).isEmpty();
        assertThat(emptyCategories.getCategory2()).isEmpty();
        assertThat(nullCategories.getCategory1()).isNull();
        assertThat(nullCategories.getCategory2()).isNull();
    }

    @Test
    void pValue_precisionTest_shouldMaintainAccuracy() {
        double veryPrecise = 0.04999999999;
        ChiSquaredTestResult result = new ChiSquaredTestResult("x", "y", veryPrecise);

        assertThat(result.getPValue()).isEqualTo(veryPrecise);
    }

    @Test
    void categories_specialCharacters_shouldBePreserved() {
        ChiSquaredTestResult result = new ChiSquaredTestResult(
                "Category with spaces & special chars!",
                "中文/日本語",
                0.05
        );

        assertThat(result.getCategory1()).isEqualTo("Category with spaces & special chars!");
        assertThat(result.getCategory2()).isEqualTo("中文/日本語");
    }
}
