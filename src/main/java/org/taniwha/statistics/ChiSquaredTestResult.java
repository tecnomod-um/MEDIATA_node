package org.taniwha.statistics;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChiSquaredTestResult {
    private String category1;
    private String category2;
    private double pValue;

    public ChiSquaredTestResult(String category1, String category2, double pValue) {
        this.category1 = category1;
        this.category2 = category2;
        this.pValue = pValue;
    }
}
