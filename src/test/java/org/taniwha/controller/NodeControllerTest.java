package org.taniwha.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.taniwha.dto.NodeAuthRequestDTO;
import org.taniwha.dto.NodeValidationRequestDTO;
import org.taniwha.dto.NodeValidationResponseDTO;
import org.taniwha.model.NodeMetadata;
import org.taniwha.service.NodeAccessService;
import org.taniwha.util.JwtTokenUtil;

import java.util.Collections;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NodeControllerTest {

    private MockMvc mvc;
    private NodeAccessService nodeAccessService;
    private JwtTokenUtil jwtTokenUtil;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        nodeAccessService = mock(NodeAccessService.class);
        jwtTokenUtil = mock(JwtTokenUtil.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new NodeController(nodeAccessService, jwtTokenUtil))
                .build();
    }

    @Test
    void healthCheck_returnsOK() throws Exception {
        mvc.perform(get("/node/health"))
                .andExpect(status().isOk())
                .andExpect(content().string("OK"));
    }

    @Test
    void metadata_present_returns200AndBody() throws Exception {
        NodeMetadata meta = new NodeMetadata();
        meta.setContext("http://example.org/context");
        meta.setDataset(Collections.emptyList());
        when(nodeAccessService.getMetadata()).thenReturn(meta);

        mvc.perform(get("/node/metadata"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['@context']").value("http://example.org/context"))
                .andExpect(jsonPath("$.dataset").isArray())
                .andExpect(jsonPath("$.dataset").isEmpty());
    }

    @Test
    void metadata_missing_returns404AndError() throws Exception {
        when(nodeAccessService.getMetadata()).thenReturn(null);

        mvc.perform(get("/node/metadata"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("No metadata file found or could not read it."));
    }

    @Test
    void authorize_nullToken_returns400() throws Exception {
        NodeAuthRequestDTO req = new NodeAuthRequestDTO();
        req.setToken(null);

        mvc.perform(post("/node/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid token"));

        verifyNoInteractions(nodeAccessService);
    }

    @Test
    void authorize_validToken_stored_returns200() throws Exception {
        NodeAuthRequestDTO req = new NodeAuthRequestDTO();
        req.setToken("SGT123");
        when(nodeAccessService.verifyKerberosToken("SGT123")).thenReturn(true);

        mvc.perform(post("/node/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Token stored successfully"));
    }

    @Test
    void authorize_invalidToken_returns401() throws Exception {
        NodeAuthRequestDTO req = new NodeAuthRequestDTO();
        req.setToken("BAD");
        when(nodeAccessService.verifyKerberosToken("BAD")).thenReturn(false);

        mvc.perform(post("/node/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid token"));
    }

    @Test
    void authorize_serviceThrows_returns500() throws Exception {
        NodeAuthRequestDTO req = new NodeAuthRequestDTO();
        req.setToken("ERR");
        when(nodeAccessService.verifyKerberosToken("ERR"))
                .thenThrow(new RuntimeException("boom"));

        mvc.perform(post("/node/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Error verifying token"));
    }

    @Test
    void validate_success_returns200AndNewToken() throws Exception {
        String oldJwt = "old.jwt";
        String sgt = "SGT456";
        when(nodeAccessService.validateUserToken(oldJwt, sgt)).thenReturn(true);
        when(jwtTokenUtil.getUsernameFromToken(oldJwt)).thenReturn("alice");
        when(jwtTokenUtil.generateToken("alice")).thenReturn("NEW.JWT");

        NodeValidationRequestDTO req = new NodeValidationRequestDTO();
        req.setKerberosToken(sgt);

        mvc.perform(post("/node/validate")
                        .header("Authorization", "Bearer " + oldJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jwtNodeToken").value("NEW.JWT"));
    }

    @Test
    void validate_failures_returns401() throws Exception {
        String oldJwt = "old.jwt";
        String sgt = "SGT789";
        when(nodeAccessService.validateUserToken(oldJwt, sgt)).thenReturn(false);

        NodeValidationRequestDTO req = new NodeValidationRequestDTO();
        req.setKerberosToken(sgt);

        mvc.perform(post("/node/validate")
                        .header("Authorization", "Bearer " + oldJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.jwtNodeToken").value("Unauthorized"));
    }
}
