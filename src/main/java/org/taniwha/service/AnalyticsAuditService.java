package org.taniwha.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/**
 * Records structured audit entries for every analytics and filtering operation.
 *
 * <p>Entries are written to the dedicated {@code AUDIT} SLF4J logger, which is
 * routed to its own rolling file (see {@code logback.xml}).  Each entry is a
 * single line in key=value format so it can be ingested by log-aggregation
 * tools without additional parsing configuration.
 *
 * <p>Fields written per entry:
 * <ul>
 *   <li>{@code ts} – ISO-8601 UTC timestamp</li>
 *   <li>{@code op} – operation name (e.g. {@code PROCESS}, {@code FILTER})</li>
 *   <li>{@code file} – dataset file name (or comma-separated list)</li>
 *   <li>{@code filters} – {@code true}/{@code false} whether filters were applied</li>
 *   <li>{@code records} – number of records in the result (request entries omit this)</li>
 *   <li>{@code suppressed} – features suppressed by disclosure controls</li>
 *   <li>{@code principal} – authenticated user name, or {@code anonymous}</li>
 * </ul>
 */
@Service
public class AnalyticsAuditService {

    private static final Logger auditLogger = LoggerFactory.getLogger("AUDIT");

    // -------------------------------------------------------------------------
    // Request-level audit entries (logged in the controller)
    // -------------------------------------------------------------------------

    /**
     * Logs the start of a multi-file analytics request (no filter conditions).
     *
     * @param operation human-readable operation label (e.g. {@code "PROCESS"})
     * @param fileNames the file names requested
     */
    public void logRequest(String operation, Collection<String> fileNames) {
        auditLogger.info("ts={} op={} files=[{}] principal={}",
                Instant.now(), operation, String.join(",", fileNames), resolvePrincipal());
    }

    /**
     * Logs a filter request that carries per-file filter conditions.
     *
     * @param operation   human-readable operation label
     * @param fileNames   the file names requested
     * @param hasFilters  whether any filter conditions were supplied
     */
    public void logRequest(String operation, Collection<String> fileNames, boolean hasFilters) {
        auditLogger.info("ts={} op={} files=[{}] filters={} principal={}",
                Instant.now(), operation, String.join(",", fileNames), hasFilters, resolvePrincipal());
    }

    /**
     * Logs a single-file request without filter conditions.
     *
     * @param operation  human-readable operation label
     * @param fileName   the file name requested
     */
    public void logRequest(String operation, String fileName) {
        auditLogger.info("ts={} op={} files=[{}] principal={}",
                Instant.now(), operation, fileName, resolvePrincipal());
    }

    /**
     * Logs a single-file request with named filter conditions.
     *
     * @param operation  human-readable operation label
     * @param fileName   the file name requested
     * @param filters    map of filter conditions (keys are logged; values are not)
     */
    public void logRequest(String operation, String fileName, Map<String, Object> filters) {
        boolean hasFilters = filters != null && !filters.isEmpty();
        auditLogger.info("ts={} op={} files=[{}] filters={} filterKeys=[{}] principal={}",
                Instant.now(), operation, fileName, hasFilters,
                hasFilters ? String.join(",", filters.keySet()) : "",
                resolvePrincipal());
    }

    // -------------------------------------------------------------------------
    // Response-level audit entries (logged in the service after processing)
    // -------------------------------------------------------------------------

    /**
     * Logs the outcome of processing a single file.
     *
     * @param operation          human-readable operation label
     * @param fileName           the processed file name
     * @param recordCount        number of records in the result
     * @param suppressedFeatures features suppressed by disclosure controls
     */
    public void logResponse(String operation, String fileName, long recordCount, int suppressedFeatures) {
        auditLogger.info("ts={} op={} file={} records={} suppressed={} principal={}",
                Instant.now(), operation, fileName, recordCount, suppressedFeatures, resolvePrincipal());
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Returns the name of the currently authenticated principal, or
     * {@code "anonymous"} if no authentication is available (e.g. in an
     * async thread where the {@link SecurityContextHolder} is not propagated).
     */
    private String resolvePrincipal() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
                return auth.getName();
            }
        } catch (Exception ignored) {
            // Defensive: SecurityContextHolder may throw in certain async contexts
        }
        return "anonymous";
    }
}
