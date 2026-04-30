package org.taniwha.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.taniwha.dto.FairDataPointAccessResponseDTO;
import org.taniwha.dto.FairDataPointSyncResponseDTO;
import org.taniwha.service.FairDataPointCatalogSyncService;
import org.taniwha.service.FairDataPointInstanceService;
import org.taniwha.util.JwtTokenUtil;

@RestController
public class FairDataPointController {

    private final FairDataPointCatalogSyncService fairDataPointCatalogSyncService;
    private final FairDataPointInstanceService fairDataPointInstanceService;
    private final JwtTokenUtil jwtTokenUtil;
    private final String configuredNodeIp;

    public FairDataPointController(FairDataPointCatalogSyncService fairDataPointCatalogSyncService,
                                   FairDataPointInstanceService fairDataPointInstanceService,
                                   JwtTokenUtil jwtTokenUtil,
                                   @Value("${node.ip:}") String configuredNodeIp) {
        this.fairDataPointCatalogSyncService = fairDataPointCatalogSyncService;
        this.fairDataPointInstanceService = fairDataPointInstanceService;
        this.jwtTokenUtil = jwtTokenUtil;
        this.configuredNodeIp = configuredNodeIp == null ? "" : configuredNodeIp.trim();
    }

    @GetMapping({
            "/fdp", "/fdp/",
            "/fdp/catalog", "/fdp/catalog/",
            "/fdp/catalog/{catalogId}",
            "/fdp/catalog/{catalogId}/dataset/",
            "/fdp/dataset/{datasetId}",
            "/fdp/dataset/{datasetId}/distribution/",
            "/fdp/distribution/{distributionId}",
            "/fdp/profile/{profileId}",
            "/fdp/metadata-schemas/{schemaId}",
            "/fdp/spec",
            "/fdp/catalog/{catalogId}/spec",
            "/fdp/dataset/{datasetId}/spec",
            "/fdp/distribution/{distributionId}/spec",
            "/fdp/shapes/{shapeId}"
    })
    public ResponseEntity<String> metadata(@RequestHeader(value = "Accept", required = false) String acceptHeader,
                                           HttpServletRequest request) {
        FairDataPointInstanceService.FetchResult result = fairDataPointInstanceService.fetchMetadata(
                resolveFdpRequestSubPath(request),
                acceptHeader,
                resolveFdpBaseUrl(request)
        );
        if (result == null || result.status() == FairDataPointInstanceService.FetchStatus.UNAVAILABLE
                || result.status() == FairDataPointInstanceService.FetchStatus.DISABLED) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("FAIR Data Point instance is unavailable.");
        }
        if (result.status() == FairDataPointInstanceService.FetchStatus.NOT_FOUND) {
            return ResponseEntity.notFound().build();
        }
        return result.response();
    }

    @GetMapping("/fdp/access/{distributionId}")
    public ResponseEntity<FairDataPointAccessResponseDTO> accessInformation(@PathVariable String distributionId,
                                                                            HttpServletRequest request) {
        FairDataPointAccessResponseDTO response = fairDataPointInstanceService.buildAccessResponse(
                resolveFdpBaseUrl(request),
                distributionId
        );
        return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
    }

    @PostMapping({"/api/fairdatapoint/sync", "/api/fairdatapoint/sync/catalogs"})
    public ResponseEntity<FairDataPointSyncResponseDTO> syncCatalogs(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        if (!isNodeValidatedToken(authorizationHeader)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    FairDataPointSyncResponseDTO.error(
                            "FAIR Data Point sync requires a node-validated token issued by /node/validate."
                    )
            );
        }

        try {
            return ResponseEntity.ok(FairDataPointSyncResponseDTO.from(fairDataPointCatalogSyncService.publishCatalogs()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(FairDataPointSyncResponseDTO.error(e.getMessage()));
        }
    }

    private String resolveFdpBaseUrl(HttpServletRequest request) {
        String configuredPublicBaseUrl = resolveConfiguredPublicFdpBaseUrl(request);
        if (configuredPublicBaseUrl != null) {
            return configuredPublicBaseUrl;
        }

        return ServletUriComponentsBuilder.fromRequestUri(request)
                .replacePath(request.getContextPath() + "/fdp")
                .replaceQuery(null)
                .build()
                .toUriString();
    }

    private String resolveConfiguredPublicFdpBaseUrl(HttpServletRequest request) {
        if (!configuredNodeIp.startsWith("http://") && !configuredNodeIp.startsWith("https://")) {
            return null;
        }

        String normalizedBase = configuredNodeIp.endsWith("/")
                ? configuredNodeIp.substring(0, configuredNodeIp.length() - 1)
                : configuredNodeIp;
        return normalizedBase + request.getContextPath() + "/fdp";
    }

    private String resolveFdpRequestSubPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String applicationPath = contextPath != null && !contextPath.isEmpty() && requestUri.startsWith(contextPath)
                ? requestUri.substring(contextPath.length())
                : requestUri;
        String subPath = applicationPath.substring("/fdp".length());
        return subPath.isBlank() ? "/" : subPath;
    }

    private boolean isNodeValidatedToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return false;
        }
        String token = authorizationHeader.substring(7);
        return jwtTokenUtil.isNodeAccessToken(token);
    }
}
