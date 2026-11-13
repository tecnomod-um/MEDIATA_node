package org.taniwha.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.taniwha.service.TrustedProxySecurityService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrustedProxyRequestFilterTest {

    private AutoCloseable mocks;
    private TrustedProxyRequestFilter filter;

    @Mock
    private TrustedProxySecurityService trustedProxySecurityService;

    @Mock
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        filter = new TrustedProxyRequestFilter(trustedProxySecurityService);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() throws Exception {
        SecurityContextHolder.clearContext();
        mocks.close();
    }

    @Test
    void doFilterInternal_verifiedProxyRequestAuthenticatesRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/taniwha/api/semantic-cde/datasets/blood_pressure.csv/registry-matches");
        request.setContextPath("/taniwha");
        MockHttpServletResponse response = new MockHttpServletResponse();
        TrustedProxySecurityService.SignedRequestContext context =
                new TrustedProxySecurityService.SignedRequestContext("GET", "/taniwha/api/semantic-cde/datasets/blood_pressure.csv/registry-matches", "nonce");

        when(trustedProxySecurityService.isEnabled()).thenReturn(true);
        when(trustedProxySecurityService.isPublicUnsignedPath(any())).thenReturn(false);
        when(trustedProxySecurityService.hasSignatureHeaders(any())).thenReturn(true);
        when(trustedProxySecurityService.verifyRequest(any(), any()))
                .thenReturn(TrustedProxySecurityService.VerificationResult.success(context));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("trusted-proxy");
        assertThat(request.getAttribute(TrustedProxySecurityService.SIGNED_PROXY_CONTEXT_ATTRIBUTE)).isSameAs(context);
        verify(filterChain).doFilter(any(), any());
    }
}
