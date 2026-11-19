package org.taniwha.privacy.pooling;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PrivateSiteNoisePlanTest {
    @Test void widePublicDomainsReceiveMorePrecisionWithoutExceedingTheTotalAllowance() {
        var wide = new PrivatePoolingDomain(IntStream.rangeClosed(0,100).mapToObj(i -> (double)i).toList(), null);
        var narrow = new PrivatePoolingDomain(null,List.of("North","South"));
        var plan = PrivateSiteNoisePlan.create(Map.of("Measurement",wide,"Label",narrow),new SecureRandom(),80);
        double charged = plan.cohort().epsilon()+plan.samplers().values().stream().mapToDouble(PrivateHistogramNoise::epsilon).sum();
        assertThat(charged).isEqualTo(plan.rowEpsilon()).isLessThanOrEqualTo(80);
        assertThat(plan.samplers().get("Measurement").variance()).isLessThanOrEqualTo(plan.samplers().get("Label").variance());
        assertThat(plan.cohort().variance()).isPositive();
    }

    @Test void precisionIsNotCappedAtTheOldTenBitNoiseFloor() {
        var plan = PrivateSiteNoisePlan.create(Map.of("Reading",new PrivatePoolingDomain(null,List.of("One","Two"))),new SecureRandom(),40);
        assertThat(plan.samplers().get("Reading").epsilon()).isGreaterThan(Math.log(1024));
        assertThat(plan.samplers().get("Reading").variance()).isLessThan(.00001);
    }

    @Test void smallAllowancesRemainAccountedAndUnsupportedBudgetsAreRejected() {
        var fields = Map.of("Reading",new PrivatePoolingDomain(List.of(0.,1.,2.),null));
        var plan = PrivateSiteNoisePlan.create(fields,new SecureRandom(),Math.log(2));
        assertThat(plan.rowEpsilon()).isPositive().isLessThanOrEqualTo(Math.log(2));
        assertThat(plan.cohort().variance()).isFinite();
        assertThatThrownBy(()->PrivateSiteNoisePlan.create(fields,new SecureRandom(),.00001)).isInstanceOf(PrivatePoolingUnavailableException.class);
    }
}
