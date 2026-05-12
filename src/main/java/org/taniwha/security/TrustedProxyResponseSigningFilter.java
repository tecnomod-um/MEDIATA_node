package org.taniwha.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.taniwha.service.TrustedProxySecurityService;

import java.io.IOException;

@Component
public class TrustedProxyResponseSigningFilter extends OncePerRequestFilter {

    private final TrustedProxySecurityService trustedProxySecurityService;

    public TrustedProxyResponseSigningFilter(TrustedProxySecurityService trustedProxySecurityService) {
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

        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        try {
            filterChain.doFilter(request, wrappedResponse);
        } finally {
            TrustedProxySecurityService.SignedRequestContext context =
                    (TrustedProxySecurityService.SignedRequestContext) request.getAttribute(
                            TrustedProxySecurityService.SIGNED_PROXY_CONTEXT_ATTRIBUTE
                    );
            if (context != null) {
                trustedProxySecurityService.signResponse(
                        wrappedResponse,
                        context,
                        wrappedResponse.getStatus(),
                        wrappedResponse.getContentAsByteArray()
                );
            }
            wrappedResponse.copyBodyToResponse();
        }
    }
}
