package org.taniwha.privacy;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.SingularValueDecomposition;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

/** Finds discrete counts already determined by the released first/second moments. */
final class MomentDisclosure {
    private MomentDisclosure() {}

    // Count, sum and sum-of-squares determine up to three distinct value counts.
    static List<BitSet> univariateCells(Map<Object, BitSet> values) {
        return values != null && values.size() <= 3 ? new ArrayList<>(values.values()) : List.of();
    }

    // The six released constraints are count, X, Y, X², Y² and XY. When these
    // determine every observed joint count, those cells must pass the history gate
    // even if the explicit frequency table is absent from the response.
    static List<BitSet> pairedCells(Map<Object, BitSet> xs, Map<Object, BitSet> ys) {
        if (xs == null || ys == null || xs.size() > 6 || ys.size() > 6) return List.of();
        List<BitSet> cells = new ArrayList<>();
        List<double[]> coordinates = new ArrayList<>();
        for (var x : xs.entrySet()) {
            for (var y : ys.entrySet()) {
                BitSet cell = (BitSet) x.getValue().clone();
                cell.and(y.getValue());
                if (cell.isEmpty()) continue;
                cells.add(cell);
                if (cells.size() > 6) return List.of();
                coordinates.add(new double[]{(Double) x.getKey(), (Double) y.getKey()});
            }
        }
        if (cells.isEmpty()) return List.of();
        double[][] constraints = new double[6][cells.size()];
        for (int column = 0; column < cells.size(); column++) {
            double x = normalise(coordinates.get(column)[0], xs);
            double y = normalise(coordinates.get(column)[1], ys);
            constraints[0][column] = 1;
            constraints[1][column] = x;
            constraints[2][column] = y;
            constraints[3][column] = x * x;
            constraints[4][column] = y * y;
            constraints[5][column] = x * y;
        }
        int rank = new SingularValueDecomposition(new Array2DRowRealMatrix(constraints)).getRank();
        return rank == cells.size() ? cells : List.of();
    }

    private static double normalise(double value, Map<Object, BitSet> values) {
        double min = values.keySet().stream().mapToDouble(key -> (Double) key).min().orElse(0);
        double max = values.keySet().stream().mapToDouble(key -> (Double) key).max().orElse(0);
        if (min == max) return 0;
        if (Double.isFinite(max - min)) return (value - min) / (max - min);
        double scale = Math.max(Math.abs(min), Math.abs(max));
        return (value / scale - min / scale) / (max / scale - min / scale);
    }
}
