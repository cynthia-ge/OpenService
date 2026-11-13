# API scope — foundation 0.1

Use JSON and authenticated Basic requests, or a logged-in session with `X-CSRF-Token` for writes. Table names are listed at `GET /api/openservice/schema`. Administrator can create/delete/update; agent can create/update; reader can read. All accounts currently share the same instance and records.

| Route | Behavior |
|---|---|
| `GET /api/now/table/{table}` | List records in a `result` array; `X-Total-Count` and pagination links |
| `POST /api/now/table/{table}` | Create a record; 201, result object and Location |
| `GET /api/now/table/{table}/{sys_id}` | Get one record and an ETag revision |
| `PATCH` or `PUT` at the same route | Update provided fields; optional `If-Match: "revision"` |
| `DELETE` at the same route | Administrator-only deletion; 204; preserves deletion audit |
| `GET /api/openservice/schema` | OpenService-owned dictionary; not the native sys_dictionary API |
| `POST /api/openservice/users` | Admin-only creation with name, password (12+ characters) and admin/agent/reader role |
| `POST /api/openservice/import/{table}?dry_run=true` | Preview migration; `false` commits an error-free batch |
| `GET /export/{table}` | Export current records merged with preserved source-only fields |
| `GET /export/{table}?source=true` | Export original imported rows; native records use current values |
| `GET /healthz` | Unauthenticated database readiness check |

List parameters: `sysparm_query`, `sysparm_fields`, `sysparm_limit` (1–10000; default 10000), `sysparm_offset` (default 0). A single-record GET supports only `sysparm_fields`. Unsupported parameters fail rather than silently changing results.

The query subset supports `field=value`, `field!=value`, string `LIKE`, `STARTSWITH`, `^` conjunction, `ORDERBYfield` and `ORDERBYDESCfield`. This is not the full encoded-query grammar. No OR/NQ, dot-walking, dynamic filters, JavaScript, ACL-dependent counts or reference display modes. Sorting and filtering execute in the database. SQLite and PostgreSQL may differ in text collation.

Writes accept only known editable fields, with string values. Unknown/read-only fields and unsupported query parameters fail. A non-empty summary is required. State transitions are constrained by the OpenService dictionary, and change approval/rejection needs an admin. This intentionally differs from unconstrained native table writes and does not reproduce ServiceNow business rules.

The current error envelope is `{error:{message,detail},status:"failure"}`; messages and exact error semantics are OpenService-specific. No XML, versioned API aliases, OAuth, request impersonation or native script execution is claimed. Source reference: [Australia Table API](https://www.servicenow.com/docs/r/australia/api-reference/rest-apis/c_TableAPI.html), retrieved 2026-11-13; the unversioned documentation URL returned Brazil, so Australia was explicitly selected.
