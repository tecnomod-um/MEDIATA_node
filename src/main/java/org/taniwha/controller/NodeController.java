package org.taniwha.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.taniwha.dto.NodeAuthRequestDTO;
import org.taniwha.dto.NodeAuthResponseDTO;
import org.taniwha.dto.NodeValidationRequestDTO;
import org.taniwha.dto.NodeValidationResponseDTO;
import org.taniwha.model.NodeMetadata;
import org.taniwha.service.NodeAccessService;
import org.taniwha.util.JwtTokenUtil;

// Sets up the node in the system with the required security
@RestController
@RequestMapping("/node")
public class NodeController {

    private static final Logger logger = LoggerFactory.getLogger(NodeController.class);
    private final NodeAccessService nodeAccessService;
    private final JwtTokenUtil jwtTokenUtil;

    public NodeController(NodeAccessService nodeAccessService, JwtTokenUtil jwtTokenUtil) {
        this.nodeAccessService = nodeAccessService;
        this.jwtTokenUtil = jwtTokenUtil;
    }

    @GetMapping("/health")
    public String healthCheck() {
        return "OK";
    }

    @GetMapping("/metadata")
    public ResponseEntity<?> nodeMetadata() {
        NodeMetadata metadata = nodeAccessService.getMetadata();
        if (metadata == null) {
            logger.warn("No metadata found or error reading the RDF file");
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("No metadata file found or could not read it.");
        }
        return ResponseEntity.ok(metadata);
    }

    @PostMapping("/authorize")
    public ResponseEntity<NodeAuthResponseDTO> authorizeNode(@RequestBody NodeAuthRequestDTO request) {
        String sgtToken = request.getToken();
        if (sgtToken != null) {
            try {
                if (nodeAccessService.verifyKerberosToken(sgtToken)) {
                    logger.debug("Token stored successfully");
                    return ResponseEntity.ok(new NodeAuthResponseDTO("Token stored successfully"));
                } else {
                    logger.warn("The received sgt token is invalid");
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new NodeAuthResponseDTO("Invalid token"));
                }
            } catch (Exception e) {
                logger.error("Error verifying token", e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new NodeAuthResponseDTO("Error verifying token"));
            }
        }
        logger.warn("Invalid token received");
        return ResponseEntity.badRequest().body(new NodeAuthResponseDTO("Invalid token"));
    }

    @PostMapping("/validate")
    public ResponseEntity<NodeValidationResponseDTO> validateRequest(@RequestHeader("Authorization") String jwtToken, @RequestBody NodeValidationRequestDTO request) {
        jwtToken = jwtToken.replace("Bearer ", "");
        String sgtToken = request.getKerberosToken();
        logger.debug("Validating tokens: JWT={}, SGT={}", jwtToken, sgtToken);
        if (nodeAccessService.validateUserToken(jwtToken, sgtToken)) {
            logger.debug("Token validation successful");

            String nodeJwtToken = jwtTokenUtil.generateToken(jwtTokenUtil.getUsernameFromToken(jwtToken));
            return ResponseEntity.ok(new NodeValidationResponseDTO(nodeJwtToken));
        }
        logger.warn("Token validation failed");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new NodeValidationResponseDTO("Unauthorized"));
    }
}
