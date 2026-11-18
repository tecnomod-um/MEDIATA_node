package org.taniwha.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.taniwha.dto.FileFilters;
import org.taniwha.privacy.pooling.PrivatePoolingRelease;
import org.taniwha.privacy.pooling.PrivatePoolingService;
import org.taniwha.privacy.pooling.PrivatePoolingUnavailableException;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PrivatePoolingControllerTest {
    private final PrivatePoolingService service = mock(PrivatePoolingService.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new PrivatePoolingController(service)).build();
    }

    @Test void returnsTheProtectedReleaseWithItsAccountingScope() throws Exception {
        when(service.release(any(FileFilters.class))).thenReturn(Optional.of(
                new PrivatePoolingRelease(2, "0", Math.log(2), null, Map.of())));
        mvc.perform(post("/api/data/approximatePool").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"sample.csv\",\"filters\":{}}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.budgetScope").value("dataset-version"));
    }

    @Test void explainsAnUnsupportedDatasetOrFilter() throws Exception {
        when(service.release(any(FileFilters.class))).thenReturn(Optional.empty());
        mvc.perform(post("/api/data/approximatePool").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"sample.csv\",\"filters\":{}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("unsupported-dataset-filter"))
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    @Test void reportsTheSpecificReleaseConstraint() throws Exception {
        when(service.release(any(FileFilters.class))).thenThrow(new PrivatePoolingUnavailableException(
                "dataset-budget-exceeded", "The configured allowance is too small."));
        mvc.perform(post("/api/data/approximatePool").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"sample.csv\",\"filters\":{}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("dataset-budget-exceeded"))
                .andExpect(jsonPath("$.detail").value("The configured allowance is too small."));
    }

    @Test void keepsStorageFailuresSeparateFromUnsupportedRequests() throws Exception {
        when(service.release(any(FileFilters.class))).thenThrow(new IOException("Storage unavailable"));
        mvc.perform(post("/api/data/approximatePool").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"sample.csv\",\"filters\":{}}"))
                .andExpect(status().isServiceUnavailable());
    }
}
