# OpenService

A service operations foundation for Cynthia: clear workspaces, shared records, controlled workflows and a practical path from ServiceNow exports.

## Run locally

Requires Docker Compose and Python 3.12+ for the credential setup helper.

```bash
python3 scripts/setup-local.py && docker compose up --build -d --wait
```

Open **http://localhost:8090**. Sign in as **admin**, using `ADMIN_PASSWORD` from the generated `.env` file. The file is ignored by Git, created with private permissions, and never overwritten by the setup helper. PostgreSQL has no host port; the application binds to loopback only. Changing `ADMIN_PASSWORD` after initialization does not reset the existing database user's password.

`docker compose down` preserves records. The named `database` volume stores records, audit events, attachment bytes and original migration payloads. Do not use `down -v` unless you intend to erase this local data. Back up PostgreSQL before upgrades.

## What works

- Incident, problem, change, request, configuration-item, knowledge and group workspaces.
- Create/edit forms, summary search, pagination, record details and responsive navigation.
- State transitions and administrator approval/rejection for changes, enforced in the API as well as the UI.
- Administrator, agent and reader accounts; password hashes, sessions, CSRF protection and authenticated Basic API access.
- Activity notes, persistent attachments, audit events and JSON export.
- A shared schema registry used by validation, API services and generated forms.
- A bounded Table API subset: CRUD, selected encoded-query operators, sorting, field projection and pagination.
- JSON/CSV migration with dry runs, all-or-nothing validation, existing-record skipping, retained IDs and source-payload export.
- Cloud-specific Terraform foundations for AWS, GCP and Azure, backed by managed PostgreSQL.

This is an independently implemented **foundation**, not a full ServiceNow replacement. Most record fields are strings. Groups and references are currently stored values, not an assignment/identity engine. Problem/change/request state values are OpenService defaults. Knowledge is plain text; there is no public portal. Read the [compatibility ledger](docs/servicenow-compatibility.md) before integrating existing clients.

## API example

Use your configured password when curl prompts; do not put passwords into shell history.

```bash
curl -u admin http://localhost:8090/api/now/table/incident \
  -H 'Content-Type: application/json' \
  -d '{"short_description":"Remote access unavailable","priority":"2"}'

curl -u admin --get http://localhost:8090/api/now/table/incident \
  --data-urlencode 'sysparm_query=priority=2^ORDERBYDESCsys_created_on' \
  --data-urlencode 'sysparm_fields=sys_id,number,short_description'
```

See [API scope](docs/api.md), [migration guide](docs/migration.md), [architecture](docs/architecture.md) and [Terraform deployments](terraform/README.md).

## Development and verification

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -e '.[test]'
.venv/bin/python -m coverage run -m pytest -q
.venv/bin/python -m coverage report
.venv/bin/python -m coverage json
```

The fast suite uses isolated SQLite databases. Run the same acceptance tests against PostgreSQL using isolated temporary schemas:

```bash
docker compose --profile test build test
docker compose --profile test run --rm test
```

Browser journey (creates clearly synthetic sample records through the actual migration API and leaves those samples in the local demo):

```bash
.venv/bin/python -m playwright install chromium
.venv/bin/python tests/browser_journey.py
```

Screenshots are written to ignored `artifacts/`. The journey tests login, creation, transitions, notes, a migration preview and a mobile viewport. It never contacts a ServiceNow instance.

```bash
terraform -chdir=terraform/aws init -backend=false
terraform -chdir=terraform/aws test
# Repeat for terraform/gcp and terraform/azure.
```

## Operational scope

Cloud configurations provide HTTPS; secure cookies are enabled outside the loopback Docker demo. Each deployment starts with a single application instance and four threads. PostgreSQL locks protect record updates; optional `If-Match` revisions prevent lost updates. Schema creation is an initial bootstrap, not an upgrade migration system. Coordinate upgrades with a database backup and downtime until versioned migrations are added.

SSO, granular ACL/domain isolation, rate limiting, malware scanning, background workers, email delivery, business-rule scripts and high availability are not implemented. Treat this version as a local evaluation foundation, not a hardened production service. No licence has been selected yet; source publication or licensing is a separate decision.
