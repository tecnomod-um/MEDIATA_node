package org.taniwha.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.taniwha.config.FairDataPointBrandingConfig;
import org.taniwha.config.FairDataPointStartupListener;
import org.taniwha.config.RestTemplateHolder;
import org.taniwha.model.FairDataPointSyncResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import static org.mockito.Mockito.*;

class FairDataPointStartupListenerTest {

    private FairDataPointBrandingConfig brandingService;
    private FairDataPointCatalogSyncService syncService;
    private RestTemplateHolder restTemplateHolder;
    private RestTemplate restTemplate;
    private FairDataPointStartupListener listener;

    @BeforeEach
    void setUp() {
        brandingService = mock(FairDataPointBrandingConfig.class);
        syncService = mock(FairDataPointCatalogSyncService.class);
        restTemplateHolder = mock(RestTemplateHolder.class);
        restTemplate = mock(RestTemplate.class);
        when(restTemplateHolder.get()).thenReturn(restTemplate);
        markFdpReady();
        listener = new FairDataPointStartupListener(true, true, "http://127.0.0.1:18080", restTemplateHolder, brandingService, syncService);
    }

    @Test
    void syncCatalogsOnStartup_skipsWhenIntegrationDisabled() {
        listener = new FairDataPointStartupListener(false, true, "http://127.0.0.1:18080", restTemplateHolder, brandingService, syncService);

        listener.syncCatalogsOnStartup();

        verifyNoInteractions(brandingService, syncService);
    }

    @Test
    void syncCatalogsOnStartup_skipsWhenStartupPublishingDisabled() {
        listener = new FairDataPointStartupListener(true, false, "http://127.0.0.1:18080", restTemplateHolder, brandingService, syncService);

        listener.syncCatalogsOnStartup();

        verify(brandingService).applyBranding();
        verifyNoInteractions(syncService);
    }

    @Test
    void syncCatalogsOnStartup_skipsQuietlyWhenFdpIsNotReady() {
        when(restTemplate.getForEntity("http://127.0.0.1:18080/v3/api-docs", String.class))
                .thenThrow(new RuntimeException("Connection refused"));

        listener.syncCatalogsOnStartup();

        verifyNoInteractions(brandingService, syncService);
    }

    @Test
    void syncCatalogsOnStartup_runsWhenBothFlagsEnabled() {
        doNothing().when(brandingService).applyBranding();
        when(syncService.publishCatalogs()).thenReturn(
                new FairDataPointSyncResult(
                        "COMPLETED",
                        1,
                        1,
                        1,
                        1,
                        java.util.List.of("http://fdp/catalog/1"),
                        java.util.List.of("http://fdp/dataset/1"),
                        java.util.List.of("http://fdp/distribution/1"),
                        "ok"
                )
        );

        listener.syncCatalogsOnStartup();

        verify(brandingService, times(2)).applyBranding();
        verify(syncService).publishCatalogs();
    }

    @Test
    void syncCatalogsOnStartup_doesNotPropagateSyncFailures() {
        doNothing().when(brandingService).applyBranding();
        doThrow(new IllegalStateException("root metadata is not initialized"))
                .when(syncService).publishCatalogs();

        listener.syncCatalogsOnStartup();

        verify(brandingService).applyBranding();
        verify(syncService).publishCatalogs();
    }

    private void markFdpReady() {
        when(restTemplate.getForEntity("http://127.0.0.1:18080/v3/api-docs", String.class))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));
    }
}
