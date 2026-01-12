package org.taniwha.service;

import org.apache.commons.math3.util.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.taniwha.dto.AnalyticsResponseDTO;
import org.taniwha.dto.FileFilters;
import org.taniwha.statistics.*;
import org.taniwha.util.AggregateCalculator;
import org.taniwha.util.DateUtil;
import org.taniwha.util.NumberUtil;

import jakarta.annotation.PreDestroy;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParseException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Service
public class AnalyticsService {

    private static final Logger logger = LoggerFactory.getLogger(AnalyticsService.class);

    private static final String CONTINUOUS_TYPE = "continuous";
    private static final String CATEGORICAL_TYPE = "categorical";
    private static final String DATE_TYPE = "date";
    private static final String BINS = "bins";
    private static final String BIN_RANGES = "binRanges";
    private static final int MIN_RECORDS_FOR_UNIQUE_FILTER = 10;

    // Huge detection thresholds (tune as needed)
    private static final long HUGE_BYTES_THRESHOLD = 1_000_000; // ~1MB
    private static final long HUGE_ROWS_THRESHOLD = 5_000;      // ~5k rows

    private static final Pattern XLSX_DIMENSION_LAST_ROW =
            Pattern.compile("dimension[^>]*ref=\"[A-Z]+\\d+:[A-Z]+(\\d+)\"");

    private final AggregateCalculator calculator = new AggregateCalculator();
    private final DataProcessingService dataProcessingService;
    private final FileService fileService;
    private final AnalyticsProcessingJobs jobs;
    private final ExecutorService discoveryJobExecutor;

    private static final String successMsg = "Data processed successfully";

