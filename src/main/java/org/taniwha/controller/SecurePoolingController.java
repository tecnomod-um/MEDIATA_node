package org.taniwha.controller;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.taniwha.privacy.pooling.*;
import java.io.IOException;
import java.security.GeneralSecurityException;

@RestController
@RequestMapping("/api/data/securePool")
public class SecurePoolingController {
    private final SecurePoolService service;
    public SecurePoolingController(SecurePoolService service) {this.service=service;}

    @PostMapping("/prepare")
    public ResponseEntity<SecurePoolEnvelope> prepare(@RequestBody SecurePoolPrepareRequest request) throws IOException,GeneralSecurityException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.prepare(request));
    }
    @PostMapping("/mask")
    public ResponseEntity<SecurePoolEnvelope> mask(@RequestBody SecurePoolRoundRequest request) throws IOException,GeneralSecurityException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.mask(request));
    }
    @PostMapping("/finish")
    public ResponseEntity<SecurePoolResult> finish(@RequestBody SecurePoolFinishRequest request) throws IOException,GeneralSecurityException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.finish(request));
    }
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> discard(@PathVariable String sessionId) {
        service.discard(sessionId);return ResponseEntity.noContent().build();
    }
    @ExceptionHandler({IllegalArgumentException.class,GeneralSecurityException.class,PrivatePoolingUnavailableException.class})
    public ResponseEntity<ProblemDetail> invalid(Exception error) {
        var problem=ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,error.getMessage());
        problem.setTitle("Secure pooled statistics unavailable");
        return ResponseEntity.unprocessableEntity().cacheControl(CacheControl.noStore()).body(problem);
    }

    @ExceptionHandler({IOException.class,IllegalStateException.class,org.springframework.web.client.RestClientException.class})
    public ResponseEntity<ProblemDetail> unavailable(Exception error) {
        var problem=ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                error instanceof IllegalStateException ? error.getMessage()
                        : "The node could not verify the peer registry or read its saved protected inputs. Retry or check node configuration.");
        problem.setTitle("Secure pooling temporarily unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore()).body(problem);
    }
}
