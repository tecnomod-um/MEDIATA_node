FROM fairdata/fairdatapoint:1.16 AS official-fdp

FROM ubuntu:22.04

ARG DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        gnupg \
        openjdk-17-jre-headless \
        python3 \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL https://www.mongodb.org/static/pgp/server-7.0.asc \
        | gpg --dearmor -o /usr/share/keyrings/mongodb-server-7.0.gpg \
    && echo "deb [ arch=amd64,arm64 signed-by=/usr/share/keyrings/mongodb-server-7.0.gpg ] https://repo.mongodb.org/apt/ubuntu jammy/mongodb-org/7.0 multiverse" \
        > /etc/apt/sources.list.d/mongodb-org-7.0.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        mongodb-mongosh \
        mongodb-org-server \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY target/TANIWHA_Backend_node.jar /app/TANIWHA_Backend_node.jar
COPY --from=official-fdp /fdp/app.jar /opt/fdp/app.jar
COPY entrypoint.sh /app/entrypoint.sh

RUN sed -i 's/\r$//' /app/entrypoint.sh \
    && chmod +x /app/entrypoint.sh \
    && mkdir -p \
        /taniwha/datasets \
        /taniwha/mapped_datasets \
        /taniwha/fhir_mappings \
        /taniwha/dataset_elements \
        /taniwha/dataset_metadata \
        /taniwha/fairdatapoint/mongo \
        /taniwha/fairdatapoint/rdf-store \
        /var/log/taniwha \
    && chmod 777 \
        /taniwha/datasets \
        /taniwha/mapped_datasets \
        /taniwha/fhir_mappings \
        /taniwha/dataset_elements \
        /taniwha/dataset_metadata

VOLUME /taniwha

ENTRYPOINT ["/app/entrypoint.sh"]