    public AnalyticsService(DataProcessingService dataProcessingService,
                            FileService fileService,
                            AnalyticsProcessingJobs jobs) {
        this.dataProcessingService = dataProcessingService;
        this.fileService = fileService;
        this.jobs = jobs;
        // Use a thread factory with meaningful thread names for debugging
        AtomicLong threadCounter = new AtomicLong(0);
        ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r);
            t.setName("analytics-discovery-" + threadCounter.incrementAndGet());
            t.setDaemon(true); // Daemon threads won't prevent JVM shutdown
            return t;
        };
        this.discoveryJobExecutor = Executors.newCachedThreadPool(threadFactory);
    }

    @PreDestroy
    public void shutdown() {
        logger.info("Shutting down AnalyticsService executor...");
        discoveryJobExecutor.shutdown();
        try {
            if (!discoveryJobExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                logger.warn("Executor did not terminate in time, forcing shutdown");
                discoveryJobExecutor.shutdownNow();
                if (!discoveryJobExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    logger.error("Executor did not terminate after forced shutdown");
                }
            }
        } catch (InterruptedException e) {
            logger.error("Interrupted while waiting for executor shutdown", e);
            discoveryJobExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // Huge detection + async discovery job entry points
    // -------------------------------------------------------------------------

    /** Backend decides whether discovery should run in "progress mode". */
    public boolean isAnyHugeForDiscovery(List<String> fileNames) {
        try {
            for (String fn : fileNames) {
                Path p = Paths.get(fileService.getDatasetFilePath(fn));
                if (isHugeFile(p)) return true;
            }
            return false;
        } catch (Exception e) {
            // If detection fails, do NOT force progress mode (keep old behavior).
            logger.warn("Huge detection failed; falling back to normal processing: {}", e.getMessage());
            return false;
        }
    }

    /** Starts async processing and updates job progress via AnalyticsProcessingJobs. */
    public void startDiscoveryJob(String jobId, List<String> fileNames) {
        discoveryJobExecutor.submit(() -> {
            try {
                List<AnalyticsResponseDTO> results = processDatasetsOnDiskWithProgress(jobId, fileNames);
                jobs.complete(jobId, results);
            } catch (Exception e) {
                logger.error("Discovery async job failed jobId={}", jobId, e);
                jobs.fail(jobId, "Error processing files: " + (e.getMessage() == null ? "Unknown error" : e.getMessage()));
            }
        });
    }

    private boolean isHugeFile(Path p) throws Exception {
        long size = Files.size(p);
        if (size >= HUGE_BYTES_THRESHOLD) return true;

        long estRows = estimateRowsFast(p);
        return estRows >= HUGE_ROWS_THRESHOLD;
    }

    private long estimateRowsFast(Path p) throws Exception {
        String name = p.getFileName().toString().toLowerCase();
        if (name.endsWith(".csv")) return estimateCsvRows(p);
        if (name.endsWith(".xlsx")) return estimateXlsxRowsFromDimensions(p);
        return 0;
    }

    private long estimateCsvRows(Path p) throws Exception {
        // Count '\n' quickly; subtract 1 header line. Approximate but good enough for progress.
        try (InputStream in = new BufferedInputStream(Files.newInputStream(p))) {
            byte[] buf = new byte[64 * 1024];
            long lines = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                for (int i = 0; i < n; i++) {
                    if (buf[i] == (byte) '\n') lines++;
                }
            }
            return Math.max(0, lines - 1);
        }
    }

    private long estimateXlsxRowsFromDimensions(Path p) throws Exception {
        long maxRow = 0;
        try (ZipFile zip = new ZipFile(p.toFile())) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry entry = en.nextElement();
                String n = entry.getName();
                if (!n.startsWith("xl/worksheets/sheet") || !n.endsWith(".xml")) continue;

                byte[] head;
                try (InputStream in = zip.getInputStream(entry)) {
                    head = in.readNBytes(64 * 1024); // dimension is near top
                }
                String s = new String(head, StandardCharsets.UTF_8);
                Matcher m = XLSX_DIMENSION_LAST_ROW.matcher(s);
                if (m.find()) {
                    long last = Long.parseLong(m.group(1));
                    if (last > maxRow) maxRow = last;
                }
            }
        }
        return Math.max(0, maxRow - 1);
    }

    private int percent(long done, long total) {
        if (total <= 0) return 0;
        double p = (done * 100.0) / total;
        return (int) Math.max(0, Math.min(100, Math.round(p)));
    }

    private List<AnalyticsResponseDTO> processDatasetsOnDiskWithProgress(String jobId, List<String> fileNames) throws Exception {
        logger.info("Async discovery job {} set to process {} file(s)", jobId, fileNames.size());

        // Build estimated "work" from row estimates (fallback to 1 per file)
        List<Path> paths = new ArrayList<>();
        List<Long> estRowsPerFile = new ArrayList<>();
        long totalEstRows = 0;

        for (String fn : fileNames) {
            Path p = Paths.get(fileService.getDatasetFilePath(fn));
            paths.add(p);
            long est = Math.max(1, estimateRowsFast(p));
            estRowsPerFile.add(est);
            totalEstRows += est;
        }
        if (totalEstRows <= 0) totalEstRows = 1;

        long doneRowsSoFar = 0;
        List<AnalyticsResponseDTO> results = new ArrayList<>();

        for (int i = 0; i < fileNames.size(); i++) {
            String filename = fileNames.get(i);
            Path path = paths.get(i);

            jobs.update(jobId, percent(doneRowsSoFar, totalEstRows), filename);

            long finalDoneRowsSoFar = doneRowsSoFar;
            long finalTotalEstRows = totalEstRows;
            AnalyticsResponseDTO r = processSingleFileOnDiskWithProgress(
                    filename,
                    path,
                    processedInFile -> {
                        long globalDone = finalDoneRowsSoFar + processedInFile;
                        jobs.update(jobId, percent(globalDone, finalTotalEstRows), filename);
                    }
            );

            results.add(r);

            // Advance global progress by this file's estimate (keeps progress monotonic even if estimate is off)
            doneRowsSoFar += estRowsPerFile.get(i);
            jobs.update(jobId, percent(doneRowsSoFar, totalEstRows), filename);
        }

        jobs.update(jobId, 100, null);
        return results;
    }

    @FunctionalInterface
    private interface RowProgress {
        void onRowsProcessed(long rowsProcessedInThisFile);
    }

    private AnalyticsResponseDTO processSingleFileOnDiskWithProgress(String filename, Path path, RowProgress progress) {
        AnalyticsResponseDTO response = new AnalyticsResponseDTO();
        response.setFileName(filename);

        Map<String, List<Double>> continuousData = new ConcurrentHashMap<>();
        Map<String, Map<String, Integer>> categoricalData = new ConcurrentHashMap<>();
        Map<String, List<String>> dateData = new ConcurrentHashMap<>();
        Map<String, Long> missingValueCounts = new ConcurrentHashMap<>();
        List<OmittedFeatureStatistics> omittedFeatures = new CopyOnWriteArrayList<>();
        Map<Pair<String, String>, Map<Pair<String, String>, Integer>> comboCounts = new ConcurrentHashMap<>();
        Map<String, Map<String, Double>> forcedMapping = new ConcurrentHashMap<>();

        try {
            final AtomicLong rows = new AtomicLong(0);

            dataProcessingService.streamRows(path, rowData -> {
                long r = rows.incrementAndGet();

                // Reduce overhead: update every N rows
                if ((r % 200) == 0) progress.onRowsProcessed(r);

                processRecord(
                        rowData,
                        continuousData,
                        categoricalData,
                        dateData,
                        missingValueCounts,
                        Optional.empty(),
                        Optional.empty(),
                        comboCounts,
                        forcedMapping
                );
            });

            progress.onRowsProcessed(rows.get());

            long totalRows =
                    continuousData.values().stream().mapToLong(List::size).sum()
                            + categoricalData.values().stream()
                            .mapToLong(m -> m.values().stream().mapToInt(i -> i).sum()).sum()
                            + dateData.values().stream().mapToLong(List::size).sum()
                            + missingValueCounts.values().stream().mapToLong(Long::longValue).sum();
            if (totalRows == 0) {
                response.setMessage("No data found in file: " + filename);
                return response;
            }

            filterCategoricalData(categoricalData, omittedFeatures, totalRows);

            response.setContinuousFeatures(processContinuousData(continuousData, missingValueCounts, totalRows));
            response.setCategoricalFeatures(processCategoricalData(categoricalData, missingValueCounts, totalRows));
            response.setDateFeatures(processDateData(dateData, missingValueCounts, totalRows));
            response.setOmittedFeatures(omittedFeatures);

            response.setCovariances(calculator.calculateCovariances(continuousData));
            response.setPearsonCorrelations(calculator.calculatePearsonCorrelations(continuousData));
            response.setSpearmanCorrelations(calculator.calculateSpearmanCorrelations(continuousData));
            response.setChiSquareTest(calculator.calculateChiSquaredTest(categoricalData, comboCounts));

            response.setMessage("File processed successfully: " + filename);

        } catch (Exception e) {
            logger.error("Error processing file {}", filename, e);
            response.setMessage("Error processing file " + filename + ": " + e.getMessage());
        }

        return response;
    }

    @Async
    public CompletableFuture<AnalyticsResponseDTO> processSingleFileOnDisk(String filename) {
        AnalyticsResponseDTO response = new AnalyticsResponseDTO();
        response.setFileName(filename);

        Map<String, List<Double>> continuousData = new ConcurrentHashMap<>();
        Map<String, Map<String, Integer>> categoricalData = new ConcurrentHashMap<>();
        Map<String, List<String>> dateData = new ConcurrentHashMap<>();
        Map<String, Long> missingValueCounts = new ConcurrentHashMap<>();
        List<OmittedFeatureStatistics> omittedFeatures = new CopyOnWriteArrayList<>();
        Map<Pair<String, String>, Map<Pair<String, String>, Integer>> comboCounts = new ConcurrentHashMap<>();
        Map<String, Map<String, Double>> forcedMapping = new ConcurrentHashMap<>();

        try {
            Path path = Paths.get(fileService.getDatasetFilePath(filename));
            dataProcessingService.streamRows(path, rowData -> processRecord(
                    rowData,
                    continuousData,
                    categoricalData,
                    dateData,
                    missingValueCounts,
                    Optional.empty(),
                    Optional.empty(),
                    comboCounts,
                    forcedMapping
            ));

            long totalRows =
                    continuousData.values().stream().mapToLong(List::size).sum()
                            + categoricalData.values().stream()
                            .mapToLong(m -> m.values().stream().mapToInt(i -> i).sum()).sum()
                            + dateData.values().stream().mapToLong(List::size).sum()
                            + missingValueCounts.values().stream().mapToLong(Long::longValue).sum();
            if (totalRows == 0) {
                response.setMessage("No data found in file: " + filename);
                return CompletableFuture.completedFuture(response);
            }

            filterCategoricalData(categoricalData, omittedFeatures, totalRows);

            response.setContinuousFeatures(processContinuousData(continuousData, missingValueCounts, totalRows));
            response.setCategoricalFeatures(processCategoricalData(categoricalData, missingValueCounts, totalRows));
            response.setDateFeatures(processDateData(dateData, missingValueCounts, totalRows));
            response.setOmittedFeatures(omittedFeatures);

            response.setCovariances(calculator.calculateCovariances(continuousData));
            response.setPearsonCorrelations(calculator.calculatePearsonCorrelations(continuousData));
            response.setSpearmanCorrelations(calculator.calculateSpearmanCorrelations(continuousData));
            response.setChiSquareTest(calculator.calculateChiSquaredTest(categoricalData, comboCounts));

            response.setMessage("File processed successfully: " + filename);
        } catch (Exception e) {
            logger.error("Error processing file {}", filename, e);
            response.setMessage("Error processing file " + filename + ": " + e.getMessage());
        }

        return CompletableFuture.completedFuture(response);
    }

    public List<AnalyticsResponseDTO> processDatasetsOnDisk(List<String> fileNames) {
        logger.info("Set to process {} file(s)", fileNames.size());
        List<CompletableFuture<AnalyticsResponseDTO>> futures = fileNames.stream()
                .map(this::processSingleFileOnDisk).toList();

        List<AnalyticsResponseDTO> results = new ArrayList<>();
        for (CompletableFuture<AnalyticsResponseDTO> future : futures) {
            try {
                results.add(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Thread was interrupted", e);
                results.add(new AnalyticsResponseDTO("Thread interrupted while processing file."));
            } catch (ExecutionException e) {
                logger.error("Execution error in processing file", e);
                results.add(new AnalyticsResponseDTO("Error: " + e.getMessage()));
            }
        }
        logger.info("All files processed, returning {} results", results.size());
        return results;
    }

    @Async
    public CompletableFuture<AnalyticsResponseDTO> recalculateFeatureAsTypeFromDisk(String fileName, String featureName, String featureType) {
        AnalyticsResponseDTO response = new AnalyticsResponseDTO();
        response.setFileName(fileName);
        Map<Pair<String, String>, Map<Pair<String, String>, Integer>> categoryCombinationCounts = new ConcurrentHashMap<>();

        try {
            String fullPath = fileService.getDatasetFilePath(fileName);
            List<Map<String, String>> records = dataProcessingService.extractDataFromPath(Paths.get(fullPath));
            if (records.isEmpty()) {
                response.setMessage("No data found in file: " + fileName);
                return CompletableFuture.completedFuture(response);
            }
            processData(records, Optional.of(featureName), Optional.of(featureType), response, categoryCombinationCounts);
            response.setMessage(successMsg);
        } catch (Exception e) {
            String errMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            if (errMsg.toLowerCase().contains("valuemap") && errMsg.toLowerCase().contains("null"))
                response.setMessage("This field cannot be converted to categorical.");
            else
                response.setMessage("Error processing file: " + errMsg);
        }
        return CompletableFuture.completedFuture(response);
    }

    // -------------------------------------------------------------------------
    // Existing logic below (unchanged)
    // -------------------------------------------------------------------------

    private void filterCategoricalData(Map<String, Map<String, Integer>> categoricalData,
                                       List<OmittedFeatureStatistics> omittedFeatures,
                                       long totalRecords) {
        categoricalData.entrySet().removeIf(entry -> {
            String columnName = entry.getKey();
            Map<String, Integer> columnData = entry.getValue();

            long uniqueValuesCount = columnData.size();
            long totalValuesCount = columnData.values().stream().mapToInt(Integer::intValue).sum();
            double uniqueValuesPercentage = (double) uniqueValuesCount / totalValuesCount * 100;

            long nullCount = totalRecords - totalValuesCount;
            boolean isTooManyUniqueValues = totalValuesCount > MIN_RECORDS_FOR_UNIQUE_FILTER && uniqueValuesPercentage >= 50.0;
            boolean exceedsMaxCategories = uniqueValuesCount > 200;
            boolean isLikelyUUID = columnData.keySet().stream().anyMatch(value -> value.matches("[0-9a-fA-F-]{36}"));

            String reason = null;
            if (isTooManyUniqueValues) reason = "Too many unique values (" + uniqueValuesPercentage + "%)";
            else if (exceedsMaxCategories) reason = "Exceeds maximum categories (more than 200)";
            else if (isLikelyUUID) reason = "Likely contains UUIDs";

            if (reason != null) {
                omittedFeatures.add(new OmittedFeatureStatistics(
                        columnName,
                        totalValuesCount,
                        (double) nullCount / totalRecords * 100,
                        nullCount,
                        reason
                ));
                logger.debug("Omitting column {}: {}", columnName, reason);
            }

            return reason != null;
        });
    }

    private String determineFeatureType(Optional<String> overrideFeatureName,
                                        Optional<String> overrideFeatureType,
                                        String column,
                                        String value) {
        boolean isDate = DateUtil.parseDate(value).isPresent();
        if (overrideFeatureName.isPresent() && getOriginalFeatureName(overrideFeatureName.get()).equals(column)) {
            if (isDate && !overrideFeatureType.orElse("unknown").equalsIgnoreCase(CATEGORICAL_TYPE))
                return DATE_TYPE;
            return overrideFeatureType.orElse("unknown").toLowerCase();
        }
        if (isDate) return DATE_TYPE;
        if (value.matches("-?\\d+([.,]\\d+)?")) return CONTINUOUS_TYPE;
        return CATEGORICAL_TYPE;
    }

    private List<FeatureStatistics> processCategoricalData(Map<String, Map<String, Integer>> categoricalData,
                                                           Map<String, Long> missingValueCounts,
                                                           long totalRecords) {
        List<FeatureStatistics> statisticsList = new ArrayList<>();
        categoricalData.forEach((key, valueMap) -> {
            long missingValues = missingValueCounts.getOrDefault(key, 0L);
            double percentMissing = (double) missingValues / totalRecords * 100;

            List<Map.Entry<String, Integer>> sortedEntries = valueMap.entrySet().stream()
                    .sorted((entry1, entry2) -> entry2.getValue().compareTo(entry1.getValue()))
                    .toList();

            Map.Entry<String, Integer> modeEntry = sortedEntries.get(0);
            String mode = modeEntry.getKey();
            int modeFrequency = modeEntry.getValue();
            double modePercentage = (double) modeFrequency / totalRecords * 100;

            String secondMode = sortedEntries.size() > 1 ? sortedEntries.get(1).getKey() : null;
            Integer secondModeFrequency = secondMode != null ? valueMap.get(secondMode) : null;
            Double secondModePercentage = secondModeFrequency != null ? (double) secondModeFrequency / totalRecords * 100 : null;

            statisticsList.add(new CategoricalFeatureStatistics(
                    key,
                    totalRecords - missingValues,
                    percentMissing,
                    missingValues,
                    valueMap.size(),
                    mode,
                    modeFrequency,
                    modePercentage,
                    secondMode,
                    secondModeFrequency,
                    secondModePercentage,
                    valueMap
            ));
        });
        return statisticsList;
    }

    private List<FeatureStatistics> processContinuousData(Map<String, List<Double>> continuousData,
                                                          Map<String, Long> missingValueCounts,
                                                          long totalRecords) {
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
            @SuppressWarnings("unchecked")
            List<Double> bins = (List<Double>) histogramInfo.get(BINS);
            @SuppressWarnings("unchecked")
            List<String> binRanges = (List<String>) histogramInfo.get(BIN_RANGES);

            statisticsList.add(new ContinuousFeatureStatistics(
                    key, valueList.size(), percentMissing, missingValues, valueList.size(),
                    min, max, mean, stddev, q1, median, q3, bins, binRanges, outliers
            ));
        });
        return statisticsList;
    }

    private List<DateFeatureStatistics> processDateData(Map<String, List<String>> dateData,
                                                        Map<String, Long> missingValueCounts,
                                                        long totalRecords) {
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

            List<String> outlierDates = outliers.stream()
                    .map(outlier -> LocalDate.ofEpochDay(outlier.longValue()).format(DateTimeFormatter.ISO_LOCAL_DATE))
                    .toList();

            DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE;

            dateStatisticsList.add(new DateFeatureStatistics(
                    key,
                    dateStringList.size(),
                    percentMissing,
                    missingValues,
                    earliestDate != null ? earliestDate.format(formatter) : "N/A",
                    latestDate != null ? latestDate.format(formatter) : "N/A",
                    dateStringList.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting())),
                    outlierDates,
                    meanDate.format(formatter),
                    stdDevEpoch,
                    medianDate.format(formatter),
                    q1Date.format(formatter),
                    q3Date.format(formatter)
            ));
        });
        return dateStatisticsList;
    }

    private Map<String, Object> generateHistogram(List<Double> data) {
        Map<String, Object> histogramInfo = new HashMap<>();
        if (data.isEmpty()) {
            histogramInfo.put(BINS, Collections.emptyList());
            histogramInfo.put(BIN_RANGES, Collections.emptyList());
            return histogramInfo;
        }

        double min = Collections.min(data);
        double max = Collections.max(data);
        if (max == min) {
            histogramInfo.put(BINS, Collections.singletonList((double) data.size()));
            histogramInfo.put(BIN_RANGES, Collections.singletonList(String.format(Locale.US, "[%f - %f]", min, max)));
            return histogramInfo;
        }

        double range = max - min;
        double q1 = getPercentile(data, 25);
        double q3 = getPercentile(data, 75);
        double iqr = q3 - q1;

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

    public List<AnalyticsResponseDTO> filterMultipleFilesByName(List<FileFilters> fileFiltersList) {
        List<CompletableFuture<AnalyticsResponseDTO>> futures = new ArrayList<>();

        for (FileFilters ff : fileFiltersList) {
            String fileName = ff.getFileName();
            Map<String, Object> filters = ff.getFilters();
            if (filters == null) futures.add(processSingleFileOnDisk(fileName));
            else futures.add(filterDataByName(fileName, filters));
        }

        List<AnalyticsResponseDTO> results = new ArrayList<>();
        for (CompletableFuture<AnalyticsResponseDTO> future : futures) {
            try {
                results.add(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Thread was interrupted while filtering multiple files", e);
                results.add(new AnalyticsResponseDTO("Thread interrupted while filtering multiple files."));
            } catch (ExecutionException e) {
                logger.error("Execution error while filtering multiple files", e);
                results.add(new AnalyticsResponseDTO("Error: " + e.getMessage()));
            }
        }
        return results;
    }

    @Async
    public CompletableFuture<AnalyticsResponseDTO> filterDataByName(String fileName, Map<String, Object> filters) {
        AnalyticsResponseDTO response = new AnalyticsResponseDTO();
        response.setFileName(fileName);
        Map<Pair<String, String>, Map<Pair<String, String>, Integer>> categoryCombinationCounts = new ConcurrentHashMap<>();

        try {
            String fullPath = fileService.getDatasetFilePath(fileName);
            List<Map<String, String>> records = dataProcessingService.extractFilteredDataFromPath(Paths.get(fullPath), filters);

            if (records.isEmpty()) {
                response.setMessage("No records match the provided filters for file: " + fileName);
                return CompletableFuture.completedFuture(response);
            }

            processData(records, Optional.empty(), Optional.empty(), response, categoryCombinationCounts);
            response.setMessage(successMsg);
        } catch (Exception e) {
            logger.error("Error filtering file by name {}", fileName, e);
            response.setMessage("Error filtering file " + fileName + ": " + e.getMessage());
        }
        return CompletableFuture.completedFuture(response);
    }

    private String getOriginalFeatureName(String featureName) {
        if (featureName == null) return null;
        int idx = featureName.indexOf(" (");
        return idx != -1 ? featureName.substring(0, idx) : featureName;
    }

    private void processData(List<Map<String, String>> records,
                             Optional<String> overrideFeatureName,
                             Optional<String> overrideFeatureType,
                             AnalyticsResponseDTO response,
                             Map<Pair<String, String>, Map<Pair<String, String>, Integer>> categoryCombinationCounts) {

        Map<String, Map<String, Double>> forcedMapping = new ConcurrentHashMap<>();

        Map<String, List<Double>> continuousData = new ConcurrentHashMap<>();
        Map<String, Map<String, Integer>> categoricalData = new ConcurrentHashMap<>();
        Map<String, List<String>> dateData = new ConcurrentHashMap<>();
        Map<String, Long> missingValueCounts = new ConcurrentHashMap<>();
        List<OmittedFeatureStatistics> omittedFeatures = new CopyOnWriteArrayList<>();

        records.stream().forEach(rowData ->
                processRecord(rowData, continuousData, categoricalData, dateData, missingValueCounts,
                        overrideFeatureName, overrideFeatureType, categoryCombinationCounts, forcedMapping)
        );

        filterCategoricalData(categoricalData, omittedFeatures, records.size());

        long totalRecords = records.size();
        if (overrideFeatureName.isPresent() && overrideFeatureType.isPresent()) {
            String normalizedKey = getOriginalFeatureName(overrideFeatureName.get());
            String type = overrideFeatureType.get().toLowerCase();
            logger.debug("processData override branch: normalizedKey = {}, type = {}", normalizedKey, type);

            if (type.equals(CONTINUOUS_TYPE)) {
                if (dateData.containsKey(normalizedKey)) {
                    response.setDateFeatures(processDateData(
                            Collections.singletonMap(normalizedKey, dateData.get(normalizedKey)),
                            missingValueCounts, totalRecords));
                } else {
                    response.setContinuousFeatures(processContinuousData(
                            Collections.singletonMap(normalizedKey, continuousData.get(normalizedKey)),
                            missingValueCounts, totalRecords));
                }
            } else if (type.equals(CATEGORICAL_TYPE)) {
                response.setCategoricalFeatures(processCategoricalData(
                        Collections.singletonMap(normalizedKey, categoricalData.get(normalizedKey)),
                        missingValueCounts, totalRecords));
            }
        } else {
            response.setContinuousFeatures(processContinuousData(continuousData, missingValueCounts, totalRecords));
            response.setCategoricalFeatures(processCategoricalData(categoricalData, missingValueCounts, totalRecords));
            response.setDateFeatures(processDateData(dateData, missingValueCounts, totalRecords));
        }

        response.setOmittedFeatures(omittedFeatures);

        response.setCovariances(calculator.calculateCovariances(continuousData));
        response.setPearsonCorrelations(calculator.calculatePearsonCorrelations(continuousData));
        response.setSpearmanCorrelations(calculator.calculateSpearmanCorrelations(continuousData));
        response.setSpearmanCorrelations(calculator.calculateSpearmanCorrelations(continuousData));
        response.setChiSquareTest(calculator.calculateChiSquaredTest(categoricalData, categoryCombinationCounts));

        response.setMessage(successMsg);
    }

    private void processRecord(Map<String, String> rowData,
                               Map<String, List<Double>> continuousData,
                               Map<String, Map<String, Integer>> categoricalData,
                               Map<String, List<String>> dateData,
                               Map<String, Long> missingValueCounts,
                               Optional<String> overrideFeatureName,
                               Optional<String> overrideFeatureType,
                               Map<Pair<String, String>, Map<Pair<String, String>, Integer>> categoryCombinationCounts,
                               Map<String, Map<String, Double>> forcedMapping) {

        rowData.forEach((column, value) -> {
            String effectiveColumn;
            if (overrideFeatureName.isPresent() &&
                    getOriginalFeatureName(overrideFeatureName.get()).equals(getOriginalFeatureName(column))) {
                effectiveColumn = getOriginalFeatureName(column);
            } else effectiveColumn = column;

            if (value == null || value.trim().isEmpty() || "NULL".equalsIgnoreCase(value.trim())) {
                missingValueCounts.merge(effectiveColumn, 1L, Long::sum);
                return;
            }

            String trimmedValue = value.trim();
            String featureType = determineFeatureType(overrideFeatureName, overrideFeatureType, column, trimmedValue);

            switch (featureType) {
                case DATE_TYPE -> {
                    DateUtil.parseDate(trimmedValue).ifPresent(parsedDate -> dateData
                            .computeIfAbsent(effectiveColumn, k -> new CopyOnWriteArrayList<>())
                            .add(parsedDate.format(DateTimeFormatter.ISO_LOCAL_DATE)));
                    continuousData.remove(effectiveColumn);
                    categoricalData.remove(effectiveColumn);
                }
                case CONTINUOUS_TYPE -> {
                    try {
                        double parsedNumber = NumberUtil.parseDouble(trimmedValue);
                        continuousData.computeIfAbsent(effectiveColumn, k -> new CopyOnWriteArrayList<>()).add(parsedNumber);
                    } catch (NumberFormatException | ParseException e) {
                        if (overrideFeatureType.isPresent() &&
                                overrideFeatureType.get().equalsIgnoreCase(CONTINUOUS_TYPE)) {
                            Map<String, Double> mapping = forcedMapping.computeIfAbsent(effectiveColumn, k -> new ConcurrentHashMap<>());
                            Double numericValue = mapping.get(trimmedValue);
                            if (numericValue == null) {
                                numericValue = mapping.size() + 1.0;
                                mapping.put(trimmedValue, numericValue);
                            }
                            continuousData.computeIfAbsent(effectiveColumn, k -> new CopyOnWriteArrayList<>()).add(numericValue);
                        } else {
                            logger.debug("Error parsing number from string: {}", trimmedValue);
                        }
                    }
                    categoricalData.remove(effectiveColumn);
                    dateData.remove(effectiveColumn);
                }
                case CATEGORICAL_TYPE -> {
                    categoricalData.computeIfAbsent(effectiveColumn, k -> new ConcurrentHashMap<>())
                            .merge(trimmedValue, 1, Integer::sum);
                    continuousData.remove(effectiveColumn);
                    dateData.remove(effectiveColumn);
                }
                default -> logger.error("Unsupported record type detected for column: {}", column);
            }
        });

        List<String> categoricalColumns = new CopyOnWriteArrayList<>(categoricalData.keySet());
        for (int i = 0; i < categoricalColumns.size(); i++) {
            for (int j = i + 1; j < categoricalColumns.size(); j++) {
                String col1 = categoricalColumns.get(i);
                String col2 = categoricalColumns.get(j);
                String val1 = rowData.getOrDefault(col1, "").trim();
                String val2 = rowData.getOrDefault(col2, "").trim();
                if (!val1.isEmpty() && !"NULL".equalsIgnoreCase(val1) &&
                        !val2.isEmpty() && !"NULL".equalsIgnoreCase(val2)) {

                    Pair<String, String> columnPair = new Pair<>(col1, col2);
                    Pair<String, String> valuePair = new Pair<>(val1, val2);

                    categoryCombinationCounts.computeIfAbsent(columnPair, k -> new ConcurrentHashMap<>())
                            .merge(valuePair, 1, Integer::sum);
                }
            }
        }
    }
}
