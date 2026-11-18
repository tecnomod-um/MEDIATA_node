package org.taniwha.privacy.pooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.taniwha.dto.DatasetElementDTO;
import org.taniwha.dto.FileFilters;
import org.taniwha.security.FileFilter;
import org.taniwha.service.DataProcessingService;
import org.taniwha.service.DatasetElementExtractionService;
import org.taniwha.service.FileService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PrivatePoolingServiceTest {
    @TempDir Path root;
    private final FileService files = mock(FileService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final double allowance = 2 * Math.log(2);

    @Test void arbitraryColumnsUseMetadataAndNoiseIsReusedAcrossCopiesAndRestarts() throws Exception {
        Path data = dataset("sample.csv", "cohort,measurement,category\n" +
                "0,17,North\n".repeat(6) + "0,22,South\n".repeat(6) +
                "1,17,North\n".repeat(6) + "1,22,South\n".repeat(6));
        Path alias = Files.copy(data, data.resolveSibling("alias.csv"));
        when(files.getDatasetFilePath("alias.csv")).thenReturn(alias.toString());
        var service = service(allowance);
        assertThat(service.release(request("sample.csv", "measurement", "0"))).isEmpty();
        var first = service.release(request("sample.csv", "cohort", "0")).orElseThrow();
        var other = service.release(request("sample.csv", "cohort", "1")).orElseThrow();
        assertThat(first.version()).isEqualTo(3);
        assertThat(first.features()).containsOnlyKeys("measurement", "category");
        assertThat(first.features().get("measurement").counts()).hasSize(7);
        assertThat(first.features().get("measurement").noiseVariance()).isEqualTo(4);
        assertThat(first.rowEpsilon()).isEqualTo(allowance);
        var json = mapper.readTree(mapper.writeValueAsBytes(first));
        assertThat(json.get("budgetScope").asText()).isEqualTo("dataset-version");
        assertThat(service.release(request("sample.csv", "cohort", "99"))).isEmpty();
        var restarted = service(allowance);
        assertThat(restarted.release(request("sample.csv", "cohort", "0.0"))).contains(first);
        assertThat(restarted.release(request("alias.csv", "cohort", "0"))).contains(first);
        assertThat(restarted.release(request("sample.csv", "cohort", "1"))).contains(other);
        String original = Files.readString(data);
        Files.writeString(data, original + "0,24,North\n");
        var updated = service.release(request("sample.csv", "cohort", "0")).orElseThrow();
        assertThat(restarted.release(request("sample.csv", "cohort", "0"))).contains(updated);
        Files.writeString(data, original);
        assertThat(restarted.release(request("sample.csv", "cohort", "0"))).contains(first);
    }

    @Test void missingPublishedStateCannotGenerateAnotherNoiseDraw() throws Exception {
        Path data = dataset("sample.csv", "cohort,value\n" + "0,12\n".repeat(8));
        var service = service(allowance);
        service.release(request("sample.csv", "cohort", "0")).orElseThrow();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(data)));
        Files.delete(root.resolve("discovery-privacy/approximate-pooling/" + hash + ".json"));
        assertThatThrownBy(() -> service.release(request("sample.csv", "cohort", "0")))
                .isInstanceOfSatisfying(PrivatePoolingUnavailableException.class,
                        error -> assertThat(error.getCode()).isEqualTo("saved-release-unavailable"));
    }

    @Test void unrelatedDatasetsDoNotConsumeEachOthersAllowance() throws Exception {
        dataset("first.csv", "arm,reading,kind\n" + "0,31,North\n".repeat(8));
        dataset("second.csv", "arm,reading,kind,outcome\n" + "0,58,South,Improved\n".repeat(8));
        var first = service(allowance).release(request("first.csv", "arm", "0")).orElseThrow();
        var second = service(allowance).release(request("second.csv", "arm", "0")).orElseThrow();
        assertThat(first.rowEpsilon()).isLessThanOrEqualTo(allowance);
        assertThat(second.rowEpsilon()).isLessThanOrEqualTo(allowance);
        assertThat(service(allowance).release(request("first.csv", "arm", "0"))).contains(first);
    }

    @Test void categoricalGroupingIsSelectedByTheFilterWithoutAReservedColumnName() throws Exception {
        dataset("groups.csv", "Trial arm,Instrument reading\n" + "Treatment,5\n".repeat(8) + "Control,10\n".repeat(8));
        var released = service(allowance).release(request("groups.csv", "Trial arm", "Treatment", "categorical")).orElseThrow();
        assertThat(released.group()).isEqualTo("Treatment");
        assertThat(released.features()).containsOnlyKeys("Instrument reading");
    }

    @Test void anotherPartitionOrACompoundFilterCannotRerollTheSavedRelease() throws Exception {
        dataset("groups.csv", "partition,reading,arm\n" + "0,5,North\n".repeat(8) + "1,10,South\n".repeat(8));
        var service = service(allowance);
        var first = service.release(request("groups.csv", "partition", "0")).orElseThrow();
        assertThat(service.release(request("groups.csv", "arm", "North", "categorical"))).isEmpty();
        var compound = request("groups.csv", "partition", "0");
        compound.setFilters(Map.of("operator", "AND", "conditions", Map.of(
                "partition", firstFilter("0"), "reading", firstFilter("5"))));
        assertThat(service.release(compound)).isEmpty();
        assertThat(service.release(request("groups.csv", "partition", "0"))).contains(first);
        assertThat(service.release(request("groups.csv", "partition", "0", "categorical"))).isEmpty();
    }

    private Map<String, Object> firstFilter(String value) {
        return Map.of("conditions", List.of(Map.of("type", "equal", "filterType", "continuous", "value", value)), "operators", List.of());
    }

    @Test void publishedMetadataSupportsDatesAndExcludesUnreleasedRareLabels() throws Exception {
        dataset("dates.csv", "partition,Recorded on,label\n" + "0,2026-01-01,Common\n".repeat(6) +
                "1,2026-01-02,Common\n".repeat(6) + "1,2026-01-03,Unique\n");
        var released = service(allowance).release(request("dates.csv", "partition", "1")).orElseThrow();
        assertThat(released.features()).containsOnlyKeys("Recorded on");
        assertThat(released.features().get("Recorded on").domain().type()).isEqualTo("date");
        assertThat(released.features().get("Recorded on").domain().indexOf("2026-01-02")).isEqualTo(1);
    }

    @Test void preservesPreviouslyPublishedSummariesWithoutRecalibration() throws Exception {
        Path file = dataset("original.csv", "Partition,Reading,Score\n0,32,95\n");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        Path store = Files.createDirectories(root.resolve("discovery-privacy/approximate-pooling"));
        var domain = new PrivatePoolingDomain(IntStream.rangeClosed(0, 20).mapToObj(i -> 5.0 * i).toList(), null);
        var policy = new PrivatePoolingPolicy("original.csv", hash, "Partition", "continuous", List.of("0"), Map.of("Score", domain));
        var histogram = new PrivatePoolingHistogram(domain, java.util.Collections.nCopies(21, 5L), null);
        var original = new PrivatePoolingRelease(1, "0", Math.log(2), 4.0, Map.of("Score", histogram));
        mapper.writeValue(store.resolve(hash + ".json").toFile(), new PrivatePoolingSnapshot(policy, Map.of("0", original)));
        assertThat(service(allowance).release(request("original.csv", "Partition", "0"))).contains(original);
    }

    @Test void tinyAllowancesDoNotPublishPartialState() throws Exception {
        dataset("sample.csv", "cohort,value\n" + "0,12\n".repeat(8));
        assertThatThrownBy(() -> service(1e-12).release(request("sample.csv", "cohort", "0")))
                .isInstanceOfSatisfying(PrivatePoolingUnavailableException.class,
                        error -> assertThat(error.getCode()).isEqualTo("dataset-budget-exceeded"));
        try (var entries = Files.list(root.resolve("discovery-privacy/approximate-pooling"))) {
            assertThat(entries.map(path -> path.getFileName().toString()).toList()).containsExactly("release.lock");
        }
    }

    @Test void anEmptyFileDoesNotInventUnpublishedDomains() throws Exception {
        dataset("empty.csv", "cohort,value\n");
        assertThat(service(allowance).release(request("empty.csv", "cohort", "0"))).isEmpty();
    }

    @Test void integerMetadataPreservesScoresAndCategoricalDomainsAllowTheLiteralOther() throws Exception {
        var domain = PrivatePoolingDomainFactory.fromElement(new DatasetElementDTO("arbitrary score",
                List.of("integer", "min:0", "max:100"))).orElseThrow();
        assertThat(List.of("0", "5", "95", "100").stream().map(v -> domain.values().get(domain.indexOf(v))))
                .containsExactly(0.0, 5.0, 95.0, 100.0);
        var categories = new PrivatePoolingDomain(null, List.of("Other", "North"));
        categories.validate();
        assertThat(categories.indexOf("Other")).isZero();
        assertThat(categories.indexOf("Unlisted")).isEqualTo(2);
        assertThat(mapper.readValue(mapper.writeValueAsString(domain), PrivatePoolingDomain.class)).isEqualTo(domain);
    }

    private Path dataset(String name, String contents) throws Exception {
        Path file = Files.createDirectories(root.resolve("datasets")).resolve(name);
        Files.writeString(file, contents);
        when(files.getDatasetFilePath(name)).thenReturn(file.toString());
        return file;
    }

    private PrivatePoolingService service(double limit) {
        var processor = new DataProcessingService(new FileFilter());
        var metadata = new PrivatePoolingMetadata(new DatasetElementExtractionService(files, processor));
        return new PrivatePoolingService(root.toString(), files, processor, mapper, metadata, limit);
    }

    private FileFilters request(String file, String column, String value) {
        return request(file, column, value, "continuous");
    }

    private FileFilters request(String file, String column, String value, String type) {
        var request = new FileFilters();
        request.setFileName(file);
        request.setFilters(Map.of("operator", "AND", "conditions", Map.of(column,
                Map.of("conditions", List.of(Map.of("type", "equal", "filterType", type, "value", value)), "operators", List.of()))));
        return request;
    }
}
