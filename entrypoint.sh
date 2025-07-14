#!/bin/sh
mkdir -p /taniwha/datasets /taniwha/mapped_datasets /taniwha/fhir_mappings /taniwha/dataset_elements /taniwha/dataset_metadata
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

exec java -jar /app/TANIWHA_Backend_node.jar --PORT=$PORT --NODE_IP=$NODE_IP --NAME="$NAME" --DESC="$DESC" --COLOR=$COLOR
