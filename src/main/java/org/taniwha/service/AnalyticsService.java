package org.taniwha.service;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.util.Pair;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.taniwha.dto.AnalyticsResponseDTO;
import org.taniwha.statistics.*;
import org.taniwha.util.AggregateCalculator;
import org.taniwha.util.DateUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// Service for creating processing and analyzing aggregated data
@Service
public class AnalyticsService {

    private static final Logger logger = LoggerFactory.getLogger(AnalyticsService.class);

    private static final String CONTINUOUS_TYPE = "continuous";
    private static final String CATEGORICAL_TYPE = "categorical";
    private static final String DATE_TYPE = "date";
    private static final String VALUE = "value";
    private static final String BINS = "bins";
    private static final String BIN_RANGES = "binRanges";

    private final AggregateCalculator calculator = new AggregateCalculator();

    @Async
    public CompletableFuture<AnalyticsResponseDTO> processAnalytics(MultipartFile file) {
        return process(file, Optional.empty(), Optional.empty());
    }

    @Async
    public CompletableFuture<AnalyticsResponseDTO> recalculateFeatureAsType(MultipartFile file, String featureName, String featureType) {
        return process(file, Optional.of(featureName), Optional.of(featureType));
    }

    @Async
    public CompletableFuture<AnalyticsResponseDTO> filterData(MultipartFile file, Map<String, Object> filters) {
        AnalyticsResponseDTO response = new AnalyticsResponseDTO();
        Map<Pair<String, String>, Map<Pair<String, String>, Integer>> categoryCombinationCounts = new ConcurrentHashMap<>();

        try {
            String fileName = file.getOriginalFilename();
            if (fileName != null && fileName.endsWith(".xlsx")) {
                filterXlsxFile(file, filters, response, categoryCombinationCounts);
            } else {
                filterCsvFile(file, filters, response, categoryCombinationCounts);
            }
        } catch (Exception e) {
            logger.debug("Error filtering file", e);
            response.setMessage("Error filtering file: " + e.getMessage());
        }
        return CompletableFuture.completedFuture(response);
    }

    private void filterCsvFile(MultipartFile file, Map<String, Object> filters, AnalyticsResponseDTO response, Map<Pair<String, String>, Map<Pair<String, String>, Integer>> categoryCombinationCounts) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            CSVFormat csvFormat = CSVFormat.DEFAULT.withFirstRecordAsHeader().withIgnoreEmptyLines(true).withDelimiter(autoDetectDelimiter(reader));
            CSVParser csvParser = new CSVParser(reader, csvFormat);

            List<CSVRecord> filteredRecords;
            if (filters == null || filters.isEmpty())
                filteredRecords = csvParser.getRecords();
            else {
                filteredRecords = List.copyOf(csvParser.getRecords().stream()
                        .filter(rowData -> applyFilters(rowData.toMap(), filters))
                        .toList());
            }

            if (filteredRecords.isEmpty()) {
                response.setMessage("No records match the provided filters.");
                return;
            }

