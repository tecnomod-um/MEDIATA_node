package org.taniwha.privacy.pooling;

import org.junit.jupiter.api.Test;
import java.security.SecureRandom;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class SecurePoolDomainsTest {
    @Test void coarsensDenseMeasurementsBeforeNoiseButKeepsCompactScoreValues() {
        var dense=new org.taniwha.dto.DatasetElementDTO("Measurement",List.of("integer","min:0","max:100"));
        dense.setDataType("integer");dense.setIntegerStep(1L);
        assertThat(PrivatePoolingDomainFactory.fromElement(dense,32).orElseThrow().values()).isNull();
        assertThat(PrivatePoolingDomainFactory.fromElement(dense).orElseThrow().values()).hasSize(101);
        dense.setIntegerStep(5L);
        assertThat(PrivatePoolingDomainFactory.fromElement(dense,32).orElseThrow().values()).hasSize(21).contains(0.,5.,100.);
    }
    @Test void alignsDiscreteSupportsAndRetainsUnknownCategoryAndMissingSlots() {
        var a=new PrivatePoolingDomain(List.of(-.5,.5,1.5),null,List.of(0.,1.),"continuous",false,true);
        var b=new PrivatePoolingDomain(List.of(.5,1.5,2.5),null,List.of(1.,2.),"continuous",false,true);
        assertThat(SecurePoolDomains.common(List.of(a,b)).values()).containsExactly(0.,1.,2.);
        var ca=new PrivatePoolingDomain(null,List.of("North"),null,"categorical",true,true);
        var cb=new PrivatePoolingDomain(null,List.of("South"),null,"categorical",false,false);
        var target=SecurePoolDomains.common(List.of(ca,cb));
        assertThat(target.categories()).containsExactly("North","South");
        assertThat(target.missingAllowed()).isTrue();assertThat(target.unlistedCategoriesAllowed()).isTrue();
    }

    @Test void coarsensIntervalsWithoutSplittingThemAndReportsTheRealComponentCount() {
        var a=new PrivatePoolingDomain(List.of(0.,5.,10.),null,null,"continuous",false,true);
        var b=new PrivatePoolingDomain(List.of(0.,10.),null,null,"continuous",false,true);
        var target=SecurePoolDomains.common(List.of(a,b));
        assertThat(target.boundaries()).containsExactly(0.,10.);
        var input=new PrivatePoolingHistogram(a,List.of(7L,12L,0L),4.);
        assertThat(SecurePoolDomains.rebin(input,target,new SecureRandom())).containsExactly(19L,0L);
        assertThat(SecurePoolDomains.offer(input,target).noiseComponents()).containsExactly(4.,4.);
        assertThatThrownBy(()->SecurePoolDomains.rebin(new PrivatePoolingHistogram(b,List.of(19L,0L),4.),a,new SecureRandom()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void datesRemainDatesAndMixedInterpretationsCannotBeMerged() {
        var date=new PrivatePoolingDomain(List.of(20000.,20001.),null,List.of(20000.),"date",false,true);
        assertThat(SecurePoolDomains.common(List.of(date,date)).type()).isEqualTo("date");
        var number=new PrivatePoolingDomain(date.boundaries(),null,date.values(),"continuous",false,true);
        assertThatThrownBy(()->SecurePoolDomains.common(List.of(date,number))).isInstanceOf(IllegalArgumentException.class);
    }
}
