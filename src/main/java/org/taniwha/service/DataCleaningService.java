package org.taniwha.service;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.taniwha.dto.DataCleaningOptionsDTO;
import org.taniwha.model.FileCategory;
import org.taniwha.util.DateUtil;
import org.taniwha.util.NumberUtil;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DataCleaningService {

    private static final Logger logger = LoggerFactory.getLogger(DataCleaningService.class);

    private final FileService fileService;
    private final DataProcessingService dataProcessingService;

    public DataCleaningService(FileService fileService, DataProcessingService dataProcessingService) {
        this.fileService = fileService;
        this.dataProcessingService = dataProcessingService;
    }

    public void cleanInPlace(FileCategory category, String name, DataCleaningOptionsDTO opts) {
        Objects.requireNonNull(category, "category is required");
        String fileName = Objects.toString(name, "").trim();
        if (fileName.isEmpty()) throw new IllegalArgumentException("name is required");

        Path file = fileService.resolveExistingFilePath(category, fileName);

        // no options => no-op (or throw; but no-op is safer for UI)
        if (opts == null || !anyEnabled(opts)) {
            logger.debug("No cleaning options enabled; skipping {}", file);
            return;
        }

        String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);

        // Read all records using existing processing service (handles delimiter + xlsx)
        final List<Map<String, String>> records;
        try {
            records = dataProcessingService.extractDataFromPath(file);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file for cleaning", e);
        }

        List<Map<String, String>> cleaned = records;

        if (opts.isRemoveEmptyRows()) cleaned = removeEmptyRows(cleaned);
        if (opts.isRemoveDuplicates()) cleaned = removeDuplicates(cleaned);

        if (opts.isStandardizeDates()) {
            cleaned = standardizeDates(cleaned, opts.getDateOutputFormat());
        }

        if (opts.isStandardizeNumeric()) {
            Set<String> numericCols = extractNumericColumns(opts);
            String mode = Objects.toString(opts.getNumericMode(), "").trim();
            if (!numericCols.isEmpty() && !mode.isEmpty()) {
                for (Map<String, String> row : cleaned) {
                    standardizeNumericInPlace(row, numericCols, mode);
                }
            }
        }

        if (lower.endsWith(".csv")) {
            writeCsvAtomically(file, cleaned);
            return;
        }

        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            Path out = file.resolveSibling(stripExt(file.getFileName().toString()) + "_cleaned.csv");
            writeCsvAtomically(out, cleaned);
            return;
        }

        throw new IllegalArgumentException("Unsupported file type for cleaning: " + file.getFileName());
    }

    private boolean anyEnabled(DataCleaningOptionsDTO o) {
        return o.isRemoveDuplicates()
                || o.isRemoveEmptyRows()
                || o.isStandardizeDates()
                || o.isStandardizeNumeric();
    }

    private Set<String> extractNumericColumns(DataCleaningOptionsDTO opts) {
        List<String> ids = opts.getNumericColumns();
        if (ids == null || ids.isEmpty()) return Collections.emptySet();

        return ids.stream()
                .filter(Objects::nonNull)
                .map(s -> {
                    int idx = s.lastIndexOf(":::");
                    return idx >= 0 ? s.substring(idx + 3) : s;
                })
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return (i >= 0) ? name.substring(0, i) : name;
    }

    private void writeCsvAtomically(Path target, List<Map<String, String>> rows) {
        if (rows == null || rows.isEmpty()) {
            logger.debug("Cleaning produced 0 rows; leaving file unchanged: {}", target);
            return;
        }

        List<String> headers = new ArrayList<>(rows.get(0).keySet());

        Path dir = target.getParent();
        try {
            if (dir != null) Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create output directory", e);
        }

        Path tmp = (dir == null)
                ? Paths.get(target.getFileName().toString() + ".tmp")
                : dir.resolve(target.getFileName().toString() + ".tmp");

        CSVFormat fmt = CSVFormat.newFormat(';')
                .withHeader(headers.toArray(new String[0]))
                .withRecordSeparator(System.lineSeparator());

        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
             CSVPrinter p = new CSVPrinter(w, fmt)) {

            for (Map<String, String> row : rows) {
                List<String> rec = headers.stream()
                        .map(h -> Objects.toString(row.get(h), ""))
                        .toList();
                p.printRecord(rec);
            }

        } catch (IOException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw new RuntimeException("Failed to write cleaned CSV", e);
        }

        try {
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw new RuntimeException("Failed to replace original file with cleaned version", e);
        }
    }

    public List<Map<String, String>> removeDuplicates(List<Map<String, String>> records) {
        logger.debug("Removing duplicates from {} records", records.size());
        return records.stream().distinct().collect(Collectors.toList());
    }

    public List<Map<String, String>> removeEmptyRows(List<Map<String, String>> records) {
        logger.debug("Removing empty rows from {} records", records.size());
        return records.stream()
                .filter(r -> r.values().stream().anyMatch(v -> v != null && !v.trim().isEmpty()))
                .collect(Collectors.toList());
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

    public boolean isEmptyRow(Map<String, String> row) {
        return row.values().stream().allMatch(v -> v == null || v.trim().isEmpty());
    }

    public String dedupeKey(Map<String, String> row) {
        return row.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("|"));
    }

    public void standardizeDatesInPlace(Map<String, String> row, String outputPattern) {
        String fmt = Optional.ofNullable(outputPattern)
                .filter(s -> !s.isBlank())
                .orElse("yyyy-MM-dd")
                .replace('Y', 'y').replace('D', 'd');
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern(fmt);

        for (var e : row.entrySet()) {
            String raw = e.getValue();
            if (raw != null && !raw.isEmpty()) {
                DateUtil.parseDate(raw).ifPresent(ldt -> e.setValue(ldt.format(dtf)));
            }
        }
    }

    public void standardizeNumericInPlace(Map<String, String> row, Set<String> numericColumns, String mode) {
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
                    default -> logger.debug("Unknown numeric standardization mode: {}", mode);
                }
            } catch (Exception e) {
                logger.debug("Cannot parse numeric value for column {}: {}", col, raw);
            }
        }
    }
}
