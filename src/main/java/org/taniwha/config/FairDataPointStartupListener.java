package org.taniwha.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.taniwha.service.FairDataPointCatalogSyncService;

@Component
public class FairDataPointStartupListener {

    private static final Logger logger = LoggerFactory.getLogger(FairDataPointStartupListener.class);

    private final boolean fairDataPointEnabled;
    private final boolean publishOnStartup;
    private final FairDataPointBrandingConfig fairDataPointBrandingConfig;
    private final FairDataPointCatalogSyncService fairDataPointCatalogSyncService;

    public FairDataPointStartupListener(@Value("${fairdatapoint.enabled:false}") boolean fairDataPointEnabled,
                                        @Value("${fairdatapoint.publish-on-startup:false}") boolean publishOnStartup,
                                        FairDataPointBrandingConfig fairDataPointBrandingConfig,
                                        FairDataPointCatalogSyncService fairDataPointCatalogSyncService) {
        this.fairDataPointEnabled = fairDataPointEnabled;
        this.publishOnStartup = publishOnStartup;
        this.fairDataPointBrandingConfig = fairDataPointBrandingConfig;
        this.fairDataPointCatalogSyncService = fairDataPointCatalogSyncService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncCatalogsOnStartup() {
        if (!fairDataPointEnabled) {
            logger.debug("Skipping FAIR Data Point sync on startup because integration is disabled");
            return;
        }

        try {
            fairDataPointBrandingConfig.applyBranding();
            logger.info("FAIR Data Point branding completed on startup");
        } catch (Exception e) {
            logger.error("FAIR Data Point branding failed; the node will continue running and can be rebranded later", e);
        }

        if (!publishOnStartup) {
            logger.debug("Skipping FAIR Data Point sync on startup because publish-on-startup is disabled");
            return;
        }

        try {
            fairDataPointCatalogSyncService.publishCatalogs();
            logger.info("FAIR Data Point startup sync completed");
        } catch (Exception e) {
            logger.error("FAIR Data Point startup sync failed; the node will continue running and can be synced later", e);
        }
    }
}
