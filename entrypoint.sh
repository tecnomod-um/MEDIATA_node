#!/bin/sh
set -eu

WORKDIRS="
/taniwha/datasets
/taniwha/mapped_datasets
/taniwha/fhir_mappings
/taniwha/dataset_elements
/taniwha/dataset_metadata
"

for d in $WORKDIRS; do
  if [ -d "$d" ]; then
    echo "Exists: $d"
  else
    echo "Creating: $d"
    mkdir -p "$d"
  fi
done

# Permissions (avoid failing if already set)
chmod 777 /taniwha/datasets \
  && chmod 777 /taniwha/mapped_datasets \
  && chmod 777 /taniwha/fhir_mappings \
  && chmod 777 /taniwha/dataset_metadata

echo "Created the work directories in /taniwha."

# Set default values for environment variables if not provided
PORT=${PORT:-8080}
NODE_IP=${NODE_IP:-http://localhost}
NAME=${NAME:-SCUBA}
DESC=${DESC:-This is the description for the new node}
COLOR=${COLOR:-#21c2c3}

HOST_URL=${HOST_URL:-http://localhost:18088}
HOST_SERVICE=${HOST_SERVICE:-/taniwha}

exec java -jar /app/TANIWHA_Backend_node.jar \
  --server.port=$PORT \
  --node.ip="$NODE_IP" \
  --name="$NAME" \
  --desc="$DESC" \
  --node.color="$COLOR" \
  --host.url="$HOST_URL" \
  --host.service="$HOST_SERVICE"
