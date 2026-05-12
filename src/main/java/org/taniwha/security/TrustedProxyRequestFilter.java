package org.taniwha.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.taniwha.service.TrustedProxySecurityService;
import org.taniwha.util.TrustedProxyRequestWrapper;

import java.io.IOException;

@Component
public class TrustedProxyRequestFilter extends OncePerRequestFilter {

    private final TrustedProxySecurityService trustedProxySecurityService;

    public TrustedProxyRequestFilter(TrustedProxySecurityService trustedProxySecurityService) {
        this.trustedProxySecurityService = trustedProxySecurityService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (!trustedProxySecurityService.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        TrustedProxyRequestWrapper wrappedRequest = new TrustedProxyRequestWrapper(request);
        boolean publicUnsignedPath = trustedProxySecurityService.isPublicUnsignedPath(wrappedRequest);
        boolean hasSignatureHeaders = trustedProxySecurityService.hasSignatureHeaders(wrappedRequest);

        if (publicUnsignedPath && !hasSignatureHeaders) {
            filterChain.doFilter(wrappedRequest, response);
            return;
        }

        if (!hasSignatureHeaders) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing trusted proxy signature");
            return;
        }

        TrustedProxySecurityService.VerificationResult verificationResult =
                trustedProxySecurityService.verifyRequest(wrappedRequest, wrappedRequest.getCachedBody());
        if (!verificationResult.valid()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, verificationResult.failureReason());
            return;
        }

        wrappedRequest.setAttribute(
                TrustedProxySecurityService.SIGNED_PROXY_CONTEXT_ATTRIBUTE,
                verificationResult.context()
        );
        filterChain.doFilter(wrappedRequest, response);
    }
}
