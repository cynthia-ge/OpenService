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

The original OpenService browser workspace is `GET /admin/accounts`: administrator-only, 25 users per page ordered by stored username, with the current account identified. Only IDs, names, roles and activation state are selected for the list; password hashes are never rendered. Optional `page` defaults to `1`; only a single positive integer of up to six digits is accepted, and pages beyond the list return 400. Unknown parameters also return 400. Database collation determines name ordering.

`POST /admin/accounts` accepts form fields `name`, `password`, and `role` plus the session CSRF token. The role defaults to `reader`. Success redirects (302) to the current account-list page and displays a confirmation; invalid input returns the form with an inline error (400), preserving the username/role but never the password. Both creation routes use the same validation, password hashing and persistence. Unauthenticated browser requests redirect to login; authenticated non-admins receive 403, and session writes without valid CSRF receive 403. Basic-authenticated requests retain the existing CSRF exemption.

This is local account onboarding, not a ServiceNow `sys_user` API. All roles can read all workspace records. Account editing, deactivation, password reset, SSO, role inheritance and per-record/field ACLs are not implemented.

## Table requests

List parameters: `sysparm_query`, `sysparm_fields`, `sysparm_limit` (1–10000; default 10000), `sysparm_offset` (default 0), `sysparm_suppress_pagination_header` and `sysparm_no_count` (true/false; default false). A single-record GET supports only `sysparm_fields`. Unsupported parameters fail rather than silently changing results.

The query subset supports `field=value`, `field!=value`, string `LIKE`, `STARTSWITH`, `^` conjunction, `ORDERBYfield` and `ORDERBYDESCfield`. This is not the full encoded-query grammar. No OR/NQ, dot-walking, dynamic filters, JavaScript, ACL-dependent counts or reference display modes. Sorting and filtering execute in the database. SQLite and PostgreSQL may differ in text collation.

Writes accept only known editable fields, with string values. Unknown/read-only fields and unsupported query parameters fail. A non-empty summary is required. State transitions are constrained by the OpenService dictionary, and change approval/rejection needs an admin. This intentionally differs from unconstrained native table writes and does not reproduce ServiceNow business rules.

