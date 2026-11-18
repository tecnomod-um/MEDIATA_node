package org.taniwha.privacy.pooling;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PublishedHistogramRestrictionTest {
    @Test void appliesPublishedSpacingWithoutDrawingNewNoiseOrSplittingBins() {
        var old = new PrivatePoolingDomain(List.of(-.5,.5,1.5,2.5,3.5,4.5), null,
                List.of(0.,1.,2.,3.,4.), "continuous");
        var saved = new PrivatePoolingHistogram(old, List.of(12L, 7L, -3L, 9L, 14L, -4L), 4.);
        var published = new PrivatePoolingDomain(List.of(-1.,1.,3.,5.), null, List.of(0.,2.,4.), "continuous", false, true);
        var result = PublishedHistogramRestriction.restrict(saved, published);
        assertThat(result.counts()).containsExactly(12L,-3L,14L,0L);
        assertThat(result.domain().values()).containsExactly(0.,2.,4.);
        assertThat(result.noiseVariance()).isEqualTo(saved.noiseVariance());
        assertThat(PublishedHistogramRestriction.restrict(result, published)).isEqualTo(result);
    }

    @Test void cannotInventPointResolutionFromReleasedIntervals() {
        var saved = new PrivatePoolingHistogram(new PrivatePoolingDomain(List.of(0.,10.,20.), null), List.of(15L,8L,-2L), 4.);
        var published = new PrivatePoolingDomain(List.of(-.5,4.5,14.5,20.5),null,List.of(0.,10.,20.),"continuous",false,true);
        var result = PublishedHistogramRestriction.restrict(saved,published);
        assertThat(result.domain().values()).isNull();
        assertThat(result.counts()).containsExactly(15L,8L,0L);
        assertThat(result.domain().boundaries()).isEqualTo(saved.domain().boundaries());
    }

    @Test void removesOnlyPubliclyImpossibleUnlistedCategories() {
        var saved = new PrivatePoolingHistogram(new PrivatePoolingDomain(null,List.of("East","West")),List.of(9L,7L,3L,-1L),4.);
        var complete = new PrivatePoolingDomain(null,List.of("West","East"),null,"categorical",false,false);
        assertThat(PublishedHistogramRestriction.restrict(saved,complete).counts()).containsExactly(7L,9L,0L,0L);
        var incomplete = new PrivatePoolingDomain(null,List.of("East"),null,"categorical",true,true);
        assertThat(PublishedHistogramRestriction.restrict(saved,incomplete)).isEqualTo(saved);
    }
}
