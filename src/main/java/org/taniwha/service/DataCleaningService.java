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
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

        // Row-level cleaning operations
        if (opts.isRemoveEmptyRows()) cleaned = removeEmptyRows(cleaned);
        if (opts.isRemoveDuplicates()) cleaned = removeDuplicates(cleaned);
        if (opts.isRemoveRowsWithPattern()) {
            cleaned = removeRowsWithPattern(cleaned, opts.getRowFilterColumn(), opts.getRowFilterPattern());
        }
        if (opts.isKeepOnlyNumericRows()) {
            cleaned = keepOnlyNumericRows(cleaned, extractColumnList(opts.getNumericValidationColumns()));
        }

        // Basic text operations
        if (opts.isTrimWhitespace()) cleaned = trimWhitespace(cleaned);
        if (opts.isRemoveExtraSpaces()) cleaned = removeExtraSpaces(cleaned);
        if (opts.isRemoveLineBreaks()) cleaned = removeLineBreaks(cleaned);
        if (opts.isNormalizeText()) cleaned = normalizeText(cleaned);
        
        // Case standardization
        if (opts.isStandardizeCase()) {
            String caseMode = Objects.toString(opts.getCaseMode(), "").trim();
            if (!caseMode.isEmpty()) {
                cleaned = standardizeCase(cleaned, caseMode);
            }
        }
        
        // Character cleaning
        if (opts.isRemoveSpecialCharacters()) {
            cleaned = removeSpecialCharacters(cleaned, opts.getSpecialCharColumnsPattern());
        }
        if (opts.isRemovePunctuation()) cleaned = removePunctuation(cleaned);
        if (opts.isRemoveNonPrintableChars()) cleaned = removeNonPrintableChars(cleaned);
        
        // Encoding operations
        if (opts.isFixEncoding()) cleaned = fixEncoding(cleaned);
        if (opts.isNormalizeUnicode()) {
            String norm = Objects.toString(opts.getUnicodeNormalization(), "NFC");
            cleaned = normalizeUnicode(cleaned, norm);
        }

        // Date operations
        if (opts.isStandardizeDates()) {
            cleaned = standardizeDates(cleaned, opts.getDateOutputFormat());
        }
        if (opts.isExtractDateComponents()) {
            cleaned = extractDateComponents(cleaned);
        }

        // Numeric operations
        if (opts.isStandardizeNumeric()) {
            Set<String> numericCols = extractNumericColumns(opts);
            String mode = Objects.toString(opts.getNumericMode(), "").trim();
            if (!numericCols.isEmpty() && !mode.isEmpty()) {
                for (Map<String, String> row : cleaned) {
                    standardizeNumericInPlace(row, numericCols, mode);
                }
            }
        }
        if (opts.isRemoveLeadingZeros()) cleaned = removeLeadingZeros(cleaned);
        if (opts.isRoundDecimals()) {
            int places = opts.getDecimalPlaces();
            cleaned = roundDecimals(cleaned, places);
        }

        // String manipulation
        if (opts.isReplaceValues() && opts.getReplacementMap() != null) {
            cleaned = replaceValues(cleaned, opts.getReplacementMap());
        }
        if (opts.isStripPrefix() && opts.getPrefixToStrip() != null) {
            cleaned = stripPrefix(cleaned, opts.getPrefixToStrip());
        }
        if (opts.isStripSuffix() && opts.getSuffixToStrip() != null) {
            cleaned = stripSuffix(cleaned, opts.getSuffixToStrip());
        }
        if (opts.isPadValues()) {
            cleaned = padValues(cleaned, opts.getPadDirection(), opts.getPadLength(), 
                              Objects.toString(opts.getPadCharacter(), " "));
        }

        // Missing value handling
        if (opts.isFillMissingValues()) {
            String strategy = Objects.toString(opts.getFillStrategy(), "").trim();
            List<String> fillCols = opts.getFillColumns();
            if (!strategy.isEmpty()) {
                cleaned = fillMissingValues(cleaned, strategy, opts.getFillConstantValue(), fillCols);
            }
        }

        // Email and URL operations
        if (opts.isExtractEmailDomain()) cleaned = extractEmailDomain(cleaned);
        if (opts.isValidateEmails()) cleaned = validateEmails(cleaned);
        if (opts.isExtractURLComponents()) cleaned = extractURLComponents(cleaned);
        if (opts.isNormalizeURLs()) cleaned = normalizeURLs(cleaned);

        // Phone number standardization
        if (opts.isStandardizePhoneNumbers()) {
            cleaned = standardizePhoneNumbers(cleaned, opts.getPhoneFormat(), opts.getDefaultCountryCode());
        }

        // Column operations
        if (opts.isSplitColumn() && opts.getColumnToSplit() != null) {
            cleaned = splitColumn(cleaned, opts.getColumnToSplit(), opts.getSplitDelimiter(), 
                                opts.getNewColumnNames());
        }
        if (opts.isMergeColumns() && opts.getColumnsToMerge() != null) {
            cleaned = mergeColumns(cleaned, opts.getColumnsToMerge(), opts.getMergeDelimiter(), 
                                 opts.getMergedColumnName());
        }

        // Data type conversion
        if (opts.isConvertDataTypes() && opts.getTypeConversionMap() != null) {
            cleaned = convertDataTypes(cleaned, opts.getTypeConversionMap());
        }

        // Statistical transformations
        if (opts.isNormalizeData() && opts.getNormalizeColumns() != null) {
            cleaned = normalizeData(cleaned, extractColumnList(opts.getNormalizeColumns()));
        }
        if (opts.isStandardizeData() && opts.getStandardizeColumns() != null) {
            cleaned = standardizeDataZScore(cleaned, extractColumnList(opts.getStandardizeColumns()));
        }
        if (opts.isBinData() && opts.getBinColumn() != null) {
            cleaned = binData(cleaned, opts.getBinColumn(), opts.getBinEdges(), opts.getBinLabels());
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
        return o.isRemoveDuplicates() || o.isRemoveEmptyRows() || o.isStandardizeDates() 
                || o.isStandardizeNumeric() || o.isTrimWhitespace() || o.isStandardizeCase()
                || o.isRemoveSpecialCharacters() || o.isFillMissingValues() || o.isNormalizeText()
                || o.isRemoveLeadingZeros() || o.isRemovePunctuation() || o.isRemoveExtraSpaces()
                || o.isRemoveLineBreaks() || o.isReplaceValues() || o.isStripPrefix()
                || o.isStripSuffix() || o.isPadValues() || o.isConvertDataTypes()
                || o.isExtractEmailDomain() || o.isValidateEmails() || o.isExtractURLComponents()
                || o.isNormalizeURLs() || o.isStandardizePhoneNumbers() || o.isFixEncoding()
                || o.isRemoveNonPrintableChars() || o.isNormalizeUnicode() || o.isSplitColumn()
                || o.isMergeColumns() || o.isRemoveRowsWithPattern() || o.isKeepOnlyNumericRows()
                || o.isNormalizeData() || o.isStandardizeData() || o.isBinData()
                || o.isExtractDateComponents() || o.isRoundDecimals();
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

    private Set<String> extractColumnList(List<String> columns) {
        if (columns == null || columns.isEmpty()) return Collections.emptySet();
        return columns.stream()
                .filter(Objects::nonNull)
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
            try { 
                Files.deleteIfExists(tmp); 
            } catch (IOException cleanupEx) {
                logger.debug("Failed to delete temp file during cleanup: {}", tmp, cleanupEx);
            }
            throw new RuntimeException("Failed to write cleaned CSV", e);
        }

        try {
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try { 
                Files.deleteIfExists(tmp); 
            } catch (IOException cleanupEx) {
                logger.debug("Failed to delete temp file during cleanup: {}", tmp, cleanupEx);
            }
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

    /**
     * Trims leading and trailing whitespace from all values in all rows.
     */
    public List<Map<String, String>> trimWhitespace(List<Map<String, String>> records) {
        logger.debug("Trimming whitespace from {} records", records.size());
        for (Map<String, String> row : records) {
            for (Map.Entry<String, String> entry : row.entrySet()) {
                String value = entry.getValue();
                if (value != null) {
                    entry.setValue(value.trim());
                }
            }
        }
        return records;
    }

    /**
     * Removes extra spaces (multiple consecutive spaces become single space).
     */
    public List<Map<String, String>> removeExtraSpaces(List<Map<String, String>> records) {
        logger.debug("Removing extra spaces from {} records", records.size());
        for (Map<String, String> row : records) {
            for (Map.Entry<String, String> entry : row.entrySet()) {
                String value = entry.getValue();
                if (value != null && !value.isEmpty()) {
                    entry.setValue(value.replaceAll("\\s+", " ").trim());
                }
            }
        }
        return records;
    }

    /**
     * Removes line breaks and replaces them with spaces.
     */
    public List<Map<String, String>> removeLineBreaks(List<Map<String, String>> records) {
        logger.debug("Removing line breaks from {} records", records.size());
        for (Map<String, String> row : records) {
            for (Map.Entry<String, String> entry : row.entrySet()) {
                String value = entry.getValue();
                if (value != null && !value.isEmpty()) {
                    entry.setValue(value.replaceAll("[\\r\\n]+", " ").trim());
                }
            }
        }
        return records;
    }

    /**
     * Standardizes text case according to the specified mode: upper, lower, title, or sentence.
     */
    public List<Map<String, String>> standardizeCase(List<Map<String, String>> records, String caseMode) {
        logger.debug("Standardizing case to {} for {} records", caseMode, records.size());
        for (Map<String, String> row : records) {
            for (Map.Entry<String, String> entry : row.entrySet()) {
                String value = entry.getValue();
                if (value != null && !value.isEmpty()) {
                    entry.setValue(switch (caseMode.toLowerCase()) {
                        case "upper" -> value.toUpperCase(Locale.ROOT);
                        case "lower" -> value.toLowerCase(Locale.ROOT);
                        case "title" -> toTitleCase(value);
                        case "sentence" -> toSentenceCase(value);
                        default -> value;
                    });
                }
            }
        }
        return records;
    }

    private String toTitleCase(String input) {
        if (input == null || input.isEmpty()) return input;
        String[] words = input.split("\\s+");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                if (result.length() > 0) result.append(" ");
                result.append(Character.toTitleCase(word.charAt(0)));
                if (word.length() > 1) {
                    result.append(word.substring(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        return result.toString();
    }

    private String toSentenceCase(String input) {
        if (input == null || input.isEmpty()) return input;
        String lower = input.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + (lower.length() > 1 ? lower.substring(1) : "");
    }

    /**
     * Removes special characters from text values, keeping only alphanumeric and basic punctuation.
     */
    public List<Map<String, String>> removeSpecialCharacters(List<Map<String, String>> records, String pattern) {
        logger.debug("Removing special characters from {} records", records.size());
        String regex = "[^a-zA-Z0-9\\s.,\\-_]";
        
        for (Map<String, String> row : records) {
            for (Map.Entry<String, String> entry : row.entrySet()) {
                String value = entry.getValue();
                if (value != null && !value.isEmpty()) {
                    entry.setValue(value.replaceAll(regex, ""));
                }
            }
        }
        return records;
    }

    /**
     * Removes all punctuation characters.
     */
    public List<Map<String, String>> removePunctuation(List<Map<String, String>> records) {
        logger.debug("Removing punctuation from {} records", records.size());
        String regex = "[\\p{Punct}]";
        
        for (Map<String, String> row : records) {
            for (Map.Entry<String, String> entry : row.entrySet()) {
                String value = entry.getValue();
                if (value != null && !value.isEmpty()) {
                    entry.setValue(value.replaceAll(regex, ""));
                }
            }
        }
        return records;
    }

    /**
     * Removes non-printable characters.
     */
    public List<Map<String, String>> removeNonPrintableChars(List<Map<String, String>> records) {
        logger.debug("Removing non-printable characters from {} records", records.size());
        String regex = "[\\p{C}]";
        
        for (Map<String, String> row : records) {
            for (Map.Entry<String, String> entry : row.entrySet()) {
                String value = entry.getValue();
                if (value != null && !value.isEmpty()) {
                    entry.setValue(value.replaceAll(regex, ""));
                }
            }
        }
        return records;
    }

    /**
     * Normalizes text by removing extra whitespace and standardizing line breaks.
     */
    public List<Map<String, String>> normalizeText(List<Map<String, String>> records) {
        logger.debug("Normalizing text for {} records", records.size());
        for (Map<String, String> row : records) {
            for (Map.Entry<String, String> entry : row.entrySet()) {
                String value = entry.getValue();
                if (value != null && !value.isEmpty()) {
                    String normalized = value.replaceAll("\\s+", " ").trim();
                    entry.setValue(normalized);
                }
            }
        }
        return records;
    }

    /**
     * Fixes encoding issues by attempting to detect and correct common problems.
     */
    public List<Map<String, String>> fixEncoding(List<Map<String, String>> records) {
        logger.debug("Fixing encoding issues for {} records", records.size());
        // This is a simplified encoding fix - in practice you'd use charset detection
        for (Map<String, String> row : records) {
            for (Map.Entry<String, String> entry : row.entrySet()) {
                String value = entry.getValue();
                if (value != null && !value.isEmpty()) {
                    // Replace common mojibake patterns
                    value = value.replace("Ã©", "é")
                               .replace("Ã¨", "è")
                               .replace("Ã¡", "á")
                               .replace("Ã ", "à");
                    entry.setValue(value);
                }
            }
        }
        return records;
    }

    /**
     * Normalizes Unicode strings (NFC, NFD, NFKC, NFKD).
     */
    public List<Map<String, String>> normalizeUnicode(List<Map<String, String>> records, String normalizationForm) {
        logger.debug("Normalizing Unicode to {} for {} records", normalizationForm, records.size());
        Normalizer.Form form = switch (normalizationForm.toUpperCase()) {
            case "NFD" -> Normalizer.Form.NFD;
            case "NFKC" -> Normalizer.Form.NFKC;
            case "NFKD" -> Normalizer.Form.NFKD;
            default -> Normalizer.Form.NFC;
        };
        
        for (Map<String, String> row : records) {
            for (Map.Entry<String, String> entry : row.entrySet()) {
                String value = entry.getValue();
                if (value != null && !value.isEmpty()) {
                    entry.setValue(Normalizer.normalize(value, form));
                }
            }
        }
        return records;
    }

    /**
     * Removes leading zeros from numeric strings.
     */
    public List<Map<String, String>> removeLeadingZeros(List<Map<String, String>> records) {
        logger.debug("Removing leading zeros from {} records", records.size());
        for (Map<String, String> row : records) {
            for (Map.Entry<String, String> entry : row.entrySet()) {
                String value = entry.getValue();
                if (value != null && value.matches("^0+\\d+.*")) {
                    entry.setValue(value.replaceFirst("^0+", ""));
                }
            }
        }
        return records;
    }

    /**
     * Rounds decimal numbers to specified decimal places.
     */
    public List<Map<String, String>> roundDecimals(List<Map<String, String>> records, int decimalPlaces) {
        logger.debug("Rounding decimals to {} places for {} records", decimalPlaces, records.size());
        double factor = Math.pow(10, decimalPlaces);
        
        for (Map<String, String> row : records) {
            for (Map.Entry<String, String> entry : row.entrySet()) {
                String value = entry.getValue();
                if (value != null && !value.isEmpty()) {
                    try {
                        double num = NumberUtil.parseDouble(value);
                        double rounded = Math.round(num * factor) / factor;
                        entry.setValue(String.valueOf(rounded));
                    } catch (Exception ignored) {
                        // Not a number, leave as is
                    }
                }
            }
        }
        return records;
    }

    /**
     * Replaces values based on a replacement map.
     */
    public List<Map<String, String>> replaceValues(List<Map<String, String>> records, Map<String, String> replacementMap) {
        logger.debug("Replacing values for {} records", records.size());
        for (Map<String, String> row : records) {
            for (Map.Entry<String, String> entry : row.entrySet()) {
                String value = entry.getValue();
                if (value != null && replacementMap.containsKey(value)) {
                    entry.setValue(replacementMap.get(value));
                }
            }
        }
        return records;
    }

    /**
     * Strips a prefix from all values.
     */
    public List<Map<String, String>> stripPrefix(List<Map<String, String>> records, String prefix) {
        logger.debug("Stripping prefix '{}' from {} records", prefix, records.size());
        for (Map<String, String> row : records) {
            for (Map.Entry<String, String> entry : row.entrySet()) {
                String value = entry.getValue();
                if (value != null && value.startsWith(prefix)) {
                    entry.setValue(value.substring(prefix.length()));
                }
            }
        }
        return records;
    }

    /**
     * Strips a suffix from all values.
     */
    public List<Map<String, String>> stripSuffix(List<Map<String, String>> records, String suffix) {
        logger.debug("Stripping suffix '{}' from {} records", suffix, records.size());
        for (Map<String, String> row : records) {
            for (Map.Entry<String, String> entry : row.entrySet()) {
                String value = entry.getValue();
                if (value != null && value.endsWith(suffix)) {
                    entry.setValue(value.substring(0, value.length() - suffix.length()));
                }
            }
        }
        return records;
    }

    /**
     * Pads values to a specified length.
     */
    public List<Map<String, String>> padValues(List<Map<String, String>> records, String direction, 
                                                int length, String padChar) {
        logger.debug("Padding values to length {} ({}) for {} records", length, direction, records.size());
        String pad = padChar.isEmpty() ? " " : String.valueOf(padChar.charAt(0));
        
        for (Map<String, String> row : records) {
            for (Map.Entry<String, String> entry : row.entrySet()) {
                String value = entry.getValue();
                if (value != null && value.length() < length) {
                    int needed = length - value.length();
                    String padding = pad.repeat(needed);
                    entry.setValue("left".equalsIgnoreCase(direction) ? padding + value : value + padding);
                }
            }
        }
        return records;
    }

    /**
     * Extracts date components (year, month, day) into separate columns.
     */
    public List<Map<String, String>> extractDateComponents(List<Map<String, String>> records) {
        logger.debug("Extracting date components for {} records", records.size());
        
        for (Map<String, String> row : records) {
            Map<String, String> newColumns = new HashMap<>();
            for (Map.Entry<String, String> entry : row.entrySet()) {
                String value = entry.getValue();
                if (value != null && !value.isEmpty()) {
                    DateUtil.parseDate(value).ifPresent(ldt -> {
                        String colName = entry.getKey();
                        newColumns.put(colName + "_year", String.valueOf(ldt.getYear()));
                        newColumns.put(colName + "_month", String.valueOf(ldt.getMonthValue()));
                        newColumns.put(colName + "_day", String.valueOf(ldt.getDayOfMonth()));
                    });
                }
            }
            row.putAll(newColumns);
        }
        return records;
    }

    /**
     * Fills missing values using various strategies with optional column filtering.
     */
    public List<Map<String, String>> fillMissingValues(List<Map<String, String>> records, String strategy, 
                                                        String constantValue, List<String> targetColumns) {
        if (records.isEmpty()) return records;
        
        logger.debug("Filling missing values with strategy '{}' for {} records", strategy, records.size());
        Set<String> columns = targetColumns != null && !targetColumns.isEmpty() 
                ? extractColumnList(targetColumns)
                : new LinkedHashSet<>(records.get(0).keySet());
        
        for (String column : columns) {
            switch (strategy.toLowerCase()) {
                case "constant" -> fillWithConstant(records, column, constantValue != null ? constantValue : "");
                case "mean" -> fillWithMean(records, column);
                case "median" -> fillWithMedian(records, column);
                case "mode" -> fillWithMode(records, column);
                case "forward" -> fillForward(records, column);
                case "backward" -> fillBackward(records, column);
                case "interpolate" -> fillInterpolate(records, column);
                default -> logger.debug("Unknown fill strategy: {}", strategy);
            }
        }
        
        return records;
    }

    private void fillWithConstant(List<Map<String, String>> records, String column, String value) {
        for (Map<String, String> row : records) {
            if (isNullOrEmpty(row.get(column))) {
                row.put(column, value);
            }
        }
    }

    private void fillWithMean(List<Map<String, String>> records, String column) {
        List<Double> values = new ArrayList<>();
        for (Map<String, String> row : records) {
            String val = row.get(column);
            if (!isNullOrEmpty(val)) {
                try {
                    values.add(NumberUtil.parseDouble(val));
                } catch (Exception ignored) {
                    return;
                }
            }
        }
        
        if (values.isEmpty()) return;
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        String meanStr = String.valueOf(mean);
        
        for (Map<String, String> row : records) {
            if (isNullOrEmpty(row.get(column))) {
                row.put(column, meanStr);
            }
        }
    }

    private void fillWithMedian(List<Map<String, String>> records, String column) {
        List<Double> values = new ArrayList<>();
        for (Map<String, String> row : records) {
            String val = row.get(column);
            if (!isNullOrEmpty(val)) {
                try {
                    values.add(NumberUtil.parseDouble(val));
                } catch (Exception ignored) {
                    return;
                }
            }
        }
        
        if (values.isEmpty()) return;
        Collections.sort(values);
        double median = values.size() % 2 == 0
                ? (values.get(values.size() / 2 - 1) + values.get(values.size() / 2)) / 2.0
                : values.get(values.size() / 2);
        String medianStr = String.valueOf(median);
        
        for (Map<String, String> row : records) {
            if (isNullOrEmpty(row.get(column))) {
                row.put(column, medianStr);
            }
        }
    }

    private void fillWithMode(List<Map<String, String>> records, String column) {
        Map<String, Integer> frequency = new HashMap<>();
        for (Map<String, String> row : records) {
            String val = row.get(column);
            if (!isNullOrEmpty(val)) {
                frequency.put(val, frequency.getOrDefault(val, 0) + 1);
            }
        }
        
        if (frequency.isEmpty()) return;
        String mode = frequency.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
        
        for (Map<String, String> row : records) {
            if (isNullOrEmpty(row.get(column))) {
                row.put(column, mode);
            }
        }
    }

    private void fillForward(List<Map<String, String>> records, String column) {
        String lastValue = null;
        for (Map<String, String> row : records) {
            String val = row.get(column);
            if (isNullOrEmpty(val)) {
                if (lastValue != null) {
                    row.put(column, lastValue);
                }
            } else {
                lastValue = val;
            }
        }
    }

    private void fillBackward(List<Map<String, String>> records, String column) {
        String nextValue = null;
        for (int i = records.size() - 1; i >= 0; i--) {
            Map<String, String> row = records.get(i);
            String val = row.get(column);
            if (isNullOrEmpty(val)) {
                if (nextValue != null) {
                    row.put(column, nextValue);
                }
            } else {
                nextValue = val;
            }
        }
    }

    private void fillInterpolate(List<Map<String, String>> records, String column) {
        // Linear interpolation for numeric values
        for (int i = 0; i < records.size(); i++) {
            if (isNullOrEmpty(records.get(i).get(column))) {
                // Find previous and next non-null values
                Double prev = null, next = null;
                int prevIdx = -1, nextIdx = -1;
                
                for (int j = i - 1; j >= 0; j--) {
                    try {
                        prev = NumberUtil.parseDouble(records.get(j).get(column));
                        prevIdx = j;
                        break;
                    } catch (Exception ignored) {}
                }
                
                for (int j = i + 1; j < records.size(); j++) {
                    try {
                        next = NumberUtil.parseDouble(records.get(j).get(column));
                        nextIdx = j;
                        break;
                    } catch (Exception ignored) {}
                }
                
                if (prev != null && next != null) {
                    double interpolated = prev + (next - prev) * (i - prevIdx) / (nextIdx - prevIdx);
                    records.get(i).put(column, String.valueOf(interpolated));
                }
            }
        }
    }

    /**
     * Extracts email domain from email addresses.
     */
    public List<Map<String, String>> extractEmailDomain(List<Map<String, String>> records) {
        logger.debug("Extracting email domains for {} records", records.size());
        Pattern emailPattern = Pattern.compile("([a-zA-Z0-9._%+-]+)@([a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})");
        
        for (Map<String, String> row : records) {
            Map<String, String> newColumns = new HashMap<>();
            for (Map.Entry<String, String> entry : row.entrySet()) {
                String value = entry.getValue();
                if (value != null) {
                    Matcher matcher = emailPattern.matcher(value);
                    if (matcher.find()) {
                        newColumns.put(entry.getKey() + "_domain", matcher.group(2));
                    }
                }
            }
            row.putAll(newColumns);
        }
        return records;
    }

    /**
     * Validates and filters email addresses.
     */
    public List<Map<String, String>> validateEmails(List<Map<String, String>> records) {
        logger.debug("Validating emails for {} records", records.size());
        Pattern emailPattern = Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");
        
        for (Map<String, String> row : records) {
            for (Map.Entry<String, String> entry : row.entrySet()) {
                String value = entry.getValue();
                if (value != null && !value.isEmpty()) {
                    if (!emailPattern.matcher(value).matches() && value.contains("@")) {
                        // Mark invalid emails
                        entry.setValue("");
                    }
                }
            }
        }
        return records;
    }

    /**
     * Extracts URL components (protocol, domain, path, query).
     */
    public List<Map<String, String>> extractURLComponents(List<Map<String, String>> records) {
        logger.debug("Extracting URL components for {} records", records.size());
        Pattern urlPattern = Pattern.compile("(https?://)([^/]+)(/[^?]*)?(\\?.*)?");
        
        for (Map<String, String> row : records) {
            Map<String, String> newColumns = new HashMap<>();
            for (Map.Entry<String, String> entry : row.entrySet()) {
                String value = entry.getValue();
                if (value != null && value.startsWith("http")) {
                    Matcher matcher = urlPattern.matcher(value);
                    if (matcher.matches()) {
                        String colName = entry.getKey();
                        newColumns.put(colName + "_protocol", matcher.group(1));
                        newColumns.put(colName + "_domain", matcher.group(2));
                        if (matcher.group(3) != null) newColumns.put(colName + "_path", matcher.group(3));
                        if (matcher.group(4) != null) newColumns.put(colName + "_query", matcher.group(4));
                    }
                }
            }
            row.putAll(newColumns);
        }
        return records;
    }

    /**
     * Normalizes URLs to lowercase and removes trailing slashes.
     */
    public List<Map<String, String>> normalizeURLs(List<Map<String, String>> records) {
        logger.debug("Normalizing URLs for {} records", records.size());
        
        for (Map<String, String> row : records) {
            for (Map.Entry<String, String> entry : row.entrySet()) {
                String value = entry.getValue();
                if (value != null && (value.startsWith("http://") || value.startsWith("https://"))) {
                    value = value.toLowerCase(Locale.ROOT);
                    if (value.endsWith("/")) {
                        value = value.substring(0, value.length() - 1);
                    }
                    entry.setValue(value);
                }
            }
        }
        return records;
    }

    /**
     * Standardizes phone numbers to a specified format.
     */
    public List<Map<String, String>> standardizePhoneNumbers(List<Map<String, String>> records, 
                                                              String format, String countryCode) {
        logger.debug("Standardizing phone numbers to {} format for {} records", format, records.size());
        String code = countryCode != null ? countryCode : "+1";
        
        for (Map<String, String> row : records) {
            for (Map.Entry<String, String> entry : row.entrySet()) {
                String value = entry.getValue();
                if (value != null && !value.isEmpty()) {
                    // Remove all non-digit characters
                    String digits = value.replaceAll("[^0-9]", "");
                    if (digits.length() >= 10) {
                        String formatted = switch (format != null ? format.toLowerCase() : "national") {
                            case "international" -> code + " " + formatPhoneDigits(digits);
                            case "e164" -> code + digits.substring(digits.length() - 10);
                            default -> formatPhoneDigits(digits); // national
                        };
                        entry.setValue(formatted);
                    }
                }
            }
        }
        return records;
    }

    private String formatPhoneDigits(String digits) {
        if (digits.length() >= 10) {
            String last10 = digits.substring(digits.length() - 10);
            return "(" + last10.substring(0, 3) + ") " + last10.substring(3, 6) + "-" + last10.substring(6);
        }
        return digits;
    }

    /**
     * Splits a column by delimiter into multiple new columns.
     */
    public List<Map<String, String>> splitColumn(List<Map<String, String>> records, String columnName, 
                                                  String delimiter, List<String> newNames) {
        logger.debug("Splitting column '{}' for {} records", columnName, records.size());
        String delim = delimiter != null ? delimiter : ",";
        
        for (Map<String, String> row : records) {
            String value = row.get(columnName);
            if (value != null) {
                String[] parts = value.split(Pattern.quote(delim), -1);
                for (int i = 0; i < parts.length; i++) {
                    String newName = (newNames != null && i < newNames.size()) 
                            ? newNames.get(i) 
                            : columnName + "_part" + (i + 1);
                    row.put(newName, parts[i].trim());
                }
            }
        }
        return records;
    }

    /**
     * Merges multiple columns into one.
     */
    public List<Map<String, String>> mergeColumns(List<Map<String, String>> records, List<String> columns, 
                                                   String delimiter, String newColumnName) {
        logger.debug("Merging columns {} for {} records", columns, records.size());
        String delim = delimiter != null ? delimiter : " ";
        String newName = newColumnName != null ? newColumnName : "merged_column";
        
        for (Map<String, String> row : records) {
            StringBuilder merged = new StringBuilder();
            for (String col : columns) {
                String val = row.get(col);
                if (val != null && !val.isEmpty()) {
                    if (merged.length() > 0) merged.append(delim);
                    merged.append(val);
                }
            }
            row.put(newName, merged.toString());
        }
        return records;
    }

    /**
     * Converts data types based on type conversion map.
     */
    public List<Map<String, String>> convertDataTypes(List<Map<String, String>> records, 
                                                       Map<String, String> typeMap) {
        logger.debug("Converting data types for {} records", records.size());
        
        for (Map<String, String> row : records) {
            for (Map.Entry<String, String> typeEntry : typeMap.entrySet()) {
                String column = typeEntry.getKey();
                String targetType = typeEntry.getValue();
                String value = row.get(column);
                
                if (value != null && !value.isEmpty()) {
                    try {
                        String converted = switch (targetType.toLowerCase()) {
                            case "integer" -> String.valueOf((long) NumberUtil.parseDouble(value));
                            case "float" -> String.valueOf(NumberUtil.parseDouble(value));
                            case "boolean" -> String.valueOf(Boolean.parseBoolean(value));
                            case "string" -> value;
                            default -> value;
                        };
                        row.put(column, converted);
                    } catch (Exception e) {
                        logger.debug("Failed to convert {} to {}: {}", column, targetType, value);
                    }
                }
            }
        }
        return records;
    }

    /**
     * Removes rows matching a specific pattern in a column.
     */
    public List<Map<String, String>> removeRowsWithPattern(List<Map<String, String>> records, 
                                                            String column, String patternStr) {
        if (column == null || patternStr == null) return records;
        
        logger.debug("Removing rows with pattern '{}' in column '{}' from {} records", 
                    patternStr, column, records.size());
        Pattern pattern = Pattern.compile(patternStr);
        
        return records.stream()
                .filter(row -> {
                    String value = row.get(column);
                    return value == null || !pattern.matcher(value).find();
                })
                .collect(Collectors.toList());
    }

    /**
     * Keeps only rows where specified columns contain valid numeric values.
     */
    public List<Map<String, String>> keepOnlyNumericRows(List<Map<String, String>> records, 
                                                          Set<String> columns) {
        if (columns.isEmpty()) return records;
        
        logger.debug("Keeping only numeric rows for columns {} from {} records", columns, records.size());
        
        return records.stream()
                .filter(row -> {
                    for (String col : columns) {
                        String value = row.get(col);
                        if (value == null || value.isEmpty()) return false;
                        try {
                            NumberUtil.parseDouble(value);
                        } catch (Exception e) {
                            return false;
                        }
                    }
                    return true;
                })
                .collect(Collectors.toList());
    }

    /**
     * Normalizes data using min-max normalization (scales to 0-1 range).
     */
    public List<Map<String, String>> normalizeData(List<Map<String, String>> records, Set<String> columns) {
        logger.debug("Normalizing data for columns {} in {} records", columns, records.size());
        
        for (String column : columns) {
            List<Double> values = new ArrayList<>();
            for (Map<String, String> row : records) {
                String val = row.get(column);
                if (!isNullOrEmpty(val)) {
                    try {
                        values.add(NumberUtil.parseDouble(val));
                    } catch (Exception ignored) {}
                }
            }
            
            if (values.isEmpty()) continue;
            
            double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
            double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
            double range = max - min;
            
            if (range == 0) continue;
            
            for (Map<String, String> row : records) {
                String val = row.get(column);
                if (!isNullOrEmpty(val)) {
                    try {
                        double num = NumberUtil.parseDouble(val);
                        double normalized = (num - min) / range;
                        row.put(column, String.valueOf(normalized));
                    } catch (Exception ignored) {}
                }
            }
        }
        return records;
    }

    /**
     * Standardizes data using Z-score standardization (mean=0, std=1).
     */
    public List<Map<String, String>> standardizeDataZScore(List<Map<String, String>> records, Set<String> columns) {
        logger.debug("Standardizing data (Z-score) for columns {} in {} records", columns, records.size());
        
        for (String column : columns) {
            List<Double> values = new ArrayList<>();
            for (Map<String, String> row : records) {
                String val = row.get(column);
                if (!isNullOrEmpty(val)) {
                    try {
                        values.add(NumberUtil.parseDouble(val));
                    } catch (Exception ignored) {}
                }
            }
            
            if (values.isEmpty()) continue;
            
            double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double variance = values.stream()
                    .mapToDouble(v -> Math.pow(v - mean, 2))
                    .average()
                    .orElse(0.0);
            double stdDev = Math.sqrt(variance);
            
            if (stdDev == 0) continue;
            
            for (Map<String, String> row : records) {
                String val = row.get(column);
                if (!isNullOrEmpty(val)) {
                    try {
                        double num = NumberUtil.parseDouble(val);
                        double standardized = (num - mean) / stdDev;
                        row.put(column, String.valueOf(standardized));
                    } catch (Exception ignored) {}
                }
            }
        }
        return records;
    }

    /**
     * Bins continuous data into discrete categories.
     */
    public List<Map<String, String>> binData(List<Map<String, String>> records, String column, 
                                              List<Double> edges, List<String> labels) {
        if (edges == null || edges.isEmpty()) return records;
        
        logger.debug("Binning column '{}' for {} records", column, records.size());
        String newColumnName = column + "_binned";
        
        for (Map<String, String> row : records) {
            String val = row.get(column);
            if (!isNullOrEmpty(val)) {
                try {
                    double num = NumberUtil.parseDouble(val);
                    String bin = "unknown";
                    
                    for (int i = 0; i < edges.size() - 1; i++) {
                        if (num >= edges.get(i) && num < edges.get(i + 1)) {
                            bin = (labels != null && i < labels.size()) ? labels.get(i) : "bin_" + i;
                            break;
                        }
                    }
                    // Handle last edge
                    if (num >= edges.get(edges.size() - 1)) {
                        int lastIdx = edges.size() - 1;
                        bin = (labels != null && lastIdx < labels.size()) ? labels.get(lastIdx) : "bin_" + lastIdx;
                    }
                    
                    row.put(newColumnName, bin);
                } catch (Exception ignored) {}
            }
        }
        return records;
    }

    private boolean isNullOrEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }
}
