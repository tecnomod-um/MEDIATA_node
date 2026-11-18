package org.taniwha.privacy.pooling;

import org.junit.jupiter.api.Test;
import org.taniwha.dto.DatasetElementDTO;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PrivatePoolingDomainFactoryTest {
    @Test void numericalGridsCoverArbitrarySignsConstantsAndLargeRanges() {
        for (var limits : List.of(List.of("integer", "min:-4", "max:3"),
                List.of("double", "min:0.25", "max:0.25"),
                List.of("double", "min:-999999999.5", "max:999999999.5"),
                List.of("integer", "min:1000000000", "max:1000000000"))) {
            var domain = PrivatePoolingDomainFactory.fromElement(new DatasetElementDTO("Reading", limits)).orElseThrow();
            domain.validate();
            assertThat(domain.boundaries()).hasSizeBetween(2, 129);
            assertThat(domain.indexOf(limits.get(1).substring(4))).isBetween(0, domain.size() - 2);
            assertThat(domain.indexOf(limits.get(2).substring(4))).isBetween(0, domain.size() - 2);
            assertThat(domain.indexOf("NULL")).isEqualTo(domain.size() - 1);
        }
    }

    @Test void datesUseEpochDaysAndBadMetadataCannotInventDomains() {
        var date = PrivatePoolingDomainFactory.fromElement(new DatasetElementDTO("When",
                List.of("date", "earliest:2026-01-01", "latest:2026-01-03"))).orElseThrow();
        assertThat(date.type()).isEqualTo("date");
        assertThat(date.values()).hasSize(3);
        assertThat(date.indexOf("not a date")).isEqualTo(date.size() - 1);
        for (var values : List.of(List.of("Natural Language"), List.of("integer", "min:9", "max:1"),
                List.of("double", "min:NaN", "max:2"), List.of("date", "earliest:bad", "latest:bad"))) {
            assertThat(PrivatePoolingDomainFactory.fromElement(new DatasetElementDTO("Value", values))).isEmpty();
        }
    }

    @Test void categoricalDomainsAreIndependentOfFeatureNames() {
        var domain = PrivatePoolingDomainFactory.fromElement(new DatasetElementDTO("Instrument state",
                List.of("West", "Other", "East"))).orElseThrow();
        assertThat(domain.categories()).containsExactly("East", "Other", "West");
        assertThat(domain.indexOf("Other")).isEqualTo(1);
        assertThat(domain.indexOf("Unlisted")).isEqualTo(3);
        assertThat(domain.indexOf("")).isEqualTo(4);
    }
}
