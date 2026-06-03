package org.taniwha.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.http.ResponseEntity;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.taniwha.service.FairDataPointCatalogSyncService;

@Component
public class FairDataPointStartupListener {

    private static final Logger logger = LoggerFactory.getLogger(FairDataPointStartupListener.class);

    private final boolean fairDataPointEnabled;
    private final boolean publishOnStartup;
    private final String fairDataPointBaseUrl;
    private final RestTemplateHolder restTemplateHolder;
    private final FairDataPointBrandingConfig fairDataPointBrandingConfig;
    private final FairDataPointCatalogSyncService fairDataPointCatalogSyncService;

    public FairDataPointStartupListener(@Value("${fairdatapoint.enabled:false}") boolean fairDataPointEnabled,
                                        @Value("${fairdatapoint.publish-on-startup:false}") boolean publishOnStartup,
                                        @Value("${fairdatapoint.base-url:}") String fairDataPointBaseUrl,
                                        RestTemplateHolder restTemplateHolder,
                                        FairDataPointBrandingConfig fairDataPointBrandingConfig,
                                        FairDataPointCatalogSyncService fairDataPointCatalogSyncService) {
        this.fairDataPointEnabled = fairDataPointEnabled;
        this.publishOnStartup = publishOnStartup;
        this.fairDataPointBaseUrl = fairDataPointBaseUrl == null ? "" : fairDataPointBaseUrl.trim();
        this.restTemplateHolder = restTemplateHolder;
        this.fairDataPointBrandingConfig = fairDataPointBrandingConfig;
        this.fairDataPointCatalogSyncService = fairDataPointCatalogSyncService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncCatalogsOnStartup() {
        if (!fairDataPointEnabled) {
            logger.debug("Skipping FAIR Data Point sync on startup because integration is disabled");
            return;
        }

        if (!isFairDataPointReady()) {
            logger.info("FAIR Data Point is not ready yet; skipping startup branding and sync for now");
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
            fairDataPointBrandingConfig.applyBranding();
            logger.info("FAIR Data Point startup sync completed");
        } catch (Exception e) {
            logger.error("FAIR Data Point startup sync failed; the node will continue running and can be synced later", e);
        }
    }

    private boolean isFairDataPointReady() {
        if (fairDataPointBaseUrl.isBlank()) {
            return false;
        }

        try {
            ResponseEntity<String> response = restTemplateHolder.get().getForEntity(
                    normalizeBaseUrl(fairDataPointBaseUrl) + "/v3/api-docs",
                    String.class
            );
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            logger.trace("FAIR Data Point readiness probe failed during node startup: {}", e.getMessage());
            return false;
        }
    }

    private String normalizeBaseUrl(String value) {
        String trimmed = value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
