#!/bin/sh
set -eu

echo "================================================"
echo "MEDIATA Node - Startup Script"
echo "================================================"

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

chmod 777 \
  /taniwha/datasets \
  /taniwha/mapped_datasets \
  /taniwha/fhir_mappings \
  /taniwha/dataset_elements \
  /taniwha/dataset_metadata || true

echo "------------------------------------------------"
echo "Node work directories ready under /taniwha"
echo "------------------------------------------------"

PORT=${PORT:-8080}
NODE_IP=${NODE_IP:-http://localhost}
NAME=${NAME:-SCUBA}
DESC=${DESC:-This is the description for the new node}
COLOR=${COLOR:-#21c2c3}

echo "================================================"
echo "Starting MEDIATA Node"
echo "------------------------------------------------"
echo "PORT:     ${PORT}"
echo "NODE_IP:  ${NODE_IP}"
echo "NAME:     ${NAME}"
echo "DESC:     ${DESC}"
echo "COLOR:    ${COLOR}"
echo "================================================"

exec java -jar /app/TANIWHA_Backend_node.jar \
  --PORT="$PORT" \
  --NODE_IP="$NODE_IP" \
  --NAME="$NAME" \
  --DESC="$DESC" \
  --COLOR="$COLOR"
