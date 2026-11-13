package org.taniwha.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    private final OpenApiConfig config = new OpenApiConfig();

    @Test
    void documentsNodeIdentityAndBothJwtFlows() {
        OpenAPI openApi = config.nodeOpenApi();

        assertThat(openApi.getInfo().getTitle()).isEqualTo("MEDIATA Node API");
        assertThat(openApi.getComponents().getSecuritySchemes())
                .containsKeys(OpenApiConfig.USER_JWT, OpenApiConfig.NODE_ACCESS_JWT);
    }

    @Test
    void marksPublicUserAndNodeAccessOperationsAccurately() {
        Operation health = new Operation();
        Operation analytics = new Operation();
        Operation sync = new Operation();
        OpenAPI openApi = config.nodeOpenApi().paths(new Paths()
                .addPathItem("/node/health", new PathItem().get(health))
                .addPathItem("/api/data/processList", new PathItem().post(analytics))
                .addPathItem("/api/fairdatapoint/sync", new PathItem().post(sync)));

        config.nodeSecurityRequirements().customise(openApi);

        assertThat(health.getSecurity()).isNullOrEmpty();
        assertThat(analytics.getSecurity().get(0)).containsKey(OpenApiConfig.USER_JWT);
        assertThat(sync.getSecurity().get(0)).containsKey(OpenApiConfig.NODE_ACCESS_JWT);
    }
}
