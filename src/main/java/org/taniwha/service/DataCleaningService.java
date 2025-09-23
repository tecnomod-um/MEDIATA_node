package org.taniwha.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.taniwha.util.DateUtil;
import org.taniwha.util.NumberUtil;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DataCleaningService {

    private static final Logger logger = LoggerFactory.getLogger(DataCleaningService.class);

    public List<Map<String, String>> removeDuplicates(List<Map<String, String>> records) {
        logger.debug("Removing duplicates from {} records", records.size());
        return records.stream().distinct().collect(Collectors.toList());
    }

    public List<Map<String, String>> removeEmptyRows(List<Map<String, String>> records) {
        logger.debug("Removing empty rows from {} records", records.size());
        return records.stream().filter(r -> r.values().stream().anyMatch(v -> v != null && !v.trim().isEmpty())).collect(Collectors.toList());
    }

    public List<Map<String, String>> standardizeDates(List<Map<String, String>> records, String outputFormat) {
        String fmt = Optional.ofNullable(outputFormat).filter(s -> !s.isBlank()).orElse("yyyy-MM-dd");
        fmt = fmt.replace('Y', 'y').replace('D', 'd');

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern(fmt);
        logger.debug("Standardizing dates to pattern {}", fmt);

        for (Map<String, String> row : records) {
            for (Map.Entry<String, String> e : row.entrySet()) {
                String raw = e.getValue();
                if (raw != null && !raw.isEmpty())
                    DateUtil.parseDate(raw).ifPresent(parsed -> e.setValue(parsed.format(dtf)));
            }
        }
        return records;
    }

    public boolean isEmptyRow(Map<String,String> row) {
        return row.values().stream()
                .allMatch(v->v==null||v.trim().isEmpty());
    }

    public String dedupeKey(Map<String,String> row) {
        // e.g. JSON or joined values
        return row.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e->e.getKey()+"="+e.getValue())
                .collect(Collectors.joining("|"));
    }

    public void standardizeDatesInPlace(Map<String,String> row, String outputPattern) {
        String fmt = Optional.ofNullable(outputPattern)
                .filter(s->!s.isBlank())
                .orElse("yyyy-MM-dd")
                .replace('Y','y').replace('D','d');
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern(fmt);
        for ( var e : row.entrySet() ) {
            String raw = e.getValue();
            if (raw!=null && !raw.isEmpty()) {
                DateUtil.parseDate(raw)
                        .ifPresent(ldt->e.setValue(ldt.format(dtf)));
            }
        }
    }

    public void standardizeNumericInPlace(Map<String,String> row,
                                          Set<String> numericColumns,
                                          String mode) {
        for (String col : numericColumns) {
            if (!row.containsKey(col)) continue;
            String raw = row.get(col);
            if (raw == null || raw.isBlank()) continue;

            try {
                double d = NumberUtil.parseDouble(raw);
                switch (mode) {
                    case "double" -> row.put(col, Double.toString(d));
                    case "int_round" -> row.put(col, Long.toString(Math.round(d)));
                    case "int_trunc" -> row.put(col, Long.toString((long) d));
                    default -> { /* ignore unknown */ }
                }
            } catch (Exception ignored) {
            }
        }
    }
}
