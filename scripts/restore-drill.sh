#!/usr/bin/env bash
# Disposable PostgreSQL backup/restore acceptance. Never targets an existing deployment.
set -euo pipefail
cd "$(dirname "$0")/.."
drill_image=${1:?Usage: bash scripts/restore-drill.sh LOCAL_APPLICATION_IMAGE}
for dependency in docker curl jq openssl; do command -v "$dependency" >/dev/null; done
drill_prefix="openservice-restore-$(date +%s)-$$"
drill_directory=$(mktemp -d)
drill_password=$(openssl rand -hex 24)
drill_secret=$(openssl rand -hex 32)
drill_started=$(date +%s)
cleanup() {
    docker rm -f "$drill_prefix-source-app" "$drill_prefix-restored-app" "$drill_prefix-source-db" "$drill_prefix-restored-db" >/dev/null 2>&1 || true
    docker volume rm "$drill_prefix-source-data" "$drill_prefix-restored-data" >/dev/null 2>&1 || true
    docker network rm "$drill_prefix" >/dev/null 2>&1 || true
    rm -rf "$drill_directory"
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
drill_image=$(docker image inspect "$drill_image" --format '{{.Id}}')
if ! docker image inspect postgres:17-alpine >/dev/null 2>&1; then docker pull postgres:17-alpine >/dev/null; fi
drill_database_image=$(docker image inspect postgres:17-alpine --format '{{.Id}}')
docker network create "$drill_prefix" >/dev/null
start_database() {
    drill_side=$1
    docker volume create "$drill_prefix-$drill_side-data" >/dev/null
    docker run -d --name "$drill_prefix-$drill_side-db" --network "$drill_prefix" \
        -e POSTGRES_USER=openservice -e POSTGRES_DB=openservice -e POSTGRES_PASSWORD="$drill_password" \
        -v "$drill_prefix-$drill_side-data:/var/lib/postgresql/data" "$drill_database_image" >/dev/null
    drill_attempt=0
    until docker exec "$drill_prefix-$drill_side-db" pg_isready -U openservice >/dev/null 2>&1; do
        drill_attempt=$((drill_attempt + 1))
        [ "$drill_attempt" -lt 90 ] || { echo 'Database readiness timed out' >&2; exit 1; }
        sleep 1
    done
}
start_application() {
    drill_side=$1
    docker run -d --name "$drill_prefix-$drill_side-app" --network "$drill_prefix" \
        -p 127.0.0.1::8080 -e ADMIN_PASSWORD="$drill_password" -e SECRET_KEY="$drill_secret" \
        -e DATABASE_URL="postgresql://openservice:$drill_password@$drill_prefix-$drill_side-db:5432/openservice" \
        "$drill_image" >/dev/null
    drill_address="http://$(docker port "$drill_prefix-$drill_side-app" 8080/tcp)"
    drill_attempt=0
    until curl --max-time 3 -fsS "$drill_address/healthz" >/dev/null 2>&1; do
        drill_attempt=$((drill_attempt + 1))
        [ "$drill_attempt" -lt 90 ] || { echo 'Application readiness timed out' >&2; exit 1; }
        sleep 1
    done
    [ "$(docker exec "$drill_prefix-$drill_side-app" id -u)" = 10001 ]
}
api() { curl --max-time 30 -fsS --user "admin:$drill_password" "$@"; }
snapshot() {
    api "$drill_address/api/openservice/configuration" | jq -S . > "$drill_directory/$1-configuration.json"
    api "$drill_address/api/now/table/u_service_case/$drill_case" | jq -S . > "$drill_directory/$1-record.json"
    api "$drill_address/api/openservice/security-events" | jq -S . > "$drill_directory/$1-security.json"
    api "$drill_address/api/openservice/analytics/u_service_case?field=estimate" | jq -S . > "$drill_directory/$1-metrics.json"
    api "$drill_address/api/now/attachment/$drill_attachment/file" > "$drill_directory/$1-attachment.bin"
}
start_database source
start_application source
api -X POST -H 'Content-Type: application/json' --data-binary @examples/service-operations.configuration.json \
    "$drill_address/api/openservice/configuration?dry_run=false" | jq -e '.result.dry_run == false' >/dev/null
jq -n --arg password "$drill_password" '{name:"RestoreReader",password:$password,role:"reader"}' > "$drill_directory/account.json"
api -X POST -H 'Content-Type: application/json' --data-binary "@$drill_directory/account.json" \
    "$drill_address/api/openservice/users" | jq -e '.result.name == "RestoreReader"' >/dev/null
drill_asset=$(api -X POST -H 'Content-Type: application/json' \
    --data '{"short_description":"Restore asset","serial_number":"restore-001"}' \
    "$drill_address/api/now/table/u_service_asset" | jq -er '.result.sys_id')
jq -n --arg asset "$drill_asset" '{short_description:"Restore case",asset:$asset,estimate:"0.10"}' > "$drill_directory/case.json"
drill_case=$(api -X POST -H 'Content-Type: application/json' --data-binary "@$drill_directory/case.json" \
    "$drill_address/api/now/table/u_service_case" | jq -er '.result.sys_id')
drill_admin=$(api "$drill_address/api/openservice/me" | jq -er '.result.user_id')
jq -n --arg owner "$drill_admin" '{visibility:"private",owner_id:$owner}' > "$drill_directory/access.json"
api -X PATCH -H 'Content-Type: application/json' --data-binary "@$drill_directory/access.json" \
    "$drill_address/api/openservice/access/u_service_case/$drill_case" >/dev/null
printf '\000\001OpenService restore bytes\377\n' > "$drill_directory/payload.bin"
drill_attachment=$(api -X POST -H 'Content-Type: application/octet-stream' --data-binary "@$drill_directory/payload.bin" \
    "$drill_address/api/now/attachment/file?table_name=u_service_case&table_sys_id=$drill_case&file_name=restore.bin" | jq -er '.result.sys_id')
snapshot source
docker stop "$drill_prefix-source-app" >/dev/null
docker exec "$drill_prefix-source-db" pg_dump -U openservice -d openservice -Fc > "$drill_directory/backup.dump"
docker stop "$drill_prefix-source-db" >/dev/null
start_database restored
docker exec -i "$drill_prefix-restored-db" pg_restore --exit-on-error --no-owner -U openservice -d openservice < "$drill_directory/backup.dump"
start_application restored
snapshot restored
for artifact in configuration.json record.json security.json metrics.json attachment.bin; do
    cmp "$drill_directory/source-$artifact" "$drill_directory/restored-$artifact"
done
cmp "$drill_directory/payload.bin" "$drill_directory/restored-attachment.bin"
curl --max-time 30 -fsS --user "RestoreReader:$drill_password" "$drill_address/api/openservice/me" | jq -e '.result.name == "RestoreReader"' >/dev/null
drill_status=$(curl --max-time 30 -sS -o /dev/null -w '%{http_code}' --user "RestoreReader:$drill_password" \
    "$drill_address/api/now/table/u_service_case/$drill_case")
[ "$drill_status" = 404 ]
jq -e '.result.rows[0].sum == "0.1" and .result.rows[0].count == "1"' "$drill_directory/restored-metrics.json" >/dev/null
echo "PASS: restored configuration, records, security events, metrics, attachment bytes, account credentials and private access."
echo "Disposable drill completed in $(($(date +%s) - drill_started)) seconds; this is not a production recovery SLA."
