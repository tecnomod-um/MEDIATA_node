package org.taniwha.privacy.pooling;

import org.taniwha.dto.DatasetElementDTO;
import org.taniwha.util.DateUtil;
import org.taniwha.util.NumberUtil;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/** Converts the node's disclosure-approved, unfiltered element metadata into fixed domains. */
final class PrivatePoolingDomainFactory {
    private static final int MAX_POINTS = 128;
    private static final int TARGET_BINS = 32;

    private PrivatePoolingDomainFactory() {}

    static Optional<PrivatePoolingDomain> fromElement(DatasetElementDTO element) {
        List<String> values = element.getValues();
        String declared = element.getDataType();
        if (values == null || values.isEmpty() || "text".equals(declared)
                || (declared == null && values.contains("Natural Language"))) return Optional.empty();
        try {
            PrivatePoolingDomain domain;
            if (!"categorical".equals(declared) && values.size() == 3 && (values.get(0).equals("integer") || values.get(0).equals("double"))) {
                domain = numerical(number(values.get(1), "min:"), number(values.get(2), "max:"),
                        values.get(0).equals("integer") || element.getIntegerStep() != null, "continuous", element.getIntegerStep());
            } else if (!"categorical".equals(declared) && values.size() == 3 && values.get(0).equals("date")) {
                double min = date(values.get(1), "earliest:");
                double max = date(values.get(2), "latest:");
                domain = numerical(min, max, true, "date", 1L);
            } else {
                domain = new PrivatePoolingDomain(null, values.stream().distinct().sorted().toList());
            }
            domain = new PrivatePoolingDomain(domain.boundaries(), domain.categories(), domain.values(), domain.type(),
                    element.getMissingAllowed(), element.getUnlistedCategoriesAllowed());
            domain.validate();
            return Optional.of(domain);
        } catch (IllegalArgumentException | ParseException | java.util.NoSuchElementException invalid) {
            return Optional.empty();
        }
    }

    private static double number(String value, String prefix) throws ParseException {
        if (!value.startsWith(prefix)) throw new IllegalArgumentException("Invalid numerical metadata");
        return NumberUtil.parseDouble(value.substring(prefix.length()));
    }

    private static double date(String value, String prefix) {
        if (!value.startsWith(prefix)) throw new IllegalArgumentException("Invalid date metadata");
        return DateUtil.parseDate(value.substring(prefix.length())).orElseThrow().toLocalDate().toEpochDay();
    }

    private static PrivatePoolingDomain numerical(double min, double max, boolean integer, String type, Long publishedStep) {
        if (!Double.isFinite(min) || !Double.isFinite(max) || min > max || Math.abs(min) > 1e9 || Math.abs(max) > 1e9) {
            throw new IllegalArgumentException("Invalid numerical limits");
        }
        long step = publishedStep == null ? 1 : publishedStep;
        if (step <= 0 || (integer && (max - min) % step != 0)) throw new IllegalArgumentException("Invalid integer step");
        if (min == max || (integer && min == Math.rint(min) && max == Math.rint(max) && (max - min) / step < MAX_POINTS)) {
            List<Double> points = IntStream.rangeClosed(0, (int) ((max - min) / step)).mapToObj(i -> min + i * step).toList();
            var edges = new ArrayList<Double>();
            edges.add(Math.max(-1e9, min - step / 2.0));
            for (int i = 1; i < points.size(); i++) edges.add((points.get(i - 1) + points.get(i)) / 2);
            edges.add(Math.min(1e9, max + step / 2.0));
            return new PrivatePoolingDomain(edges, null, points, type);
        }
        double width = Math.pow(2, Math.ceil(Math.log(Math.max(max - min, 1) / TARGET_BINS) / Math.log(2)));
        double lower = Math.floor(min / width) * width;
        double upper = Math.ceil(max / width) * width;
        if (lower == upper) upper += width;
        int bins = (int) Math.round((upper - lower) / width);
        List<Double> edges = IntStream.rangeClosed(0, bins).mapToObj(i -> Math.max(-1e9, Math.min(1e9, lower + i * width))).toList();
        return new PrivatePoolingDomain(edges, null, null, type);
    }
}
