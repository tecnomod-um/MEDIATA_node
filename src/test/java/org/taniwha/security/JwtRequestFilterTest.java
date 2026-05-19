package org.taniwha.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.taniwha.util.JwtTokenUtil;

import java.io.IOException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtRequestFilterTest {

    private JwtRequestFilter jwtRequestFilter;

    @Mock
    private JwtTokenUtil jwtTokenUtil;

    @Mock
    private FilterChain filterChain;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        jwtRequestFilter = new JwtRequestFilter(jwtTokenUtil);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilterInternal_exemptedEndpoint_shouldBypassFilter() throws ServletException, IOException {
        request.setRequestURI("/taniwha/node/register");
        request.setContextPath("/taniwha");

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(jwtTokenUtil);
    }

    @Test
    void doFilterInternal_validToken_shouldAuthenticateAndContinue() throws ServletException, IOException {
        request.setRequestURI("/taniwha/api/data");
        request.setContextPath("/taniwha");
        request.addHeader("Authorization", "Bearer validToken123");
        when(jwtTokenUtil.validateToken("validToken123")).thenReturn(true);

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        verify(jwtTokenUtil).validateToken("validToken123");
        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void doFilterInternal_missingAuthorizationHeader_shouldReturnUnauthorized() throws ServletException, IOException {
        request.setRequestURI("/taniwha/api/data");
        request.setContextPath("/taniwha");

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getErrorMessage()).contains("JWT Token does not begin with Bearer String");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void doFilterInternal_fairDataPointSyncPathWithoutAuthorization_shouldRemainProtected() throws ServletException, IOException {
        request.setRequestURI("/taniwha/api/fairdatapoint/sync/catalogs");
        request.setContextPath("/taniwha");

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getErrorMessage()).contains("JWT Token does not begin with Bearer String");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void doFilterInternal_invalidBearerFormat_shouldReturnUnauthorized() throws ServletException, IOException {
        request.setRequestURI("/taniwha/api/data");
        request.setContextPath("/taniwha");
        request.addHeader("Authorization", "Basic invalidFormat");

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getErrorMessage()).contains("JWT Token does not begin with Bearer String");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void doFilterInternal_expiredToken_shouldReturnUnauthorized() throws ServletException, IOException {
        request.setRequestURI("/taniwha/api/data");
        request.setContextPath("/taniwha");
        request.addHeader("Authorization", "Bearer expiredToken");
        when(jwtTokenUtil.validateToken("expiredToken"))
                .thenThrow(new ExpiredJwtException(null, null, "Token expired"));

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getErrorMessage()).contains("JWT Token expired");
        assertThat(response.getHeader("X-Token-Expired")).isEqualTo("true");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void doFilterInternal_invalidSignature_shouldReturnUnauthorized() throws ServletException, IOException {
        request.setRequestURI("/taniwha/api/data");
        request.setContextPath("/taniwha");
        request.addHeader("Authorization", "Bearer invalidSignatureToken");
        when(jwtTokenUtil.validateToken("invalidSignatureToken"))
                .thenThrow(new SignatureException("Invalid signature"));

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getErrorMessage()).contains("Invalid JWT Signature");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void doFilterInternal_malformedToken_shouldReturnUnauthorized() throws ServletException, IOException {
        request.setRequestURI("/taniwha/api/data");
        request.setContextPath("/taniwha");
        request.addHeader("Authorization", "Bearer malformedToken");
        when(jwtTokenUtil.validateToken("malformedToken"))
                .thenThrow(new MalformedJwtException("Malformed JWT"));

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getErrorMessage()).contains("Malformed JWT Token");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void doFilterInternal_tokenValidationReturnsFalse_shouldReturnUnauthorized() throws ServletException, IOException {
        request.setRequestURI("/taniwha/api/data");
        request.setContextPath("/taniwha");
        request.addHeader("Authorization", "Bearer invalidToken");
        when(jwtTokenUtil.validateToken("invalidToken")).thenReturn(false);

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getErrorMessage()).contains("JWT Token expired");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void isExemptedEndpoint_nodeEndpoints_shouldBeExempted() {
        request.setRequestURI("/taniwha/node/status");
        request.setContextPath("/taniwha");
        
        // Access the filter to test - this would normally be tested via doFilterInternal
        // but we can verify behavior through the main method
        try {
            jwtRequestFilter.doFilterInternal(request, response, filterChain);
            verify(filterChain).doFilter(request, response);
        } catch (Exception e) {
            fail("Should not throw exception");
        }
    }

    @Test
    void doFilterInternal_multipleExemptedPaths_shouldAllBypass() throws ServletException, IOException {
        String[] exemptedPaths = {
            "/taniwha/node/register",
            "/taniwha/node/heartbeat",
            "/taniwha/node/status",
            "/taniwha/fdp",
            "/taniwha/fdp/catalog/node-catalog"
        };

        for (String path : exemptedPaths) {
            MockHttpServletRequest req = new MockHttpServletRequest();
            MockHttpServletResponse resp = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);
            
            req.setRequestURI(path);
            req.setContextPath("/taniwha");
            jwtRequestFilter.doFilterInternal(req, resp, chain);
            
            verify(chain).doFilter(req, resp);
        }
    }
}
