package org.taniwha.service;

import org.junit.jupiter.api.Test;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;

class ColumnAccumulatorTest {
    @Test void longNumericalColumnsRetainLimitsAfterTheCategoryDictionaryIsBounded() {
        var column = new ColumnAccumulator();
        IntStream.range(0, 10000).forEach(i -> column.accept(Integer.toString(i)));
        assertThat(column.canRelease()).isTrue();
        assertThat(column.toElementValues()).containsExactly("integer", "min:0", "max:9999");
    }

    @Test void highCardinalityTextDoesNotRetainAnUnboundedLabelDictionary() {
        var column = new ColumnAccumulator();
        IntStream.range(0, 10000).forEach(i -> column.accept("label-" + i));
        assertThat(column.toElementValues()).containsExactly("Natural Language");
    }
}
