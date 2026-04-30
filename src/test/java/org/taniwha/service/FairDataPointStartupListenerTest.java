package org.taniwha.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.taniwha.config.FairDataPointStartupListener;
import org.taniwha.config.FairDataPointBrandingConfig;
import org.taniwha.model.FairDataPointSyncResult;

import static org.mockito.Mockito.*;

class FairDataPointStartupListenerTest {

    private FairDataPointBrandingConfig brandingService;
    private FairDataPointCatalogSyncService syncService;
    private FairDataPointStartupListener listener;

    @BeforeEach
    void setUp() {
        brandingService = mock(FairDataPointBrandingConfig.class);
        syncService = mock(FairDataPointCatalogSyncService.class);
        listener = new FairDataPointStartupListener(true, true, brandingService, syncService);
    }

    @Test
    void syncCatalogsOnStartup_skipsWhenIntegrationDisabled() {
        listener = new FairDataPointStartupListener(false, true, brandingService, syncService);

        listener.syncCatalogsOnStartup();

        verifyNoInteractions(brandingService, syncService);
    }

    @Test
    void syncCatalogsOnStartup_skipsWhenStartupPublishingDisabled() {
        listener = new FairDataPointStartupListener(true, false, brandingService, syncService);

        listener.syncCatalogsOnStartup();

        verify(brandingService).applyBranding();
        verifyNoInteractions(syncService);
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

        verify(brandingService).applyBranding();
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
}