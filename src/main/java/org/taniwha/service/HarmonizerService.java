package org.taniwha.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.taniwha.dto.DataCleaningOptionsDTO;
import org.taniwha.service.jobs.HarmonizationProcessingJobs;
import org.taniwha.util.DateUtil;
import org.taniwha.util.NumberUtil;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class HarmonizerService {
    private static final Logger logger = LoggerFactory.getLogger(HarmonizerService.class);

    private static final String GROUPS_KEY = "groups";
    private static final String VALUES_KEY = "values";

    private final DataProcessingService dataProcessingService;
    private final DataCleaningService dataCleaningService;
    private final FileService fileService;
    private final ObjectMapper objectMapper;
    private final String mappedDatasetFolder;
    private final HarmonizationProcessingJobs jobs;
    private final ExecutorService parseJobExecutor;

    public HarmonizerService(DataProcessingService dataProcessingService,
                             DataCleaningService dataCleaningService,
                             FileService fileService,
                             HarmonizationProcessingJobs jobs) {
        this.dataProcessingService = dataProcessingService;
        this.dataCleaningService = dataCleaningService;
        this.fileService = fileService;
        this.jobs = jobs;
        this.objectMapper = new ObjectMapper();
        this.mappedDatasetFolder = fileService.getMappedDatasetsFolder();
        this.parseJobExecutor = Executors.newCachedThreadPool();
    }

    public void startParseJob(String jobId,
                              String configs,
                              Map<String, List<String>> fileMappings,
                              DataCleaningOptionsDTO cleanOpts) {
        parseJobExecutor.submit(() -> {
            try {
                String result = parseFilesWithProgress(jobId, configs, fileMappings, cleanOpts);
                jobs.complete(jobId, result);
            } catch (Exception e) {
                logger.error("Parse job failed jobId={}", jobId, e);
                jobs.fail(jobId, "Error processing files: " + (e.getMessage() == null ? "Unknown error" : e.getMessage()));
            }
        });
    }

    public String parseFilesWithProgress(String jobId,
                                         String configs,
                                         Map<String, List<String>> fileMappings,
                                         DataCleaningOptionsDTO cleanOpts) {
        try {
            logger.info("[parseFilesWithProgress] jobId={} fileMappings keys = {}", jobId, fileMappings.keySet());
            logger.info("[parseFilesWithProgress] jobId={} raw configs JSON = {}", jobId, configs);

            List<Map<String, Object>> configList =
                    objectMapper.readValue(configs, new TypeReference<>() {});
            logger.info("[parseFilesWithProgress] jobId={} parsed {} config objects", jobId, configList.size());

            Map<String, Object> customConfig = getAllConfigsForFile(configList);

            int totalDatasets = fileMappings.values().stream().mapToInt(List::size).sum();
            if (totalDatasets <= 0) totalDatasets = 1;

            int processedDatasets = 0;

            for (var entry : fileMappings.entrySet()) {
                String configFileName = entry.getKey();
                Map<String, Object> configForKey = getConfigForFile(configFileName, configList);

                boolean customOnlyMode = configForKey.isEmpty();

                if (customOnlyMode) {
                    logger.warn("[parseFilesWithProgress] No config for key {} -> running in CUSTOM-ONLY mode.",
                            configFileName);
                    if (customConfig.isEmpty()) {
                        logger.warn("[parseFilesWithProgress] No custom_mapping present either -> nothing to output for key {}",
                                configFileName);
                    }
                }

                for (String datasetName : entry.getValue()) {
                    int percent = (int) Math.round((processedDatasets * 100.0) / totalDatasets);
                    jobs.update(
                            jobId,
                            percent,
                            datasetName,
                            "Processing " + (processedDatasets + 1) + "/" + totalDatasets + ": " + datasetName
                    );

                    logger.info("[parseFilesWithProgress] jobId={} Processing dataset=\"{}\" with configKey=\"{}\"",
                            jobId, datasetName, configFileName);

                    Path input = Paths.get(fileService.getDatasetFilePath(datasetName));
                    if (!Files.exists(input)) {
                        logger.error("[parseFilesWithProgress] Missing file {}", input);
                        processedDatasets++;
                        continue;
                    }

                    List<String> originalCols = new ArrayList<>();
                    dataProcessingService.streamRows(input, row -> {
                        if (originalCols.isEmpty()) originalCols.addAll(row.keySet());
                    });

                    Set<String> headerSet = new LinkedHashSet<>();

                    if (!customOnlyMode) {
                        configForKey.values().forEach(cc -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> m = (Map<String, Object>) cc;

                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> groups = (List<Map<String, Object>>) m.get(GROUPS_KEY);
                            if (groups != null) {
                                groups.forEach(g -> headerSet.add((String) g.get("column")));
                            }
                        });
                    }

                    headerSet.addAll(customConfig.keySet());

                    List<String> outputHeaders = new ArrayList<>(headerSet);
                    logger.info("[parseFilesWithProgress] jobId={} customOnlyMode={}, outputHeaders({})={}",
                            jobId, customOnlyMode, outputHeaders.size(), outputHeaders);

                    String base = datasetName.replaceAll("\\.[^.]+$", "");
                    Path out = Paths.get(mappedDatasetFolder, "parsed_" + base + ".csv");
                    try {
                        Files.createDirectories(out.getParent());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }

                    Set<String> seen = new HashSet<>();
                    Set<String> numericColsForCleaning = extractNumericColumns(cleanOpts);

                    try (BufferedWriter writer = Files.newBufferedWriter(out);
                         CSVPrinter printer = new CSVPrinter(writer,
                                 CSVFormat.newFormat(';')
                                         .withRecordSeparator(System.lineSeparator())
                                         .withHeader(outputHeaders.toArray(new String[0])))) {

                        dataProcessingService.streamRows(input, row -> {
                            if (cleanOpts != null) {
                                if (cleanOpts.isRemoveEmptyRows() && dataCleaningService.isEmptyRow(row)) return;

                                if (cleanOpts.isRemoveDuplicates()) {
                                    String key = dataCleaningService.dedupeKey(row);
                                    if (!seen.add(key)) return;
                                }

                                if (cleanOpts.isStandardizeDates()) {
                                    dataCleaningService.standardizeDatesInPlace(row, cleanOpts.getDateOutputFormat());
                                }

                                if (shouldStandardizeNumeric(cleanOpts, numericColsForCleaning)) {
                                    dataCleaningService.standardizeNumericInPlace(
                                            row, numericColsForCleaning, cleanOpts.getNumericMode()
                                    );
                                }
                            }

                            Map<String, String> outRow = new LinkedHashMap<>();
                            outputHeaders.forEach(h -> outRow.put(h, ""));

                            if (!customOnlyMode) {
                                row.forEach((k, v) -> {
                                    if (outRow.containsKey(k)) outRow.put(k, v);
                                });
                            }

                            if (!customOnlyMode) {
                                configForKey.forEach((k, obj) -> {
                                    @SuppressWarnings("unchecked")
                                    var m = (Map<String, Object>) obj;

                                    @SuppressWarnings("unchecked")
                                    List<Map<String, Object>> groups = (List<Map<String, Object>>) m.get(GROUPS_KEY);
                                    if (groups == null) return;

                                    for (var grp : groups) {
                                        String tgt = (String) grp.get("column");

                                        @SuppressWarnings("unchecked")
                                        List<Map<String, Object>> vals = (List<Map<String, Object>>) grp.get(VALUES_KEY);
                                        if (vals == null) continue;

                                        vals.stream()
                                                .flatMap(vo -> mapValueForRecord(row, vo, configFileName))
                                                .findFirst()
                                                .ifPresent(val -> outRow.put(tgt, val));
                                    }
                                });
                            }

                            customConfig.forEach((cust, obj) -> {
                                @SuppressWarnings("unchecked")
                                var m = (Map<String, Object>) obj;

                                String type = (String) m.get("mappingType");

                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> groups = (List<Map<String, Object>>) m.get(GROUPS_KEY);
                                if (groups == null) return;

                                @SuppressWarnings("unchecked")
                                List<String> allowed = (List<String>) m.get("columns");
                                Set<String> allowedSet = allowed == null ? Collections.emptySet() : new HashSet<>(allowed);

                                if ("one-hot".equalsIgnoreCase(type)) {
                                    boolean hit = groups.stream()
                                            .flatMap(g -> {
                                                @SuppressWarnings("unchecked")
                                                List<Map<String, Object>> values = (List<Map<String, Object>>) g.get(VALUES_KEY);
                                                return values == null ? Stream.empty() : values.stream();
                                            })
                                            .anyMatch(vo -> mapValueForRecord(row, vo, allowedSet, configFileName).findFirst().isPresent());
                                    outRow.put(cust, hit ? "1" : "0");
                                } else {
                                    for (var grp : groups) {
                                        @SuppressWarnings("unchecked")
                                        List<Map<String, Object>> vals = (List<Map<String, Object>>) grp.get(VALUES_KEY);
                                        if (vals == null) continue;

                                        Optional<String> mapped = vals.stream()
                                                .filter(v -> v.get("mapping") != null)
                                                .flatMap(vo -> mapValueForRecord(row, vo, allowedSet, configFileName))
                                                .findFirst();

                                        if (mapped.isPresent()) {
                                            outRow.put(cust, mapped.get());
                                            break;
                                        }
                                    }
                                }
                            });

                            try {
                                printer.printRecord(outputHeaders.stream().map(outRow::get).toArray());
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }

                    processedDatasets++;
                    int afterPercent = (int) Math.round((processedDatasets * 100.0) / totalDatasets);
                    jobs.update(
                            jobId,
                            afterPercent,
                            datasetName,
                            "Processed " + processedDatasets + "/" + totalDatasets + ": " + datasetName
                    );

                    logger.info("[parseFilesWithProgress] jobId={} Finished writing {} -> {}", jobId, datasetName, out);
                }
            }

            return "Files processed successfully.";
        } catch (Exception ex) {
            throw new IllegalStateException("Error processing files", ex);
        }
    }

    public String parseFiles(String configs,
                             Map<String, List<String>> fileMappings,
                             DataCleaningOptionsDTO cleanOpts) {
        try {
            logger.info("[parseFiles] fileMappings keys = {}", fileMappings.keySet());
            logger.info("[parseFiles] raw configs JSON = {}", configs);

            List<Map<String, Object>> configList =
                    objectMapper.readValue(configs, new TypeReference<>() {});
            logger.info("[parseFiles] parsed {} config objects", configList.size());

            // all custom_mapping entries across the whole configs payload
            Map<String, Object> customConfig = getAllConfigsForFile(configList);

            // for each element-file key -> list of dataset file names
            for (var entry : fileMappings.entrySet()) {
                String configFileName = entry.getKey();
                Map<String, Object> configForKey = getConfigForFile(configFileName, configList);

                boolean customOnlyMode = configForKey.isEmpty();

                if (customOnlyMode) {
                    logger.warn("[parseFiles] No config for key {} -> running in CUSTOM-ONLY mode (no original/std columns).",
                            configFileName);
                    if (customConfig.isEmpty()) {
                        logger.warn("[parseFiles] No custom_mapping present either -> nothing to output for key {}",
                                configFileName);
                    }
                }

                for (String datasetName : entry.getValue()) {
                    logger.info("[parseFiles] Processing dataset=\"{}\" with configKey=\"{}\"",
                            datasetName, configFileName);

                    Path input = Paths.get(fileService.getDatasetFilePath(datasetName));
                    if (!Files.exists(input)) {
                        logger.error("[parseFiles] Missing file {}", input);
                        continue;
                    }

                    // PASS 1: discover original columns (used for seeding + debug)
                    List<String> originalCols = new ArrayList<>();
                    dataProcessingService.streamRows(input, row -> {
                        if (originalCols.isEmpty()) originalCols.addAll(row.keySet());
                    });

                    // Build output headers
                    Set<String> headerSet = new LinkedHashSet<>();

                    // Standard mode: include configured output columns
                    if (!customOnlyMode) {
                        configForKey.values().forEach(cc -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> m = (Map<String, Object>) cc;

                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> groups = (List<Map<String, Object>>) m.get(GROUPS_KEY);
                            if (groups != null) {
                                groups.forEach(g -> headerSet.add((String) g.get("column")));
                            }
                        });
                    }

                    // Always include custom output columns
                    headerSet.addAll(customConfig.keySet());

                    List<String> outputHeaders = new ArrayList<>(headerSet);
                    logger.info("[parseFiles] customOnlyMode={}, outputHeaders({})={}",
                            customOnlyMode, outputHeaders.size(), outputHeaders);

                    // output file
                    String base = datasetName.replaceAll("\\.[^.]+$", "");
                    Path out = Paths.get(mappedDatasetFolder, "parsed_" + base + ".csv");
                    Files.createDirectories(out.getParent());

                    // track duplicates
                    Set<String> seen = new HashSet<>();

                    // numeric cleaning selection
                    Set<String> numericColsForCleaning = extractNumericColumns(cleanOpts);

                    try (BufferedWriter writer = Files.newBufferedWriter(out);
                         CSVPrinter printer = new CSVPrinter(writer,
                                 CSVFormat.newFormat(';')
                                         .withRecordSeparator(System.lineSeparator())
                                         .withHeader(outputHeaders.toArray(new String[0])))) {

                        dataProcessingService.streamRows(input, row -> {
                            // cleaning
                            if (cleanOpts != null) {
                                if (cleanOpts.isRemoveEmptyRows() && dataCleaningService.isEmptyRow(row)) return;

                                if (cleanOpts.isRemoveDuplicates()) {
                                    String key = dataCleaningService.dedupeKey(row);
                                    if (!seen.add(key)) return;
                                }
                                if (cleanOpts.isStandardizeDates())
                                    dataCleaningService.standardizeDatesInPlace(row, cleanOpts.getDateOutputFormat());

                                if (shouldStandardizeNumeric(cleanOpts, numericColsForCleaning))
                                    dataCleaningService.standardizeNumericInPlace(
                                            row, numericColsForCleaning, cleanOpts.getNumericMode()
                                    );
                            }

                            Map<String, String> outRow = new LinkedHashMap<>();
                            outputHeaders.forEach(h -> outRow.put(h, ""));

                            // Seed original values ONLY when not in custom-only mode
                            if (!customOnlyMode) {
                                row.forEach((k, v) -> {
                                    if (outRow.containsKey(k)) outRow.put(k, v);
                                });
                            }

                            // Standard mappings (only when configForKey exists)
                            if (!customOnlyMode) {
                                configForKey.forEach((k, obj) -> {
                                    @SuppressWarnings("unchecked")
                                    var m = (Map<String, Object>) obj;

                                    @SuppressWarnings("unchecked")
                                    List<Map<String, Object>> groups = (List<Map<String, Object>>) m.get(GROUPS_KEY);
                                    if (groups == null) return;

                                    for (var grp : groups) {
                                        String tgt = (String) grp.get("column");

                                        @SuppressWarnings("unchecked")
                                        List<Map<String, Object>> vals = (List<Map<String, Object>>) grp.get(VALUES_KEY);
                                        if (vals == null) continue;

                                        vals.stream()
                                                .flatMap(vo -> mapValueForRecord(row, vo, configFileName))
                                                .findFirst()
                                                .ifPresent(val -> outRow.put(tgt, val));
                                    }
                                });
                            }

                            // Custom mappings (always)
                            customConfig.forEach((cust, obj) -> {
                                @SuppressWarnings("unchecked")
                                var m = (Map<String, Object>) obj;

                                String type = (String) m.get("mappingType");

                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> groups = (List<Map<String, Object>>) m.get(GROUPS_KEY);
                                if (groups == null) return;

                                @SuppressWarnings("unchecked")
                                List<String> allowed = (List<String>) m.get("columns");
                                Set<String> allowedSet = allowed == null ? Collections.emptySet() : new HashSet<>(allowed);

                                if ("one-hot".equalsIgnoreCase(type)) {
                                    boolean hit = groups.stream()
                                            .flatMap(g -> {
                                                @SuppressWarnings("unchecked")
                                                List<Map<String, Object>> values = (List<Map<String, Object>>) g.get(VALUES_KEY);
                                                return values == null ? Stream.empty() : values.stream();
                                            })
                                            .anyMatch(vo -> mapValueForRecord(row, vo, allowedSet, configFileName).findFirst().isPresent());
                                    outRow.put(cust, hit ? "1" : "0");
                                } else {
                                    for (var grp : groups) {
                                        @SuppressWarnings("unchecked")
                                        List<Map<String, Object>> vals = (List<Map<String, Object>>) grp.get(VALUES_KEY);
                                        if (vals == null) continue;

                                        Optional<String> mapped = vals.stream()
                                                .filter(v -> v.get("mapping") != null)
                                                .flatMap(vo -> mapValueForRecord(row, vo, allowedSet, configFileName))
                                                .findFirst();

                                        if (mapped.isPresent()) {
                                            outRow.put(cust, mapped.get());
                                            break;
                                        }
                                    }
                                }
                            });

                            // write
                            try {
                                printer.printRecord(outputHeaders.stream().map(outRow::get).toArray());
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
                    }
                    logger.info("[parseFiles] Finished writing {} -> {}", datasetName, out);
                }
            }
            return "Files processed successfully.";
        } catch (Exception ex) {
            throw new IllegalStateException("Error processing files", ex);
        }
    }


    private Map<String, Object> getAllConfigsForFile(List<Map<String, Object>> configList) {
        Map<String, Object> combined = new HashMap<>();
        for (var cfg : configList) {
            for (var e : cfg.entrySet()) {
                @SuppressWarnings("unchecked")
                var details = (Map<String, Object>) e.getValue();
                if ("custom_mapping".equals(details.get("fileName"))) {
                    combined.put(e.getKey(), details);
                }
            }
        }
        if (combined.isEmpty()) logger.warn("No custom_mapping found");
        return combined;
    }

    private Map<String, Object> getConfigForFile(String key, List<Map<String, Object>> configList) {
        Map<String, Object> matched = new LinkedHashMap<>();

        for (var cfg : configList) {
            for (var e : cfg.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> details = (Map<String, Object>) e.getValue();

                if (matchesConfigKey(details, key)) {
                    matched.put(e.getKey(), details);
                }
            }
        }

        return matched;
    }

    private boolean matchesConfigKey(Map<String, Object> details, String key) {
        Object directFileName = details.get("fileName");

        if (key.equals(directFileName)) {
            return true;
        }
        return isConfigForElementFile(details, key);
    }

    @SuppressWarnings("unchecked")
    private boolean isConfigForElementFile(Map<String, Object> details, String key) {
        Object groupsObj = details.get(GROUPS_KEY);
        if (!(groupsObj instanceof List<?> groups)) {
            return false;
        }

        for (Object groupObj : groups) {
            if (!(groupObj instanceof Map<?, ?> group)) continue;

            Object valuesObj = group.get(VALUES_KEY);
            if (!(valuesObj instanceof List<?> values)) continue;

            for (Object valueObj : values) {
                if (!(valueObj instanceof Map<?, ?> valueMap)) continue;

                Object mappingsObj = valueMap.get("mapping");
                if (!(mappingsObj instanceof List<?> mappings)) continue;

                for (Object mappingObj : mappings) {
                    if (!(mappingObj instanceof Map<?, ?> mapping)) continue;

                    Object fileNameObj = mapping.get("fileName");
                    if (key.equals(fileNameObj)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private Stream<String> mapValueForRecord(Map<String, String> row,
                                             Map<String, Object> valueObj) {
        return mapValueForRecordInternal(row, valueObj, null, null);
    }

    private Stream<String> mapValueForRecord(Map<String, String> row,
                                             Map<String, Object> valueObj,
                                             Set<String> allowedGroupColumns) {
        return mapValueForRecordInternal(row, valueObj, allowedGroupColumns, null);
    }

    private Stream<String> mapValueForRecord(Map<String, String> row,
                                             Map<String, Object> valueObj,
                                             String sourceConfigFileName) {
        return mapValueForRecordInternal(row, valueObj, null, sourceConfigFileName);
    }

    private Stream<String> mapValueForRecord(Map<String, String> row,
                                             Map<String, Object> valueObj,
                                             Set<String> allowedGroupColumns,
                                             String sourceConfigFileName) {
        return mapValueForRecordInternal(row, valueObj, allowedGroupColumns, sourceConfigFileName);
    }

    private Stream<String> mapValueForRecordInternal(Map<String, String> row,
                                                     Map<String, Object> valueObj,
                                                     Set<String> allowedGroupColumnsOrNull,
                                                     String sourceConfigFileName) {
        String mappedName = Objects.toString(valueObj.get("name"), "");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mappings = (List<Map<String, Object>>) valueObj.get("mapping");
        if (mappings == null || mappings.isEmpty()) return Stream.empty();

        return mappings.stream()
                .filter(mapping -> {
                    String mappingFileName = (String) mapping.get("fileName");

                    // If mapping.fileName exists, only consider entries for the current source config file
                    if (sourceConfigFileName != null
                            && mappingFileName != null
                            && !sourceConfigFileName.equals(mappingFileName)) {
                        return false;
                    }

                    String groupColumn = (String) mapping.get("groupColumn");
                    if (groupColumn == null) return false;

                    if (allowedGroupColumnsOrNull != null && !allowedGroupColumnsOrNull.contains(groupColumn)) {
                        return false;
                    }

                    if (!row.containsKey(groupColumn)) {
                        return false;
                    }

                    String raw = row.get(groupColumn);
                    Object mv = mapping.get("value");

                    // range/date mapping
                    if (mv instanceof Map<?, ?>) {
                        @SuppressWarnings("unchecked")
                        var rm = (Map<String, Object>) mv;
                        return matchRangeOrDate(raw, rm);
                    }

                    // exact match OR typed passthrough
                    if (mv instanceof String s) {
                        if (isTypeMarker(s)) {
                            return matchesDeclaredType(raw, s);
                        }
                        return raw != null && raw.equals(s);
                    }

                    return false;
                })
                .map(mapping -> {
                    String groupColumn = (String) mapping.get("groupColumn");
                    String raw = row.get(groupColumn);
                    Object mv = mapping.get("value");

                    // typed passthrough -> write the raw source value
                    if (mv instanceof String s && isTypeMarker(s)) {
                        return raw;
                    }

                    return mappedName;
                });
    }

    private boolean isTypeMarker(String s) {
        if (s == null) return false;
        String t = s.trim().toLowerCase();
        return "integer".equals(t) || "double".equals(t) || "date".equals(t);
    }

    private boolean matchesDeclaredType(String raw, String declaredType) {
        if (raw == null || raw.isBlank()) return false;

        String t = declaredType.trim().toLowerCase();

        try {
            return switch (t) {
                case "integer" -> {
                    double d = NumberUtil.parseDouble(raw);
                    yield d == Math.rint(d);
                }
                case "double" -> {
                    NumberUtil.parseDouble(raw);
                    yield true;
                }
                case "date" -> DateUtil.parseDate(raw).isPresent();
                default -> false;
            };
        } catch (Exception ex) {
            return false;
        }
    }
    private boolean matchRangeOrDate(String raw, Map<String, Object> rm) {
        String type = Objects.toString(rm.get("type"), "").toLowerCase();

        if ("integer".equals(type) || "double".equals(type)) {
            double lo = ((Number) rm.get("minValue")).doubleValue();
            double hi = ((Number) rm.get("maxValue")).doubleValue();
            try {
                double val = NumberUtil.parseDouble(raw);
                return val >= lo && val <= hi;
            } catch (Exception ex) {
                return false;
            }
        }

        if ("date".equals(type)) {
            Optional<LocalDateTime> lo = DateUtil.parseDate(Objects.toString(rm.get("minValue"), null));
            Optional<LocalDateTime> hi = DateUtil.parseDate(Objects.toString(rm.get("maxValue"), null));
            Optional<LocalDateTime> dt = DateUtil.parseDate(raw);

            return lo.isPresent() && hi.isPresent() && dt.isPresent()
                    && !dt.get().isBefore(lo.get())
                    && !dt.get().isAfter(hi.get());
        }

        return false;
    }

    private boolean shouldStandardizeNumeric(DataCleaningOptionsDTO cleanOpts, Set<String> numericColsForCleaning) {
        if (cleanOpts == null) return false;
        if (!cleanOpts.isStandardizeNumeric()) return false;

        String mode = cleanOpts.getNumericMode();
        if (mode == null || mode.isBlank()) return false;

        return numericColsForCleaning != null && !numericColsForCleaning.isEmpty();
    }

    private Set<String> extractNumericColumns(DataCleaningOptionsDTO cleanOpts) {
        if (cleanOpts == null) return Collections.emptySet();
        List<String> ids = cleanOpts.getNumericColumns();
        if (ids == null || ids.isEmpty()) return Collections.emptySet();

        return ids.stream()
                .filter(Objects::nonNull)
                .map(s -> {
                    int idx = s.lastIndexOf(":::");
                    return idx >= 0 ? s.substring(idx + 3) : s;
                }).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
