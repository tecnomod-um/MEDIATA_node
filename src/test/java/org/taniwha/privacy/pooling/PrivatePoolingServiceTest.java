package org.taniwha.privacy.pooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.taniwha.dto.FileFilters;
import org.taniwha.security.FileFilter;
import org.taniwha.service.DataProcessingService;
import org.taniwha.service.FileService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrivatePoolingServiceTest {
    @TempDir Path root;

    @Test void releasesAnnotatedClusterDataWithoutConfigurationAndReusesNoise() throws Exception {
        Path data = Files.createDirectories(root.resolve("datasets")).resolve("sample.csv");
        Files.writeString(data, "Cluster ID,Age,Sex\n0,15,Female\n0,22,Male\n");
        Path store = root.resolve("discovery-privacy/approximate-pooling");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(data)));
        var mapper = new ObjectMapper();
        FileService files = mock(FileService.class);
        when(files.getDatasetFilePath("sample.csv")).thenReturn(data.toString());
        Path alias = Files.copy(data, data.resolveSibling("alias.csv"));
        when(files.getDatasetFilePath("alias.csv")).thenReturn(alias.toString());
        var processor = new DataProcessingService(new FileFilter());
        var service = new PrivatePoolingService(root.toString(), files, processor, mapper, 2 * Math.log(2));
        assertThat(service.release(request("sample.csv", "Age", "0"))).isEmpty();
        var first = service.release(request("sample.csv", "Cluster ID", "0")).orElseThrow();
        var empty = service.release(request("sample.csv", "Cluster ID", "1")).orElseThrow();
        assertThat(first.version()).isEqualTo(2);
        assertThat(first.features().get("Age").counts()).hasSize(25);
        assertThat(first.features().get("Age").noiseVariance()).isEqualTo(20.0 / 9);
        assertThat(first.features().get("Sex").noiseVariance()).isEqualTo(80.0 / 9);
        assertThat(empty.features()).containsOnlyKeys("Age", "Sex");
        assertThat(first.rowEpsilon()).isEqualTo(2 * Math.log(2));
        var serialized = mapper.readTree(mapper.writeValueAsBytes(first));
        assertThat(serialized.get("privacyUnit").asText()).isEqualTo("one-row-add-remove");
        assertThat(serialized.get("privacyScope").asText()).isEqualTo("protected-histograms-only");
        assertThat(serialized.get("budgetScope").asText()).isEqualTo("dataset-version");
        assertThat(service.release(request("sample.csv", "Cluster ID", "1000"))).isEmpty();
        var restarted = new PrivatePoolingService(root.toString(), files, processor, mapper, 2 * Math.log(2));
        assertThat(restarted.release(request("sample.csv", "Cluster ID", "0.0"))).contains(first);
        assertThat(restarted.release(request("sample.csv", "Cluster ID", "1"))).contains(empty);
        assertThat(restarted.release(request("alias.csv", "Cluster ID", "0"))).contains(first);

        String contents = Files.readString(data);
        Files.writeString(data, contents + "0,24,Female\n");
        var updated = service.release(request("sample.csv", "Cluster ID", "0")).orElseThrow();
        assertThat(restarted.release(request("sample.csv", "Cluster ID", "0"))).contains(updated);
        Files.writeString(data, contents);
        assertThat(service.release(request("sample.csv", "Cluster ID", "0"))).contains(first);
        Files.delete(store.resolve(hash + ".json"));
        assertThatThrownBy(() -> service.release(request("sample.csv", "Cluster ID", "0")))
                .isInstanceOfSatisfying(PrivatePoolingUnavailableException.class,
                        error -> assertThat(error.getCode()).isEqualTo("saved-release-unavailable"));

        Path second = Files.writeString(data.resolveSibling("second.csv"), "Cluster ID,Age,Sex\n0,30,Female\n");
        when(files.getDatasetFilePath("second.csv")).thenReturn(second.toString());
        assertThat(restarted.release(request("second.csv", "Cluster ID", "0"))).isPresent();
    }

    @Test void preservesAnExistingReleaseWithoutChargingItAgainstOtherDatasets() throws Exception {
        Path file = Files.createDirectories(root.resolve("datasets")).resolve("original.csv");
        Files.writeString(file, "Cluster,Age,Barthel Total\n0,32,95\n");
        Path next = Files.writeString(file.resolveSibling("new.csv"), "Cluster,Age\n0,47\n");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        Path store = Files.createDirectories(root.resolve("discovery-privacy/approximate-pooling"));
        var legacyBarthel = new PrivatePoolingDomain(
                java.util.stream.IntStream.rangeClosed(0, 20).mapToObj(i -> 5.0 * i).toList(), null);
        var policy = new PrivatePoolingPolicy("original.csv", hash, "Cluster", "continuous", List.of("0"),
                Map.of("Barthel Total", legacyBarthel));
        var histogram = new PrivatePoolingHistogram(legacyBarthel, java.util.Collections.nCopies(21, 5L), null);
        var original = new PrivatePoolingRelease(1, "0", Math.log(2), 4.0, Map.of("Barthel Total", histogram));
        var mapper = new ObjectMapper();
        mapper.writeValue(store.resolve(hash + ".json").toFile(), new PrivatePoolingSnapshot(policy, Map.of("0", original)));

        FileService files = mock(FileService.class);
        when(files.getDatasetFilePath("original.csv")).thenReturn(file.toString());
        when(files.getDatasetFilePath("new.csv")).thenReturn(next.toString());
        var service = new PrivatePoolingService(root.toString(), files, new DataProcessingService(new FileFilter()),
                mapper, Math.log(2));
        var nextRelease = service.release(request("new.csv", "Cluster", "0")).orElseThrow();
        assertThat(nextRelease.rowEpsilon()).isEqualTo(Math.log(2));
        assertThat(service.release(request("original.csv", "Cluster", "0"))).contains(original);
        assertThat(Double.parseDouble(Files.readString(store.resolve(hash + ".published")))).isEqualTo(Math.log(2));
    }

    @Test void permitsNewAnnotatedDatasetsAfterThePreviousDatasetUsedItsFullAllowance() throws Exception {
        Path folder = Files.createDirectories(root.resolve("datasets"));
        Path first = Files.writeString(folder.resolve("first.csv"), "Cluster,Age,Sex\n0,31,Female\n");
        Path second = Files.writeString(folder.resolve("second.csv"), "Cluster,Age,Sex,Outcome\n0,58,Male,Improved\n");
        FileService files = mock(FileService.class);
        when(files.getDatasetFilePath("first.csv")).thenReturn(first.toString());
        when(files.getDatasetFilePath("second.csv")).thenReturn(second.toString());
        double limit = 2 * Math.log(2);
        var processor = new DataProcessingService(new FileFilter());
        var mapper = new ObjectMapper();
        var service = new PrivatePoolingService(root.toString(), files, processor, mapper, limit);
        var initial = service.release(request("first.csv", "Cluster", "0")).orElseThrow();
        assertThat(initial.rowEpsilon()).isEqualTo(limit);

        var restarted = new PrivatePoolingService(root.toString(), files, processor, mapper, limit);
        var next = restarted.release(request("second.csv", "Cluster", "0")).orElseThrow();
        assertThat(next.rowEpsilon()).isEqualTo(limit);
        assertThat(restarted.release(request("first.csv", "Cluster", "0"))).contains(initial);
        assertThat(restarted.release(request("second.csv", "Cluster", "0"))).contains(next);
        try (var markers = Files.list(root.resolve("discovery-privacy/approximate-pooling"))) {
            assertThat(markers.filter(path -> path.toString().endsWith(".published")).count()).isEqualTo(2);
        }
    }

    @Test void rejectsAnInsufficientDatasetAllowanceWithoutPublishingPartialState() throws Exception {
        Path file = Files.createDirectories(root.resolve("datasets")).resolve("sample.csv");
        Files.writeString(file, "Cluster,Age,Sex\n0,31,Female\n");
        FileService files = mock(FileService.class);
        when(files.getDatasetFilePath("sample.csv")).thenReturn(file.toString());
        var service = new PrivatePoolingService(root.toString(), files, new DataProcessingService(new FileFilter()),
                new ObjectMapper(), Math.log(2));

        assertThatThrownBy(() -> service.release(request("sample.csv", "Cluster", "0")))
                .isInstanceOfSatisfying(PrivatePoolingUnavailableException.class,
                        error -> assertThat(error.getCode()).isEqualTo("dataset-budget-exceeded"));
        try (var entries = Files.list(root.resolve("discovery-privacy/approximate-pooling"))) {
            assertThat(entries.map(path -> path.getFileName().toString()).toList()).containsExactly("release.lock");
        }
    }

    @Test void anEmptyAnnotatedCsvStillReleasesThePublicHistogramShape() throws Exception {
        Path file = Files.createDirectories(root.resolve("datasets")).resolve("empty.csv");
        Files.writeString(file, "Cluster,Age\n");
        FileService files = mock(FileService.class);
        when(files.getDatasetFilePath("empty.csv")).thenReturn(file.toString());
        var service = new PrivatePoolingService(root.toString(), files, new DataProcessingService(new FileFilter()),
                new ObjectMapper(), Math.log(2));
        assertThat(service.release(request("empty.csv", "Cluster", "0")).orElseThrow().features())
                .containsOnlyKeys("Age");
    }

    @Test void keepsBinDefinitionsPublicAndFixed() {
        var categories = new PrivatePoolingDomain(null, List.of("North", "South"));
        assertThat(categories.indexOf("Unlisted")).isEqualTo(2);
        assertThat(categories.indexOf("")).isEqualTo(3);
        var numeric = new PrivatePoolingDomain(List.of(10.0, 20.0, 30.0), null);
        assertThat(numeric.indexOf("0")).isZero();
        assertThat(numeric.indexOf("20")).isEqualTo(1);
        assertThat(numeric.indexOf("100")).isEqualTo(1);
        assertThat(numeric.indexOf("NULL")).isEqualTo(2);
    }

    @Test void representsTheEndpointsAndTopScoresSeparatelyInNewProtectedReleases() throws Exception {
        var policy = ClusterProfileSchema.fromHeaders(List.of("Cluster", "Barthel Total"), "scores.csv", "hash");
        var domain = policy.features().get("Barthel Total");
        domain.validate();
        assertThat(domain.size()).isEqualTo(22);
        assertThat(List.of("0", "5", "95", "100").stream().map(value -> domain.values().get(domain.indexOf(value))))
                .containsExactly(0.0, 5.0, 95.0, 100.0);
        var mapper = new ObjectMapper();
        assertThat(mapper.readValue(mapper.writeValueAsString(domain), PrivatePoolingDomain.class)).isEqualTo(domain);
    }

    private FileFilters request(String fileName, String clusterColumn, String group) {
        var request = new FileFilters();
        request.setFileName(fileName);
        request.setFilters(Map.of("operator", "AND", "conditions", Map.of(clusterColumn,
                Map.of("conditions", List.of(Map.of("type", "equal", "filterType", "continuous", "value", group)),
                        "operators", List.of()))));
        return request;
    }
}
