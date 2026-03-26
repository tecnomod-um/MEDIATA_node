package org.taniwha.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AnalyticsAuditService}.
 *
 * <p>Log output is captured via a Logback {@link ListAppender} attached to the
 * {@code AUDIT} logger so each test can assert on the structured fields that
 * are written per operation.
 */
class AnalyticsAuditServiceTest {

    private AnalyticsAuditService service;
    private ListAppender<ILoggingEvent> listAppender;
    private Logger auditLogger;

    @BeforeEach
    void setUp() {
        service = new AnalyticsAuditService();

        // Attach a capturing appender to the AUDIT logger
        auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        listAppender = new ListAppender<>();
        listAppender.start();
        auditLogger.addAppender(listAppender);

        // Start each test with a clean security context
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        auditLogger.detachAppender(listAppender);
        SecurityContextHolder.clearContext();
    }

    // -------------------------------------------------------------------------
    // logRequest(op, files) – multi-file, no filters
    // -------------------------------------------------------------------------

    @Test
    void logRequest_multiFile_noFilters_containsOpAndFiles() {
        service.logRequest("PROCESS", List.of("a.csv", "b.csv"));

        String msg = singleMessage();
        assertThat(msg).contains("op=PROCESS");
        assertThat(msg).contains("a.csv");
        assertThat(msg).contains("b.csv");
        assertThat(msg).contains("ts=");
        assertThat(msg).contains("principal=anonymous");
    }

    @Test
    void logRequest_multiFile_noFilters_anonymousPrincipal() {
        service.logRequest("PROCESS", List.of("x.csv"));

        assertThat(singleMessage()).contains("principal=anonymous");
    }

    // -------------------------------------------------------------------------
    // logRequest(op, files, hasFilters) – multi-file with filter flag
    // -------------------------------------------------------------------------

    @Test
    void logRequest_multiFile_withFiltersTrue_containsFiltersField() {
        service.logRequest("FILTER", List.of("data.csv"), true);

        String msg = singleMessage();
        assertThat(msg).contains("op=FILTER");
        assertThat(msg).contains("filters=true");
        assertThat(msg).contains("data.csv");
    }

    @Test
    void logRequest_multiFile_withFiltersFalse_containsFiltersFalse() {
        service.logRequest("FILTER", List.of("data.csv"), false);

        assertThat(singleMessage()).contains("filters=false");
    }

    // -------------------------------------------------------------------------
    // logRequest(op, fileName) – single file, no filters
    // -------------------------------------------------------------------------

    @Test
    void logRequest_singleFile_noFilters_containsOpAndFile() {
        service.logRequest("REPROCESS", "patient.csv");

        String msg = singleMessage();
        assertThat(msg).contains("op=REPROCESS");
        assertThat(msg).contains("patient.csv");
        assertThat(msg).contains("principal=anonymous");
    }

    // -------------------------------------------------------------------------
    // logRequest(op, fileName, filters) – single file with filter map
    // -------------------------------------------------------------------------

    @Test
    void logRequest_singleFile_withFilters_logsFilterKeysNotValues() {
        Map<String, Object> filters = Map.of("diagnosis", "cancer", "age", 50);
        service.logRequest("FILTER", "records.csv", filters);

        String msg = singleMessage();
        assertThat(msg).contains("filters=true");
        // Keys logged
        assertThat(msg).contains("diagnosis").contains("age");
        // Values must NOT appear in the filterKeys field (scope check to avoid
        // false positives from numeric substrings in the timestamp)
        int start = msg.indexOf("filterKeys=[") + "filterKeys=[".length();
        int end   = msg.indexOf("]", start);
        String filterKeysPart = msg.substring(start, end);
        assertThat(filterKeysPart).doesNotContain("cancer").doesNotContain("50");
    }

    @Test
    void logRequest_singleFile_nullFilters_loggedAsNoFilters() {
        service.logRequest("FILTER", "records.csv", null);

        String msg = singleMessage();
        assertThat(msg).contains("filters=false");
    }

    @Test
    void logRequest_singleFile_emptyFilters_loggedAsNoFilters() {
        service.logRequest("FILTER", "records.csv", Map.of());

        String msg = singleMessage();
        assertThat(msg).contains("filters=false");
    }

    // -------------------------------------------------------------------------
    // logResponse
    // -------------------------------------------------------------------------

    @Test
    void logResponse_containsAllFields() {
        service.logResponse("PROCESS", "dataset.csv", 1234L, 2);

        String msg = singleMessage();
        assertThat(msg).contains("op=PROCESS");
        assertThat(msg).contains("file=dataset.csv");
        assertThat(msg).contains("records=1234");
        assertThat(msg).contains("suppressed=2");
        assertThat(msg).contains("principal=anonymous");
    }

    @Test
    void logResponse_zeroSuppressed_recordedCorrectly() {
        service.logResponse("FILTER", "f.csv", 50L, 0);

        assertThat(singleMessage()).contains("suppressed=0");
    }

    // -------------------------------------------------------------------------
    // Principal resolution
    // -------------------------------------------------------------------------

    @Test
    void logRequest_authenticatedPrincipal_recordedInAuditEntry() {
        var auth = new UsernamePasswordAuthenticationToken("dr.smith", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        service.logRequest("PROCESS", List.of("records.csv"));

        assertThat(singleMessage()).contains("principal=dr.smith");
    }

    @Test
    void logResponse_authenticatedPrincipal_recordedInAuditEntry() {
        var auth = new UsernamePasswordAuthenticationToken("researcher1", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        service.logResponse("FILTER", "data.csv", 100L, 0);

        assertThat(singleMessage()).contains("principal=researcher1");
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private String singleMessage() {
        List<ILoggingEvent> events = listAppender.list;
        assertThat(events).as("expected exactly one audit log event").hasSize(1);
        return events.get(0).getFormattedMessage();
    }
}
