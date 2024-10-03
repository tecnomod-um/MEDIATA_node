package org.taniwha.service;

import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.taniwha.dto.ErrorLogDTO;
import org.taniwha.dto.RegisterResponseDTO;
import org.taniwha.model.NodeHeartbeat;
import org.taniwha.model.NodeInfo;
import org.taniwha.util.ColorUtil;
import org.taniwha.util.JwtTokenUtil;
import org.taniwha.util.KeyPairUtil;

import java.time.Instant;
import java.time.LocalDateTime;

// Registers in central and sends user exceptions to the central log
@Service
public class NodeSyncService {

    private static final Logger logger = LoggerFactory.getLogger(NodeSyncService.class);
    private final RestTemplate restTemplate;
    private final RetryTemplate retryTemplate;
    private final JwtTokenUtil jwtTokenUtil;
    private final String publicKeyBase64;
    private final PrincipalService principalService;

    @Value("${central.backend.url}")
    private String centralBackendUrl;
    @Value("${node.ip}")
    private String nodeIp;
    @Value("${server.port}")
    private int nodePort;
    @Value("${name}")
    private String nodeName;
    @Value("${desc}")
    private String nodeDescription;
    @Value("${spring.security.user.name}")
    private String jwtUsername;
    @Value("${node.color:}")
    private String nodeColor;
    private String objectId;

    public NodeSyncService(RestTemplate restTemplate, RetryTemplate retryTemplate, JwtTokenUtil jwtTokenUtil, KeyPairUtil keyPairUtil, PrincipalService principalService) {
        this.restTemplate = restTemplate;
        this.retryTemplate = retryTemplate;
        this.jwtTokenUtil = jwtTokenUtil;
        this.publicKeyBase64 = keyPairUtil.getPublicKeyBase64();
        this.principalService = principalService;
    }

    public void registerWithCentralBackend() {
        String color = nodeColor.isEmpty() ? ColorUtil.generateRandomColor() : nodeColor;
        objectId = new ObjectId().toString();
        NodeInfo nodeInfo = new NodeInfo(objectId, nodeIp, nodePort, nodeName, null, nodeDescription, color, publicKeyBase64);
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(centralBackendUrl + "/nodes/register");
        String token = jwtTokenUtil.generateToken(jwtUsername);

        try {
            RegisterResponseDTO response = retryTemplate.execute((RetryCallback<RegisterResponseDTO, RestClientException>) context -> {
                if (context.getRetryCount() > 0)
                    logger.warn("Retrying registration with central backend. Attempt {}", context.getRetryCount() + 1);
                return restTemplate.postForObject(builder.toUriString(), createHttpEntity(nodeInfo, token), RegisterResponseDTO.class);
            });
            principalService.setKeytab(response.getKeytab());
            logger.info("Node registered successfully with central backend: {}", nodeName);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT)
                logger.error("Node registration conflict: Node with IP {} and port {} is already registered", nodeIp, nodePort);
            else
                logger.error("Failed to register node with central backend after retries", e);
        }
    }

    public void sendHeartbeat() {
        NodeHeartbeat heartbeat = new NodeHeartbeat(objectId, Instant.now().getEpochSecond());
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(centralBackendUrl + "/nodes/heartbeat");
        String token = jwtTokenUtil.generateToken(jwtUsername);

        try {
            retryTemplate.execute((RetryCallback<Void, RestClientException>) context -> {
                if (context.getRetryCount() > 0)
                    logger.warn("Retrying to send heartbeat. Attempt {}", context.getRetryCount() + 1);
                restTemplate.postForEntity(builder.toUriString(), createHttpEntity(heartbeat, token), String.class);
                return null;
            });
            logger.info("Heartbeat sent successfully");
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                logger.warn("Node not registered. Attempting to re-register.");
                registerWithCentralBackend();
            } else
                handleHttpClientErrorException(e, "/nodes/heartbeat");
        } catch (RestClientException e) {
            logger.error("Failed to send heartbeat to central backend after retries", e);
        }
    }

    private <T> HttpEntity<T> createHttpEntity(T body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        return new HttpEntity<>(body, headers);
    }

    private void handleHttpClientErrorException(HttpClientErrorException e, String endpoint) {
        String errorInfo = String.format("HTTP error when accessing endpoint %s. Status Code: %s. Error: %s", endpoint, e.getStatusCode(), e.getResponseBodyAsString());

        switch (e.getStatusCode().value()) {
            case 404:
                logger.error("Endpoint {} not found. Please ensure the endpoint is correctly defined on the central backend.", endpoint);
                logErrorToCentralBackend(e.getMessage(), errorInfo);
                break;
            case 400:
                logger.error("Bad request when accessing endpoint {}. Error: {}", endpoint, e.getResponseBodyAsString());
                logErrorToCentralBackend(e.getMessage(), errorInfo);
                break;
            case 401:
                logger.error("Unauthorized access to endpoint {}. Please check your credentials or access settings.", endpoint);
                logErrorToCentralBackend(e.getMessage(), errorInfo);
                break;
            default:
                logger.error(errorInfo);
                logErrorToCentralBackend(e.getMessage(), errorInfo);
                break;
        }
    }

    public void logErrorToCentralBackend(String error, String info) {
        ErrorLogDTO errorLogDTO = new ErrorLogDTO();
        errorLogDTO.setError(error);
        errorLogDTO.setInfo(info);
        errorLogDTO.setTimestamp(LocalDateTime.now().toString());

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(centralBackendUrl + "/api/error");
        String token = jwtTokenUtil.generateToken(jwtUsername);

        try {
            retryTemplate.execute((RetryCallback<Void, RestClientException>) context -> {
                if (context.getRetryCount() > 0)
                    logger.warn("Retrying to send error log. Attempt {}", context.getRetryCount() + 1);
                restTemplate.postForEntity(builder.toUriString(), createHttpEntity(errorLogDTO, token), String.class);
                return null;
            });
            logger.info("Error log sent successfully");
        } catch (RestClientException e) {
            logger.error("Failed to send error log to central backend after retries", e);
        }
    }
}
