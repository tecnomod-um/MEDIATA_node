package org.taniwha.util;

import org.apache.commons.math3.util.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.taniwha.statistics.ChiSquaredTestResult;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class AggregateCalculatorTest {

    @Test
    void pairedSummariesUseCompleteCasesAndDoNotReleaseSparseJointFrequencies() {
        var data = Map.of("x", Arrays.asList(null, 2., 3., 4., 5., 6., 7., 8., 9., 10.),
                "y", Arrays.asList(2., null, 6., 8., 10., 12., 14., 16., 18., 20.));
        var pair = new AggregateCalculator(6).numericPairSummaries(data).stream()
                .filter(summary -> summary.featureX().equals("x") && summary.featureY().equals("y")).findFirst().orElseThrow();
        assertThat(pair.count()).isEqualTo(8);
        assertThat(pair.meanX()).isEqualTo(6.5);
        assertThat(pair.meanY()).isEqualTo(13);
        assertThat(pair.m2X()).isEqualTo(42);
        assertThat(pair.crossMoment()).isEqualTo(84);
        assertThat(pair.frequencies()).isEmpty();
    }

    @Test
    void releasesOnlyCompleteFrequencyTablesWhosePositiveCellsMeetDisclosureCount() {
        var x = new ArrayList<Double>(); var y = new ArrayList<Double>();
        for (int i = 0; i < 6; i++) { x.add(1.); y.add(10.); }
        for (int i = 0; i < 6; i++) { x.add(2.); y.add(20.); }
        var summaries = new AggregateCalculator(6).numericPairSummaries(Map.of("x", x, "y", y));
        var pair = summaries.stream().filter(summary -> !summary.featureX().equals(summary.featureY())).findFirst().orElseThrow();
        assertThat(pair.frequencies()).hasSize(2).allSatisfy(cell -> assertThat(cell.count()).isGreaterThanOrEqualTo(6));
        assertThat(AggregateDisclosure.numericFrequencies(x)).hasSize(2);
        x.add(3.); y.add(30.);
        pair = new AggregateCalculator(6).numericPairSummaries(Map.of("x", x, "y", y)).stream()
                .filter(summary -> !summary.featureX().equals(summary.featureY())).findFirst().orElseThrow();
        assertThat(pair.count()).isEqualTo(13);
        assertThat(pair.frequencies()).isEmpty();
        assertThat(AggregateDisclosure.numericFrequencies(x)).isEmpty();
    }

    @Test
    void contingencySummariesPreservePairOrientationAndSuppressSmallCells() {
        var categories = Map.of("a", Map.of("A", 30, "B", 30), "b", Map.of("U", 30, "V", 30));
        var cells = Map.of(Pair.create("U", "A"), 24, Pair.create("V", "A"), 6,
                Pair.create("U", "B"), 6, Pair.create("V", "B"), 24);
        var calc = new AggregateCalculator(6);
        var summary = calc.categoricalPairSummaries(categories, Map.of(Pair.create("b", "a"), cells)).get(0);
        assertThat(summary.featureX()).isEqualTo("a");
        assertThat(summary.count()).isEqualTo(60);
        assertThat(summary.frequencies()).anySatisfy(cell -> {
            assertThat(cell.x()).isEqualTo("A"); assertThat(cell.y()).isEqualTo("U"); assertThat(cell.count()).isEqualTo(24);
        });
        var sparse = Map.of(Pair.create("A", "U"), 29, Pair.create("A", "V"), 1,
                Pair.create("B", "U"), 1, Pair.create("B", "V"), 29);
        assertThat(calc.categoricalPairSummaries(categories, Map.of(Pair.create("a", "b"), sparse))).isEmpty();
        assertThat(calc.calculateChiSquaredTest(categories, Map.of(Pair.create("a", "b"), sparse)).get(0).getPValue()).isNaN();
    }

    @Test
    void relationshipsSkipInsufficientPairedRecordsAndNonFiniteValues() {
        var small = Map.of("x", Arrays.asList(1.0, null), "y", List.of(2.0, 4.0));
        assertThat(new AggregateCalculator().calculateCovariances(small)).isEmpty();
        assertThat(new AggregateCalculator().calculatePearsonCorrelations(small)).isEmpty();
        var nonFinite = Map.of("x", List.of(1.0, Double.POSITIVE_INFINITY, 3.0), "y", List.of(2.0, 99.0, 6.0));
        assertThat(new AggregateCalculator().calculatePearsonCorrelations(nonFinite).get("x").get("y"))
                .isCloseTo(1.0, within(1e-12));
        assertThat(new AggregateCalculator(3).calculatePearsonCorrelations(nonFinite)).isEmpty();
    }

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
