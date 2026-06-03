#!/usr/bin/env bash
set -euo pipefail

PORT=${PORT:-8080}
NODE_IP=${NODE_IP:-http://localhost}
NAME=${NAME:-SCUBA}
DESC=${DESC:-This is the description for the new node}
COLOR=${COLOR:-#21c2c3}

FAIRDATAPOINT_ENABLED=${FAIRDATAPOINT_ENABLED:-true}
FDP_HOST=${FDP_HOST:-127.0.0.1}
FDP_PORT=${FDP_PORT:-18080}
FDP_MONGO_PORT=${FDP_MONGO_PORT:-37017}
FDP_URL=${FDP_URL:-http://${FDP_HOST}:${FDP_PORT}}
FDP_MONGO_DBPATH=${FDP_MONGO_DBPATH:-/taniwha/fairdatapoint/mongo}
FDP_NATIVE_DIR=${FDP_NATIVE_DIR:-/taniwha/fairdatapoint/rdf-store}
FDP_LOG_DIR=${FDP_LOG_DIR:-/var/log/taniwha}
FDP_START_DELAY_S=${FDP_START_DELAY_S:-0}
SPRING_DATA_MONGODB_URI=${SPRING_DATA_MONGODB_URI:-mongodb://127.0.0.1:${FDP_MONGO_PORT}/fdp}
INSTANCE_CLIENTURL=${INSTANCE_CLIENTURL:-${FDP_URL}}
INSTANCE_PERSISTENTURL=${INSTANCE_PERSISTENTURL:-${INSTANCE_CLIENTURL}}
FAIRDATAPOINT_BASE_URL=${FAIRDATAPOINT_BASE_URL:-${FDP_URL}}
FAIRDATAPOINT_PERSISTENT_URL=${FAIRDATAPOINT_PERSISTENT_URL:-${INSTANCE_PERSISTENTURL}}

export PORT
export NODE_IP
export NAME
export DESC
export COLOR
export FAIRDATAPOINT_ENABLED
export FDP_HOST
export FDP_PORT
export FDP_MONGO_PORT
export FDP_URL
export FDP_MONGO_DBPATH
export FDP_NATIVE_DIR
export FDP_LOG_DIR
export FDP_START_DELAY_S
export SPRING_DATA_MONGODB_URI
export INSTANCE_CLIENTURL
export INSTANCE_PERSISTENTURL
export FAIRDATAPOINT_BASE_URL
export FAIRDATAPOINT_PERSISTENT_URL

NODE_PID=""
FDP_LAUNCH_PID=""
FDP_JAVA_PID_FILE="/tmp/taniwha-fdp-java.pid"

echo "================================================"
echo "MEDIATA Node - Unified Startup Script"
echo "================================================"

WORKDIRS="
/taniwha/datasets
/taniwha/mapped_datasets
/taniwha/fhir_mappings
/taniwha/dataset_elements
/taniwha/dataset_metadata
/taniwha/fairdatapoint
${FDP_MONGO_DBPATH}
${FDP_NATIVE_DIR}
${FDP_LOG_DIR}
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

shutdown_all() {
  set +e
  if [ -n "${NODE_PID}" ] && kill -0 "${NODE_PID}" 2>/dev/null; then
    kill "${NODE_PID}" 2>/dev/null || true
    wait "${NODE_PID}" 2>/dev/null || true
  fi

  if [ -n "${FDP_LAUNCH_PID}" ] && kill -0 "${FDP_LAUNCH_PID}" 2>/dev/null; then
    kill "${FDP_LAUNCH_PID}" 2>/dev/null || true
    wait "${FDP_LAUNCH_PID}" 2>/dev/null || true
  fi

  if [ -f "${FDP_JAVA_PID_FILE}" ]; then
    local fdp_java_pid
    fdp_java_pid="$(cat "${FDP_JAVA_PID_FILE}" 2>/dev/null || true)"
    if [ -n "${fdp_java_pid}" ] && kill -0 "${fdp_java_pid}" 2>/dev/null; then
      kill "${fdp_java_pid}" 2>/dev/null || true
      wait "${fdp_java_pid}" 2>/dev/null || true
    fi
  fi

  if command -v mongod >/dev/null 2>&1 && [ -f /tmp/taniwha-mongod.pid ]; then
    mongod --shutdown \
      --dbpath "${FDP_MONGO_DBPATH}" \
      --pidfilepath /tmp/taniwha-mongod.pid >/dev/null 2>&1 || true
  fi
}

trap shutdown_all EXIT INT TERM

wait_for_http() {
  local url=$1
  local attempts=${2:-60}
  local sleep_seconds=${3:-2}
  local status

  for ((i=1; i<=attempts; i++)); do
    status=$(curl -s -o /dev/null -w '%{http_code}' "$url" || true)
    if [[ "$status" != "000" ]]; then
      return 0
    fi
    sleep "$sleep_seconds"
  done

  return 1
}

wait_for_mongo() {
  local attempts=${1:-60}
  local sleep_seconds=${2:-1}

  for ((i=1; i<=attempts; i++)); do
    if mongosh --quiet "${SPRING_DATA_MONGODB_URI}" --eval 'db.runCommand({ping: 1}).ok' >/dev/null 2>&1; then
      return 0
    fi
    sleep "$sleep_seconds"
  done

  return 1
}

mongo_eval() {
  mongosh --quiet "${SPRING_DATA_MONGODB_URI}" --eval "$1"
}

fdp_read_token() {
  local response
  response=$(curl -sS -X POST "${FDP_URL}/tokens" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"${FAIRDATAPOINT_EMAIL:-albert.einstein@example.com}\",\"password\":\"${FAIRDATAPOINT_PASSWORD:-password}\"}")

  RESPONSE_JSON="$response" python3 - <<'PY'
import json
import os
import sys

raw = os.environ.get("RESPONSE_JSON", "")
try:
    data = json.loads(raw)
except json.JSONDecodeError as exc:
    print(f"Unable to parse FDP token response: {exc}: {raw}", file=sys.stderr)
    sys.exit(1)

token = data.get("token")
if not token:
    print(f"FDP token was not returned: {raw}", file=sys.stderr)
    sys.exit(1)

print(token)
PY
}

fdp_repair_extend_schemas() {
  mongo_eval '
var updates = 0;

function sameArray(left, right) {
  if (!Array.isArray(left) || left.length !== right.length) {
    return false;
  }
  for (var i = 0; i < left.length; i += 1) {
    if (left[i] !== right[i]) {
      return false;
    }
  }
  return true;
}

function schemaUuid(name) {
  var doc = db.metadataSchema.findOne({name: name}, {uuid: 1});
  return doc && doc.uuid;
}

function ensureSchemaExtends(name, expected) {
  var doc = db.metadataSchema.findOne({name: name}, {uuid: 1, extendSchemas: 1});
  if (!doc) {
    return;
  }
  if (sameArray(doc.extendSchemas, expected)) {
    return;
  }
  db.metadataSchema.updateOne({uuid: doc.uuid}, {$set: {extendSchemas: expected}});
  updates += 1;
}

var resource = schemaUuid("Resource");
var dataService = schemaUuid("Data Service");
var metadataService = schemaUuid("Metadata Service");

if (resource) {
  ensureSchemaExtends("Resource", []);
  ensureSchemaExtends("Catalog", [resource]);
  ensureSchemaExtends("Dataset", [resource]);
  ensureSchemaExtends("Distribution", [resource]);
  ensureSchemaExtends("Data Service", [resource]);
}

if (dataService) {
  ensureSchemaExtends("Metadata Service", [dataService]);
}

if (metadataService) {
  ensureSchemaExtends("FAIR Data Point", [metadataService]);
}

print(updates);
'
}

fdp_preflight() {
  local latest_count
  latest_count=$(mongo_eval 'db.metadataSchema.find({latest: true}).count()')

  if [[ "${latest_count}" == "0" ]]; then
    echo "[fdp-bootstrap] Marking metadata schemas as latest"
    mongo_eval 'db.metadataSchema.updateMany({}, {$set: {latest: true}})'
  fi

  local repaired_schemas
  repaired_schemas=$(fdp_repair_extend_schemas)
  if [[ "${repaired_schemas}" != "0" ]]; then
    echo "[fdp-bootstrap] Repaired ${repaired_schemas} metadata schema inheritance links"
  fi
}

fdp_bootstrap() {
  echo "[fdp-bootstrap] Checking local FDP services"
  wait_for_http "${FDP_URL}/v3/api-docs" 90 2

  local root_status
  root_status=$(curl -s -o /dev/null -w '%{http_code}' "${FDP_URL}/" || true)

  if [[ "${root_status}" != "200" ]]; then
    echo "[fdp-bootstrap] FDP root metadata is missing; repairing default metadata state"
    mongo_eval 'db.resourceDefinition.deleteOne({urlPrefix: ""}); db.metadata.deleteMany({});'

    local token
    token=$(fdp_read_token)

    local reset_body='{"metadata":true,"users":false,"resourceDefinitions":true,"settings":false}'
    local reset_status
    reset_status=$(curl -sS -o /tmp/fdp-reset-response.txt -w '%{http_code}' \
      -X POST "${FDP_URL}/reset" \
      -H "Authorization: Bearer ${token}" \
      -H 'Content-Type: application/json' \
      -d "${reset_body}")

    if [[ "${reset_status}" != "204" ]]; then
      echo "[fdp-bootstrap] FDP reset failed with HTTP ${reset_status}" >&2
      cat /tmp/fdp-reset-response.txt >&2 || true
      return 1
    fi
  fi

  root_status=$(curl -s -o /dev/null -w '%{http_code}' "${FDP_URL}/" || true)
  if [[ "${root_status}" != "200" ]]; then
    echo "[fdp-bootstrap] FDP root metadata is still unavailable after bootstrap" >&2
    curl -sS "${FDP_URL}/" || true
    return 1
  fi

  echo "[fdp-bootstrap] FDP bootstrap completed successfully"
}

fdp_bootstrap_background() {
  if ! wait_for_mongo 60 1; then
    echo "[fdp-bootstrap] Local MongoDB did not become ready in time" >&2
    return 1
  fi

  fdp_preflight

  if ! fdp_bootstrap; then
    echo "[fdp-bootstrap] FDP bootstrap failed; node will continue running and can be synced later" >&2
    return 1
  fi

  echo "[fdp-bootstrap] Background FDP bootstrap finished"
}

write_fdp_config() {
  cat > /tmp/taniwha-fdp-application.yml <<EOF
instance:
  clientUrl: ${INSTANCE_CLIENTURL}
  persistentUrl: ${INSTANCE_PERSISTENTURL}

repository:
  type: ${FDP_REPOSITORY_TYPE:-2}
  native:
    dir: ${FDP_NATIVE_DIR}

spring:
  data:
    mongodb:
      uri: ${SPRING_DATA_MONGODB_URI}

server:
  port: ${FDP_PORT}
EOF
}

start_fair_stack() {
  echo "------------------------------------------------"
  echo "Starting bundled FAIR Data Point services"
  echo "------------------------------------------------"
  echo "FDP URL:        ${FDP_URL}"
  echo "FDP Mongo URI:  ${SPRING_DATA_MONGODB_URI}"

  write_fdp_config
  rm -f /tmp/taniwha-mongod.pid "${FDP_JAVA_PID_FILE}"

  (
    if [[ "${FDP_START_DELAY_S}" =~ ^[0-9]+$ ]] && (( FDP_START_DELAY_S > 0 )); then
      echo "Delaying bundled FAIR Data Point startup by ${FDP_START_DELAY_S}s"
      sleep "${FDP_START_DELAY_S}"
    fi

    mongod \
      --bind_ip 127.0.0.1 \
      --port "${FDP_MONGO_PORT}" \
      --dbpath "${FDP_MONGO_DBPATH}" \
      --pidfilepath /tmp/taniwha-mongod.pid \
      --logpath "${FDP_LOG_DIR}/mongod.log" \
      --fork

    java -jar /opt/fdp/app.jar \
      --spring.profiles.active=production \
      --spring.config.location=classpath:/application.yml,classpath:/application-production.yml,file:/tmp/taniwha-fdp-application.yml \
      > "${FDP_LOG_DIR}/fdp.log" 2>&1 &
    echo "$!" > "${FDP_JAVA_PID_FILE}"

    fdp_bootstrap_background || true
  ) &
  FDP_LAUNCH_PID=$!
}

if [[ "${FAIRDATAPOINT_ENABLED}" == "true" ]]; then
  start_fair_stack
else
  echo "FAIR Data Point runtime disabled via FAIRDATAPOINT_ENABLED=${FAIRDATAPOINT_ENABLED}"
fi

echo "------------------------------------------------"
echo "Node work directories ready under /taniwha"
echo "------------------------------------------------"

echo "================================================"
echo "Starting MEDIATA Node"
echo "------------------------------------------------"
echo "PORT:                  ${PORT}"
echo "NODE_IP:               ${NODE_IP}"
echo "NAME:                  ${NAME}"
echo "DESC:                  ${DESC}"
echo "COLOR:                 ${COLOR}"
echo "FAIRDATAPOINT_ENABLED: ${FAIRDATAPOINT_ENABLED}"
echo "FDP_PORT:              ${FDP_PORT}"
echo "FDP_MONGO_PORT:        ${FDP_MONGO_PORT}"
echo "================================================"

java -jar /app/TANIWHA_Backend_node.jar \
  --PORT="${PORT}" \
  --NODE_IP="${NODE_IP}" \
  --NAME="${NAME}" \
  --DESC="${DESC}" \
  --COLOR="${COLOR}" &
NODE_PID=$!

wait "${NODE_PID}"