The current error envelope is `{error:{message,detail},status:"failure"}`; messages and exact error semantics are OpenService-specific. No XML, OAuth, request impersonation or native script execution is claimed. Source reference: [Australia Table API](https://www.servicenow.com/docs/r/australia/api-reference/rest-apis/c_TableAPI.html), retrieved 2026-11-13; the unversioned documentation URL returned Brazil, so Australia was explicitly selected.

## Count aggregates

`GET /api/now/stats/{table}?sysparm_count=true` returns `{result: {stats: {count: "N"}}}`. Supports the existing encoded `sysparm_query`, comma-separated `sysparm_group_by`, and `sysparm_order_by=COUNT` or a grouped field, optionally suffixed `^DESC`. Grouped results are arrays with `stats` and `groupby_fields`; empty grouped results are `[]`. Counts use the full selection, independent of Table API pagination. `sysparm_display_value=false` is the only supported display mode. Unknown/repeated parameters, invalid groups, and other aggregate functions fail explicitly. Existing authentication and reader permissions apply.

### Membership filters

Table and Aggregate APIs accept `fieldINa,b` and `fieldNOT INa,b` in sysparm_query. These match stored literal values; list order and duplicate values do not duplicate records. Combine with existing ^ AND and ORDERBY terms. List membership is evaluated in the database before pagination/grouping/counts. Supports 1–100 nonempty comma-separated values, without whitespace trimming or comma/caret escaping. Unknown fields and malformed lists return 400. There is no display-value/reference resolution, dynamic JavaScript, hierarchy membership or OR grammar. Empty stored strings are ordinary values and satisfy NOT IN when absent from the list; SQL NULLs do not match either operator.


## Attachment API

Authenticated `GET /api/now/attachment` lists metadata in `result`, with optional `sysparm_query` exact equality filters on `sys_id`, `file_name`, `table_name` and `table_sys_id`, joined by `^`, plus ordering on those fields. Pagination uses `sysparm_limit` (1–1000; default 1000) and nonnegative `sysparm_offset`. Field projection is not supported. Unknown/repeated parameters, malformed filters and unsupported fields return 400. Results exclude attachments whose parent record is absent.

`GET /api/now/attachment/{sys_id}` returns one metadata object. `GET /api/now/attachment/{sys_id}/file` returns stored bytes using the recorded content type (legacy/browser uploads default to `application/octet-stream`), a sanitized attachment filename and an `X-Attachment-Metadata` JSON header. All local roles may read; unauthenticated API requests receive 401, missing/orphan attachments 404. Parent-record deletion removes its attachments. This is a bounded local read contract; binary upload is described below; attachment updates are not implemented. Browser attachment upload remains supported for agents and administrators.

### Binary attachment upload

`POST /api/now/attachment/file?table_name=incident&table_sys_id=<id>&file_name=<name>` accepts raw bytes, including an empty file and files up to 5 MiB. All three query parameters must be nonblank and unique; other parameters fail with 400. Supply a concrete valid `Content-Type` of at most 255 characters; missing/invalid media types return 415. Multipart form uploads are rejected here with 415; this endpoint accepts a raw file body. The parent must exist in the requested table (404 otherwise). Admin and agent may upload, readers receive 403, unauthenticated requests 401. Session clients must supply `X-CSRF-Token`; raw file bytes are never parsed as form fields.

Success is 201 with `{result: <metadata>}` and `Location` pointing to the metadata endpoint. Stored bytes and content type round-trip through both download routes. Filenames use the existing safe ASCII normalization and 255-character cap. Oversize files return 413. Persistence and the attachment audit event commit together; the parent revision is unchanged. Startup adds a content-type column to existing SQLite/PostgreSQL attachment tables, defaulting old rows to octet-stream. Startup upgrades are now serialized and journaled; use planned downtime and the platform upgrade procedure. Limits, role mappings and filename rules are OpenService choices; there is no malware scanner or vendor-instance parity claim.

### Attachment deletion

`DELETE /api/now/attachment/{sys_id}` is restricted to local administrators and returns 204 with no body. It removes only that attachment, preserves its parent and prior events, and records an `attachment_deleted` event with ID, name and actor in the same transaction. Missing/orphan files return 404. Agents/readers receive 403, missing authentication 401; session mutations require CSRF. Query parameters fail with 400; DELETE on the collection or `/file` route returns 405. Authorization is OpenService's local policy, not a reproduction of ServiceNow ACLs.

### Multipart attachment upload

`POST /api/now/attachment/upload` accepts `multipart/form-data` with exactly the form fields `table_name` and `table_sys_id` and exactly one file part named `uploadFile`, matching the official Australia example. The file part must have a nonblank filename and a concrete Content-Type. Query parameters, repeated/extra fields, missing/multiple/unexpected file parts and blank fields return 400; non-multipart requests or missing/invalid file media types return 415. Missing parents return 404. Binary limits (empty through 5 MiB), filename normalization, role/CSRF checks, metadata/Location, persistence and audit behavior match the raw-file endpoint. Session clients use the `X-CSRF-Token` header. Responses contain the existing local metadata subset, not every native vendor field.

## Versioned Table API routes

The existing Table CRUD subset also accepts `/api/now/v1/table/{table}[/{sys_id}]` and `/api/now/v2/table/{table}[/{sys_id}]`. They share stored records, authentication, role/CSRF rules, input validation, field projection and revision checks. Location and pagination links retain the requested version. In accordance with the Australia reference, a v1 collection read with no returned records yields 404; v2 and unversioned collection reads return 200 with an empty result array. Unsupported versions return 404. These routes implement the documented local subset, not every historical behavior of either vendor API version. Versioned Attachment and Aggregate routes remain outside this slice.

## Empty-value query predicates

Table and count Aggregate APIs accept `fieldISEMPTY` and `fieldISNOTEMPTY`, with no operand. Missing JSON keys, JSON null and empty strings match ISEMPTY; whitespace and other stored text match ISNOTEMPTY. Predicates run in SQL before pagination/grouping/counting and combine with existing AND and ordering terms. Unknown fields, an appended operand, and unsupported OR syntax return 400. These are stored-value semantics; reference/display-value resolution and native scripting are not implied.

## Literal suffix queries

`fieldENDSWITHvalue` is available to Table and count Aggregate APIs and combines with AND, ordering and pagination. Values are bound parameters: percent, underscore, backslash, quotes and Unicode are literal characters. An empty suffix matches every non-null string, including an empty one; SQL nulls do not match. Unknown fields/operators return 400. String LIKE-family queries use the existing database comparison policy (SQLite's default ASCII-insensitive LIKE; PostgreSQL's case-sensitive LIKE); cross-database case folding and vendor collation parity are not claimed.

## Literal negative substring queries

`fieldNOT LIKEvalue` (with a space in `NOT LIKE`) selects non-null stored strings that do not contain the literal value. It shares the LIKE-family escaping/comparison policy above and runs before Table pagination and Aggregate counts. Empty stored strings match when the operand is nonempty; an empty operand selects no records. SQL null/missing values are excluded. Unknown fields and the unsupported spelling `NOTLIKE` return 400. AND composition and ordering are supported.

## Pagination-header suppression

For Table collection reads, `sysparm_suppress_pagination_header=true` omits the Link header. It preserves records, filtering, projection, pagination and X-Total-Count. Omitted or explicit false retains the existing links; generated links preserve the false flag. Only literal lowercase true/false is accepted; invalid/repeated values return 400. The flag applies to unversioned/v1/v2 collection routes, including empty v2/unversioned results, and is not supported on individual-record reads.

## Collection reads without a total-count query

`sysparm_no_count=true` skips SQL COUNT(*) and omits X-Total-Count. The query reads at most limit+1 rows, returns at most limit, and uses the extra row to decide whether to emit a next-page link. As a local pagination policy, first/previous/next links are available when applicable and the last link is omitted because the total is unknown. Links retain no_count and other query parameters. Combining it with pagination-header suppression omits both headers. Omitted/false preserves normal counting and links. Strict boolean validation and existing filters/projection/limits/versioned empty-result rules still apply; the flag is collection-only. This removes the count query, not the cost of large offsets or filtering.

## Attachment metadata ordering

Attachment collection `sysparm_query` accepts `ORDERBYfield` and `ORDERBYDESCfield` for sys_id, file_name, table_name and table_sys_id. Multiple ordering terms and equality filters combine with ^. Ordering runs in SQL before limit/offset; sys_id ascending is the stable final tie breaker, and the default order remains sys_id ascending. Unknown/empty/injected fields, unsupported size_bytes ordering, malformed terms and repeated query parameters fail with 400. File bytes are unaffected. This does not add full Table encoded-query grammar to attachments.

## Current account identity

`GET /api/openservice/me` accepts Basic authentication or an authenticated browser session and returns `{"result":{"user_id":"<32 hex characters>","name":"<username>","role":"admin|agent|reader"}}`. No query parameters are supported. Anonymous/invalid authentication returns 401. Identity IDs are generated by OpenService, unique and stable across restart; account-creation inputs and responses remain unchanged. Sessions bind to the ID, so username reuse cannot transfer an old session. Sessions created before the identity migration must sign in again. This endpoint is an OpenService extension.

## Browser session revocation

All local roles may use `GET /account` and `POST /account/sessions/revoke` with the session CSRF token, or `POST /api/openservice/me/sessions/revoke` with an empty JSON object. API success is `200 {"result":{"revoked":true}}`; browser success redirects to login. No query arguments or target-user parameters are accepted. The caller's browser sessions fail authentication on their next request, including the caller's current session. Credentials remain valid and new login is allowed. Existing sessions must sign in again after migration 3; revocation does not stop an already-running request, disable Basic authentication or replace deactivation.

## Administrative account state

Administrators may `PATCH /api/openservice/users/{user_id}/status` with exactly `{"active":true}` or `{"active":false}`. Success returns the target's ID, name, local role and boolean active state. Invalid input/query arguments return 400, unknown IDs 404, unauthorized actors 403, and attempts to deactivate the last active administrator 409. Identical state updates are idempotent. The `/admin/accounts/{user_id}` page offers the same operation with CSRF-protected forms.

Inactive accounts cannot authenticate through login, existing browser sessions or Basic requests. Reactivation preserves the identity/password but requires a fresh browser login. The account and its historic records are retained. State changes serialize and recheck administrator authority to protect the last active administrator under concurrent requests. This does not cancel already-running transactions or implement record/field ACLs.

## Administrative role changes

`PATCH /api/openservice/users/{user_id}/role` requires an active administrator and exactly `{"role":"reader"}`, `{"role":"agent"}` or `{"role":"admin"}`. It returns 200 with ID/name/role/active fields; errors follow the account-state endpoint. The final active administrator cannot be demoted. Effective changes revoke browser sessions; Basic credentials remain valid under the new permissions. Inactive accounts stay inactive. The account-detail page has a separate role form (`operation=role`) with CSRF protection. Repeating the current role does not revoke sessions.

## Self-service password changes

`POST /api/openservice/me/password` requires exactly `current_password` and `new_password`; the latter must differ and contain 12–1024 Unicode code points. Invalid input or an incorrect current secret returns 400, unauthenticated/inactive accounts return 401, and query parameters are rejected. Success is `200 {"result":{"changed":true}}`. Every role may change only its own password. Updates serialize; the previous credential cannot win two concurrent changes. All browser sessions are revoked and old Basic credentials fail. The `/account` form posts the same fields with CSRF to `/account/password`; success redirects to login, errors render without retaining passwords. Recovery without the current secret is a separate capability.

## Account security events

Administrator-only GET `/api/openservice/security-events` returns `{"result":[...],"next_before":"..."}` containing at most 100 rows ordered by descending database sequence allocation. Fields: `event_id`, `occurred_at` (UTC), `actor_id`, `actor_name`, `target_id`, `target_name`, `action`, `detail`. Cursor/download arguments are described below; event mutation methods are unavailable. Supported successful events include `account.created`, `account.state_changed`, `account.role_changed`, `account.password_changed` and `account.sessions_revoked`. Only state/role events carry before/after values; creation carries the role. No password or session secret is stored. The administrator Security events page renders the same window. Older events can be browsed/downloaded by cursor; authentication attempts have a separate journal. Tamper-resistant archival remains open.

## Browser-session lifetime

Browser authentication has an eight-hour absolute lifetime from successful login, checked on every request, plus the configured 30-minute servlet inactivity timeout. Expired or pre-upgrade timestamp-less sessions require a fresh login (browser redirect/API 401). The expired server session is invalidated and CSRF state renewed. Activity cannot extend the absolute lifetime. Basic requests authenticate independently and do not renew a browser session.

## Manual invitations

Administrator-only `/api/openservice/invitations` supports GET (latest 100 sanitized rows) and POST with exactly `name` and `role` (201 with metadata and a one-time `accept_path`). Names/roles follow local account rules; existing users return 400. A replacement revokes pending invitations for that username. No query arguments are accepted. `POST /api/openservice/invitations/{invite_id}/revoke` accepts an empty object and returns metadata; unknown IDs return 404 and accepted invitations 409. Repeated revocation preserves the first revoker. `/admin/invitations` provides the same creation/list/revoke workflow with CSRF-protected forms.

`GET /join/{token}` presents a valid invitation; POST requires its session CSRF token and a `password` of 12–1024 characters. Success creates the named account with the administrator-selected role and redirects to login without signing the recipient in automatically. Invalid, expired, used or revoked tokens return 400. Tokens expire after 24 hours and are invalidated by the issuer’s role/state/password/session-revocation changes. Re-activation or promotion does not restore an old link. Acceptance is single-use and transactional. Raw tokens appear only in the creation response/link, are stored only as SHA-256 digests, and are absent from lists/security events. Issuance/revocation metadata remains in the invitation journal; acceptance emits `account.created` and `account.invitation_accepted` security events. This manual channel does not send email or verify addresses.

## Administrator-assisted password recovery

`POST /api/openservice/users/{user_id}/recovery` requires an active administrator and exactly `{"current_password":"the administrator’s current password"}`. Success is 201 with a one-time relative `reset_path` and UTC `expires_at`. Incorrect confirmation/invalid bodies return 400, unknown users 404, inactive targets 409 and unauthorized actors 403. Query arguments are rejected. Replacement invalidates the prior link. `POST /api/openservice/users/{user_id}/recovery/revoke` accepts an empty object and returns `{"result":{"revoked":true}}`; it is idempotent for an existing account. Both actions are available on the account-detail page with CSRF protection; issuance uses `operation=recovery`.

`GET /recover/{token}` displays a valid link; POST accepts `password` and the session CSRF token. New passwords must differ and contain 12–1024 Unicode code points. Success redirects to login and atomically replaces the credential, revokes every browser session and consumes the token. Old Basic credentials fail. Invalid/expired/consumed/revoked links return 400. Links last 15 minutes and bind both issuer and target security revisions; they cannot reactivate users or survive intervening access/security changes. Raw tokens are stored only as SHA-256 digests, excluded from event details, and shown once at issuance. Events are `account.recovery_issued`, `account.recovery_revoked` and `account.password_recovered`. This flow provides neither automated identity verification nor mail delivery; an administrator verifies the recipient through the agreed support process.

## Record visibility

Administrator-only PATCH `/api/openservice/access/{table}/{sys_id}` accepts exactly `{"visibility":"private","owner_id":"active-user-id"}` or `{"visibility":"workspace","owner_id":""}`. It returns visibility, owner_id and owner username; invalid input returns 400, unauthorized actors 403, unknown records 404. Record-detail pages offer the same CSRF-protected operation by username. Private records are available only to their selected user and administrators, subject to existing role mutation restrictions. Lists/counts/exports/audit/attachments use the same visibility filter; direct denied resources return 404. Deleted private-record audit remains restricted. Field policies and shared tenancy are separate capabilities; group visibility is described below.

## Group membership

Administrator-only GET `/api/openservice/groups/{group_id}/members` lists `user_id`, `name`, `role`, and numeric `active` for members of an existing `sys_user_group` record. PUT accepts exactly `{"user_id":"active-user-id"}`; DELETE `/api/openservice/groups/{group_id}/members/{user_id}` removes membership. Writes return the resulting roster with 200. Repeats are idempotent; invalid input/inactive additions return 400, unauthorized roles 403, and missing/wrong-table groups 404. Query arguments are rejected. Group record pages provide CSRF-protected add/remove controls. Membership does not change account roles; deactivation preserves it, group deletion removes it. Audit records effective changes only.

Group visibility extends the record policy endpoint: PATCH with exactly `{"visibility":"group","group_id":"existing-group-id"}`. Responses now include `group_id` (empty for workspace/private); `owner_id` and `owner` are empty for group policies. The record form selects an existing group. Group members retain their existing reader/agent action permissions. Membership removal is enforced on the next request, including existing sessions; deletion of the group denies all non-administrators until an administrator changes the policy. Groups do not inherit account roles, and field visibility remains unavailable.

## Authentication attempt limits

Browser `/login` and parsed Basic credentials share five failed attempts per case-sensitive username in a fixed five-minute window. Later attempts return 429 with `Retry-After: 300`; invalid credentials before that return the existing generic 401. Blocked attempts never extend the window. Expiry is automatic, successful authentication clears failures before the threshold, and active browser sessions are unaffected. Counters persist across restart and are capped at 4096; at capacity new unsuccessful identities receive 429, while valid unblocked credentials remain usable. No external/client-supplied IP header is trusted for this policy. This does not yet rate-limit token redemption or authenticated password confirmation.

## Authentication event journal

Administrator-only GET `/api/openservice/authentication-events` returns `result` in pages of up to 100 password-verification events; `/admin/authentication-events` provides the same view. Cursor/download arguments are described below. Fields are `event_id`, `occurred_at`, `user_id`, `user_name`, `identity_hash`, `mechanism` (`browser`/`basic`) and `outcome` (`succeeded`/`failed`/`limited`). Unknown names have empty user fields and only a SHA-256 username fingerprint. The newest 10,000 events are retained. Events commit before business routing and persist even if authentication or the subsequent business request fails. Session reuse, malformed Basic headers, invalid CSRF and authenticated password confirmation are outside this journal. Credentials, cookies and tokens are never fields; no event modification/deletion API exists.

## Administrator account directory

GET `/api/openservice/users` is administrator-only. Optional `q` is a trimmed case-insensitive literal username substring of at most 80 Unicode code points (NUL rejected); `role` is empty/reader/agent/admin, `status` is empty/active/inactive, and `page` defaults to 1 (positive integer, at most six digits). Unknown or duplicate arguments, invalid filters and pages outside the filtered list return 400. The response has `result` (up to 25 sanitized accounts: user_id/name/role/numeric active), `total`, `page` and `pages`; empty results have one empty page. Existing POST creation is unchanged. `/admin/accounts` provides the same filters and preserves them in navigation and creation errors. These filters do not authorize non-administrators.

## Journal history and downloads

Both `/api/openservice/security-events` and `/api/openservice/authentication-events` now accept optional `before` (exclusive positive signed-64-bit sequence cursor) and `download=true|false` (default false). Responses remain `result` arrays of at most 100 events and add `next_before`; use a nonempty cursor as the next request's `before`. Empty means no older page at query time. Internal sequences are not added to event rows. `download=true` adds a fixed JSON attachment filename and returns the same selected page. Administrator HTML routes accept the same arguments and provide Older/Latest and Download this page links. Invalid/unknown/repeated arguments return 400. No all-history snapshot, external archive or additional retention is implied; the authentication journal still retains only its newest 10,000 events.

Invitation metadata additionally reports `invalidated` for an unexpired pending link whose issuer is missing, inactive, no longer admin or has changed authentication revision; `unavailable` means its username already has a local account. Expired takes precedence, while accepted/revoked remain terminal. These are derived display states, not new stored token states. Username conflicts also make public link GET/POST unavailable before password selection. Revocation accepts all unaccepted states; a fresh invitation can replace an issuer-invalidated link, while an existing account must be managed through account administration. Metadata still excludes token/hash material.


## Account group-access review and removal

Administrator-only GET `/api/openservice/users/{user_id}/memberships` returns `result` entries with `group_id` and `name`, ordered by group ID. It lists the account’s existing groups, including when the account is inactive. POST `/api/openservice/users/{user_id}/memberships/remove` requires an empty JSON object and returns `result: {removed_count, group_ids}`. It removes every membership atomically, preserving other users, account role/state and private ownership. Only effective changes emit `account.memberships_removed` in the account security journal. Repeated removal returns zero. Unknown accounts return 404, unauthorized roles 403, unexpected fields/query arguments 400 and unsupported methods 405. The account page provides the same list and CSRF-protected action. Current membership is enforced on subsequent record requests; reactivation does not restore memberships.


## Private-record access handover

Administrator-only POST `/api/openservice/users/{user_id}/private-records/transfer` accepts exactly `{"new_owner_id":"active-account-id"}`. The source may be inactive; the recipient must be a different existing active account. Success returns 200 with `result: {transferred_count, record_ids, new_owner_id}`; IDs are sorted. It transfers access ownership of live private records and their attachment access atomically, preserving original business fields, creators and history. It excludes workspace/group policies and already-deleted record audit tombstones; liveness is evaluated by the transfer statement, so a concurrent deletion may finish with the transferred policy. Effective transfers emit one account security event; repeats return zero. Invalid input/recipient/query arguments return 400, unknown source 404, unauthorized roles 403 and unsupported methods 405. The account page displays the live count and provides a CSRF-protected username-based action. This does not change account state, roles, group membership or business assignment fields. Subsequent requests use the new policy; already-running requests may finish.

## Configuration packages

Administrator `GET /api/openservice/configuration` exports current definitions and versions; `POST` previews by default, or atomically installs with `dry_run=false`. Definitions govern existing record API validation and workflows. See [the full configuration contract](configuration.md), including dependency checks, destination-version conflicts, typed string values, reference access, assignment eligibility and import limits.

## Typed numeric reports

Authenticated `GET /api/openservice/analytics/{table}?field=estimate` reports count, value count, sum, min, max and average over all authorized matching rows. Optional `group_by` and `q` select grouping and the existing encoded-query filter. See [analytics](analytics.md) for numeric precision, blanks, empty selections and limits.

### Internal activity visibility

Local internal-note events are visible only to agents and administrators who can access the record. Readers receive ordinary record activity but no internal-note text in either record detail or `/audit`. This rule does not classify arbitrary imported fields, hide ordinary attachments or implement a full field ACL engine.

### Shared comments

Authenticated readers, agents and administrators may append comments to records they can see. `GET /api/openservice/comments/{table}/{sys_id}` returns `{result:[...]}` with the newest 100 comments in descending `entry_id` order. Each row has `entry_id`, `actor`, `created` (UTC) and `detail.body`. Internal notes are never returned. `POST` accepts exactly `{"body":"text"}`, strips surrounding whitespace, requires 1–65,536 Unicode code points and returns the created row under `result` with status 201. Unknown query parameters are rejected; other methods return 405. Missing/inaccessible records return 404.

The record page provides the same append-only journey. Browser mutations require CSRF; Basic authentication uses the existing API rules. Readers still cannot change record fields or add internal notes. Shared means visible to accounts with record access, not anonymous/public internet access. Comments do not increment field revisions, send notifications or provide edit/delete, pagination or idempotency-key contracts.

### Inspect current mutation permissions

`GET /api/openservice/permissions/{table}/{sys_id}` reports the caller's current `role`, `record_id`, `update`, `delete` and per-field decisions under `result`. Each decision is `{"allowed":true|false,"reason":"allowed|account_role|record_roles|field_roles"}`. Account-level restrictions take precedence over table policy, then field policy. The record must already be visible; missing/inaccessible records return 404. No query parameters, subject impersonation, writes or record values are accepted/returned.

This reports mutation authorization. Workflow transitions, required/type checks, reference integrity, optimistic revisions and other business validation can still reject an otherwise authorized request. An unchanged restricted field may be submitted without changing its value. Reports confer no rights, and every mutation rechecks current policy.

### Assigned work during offboarding

Administrators can open an account's **View assigned work** link or `GET /api/openservice/users/{user_id}/assignments?page=1`. The API returns `{result:{account,records,page,pages,total}}`; each record contains only `sys_id`, `table`, `number` and `summary`. Pages contain at most 25 rows, ordered by table then ID. `page` defaults to 1 and must be positive, at most six digits and within the result range (an empty list has one page). Other/duplicate query parameters fail with 400.

The directory scans installed `assignment.user_field` metadata across tables and matches stable account IDs, including inactive assignees. Arbitrary legacy text fields and private-access ownership are not treated as work assignments. Other roles receive 403; missing users receive 404. Reads do not reactivate users, alter assignments or change record visibility.

### Transfer selected assignments

`POST /api/openservice/users/{source_id}/assignments/transfer` is administrator-only and accepts exactly `{"new_owner_id":"32-character account ID","record_ids":["record ID"]}`. Select 1–100 unique IDs. The source account may be inactive; the replacement must be a different active agent/administrator and belong to each selected record's configured assignment group. Every record must still be assigned to the source.

The operation uses normal record-update validation and policies, increments record revisions, writes record audit events and one `account.assignments_transferred` security event. Any failure rolls back the entire batch. The 200 result reports `transferred_count`, `record_ids`, `new_owner_id` and `dry_run`; malformed/ineligible input returns 400, policy denial 403, missing records/users 404, and changed/no-longer-assigned records 409. A repeated successful request therefore returns 409, not a second transfer.

Add `?dry_run=true` to validate the same batch through the same update policies, then roll back assignment, revision and audit-row changes. The reported count is the number that would transfer. Preview is repeatable and does not reserve eligibility; subsequent apply validates again. PostgreSQL sequence values may advance during rollback. Omit `dry_run` or use `false` to apply. Other/duplicate query parameters and non-boolean values are rejected.

The account assignment page offers checkboxes, preview, explicit transfer and a replacement username with CSRF protection. A successful preview retains the selection and username on the same page. No account is reactivated and no private/group visibility is changed; use the separate private-access handover when needed. No external notifications are sent.
