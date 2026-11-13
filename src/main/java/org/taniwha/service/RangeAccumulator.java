package org.taniwha.service;

import org.taniwha.service.MappingSpecAdapter.SourceColumnRef;

import java.util.Objects;

final class RangeAccumulator {
    SourceColumnRef ref;
    Object min = MappingSpecAdapter.UNSET;
    Object max = MappingSpecAdapter.UNSET;
    boolean valid = true;

    void accept(SourceColumnRef nextRef, String op, Object literal) {
        if (!valid) return;

        if (ref == null) {
            ref = nextRef;
        } else if (!Objects.equals(ref.sourceId(), nextRef.sourceId())
                || !Objects.equals(ref.column(), nextRef.column())) {
            valid = false;
            return;
        }

        switch (op) {
            case ">=", ">" -> min = literal;
            case "<=", "<" -> max = literal;
            default -> valid = false;
        }
    }
}
