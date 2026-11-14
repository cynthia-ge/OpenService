# ServiceNow compatibility ledger

Baseline: empty Git repository. The user expanded the first iteration from one feature into a broad, connected foundation with a polished UI, migration and three-cloud Terraform. Reference inventory: `../skills/open-service/references/servicenow-blueprint.md`.

Target: a bounded subset of the **Australia Table API documentation**, accessed 2026-11-13, not verified Australia Patch 6 runtime parity. [Primary reference](https://www.servicenow.com/docs/r/australia/api-reference/rest-apis/c_TableAPI.html). The generic docs URL returned Brazil; no silent mixing of release contracts is intended. There is no licensed-instance conformance capture.

| ID | Inventory | Capability | Status and boundary |
|---|---|---|---|
| OS-001 | A4(a) | Table CRUD and JSON envelopes | Implemented subset: seven configured tables, unversioned and v1/v2 routes, bounded parameters. API details in `api.md`. |
| OS-002 | A1 | Shared schema and persistence | Partial: configurable registry drives typed validation, forms, references and assignment checks; see configuration.md. No native sys_db_object/sys_dictionary CRUD, table inheritance or reference display-value resolution. |
| OS-003 | A2 | Workspaces, forms, lists | Implemented original UI: overview, record forms, search, paging, dictionary, history and migration. No native UI16/g_form compatibility. |
| OS-004 | A4(d) | Encoded queries | Partial: equality, inequality, contains/not-contains, starts-with, ends-with, IN/NOT IN, empty/not-empty, AND and ordering; database filtering. No full grammar or scripting. |
| OS-005 | A2/A4(e) | Identity and access | Partial: local admin/agent/reader, account administration, manual invitations/recovery, deactivation, groups, Basic/session authentication and CSRF. Verified delivery, OAuth/SSO, field ACLs and domain separation remain missing. |
| OS-006 | A3 | Incident/change/request/problem workflows | Partial: reusable state machines, role-gated transitions, required fields per state and revision checks. No separate approval-request engine or verified vendor state/BR semantics. |
| OS-007 | A1/A4(a) | Audit, journal and files | Partial: persistent record events, internal notes and attachments. Attachment upload/read/delete APIs; no native journal API or full activity semantics. |
| OS-008 | Recommendations | Migration/import/export | Implemented bounded JSON/CSV path with dry-run, source retention, stable IDs and repeat-import skips. See migration limits. |
| OS-009 | A3 | Configuration items/knowledge/groups | Partial: CRUD workspaces, group membership and configurable asset/knowledge authoring examples. No full CMDB/IRE, requester knowledge publishing or discovery. |
| OS-010 | Platform operations | Docker and multi-cloud infrastructure | Docker verified locally; AWS/GCP/Azure validated and mock-plan tested, not deployed. |
| OS-011 | A1/A4(c) | Business rules and Glide runtime | Missing; prioritize a sandboxed, explicitly scoped implementation with source-backed semantics. |
| OS-012 | A4(a) | Aggregate, native attachments, Import Set and Batch APIs | Partial: filtered/grouped count Aggregate API added; attachment upload/read/delete supported; numeric aggregates, attachment updates, Import Set and Batch remain missing. |
| OS-013 | A1/A5 | Flow Designer, SLAs, catalog, update sets | Partial: local configuration packages and configurable workflow policies. Native Flow Designer, SLA timers, service catalogue and ServiceNow update-set compatibility remain missing. |
| OS-014 | Enterprise operation | SSO, fine-grained ACLs, rate limits, workers, migrations | Partial: serialized versioned upgrades, account/session controls, record visibility and persistent authentication-attempt limits. SSO, field ACLs and durable workers remain missing. |
| OS-015 | Migration | Direct connector, attachments, journals, differential sync | Missing; next adoption-focused tranche. |

## Build priorities

The [adoption roadmap](roadmap.md) controls implementation order. OS-005/009/014 identity, membership and access prerequisites, supported by migration and reliable-delivery foundations, take priority over optional API extensions. ADOPT-01 through ADOPT-19 make the previously broad identity, operational, governance and service-workflow gaps explicit; their statuses and milestone gates are separate from compatibility and code coverage. Ready P0 work should be selected first in future development cycles.

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

## OS-025 — Stable attachment metadata ordering

Implemented bounded slice, blueprint A4(a). Australia Attachment API list documentation supports ORDERBY/ORDERBYDESC terms in sysparm_query. OpenService supports its four existing identifier/name filter fields and adds sys_id as a stable tie breaker. The new populated-list regression failed with 400 before implementation. Both database suites verify ascending/descending filenames, duplicate-name stable ties, multi-key table/name ordering, ID and parent-ID ordering, mixed equality filters, sorting before offset/limit, empty pages, rejected unknown/empty/injected/malformed fields, duplicate parameters and authentication. Mutation counts remain unchanged. Final Java 27 Docker/PostgreSQL acceptance verifies packaging, readiness, persistent records and attachment bytes after container replacement, versioned routes, new query/header behavior and audited deletion. Ordering is limited to sys_id, file_name, table_name and table_sys_id. It does not add size/content ordering, full Table query grammar, attachment updates or vendor-instance conformance. Exact acceptance/coverage and QA base are recorded in [cycle 10](iteration-log.md).

## OS-026 — Serialized versioned schema upgrades

Implemented original OpenService persistence slice, blueprint A1 and platform operations, ADOPT-02. Startup serializes schema/bootstrap changes and journals ordered transactional upgrades. Fresh, legacy and previously upgraded databases retain records and credentials; concurrent starts do not race; failed upgrades roll back; unknown or inconsistent histories reject startup. PostgreSQL metadata is scoped to the current schema. This is a local operational contract, not a ServiceNow database/runtime compatibility claim. See [contract, sources and QA evidence](platform-foundations.md).

## OS-027 — Persistent user identities

Implemented local identity slice, ADOPT-03 / blueprint A1/A4(e): migration backfills stable unique IDs, new/bootstrap accounts receive IDs, sessions bind to identities, and the authenticated current-account API exposes only ID/name/role. Credentials, existing create-account responses and historic actor names are preserved. No native sys_user, membership, recovery or record-isolation parity is claimed. See [cycle 2 evidence and sources](platform-foundations.md).

## OS-028 — Persistent browser-session revocation

Implemented local ADOPT-05/08 slice: persistent authentication revisions, next-request invalidation of all caller browser sessions, self-service UI/API for every role, CSRF protection and fresh-login recovery. No individual-session directory, account lockout, SSO or ServiceNow session API parity is claimed. See [cycle 3](platform-foundations.md).

## OS-029 — Administrator account-state lifecycle

Implemented local ADOPT-05 slice: active/inactive migration, administrator UI/API, denied inactive authentication on every supported path, revocation on effective state changes, idempotency, persistent reactivation and concurrent last-active-administrator protection. Identities, passwords and attribution are preserved. Native sys_user, vendor reference-picker rules and delegated roles are not claimed. See [cycle 4](platform-foundations.md).

## OS-030 — Guarded local role administration

Implemented local ADOPT-05 slice: administrator UI/API role changes, transactional final-administrator protection, immediate subsequent-request Basic permissions and browser session revocation. Account state/identity is preserved. Group inheritance, custom roles and native ServiceNow role APIs remain outside this contract. See [cycle 5](platform-foundations.md).

## OS-031 — Authenticated password changes

Implemented local ADOPT-05/08 slice: current-secret verification, bounded password validation, transactional update and all-browser revocation, UI/API for every local role, and concurrent replay rejection. Native password-reset applications, MFA, notification delivery and recovery are not claimed. See [cycle 6](platform-foundations.md).

## OS-032 — Account lifecycle security journal

Implemented local ADOPT-09 slice: transactionally persisted successful account changes with stable attribution, sanitized details, administrator UI/API and a deterministic 100-event window. Native sys_audit_role/identity plugins, login logging and tamper-resistant archives are not claimed. See [cycle 7](platform-foundations.md).

## OS-033 — Absolute browser-session expiration

Implemented local ADOPT-08 slice: server-side authentication age, an exclusive eight-hour cutoff, invalidation and fresh-login recovery, plus explicit servlet idle configuration. Eight hours is a local policy and does not claim ServiceNow property/default parity. See [cycle 8](platform-foundations.md).

## OS-034 — Manual invitation onboarding

Implemented local ADOPT-04 slice: administrator UI/API issuance/revocation, hashed expiring bearer tokens, issuer-revision binding, CSRF-protected recipient password setup, atomic single-use acceptance and sanitized journals. Vendor application-specific invitation semantics, email delivery, address verification and scoped membership are not claimed. See [cycle 9](platform-foundations.md).

## OS-035 — Administrator-assisted account recovery

Implemented local ADOPT-05/08/09 slice: administrator reauthentication, one-time hashed 15-minute links, replacement/revocation, issuer/target revision binding, recipient password selection, atomic single-use consumption and browser/credential revocation with sanitized audit. It does not unlock inactive users or reproduce vendor delegated reset roles, identity-verification integrations or delivery services. See [cycle 10](platform-foundations.md).

## OS-036 — Private account record visibility

Implemented local ADOPT-06 slice: administrator-selected private owner and consistent visibility across existing record surfaces. This is a local extension, not ServiceNow's programmable ACL engine. See [cycle 11](platform-foundations.md) for evidence, scope and exact counters.

## OS-037 — Group membership administration

Implemented local ADOPT-03 slice: administrator roster, add/remove, persistence, audit and cleanup. Australia group concepts are mapped, while group-role inheritance remains missing. See [cycle 12](platform-foundations.md) for acceptance and exact counters.

## OS-038 — Group-restricted record visibility

Implemented local ADOPT-06 slice: administrator policy selection, current-membership enforcement, revocation and fail-closed group deletion. Australia contextual-security concepts inform this behavior; programmable ACLs and inherited roles remain missing. See [cycle 13](platform-foundations.md) for acceptance and exact counters.

## OS-039 — Persistent password-attempt throttling

Implemented local ADOPT-08 slice: browser and Basic limits, durable counters, fixed automatic expiry, concurrent enforcement and bounded storage. Local fixed-window behavior differs from the vendor's configurable lockout scripts. See [cycle 14](platform-foundations.md) for evidence and exact counters.

## OS-040 — Password-authentication event journal

Implemented local ADOPT-09 slice: durable password-verification outcomes, administrator UI/API, secret exclusion and bounded rolling retention. It is distinct from native ServiceNow event names, SSO and full security monitoring. See [cycle 15](platform-foundations.md) for exact evidence.

## OS-041 — Searchable account administration

Implemented local ADOPT-03/05 slice: administrator username/role/state filters, matching counts, paginated UI/API and sanitized output. It does not reproduce vendor encoded queries or delegated user administration. See [cycle 16](platform-foundations.md) for acceptance and exact counters.

## OS-042 — Journal history navigation and export

Implemented local ADOPT-09 slice: bounded cursor pages, Older/Latest browser navigation and permission-checked selected-page JSON downloads for account and authentication journals. It is not native sys_audit or Log Export Service parity. See [cycle 17](platform-foundations.md) for exact evidence.

## OS-043 — Accurate invitation availability

Implemented local ADOPT-04/05 correction: derived invalidated/unavailable states match issuer and username eligibility; public redemption, replacement guidance and revocation agree. Product-specific vendor magic-link documentation informs lifecycle handling without implying a universal invite API. See [cycle 18](platform-foundations.md).

## OS-044 — Account group-access review and removal

Implemented local ADOPT-03/05/06/09 slice: per-account group review and atomic bulk membership withdrawal with security audit and request-time enforcement. Vendor group-removal workflows inform this local extension; inherited group roles and complete deprovisioning are not implemented. See [cycle 19](platform-foundations.md).

## OS-045 — Private-record access handover

Implemented local ADOPT-05/06/09 slice: atomic administrative handover of live private-record access, including parent-governed attachments, with rollback/concurrency/security evidence. Original record attribution and business assignments remain unchanged. Deleted-record audit policies and other visibility modes are excluded. This is a local extension, not vendor offboarding or task reassignment parity. See [cycle 20](platform-foundations.md).

## Reusable application configuration — 2026-11-14

Implemented bounded slices in OS-PARITY-01/02/09, with reusable case/asset/knowledge examples for OS-PARITY-04/05. [Contract and primary references](configuration.md). Existing API paths and legacy unversioned definitions retain their contracts. Configured tables enforce types and relationships through real entry points. Promotion uses destination revisions and validates affected persisted records atomically; no arbitrary code or vendor package execution is enabled.

Remaining shortlist: typed metrics (OS-PARITY-07); durable delivery/connectors (03 and ADOPT-07); separate approvals/SLA timers (02); requester/field controls (08); discovery/impact (05); grounded, governed AI (06); operational restore and failover evidence (10). Whole-area statuses remain partial or missing. Exact validation results are recorded in validation.md.

## Typed numeric reporting — 2026-11-14

OS-PARITY-07 now includes complete-selection sum/minimum/maximum/average reports for configured integer/decimal fields, optional grouping and encoded filtering, record-level visibility enforcement and a browser workspace. [Contract and evidence](analytics.md). Results use exact decimal accumulation and documented DECIMAL128 averages. SQLite/PostgreSQL tests explicitly cover 10,001 rows and private records; there is no Table API pagination shortcut. This is a local API extension; native numeric Aggregate API compatibility, saved/time-series reports and process mining remain open.

## Bounded recovery verification — 2026-11-14

OS-PARITY-10 / ADOPT-15: [restore-drill.sh](../scripts/restore-drill.sh) successfully restores a quiesced PostgreSQL dump to a separate database volume and verifies state through the rebuilt non-root application. [Scope and instructions](recovery.md). Production recovery objectives, online recovery, cross-version upgrades and high availability remain unverified.
