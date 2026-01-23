package org.taniwha.service;

import com.github.pjfanning.xlsx.StreamingReader;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.taniwha.security.FileFilter;
import org.taniwha.util.DateUtil;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.text.ParseException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class DataProcessingService {

    private final FileFilter fileFilter;
    private static final Logger logger = LoggerFactory.getLogger(DataProcessingService.class);
    private static final Locale LOCALE = Locale.GERMANY;
    private static final NumberFormat NUMBER_FORMAT = NumberFormat.getInstance(LOCALE);

    public DataProcessingService(FileFilter fileFilter) {
        this.fileFilter = fileFilter;
    }

    public void streamRows(Path path, Consumer<Map<String,String>> rowConsumer) throws IOException {
        fileFilter.validate(path);
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".csv")) {
            streamCsv(path, rowConsumer);
        } else if (name.endsWith(".xlsx")) {
            try (InputStream in = Files.newInputStream(path)) {
                streamXlsx(in, rowConsumer);
            }
        } else {
            throw new IllegalArgumentException("Unsupported file type: " + name);
        }
    }

    public void streamRows(MultipartFile file, Consumer<Map<String,String>> rowConsumer) throws IOException {
        fileFilter.validate(file);
        String name = file.getOriginalFilename().toLowerCase();
        if (name.endsWith(".csv")) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
                streamCsv(reader, rowConsumer);
            }
        } else {
            try (InputStream in = file.getInputStream()) {
                streamXlsx(in, rowConsumer);
            }
        }
    }

    private void streamCsv(Path path, Consumer<Map<String,String>> rowConsumer) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            streamCsv(reader, rowConsumer);
        }
    }

    private void streamCsv(BufferedReader reader, Consumer<Map<String,String>> rowConsumer) throws IOException {
        // auto-detect delimiter (as in your existing code)
        char delim = autoDetectDelimiter(reader);

        CSVFormat fmt = CSVFormat.newFormat(delim)
                .withFirstRecordAsHeader()
                .withIgnoreSurroundingSpaces()
                .withTrim()
                .withQuote('"')
                .withIgnoreEmptyLines();

        // rewind and parse
        reader.reset();
        try (CSVParser parser = new CSVParser(reader, fmt)) {
            List<String> headers = parser.getHeaderNames();
            for (CSVRecord rec : parser) {
                Map<String,String> map = new HashMap<>();
                for (String h : headers) {
                    map.put(h, rec.get(h));
                }
                rowConsumer.accept(map);
            }
        }
    }

    private void streamXlsx(Path path, Consumer<Map<String,String>> rowConsumer) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            streamXlsx(in, rowConsumer);
        }
    }

    private void streamXlsx(InputStream in, Consumer<Map<String,String>> rowConsumer) {
        DataFormatter fmt = new DataFormatter(LOCALE);

        try (Workbook wb = StreamingReader.builder()
                .rowCacheSize(100)
                .bufferSize(4 * 1024)
                .setReadSharedFormulas(true)
                .open(in)) {

            for (Sheet sheet : wb) {
                Iterator<Row> rows = sheet.rowIterator();
                if (!rows.hasNext()) continue;

                // header
                Row header = rows.next();
                List<String> headers = new ArrayList<>();
                for (Cell c : header) {
                    headers.add(fmt.formatCellValue(c));
                }

                // rows
                while (rows.hasNext()) {
                    Row r = rows.next();
                    Map<String,String> map = new HashMap<>();
                    for (int i = 0; i < headers.size(); i++) {
                        Cell c = r.getCell(i, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                        map.put(headers.get(i), fmt.formatCellValue(c));
                    }
                    rowConsumer.accept(map);
                }
            }
        } catch (IOException e) {
            logger.error("Error streaming XLSX", e);
            throw new UncheckedIOException(e);
        }
    }
    public List<Map<String, String>> extractDataFromPath(Path path) throws IOException {
        fileFilter.validate(path);
        String fileName = path.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".xlsx"))
            return readXlsxFile(path);
        else
            return readCsvFile(path);
    }

    public List<Map<String, String>> extractData(MultipartFile file) throws IOException {
        fileFilter.validate(file);
        String fileName = file.getOriginalFilename();
        if (fileName != null && fileName.toLowerCase().endsWith(".xlsx")) {
            return readXlsxFile(file);
        } else {
            return readCsvFile(file);
        }
    }

    public List<Map<String, String>> extractFilteredDataFromPath(Path path, Map<String, Object> filters) throws IOException {
        fileFilter.validate(path);
        String fileName = path.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".xlsx")) {
            return readAndFilterXlsxFile(path, filters);
        } else {
            return readAndFilterCsvFile(path, filters);
        }
    }

    private List<Map<String, String>> readAndFilterCsvFile(Path path, Map<String, Object> filters) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            char delimiter = autoDetectDelimiter(reader);
            CSVFormat csvFormat = CSVFormat.newFormat(delimiter)
                    .withFirstRecordAsHeader()
                    .withIgnoreSurroundingSpaces()
                    .withTrim()
                    .withQuote('"')
                    .withIgnoreEmptyLines(true);

            // re-open after detecting delimiter
            try (BufferedReader secondReader = Files.newBufferedReader(path);
                 CSVParser csvParser = new CSVParser(secondReader, csvFormat)) {
                if (filters == null || filters.isEmpty()) {
                    return csvParser.getRecords().stream()
                            .map(CSVRecord::toMap)
                            .collect(Collectors.toList());
                } else {
                    return csvParser.getRecords().stream()
                            .map(CSVRecord::toMap)
                            .filter(rowData -> applyFilters(rowData, filters))
                            .collect(Collectors.toList());
                }
            }
        }
    }

    private List<Map<String, String>> readAndFilterXlsxFile(Path path, Map<String, Object> filters) throws IOException {
        try (InputStream in = Files.newInputStream(path);
             Workbook workbook = new XSSFWorkbook(in)) {

            List<Map<String, String>> records = new ArrayList<>();
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                records.addAll(extractSheetData(workbook.getSheetAt(s), filters));
            }
            return records;
        }
    }

    private List<Map<String, String>> readCsvFile(MultipartFile file) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            // Detect delimiter
            char delimiter = autoDetectDelimiter(reader);

            CSVFormat csvFormat = CSVFormat.newFormat(delimiter)
                    .withFirstRecordAsHeader()
                    .withIgnoreSurroundingSpaces()
                    .withTrim()
                    .withQuote('"')
                    .withIgnoreEmptyLines(true);

            // After detecting, re-open the stream
            try (BufferedReader secondReader = new BufferedReader(new InputStreamReader(file.getInputStream()));
                 CSVParser csvParser = new CSVParser(secondReader, csvFormat)) {

                return csvParser.getRecords().stream()
                        .map(CSVRecord::toMap)
                        .collect(Collectors.toList());
            }
        }
    }

    private List<Map<String, String>> readCsvFile(Path path) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            // Detect delimiter
            char delimiter = autoDetectDelimiter(reader);

            CSVFormat csvFormat = CSVFormat.newFormat(delimiter)
                    .withFirstRecordAsHeader()
                    .withIgnoreSurroundingSpaces()
                    .withTrim()
                    .withQuote('"')
                    .withIgnoreEmptyLines(true);

            try (BufferedReader secondReader = Files.newBufferedReader(path);
                 CSVParser csvParser = new CSVParser(secondReader, csvFormat)) {

                return csvParser.getRecords().stream()
                        .map(CSVRecord::toMap)
                        .collect(Collectors.toList());
            }
        }
    }

    private char autoDetectDelimiter(BufferedReader reader) throws IOException {
        reader.mark(8192);
        List<String> lines = new ArrayList<>();
        String line;
        int numLines = 5;
        while ((line = reader.readLine()) != null && lines.size() < numLines) {
            lines.add(line);
        }
        reader.reset();

        Map<Character, Integer> delimiterCounts = new HashMap<>();
        for (char delimiter : new char[]{',', ';', '\t'}) {
            int count = 0;
            for (String l : lines) {
                count += StringUtils.countMatches(l, delimiter);
            }
            delimiterCounts.put(delimiter, count);
        }

        return delimiterCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(',');
    }

    private List<Map<String, String>> readXlsxFile(MultipartFile file) throws IOException {
        DataFormatter formatter = new DataFormatter(LOCALE);
        List<Map<String, String>> records = new ArrayList<>();

        try (InputStream in = file.getInputStream();
             Workbook workbook = StreamingReader.builder()
                     .rowCacheSize(100)
                     .bufferSize(4096)
                     .setReadSharedFormulas(true)
                     .open(in)) {

            for (Sheet sheet : workbook) {
                Iterator<Row> rows = sheet.rowIterator();
                if (!rows.hasNext()) continue;
                // read header
                Row headerRow = rows.next();
                List<String> headers = new ArrayList<>();
                headerRow.forEach(cell -> headers.add(formatter.formatCellValue(cell)));

                while (rows.hasNext()) {
                    Row row = rows.next();
                    Map<String, String> map = new HashMap<>();
                    for (int i = 0; i < headers.size(); i++) {
                        Cell cell = row.getCell(i, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                        map.put(headers.get(i), formatter.formatCellValue(cell));
                    }
                    records.add(map);
                }
            }
        }
        return records;
    }

    private List<Map<String, String>> readXlsxFile(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path);
             Workbook workbook = new XSSFWorkbook(in)) {

            List<Map<String, String>> records = new ArrayList<>();
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                records.addAll(extractSheetData(workbook.getSheetAt(s), null));
            }
            return records;
        }
    }

    private List<Map<String, String>> extractSheetData(Sheet sheet, Map<String, Object> filters) {
        DataFormatter formatter = new DataFormatter(LOCALE);
        List<Map<String, String>> records = new ArrayList<>();
        Row headerRow = sheet.getRow(0);

        if (headerRow == null) return records;

        int numCells = headerRow.getLastCellNum();

        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || isRowEmpty(row, numCells)) {
                continue;
            }

            Map<String, String> rowData = new HashMap<>();
            for (int j = 0; j < numCells; j++) {
                String headerValue = formatter.formatCellValue(headerRow.getCell(j));
                String cellValue = formatter.formatCellValue(row.getCell(j));
                rowData.put(headerValue, cellValue);
            }

            // Apply filters if present
            if (filters == null || applyFilters(rowData, filters))
                records.add(rowData);
        }
        return records;
    }

    private boolean isRowEmpty(Row row, int numCells) {
        DataFormatter formatter = new DataFormatter(LOCALE);
        for (int j = 0; j < numCells; j++) {
            Cell cell = row.getCell(j, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell != null && !formatter.formatCellValue(cell).isEmpty()) return false;

        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Filtering logic
    // -------------------------------------------------------------------------

    private boolean applyFilters(Map<String, String> rowData, Map<String, Object> filters) {
        // If there's no operator or no conditions, just accept everything
        Object operatorObj = filters.get("operator");
        if (!(operatorObj instanceof String globalOperator)) return true;

        // Safely extract the conditions map if possible
        Map<String, Object> conditions = Collections.emptyMap();
        Object conditionsObj = filters.get("conditions");
        if (conditionsObj instanceof Map<?, ?> condMap) {
            conditions = condMap.entrySet().stream()
                    .filter(e -> e.getKey() instanceof String)
                    .collect(Collectors.toMap(
                            e -> (String) e.getKey(),
                            Map.Entry::getValue
                    ));
        }

        boolean globalResult = "AND".equalsIgnoreCase(globalOperator);

        for (Map.Entry<String, Object> filter : conditions.entrySet()) {
            String feature = filter.getKey();
            Object criteriaObject = filter.getValue();

            if (criteriaObject == null) continue;


            boolean featureResult = applyFeatureFilter(rowData, feature, criteriaObject);
            if ("AND".equalsIgnoreCase(globalOperator)) {
                globalResult = globalResult && featureResult;
                if (!globalResult) return false;
            } else if ("OR".equalsIgnoreCase(globalOperator)) {
                globalResult = featureResult;
                if (globalResult)
                    return true;
            }
        }
        return globalResult;
    }

    private String stripFileSuffix(String columnOrFeatureName) {
        // Pattern to match file name inside parentheses: "(FileName.ext)"
        // Matches any characters except closing parenthesis, followed by .csv or .xlsx extension
        // This handles filenames with spaces, special characters, dots, etc.
        return columnOrFeatureName.replaceFirst("\\s*\\([^)]+\\.(csv|xlsx)\\)$", "");
    }

    private boolean applyFeatureFilter(Map<String, String> rowData, String feature, Object criteriaObject) {
        // Strip the file name suffix from the feature name.
        String rawFeature = stripFileSuffix(feature);

        // Handle different types of criteria (complex conditions, lists, or strings).
        if (criteriaObject instanceof Map<?, ?> map) {
            return applyComplexCondition(rowData, rawFeature, map);
        } else if (criteriaObject instanceof List<?> list) {
            // We assume a list of Strings (e.g., categories).
            List<String> categories = list.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .toList();
            return categories.contains(rowData.get(rawFeature));
        } else if (criteriaObject instanceof String str) {
            return str.equals(rowData.get(rawFeature));
        }

        throw new IllegalArgumentException("Invalid filter criteria format: " + criteriaObject);
    }

    private boolean applyComplexCondition(Map<String, String> rowData,
                                          String feature,
                                          Map<?, ?> criteriaWithOperators) {
        // We expect "conditions" to be a List<?> and "operators" to be a List<String>
        Object conditionsObj = criteriaWithOperators.get("conditions");
        Object operatorsObj = criteriaWithOperators.get("operators");
        if (!(conditionsObj instanceof List<?> criteriaList) || criteriaList.isEmpty()) {
            return false;
        }
        if (!(operatorsObj instanceof List<?> opsList)) {
            return false;
        }

        // Evaluate the first condition
        boolean featureResult = evaluateCondition(rowData, feature, criteriaList.get(0));

        // Combine with subsequent conditions
        for (int i = 1; i < criteriaList.size(); i++) {
            boolean conditionResult = evaluateCondition(rowData, feature, criteriaList.get(i));
            // operators list is of size (criteriaList.size() - 1)
            String logicalOp = opsList.get(i - 1).toString();

            if ("AND".equalsIgnoreCase(logicalOp)) {
                featureResult = featureResult && conditionResult;
            } else if ("OR".equalsIgnoreCase(logicalOp)) {
                featureResult = featureResult || conditionResult;
            }
        }
        return featureResult;
    }


    private boolean evaluateCondition(Map<String, String> rowData, String feature, Object criteria) {
        if (!(criteria instanceof Map<?, ?> criteriaMap)) {
            throw new IllegalArgumentException("Invalid filter criteria format: " + criteria);
        }
        Object typeObj = criteriaMap.get("type");
        Object filterTypeObj = criteriaMap.get("filterType");
        if (!(typeObj instanceof String type) || !(filterTypeObj instanceof String filterType)) {
            throw new IllegalArgumentException("Missing type or filterType in criteria: " + criteriaMap);
        }
        String featureValue = rowData.get(feature);
        if (featureValue == null || featureValue.trim().isEmpty()) {
            return false;
        }

        try {
            return switch (filterType) {
                case "categorical" -> evaluateCategorical(type, featureValue, criteriaMap.get("value"));
                case "continuous", "date" -> evaluateValueCondition(type, featureValue, criteriaMap.get("value"));
                default -> throw new IllegalArgumentException("Unknown filter type: " + filterType);
            };
        } catch (Exception e) {
            logger.debug("Cannot evaluate condition: {} and {}", featureValue, criteriaMap.get("value"));
            return false;
        }
    }

    private boolean evaluateCategorical(String type, String featureValue, Object val) {
        // For “categorical”: we generally only check equality
        if ("equal".equals(type) && val != null) {
            return featureValue.equals(val.toString());
        }
        return false;
    }

    private boolean evaluateValueCondition(String type, String featureValue, Object rawValue) {
        return switch (type) {
            case "equal" -> compareValues(featureValue, Objects.toString(rawValue, "")) == 0;
            case "greater" -> compareValues(featureValue, Objects.toString(rawValue, "")) > 0;
            case "less" -> compareValues(featureValue, Objects.toString(rawValue, "")) < 0;
            case "between" -> {
                if (rawValue instanceof List<?> valList && valList.size() == 2) {
                    String left = Objects.toString(valList.get(0), "");
                    String right = Objects.toString(valList.get(1), "");
                    int compareLeft = compareValues(featureValue, left);
                    int compareRight = compareValues(featureValue, right);
                    yield (compareLeft >= 0 && compareRight <= 0);
                } else {
                    yield false;
                }
            }
            default -> false;
        };
    }

    private int compareValues(String featureValue, String criteriaValue) {
        // Try parse as dates first
        Optional<LocalDateTime> featureDateOpt = DateUtil.parseDate(featureValue);
        Optional<LocalDateTime> criteriaDateOpt = DateUtil.parseDate(criteriaValue);

        if (featureDateOpt.isPresent() && criteriaDateOpt.isPresent())
            return featureDateOpt.get().compareTo(criteriaDateOpt.get());

        // Fall back to numeric comparison
        try {
            double featureNum = NUMBER_FORMAT.parse(featureValue.trim()).doubleValue();
            double criteriaNum = NUMBER_FORMAT.parse(criteriaValue.trim()).doubleValue();
            return Double.compare(featureNum, criteriaNum);
        } catch (ParseException e) {
            logger.debug("Cannot compare values: {} and {}", featureValue, criteriaValue);
            throw new IllegalArgumentException("Cannot compare values: " + featureValue + " and " + criteriaValue, e);
        }
    }
}