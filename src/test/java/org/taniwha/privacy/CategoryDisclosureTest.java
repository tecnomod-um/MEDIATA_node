package org.taniwha.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.taniwha.statistics.CategoricalFeatureStatistics;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class CategoryDisclosureTest {
    private CategoricalFeatureStatistics feature(Map<String, Integer> counts) {
        var entries = counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey())).toList();
        long total = counts.values().stream().mapToLong(Integer::longValue).sum();
        var first = entries.get(0);
        var second = entries.size() > 1 ? entries.get(1) : null;
        return new CategoricalFeatureStatistics("Group", total, 0, 0, counts.size(), first.getKey(), first.getValue(),
                100.0 * first.getValue() / total, second == null ? null : second.getKey(),
                second == null ? null : second.getValue(), second == null ? null : 100.0 * second.getValue() / total, counts);
    }

    @Test void commonModeRemainsButItsCountCannotRevealASingleRareRecordBySubtraction() {
        var feature = feature(Map.of("Common", 95, "Rare", 1));
        CategoryDisclosure.release(feature);
        assertThat(feature.getCardinality()).isEqualTo(2);
        assertThat(feature.getMode()).isEqualTo("Common");
        assertThat(feature.getModeFrequency()).isNull();
        assertThat(feature.getModeFrequencyPercentage()).isNull();
        assertThat(feature.getSecondMode()).isNull();
        assertThat(feature.getCategoryCounts()).isEmpty();
        assertThat(feature.getCategoryBins()).singleElement().satisfies(bin -> {
            assertThat(bin.grouped()).isTrue();
            assertThat(bin.count()).isEqualTo(96);
        });
    }

    @Test void complementarySuppressionKeepsUsefulCommonCountsWithoutRevealingTheRemainingSmallCell() {
        var feature = feature(Map.of("A", 80, "B", 15, "Rare", 1));
        CategoryDisclosure.release(feature);
        assertThat(feature.getMode()).isEqualTo("A");
        assertThat(feature.getModeFrequency()).isEqualTo(80);
        assertThat(feature.getSecondMode()).isEqualTo("B");
        assertThat(feature.getSecondModeFrequency()).isNull();
        assertThat(feature.getCategoryBins()).extracting(bin -> bin.count()).containsExactly(80L, 16L);
        assertThat(feature.getCategoryBins().stream().mapToLong(bin -> bin.count()).sum()).isEqualTo(feature.getCount());
    }

    @Test void highCardinalityLabelsDoNotEscapeThroughModesOrChartBins() throws Exception {
        Map<String, Integer> counts = new HashMap<>();
        for (int index = 0; index < 96; index++) counts.put("private-label-" + index, 1);
        var feature = feature(counts);
        CategoryDisclosure.release(feature);
        assertThat(feature.getCardinality()).isEqualTo(96);
        assertThat(feature.getMode()).isNull();
        assertThat(feature.getSecondMode()).isNull();
        assertThat(new ObjectMapper().writeValueAsString(feature)).doesNotContain("private-label-");
    }

    @Test void completeApprovedDistributionsRemainUnchanged() {
        var feature = feature(Map.of("A", 60, "B", 36));
        CategoryDisclosure.release(feature);
        assertThat(feature.getCategoryCounts()).containsEntry("A", 60).containsEntry("B", 36);
        assertThat(feature.getModeFrequency()).isEqualTo(60);
        assertThat(feature.getCategoryCountsStatus()).isEqualTo("AVAILABLE");
    }
}
