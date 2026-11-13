# API scope — Java foundation 0.2

Use JSON and authenticated Basic requests, or a logged-in session with `X-CSRF-Token` for writes. Table names are listed at `GET /api/openservice/schema`. Administrator can create/delete/update; agent can create/update; reader can read. All accounts currently share the same instance and records.

| Route | Behavior |
|---|---|
| `GET /api/now/table/{table}` | List records in a `result` array; `X-Total-Count` and pagination links |
| `POST /api/now/table/{table}` | Create a record; 201, result object and Location |
| `GET /api/now/table/{table}/{sys_id}` | Get one record and an ETag revision |
| `PATCH` or `PUT` at the same route | Update provided fields; optional `If-Match: "revision"` |
| `DELETE` at the same route | Administrator-only deletion; 204; preserves deletion audit |
| `GET /api/openservice/schema` | OpenService-owned dictionary; not the native sys_dictionary API |
| `POST /api/openservice/users` | Admin-only creation with name, password (12–1024 characters) and admin/agent/reader role |
| `POST /api/openservice/import/{table}?dry_run=true` | Preview migration; `false` commits an error-free batch |
| `GET /export/{table}` | Export current records merged with preserved source-only fields |
| `GET /export/{table}?source=true` | Export original imported rows; native records use current values |
| `GET /healthz` | Unauthenticated database readiness check |

## Local accounts

`POST /api/openservice/users` requires exactly three JSON keys: `name`, `password`, and `role`. Names contain 1–80 ASCII letters or digits and are case-sensitive; passwords contain 12–1024 characters; roles are `admin`, `agent`, or `reader`. A successful creation returns 201 with `{"result":{"name":"...","role":"..."}}`. Invalid fields or a duplicate name return 400 using the existing error envelope. Duplicate creation never changes an existing account. The 1024-character maximum is now enforced on both the API and browser form; the earlier API had no explicit maximum.

The original OpenService browser workspace is `GET /admin/accounts`: administrator-only, 25 users per page ordered by stored username, with the current account identified. Only names and roles are selected for the list; password hashes are never rendered. Optional `page` defaults to `1`; only a single positive integer of up to six digits is accepted, and pages beyond the list return 400. Unknown parameters also return 400. Database collation determines name ordering.

`POST /admin/accounts` accepts form fields `name`, `password`, and `role` plus the session CSRF token. The role defaults to `reader`. Success redirects (302) to the current account-list page and displays a confirmation; invalid input returns the form with an inline error (400), preserving the username/role but never the password. Both creation routes use the same validation, password hashing and persistence. Unauthenticated browser requests redirect to login; authenticated non-admins receive 403, and session writes without valid CSRF receive 403. Basic-authenticated requests retain the existing CSRF exemption.

This is local account onboarding, not a ServiceNow `sys_user` API. All roles can read all workspace records. Account editing, deactivation, password reset, SSO, role inheritance and per-record/field ACLs are not implemented.

## Table requests

List parameters: `sysparm_query`, `sysparm_fields`, `sysparm_limit` (1–10000; default 10000), `sysparm_offset` (default 0). A single-record GET supports only `sysparm_fields`. Unsupported parameters fail rather than silently changing results.

The query subset supports `field=value`, `field!=value`, string `LIKE`, `STARTSWITH`, `^` conjunction, `ORDERBYfield` and `ORDERBYDESCfield`. This is not the full encoded-query grammar. No OR/NQ, dot-walking, dynamic filters, JavaScript, ACL-dependent counts or reference display modes. Sorting and filtering execute in the database. SQLite and PostgreSQL may differ in text collation.

Writes accept only known editable fields, with string values. Unknown/read-only fields and unsupported query parameters fail. A non-empty summary is required. State transitions are constrained by the OpenService dictionary, and change approval/rejection needs an admin. This intentionally differs from unconstrained native table writes and does not reproduce ServiceNow business rules.

