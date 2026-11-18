package org.taniwha.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.taniwha.privacy.pooling.PrivatePoolingMetadata;
import org.taniwha.privacy.pooling.PrivatePoolingService;
import org.taniwha.security.FileFilter;
import org.taniwha.service.DataProcessingService;
import org.taniwha.service.DatasetElementExtractionService;
import org.taniwha.service.FileService;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PrivatePoolingIntegrationTest {
    @TempDir Path root;

    @Test void realMetadataAndSavedHistogramsRoundTripThroughTheHttpContract() throws Exception {
        Files.createDirectories(root.resolve("datasets"));
        Files.writeString(root.resolve("datasets/measurements.csv"), "Arm,Reading,Recorded on\n"
                + "North,10,2026-01-01\n".repeat(12) + "South,20,2026-01-02\n".repeat(12));
        var guard = new FileFilter();
        var files = new FileService(guard, root.toString());
        var rows = new DataProcessingService(guard);
        var metadata = new PrivatePoolingMetadata(new DatasetElementExtractionService(files, rows));
        var mapper = new ObjectMapper();
        var service = new PrivatePoolingService(root.toString(), files, rows, mapper, metadata, 4 * Math.log(2));
        var mvc = MockMvcBuilders.standaloneSetup(new PrivatePoolingController(service)).build();
        String request = """
                {"fileName":"measurements.csv","filters":{"operator":"AND","conditions":{"Arm":{
                "conditions":[{"type":"equal","filterType":"categorical","value":"North"}],"operators":[]}}}}
                """;
        String first = mvc.perform(post("/api/data/approximatePool").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.version").value(3)).andExpect(jsonPath("$.group").value("North"))
                .andExpect(jsonPath("$.privacyScope").value("protected-histograms-only"))
                .andExpect(jsonPath("$.features.Reading.domain.type").value("continuous"))
                .andExpect(jsonPath("$.features['Recorded on'].domain.type").value("date"))
                .andReturn().getResponse().getContentAsString();
        String repeated = mvc.perform(post("/api/data/approximatePool").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(repeated)).isEqualTo(mapper.readTree(first));
        var histogram = mapper.readTree(first).get("features").get("Reading");
        assertThat(histogram.get("counts").size()).isEqualTo(histogram.get("domain").get("boundaries").size());
        assertThat(mapper.readTree(first).has("rows")).isFalse();
    }
}
