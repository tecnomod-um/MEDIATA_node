package org.taniwha.privacy;

import org.junit.jupiter.api.Test;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class MomentDisclosureTest {
    private Map<Object, BitSet> values(double... rows) {
        Map<Object, BitSet> result = new LinkedHashMap<>();
        for (int index = 0; index < rows.length; index++) {
            result.computeIfAbsent(rows[index], ignored -> new BitSet()).set(index);
        }
        return result;
    }

    @Test void firstAndSecondMomentsDetermineUpToThreeUnivariateCounts() {
        assertThat(MomentDisclosure.univariateCells(values(1, 1, 2, 3))).hasSize(3);
        assertThat(MomentDisclosure.univariateCells(values(1, 2, 3, 4))).isEmpty();
        assertThat(MomentDisclosure.univariateCells(null)).isEmpty();
    }

    @Test void crossProductsDetermineBinaryJointCountsAndSparseNonlinearDomains() {
        assertThat(MomentDisclosure.pairedCells(values(0, 0, 1, 1), values(0, 1, 0, 1))).hasSize(4);
        assertThat(MomentDisclosure.pairedCells(values(0, 1, 2, 3), values(0, 1, 4, 9))).hasSize(4);
    }

    @Test void redundantMomentsDoNotInventAnIdentifiableDistribution() {
        assertThat(MomentDisclosure.pairedCells(values(0, 1, 2, 3), values(0, 1, 2, 3))).isEmpty();
        assertThat(MomentDisclosure.pairedCells(values(0, 1, 2, 3, 4, 5, 6), values(0, 1, 4, 9, 16, 25, 36))).isEmpty();
        assertThat(MomentDisclosure.pairedCells(values(0, 0, 0, 1, 1, 1, 2), values(0, 1, 2, 0, 1, 2, 0))).isEmpty();
    }

    @Test void normalisationHandlesConstantsAndAnOverflowingRange() {
        assertThat(MomentDisclosure.pairedCells(values(7, 7, 7), values(0, 1, 2))).hasSize(3);
        assertThat(MomentDisclosure.pairedCells(values(-Double.MAX_VALUE, Double.MAX_VALUE), values(0, 1))).hasSize(2);
    }

    @Test void onlyActualCompletePairsDefineTheConstraints() {
        assertThat(MomentDisclosure.pairedCells(null, values(0, 1))).isEmpty();
        assertThat(MomentDisclosure.pairedCells(values(0, 1), null)).isEmpty();
        assertThat(MomentDisclosure.pairedCells(values(), values())).isEmpty();
        Map<Object, BitSet> disjoint = new LinkedHashMap<>();
        BitSet later = new BitSet(); later.set(8);
        disjoint.put(2.0, later);
        assertThat(MomentDisclosure.pairedCells(values(0, 1), disjoint)).isEmpty();
    }
}