The current error envelope is `{error:{message,detail},status:"failure"}`; messages and exact error semantics are OpenService-specific. No XML, OAuth, request impersonation or native script execution is claimed. Source reference: [Australia Table API](https://www.servicenow.com/docs/r/australia/api-reference/rest-apis/c_TableAPI.html), retrieved 2026-11-13; the unversioned documentation URL returned Brazil, so Australia was explicitly selected.

## Count aggregates

`GET /api/now/stats/{table}?sysparm_count=true` returns `{result: {stats: {count: "N"}}}`. Supports the existing encoded `sysparm_query`, comma-separated `sysparm_group_by`, and `sysparm_order_by=COUNT` or a grouped field, optionally suffixed `^DESC`. Grouped results are arrays with `stats` and `groupby_fields`; empty grouped results are `[]`. Counts use the full selection, independent of Table API pagination. `sysparm_display_value=false` is the only supported display mode. Unknown/repeated parameters, invalid groups, and other aggregate functions fail explicitly. Existing authentication and reader permissions apply.

### Membership filters

Table and Aggregate APIs accept `fieldINa,b` and `fieldNOT INa,b` in sysparm_query. These match stored literal values; list order and duplicate values do not duplicate records. Combine with existing ^ AND and ORDERBY terms. List membership is evaluated in the database before pagination/grouping/counts. Supports 1–100 nonempty comma-separated values, without whitespace trimming or comma/caret escaping. Unknown fields and malformed lists return 400. There is no display-value/reference resolution, dynamic JavaScript, hierarchy membership or OR grammar. Empty stored strings are ordinary values and satisfy NOT IN when absent from the list; SQL NULLs do not match either operator.


## Attachment API

Authenticated `GET /api/now/attachment` lists metadata in `result`, with optional `sysparm_query` exact equality filters on `sys_id`, `file_name`, `table_name` and `table_sys_id`, joined by `^`. Pagination uses `sysparm_limit` (1–1000; default 1000) and nonnegative `sysparm_offset`. Field projection is not supported. Unknown/repeated parameters, malformed filters and unsupported fields return 400. Results exclude attachments whose parent record is absent.

`GET /api/now/attachment/{sys_id}` returns one metadata object. `GET /api/now/attachment/{sys_id}/file` returns stored bytes using the recorded content type (legacy/browser uploads default to `application/octet-stream`), a sanitized attachment filename and an `X-Attachment-Metadata` JSON header. All local roles may read; unauthenticated API requests receive 401, missing/orphan attachments 404. Parent-record deletion removes its attachments. This is a bounded local read contract; binary upload is described below; attachment updates are not implemented. Browser attachment upload remains supported for agents and administrators.

### Binary attachment upload

`POST /api/now/attachment/file?table_name=incident&table_sys_id=<id>&file_name=<name>` accepts raw bytes, including an empty file and files up to 5 MiB. All three query parameters must be nonblank and unique; other parameters fail with 400. Supply a concrete valid `Content-Type` of at most 255 characters; missing/invalid media types return 415. Multipart form uploads are rejected here with 415; this endpoint accepts a raw file body. The parent must exist in the requested table (404 otherwise). Admin and agent may upload, readers receive 403, unauthenticated requests 401. Session clients must supply `X-CSRF-Token`; raw file bytes are never parsed as form fields.

Success is 201 with `{result: <metadata>}` and `Location` pointing to the metadata endpoint. Stored bytes and content type round-trip through both download routes. Filenames use the existing safe ASCII normalization and 255-character cap. Oversize files return 413. Persistence and the attachment audit event commit together; the parent revision is unchanged. Startup adds a content-type column to existing SQLite/PostgreSQL attachment tables, defaulting old rows to octet-stream. Run one instance during this upgrade; it is not distributed migration coordination. Limits, role mappings and filename rules are OpenService choices; there is no malware scanner or vendor-instance parity claim.

### Attachment deletion

`DELETE /api/now/attachment/{sys_id}` is restricted to local administrators and returns 204 with no body. It removes only that attachment, preserves its parent and prior events, and records an `attachment_deleted` event with ID, name and actor in the same transaction. Missing/orphan files return 404. Agents/readers receive 403, missing authentication 401; session mutations require CSRF. Query parameters fail with 400; DELETE on the collection or `/file` route returns 405. Authorization is OpenService's local policy, not a reproduction of ServiceNow ACLs.

### Multipart attachment upload

`POST /api/now/attachment/upload` accepts `multipart/form-data` with exactly the form fields `table_name` and `table_sys_id` and exactly one file part named `uploadFile`, matching the official Australia example. The file part must have a nonblank filename and a concrete Content-Type. Query parameters, repeated/extra fields, missing/multiple/unexpected file parts and blank fields return 400; non-multipart requests or missing/invalid file media types return 415. Missing parents return 404. Binary limits (empty through 5 MiB), filename normalization, role/CSRF checks, metadata/Location, persistence and audit behavior match the raw-file endpoint. Session clients use the `X-CSRF-Token` header. Responses contain the existing local metadata subset, not every native vendor field.

## Versioned Table API routes

The existing Table CRUD subset also accepts `/api/now/v1/table/{table}[/{sys_id}]` and `/api/now/v2/table/{table}[/{sys_id}]`. They share stored records, authentication, role/CSRF rules, input validation, field projection and revision checks. Location and pagination links retain the requested version. In accordance with the Australia reference, a v1 collection read with no returned records yields 404; v2 and unversioned collection reads return 200 with an empty result array. Unsupported versions return 404. These routes implement the documented local subset, not every historical behavior of either vendor API version. Versioned Attachment and Aggregate routes remain outside this slice.
