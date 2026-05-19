package org.taniwha.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.taniwha.util.JwtTokenUtil;

import java.io.IOException;

@Component
public class JwtRequestFilter extends OncePerRequestFilter {

    private static final Logger filterLogger = LoggerFactory.getLogger(JwtRequestFilter.class);
    private final JwtTokenUtil jwtTokenUtil;

    public JwtRequestFilter(JwtTokenUtil jwtTokenUtil) {
        this.jwtTokenUtil = jwtTokenUtil;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain chain)
            throws ServletException, IOException {
        // Allow requests to specific endpoints to bypass the filter
        if (isExemptedEndpoint(request)) {
            chain.doFilter(request, response);
            return;
        }

        String jwtToken = extractJwtToken(request, response);
        if (jwtToken == null) return;
        if (!validateToken(jwtToken, response)) return;
        setAuthenticationContext(request);
        chain.doFilter(request, response);
    }

    private boolean isExemptedEndpoint(HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        String contextPath = request.getContextPath();
        String applicationPath = contextPath != null && !contextPath.isEmpty() && requestURI.startsWith(contextPath)
                ? requestURI.substring(contextPath.length())
                : requestURI;

        return applicationPath.startsWith("/node") || applicationPath.startsWith("/fdp");
    }

    private String extractJwtToken(HttpServletRequest request, HttpServletResponse response) throws IOException {
        final String requestTokenHeader = request.getHeader("Authorization");

        if (requestTokenHeader != null && requestTokenHeader.startsWith("Bearer "))
            return requestTokenHeader.substring(7);
        else {
            filterLogger.warn("JWT Token does not begin with Bearer String");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "JWT Token does not begin with Bearer String");
            return null;
        }
    }

    private boolean validateToken(String jwtToken, HttpServletResponse response) throws IOException {
        try {
            if (!jwtTokenUtil.validateToken(jwtToken)) {
                filterLogger.warn("JWT Token is expired");
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "JWT Token expired");
                return false;
            }
            return true;
        } catch (ExpiredJwtException e) {
            filterLogger.warn("JWT Token has expired");
            response.setHeader("X-Token-Expired", "true");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "JWT Token expired");
        } catch (SignatureException e) {
            filterLogger.warn("Invalid JWT Signature");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid JWT Signature");
        } catch (MalformedJwtException e) {
            filterLogger.warn("Malformed JWT Token");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Malformed JWT Token");
        }
        return false;
    }

    private void setAuthenticationContext(HttpServletRequest request) {
        UsernamePasswordAuthenticationToken authenticationToken =
                new UsernamePasswordAuthenticationToken(null, null, null);
        authenticationToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authenticationToken);
    }
}
