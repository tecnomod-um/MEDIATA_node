package org.taniwha.service;

import org.apache.commons.math3.util.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.taniwha.dto.AnalyticsResponseDTO;
import org.taniwha.dto.FileFilters;
import org.taniwha.service.jobs.AnalyticsProcessingJobs;
import org.taniwha.service.jobs.DiscoveryJobRunner;
import org.taniwha.statistics.*;
import org.taniwha.util.AggregateCalculator;
import org.taniwha.util.AggregateDisclosure;
import org.taniwha.util.DateUtil;
import org.taniwha.privacy.DiscoveryDisclosureService;
import org.taniwha.privacy.DisclosureObservation;
import org.taniwha.privacy.DiscoveryQueryLedger;
import org.taniwha.util.NumberUtil;
import org.taniwha.util.FeatureTypeOverrides;

import jakarta.annotation.PreDestroy;
import java.io.BufferedInputStream;
import java.io.IOException;
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
import java.util.function.LongConsumer;
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
    private static final String NO_DATA_FOUND_MSG = "No data found in file: ";
    private static final int MIN_RECORDS_FOR_UNIQUE_FILTER = 10;
    private static final int MIN_DISCLOSURE_COUNT = AggregateDisclosure.MIN_COUNT - 1;
    private static final String SMALL_FEATURE_SUPPRESSION_REASON =
            "Suppressed for privacy: 5 or fewer observations";

    private static final long HUGE_BYTES_THRESHOLD = 1_000_000; // ~1MB
    private static final long HUGE_ROWS_THRESHOLD = 5_000;      // ~5k rows

    private static final Pattern XLSX_DIMENSION_LAST_ROW =
            Pattern.compile("dimension[^>]*ref=\"[A-Z]+\\d+:[A-Z]+(\\d+)\"");

    private final AggregateCalculator calculator = new AggregateCalculator(MIN_DISCLOSURE_COUNT + 1);
    private final DataProcessingService dataProcessingService;
    private final DiscoveryDisclosureService disclosure;
    private final FileService fileService;
    private final AnalyticsProcessingJobs jobs;
    private final ExecutorService discoveryJobExecutor;

    private static final String SUCCESS_MSG = "Data processed successfully";

    public AnalyticsService(DataProcessingService dataProcessingService,
                            FileService fileService,
                            AnalyticsProcessingJobs jobs, DiscoveryDisclosureService disclosure) {
        this.dataProcessingService = dataProcessingService;
        this.fileService = fileService;
        this.jobs = jobs;
        this.disclosure = disclosure;
        // Use a thread factory with meaningful thread names for debugging
        AtomicLong threadCounter = new AtomicLong(0);
        ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r);
            t.setName("analytics-discovery-" + threadCounter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.discoveryJobExecutor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32), threadFactory, new ThreadPoolExecutor.AbortPolicy());
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

    // Huge detection + async discovery job entry points

    /** Backend decides whether discovery should run in "progress mode". */
    public boolean isAnyHugeForDiscovery(List<String> fileNames) {
        try {
            for (String fn : fileNames) {
                Path p = Paths.get(fileService.getDatasetFilePath(fn));
                if (isHugeFile(p)) return true;
            }
            return false;
        } catch (Exception e) {
            // A failed estimate does not prevent profiling the file.
            logger.warn("Huge detection failed; falling back to normal processing");
            return false;
        }
    }

    /** Starts async processing and updates job progress via AnalyticsProcessingJobs. */
    public void startDiscoveryJob(String jobId, List<String> fileNames) {
        DiscoveryJobRunner.submit(jobId, discoveryJobExecutor, jobs, () -> processDatasetsOnDiskWithProgress(jobId, fileNames));
    }

    public void startFilteredDiscoveryJob(String jobId, List<FileFilters> requests) {
        List<FileFilters> files = List.copyOf(requests);
        DiscoveryJobRunner.submit(jobId, discoveryJobExecutor, jobs, () -> {
            List<AnalyticsResponseDTO> results = new ArrayList<>();
            for (int index = 0; index < files.size(); index++) {
                ensureJobActive(jobId);
                FileFilters file = files.get(index);
                jobs.update(jobId, percent(index, files.size()), file.getFileName());
                results.add(filterDataByName(file.getFileName(), file.getFilters(), file.getFeatureTypes()));
            }
            ensureJobActive(jobId);
            return results;
        });
    }
    private void ensureJobActive(String jobId) throws InterruptedException {
        if (jobs.isCanceled(jobId) || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Discovery job canceled");
        }
    }

    private boolean isHugeFile(Path p) throws IOException {
        long size = Files.size(p);
        if (size >= HUGE_BYTES_THRESHOLD) return true;

        long estRows = estimateRowsFast(p);
        return estRows >= HUGE_ROWS_THRESHOLD;
    }

    private long estimateRowsFast(Path p) throws IOException {
        String name = p.getFileName().toString().toLowerCase();
        if (name.endsWith(".csv")) return estimateCsvRows(p);
        if (name.endsWith(".xlsx")) return estimateXlsxRowsFromDimensions(p);
        return 0;
    }

    private long estimateCsvRows(Path p) throws IOException {
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

    private long estimateXlsxRowsFromDimensions(Path p) throws IOException {
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

    private List<AnalyticsResponseDTO> processDatasetsOnDiskWithProgress(String jobId, List<String> fileNames)
            throws IOException, InterruptedException {

        logger.info("Async discovery job {} set to process {} file(s)", jobId, fileNames.size());

        List<Long> estRowsPerFile = new ArrayList<>();
        long totalEstRows = 0L;

        for (String fn : fileNames) {
            ensureJobActive(jobId);

            Path p = Paths.get(fileService.getDatasetFilePath(fn));

            long est = Math.max(1L, estimateRowsFast(p));
            estRowsPerFile.add(est);
            totalEstRows += est;
        }

        if (totalEstRows <= 0L) totalEstRows = 1L;

        long doneRowsSoFar = 0L;
        List<AnalyticsResponseDTO> results = new ArrayList<>();

        for (int i = 0; i < fileNames.size(); i++) {
            ensureJobActive(jobId);

            String filename = fileNames.get(i);

            jobs.update(jobId, percent(doneRowsSoFar, totalEstRows), filename);

            AnalyticsResponseDTO result = processSingleFileOnDiskWithProgress(
                    jobId,
                    filename,
                    doneRowsSoFar,
                    totalEstRows
            );

            results.add(result);

            doneRowsSoFar += estRowsPerFile.get(i);
            jobs.update(jobId, percent(doneRowsSoFar, totalEstRows), filename);
        }

        ensureJobActive(jobId);
        jobs.update(jobId, 100, null);

        return results;
    }

    private void updateJobProgressForFile(String jobId,
                                          String filename,
                                          long doneRowsBeforeFile,
                                          long processedInFile,
                                          long totalEstRows) {
        long globalDone = doneRowsBeforeFile + processedInFile;
        jobs.update(jobId, Math.min(99, percent(globalDone, totalEstRows)), filename);
    }

    private AnalyticsResponseDTO processSingleFileOnDiskWithProgress(String jobId,
                                                                     String filename,
                                                                     long doneRowsBeforeFile,
                                                                     long totalEstRows)
            throws InterruptedException {
        try {
            return profileDataset(filename, null, Map.of(), rows -> {
                if (jobs.isCanceled(jobId)) throw new CancellationException("Discovery job canceled");
                if (rows % 200 == 0)
                    updateJobProgressForFile(jobId, filename, doneRowsBeforeFile, rows, totalEstRows);
            });
        } catch (CancellationException canceled) {
            throw new InterruptedException("Discovery job canceled");
        }
    }

    public AnalyticsResponseDTO processSingleFileOnDisk(String filename) {
        return filterDataByName(filename, null, Map.of());
    }

    public List<AnalyticsResponseDTO> processDatasetsOnDisk(List<String> fileNames) {
        return fileNames.stream().map(this::processSingleFileOnDisk).toList();
    }

    private AnalyticsResponseDTO blockedResponse(String fileName, Exception failure) {
        AnalyticsResponseDTO safe = new AnalyticsResponseDTO(failure instanceof DiscoveryQueryLedger.Blocked
                ? DiscoveryQueryLedger.BLOCKED : failure instanceof InvalidNumericConversion
                ? "This field cannot be converted to continuous without an explicit numerical mapping." : "Error processing statistics.");
        safe.setFileName(fileName);
        return safe;
    }

    private static final class InvalidNumericConversion extends RuntimeException {}

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

    private void suppressSmallFeatures(Map<String, List<Double>> continuousData,
                                       Map<String, Map<String, Integer>> categoricalData,
                                       Map<String, List<String>> dateData,
                                       List<OmittedFeatureStatistics> omittedFeatures) {
        continuousData.entrySet().removeIf(entry -> suppressFeatureIfNeeded(
                entry.getKey(), entry.getValue().size(), omittedFeatures));
        categoricalData.entrySet().removeIf(entry -> suppressFeatureIfNeeded(
                entry.getKey(), entry.getValue().values().stream().mapToLong(Integer::longValue).sum(), omittedFeatures));
        dateData.entrySet().removeIf(entry -> suppressFeatureIfNeeded(
                entry.getKey(), entry.getValue().size(), omittedFeatures));
    }

    private boolean suppressFeatureIfNeeded(String featureName, long nonMissingCount,
                                            List<OmittedFeatureStatistics> omittedFeatures) {
        String reason = nonMissingCount <= MIN_DISCLOSURE_COUNT ? SMALL_FEATURE_SUPPRESSION_REASON : null;
        if (reason == null) return false;

        // Do not expose exact counts or missingness for privacy-suppressed features.
        omittedFeatures.add(new OmittedFeatureStatistics(
                featureName, 0L, 0.0, 0L, reason));
        logger.debug("Omitting feature {}: {}", featureName, reason);
        return true;
    }

    private String determineFeatureType(String override, String value) {
        boolean isDate = DateUtil.parseDate(value).isPresent();
        if (isDate && !CATEGORICAL_TYPE.equals(override)) return DATE_TYPE;
        if (override != null) return override;
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
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .toList();

            Map.Entry<String, Integer> modeEntry = sortedEntries.get(0);
            String mode = modeEntry.getKey();
            int modeFrequency = modeEntry.getValue();
            double modePercentage = (double) modeFrequency / (totalRecords - missingValues) * 100;

            String secondMode = sortedEntries.size() > 1 ? sortedEntries.get(1).getKey() : null;
            Integer secondModeFrequency = secondMode != null ? valueMap.get(secondMode) : null;
            Double secondModePercentage = secondModeFrequency != null ? (double) secondModeFrequency / (totalRecords - missingValues) * 100 : null;

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
            // Discovery responses contain summaries only; do not return individual outlier values.
            List<Double> outliers = List.of();
            double mean = valueList.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
            double stddev = Math.sqrt(valueList.stream().mapToDouble(v -> Math.pow(v - mean, 2)).sum() / valueList.size());
            long missingValues = missingValueCounts.getOrDefault(key, 0L);
            double percentMissing = (double) missingValues / totalRecords * 100;
            List<Double> sorted = new ArrayList<>(valueList);
            Collections.sort(sorted);
            int cardinality = 1;
            for (int index = 1; index < sorted.size(); index++) {
                if (Double.compare(sorted.get(index - 1), sorted.get(index)) != 0) cardinality++;
            }
            double min = sorted.get(0);
            double max = sorted.get(sorted.size() - 1);
            double q1 = percentileFromSorted(sorted, 25);
            double median = percentileFromSorted(sorted, 50);
            double q3 = percentileFromSorted(sorted, 75);

            Map<String, Object> histogramInfo = generateHistogram(valueList, sorted);
            @SuppressWarnings("unchecked")
            List<Double> bins = (List<Double>) histogramInfo.get(BINS);
            @SuppressWarnings("unchecked")
            List<String> binRanges = (List<String>) histogramInfo.get(BIN_RANGES);

            ContinuousFeatureStatistics feature = new ContinuousFeatureStatistics(
                    key, valueList.size(), percentMissing, missingValues, cardinality,
                    min, max, mean, stddev, q1, median, q3, bins, binRanges, outliers);
            feature.setValueCounts(AggregateDisclosure.numericFrequencies(valueList));
            feature.setSummary(ContinuousSummary.calculateSorted(sorted, mean, stddev));
            feature.setHistogramBinWidth(((Number) histogramInfo.getOrDefault("binWidth", 0.0)).doubleValue());
            statisticsList.add(feature);
        });
        return statisticsList;
    }

    private List<DateFeatureStatistics> processDateData(Map<String, List<String>> dateData,
                                                        Map<String, Long> missingValueCounts,
                                                        long totalRecords) {
        List<DateFeatureStatistics> dateStatisticsList = new ArrayList<>();
        dateData.forEach((key, dateStringList) -> {
            List<LocalDate> dates = dateStringList.stream().map(LocalDate::parse).toList();
            List<Double> dateValues = dates.stream().mapToDouble(LocalDate::toEpochDay).boxed().toList();
            List<Double> sortedDates = new ArrayList<>(dateValues);
            Collections.sort(sortedDates);

            LocalDate earliestDate = dates.stream().min(LocalDate::compareTo).orElse(null);
            LocalDate latestDate = dates.stream().max(LocalDate::compareTo).orElse(null);

            long missingValues = missingValueCounts.getOrDefault(key, 0L);
            double percentMissing = (double) missingValues / totalRecords * 100;

            double meanEpoch = dateValues.stream().mapToDouble(v -> v).average().orElse(Double.NaN);
            LocalDate meanDate = LocalDate.ofEpochDay((long) meanEpoch);

            double medianEpoch = percentileFromSorted(sortedDates, 50);
            LocalDate medianDate = LocalDate.ofEpochDay((long) medianEpoch);

            double q1Epoch = percentileFromSorted(sortedDates, 25);
            LocalDate q1Date = LocalDate.ofEpochDay((long) q1Epoch);

            double q3Epoch = percentileFromSorted(sortedDates, 75);
            LocalDate q3Date = LocalDate.ofEpochDay((long) q3Epoch);

            double stdDevEpoch = Math.sqrt(dateValues.stream().mapToDouble(v -> Math.pow(v - meanEpoch, 2)).sum() / dateValues.size());

            List<String> outlierDates = List.of();

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

    private Map<String, Object> generateHistogram(List<Double> data, List<Double> sorted) {
        Map<String, Object> histogramInfo = new HashMap<>();
        if (data.isEmpty()) {
            histogramInfo.put(BINS, Collections.emptyList());
            histogramInfo.put(BIN_RANGES, Collections.emptyList());
            return histogramInfo;
        }

        double min = sorted.get(0);
        double max = sorted.get(sorted.size() - 1);
        if (max == min) {
            histogramInfo.put(BINS, Collections.singletonList((double) data.size()));
            histogramInfo.put(BIN_RANGES, Collections.singletonList(String.format(Locale.US, "[%f - %f]", min, max)));
            return histogramInfo;
        }

        double range = max - min;
        double q1 = percentileFromSorted(sorted, 25);
        double q3 = percentileFromSorted(sorted, 75);
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

        histogramInfo.put("binWidth", binWidth);
        histogramInfo.put(BINS, bins);
        histogramInfo.put(BIN_RANGES, binRanges);
        return histogramInfo;
    }

    private double percentileFromSorted(List<Double> sortedData, double percentile) {
        if (sortedData.isEmpty()) return 0;
        // Linear interpolation (R7), including the average of the middle two
        // observations for an even-sized cohort. Shared by quartiles/histograms.
        double position = (sortedData.size() - 1) * (percentile / 100.0);
        int lower = (int) Math.floor(position), upper = (int) Math.ceil(position);
        if (lower == upper) return sortedData.get(lower);
        double weight = position - lower;
        return sortedData.get(lower) * (1 - weight) + sortedData.get(upper) * weight;
    }

    public List<AnalyticsResponseDTO> filterMultipleFilesByName(List<FileFilters> requests) {
        return requests.stream().map(file -> filterDataByName(
                file.getFileName(), file.getFilters(), file.getFeatureTypes())).toList();
    }

    public AnalyticsResponseDTO filterDataByName(String fileName, Map<String, Object> filters) {
        return filterDataByName(fileName, filters, Map.of());
    }

    public AnalyticsResponseDTO filterDataByName(String fileName, Map<String, Object> filters,
                                                Map<String, String> featureTypes) {
        return profileDataset(fileName, filters, featureTypes, rows -> {});
    }

    private AnalyticsResponseDTO profileDataset(String fileName, Map<String, Object> filters,
                                                Map<String, String> featureTypes, LongConsumer progress) {
        AnalyticsResponseDTO response = new AnalyticsResponseDTO();
        response.setFileName(fileName);
        try {
            Map<String, String> overrides = FeatureTypeOverrides.validate(featureTypes);
            response.setFeatureTypeSummaryVersion(1);
            response.setFeatureTypes(overrides);
            Path path = Paths.get(fileService.getDatasetFilePath(fileName));
            DisclosureObservation observation = disclosure.begin(path);
            DiscoveryAccumulator buffer = new DiscoveryAccumulator();
            long[] scanned = {0};
            dataProcessingService.streamRows(path, row -> {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException("Discovery interrupted");
                progress.accept(++scanned[0]);
                boolean included = filters == null || dataProcessingService.matchesDiscoveryFilters(row, filters);
                observation.row(row, included);
                if (included) accumulateRecord(row, buffer, overrides, observation);
            });
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("Discovery interrupted");
            progress.accept(scanned[0]);
            if (buffer.rows == 0) {
                response.setMessage(filters == null ? NO_DATA_FOUND_MSG + fileName
                        : "No records match the provided filters for file: " + fileName);
            } else {
                completeProfile(buffer, response, observation);
            }
            return disclosure.release(observation, response);
        } catch (CancellationException canceled) {
            throw canceled;
        } catch (Exception failure) {
            logger.warn("Discovery statistics processing failed");
            return blockedResponse(fileName, failure);
        }
    }

    private void accumulateRecord(Map<String, String> row, DiscoveryAccumulator buffer,
                                  Map<String, String> overrides, DisclosureObservation observation) {
        buffer.rows++;
        observation.numeric(processRecord(row, buffer.continuous, buffer.categorical, buffer.dates, buffer.missing,
                overrides, buffer.combinations, observation));
    }

    private void completeProfile(DiscoveryAccumulator buffer, AnalyticsResponseDTO response, DisclosureObservation observation) {
        Map<String, List<Double>> continuousData = buffer.continuous;
        Map<String, Map<String, Integer>> categoricalData = buffer.categorical;
        Map<String, List<String>> dateData = buffer.dates;
        Map<String, Long> missingValueCounts = buffer.missing;
        List<OmittedFeatureStatistics> omittedFeatures = buffer.omitted;
        long totalRecords = buffer.rows;
        // Retain all-missing column identities so automatic mappings cannot
        // silently exclude a selected site's unavailable observations.
        for (String column : missingValueCounts.keySet()) {
            if (!continuousData.containsKey(column) && !categoricalData.containsKey(column) && !dateData.containsKey(column))
                omittedFeatures.add(new OmittedFeatureStatistics(column, 0, 0, 0, SMALL_FEATURE_SUPPRESSION_REASON));
        }
        suppressSmallFeatures(continuousData, categoricalData, dateData, omittedFeatures);
        filterCategoricalData(categoricalData, omittedFeatures, totalRecords);

        response.setContinuousFeatures(processContinuousData(continuousData, missingValueCounts, totalRecords));
        response.setCategoricalFeatures(processCategoricalData(categoricalData, missingValueCounts, totalRecords));
        response.setDateFeatures(processDateData(dateData, missingValueCounts, totalRecords));

        response.setOmittedFeatures(omittedFeatures);

        Set<String> numericColumns = new HashSet<>(continuousData.keySet());
        numericColumns.addAll(dateData.keySet());
        setNumericRelationships(response, observation.numericColumns(numericColumns));
        response.setChiSquareTest(calculator.calculateChiSquaredTest(categoricalData, buffer.combinations));
        response.setCategoricalPairSummaries(calculator.categoricalPairSummaries(categoricalData, buffer.combinations));

        response.setMessage(SUCCESS_MSG);
    }

    private void setNumericRelationships(AnalyticsResponseDTO response, Map<String, List<Double>> alignedData) {
        List<NumericPairSummary> summaries = calculator.numericPairSummaries(alignedData);
        response.setNumericPairSummaries(summaries);
        Map<String, Map<String, Double>> covariances = new HashMap<>(), correlations = new HashMap<>();
        for (NumericPairSummary pair : summaries) {
            if (pair.featureX().equals(pair.featureY())) continue;
            putSymmetric(covariances, pair.featureX(), pair.featureY(), pair.crossMoment() / (pair.count() - 1));
            double denominator = Math.sqrt(pair.m2X()) * Math.sqrt(pair.m2Y());
            if (denominator > 0) putSymmetric(correlations, pair.featureX(), pair.featureY(),
                    Math.max(-1, Math.min(1, pair.crossMoment() / denominator)));
        }
        response.setCovariances(covariances);
        response.setPearsonCorrelations(correlations);
        response.setSpearmanCorrelations(calculator.calculateSpearmanCorrelations(alignedData));
    }

    private void putSymmetric(Map<String, Map<String, Double>> matrix, String x, String y, double value) {
        matrix.computeIfAbsent(x, ignored -> new HashMap<>()).put(y, value);
        matrix.computeIfAbsent(y, ignored -> new HashMap<>()).put(x, value);
    }

    private Map<String, Double> processRecord(Map<String, String> rowData,
                               Map<String, List<Double>> continuousData,
                               Map<String, Map<String, Integer>> categoricalData,
                               Map<String, List<String>> dateData, Map<String, Long> missingValueCounts,
                               Map<String, String> overrides,
                               Map<Pair<String, String>, Map<Pair<String, String>, Integer>> categoryCombinationCounts,
                               DisclosureObservation observation) {

        Map<String, Double> numericRow = new HashMap<>();
        rowData.forEach((column, value) -> {
            String override = overrides.get(column);

            if (value == null || value.trim().isEmpty() || "NULL".equalsIgnoreCase(value.trim())) {
                missingValueCounts.merge(column, 1L, Long::sum);
                return;
            }

            String trimmedValue = value.trim();
            String featureType = determineFeatureType(override, trimmedValue);

            switch (featureType) {
                case DATE_TYPE -> {
                    DateUtil.parseDate(trimmedValue).ifPresent(parsedDate -> {
                        String date = parsedDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
                        dateData.computeIfAbsent(column, k -> new ArrayList<>()).add(date);
                        observation.exactCell(column, date);
                        numericRow.put(column, (double) parsedDate.toLocalDate().toEpochDay());
                    });
                    continuousData.remove(column);
                    categoricalData.remove(column);
                }
                case CONTINUOUS_TYPE -> {
                    try {
                        double parsedNumber = NumberUtil.parseDouble(trimmedValue);
                        Double numericValue = parsedNumber;
                        continuousData.computeIfAbsent(column, k -> new ArrayList<>()).add(numericValue);
                        numericRow.put(column, numericValue);
                    } catch (NumberFormatException | ParseException e) {
                        if (CONTINUOUS_TYPE.equals(override)) {
                            // Encounter-order category codes differ across nodes and have no
                            // valid numerical interpretation for moments or pooled relationships.
                            throw new InvalidNumericConversion();
                        } else {
                            logger.debug("A numeric value could not be parsed");
                        }
                    }
                    categoricalData.remove(column);
                    dateData.remove(column);
                }
                case CATEGORICAL_TYPE -> {
                    categoricalData.computeIfAbsent(column, k -> new HashMap<>())
                            .merge(trimmedValue, 1, Integer::sum);
                    observation.exactCell(column, trimmedValue);
                    continuousData.remove(column);
                    dateData.remove(column);
                }
                default -> logger.error("Unsupported record type detected for column: {}", column);
            }
        });

        List<String> categoricalColumns = new ArrayList<>(categoricalData.keySet());
        for (int i = 0; i < categoricalColumns.size(); i++) {
            for (int j = i + 1; j < categoricalColumns.size(); j++) {
                String col1 = categoricalColumns.get(i);
                String col2 = categoricalColumns.get(j);
                String val1 = Objects.toString(rowData.get(col1), "").trim();
                String val2 = Objects.toString(rowData.get(col2), "").trim();
                if (!val1.isEmpty() && !"NULL".equalsIgnoreCase(val1) &&
                        !val2.isEmpty() && !"NULL".equalsIgnoreCase(val2)) {

                    Pair<String, String> columnPair = new Pair<>(col1, col2);
                    Pair<String, String> valuePair = new Pair<>(val1, val2);

                    categoryCombinationCounts.computeIfAbsent(columnPair, k -> new HashMap<>())
                            .merge(valuePair, 1, Integer::sum);
                }
            }
        }
        return numericRow;
    }
}
