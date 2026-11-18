package org.taniwha.service;

import org.junit.jupiter.api.Test;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;

class ColumnAccumulatorTest {
    @Test void publishesGenericIntegerSpacingAndObservedMissingness() {
        var column = new ColumnAccumulator();
        for (String value : new String[]{"0","5","10","15","0","5"}) column.accept(value);
        var metadata = column.toElement("Instrument score");
        assertThat(metadata.getIntegerStep()).isEqualTo(5);
        assertThat(metadata.getMissingAllowed()).isFalse();
        column.accept("NULL");
        assertThat(column.toElement("Instrument score").getMissingAllowed()).isTrue();
    }

    @Test void preservesDiscreteSpacingForIntegerValuesWrittenWithDecimalPlaces() {
        var column = new ColumnAccumulator();
        for (String value : new String[]{"0.0","5.0","10.0","15.0","0.0","5.0"}) column.accept(value);
        assertThat(column.toElement("Reading").getIntegerStep()).isEqualTo(5);
        assertThat(column.toElementValues().get(0)).isEqualTo("double");
    }
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
