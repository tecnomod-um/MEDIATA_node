package org.taniwha.service;

import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;
import org.springframework.web.util.UriComponentsBuilder;
import org.taniwha.config.RestTemplateHolder;
import org.taniwha.dto.ErrorLogDTO;
import org.taniwha.dto.RegisterResponseDTO;
import org.taniwha.model.NodeHeartbeat;
import org.taniwha.model.NodeInfo;
import org.taniwha.util.ColorUtil;
import org.taniwha.util.JwtTokenUtil;
import org.taniwha.util.KeyPairUtil;

import javax.net.ssl.SSLHandshakeException;
import java.time.Instant;
import java.time.LocalDateTime;

@Service
public class NodeSyncService {

    private static final Logger logger = LoggerFactory.getLogger(NodeSyncService.class);

    private final RestTemplateHolder restTemplateHolder;
    private final RetryTemplate retryTemplate;
    private final JwtTokenUtil jwtTokenUtil;
    private final String publicKeyBase64;
    private final PrincipalService principalService;

    @Value("${host.url}${host.service}")
    private String centralBackendUrl;

    @Value("${node.ip}")
    private String nodeIp;

    @Value("${name}")
    private String nodeName;

    @Value("${desc}")
    private String nodeDescription;

    @Value("${spring.security.user.name}")
    private String jwtUsername;

    @Value("${node.color:}")
    private String nodeColor;

    private String objectId;

    public NodeSyncService(RestTemplateHolder restTemplateHolder,
                           RetryTemplate retryTemplate,
                           JwtTokenUtil jwtTokenUtil,
                           KeyPairUtil keyPairUtil,
                           PrincipalService principalService) {
        this.restTemplateHolder = restTemplateHolder;
        this.retryTemplate = retryTemplate;
        this.jwtTokenUtil = jwtTokenUtil;
        this.publicKeyBase64 = keyPairUtil.getPublicKeyBase64();
        this.principalService = principalService;
    }

    public void registerWithCentralBackend() {
        String color = nodeColor.isEmpty() ? ColorUtil.generateRandomColor() : nodeColor;
        objectId = new ObjectId().toString();

        NodeInfo nodeInfo = new NodeInfo(objectId, nodeIp, nodeName, null, nodeDescription, color, publicKeyBase64);
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(centralBackendUrl + "/nodes/register");
        String token = jwtTokenUtil.generateToken(jwtUsername);

        try {
            RegisterResponseDTO response = retryTemplate.execute((RetryCallback<RegisterResponseDTO, RestClientException>) context -> {
                if (context.getRetryCount() > 0)
                    logger.warn("Retrying registration with central backend. Attempt {}", context.getRetryCount() + 1);

                try {
                    return restTemplateHolder.get().postForObject(builder.toUriString(), createHttpEntity(nodeInfo, token), RegisterResponseDTO.class);
                } catch (ResourceAccessException e) {
                    if (e.getCause() instanceof SSLHandshakeException) {
                        logger.warn("SSL handshake failed during registration. Refreshing RestTemplate...");
                        restTemplateHolder.refresh();
                        return restTemplateHolder.get().postForObject(builder.toUriString(), createHttpEntity(nodeInfo, token), RegisterResponseDTO.class);
                    }
                    throw e;
                }
            });
            principalService.setKeytab(response.getKeytab());
            logger.info("Node registered successfully with central backend: {}", nodeName);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                logger.error("Node registration conflict: Node with IP {} is already registered", nodeIp);
            } else {
                logger.error("Failed to register node with central backend after retries", e);
            }
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

                try {
                    restTemplateHolder.get().postForEntity(builder.toUriString(), createHttpEntity(heartbeat, token), String.class);
                } catch (ResourceAccessException e) {
                    if (e.getCause() instanceof SSLHandshakeException) {
                        logger.warn("SSL handshake failed during heartbeat. Refreshing RestTemplate...");
                        restTemplateHolder.refresh();
                        restTemplateHolder.get().postForEntity(builder.toUriString(), createHttpEntity(heartbeat, token), String.class);
                    } else {
                        throw e;
                    }
                }
                return null;
            });
            logger.info("Heartbeat sent successfully");
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                logger.warn("Node not registered. Attempting to re-register.");
                registerWithCentralBackend();
            } else handleHeartbeatException(e);
        } catch (RestClientException e) {
            logger.error("Failed to send heartbeat to central backend after retries", e);
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

                try {
                    restTemplateHolder.get().postForEntity(builder.toUriString(), createHttpEntity(errorLogDTO, token), String.class);
                } catch (ResourceAccessException e) {
                    if (e.getCause() instanceof SSLHandshakeException) {
                        logger.warn("SSL handshake failed during error logging. Refreshing RestTemplate...");
                        restTemplateHolder.refresh();
                        restTemplateHolder.get().postForEntity(builder.toUriString(), createHttpEntity(errorLogDTO, token), String.class);
                    } else throw e;
                }
                return null;
            });
            logger.info("Error log sent successfully");
        } catch (RestClientException e) {
            logger.error("Failed to send error log to central backend after retries", e);
        }
    }

    private <T> HttpEntity<T> createHttpEntity(T body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        return new HttpEntity<>(body, headers);
    }

    private void handleHeartbeatException(HttpClientErrorException e) {
        String errorInfo = String.format("HTTP error during heartbeat. Status Code: %s. Error: %s", e.getStatusCode(), e.getResponseBodyAsString());

        switch (e.getStatusCode().value()) {
            case 404 -> {
                logger.error("Heartbeat location not found. Please ensure the endpoint is correctly defined on the central backend.");
                logErrorToCentralBackend(e.getMessage(), errorInfo);
            }
            case 400 -> {
                logger.error("Bad request during heartbeat. Error: {}", e.getResponseBodyAsString());
                logErrorToCentralBackend(e.getMessage(), errorInfo);
            }
            case 401 -> {
                logger.error("Heartbeat unauthorized. Please check your credentials or access settings.");
                logErrorToCentralBackend(e.getMessage(), errorInfo);
            }
            default -> {
                logger.error(errorInfo);
                logErrorToCentralBackend(e.getMessage(), errorInfo);
            }
        }
    }
}
