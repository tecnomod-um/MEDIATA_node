package org.taniwha.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.taniwha.dto.FileFilters;
import org.taniwha.privacy.pooling.PrivatePoolingRelease;
import org.taniwha.privacy.pooling.PrivatePoolingService;

import java.io.IOException;

@RestController
@RequestMapping("/api/data")
public class PrivatePoolingController {
    private static final Logger logger = LoggerFactory.getLogger(PrivatePoolingController.class);
    private final PrivatePoolingService pooling;

    public PrivatePoolingController(PrivatePoolingService pooling) {
        this.pooling = pooling;
    }

    @PostMapping("/approximatePool")
    public ResponseEntity<PrivatePoolingRelease> approximatePool(@RequestBody FileFilters request) {
        try {
            return pooling.release(request)
                    .map(release -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(release))
                    .orElseGet(() -> ResponseEntity.unprocessableEntity().build());
        } catch (IOException | IllegalArgumentException | IllegalStateException error) {
            logger.warn("Approximate pooling unavailable: {}", error.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }
}