            processData(List.copyOf(filteredRecords.stream().map(CSVRecord::toMap).toList()), Optional.empty(), Optional.empty(), response, categoryCombinationCounts);
        }
    }

    private void filterXlsxFile(MultipartFile file, Map<String, Object> filters, AnalyticsResponseDTO response, Map<Pair<String, String>, Map<Pair<String, String>, Integer>> categoryCombinationCounts) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            DataFormatter formatter = new DataFormatter();
            List<Map<String, String>> records = new ArrayList<>();

            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                Row headerRow = sheet.getRow(0);
                for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                    Row row = sheet.getRow(i);
                    Map<String, String> rowData = new HashMap<>();
                    for (int j = 0; j < headerRow.getLastCellNum(); j++) {
                        rowData.put(formatter.formatCellValue(headerRow.getCell(j)), formatter.formatCellValue(row.getCell(j)));
                    }
                    records.add(rowData);
                }
            }

            List<Map<String, String>> filteredRecords;
            if (filters == null || filters.isEmpty())
                filteredRecords = records;
            else {
                filteredRecords = List.copyOf(records.stream()
                        .filter(rowData -> applyFilters(rowData, filters))
                        .toList());
            }

            if (filteredRecords.isEmpty()) {
                response.setMessage("No records match the provided filters.");
                return;
            }

            processData(filteredRecords, Optional.empty(), Optional.empty(), response, categoryCombinationCounts);
        }
    }

    private boolean evaluateCondition(Map<String, String> rowData, String feature, Object criteria) {
        if (criteria instanceof Map) {
            Map<String, Object> criteriaMap = (Map<String, Object>) criteria;
            String type = (String) criteriaMap.get("type");
            String filterType = (String) criteriaMap.get("filterType");
            String featureValue = rowData.get(feature);

            if (featureValue == null || featureValue.trim().isEmpty())
                return false;

            try {
                return switch (filterType) {
                    case CATEGORICAL_TYPE ->
                            "equal".equals(type) && featureValue.equals(criteriaMap.get(VALUE).toString());
                    case CONTINUOUS_TYPE, DATE_TYPE -> evaluateValueCondition(featureValue, criteriaMap);
                    default -> throw new IllegalArgumentException("Unknown filter type: " + filterType);
                };
            } catch (Exception e) {
                logger.debug("Cannot evaluate condition: {} and {}", featureValue, criteriaMap.get(VALUE));
                return false;
            }
        } else {
            throw new IllegalArgumentException("Invalid filter criteria format");
        }
    }

    private boolean evaluateValueCondition(String featureValue, Map<String, Object> criteriaMap) {
        String type = (String) criteriaMap.get("type");
        Object value = criteriaMap.get(VALUE);

        return switch (type) {
            case "equal" -> compareValues(featureValue, value.toString()) == 0;
            case "greater" -> compareValues(featureValue, value.toString()) > 0;
            case "less" -> compareValues(featureValue, value.toString()) < 0;
            case "between" -> {
                List<String> values = (List<String>) value;
                yield compareValues(featureValue, values.get(0)) >= 0 &&
                        compareValues(featureValue, values.get(1)) <= 0;
            }
            default -> false;
        };
    }

    private int compareValues(String featureValue, String criteriaValue) {
        // Try to parse as date first
        Optional<LocalDateTime> featureDateOpt = DateUtils.parseDate(featureValue);
        Optional<LocalDateTime> criteriaDateOpt = DateUtils.parseDate(criteriaValue);

        if (featureDateOpt.isPresent() && criteriaDateOpt.isPresent())
            return featureDateOpt.get().compareTo(criteriaDateOpt.get());

        // If not a date, try to parse as number
        try {
            double featureNum = Double.parseDouble(featureValue);
            double criteriaNum = Double.parseDouble(criteriaValue);
            return Double.compare(featureNum, criteriaNum);
        } catch (NumberFormatException e) {
            logger.debug("Cannot compare values: {} and {}", featureValue, criteriaValue);
            throw new IllegalArgumentException("Cannot compare values: " + featureValue + " and " + criteriaValue, e);
        }
    }

    private boolean applyFilters(Map<String, String> rowData, Map<String, Object> filters) {
        boolean globalResult = true;
        String globalOperator = (String) filters.get("operator");

        Map<String, Object> conditions = (Map<String, Object>) filters.get("conditions");

        for (Map.Entry<String, Object> filter : conditions.entrySet()) {
            String feature = filter.getKey();
            Object criteriaObject = filter.getValue();

            if (criteriaObject == null) continue;

            boolean featureResult = applyFeatureFilter(rowData, feature, criteriaObject);

            if ("AND".equalsIgnoreCase(globalOperator))
                globalResult = globalResult && featureResult;

            if (!globalResult && "AND".equalsIgnoreCase(globalOperator)) return false;
            if (globalResult && "OR".equalsIgnoreCase(globalOperator)) return true;
        }
        return globalResult;
    }

    private boolean applyFeatureFilter(Map<String, String> rowData, String feature, Object criteriaObject) {
        if (criteriaObject instanceof Map) {
            return applyComplexCondition(rowData, feature, (Map<String, Object>) criteriaObject);
        } else if (criteriaObject instanceof List) {
            List<String> categories = (List<String>) criteriaObject;
            return categories.contains(rowData.get(feature));
        } else if (criteriaObject instanceof String) {
            return criteriaObject.equals(rowData.get(feature));
        } else {
            throw new IllegalArgumentException("Invalid filter criteria format");
        }
    }

    private boolean applyComplexCondition(Map<String, String> rowData, String feature, Map<String, Object> criteriaWithOperators) {
        List<?> criteriaList = (List<?>) criteriaWithOperators.get("conditions");
        List<String> logicalOps = (List<String>) criteriaWithOperators.get("operators");

        if (criteriaList == null || criteriaList.isEmpty()) return false;

        boolean featureResult = evaluateCondition(rowData, feature, criteriaList.get(0));

        for (int i = 1; i < criteriaList.size(); i++) {
            boolean conditionResult = evaluateCondition(rowData, feature, criteriaList.get(i));
            String logicalOp = logicalOps.get(i - 1);

            if ("AND".equalsIgnoreCase(logicalOp))
                featureResult = featureResult && conditionResult;
            else if ("OR".equalsIgnoreCase(logicalOp))
                featureResult = featureResult || conditionResult;
        }

        return featureResult;
    }


    private CompletableFuture<AnalyticsResponseDTO> process(MultipartFile file, Optional<String> overrideFeatureName, Optional<String> overrideFeatureType) {
        AnalyticsResponseDTO response = new AnalyticsResponseDTO();
        // For chi squared
        Map<Pair<String, String>, Map<Pair<String, String>, Integer>> categoryCombinationCounts = new ConcurrentHashMap<>();

        try {
            String fileName = file.getOriginalFilename();
            if (fileName != null && fileName.endsWith(".xlsx")) {
                processXlsxFile(file, overrideFeatureName, overrideFeatureType, response, categoryCombinationCounts);
            } else {
                processCsvFile(file, overrideFeatureName, overrideFeatureType, response, categoryCombinationCounts);
            }
        } catch (Exception e) {
            response.setMessage("Error processing file: " + e.getMessage());
        }

        return CompletableFuture.completedFuture(response);
    }

    private void processCsvFile(MultipartFile file, Optional<String> overrideFeatureName, Optional<String> overrideFeatureType, AnalyticsResponseDTO response, Map<Pair<String, String>, Map<Pair<String, String>, Integer>> categoryCombinationCounts) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            CSVFormat csvFormat = CSVFormat.DEFAULT.withFirstRecordAsHeader().withIgnoreEmptyLines(true).withDelimiter(autoDetectDelimiter(reader));
            CSVParser csvParser = new CSVParser(reader, csvFormat);
            List<Map<String, String>> records = csvParser.getRecords().stream().map(CSVRecord::toMap).toList();
            processData(records, overrideFeatureName, overrideFeatureType, response, categoryCombinationCounts);
        }
    }

    private void processXlsxFile(MultipartFile file, Optional<String> overrideFeatureName, Optional<String> overrideFeatureType, AnalyticsResponseDTO response, Map<Pair<String, String>, Map<Pair<String, String>, Integer>> categoryCombinationCounts) {
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            DataFormatter formatter = new DataFormatter();
            List<Map<String, String>> records = new ArrayList<>();
            logger.debug("Starting to process XLSX file.");

            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                processSheet(workbook.getSheetAt(sheetIndex), records, formatter);
            }

            if (records.isEmpty()) {
                response.setMessage("No data found in any sheets of the provided Excel file.");
                return;
            }

            processData(records, overrideFeatureName, overrideFeatureType, response, categoryCombinationCounts);
        } catch (Exception e) {
            logger.debug("Exception encountered while processing XLSX file", e);
            response.setMessage("Error processing XLSX file: " + e.getMessage());
        }
    }

    private void processSheet(Sheet sheet, List<Map<String, String>> records, DataFormatter formatter) {
        Row headerRow = sheet.getRow(0);
        if (headerRow == null) return;

        int numCells = headerRow.getLastCellNum();
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            processRow(sheet.getRow(rowIndex), headerRow, numCells, records, formatter);
        }
    }

    private void processRow(Row row, Row headerRow, int numCells, List<Map<String, String>> records, DataFormatter formatter) {
        if (row == null) return; // Skip null rows

        Map<String, String> rowData = new HashMap<>();
        for (int cellIndex = 0; cellIndex < numCells; cellIndex++) {
            String headerValue = formatter.formatCellValue(headerRow.getCell(cellIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
            String cellValue = formatCellValue(row.getCell(cellIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL), formatter);
            rowData.put(headerValue, cellValue);
        }
        records.add(rowData);
    }

    private String formatCellValue(Cell cell, DataFormatter formatter) {
        return cell == null ? "" : formatter.formatCellValue(cell);
    }

    private void processData(List<Map<String, String>> records, Optional<String> overrideFeatureName, Optional<String> overrideFeatureType, AnalyticsResponseDTO response, Map<Pair<String, String>, Map<Pair<String, String>, Integer>> categoryCombinationCounts) {
        Map<String, List<Double>> continuousData = new ConcurrentHashMap<>();
        Map<String, Map<String, Integer>> categoricalData = new ConcurrentHashMap<>();
        Map<String, List<String>> dateData = new ConcurrentHashMap<>();
        Map<String, Long> missingValueCounts = new ConcurrentHashMap<>();
        List<OmittedFeatureStatistics> omittedFeatures = new CopyOnWriteArrayList<>();

        Map<String, Map<String, Integer>> finalCategoricalData = categoricalData;
        records.parallelStream().forEach(rowData -> processRecord(rowData, continuousData, finalCategoricalData, dateData, missingValueCounts, overrideFeatureName, overrideFeatureType, categoryCombinationCounts));

        categoricalData = filterCategoricalData(categoricalData, omittedFeatures, records.size());

        long totalRecords = records.size();
        if (overrideFeatureName.isPresent() && overrideFeatureType.isPresent()) {
            switch (overrideFeatureType.get().toLowerCase()) {
                case CONTINUOUS_TYPE:
                    // Check if the field was processed as date
                    if (dateData.containsKey(overrideFeatureName.get())) {
                        List<DateFeatureStatistics> dateStatistics = processDateData(Collections.singletonMap(overrideFeatureName.get(), dateData.get(overrideFeatureName.get())), missingValueCounts, totalRecords);
                        response.setDateFeatures(dateStatistics);
                    } else {
                        List<FeatureStatistics> continuousStatistics = processContinuousData(Collections.singletonMap(overrideFeatureName.get(), continuousData.get(overrideFeatureName.get())), missingValueCounts, totalRecords);
                        response.setContinuousFeatures(continuousStatistics);
                    }
                    break;
                case CATEGORICAL_TYPE:
                    List<FeatureStatistics> categoricalStatistics = processCategoricalData(Collections.singletonMap(overrideFeatureName.get(), categoricalData.get(overrideFeatureName.get())), missingValueCounts, totalRecords);
                    response.setCategoricalFeatures(categoricalStatistics);
                    break;
                default:
                    break;
            }
        } else {
            List<FeatureStatistics> continuousStatistics = processContinuousData(continuousData, missingValueCounts, totalRecords);
            List<FeatureStatistics> categoricalStatistics = processCategoricalData(categoricalData, missingValueCounts, totalRecords);
            List<DateFeatureStatistics> dateStatistics = processDateData(dateData, missingValueCounts, totalRecords);

            response.setDateFeatures(dateStatistics);
            response.setContinuousFeatures(continuousStatistics);
            response.setCategoricalFeatures(categoricalStatistics);
        }

        // Add omitted features to the response
        response.setOmittedFeatures(omittedFeatures);

        // Calculate aggregate statistics
        Map<String, Map<String, Double>> covariances = calculator.calculateCovariances(continuousData);
        Map<String, Map<String, Double>> pearsonCorrelations = calculator.calculatePearsonCorrelations(continuousData);
        Map<String, Map<String, Double>> spearmanCorrelations = calculator.calculateSpearmanCorrelations(continuousData);
        List<ChiSquaredTestResult> chiSquareTest = calculator.calculateChiSquaredTest(categoricalData, categoryCombinationCounts);

        response.setCovariances(covariances);
        response.setPearsonCorrelations(pearsonCorrelations);
        response.setSpearmanCorrelations(spearmanCorrelations);
        response.setChiSquareTest(chiSquareTest);

        response.setMessage("Data processed successfully.");
    }

    private Map<String, Map<String, Integer>> filterCategoricalData(Map<String, Map<String, Integer>> categoricalData, List<OmittedFeatureStatistics> omittedFeatures, long totalRecords) {
        return categoricalData.entrySet().stream()
                .filter(entry -> {
                    String columnName = entry.getKey();
                    Map<String, Integer> columnData = entry.getValue();

                    long uniqueValuesCount = columnData.size();
                    long totalValuesCount = columnData.values().stream().mapToInt(Integer::intValue).sum();
                    double uniqueValuesPercentage = (double) uniqueValuesCount / totalValuesCount * 100;

                    long nullCount = totalRecords - totalValuesCount;
                    boolean isTooManyUniqueValues = uniqueValuesPercentage >= 50.0;
                    boolean exceedsMaxCategories = uniqueValuesCount > 200;
                    boolean isLikelyUUID = columnData.keySet().stream().anyMatch(value -> value.matches("[0-9a-fA-F-]{36}"));

                    String reason = null;
                    if (isTooManyUniqueValues) reason = "Too many unique values (" + uniqueValuesPercentage + "%)";
                    else if (exceedsMaxCategories) reason = "Exceeds maximum categories (more than 200)";
                    else if (isLikelyUUID) reason = "Likely contains UUIDs";

                    if (reason != null) {
                        omittedFeatures.add(new OmittedFeatureStatistics(columnName, totalValuesCount, (double) nullCount / totalRecords * 100, nullCount, reason));
                        logger.debug("Omitting column {}: {}", columnName, reason);
                    }

                    return reason == null;
                })
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> b, ConcurrentHashMap::new));
    }

    private void processRecord(Map<String, String> rowData, Map<String, List<Double>> continuousData, Map<String, Map<String, Integer>> categoricalData, Map<String, List<String>> dateData, Map<String, Long> missingValueCounts, Optional<String> overrideFeatureName, Optional<String> overrideFeatureType, Map<Pair<String, String>, Map<Pair<String, String>, Integer>> categoryCombinationCounts) {
        rowData.forEach((column, value) -> {
            // Treat null, empty strings, and "NULL" as missing values
            if (value == null || value.trim().isEmpty() || "NULL".equalsIgnoreCase(value.trim())) {
                missingValueCounts.merge(column, 1L, Long::sum);
                return;
            }

            String trimmedValue = value.trim();
            String featureType = determineFeatureType(overrideFeatureName, overrideFeatureType, column, trimmedValue);

            switch (featureType) {
                case DATE_TYPE:
                    DateUtils.parseDate(trimmedValue).ifPresent(parsedDate -> dateData.computeIfAbsent(column, k -> new CopyOnWriteArrayList<>()).add(parsedDate.format(DateTimeFormatter.ISO_LOCAL_DATE)));
                    continuousData.remove(column);
                    categoricalData.remove(column);
                    break;
                case CONTINUOUS_TYPE:
                    try {
                        double parsedNumber = Double.parseDouble(trimmedValue);
                        continuousData.computeIfAbsent(column, k -> new CopyOnWriteArrayList<>()).add(parsedNumber);
                    } catch (NumberFormatException e) {
                        logger.debug("Error parsing number from string: {}", trimmedValue);
                    }
                    categoricalData.remove(column);
                    dateData.remove(column);
                    break;
                case CATEGORICAL_TYPE:
                    categoricalData.computeIfAbsent(column, k -> new ConcurrentHashMap<>()).merge(trimmedValue, 1, Integer::sum);
                    continuousData.remove(column);
                    dateData.remove(column);
                    break;
                default:
                    logger.debug("Unknown feature type for column {}", column);
                    break;
            }
        });

        // Build contingency tables for pairs of categorical columns
        List<String> categoricalColumns = new CopyOnWriteArrayList<>(categoricalData.keySet());
        for (int i = 0; i < categoricalColumns.size(); i++) {
            for (int j = i + 1; j < categoricalColumns.size(); j++) {
                String column1 = categoricalColumns.get(i);
                String column2 = categoricalColumns.get(j);
                String value1 = rowData.getOrDefault(column1, "").trim();
                String value2 = rowData.getOrDefault(column2, "").trim();

                if (!value1.isEmpty() && !"NULL".equalsIgnoreCase(value1) && !value2.isEmpty() && !"NULL".equalsIgnoreCase(value2)) {
                    Pair<String, String> columnPair = new Pair<>(column1, column2);
                    Pair<String, String> valuePair = new Pair<>(value1, value2);

                    categoryCombinationCounts.computeIfAbsent(columnPair, k -> new ConcurrentHashMap<>()).merge(valuePair, 1, Integer::sum);
                }
            }
        }
    }

    private String determineFeatureType(Optional<String> overrideFeatureName, Optional<String> overrideFeatureType, String column, String value) {
        boolean isDate = DateUtils.parseDate(value).isPresent();
        if (overrideFeatureName.isPresent() && overrideFeatureName.get().equals(column)) {
            if (isDate && !overrideFeatureType.orElse("unknown").equalsIgnoreCase(CATEGORICAL_TYPE))
                return DATE_TYPE;
            return overrideFeatureType.orElse("unknown").toLowerCase();
        }
        if (isDate)
            return DATE_TYPE;
        else if (value.matches("-?\\d+(\\.\\d+)?"))
            return CONTINUOUS_TYPE;
        else
            return CATEGORICAL_TYPE;
    }

    private char autoDetectDelimiter(BufferedReader reader) throws IOException {
        reader.mark(1024);
        String line = reader.readLine();
        reader.reset();
        return Stream.of(',', ';', '\t').max(Comparator.comparingInt(delimiter -> StringUtils.countMatches(line, delimiter))).orElse(',');
    }

    // Process categorical data for analytics
    private List<FeatureStatistics> processCategoricalData(Map<String, Map<String, Integer>> categoricalData, Map<String, Long> missingValueCounts, long totalRecords) {
        List<FeatureStatistics> statisticsList = new ArrayList<>();
        categoricalData.forEach((key, valueMap) -> {
            long missingValues = missingValueCounts.getOrDefault(key, 0L);
            double percentMissing = (double) missingValues / totalRecords * 100;
            List<Map.Entry<String, Integer>> sortedEntries = List.copyOf(valueMap.entrySet().stream()
                    .sorted((entry1, entry2) -> entry2.getValue().compareTo(entry1.getValue()))
                    .toList());
            Map.Entry<String, Integer> modeEntry = sortedEntries.get(0);
            String mode = modeEntry.getKey();
            int modeFrequency = modeEntry.getValue();
            double modePercentage = (double) modeFrequency / totalRecords * 100;
            String secondMode = sortedEntries.size() > 1 ? sortedEntries.get(1).getKey() : null;
            Integer secondModeFrequency = secondMode != null ? valueMap.get(secondMode) : null;
            Double secondModePercentage = secondModeFrequency != null ? (double) secondModeFrequency / totalRecords * 100 : null;

            CategoricalFeatureStatistics stats = new CategoricalFeatureStatistics(key, totalRecords - missingValues, percentMissing, missingValues, valueMap.size(), mode, modeFrequency, modePercentage, secondMode, secondModeFrequency, secondModePercentage, valueMap);

            statisticsList.add(stats);
        });
        return statisticsList;
    }

    // Process continuous data for analytics
    private List<FeatureStatistics> processContinuousData(Map<String, List<Double>> continuousData, Map<String, Long> missingValueCounts, long totalRecords) {
        List<FeatureStatistics> statisticsList = new ArrayList<>();
        continuousData.forEach((key, valueList) -> {
            List<Double> outliers = identifyOutliers(valueList);
            double mean = valueList.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
            double stddev = Math.sqrt(valueList.stream().mapToDouble(v -> Math.pow(v - mean, 2)).sum() / valueList.size());
            long missingValues = missingValueCounts.getOrDefault(key, 0L);
            double percentMissing = (double) missingValues / totalRecords * 100;
            double min = Collections.min(valueList);
            double max = Collections.max(valueList);
            double q1 = getPercentile(valueList, 25);
            double median = getPercentile(valueList, 50);
            double q3 = getPercentile(valueList, 75);
            Map<String, Object> histogramInfo = generateHistogram(valueList);

            List<Double> bins = (List<Double>) histogramInfo.get(BINS);
            List<String> binRanges = (List<String>) histogramInfo.get(BIN_RANGES);

            statisticsList.add(new ContinuousFeatureStatistics(key, valueList.size(), percentMissing, missingValues, valueList.size(), min, max, mean, stddev, q1, median, q3, bins, binRanges, outliers));
        });
        return statisticsList;
    }

    // Process date data for analytics
    private List<DateFeatureStatistics> processDateData(Map<String, List<String>> dateData, Map<String, Long> missingValueCounts, long totalRecords) {
        if (dateData.isEmpty()) {
            return Collections.emptyList(); // Return an empty list if no date data
        }

        List<DateFeatureStatistics> dateStatisticsList = new ArrayList<>();
        dateData.forEach((key, dateStringList) -> {
            List<LocalDate> dates = dateStringList.stream().map(LocalDate::parse).toList();
            List<Double> dateValues = dates.stream().mapToDouble(LocalDate::toEpochDay).boxed().collect(Collectors.toList());
            List<Double> outliers = identifyOutliers(dateValues);

            LocalDate earliestDate = dates.stream().min(LocalDate::compareTo).orElse(null);
            LocalDate latestDate = dates.stream().max(LocalDate::compareTo).orElse(null);
            long missingValues = missingValueCounts.getOrDefault(key, 0L);
            double percentMissing = (double) missingValues / totalRecords * 100;
            double meanEpoch = dateValues.stream().mapToDouble(v -> v).average().orElse(Double.NaN);
            LocalDate meanDate = LocalDate.ofEpochDay((long) meanEpoch);
            double medianEpoch = getPercentile(dateValues, 50);
            LocalDate medianDate = LocalDate.ofEpochDay((long) medianEpoch);
            double q1Epoch = getPercentile(dateValues, 25);
            LocalDate q1Date = LocalDate.ofEpochDay((long) q1Epoch);
            double q3Epoch = getPercentile(dateValues, 75);
            LocalDate q3Date = LocalDate.ofEpochDay((long) q3Epoch);
            double stdDevEpoch = Math.sqrt(dateValues.stream().mapToDouble(v -> Math.pow(v - meanEpoch, 2)).sum() / dateValues.size());

            List<String> outlierDates = List.copyOf(outliers.stream()
                    .map(outlier -> LocalDate.ofEpochDay(outlier.longValue()).format(DateTimeFormatter.ISO_LOCAL_DATE))
                    .toList());
            DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE;
            DateFeatureStatistics stats = new DateFeatureStatistics(key, dateStringList.size(), percentMissing, missingValues, earliestDate != null ? earliestDate.format(formatter) : "N/A", latestDate != null ? latestDate.format(formatter) : "N/A", dateStringList.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting())), outlierDates, meanDate.format(formatter), stdDevEpoch, medianDate.format(formatter), q1Date.format(formatter), q3Date.format(formatter));

            dateStatisticsList.add(stats);
        });
        return dateStatisticsList;
    }

    // Generate histogram for continuous data
    private Map<String, Object> generateHistogram(List<Double> data) {
        Map<String, Object> histogramInfo = new HashMap<>();
        if (data.isEmpty()) {
            histogramInfo.put(BINS, Collections.emptyList());
            histogramInfo.put(BIN_RANGES, Collections.emptyList());
            return histogramInfo;
        }

        double min = Collections.min(data);
        double max = Collections.max(data);
        // Handle case where all data points are identical
        if (max == min) {
            histogramInfo.put(BINS, Collections.singletonList((double) data.size()));
            histogramInfo.put(BIN_RANGES, Collections.singletonList(String.format(Locale.US, "[%f - %f]", min, max)));
            return histogramInfo;
        }

        // Calculate histogram bin width using the Freedman-Diaconis rule as a basis
        double range = max - min;
        double q1 = getPercentile(data, 25);
        double q3 = getPercentile(data, 75);
        double iqr = q3 - q1;

        // Calculate binWidth and handle division by zero or invalid values
        double binWidth = 2.0 * iqr / Math.cbrt(data.size());
        binWidth = Math.max(binWidth, range / 10.0);

        if (binWidth <= 0)
            throw new IllegalArgumentException("Invalid bin width calculated. Check the data distribution.");
        int binCount = (int) Math.ceil(range / binWidth);

        List<Double> bins = new ArrayList<>(Collections.nCopies(binCount, 0.0));
        double finalBinWidth = binWidth;
        data.forEach(value -> {
            int binIndex = (int) ((value - min) / finalBinWidth);
            binIndex = Math.min(binIndex, binCount - 1);
            bins.set(binIndex, bins.get(binIndex) + 1);
        });
        DecimalFormat df = new DecimalFormat("0.##", new DecimalFormatSymbols(Locale.US));
        List<String> binRanges = new ArrayList<>();
        for (int i = 0; i < binCount; i++) {
            double binMin = min + (i * binWidth);
            double binMax = binMin + binWidth;
            binRanges.add(String.format(Locale.US, "[%s - %s]", df.format(binMin), df.format(binMax)));
        }
        histogramInfo.put(BINS, bins);
        histogramInfo.put(BIN_RANGES, binRanges);
        return histogramInfo;
    }

    // Calculate percentile of data
    private double getPercentile(List<Double> data, double percentile) {
        if (data.isEmpty()) return 0;
        List<Double> sortedData = new ArrayList<>(data);
        Collections.sort(sortedData);
        int index = (int) ((percentile / 100.0) * sortedData.size());
        return sortedData.get(Math.min(index, sortedData.size() - 1));
    }

    private List<Double> identifyOutliers(List<Double> data) {
        Collections.sort(data);
        double q1 = getPercentile(data, 25);
        double q3 = getPercentile(data, 75);
        double iqr = q3 - q1;
        double lowerBound = q1 - 1.5 * iqr;
        double upperBound = q3 + 1.5 * iqr;
        return data.stream().filter(x -> x < lowerBound || x > upperBound).toList();
    }
}
