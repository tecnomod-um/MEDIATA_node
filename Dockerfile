# Build from JAR
FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app

COPY target/TANIWHA_Backend_node.jar /app/TANIWHA_Backend_node.jar
COPY entrypoint.sh /app/entrypoint.sh

RUN chmod +x /app/entrypoint.sh \
    && mkdir -p \
        /taniwha/datasets \
        /taniwha/mapped_datasets \
        /taniwha/fhir_mappings \
        /taniwha/dataset_elements \
        /taniwha/dataset_metadata \
    && chmod 777 \
        /taniwha/datasets \
        /taniwha/mapped_datasets \
        /taniwha/fhir_mappings \
        /taniwha/dataset_elements \
        /taniwha/dataset_metadata

VOLUME /taniwha

ARG EXPOSE_PORT=8080
EXPOSE ${EXPOSE_PORT}
ENTRYPOINT ["/app/entrypoint.sh"]
