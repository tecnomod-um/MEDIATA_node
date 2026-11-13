package org.taniwha.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Configuration
public class OpenApiConfig {

    public static final String USER_JWT = "bearerAuth";
    public static final String NODE_ACCESS_JWT = "nodeAccessAuth";
    private static final Pattern PATH_PARAMETER = Pattern.compile("\\{([^}/]+)}");

    private static final Set<String> NODE_ACCESS_PATHS = Set.of(
            "/api/fairdatapoint/sync",
            "/api/fairdatapoint/sync/catalogs",
            "/api/files/datasets/shareability"
    );

    @Bean
    public OpenAPI nodeOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("MEDIATA Node API")
                        .version("1.0")
                        .description("On-site APIs for secure dataset discovery, cleaning, harmonization, "
                                + "semantic processing, and FAIR metadata publication. Patient-level data "
                                + "remains at the originating node.")
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/license/mit")))
                .servers(List.of(new Server()
                        .url("/taniwha")
                        .description("Current MEDIATA node")))
                .components(new Components()
                        .addSecuritySchemes(USER_JWT, bearerScheme(
                                "User JWT issued by the MEDIATA orchestrator."
                        ))
                        .addSecuritySchemes(NODE_ACCESS_JWT, bearerScheme(
                                "Node-access JWT returned by POST /node/validate."
                        )));
    }

    @Bean
    public OpenApiCustomizer nodeSecurityRequirements() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            List<String> trailingSlashAliases = openApi.getPaths().keySet().stream()
                    .filter(path -> path.endsWith("/") && openApi.getPaths().containsKey(
                            path.substring(0, path.length() - 1)
                    ))
                    .toList();
            trailingSlashAliases.forEach(openApi.getPaths()::remove);

            openApi.getPaths().forEach((path, pathItem) ->
                    pathItem.readOperationsMap().forEach((method, operation) -> {
                        addMissingPathParameters(path, operation);
                        if (isPublicOperation(path, method.name())) {
                            operation.setSecurity(List.of());
                            return;
                        }
                        String scheme = NODE_ACCESS_PATHS.contains(path) ? NODE_ACCESS_JWT : USER_JWT;
                        operation.addSecurityItem(new SecurityRequirement().addList(scheme));
                        if (operation.getResponses() == null) {
                            operation.setResponses(new ApiResponses());
                        }
                        operation.getResponses().putIfAbsent(
                                "401",
                                new ApiResponse().description("Missing, invalid, or expired JWT.")
                        );
                        operation.getResponses().putIfAbsent(
                                "403",
                                new ApiResponse().description("The authenticated principal cannot access this operation.")
                        );
                    })
            );
        };
    }

    private void addMissingPathParameters(String path, Operation operation) {
        Set<String> documentedNames = operation.getParameters() == null
                ? Set.of()
                : operation.getParameters().stream()
                        .filter(parameter -> "path".equals(parameter.getIn()))
                        .map(Parameter::getName)
                        .collect(java.util.stream.Collectors.toSet());

        Matcher matcher = PATH_PARAMETER.matcher(path);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!documentedNames.contains(name)) {
                operation.addParametersItem(new Parameter()
                        .name(name)
                        .in("path")
                        .required(true)
                        .description("Resource identifier from the FAIR Data Point route.")
                        .schema(new StringSchema()));
            }
        }
    }

    private SecurityScheme bearerScheme(String description) {
        return new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description(description);
    }

    private boolean isPublicOperation(String path, String method) {
        if (path.startsWith("/fdp")) {
            return true;
        }
        if (HttpMethod.GET.name().equals(method)
                && ("/node/health".equals(path) || "/node/metadata".equals(path))) {
            return true;
        }
        return HttpMethod.POST.name().equals(method) && "/node/authorize".equals(path);
    }
}
