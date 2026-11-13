# ServiceNow compatibility ledger

Baseline: empty Git repository. The user expanded the first iteration from one feature into a broad, connected foundation with a polished UI, migration and three-cloud Terraform. Reference inventory: `../skills/open-service/references/servicenow-blueprint.md`.

Target: a bounded subset of the **Australia Table API documentation**, accessed 2026-11-13, not verified Australia Patch 6 runtime parity. [Primary reference](https://www.servicenow.com/docs/r/australia/api-reference/rest-apis/c_TableAPI.html). The generic docs URL returned Brazil; no silent mixing of release contracts is intended. There is no licensed-instance conformance capture.

| ID | Inventory | Capability | Status and boundary |
|---|---|---|---|
| OS-001 | A4(a) | Table CRUD and JSON envelopes | Implemented subset: seven configured tables, unversioned and v1/v2 routes, bounded parameters. API details in `api.md`. |
| OS-002 | A1 | Shared schema and persistence | Partial: registry drives validation and forms. No native sys_db_object/sys_dictionary CRUD, table inheritance or typed reference resolution. |
| OS-003 | A2 | Workspaces, forms, lists | Implemented original UI: overview, record forms, search, paging, dictionary, history and migration. No native UI16/g_form compatibility. |
| OS-004 | A4(d) | Encoded queries | Partial: equality, inequality, contains/not-contains, starts-with, ends-with, IN/NOT IN, empty/not-empty, AND and ordering; database filtering. No full grammar or scripting. |
| OS-005 | A2/A4(e) | Identity and access | Partial: local admin/agent/reader, administrator account-onboarding workspace, Basic and session auth, CSRF. No password reset/deactivation, OAuth/SSO, field ACLs or domain separation. |
| OS-006 | A3 | Incident/change/request/problem workflows | Partial: local state machines, approval by admin, revision checks. Not verified vendor state/BR semantics. |
| OS-007 | A1/A4(a) | Audit, journal and files | Partial: persistent record events, internal notes and attachments. Attachment upload/read/delete APIs; no native journal API or full activity semantics. |
| OS-008 | Recommendations | Migration/import/export | Implemented bounded JSON/CSV path with dry-run, source retention, stable IDs and repeat-import skips. See migration limits. |
| OS-009 | A3 | Configuration items/knowledge/groups | Partial: real CRUD workspaces; not full CMDB/IRE, knowledge publishing or group membership. |
| OS-010 | Platform operations | Docker and multi-cloud infrastructure | Docker verified locally; AWS/GCP/Azure validated and mock-plan tested, not deployed. |
| OS-011 | A1/A4(c) | Business rules and Glide runtime | Missing; prioritize a sandboxed, explicitly scoped implementation with source-backed semantics. |
| OS-012 | A4(a) | Aggregate, native attachments, Import Set and Batch APIs | Partial: filtered/grouped count Aggregate API added; attachment upload/read/delete supported; numeric aggregates, attachment updates, Import Set and Batch remain missing. |
| OS-013 | A1/A5 | Flow Designer, SLAs, catalog, update sets | Missing; no placeholder interfaces claim support. |
| OS-014 | Enterprise operation | SSO, fine-grained ACLs, rate limits, workers, migrations | Missing; required before production adoption. |
| OS-015 | Migration | Direct connector, attachments, journals, differential sync | Missing; next adoption-focused tranche. |

## Acceptance gates

- Real CRUD persists and is shared by UI/API; all seven record types render and edit.
- Readers cannot mutate; agents cannot approve changes or delete; session mutations require CSRF.
- Invalid bodies, fields, parameters and transitions fail; stale revisions conflict.
- Bound database filters preserve literal wildcard characters and resist query injection.
- Import preview writes nothing; errors prevent partial writes; retries skip existing records; original source exports round-trip.
- Attachments persist and download as files; deletion removes files but preserves audit events.
- The Maven suite runs real HTTP tests against SQLite and disposable PostgreSQL, plus Chromium browser journeys.
- Coverage includes every production Java class with exactly 100% executable lines, methods and branch outcomes. Templates/CSS are checked through rendered views and browser journeys; JaCoCo does not measure visual correctness or Terraform.
- Terraform uses provider validation and mock-provider plan assertions for each target; no claim of a live cloud deployment.

Reproduce results with the commands in the README. Record the final measured counts and checks in `validation.md`. Future iterations should close one ledger gap at a time unless the user expands scope again.

## Count aggregation iteration — 13 November 2026

[Primary Aggregate API reference](https://www.servicenow.com/docs/r/api-reference/rest-apis/c_AggregateAPI.html?contentId=z1y5kSDzdmS5hEs40YMlSQ), Australia documentation updated 2026-04-21. Implements stored-value counts over the full filtered selection, optional groupings and COUNT/field ordering. Numeric aggregate functions and display-value resolution are explicitly rejected. Baseline HEAD: `4360f0a868633f7b7dba9b3b2e415da1d5bcf4c5`.

## OS-004 continuation — membership filters, 13 November 2026

Baseline HEAD 512a471, main, clean tree. Blueprint A4(d). Selected the missing IN/NOT IN subset over native attachments (OS-012, missing) and upgrade migrations (OS-014, missing), extending the common database selection layer used by table and aggregate reads.

Contract: Australia encoded-query operator documentation, dated 2026-03-12, retrieved 2026-11-13 from the [official Australia documentation source](https://raw.githubusercontent.com/ServiceNow/ServiceNowDocs/australia/markdown/platform-user-interface/r_OpAvailableFiltersQueries.md). The generic documentation route now returns Brazil; Australia source is the selected evidence. Literal stored-value comma-list matching is implemented; local limits and empty/null behavior are specified in api.md. No vendor-instance conformance claim.

Implementation: `Service.java`; Java contract and workflow tests run on SQLite and PostgreSQL. Current measurements are in [validation](validation.md).

## OS-005 continuation — local account onboarding, 13 November 2026

Baseline HEAD `f4a2d3e`, branch `main`, clean tree; baseline suite: 88 passing tests. Blueprint A2 and A4(e). Account creation existed only through an administrator API, making a multi-person browser evaluation depend on API tooling. Selected a complete administrator account-list/create workflow. Native Attachment API (OS-012, missing), versioned schema upgrades (OS-014, missing) and real assignment/group membership (OS-009, missing) remain candidates for later iterations.

Primary contract evidence, retrieved 2026-11-13:

- [Create a user, official Australia source](https://raw.githubusercontent.com/ServiceNow/ServiceNowDocs/australia/markdown/platform-administration/user-administration/t_CreateAUser.md), updated 2026-03-12: privileged user administration, unique usernames and a create/list workflow.
- [Assign a role to a user, official Australia source](https://raw.githubusercontent.com/ServiceNow/ServiceNowDocs/australia/markdown/platform-administration/user-administration/t_AssignARoleToAUser.md), updated 2026-09-10: privileged role assignment and additional restrictions on granting administrator access.

The generic documentation route now returns Brazil. The release-specific Australia source above supports the conceptual workflow; this implementation is an original OpenService local-account contract, **not** `sys_user` API or vendor-instance conformance. OpenService requires its own `admin` role and assigns one of three local roles at creation. It does not reproduce `user_admin`, role inheritance, profile fields or the vendor password-setting workflow.

Implemented slice: `GET/POST /admin/accounts`, administrator-only navigation, 25-account pages, shared API/browser validation and password hashing, reader default, inline errors, duplicate protection and immediate sign-in. Detailed input/status/serialization boundaries are in [API scope](api.md#local-accounts). The users table is unchanged. Passwords now have an explicit maximum of 1024 characters on both creation entry points.

Acceptance checklist:

- [x] Administrator lists names/roles, creates a persisted account and sees confirmation; the account can sign in immediately.
- [x] Reader/agent cannot access account administration or elevate privileges; missing/invalid session CSRF cannot create an account.
- [x] Both creation paths reject invalid types, names, passwords and roles; maximum accepted lengths work; passwords/hashes are never returned in the listing or error form.
- [x] Duplicates preserve existing credentials/role; an actual unique-constraint failure rolls back and leaves the database session usable.
- [x] Pagination is bounded and ordered; invalid, repeated, unknown and out-of-range parameters fail explicitly.
- [x] Desktop/mobile Chromium verifies the complete form and subsequent agent/reader access, including absence of document overflow and browser script errors.

Implementation: `Service.java`, `WebController.java`, Thymeleaf templates and CSS. Java contract/workflow tests and Chromium exercise the acceptance checklist. Current exact measurements are in [validation](validation.md).

Only the local onboarding slice is implemented. OS-005 remains partial: no account editing, password reset, deactivation, SSO, inherited roles, customer separation or field/record ACLs. The evaluator walkthrough records these boundaries; this does not establish enterprise production readiness.

## Java 27 migration and preserved attachment work

All production logic now uses Java 27 and Spring Boot 4.1.2. Persisted records, identities, password hashes, events and attachment bytes are retained. The interrupted attachment work is included: authenticated metadata listing with exact `table_name`/`table_sys_id` filters, pagination, metadata lookup and file downloads. Orphan attachments are hidden; parent deletion removes attachment data. Subsequent cycles add binary/multipart uploads and deletion; attachment updates remain outside this subset. The laptop sidebar scrolling repair is preserved and tested through Chromium. Current verification is in [validation](validation.md).

## OS-016 — Binary attachment upload

Implemented bounded slice, blueprint A1/A4(a). Australia Attachment API POST /file (primary source linked above): required parent/filename query fields, raw file body, 201 metadata and Location. This route was previously GET-only and the new positive HTTP regression failed with 405 before implementation. Acceptance passed on SQLite and PostgreSQL: agent/admin uploads, reader/anonymous rejection, session CSRF, exact binary/media-type round-trip, empty and 5 MiB files, oversized rejection, invalid/repeated/unknown/blank parameters, missing/wrong-table parents, safe filenames, atomic validation, unchanged parent revisions, audit events, and additive legacy-schema upgrade preserving bytes. Form-encoded bytes remain opaque; multipart forms are explicitly rejected on the raw endpoint. No multipart upload, attachment updates, antivirus or granular record ACL parity is claimed in this slice. Schema upgrade requires one application instance; legacy/browser uploads retain the octet-stream default. Exact acceptance/coverage and QA base are recorded in [cycle 1](iteration-log.md).

## OS-017 — Attachment deletion

Implemented bounded slice, blueprint A1/A4(a). Australia Attachment API DELETE resource: an empty 204 response and 404 for missing attachments. The route previously rejected DELETE with 405; the new regression failed before implementation. OpenService restricts deletion to local administrators. Acceptance passed on both databases: administrator session with CSRF deletes metadata and bytes, returns an empty 204, preserves the parent/revision and other attachments, and records actor, ID and filename in audit history. Missing/repeated deletion returns 404; unauthenticated, reader, agent, missing-CSRF, query-parameter and wrong-route cases are rejected. The role policy is local, not vendor ACL parity. No attachment update, multipart upload or antivirus is added in this slice. Exact acceptance/coverage and QA base are recorded in [cycle 2](iteration-log.md).

## OS-018 — Multipart attachment upload

Implemented bounded slice, blueprint A1/A4(a). Australia Attachment API POST /upload: parent fields in the multipart body, file-part Content-Type, 201 metadata and Location. The official example uses uploadFile. The positive regression failed with 405 before implementation; a second regression using the documented part name failed with 400 before the QA repair. Acceptance passed on SQLite/PostgreSQL for agent/admin and session-CSRF uploads, literal bytes/media type, empty and maximum-size files, metadata/Location, audit persistence, oversized files, forbidden roles, missing/duplicate/extra fields, missing/multiple/wrong file parts, blank/null filenames, absent part media types, query-string rejection and invalid parent/table pairing. Binary and multipart APIs use the same validation and persistence method. Exactly one uploadFile part and the existing local metadata subset are supported. Attachment updates, malware scanning and native field/record ACL parity remain outside this slice. Exact acceptance/coverage and QA base are recorded in [cycle 3](iteration-log.md).

## OS-019 — Versioned Table API routes

Implemented bounded slice, blueprint A4(a). Australia Table API documents /api/now/{api_version}/table paths and examples v1/v2. Its collection contract distinguishes v1 empty results (404) from v2 (200 with an empty result array). The new positive regression returned 404 on creation before implementation. Both database suites verify v1/v2 creation, shared unversioned records, field projection, ETags, update/PUT, stale-revision conflict, role restrictions, deletion, invalid/repeated parameters, empty/filter-empty/out-of-range pages, unsupported versions and version-preserving Location/Link headers. Only the existing bounded Table API subset is versioned. Other historical vendor-version differences, versioned Attachment/Aggregate APIs and complete vendor parity are not claimed. Exact acceptance/coverage and QA base are recorded in [cycle 4](iteration-log.md).

## OS-020 — Empty and nonempty encoded-query predicates

Implemented bounded slice, blueprint A4(d). Australia operator reference defines ISEMPTY and ISNOTEMPTY as operand-free absence/presence tests. The first HTTP regression failed with unsupported-query 400 before implementation. OpenService specifies missing JSON keys, JSON null and empty strings as empty; whitespace is a value. SQLite/PostgreSQL acceptance covers missing/null/empty/whitespace/nonempty records, both operators, AND/order composition, filtering before pagination, full-selection aggregate counts, contradictory predicates and rejection of operands, unknown fields and OR syntax through both APIs. Stored-value predicates only; no display-value resolution, reference traversal, OR grammar or native scripting is added. Exact acceptance/coverage and QA base are recorded in [cycle 5](iteration-log.md).

## OS-021 — Literal ENDSWITH filtering

Implemented bounded slice, blueprint A4(d). Australia string operator reference defines ENDSWITH as matching a stored suffix. The new real HTTP regression failed with unsupported-query 400 before implementation. OpenService reuses bound, escaped LIKE-family SQL rather than interpreting user wildcard characters. SQLite/PostgreSQL Table and Aggregate cases verify ordinary suffixes versus prefixes/middle text, percent/underscore/backslash literals, injection-shaped quote strings, Unicode, empty operands, null exclusion, system fields, AND/order composition, pagination, counts and invalid fields/operator rejection. Existing database LIKE comparison policy is retained: SQLite default ASCII-insensitive behavior differs from PostgreSQL case-sensitive behavior. No vendor collation, reference/display-value or full encoded-query parity is claimed. Exact acceptance/coverage and QA base are recorded in [cycle 6](iteration-log.md).

## OS-022 — Literal NOT LIKE filtering

Implemented bounded slice, blueprint A4(d). Australia operator reference spells the negative substring operator NOT LIKE with a space. The positive HTTP regression failed with unsupported-query 400 before implementation. OpenService binds and escapes a literal substring, using SQL NOT LIKE. Both database suites verify exclusion of ordinary, wildcard/backslash, quote/injection-shaped and Unicode substrings; absent/empty operands; empty stored strings and null exclusion; AND with ISNOTEMPTY; ordering/pagination; matching full-selection aggregate counts; and unknown fields/unsupported spelling. Existing database case-comparison differences and stored-value-only scope remain documented. No display-value resolution or complete vendor encoded-query parity is claimed. Exact acceptance/coverage and QA base are recorded in [cycle 7](iteration-log.md).

## OS-023 — Optional pagination-header suppression

Implemented bounded slice, blueprint A4(a). Australia Table API documents sysparm_suppress_pagination_header, default false, with true suppressing Link. The new HTTP regression failed with unsupported-parameter 400 before implementation. OpenService accepts strict lowercase boolean values. Both database suites exercise unversioned/v1/v2 collection routes, true/false/omitted flags, unchanged rows/projection/count, all pagination relations, preservation of the explicit false flag in links, empty results and invalid/repeated/single-record parameter rejection. This flag suppresses links only; avoiding the count query is a separate candidate. Existing local pagination/error semantics and bounded Table subset remain in force. Exact acceptance/coverage and QA base are recorded in [cycle 8](iteration-log.md).

## OS-024 — Table reads without a total-count query

Implemented bounded slice, blueprint A4(a). Australia Table API sysparm_no_count defaults false; true avoids the SELECT COUNT(*) query. The new HTTP regression failed with unsupported-parameter 400 before implementation. OpenService uses a bounded extra row for next-page detection and omits unknown total/last-page metadata. SQLite/PostgreSQL tests inspect prepared statements on real JDBC connections: no-count executes the selection without COUNT, while false executes COUNT and returns the total. HTTP cases cover unversioned/v1/v2 paging, first/previous/next links, following next URLs, no last/total headers, projection, exact page size, final/empty/out-of-range pages, suppression composition, false/default behavior and invalid/repeated/resource-only parameters. First/previous/next without last is an explicit local policy when totals are unknown. Count omission does not remove filtering or large-offset costs. Other Table API limitations remain unchanged. Exact acceptance/coverage and QA base are recorded in [cycle 9](iteration-log.md).
