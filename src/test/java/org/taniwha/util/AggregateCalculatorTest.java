package org.taniwha.util;

import org.apache.commons.math3.util.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.taniwha.statistics.ChiSquaredTestResult;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class AggregateCalculatorTest {

    private AggregateCalculator calc;

    @BeforeEach
    void setUp() {
        calc = new AggregateCalculator();
    }

    @Test
    void calculateCovariances_perfectlyLinearlyRelated_returnsSampleCovariance() {
        Map<String, List<Double>> data = Map.of(
                "f1", List.of(1.0, 2.0, 3.0),
                "f2", List.of(2.0, 4.0, 6.0)
        );

        var covs = calc.calculateCovariances(data);
        assertThat(covs).containsKey("f1");
        double cov = covs.get("f1").get("f2");
        assertThat(cov).isCloseTo(2.0, within(1e-12));
        assertThat(covs.get("f2").get("f1")).isCloseTo(2.0, within(1e-12));
    }

    @Test
    void calculateCovariances_skipsNulls() {
        Map<String, List<Double>> data = Map.of(
                "f1", Arrays.asList(1.0, null, 3.0),
                "f2", Arrays.asList(2.0, 4.0, 6.0)
        );
        var covs = calc.calculateCovariances(data);
        assertThat(covs.get("f1").get("f2")).isCloseTo(4.0, within(1e-12));
    }

    @Test
    void calculatePearsonCorrelations_perfectCorrelation_returnsOne() {
        Map<String, List<Double>> data = Map.of(
                "a", List.of(10.0, 20.0, 30.0, 40.0),
                "b", List.of(15.0, 30.0, 45.0, 60.0)
        );
        var pcs = calc.calculatePearsonCorrelations(data);
        assertThat(pcs.get("a").get("b")).isCloseTo(1.0, within(1e-12));
        assertThat(pcs.get("b").get("a")).isCloseTo(1.0, within(1e-12));
    }

    @Test
    void calculateSpearmanCorrelations_perfectCorrelation_returnsOne() {
        Map<String, List<Double>> data = Map.of(
                "x", List.of(5.0, 1.0, 4.0, 2.0, 3.0),
                "y", List.of(50.0,10.0,40.0,20.0,30.0)
        );
        var scs = calc.calculateSpearmanCorrelations(data);
        assertThat(scs.get("x").get("y")).isCloseTo(1.0, within(1e-12));
        assertThat(scs.get("y").get("x")).isCloseTo(1.0, within(1e-12));
    }

    @Test
    void calculateChiSquared_uniform_returnsPValueOne() {
        Map<String, Map<String, Integer>> catData = new LinkedHashMap<>();
        catData.put("f1", Map.of("A",2,"B",2));
        catData.put("f2", Map.of("X",2,"Y",2));

        Pair<String,String> key = Pair.create("f1","f2");
        Map<Pair<String,String>,Integer> combos = new HashMap<>();
        for (var v1 : List.of("A","B"))
            for (var v2 : List.of("X","Y"))
                combos.put(Pair.create(v1,v2), 1);

        var singleMap = Map.<Pair<String,String>,Map<Pair<String,String>,Integer>>of(key, combos);
        List<ChiSquaredTestResult> results = calc.calculateChiSquaredTest(catData, singleMap);

        assertThat(results).hasSize(1);
        ChiSquaredTestResult r = results.get(0);
        assertThat(r.getCategory1()).isEqualTo("f1");
        assertThat(r.getCategory2()).isEqualTo("f2");
        assertThat(r.getPValue()).isCloseTo(1.0, within(1e-12));
    }

    @Test
    void calculateChiSquared_missingCombination_returnsNaN() {
        Map<String, Map<String, Integer>> catData = Map.of(
                "f1", Map.of("A",1,"B",1),
                "f2", Map.of("X",1,"Y",1)
        );
        List<ChiSquaredTestResult> results = calc.calculateChiSquaredTest(catData, Collections.emptyMap());
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getPValue()).isNaN();
    }
}