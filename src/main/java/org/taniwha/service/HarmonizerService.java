package org.taniwha.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.taniwha.dto.DataCleaningOptionsDTO;
import org.taniwha.util.DateUtil;
import org.taniwha.util.NumberUtil;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;

@Service
public class HarmonizerService {
    private static final Logger logger = LoggerFactory.getLogger(HarmonizerService.class);

    private final DataProcessingService dataProcessingService;
    private final DataCleaningService    dataCleaningService;
    private final FileService            fileService;
    private final ObjectMapper           objectMapper;
    private final String                 mappedDatasetFolder;

    public HarmonizerService(DataProcessingService dataProcessingService,
                             DataCleaningService    dataCleaningService,
                             FileService            fileService)
    {
        this.dataProcessingService = dataProcessingService;
        this.dataCleaningService  = dataCleaningService;
        this.fileService          = fileService;
        this.objectMapper         = new ObjectMapper();
        this.mappedDatasetFolder  = fileService.getMappedDatasetsFolder();
    }

    public String parseFiles(String configs,
                             Map<String,List<String>> fileMappings,
                             DataCleaningOptionsDTO cleanOpts) {
        try {
            // 1) parse JSON configs
            List<Map<String,Object>> configList = objectMapper.readValue(
                    configs, new TypeReference<>() {}
            );
            Map<String,Object> customConfig = getAllConfigsForFile(configList);

            // 2) for each config key → list of dataset file names
            for (var entry : fileMappings.entrySet()) {
                String configFileName = entry.getKey();
                Map<String,Object> configForKey = getConfigForFile(configFileName, configList);
                if (configForKey.isEmpty()) {
                    logger.warn("No config for key {}", configFileName);
                    continue;
                }

                for (String datasetName : entry.getValue()) {
                    logger.info("Processing \"{}\" with config {}", datasetName, configFileName);
                    Path input = Paths.get(fileService.getDatasetFilePath(datasetName));
                    if (!Files.exists(input)) {
                        logger.error("Missing file {}", input);
                        continue;
                    }

                    // --- PASS 1: discover original columns from first row ---
                    List<String> originalCols = new ArrayList<>();
                    dataProcessingService.streamRows(input, row -> {
                        if (originalCols.isEmpty()) {
                            originalCols.addAll(row.keySet());
                        }
                    });

                    // build outputHeaders (config + custom)
                    Set<String> headerSet = new LinkedHashSet<>();
                    // config-driven
                    configForKey.values().forEach(cc -> {
                        Map<String,Object> m = (Map<String,Object>)cc;
                        Optional.ofNullable((List<String>)m.get("columns"))
                                .ifPresent(headerSet::addAll);
                        Optional.ofNullable((List<Map<String,Object>>)m.get("groups"))
                                .ifPresent(grps ->
                                        grps.forEach(g -> headerSet.add((String)g.get("column")))
                                );
                    });
                    // custom_mapping
                    customConfig.forEach((col,obj) -> {
                        var m = (Map<String,Object>)obj;
                        @SuppressWarnings("unchecked")
                        List<String> srcs = (List<String>)m.get("columns");
                        if (srcs.stream().anyMatch(originalCols::contains)) {
                            headerSet.add(col);
                        }
                    });
                    List<String> outputHeaders = new ArrayList<>(headerSet);

                    // output file
                    String base = datasetName.replaceAll("\\.[^.]+$", "");
                    Path out = Paths.get(mappedDatasetFolder, "parsed_" + base + ".csv");
                    Files.createDirectories(out.getParent());

                    // track duplicates
                    Set<String> seen = new HashSet<>();

                    // --- PASS 2: stream + clean + map + write ---
                    try (BufferedWriter writer = Files.newBufferedWriter(out);
                         CSVPrinter printer = new CSVPrinter(writer,
                                 CSVFormat.newFormat(';')
                                         .withRecordSeparator(System.lineSeparator())
                                         .withHeader(outputHeaders.toArray(new String[0]))))
                    {
                        dataProcessingService.streamRows(input, row -> {
                            // cleaning
                            if (cleanOpts != null) {
                                if (cleanOpts.isRemoveEmptyRows() && dataCleaningService.isEmptyRow(row)) {
                                    return;
                                }
                                if (cleanOpts.isRemoveDuplicates()) {
                                    String key = dataCleaningService.dedupeKey(row);
                                    if (!seen.add(key)) return;
                                }
                                if (cleanOpts.isStandardizeDates()) {
                                    dataCleaningService.standardizeDatesInPlace(
                                            row, cleanOpts.getDateOutputFormat());
                                }
                            }

                            // mapping → build outRow
                            Map<String,String> outRow = new LinkedHashMap<>();
                            outputHeaders.forEach(h -> outRow.put(h, ""));
                            // seed with original
                            row.forEach((k,v) -> {
                                if (outRow.containsKey(k)) outRow.put(k, v);
                            });

                            // standard mappings
                            configForKey.forEach((k,obj) -> {
                                var m = (Map<String,Object>)obj;
                                @SuppressWarnings("unchecked")
                                List<Map<String,Object>> groups =
                                        (List<Map<String,Object>>)m.get("groups");
                                for (var grp : groups) {
                                    String tgt = (String)grp.get("column");
                                    @SuppressWarnings("unchecked")
                                    List<Map<String,Object>> vals =
                                            (List<Map<String,Object>>)grp.get("values");
                                    vals.stream()
                                            .flatMap(vo -> mapValueForRecord(row, vo))
                                            .findFirst()
                                            .ifPresent(val -> outRow.put(tgt, val));
                                }
                            });

                            // custom mappings
                            customConfig.forEach((cust,obj) -> {
                                var m = (Map<String,Object>)obj;
                                String type = (String)m.get("mappingType");
                                @SuppressWarnings("unchecked")
                                List<Map<String,Object>> groups =
                                        (List<Map<String,Object>>)m.get("groups");
                                @SuppressWarnings("unchecked")
                                List<String> allowed = (List<String>)m.get("columns");
                                Set<String> allowedSet = new HashSet<>(allowed);

                                if ("one-hot".equalsIgnoreCase(type)) {
                                    boolean hit = groups.stream()
                                            .flatMap(g -> ((List<Map<String,Object>>)g.get("values")).stream())
                                            .anyMatch(vo ->
                                                    mapValueForRecord(row, vo, allowedSet).findFirst().isPresent()
                                            );
                                    outRow.put(cust, hit ? "1" : "0");
                                } else {
                                    for (var grp : groups) {
                                        @SuppressWarnings("unchecked")
                                        List<Map<String,Object>> vals =
                                                (List<Map<String,Object>>)grp.get("values");
                                        Optional<String> mapped = vals.stream()
                                                .filter(v -> v.get("mapping") != null)
                                                .flatMap(vo -> mapValueForRecord(row, vo, allowedSet))
                                                .findFirst();
                                        if (mapped.isPresent()) {
                                            outRow.put(cust, mapped.get());
                                            break;
                                        }
                                    }
                                }
                            });

                            // write it
                            try {
                                printer.printRecord(
                                        outputHeaders.stream()
                                                .map(outRow::get)
                                                .toArray()
                                );
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });

                        // printer auto-closes here
                    }

                    logger.info("Finished writing {} → {}", datasetName, out);
                }
            }

            return "Files processed successfully.";
        }
        catch(Exception ex) {
            logger.error("Error in parseFiles:", ex);
            throw new RuntimeException("Error processing files", ex);
        }
    }

    private Map<String,Object> getAllConfigsForFile(List<Map<String,Object>> configList) {
        Map<String,Object> combined = new HashMap<>();
        for (var cfg : configList) {
            for (var e : cfg.entrySet()) {
                var details = (Map<String,Object>)e.getValue();
                if ("custom_mapping".equals(details.get("fileName"))) {
                    combined.put(e.getKey(), details);
                }
            }
        }
        if (combined.isEmpty()) {
            logger.warn("No custom_mapping found");
        }
        return combined;
    }

    private Map<String,Object> getConfigForFile(String key,
                                                List<Map<String,Object>> configList)
    {
        for (var cfg : configList) {
            for (var e : cfg.entrySet()) {
                var details = (Map<String,Object>)e.getValue();
                if (key.equals(details.get("fileName"))) {
                    return cfg;
                }
            }
        }
        return Collections.emptyMap();
    }

    private Stream<String> mapValueForRecord(Map<String,String> record,
                                             Map<String,Object> valueObj)
    {
        String mappedName = (String)valueObj.get("name");
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> mappings =
                (List<Map<String,Object>>)valueObj.get("mapping");

        return mappings.stream()
                .filter(m -> {
                    String grpCol = (String)m.get("groupColumn");
                    if (!record.containsKey(grpCol)) return false;
                    String raw = record.get(grpCol);
                    Object mv = m.get("value");
                    if (mv instanceof Map<?,?>) {
                        var rm = (Map<String,Object>)mv;
                        String type = ((String)rm.get("type")).toLowerCase();
                        if ("integer".equals(type)||"double".equals(type)) {
                            double lo = ((Number)rm.get("minValue")).doubleValue();
                            double hi = ((Number)rm.get("maxValue")).doubleValue();
                            try {
                                double val = NumberUtil.parseDouble(raw);
                                return val>=lo && val<=hi;
                            } catch(Exception ex) {
                                return false;
                            }
                        } else if ("date".equals(type)) {
                            Optional<LocalDateTime> lo = DateUtil.parseDate((String)rm.get("minValue"));
                            Optional<LocalDateTime> hi = DateUtil.parseDate((String)rm.get("maxValue"));
                            Optional<LocalDateTime> dt = DateUtil.parseDate(raw);
                            return lo.isPresent() && hi.isPresent() && dt.isPresent()
                                    && !dt.get().isBefore(lo.get()) && !dt.get().isAfter(hi.get());
                        }
                    } else if (mv instanceof String) {
                        return raw.equals(mv);
                    }
                    return false;
                })
                .map(m->mappedName);
    }

    private Stream<String> mapValueForRecord(Map<String,String> record,
                                             Map<String,Object> valueObj,
                                             Set<String> allowedGroupColumns)
    {
        String mappedName = (String) valueObj.get("name");
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> mappings =
                (List<Map<String,Object>>)valueObj.get("mapping");

        return mappings.stream()
                .filter(mapping -> {
                    // 1) only look at mappings whose groupColumn is in our allowed set
                    String groupColumn = (String)mapping.get("groupColumn");
                    if (!allowedGroupColumns.contains(groupColumn)) return false;

                    // 2) then apply exactly the same logic you already had:
                    Object mv = mapping.get("value");
                    String raw = record.get(groupColumn);
                    if (mv instanceof Map<?,?>) {
                        var rm = (Map<String,Object>)mv;
                        String type = ((String)rm.get("type")).toLowerCase();
                        try {
                            if ("integer".equals(type) || "double".equals(type)) {
                                double lo = ((Number)rm.get("minValue")).doubleValue();
                                double hi = ((Number)rm.get("maxValue")).doubleValue();
                                double val = NumberUtil.parseDouble(raw);
                                return val>=lo && val<=hi;
                            } else if ("date".equals(type)) {
                                Optional<LocalDateTime> lo = DateUtil.parseDate((String)rm.get("minValue"));
                                Optional<LocalDateTime> hi = DateUtil.parseDate((String)rm.get("maxValue"));
                                Optional<LocalDateTime> dt = DateUtil.parseDate(raw);
                                return lo.isPresent() && hi.isPresent() && dt.isPresent()
                                        && !dt.get().isBefore(lo.get())
                                        && !dt.get().isAfter(hi.get());
                            }
                        } catch (Exception e) {
                            return false;
                        }
                    } else if (mv instanceof String) {
                        return raw.equals(mv);
                    }
                    return false;
                })
                .map(m -> mappedName);
    }
}
