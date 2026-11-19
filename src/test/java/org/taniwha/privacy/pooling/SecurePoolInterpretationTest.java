package org.taniwha.privacy.pooling;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SecurePoolInterpretationTest {
    @Test void categoricalScoresRetainTheirCountsAndCanReturnToNumericalOrder() {
        var domain = new PrivatePoolingDomain(null,List.of("10","0","5"),null,"categorical",true,false);
        var input = new PrivatePoolingHistogram(domain,List.of(30L,40L,-2L,0L,3L),4.,List.of(4.));
        var numerical = ProtectedHistogramInterpretation.as(input,"continuous");
        assertThat(numerical.domain().values()).containsExactly(0.,5.,10.);
        assertThat(numerical.counts()).containsExactly(40L,-2L,30L,3L);
        assertThat(numerical.noiseComponents()).isEqualTo(input.noiseComponents());
        var categorical = ProtectedHistogramInterpretation.as(numerical,"categorical");
        assertThat(categorical.domain().categories()).containsExactly("0","5","10");
        assertThat(categorical.counts()).containsExactly(40L,-2L,30L,0L,3L);
    }

    @Test void binnedIntervalsDatesAndUnknownCategoriesAreNotInventedAsRawScores() {
        var intervals = new PrivatePoolingHistogram(new PrivatePoolingDomain(List.of(0.,5.,10.),null),List.of(20L,30L,1L),4.);
        assertThatThrownBy(()->ProtectedHistogramInterpretation.as(intervals,"categorical")).hasMessageContaining("binned intervals");
        var date = new PrivatePoolingHistogram(new PrivatePoolingDomain(List.of(.5,1.5),null,List.of(1.),"date"),List.of(30L,0L),4.);
        assertThat(ProtectedHistogramInterpretation.as(date,"date")).isSameAs(date);
        assertThatThrownBy(()->ProtectedHistogramInterpretation.as(date,"continuous")).isInstanceOf(IllegalArgumentException.class);
        var other = new PrivatePoolingHistogram(new PrivatePoolingDomain(null,List.of("1","2")),List.of(10L,20L,5L,1L),4.);
        assertThatThrownBy(()->ProtectedHistogramInterpretation.as(other,"continuous")).isInstanceOf(IllegalArgumentException.class);
        var codes = new PrivatePoolingHistogram(new PrivatePoolingDomain(null,List.of("01","1"),null,"categorical",false,false),List.of(10L,20L,0L,0L),4.);
        assertThatThrownBy(()->ProtectedHistogramInterpretation.as(codes,"continuous")).hasMessageContaining("distinct");
    }
}
